package com.pppp.zhimesh.common.workflow.node.knowledgeretrieval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.rag.CompositeRag;
import com.pppp.zhimesh.common.rag.GraphRagContext;
import com.pppp.zhimesh.common.rag.bm25.Bm25RagContext;
import com.pppp.zhimesh.common.rag.bm25.Bm25ReadinessService;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.vo.ChatModelBuilderProperties;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import com.pppp.zhimesh.common.vo.RetrieverWrapper;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.metrics.KnowledgeRetrievalMetrics;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;

/**
 * 【节点】知识抽取 <br/>
 * 节点内容固定格式：KnowledgeRetrievalNodeConfig
 */
@Slf4j
public class KnowledgeRetrievalNode extends AbstractWfNode {

    public KnowledgeRetrievalNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        state.setMetrics(new KnowledgeRetrievalMetrics());
    }

    /**
     * nodeConfig格式：<br/>
     * {"knowledge_base_uuid": "","score":0.6,"top_n":3,"is_strict": false, "default_response":"数据不存在~~~"}<br/>
     */
    @Override
    public NodeProcessResult onProcess() {
        ObjectNode objectConfig = node.getNodeConfig();
        if (objectConfig.isEmpty()) {
            throw new BaseException(A_WF_NODE_CONFIG_NOT_FOUND);
        }
        KnowledgeRetrievalNodeConfig nodeConfigObj = JsonUtil.fromJson(objectConfig, KnowledgeRetrievalNodeConfig.class);
        if (null == nodeConfigObj || StringUtils.isBlank(nodeConfigObj.getKnowledgeBaseUuid())) {
            log.warn("Knowledge retrieval node configuration not found");
            throw new BaseException(A_WF_NODE_CONFIG_ERROR);
        }
        String kbUuid = nodeConfigObj.getKnowledgeBaseUuid();
        log.info("KnowledgeRetrievalNode config:{}", nodeConfigObj);
        String textInput = getFirstInputText();
        if (StringUtils.isBlank(textInput)) {
            log.warn("Input content is empty");
            return NodeProcessResult
                    .builder()
                    .content(List.of(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", "")))
                    .build();
        }

        KnowledgeBase knowledgeBase = requireAuthorizedKnowledgeBase(kbUuid);
        Set<String> authorizedKbUuids = Set.of(kbUuid);
        Set<RetrievalRoute> routes = availableRoutes(
                authorizedKbUuids,
                GraphRagContext.get(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE) != null,
                Bm25RagContext.get(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE) != null,
                SpringUtil.getBean(Bm25ReadinessService.class));
        ChatModel graphQueryModel = null;
        if (routes.contains(RetrievalRoute.GRAPH)) {
            try {
                graphQueryModel = LLMContext
                        .getServiceById(knowledgeBase.getIngestModelId(), true)
                        .buildChatLLM(ChatModelBuilderProperties.builder()
                                .temperature(knowledgeBase.getQueryLlmTemperature())
                                .build());
            } catch (RuntimeException exception) {
                // A graph model is an optional branch for this explicit workflow node.
                // Preserve the former vector behavior when it cannot be constructed.
                log.warn("Unable to create graph query model for workflow knowledge retrieval; "
                                + "continuing without graph, kbUuid:{}, reason:{}",
                        kbUuid, exception.getMessage());
                EnumSet<RetrievalRoute> fallbackRoutes = EnumSet.copyOf(routes);
                fallbackRoutes.remove(RetrievalRoute.GRAPH);
                routes = Set.copyOf(fallbackRoutes);
            }
        }
        RetrieverCreateParam kbRetrieveParam = RetrieverCreateParam.builder()
                .retrievalRoutes(routes)
                .knowledgeBaseUuids(authorizedKbUuids)
                .chatModel(graphQueryModel)
                .filter(new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid))
                .maxResults(nodeConfigObj.getTopN())
                .minScore(nodeConfigObj.getScore())
                .breakIfSearchMissed(Boolean.TRUE.equals(nodeConfigObj.getIsStrict()))
                .graphHopDepth(knowledgeBase.getGraphHopDepth() == null
                        ? 1 : knowledgeBase.getGraphHopDepth())
                // CompositeRag's final packing limit must retain the workflow's
                // established top_n contract after multiple branches are fused.
                .rerankTopN(nodeConfigObj.getTopN())
                .build();
        List<RetrieverWrapper> retrievers = new CompositeRag(
                ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE).createRetriever(kbRetrieveParam);
        StringBuilder resp = new StringBuilder();
        try {
            List<Content> contents = retrievers.get(0).getRetriever().retrieve(Query.from(textInput));
            //记录检索指标 | Record retrieval metrics
            ((KnowledgeRetrievalMetrics) state.getMetrics()).setRetrievalCount(contents.size());
            for (Content content : contents) {
                resp.append(content.textSegment().text());
            }
        } catch (BaseException e) {
            if (B_BREAK_SEARCH.getCode().equals(e.getCode())) {
                log.warn("Search interrupted by user");
            } else {
                log.error("KnowledgeRetrievalNode retrieve error", e);
                throw e;
            }
        }
        String respText = resp.toString();
        if (StringUtils.isBlank(respText) && StringUtils.isNotBlank(nodeConfigObj.getDefaultResponse())) {
            respText = nodeConfigObj.getDefaultResponse();
        }
        return NodeProcessResult
                .builder()
                .content(List.of(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", respText)))
                .build();
    }

    private KnowledgeBase requireAuthorizedKnowledgeBase(String kbUuid) {
        KnowledgeBase knowledgeBase = SpringUtil.getBean(KnowledgeBaseService.class).getOrThrow(kbUuid);
        if (!canRead(knowledgeBase, wfState.getUser())) {
            // Match the knowledge-base read API and do not disclose private or
            // system knowledge-base existence through a stored workflow.
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        return knowledgeBase;
    }

    static boolean canRead(KnowledgeBase knowledgeBase, User user) {
        if (knowledgeBase == null || Boolean.TRUE.equals(knowledgeBase.getIsSystem())) {
            return false;
        }
        if (Boolean.TRUE.equals(knowledgeBase.getIsPublic())) {
            return true;
        }
        return user != null && user.getId() != null
                && user.getId().equals(knowledgeBase.getOwnerId());
    }

    static Set<RetrievalRoute> availableRoutes(Set<String> authorizedKbUuids,
                                               boolean graphConfigured,
                                               boolean bm25Configured,
                                               Bm25ReadinessService readinessService) {
        EnumSet<RetrievalRoute> routes = EnumSet.of(RetrievalRoute.VECTOR);
        if (graphConfigured) {
            routes.add(RetrievalRoute.GRAPH);
        }
        if (bm25Configured && readinessService != null
                && authorizedKbUuids != null && !authorizedKbUuids.isEmpty()) {
            try {
                if (readinessService.readyKnowledgeBases(authorizedKbUuids)
                        .containsAll(authorizedKbUuids)) {
                    routes.add(RetrievalRoute.BM25);
                }
            } catch (RuntimeException exception) {
                // Normal workflow traffic keeps working through the established
                // routes when the readiness check or the full-text index is down.
                log.warn("Unable to check BM25 readiness for workflow knowledge retrieval; "
                                + "continuing with vector/graph, reason:{}",
                        exception.getMessage());
            }
        }
        return Set.copyOf(routes);
    }
}
