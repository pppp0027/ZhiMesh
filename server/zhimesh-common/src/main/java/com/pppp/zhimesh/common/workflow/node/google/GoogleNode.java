package com.pppp.zhimesh.common.workflow.node.google;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.dto.SearchReturn;
import com.pppp.zhimesh.common.dto.SearchReturnWebPage;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.searchengine.SearchEngineServiceContext;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.WorkflowUtil;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.metrics.SearchMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;

/**
 * 以摘要模式进行搜索
 */
@Slf4j
public class GoogleNode extends AbstractWfNode {

    public GoogleNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        state.setMetrics(new SearchMetrics());
    }

    @Override
    protected NodeProcessResult onProcess() {
        ObjectNode objectConfig = node.getNodeConfig();
        if (objectConfig.isEmpty()) {
            throw new BaseException(A_WF_NODE_CONFIG_NOT_FOUND);
        }
        GoogleNodeConfig nodeConfigObj = JsonUtil.fromJson(objectConfig, GoogleNodeConfig.class);
        if (null == nodeConfigObj) {
            log.warn("Google search node configuration not found");
            throw new BaseException(A_WF_NODE_CONFIG_ERROR);
        }
        log.info("GoogleNode config:{}", nodeConfigObj);
        String query;
        if (StringUtils.isNotBlank(nodeConfigObj.getQuery())) {
            query = WorkflowUtil.renderTemplate(nodeConfigObj.getQuery(), state.getInputs());
        } else {
            query = getFirstInputText();
        }
        if (StringUtils.isBlank(query)) {
            log.error("Search query cannot be empty");
            throw new BaseException(A_SEARCH_QUERY_IS_EMPTY);
        }
        log.info("GoogleNode query:{}", query);
        SearchReturn searchResult = SearchEngineServiceContext.getService(ZhiMeshConstant.SearchEngineName.GOOGLE).search(query, nodeConfigObj.getCountry(), nodeConfigObj.getLanguage(), nodeConfigObj.getTopN());
        if (StringUtils.isNotBlank(searchResult.getErrorMessage())) {
            log.error("Google search error:{}", searchResult.getErrorMessage());
        }
        //记录搜索指标 | Record search metrics
        List<SearchReturnWebPage> items = searchResult.getItems() != null ? searchResult.getItems() : Collections.emptyList();
        ((SearchMetrics) state.getMetrics()).setSearchResultCount(items.size());
        StringBuilder respText = new StringBuilder();
        for (SearchReturnWebPage searchReturn : items) {
            respText.append(searchReturn.getSnippet());
        }
        return NodeProcessResult
                .builder()
                .content(List.of(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", respText.toString())))
                .build();
    }
}
