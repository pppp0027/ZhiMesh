package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.cosntant.RedisKeyConstant;
import com.pppp.zhimesh.common.dto.KbEditReq;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.dto.KbQaDto;
import com.pppp.zhimesh.common.dto.KbSearchReq;
import com.pppp.zhimesh.common.dto.KbUploadResult;
import com.pppp.zhimesh.common.dto.RefGraphDto;
import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationAskReq;
import com.pppp.zhimesh.common.dto.evaluation.RagEvaluationAskResp;
import com.pppp.zhimesh.common.dto.evaluation.RetrievalMode;
import com.pppp.zhimesh.common.entity.*;
import com.pppp.zhimesh.common.enums.LLMCallRecordSourceType;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.file.FileOperatorContext;
import com.pppp.zhimesh.common.helper.LLMContext;
import com.pppp.zhimesh.common.helper.SseManager;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseMapper;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryService;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryTurnCoordinator;
import com.pppp.zhimesh.common.memory.shortterm.ShortTermMemoryWindow;
import com.pppp.zhimesh.common.rag.*;
import com.pppp.zhimesh.common.rag.bm25.Bm25ReadinessService;
import com.pppp.zhimesh.common.rag.profile.KnowledgeRouteProfileCoordinator;
import com.pppp.zhimesh.common.rag.intent.ContextualQueryRewriter;
import com.pppp.zhimesh.common.rag.intent.IntentRoutingService;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopeDecision;
import com.pppp.zhimesh.common.rag.intent.KnowledgeScopePreflightGate;
import com.pppp.zhimesh.common.rag.intent.KnowledgeSourceType;
import com.pppp.zhimesh.common.rag.intent.RetrievalPlan;
import com.pppp.zhimesh.common.rag.intent.RetrievalRoute;
import com.pppp.zhimesh.common.rag.intent.RuleIntentRecognizer;
import com.pppp.zhimesh.common.service.embedding.IKnowledgeEmbeddingService;
import com.pppp.zhimesh.common.util.*;
import com.pppp.zhimesh.common.util.NumberUtil;
import com.pppp.zhimesh.common.vo.*;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.SSE_TIMEOUT;
import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.SysConfigKey.QUOTA_BY_QA_ASK_DAILY;
import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_STATISTIC_RECALCULATE_SIGNAL;
import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.KB_QA_PROCESS_LOCK;
import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.USER_INDEXING;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;
import static com.pppp.zhimesh.common.util.LocalDateTimeUtil.PATTERN_YYYY_MM_DD;

@Slf4j
@Service
public class KnowledgeBaseService extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> {

    private static final String NO_KNOWLEDGE_EVIDENCE_ANSWER = "根据当前知识库无法确定。";

    @Lazy
    @Resource
    private KnowledgeBaseService self;

    @Resource
    private AsyncTaskExecutor chatExecutor;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KnowledgeBaseItemService knowledgeBaseItemService;

    @Resource
    private KnowledgeBaseQaService knowledgeBaseQaRecordService;

    @Resource
    private KnowledgeBaseStarService knowledgeBaseStarRecordService;

    @Resource
    private FileService fileService;

    @Resource
    private SseManager sseManager;

    @Resource
    private UserDayCostService userDayCostService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private ModelPlatformService modelPlatformService;

    @Resource
    private IKnowledgeEmbeddingService embeddingService;

    @Resource
    private LLMCallRecordService llmCallRecordService;

    @Resource
    private ZhiMeshProperties adiProperties;

    @Resource
    private ShortTermMemoryService shortTermMemoryService;

    @Resource
    private ShortTermMemoryTurnCoordinator shortTermMemoryTurnCoordinator;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private IntentRoutingService intentRoutingService;

    @Resource
    private KnowledgeScopePreflightGate knowledgeScopePreflightGate;

    @Resource
    private RuleIntentRecognizer ruleIntentRecognizer;

    @Resource
    private ContextualQueryRewriter contextualQueryRewriter;

    @Resource
    private Bm25ReadinessService bm25ReadinessService;

    @Resource
    private KnowledgeRouteProfileCoordinator routeProfileCoordinator;

    @Transactional
    public KnowledgeBase saveOrUpdate(KbEditReq kbEditReq) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        BeanUtils.copyProperties(kbEditReq, knowledgeBase, "id", "uuid", "ingestTokenizer", "ingestEmbeddingModel", "isSystem", "isEnabled");
        if (null != kbEditReq.getIngestModelId() && kbEditReq.getIngestModelId() > 0) {
            knowledgeBase.setIngestModelName(aiModelService.getByIdOrThrow(kbEditReq.getIngestModelId()).getName());
        } else {
            //????????????????????????????????????LLM????????????????????????????????????
            LLMContext.getFirstEnableAndFree().ifPresent(llmService -> {
                knowledgeBase.setIngestModelName(llmService.getAiModel().getName());
                knowledgeBase.setIngestModelId(llmService.getAiModel().getId());
            });
        }
        if (StringUtils.isNotBlank(kbEditReq.getIngestTokenEstimator()) && ZhiMeshConstant.TokenEstimator.ALL.contains(kbEditReq.getIngestTokenEstimator())) {
            knowledgeBase.setIngestTokenEstimator(kbEditReq.getIngestTokenEstimator());
        }
        if (null == kbEditReq.getId() || kbEditReq.getId() < 1) {
            User user = ThreadContext.getCurrentUser();
            knowledgeBase.setUuid(UuidUtil.createShort());
            knowledgeBase.setOwnerId(user.getId());
            knowledgeBase.setOwnerUuid(user.getUuid());
            knowledgeBase.setOwnerName(user.getName());
            knowledgeBase.setIsSystem(Boolean.TRUE.equals(user.getIsAdmin()) && Boolean.TRUE.equals(kbEditReq.getIsSystem()));
            knowledgeBase.setIsEnabled(kbEditReq.getIsEnabled() == null || kbEditReq.getIsEnabled());
            if (Boolean.TRUE.equals(knowledgeBase.getIsSystem())) {
                // System KBs are private by design; role binding is the access grant.
                knowledgeBase.setIsPublic(false);
            }
            baseMapper.insert(knowledgeBase);
        } else {
            checkWritePrivilege(kbEditReq.getId(), null);
            knowledgeBase.setId(kbEditReq.getId());
            KnowledgeBase existing = getById(kbEditReq.getId());
            knowledgeBase.setUuid(existing.getUuid());
            User user = ThreadContext.getCurrentUser();
            if (Boolean.TRUE.equals(user.getIsAdmin())) {
                knowledgeBase.setIsSystem(kbEditReq.getIsSystem() == null ? existing.getIsSystem() : kbEditReq.getIsSystem());
                knowledgeBase.setIsEnabled(kbEditReq.getIsEnabled() == null ? existing.getIsEnabled() : kbEditReq.getIsEnabled());
            } else {
                knowledgeBase.setIsSystem(existing.getIsSystem());
                knowledgeBase.setIsEnabled(existing.getIsEnabled());
            }
            if (Boolean.TRUE.equals(knowledgeBase.getIsSystem())) {
                knowledgeBase.setIsPublic(false);
            }
            baseMapper.updateById(knowledgeBase);
        }
        return knowledgeBase;
    }

    /**
     * Save a knowledge base from the user-facing workspace. System knowledge
     * bases are an administration-only resource and must not be created,
     * converted, or edited through the user API, even when the signed-in user
     * also happens to be an administrator.
     */
    @Transactional
    public KnowledgeBase saveOrUpdateForUserWorkspace(KbEditReq kbEditReq) {
        if (kbEditReq.getId() != null && kbEditReq.getId() > 0) {
            KnowledgeBase existing = getById(kbEditReq.getId());
            if (existing == null || Boolean.TRUE.equals(existing.getIsSystem())) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
            checkUserWorkspaceWritePrivilege(existing.getUuid());
        }
        kbEditReq.setIsSystem(false);
        return saveOrUpdate(kbEditReq);
    }

    public List<KbUploadResult> uploadDocs(String kbUuid, Boolean indexAfterUpload, MultipartFile[] docs, List<String> indexTypes) {
        if (ArrayUtils.isEmpty(docs)) {
            return Collections.emptyList();
        }
        checkWritePrivilege(null, kbUuid);
        List<KbUploadResult> result = new ArrayList<>();
        KnowledgeBase knowledgeBase = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBase::getUuid, kbUuid)
                
                .oneOpt()
                .orElseThrow(() -> new BaseException(A_DATA_NOT_FOUND));
        List<String> parsedItemUuids = new ArrayList<>();
        for (MultipartFile doc : docs) {
            try {
                // Parse every file first. Starting one asynchronous indexing job per
                // file would make later files collide with the user's active-job lock.
                ZhiMeshFile file = uploadDoc(knowledgeBase, doc, false, Collections.emptyList());
                KnowledgeBaseItem item = knowledgeBaseItemService.lambdaQuery()
                        .eq(KnowledgeBaseItem::getKbUuid, kbUuid)
                        .eq(KnowledgeBaseItem::getSourceFileId, file.getId())
                        
                        .one();
                boolean parsed = item != null;
                if (parsed) {
                    parsedItemUuids.add(item.getUuid());
                }
                result.add(KbUploadResult.builder()
                        .fileName(doc.getOriginalFilename())
                        .fileUuid(file.getUuid())
                        .itemUuid(parsed ? item.getUuid() : null)
                        .parsed(parsed)
                        .indexQueued(false)
                        .message(parsed ? "PARSED" : "UNSUPPORTED_OR_EMPTY")
                        .build());
            } catch (Exception e) {
                log.warn("uploadDocs fail,fileName:{}", doc.getOriginalFilename(), e);
                result.add(KbUploadResult.builder()
                        .fileName(doc.getOriginalFilename())
                        .parsed(false)
                        .indexQueued(false)
                        .message("UPLOAD_OR_PARSE_FAILED")
                        .build());
            }
        }
        if (Boolean.TRUE.equals(indexAfterUpload) && !indexTypes.isEmpty() && !parsedItemUuids.isEmpty()) {
            try {
                indexItems(parsedItemUuids, indexTypes);
                result.stream()
                        .filter(KbUploadResult::isParsed)
                        .forEach(item -> item.setIndexQueued(true));
            } catch (Exception e) {
                log.warn("batch upload parsed successfully but indexing queue failed,kbUuid:{}", kbUuid, e);
                result.stream()
                        .filter(KbUploadResult::isParsed)
                        .forEach(item -> item.setMessage("INDEX_QUEUE_FAILED"));
            }
        }
        return result;
    }

    public ZhiMeshFile uploadDoc(String kbUuid, Boolean indexAfterUpload, MultipartFile doc, List<String> indexTypes) {
        KnowledgeBase knowledgeBase = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBase::getUuid, kbUuid)
                
                .oneOpt()
                .orElseThrow(() -> new BaseException(A_DATA_NOT_FOUND));
        return uploadDoc(knowledgeBase, doc, indexAfterUpload, indexTypes);
    }

    private ZhiMeshFile uploadDoc(KnowledgeBase knowledgeBase, MultipartFile doc, Boolean indexAfterUpload, List<String> indexTypes) {
        try {
            String fileName = doc.getOriginalFilename();
            ZhiMeshFile adiFile = fileService.saveFile(doc, false);

            //????????????
            Document document = FileOperatorContext.loadDocument(adiFile);
            if (null == document) {
                log.warn("This file type:{} cannot be parsed, ignored", adiFile.getExt());
                return adiFile;
            }
//Create knowledge base item
            //?????????????????????
            String uuid = UuidUtil.createShort();
//PostgreSQL does not support \u0000
            //postgresql?????????\u0000
            String content = document.text().replace("\u0000", "");
            KnowledgeBaseItem knowledgeBaseItem = new KnowledgeBaseItem();
            knowledgeBaseItem.setUuid(uuid);
            knowledgeBaseItem.setKbId(knowledgeBase.getId());
            knowledgeBaseItem.setKbUuid(knowledgeBase.getUuid());
            knowledgeBaseItem.setSourceFileId(adiFile.getId());
            knowledgeBaseItem.setTitle(fileName);
            knowledgeBaseItem.setBrief(StringUtils.substring(content, 0, 200));
            knowledgeBaseItem.setRemark(content);
            boolean success = knowledgeBaseItemService.save(knowledgeBaseItem);
            if (success && Boolean.TRUE.equals(indexAfterUpload)) {
                indexItems(List.of(uuid), indexTypes);
            }

            //Replace file path with url
            adiFile.setPath(FileOperatorContext.getFileUrl(adiFile));
            return adiFile;
        } catch (Exception e) {
            log.error("upload error", e);
            throw new BaseException(A_UPLOAD_FAIL);
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param kbUuid     ?????????uuid
     * @param indexTypes ??????????????????embedding,graphical,fulltext
     * @return ???????????????
     */
    public boolean indexing(String kbUuid, List<String> indexTypes) {
        checkWritePrivilege(null, kbUuid);
        KnowledgeBase knowledgeBase = this.getOrThrow(kbUuid);
        LambdaQueryWrapper<KnowledgeBaseItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBaseItem::getKbUuid, kbUuid);
        if (knowledgeBaseItemService.count(wrapper) <= 0) return true;
        // A full-KB graphical rebuild starts from an emptied graph so historical
        // leftovers (including elements that lost their provenance rows) cannot
        // survive into the new generation. Item-subset indexing keeps the
        // incremental per-document cleanup instead.
        if (indexTypes != null && indexTypes.contains(ZhiMeshConstant.DOC_INDEX_TYPE_GRAPHICAL)) {
            knowledgeBaseItemService.cleanupKnowledgeBaseGraph(kbUuid);
        }
        KnowledgeBaseItemService.IndexBatchCompletion batch =
                knowledgeBaseItemService.beginIndexBatch(knowledgeBase, indexTypes);
        try {
            BizPager.oneByOneWithAnchor(wrapper, knowledgeBaseItemService,
                    KnowledgeBaseItem::getId, kbItem -> knowledgeBaseItemService.submitIndexTask(
                            ThreadContext.getCurrentUser(), knowledgeBase, kbItem, indexTypes, batch));
        } finally {
            batch.submissionFinished();
        }
        return true;
    }

    /**
     * ???????????????????????????????????????
     *
     * @param itemUuids  ?????????uuid??????
     * @param indexTypes ??????????????????embedding,graphical,fulltext
     * @return ???????????????
     */
    public boolean indexItems(List<String> itemUuids, List<String> indexTypes) {
        if (CollectionUtils.isEmpty(itemUuids)) {
            return false;
        }
        Map<String, KnowledgeBase> knowledgeBases = new LinkedHashMap<>();
        Map<String, List<String>> itemsByKnowledgeBase = new LinkedHashMap<>();
        for (String itemUuid : itemUuids) {
            KnowledgeBase knowledgeBase = baseMapper.getByItemUuid(itemUuid);
            if (knowledgeBase == null) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
            knowledgeBases.putIfAbsent(knowledgeBase.getUuid(), knowledgeBase);
            itemsByKnowledgeBase.computeIfAbsent(knowledgeBase.getUuid(), ignored -> new ArrayList<>())
                    .add(itemUuid);
        }
        for (KnowledgeBase knowledgeBase : knowledgeBases.values()) {
            String userIndexKey = MessageFormat.format(USER_INDEXING, knowledgeBase.getOwnerId());
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(userIndexKey))) {
                log.warn("Document is being indexed, please avoid frequent operations, userId:{}",
                        knowledgeBase.getOwnerId());
                throw new BaseException(A_DOC_INDEX_DOING);
            }
        }
        for (Map.Entry<String, List<String>> entry : itemsByKnowledgeBase.entrySet()) {
            KnowledgeBase knowledgeBase = knowledgeBases.get(entry.getKey());
            // Selecting every item of a knowledge base upgrades a graphical
            // rebuild to the whole-KB path: the graph is wiped first so legacy
            // leftovers that lost their provenance rows cannot survive into the
            // new generation. Other index types (embedding, fulltext) keep their
            // regular per-item rebuild; without a graphical request nothing is wiped.
            if (indexTypes != null && indexTypes.contains(ZhiMeshConstant.DOC_INDEX_TYPE_GRAPHICAL)
                    && selectsAllKnowledgeBaseItems(knowledgeBase.getUuid(), entry.getValue())) {
                knowledgeBaseItemService.cleanupKnowledgeBaseGraph(knowledgeBase.getUuid());
            }
            knowledgeBaseItemService.checkAndIndexing(knowledgeBase, entry.getValue(), indexTypes);
        }
        return true;
    }

    /**
     * Reports whether the selection covers every item of one knowledge base.
     * Callers have already resolved each uuid to this knowledge base, so equal
     * distinct counts mean the selection is exactly the full item set; a stale
     * or partial selection falls back to the incremental per-document path.
     */
    boolean selectsAllKnowledgeBaseItems(String kbUuid, List<String> selectedUuids) {
        Set<String> distinct = new HashSet<>(selectedUuids);
        if (distinct.isEmpty()) {
            return false;
        }
        LambdaQueryWrapper<KnowledgeBaseItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBaseItem::getKbUuid, kbUuid);
        return distinct.size() == knowledgeBaseItemService.count(wrapper);
    }

    /** Index items from the user workspace only after excluding system KBs. */
    public boolean indexItemsForUserWorkspace(List<String> itemUuids, List<String> indexTypes) {
        if (CollectionUtils.isEmpty(itemUuids)) {
            return false;
        }
        for (String itemUuid : itemUuids) {
            KnowledgeBase knowledgeBase = baseMapper.getByItemUuid(itemUuid);
            if (knowledgeBase == null) {
                throw new BaseException(A_DATA_NOT_FOUND);
            }
            checkUserWorkspaceWritePrivilege(knowledgeBase.getUuid());
        }
        return indexItems(itemUuids, indexTypes);
    }

    /**
     * ??????????????????????????????????????????????????????
     *
     * @return ???????????????
     */
    public boolean checkIndexIsFinish() {
        String userIndexKey = MessageFormat.format(USER_INDEXING, ThreadContext.getCurrentUserId());
        return Boolean.FALSE.equals(stringRedisTemplate.hasKey(userIndexKey));
    }

    /**
     * Checks indexing state by knowledge base. Administrative indexing may target
     * a system knowledge base owned by another account, so the current user's key
     * is not sufficient for the management workbench.
     */
    public boolean checkIndexIsFinish(String kbUuid) {
        checkWritePrivilege(null, kbUuid);
        KnowledgeBase knowledgeBase = getOrThrow(kbUuid);
        String ownerIndexKey = MessageFormat.format(USER_INDEXING, knowledgeBase.getOwnerId());
        return Boolean.FALSE.equals(stringRedisTemplate.hasKey(ownerIndexKey));
    }

    public Page<KbInfoResp> searchMine(String keyword, Boolean includeOthersPublic, Integer currentPage, Integer pageSize) {
        Page<KbInfoResp> result = new Page<>();
        User user = ThreadContext.getCurrentUser();
        // The user workspace never lists system knowledge bases. Administrators
        // manage those through /admin/kb, not through the regular user UI.
        Page<KnowledgeBase> knowledgeBasePage = baseMapper.searchByUser(
                new Page<>(currentPage, pageSize), user.getId(), keyword, includeOthersPublic);
        return MPPageUtil.convertToPage(knowledgeBasePage, result, KbInfoResp.class, null);
    }

    /**
     * Lists public knowledge bases for the user workspace. System knowledge bases
     * are deliberately excluded here even if an old record was incorrectly marked
     * public before the administration-only rule was introduced.
     */
    public Page<KbInfoResp> searchPublicForUserWorkspace(String keyword, Integer currentPage, Integer pageSize) {
        Page<KbInfoResp> result = new Page<>();
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getIsPublic, true)
                .and(item -> item.eq(KnowledgeBase::getIsSystem, false)
                        .or()
                        .isNull(KnowledgeBase::getIsSystem));
        if (StringUtils.isNotBlank(keyword)) {
            wrapper.like(KnowledgeBase::getTitle, keyword);
        }
        wrapper.orderByDesc(KnowledgeBase::getStarCount, KnowledgeBase::getUpdateTime);
        Page<KnowledgeBase> knowledgeBasePage = baseMapper.selectPage(new Page<>(currentPage, pageSize), wrapper);
        return MPPageUtil.convertToPage(knowledgeBasePage, result, KbInfoResp.class, null);
    }

    public Page<KbInfoResp> search(KbSearchReq req, Integer currentPage, Integer pageSize) {
        Page<KbInfoResp> result = new Page<>();
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(req.getTitle())) {
            wrapper.like(KnowledgeBase::getTitle, req.getTitle());
        }
        if (StringUtils.isNotBlank(req.getOwnerName())) {
            wrapper.like(KnowledgeBase::getOwnerName, req.getOwnerName());
        }
        if (null != req.getIsPublic()) {
            wrapper.eq(KnowledgeBase::getIsPublic, req.getIsPublic());
        }
        if (null != req.getIsSystem()) {
            wrapper.eq(KnowledgeBase::getIsSystem, req.getIsSystem());
        }
        if (null != req.getIsEnabled()) {
            wrapper.eq(KnowledgeBase::getIsEnabled, req.getIsEnabled());
        }
        if (null != req.getMinItemCount()) {
            wrapper.ge(KnowledgeBase::getItemCount, req.getMinItemCount());
        }
        if (null != req.getMinEmbeddingCount()) {
            wrapper.ge(KnowledgeBase::getEmbeddingCount, req.getMinEmbeddingCount());
        }
        if (null != req.getCreateTime() && req.getCreateTime().length == 2) {
            wrapper.between(KnowledgeBase::getCreateTime, LocalDateTimeUtil.parse(req.getCreateTime()[0]), LocalDateTimeUtil.parse(req.getCreateTime()[1]));
        }
        if (null != req.getUpdateTime() && req.getUpdateTime().length == 2) {
            wrapper.between(KnowledgeBase::getUpdateTime, LocalDateTimeUtil.parse(req.getUpdateTime()[0]), LocalDateTimeUtil.parse(req.getUpdateTime()[1]));
        }
        wrapper.orderByDesc(KnowledgeBase::getStarCount, KnowledgeBase::getUpdateTime);
        Page<KnowledgeBase> knowledgeBasePage = baseMapper.selectPage(new Page<>(currentPage, pageSize), wrapper);
        return MPPageUtil.convertToPage(knowledgeBasePage, result, KbInfoResp.class, null);
    }

    public List<KbInfoResp> listByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> knowledgeBases = baseMapper.selectByIds(ids);
        return MPPageUtil.convertToList(knowledgeBases, KbInfoResp.class);
    }

    /** Return only active, enabled system KB ids selected by an administrator. */
    public List<Long> filterEnabledSystemIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .in(KnowledgeBase::getId, ids)
                
                .eq(KnowledgeBase::getIsSystem, true)
                .eq(KnowledgeBase::getIsEnabled, true)
                .list()
                .stream()
                .map(KnowledgeBase::getId)
                .toList();
    }

    public boolean softDelete(String uuid) {
        checkWritePrivilege(null, uuid);
        KnowledgeBase knowledgeBase = getOrThrow(uuid);
        if (Boolean.TRUE.equals(knowledgeBase.getIsSystem())) {
            // A system KB may be disabled through edit, but must never be removed.
            throw new BaseException(A_PARAMS_ERROR);
        }
        boolean deleted = baseMapper.delete(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUuid, uuid)) > 0;
        if (deleted) {
            routeProfileCoordinator.knowledgeBaseDeleted(uuid,
                    knowledgeBase.getRouteProfileActiveGeneration() == null
                            ? 0L : knowledgeBase.getRouteProfileActiveGeneration());
            cleanupKnowledgeBaseGraph(uuid);
        }
        return deleted;
    }

    /**
     * Removes the deleted knowledge base's complete graph footprint. Best
     * effort: the row deletion must not be undone by a graph-store outage, and
     * a leftover vertex can no longer be reached because every retrieval
     * filter is scoped to living knowledge bases.
     */
    private void cleanupKnowledgeBaseGraph(String kbUuid) {
        GraphRag graphRag = GraphRagContext.get(KNOWLEDGE_BASE);
        if (null == graphRag) {
            return;
        }
        try {
            graphRag.cleanupKnowledgeBase(kbUuid);
        } catch (Exception exception) {
            log.error("Unable to clean graph of deleted knowledge base, kbUuid:{}", kbUuid, exception);
        }
    }

    /** Deletes a normal user knowledge base through the user-facing workspace only. */
    public boolean softDeleteForUserWorkspace(String uuid) {
        checkUserWorkspaceWritePrivilege(uuid);
        return softDelete(uuid);
    }

    public SseEmitter sseAsk(String qaRecordUuid) {
        checkRequestTimesOrThrow();
        User user = ThreadContext.getCurrentUser();
        knowledgeBaseQaRecordService.getOwnedOrThrow(qaRecordUuid, user.getId());
        String sseUuid = UuidUtil.createShort();
        SseEmitter sseEmitter = new SseEmitter(SSE_TIMEOUT);
        if (!sseManager.checkOrComplete(user, sseUuid, sseEmitter)) {
            return sseEmitter;
        }
        sseManager.startSse(user, sseUuid, sseEmitter, null);
        String processLockKey = MessageFormat.format(KB_QA_PROCESS_LOCK, qaRecordUuid);
        boolean acquired = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(processLockKey, sseUuid, Duration.ofMinutes(10)));
        if (!acquired) {
            log.warn("Duplicate knowledge-base QA process ignored, qaRecordUuid:{}, userId:{}",
                    qaRecordUuid, user.getId());
            sseManager.sendErrorAndComplete(user.getId(), sseUuid,
                    "该问题正在处理中，请等待当前回答完成");
            return sseEmitter;
        }
        try {
            self.retrieveAndPushToLLM(user, sseUuid, qaRecordUuid);
        } catch (TaskRejectedException exception) {
            stringRedisTemplate.delete(processLockKey);
            log.warn("Knowledge-base chat task rejected, userId:{}, sseUuid:{}", user.getId(), sseUuid);
            sseManager.sendErrorAndComplete(user.getId(), sseUuid, SpringUtil.getMessage("A_SYSTEM_BUSY"));
        }
        return sseEmitter;
    }

    /**
     * Computes request-scoped route availability. BM25 is exposed only when every
     * knowledge base in the authorized scope has an ACTIVE build for the current
     * analyzer version; readiness failures degrade to the established V/G routes.
     */
    private Set<RetrievalRoute> availableKnowledgeRoutes(Collection<String> kbUuids) {
        EnumSet<RetrievalRoute> routes = EnumSet.of(RetrievalRoute.VECTOR, RetrievalRoute.GRAPH);
        LinkedHashSet<String> scope = new LinkedHashSet<>();
        if (kbUuids != null) {
            kbUuids.stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(scope::add);
        }
        if (!scope.isEmpty() && adiProperties.getRetrieval().getBm25().isEnabled()) {
            try {
                if (bm25ReadinessService.readyKnowledgeBases(scope).containsAll(scope)) {
                    routes.add(RetrievalRoute.BM25);
                }
            } catch (RuntimeException exception) {
                log.warn("Unable to check BM25 readiness; continuing with vector/graph routes: {}",
                        exception.getMessage());
            }
        }
        return Set.copyOf(routes);
    }

    private KnowledgeQueryRoutingResult routeKnowledgeQuery(String rawQuery,
                                                             ResolvedKnowledgeQuery resolvedQuery,
                                                             KnowledgeBase knowledgeBase) {
        KbInfoResp scopeInfo = new KbInfoResp();
        BeanUtils.copyProperties(knowledgeBase, scopeInfo);
        KnowledgeScopeDecision scopeDecision = knowledgeScopePreflightGate
                .evaluateDedicatedKnowledgeBase(rawQuery, resolvedQuery.retrievalQuery(),
                        resolvedQuery.scopeEmbeddings(), List.of(scopeInfo));
        if (scopeDecision.skipKnowledgeBaseRouting()) {
            log.info("Dedicated knowledge-base retrieval skipped by scope preflight, kbUuid:{}, reason:{}",
                    knowledgeBase.getUuid(), scopeDecision.reason());
            return new KnowledgeQueryRoutingResult(scopeDecision, Set.of());
        }

        Set<String> kbScope = Set.of(knowledgeBase.getUuid());
        RetrievalPlan routingPlan = intentRoutingService.route(resolvedQuery.retrievalQuery(),
                resolvedQuery.queryContext().embedding(), null,
                Set.of(KnowledgeSourceType.DOCUMENT_KB), availableKnowledgeRoutes(kbScope));
        return new KnowledgeQueryRoutingResult(scopeDecision,
                intentRoutingService.effectiveRoutes(routingPlan));
    }

    private record KnowledgeQueryRoutingResult(KnowledgeScopeDecision scopeDecision,
                                                 Set<RetrievalRoute> effectiveRoutes) {
        private boolean skipRetrieval() {
            return effectiveRoutes == null || effectiveRoutes.isEmpty();
        }
    }

    private ResolvedKnowledgeQuery resolveKnowledgeQuery(String rawQuery, String memoryId,
                                                          AbstractLLMService llmService) {
        ContextualQueryRewriter.Result rewritten = contextualQueryRewriter.resolve(
                rawQuery, memoryId, llmService);
        RetrievalQueryContext queryContext = RetrievalQueryContext.create(
                rewritten.retrievalQuery(), embeddingModel);
        List<Embedding> scopeEmbeddings = new ArrayList<>();
        scopeEmbeddings.add(queryContext.embedding());
        if (rewritten.rewritten()) {
            scopeEmbeddings.add(embeddingModel.embed(rawQuery).content());
        }
        return new ResolvedKnowledgeQuery(
                rewritten.retrievalQuery(), queryContext, List.copyOf(scopeEmbeddings));
    }

    private record ResolvedKnowledgeQuery(String retrievalQuery,
                                          RetrievalQueryContext queryContext,
                                          List<Embedding> scopeEmbeddings) {
    }

    private GraphRoutePreparation prepareGraphRoute(KnowledgeBase knowledgeBase,
                                                     Set<RetrievalRoute> requestedRoutes) {
        if (!requestedRoutes.contains(RetrievalRoute.GRAPH)) {
            return new GraphRoutePreparation(Set.copyOf(requestedRoutes), null);
        }
        try {
            ChatModel model = LLMContext.getServiceById(knowledgeBase.getIngestModelId(), true)
                    .buildChatLLM(ChatModelBuilderProperties.builder()
                            .temperature(knowledgeBase.getQueryLlmTemperature())
                            .build());
            return new GraphRoutePreparation(Set.copyOf(requestedRoutes), model);
        } catch (RuntimeException exception) {
            EnumSet<RetrievalRoute> fallback = EnumSet.copyOf(requestedRoutes);
            fallback.remove(RetrievalRoute.GRAPH);
            if (fallback.isEmpty()) {
                throw exception;
            }
            log.warn("Unable to initialize graph retrieval; continuing with remaining routes, kbUuid:{}, routes:{}, reason:{}",
                    knowledgeBase.getUuid(), fallback, exception.getMessage());
            return new GraphRoutePreparation(Set.copyOf(fallback), null);
        }
    }

    private record GraphRoutePreparation(Set<RetrievalRoute> routes, ChatModel chatModel) {
    }

    /**
     * Blocking knowledge base Q&A: retrieve from knowledge base, then call LLM synchronously.
     *
     * @param user          current user
     * @param knowledgeBase knowledge base entity
     * @param qaDto         QA record DTO
     * @return JSON response with answer
     */
    public Map<String, Object> blockingAsk(User user, KnowledgeBase knowledgeBase, KbQaDto qaDto) {
        checkRequestTimesOrThrow();
        KnowledgeBaseQa qaRecord = knowledgeBaseQaRecordService.getOwnedOrThrow(qaDto.getUuid(), user.getId());
        if (!Objects.equals(qaRecord.getKbUuid(), knowledgeBase.getUuid())) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        AiModel aiModel = qaRecord.getAiModelId() > 0
                ? aiModelService.getByIdOrThrow(qaRecord.getAiModelId())
                : LLMContext.getAiModel(null, null);
        AbstractLLMService llmService = aiModel != null
                ? LLMContext.getServiceOrDefault(null, aiModel.getName())
                : LLMContext.getServiceOrDefault(null, null);
        String memoryId = knowledgeBase.getUuid() + "_" + user.getUuid();

        int maxInputTokens = aiModel.getMaxInputTokens();
        int maxResults = knowledgeBase.getRetrieveMaxResults();
        if (maxResults < 1) {
            maxResults = EmbeddingRag.getRetrieveMaxResults(qaRecord.getQuestion(), maxInputTokens);
        }

        // Retrieve from knowledge base
        String knowledgeContext = "";
        if (maxResults == 0) {
            // User question too long, no retrieval possible
            if (Boolean.TRUE.equals(knowledgeBase.getIsStrict())) {
                throw new BaseException(A_PARAMS_ERROR);
            }
        }
        if (maxResults > 0) {
            boolean definiteNoRag = ruleIntentRecognizer.isDefiniteNoRag(qaRecord.getQuestion());
            if (definiteNoRag && Boolean.TRUE.equals(knowledgeBase.getIsStrict())) {
                return completeBlockingWithoutKnowledgeEvidence(qaRecord);
            }
            if (!definiteNoRag) {
                ResolvedKnowledgeQuery resolvedQuery = resolveKnowledgeQuery(
                        qaRecord.getQuestion(), memoryId, llmService);
                RetrievalQueryContext queryContext = resolvedQuery.queryContext();
                KnowledgeQueryRoutingResult routingResult = routeKnowledgeQuery(
                        qaRecord.getQuestion(), resolvedQuery, knowledgeBase);
                if (routingResult.skipRetrieval() && Boolean.TRUE.equals(knowledgeBase.getIsStrict())) {
                    return completeBlockingWithoutKnowledgeEvidence(qaRecord);
                }
                Set<String> kbScope = Set.of(qaRecord.getKbUuid());
                Set<RetrievalRoute> effectiveRoutes = routingResult.effectiveRoutes();
                if (!effectiveRoutes.isEmpty()) {
                    GraphRoutePreparation graphRoute = prepareGraphRoute(knowledgeBase, effectiveRoutes);
                    effectiveRoutes = graphRoute.routes();
                    ChatModel chatModel = graphRoute.chatModel();
                    RetrieverCreateParam createParam = RetrieverCreateParam.builder()
                            .retrievalRoutes(effectiveRoutes)
                            .knowledgeBaseUuids(kbScope)
                            .chatModel(chatModel)
                            .queryEmbedding(effectiveRoutes.contains(RetrievalRoute.VECTOR)
                                    ? queryContext.embedding() : null)
                            .tokenEstimator(TokenEstimatorFactory.create(knowledgeBase.getIngestTokenEstimator()))
                            .filter(new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, qaRecord.getKbUuid()))
                            .maxResults(maxResults)
                            .minScore(knowledgeBase.getRetrieveMinScore())
                            .breakIfSearchMissed(knowledgeBase.getIsStrict())
                            .graphHopDepth(knowledgeBase.getGraphHopDepth() == null ? 1 : knowledgeBase.getGraphHopDepth())
                            .reranker(createReranker(knowledgeBase))
                            .rerankTopN(knowledgeBase.getRerankTopN() == null ? 5 : knowledgeBase.getRerankTopN())
                            .maxInputTokens(maxInputTokens)
                            .systemMessage(knowledgeBase.getQuerySystemMessage())
                            .build();
                    CompositeRag compositeRag = new CompositeRag(ZhiMeshConstant.RetrieveContentFrom.KNOWLEDGE_BASE);
                    List<RetrieverWrapper> retrieverWrappers = compositeRag.createRetriever(createParam);

                    StringBuilder sb = new StringBuilder();
                    boolean evidenceLabeling = adiProperties.getRetrieval()
                            .isEvidenceLabelingEnabled();
                    for (RetrieverWrapper wrapper : retrieverWrappers) {
                        List<Content> contents = wrapper.getRetriever().retrieve(
                                Query.from(resolvedQuery.retrievalQuery()));
                        wrapper.setResponse(contents);
                        if (!contents.isEmpty()) {
                            if (sb.length() > 0) {
                                sb.append("\n\n");
                            }
                            sb.append(LabeledEvidenceFormatter.format(evidenceLabeling, contents));
                        }
                    }
                    knowledgeContext = sb.toString();
                }
            }
        }

        // Build prompt
        String prompt = PromptUtil.createPrompt(qaRecord.getQuestion(), "", knowledgeContext, "");

        // Call LLM in blocking mode
        String uuid = UuidUtil.createShort();
        ChatModelBuilderProperties modelProperties = ChatModelBuilderProperties.builder()
                .temperature(knowledgeBase.getQueryLlmTemperature())
                .build();
        ChatModelRequest chatRequestParams = ChatModelRequest.builder()
                .memoryId(memoryId)
                .systemMessage(knowledgeBase.getQuerySystemMessage())
                .userMessage(prompt)
                .shortTermMemoryUserMessage(qaRecord.getQuestion())
                .build();
        SseAskParam sseAskParam = SseAskParam.builder()
                .user(user)
                .uuid(uuid)
                .modelProperties(modelProperties)
                .httpRequestParams(chatRequestParams)
                .build();

        long llmStartTime = System.currentTimeMillis();
        ChatResponse chatResponse;
        try (ShortTermMemoryTurnCoordinator.TurnLease turnLease =
                     shortTermMemoryTurnCoordinator.acquire(memoryId, null)) {
            chatResponse = llmService.chat(sseAskParam);
            turnLease.requireValid();
            appendKnowledgeBaseAiMessage(memoryId, chatRequestParams, llmService,
                    chatResponse.aiMessage());
        }
        int llmDuration = NumberUtil.saturatedCastToInt(System.currentTimeMillis() - llmStartTime);

        // Update QA record
        KnowledgeBaseQa updateRecord = new KnowledgeBaseQa();
        updateRecord.setId(qaRecord.getId());
        updateRecord.setPrompt(prompt);
        updateRecord.setAnswer(chatResponse.aiMessage().text());
        knowledgeBaseQaRecordService.updateById(updateRecord);

        // Record token consumption and save LLM call record
        if (chatResponse.metadata() != null && chatResponse.metadata().tokenUsage() != null) {
            int allToken = chatResponse.metadata().tokenUsage().totalTokenCount().intValue();
            if (allToken > 0) {
                userDayCostService.appendCostToUser(user, allToken, false);
            }
            LLMCallRecord callRecord = new LLMCallRecord();
            callRecord.setUuid(UuidUtil.createShort());
            callRecord.setSourceType(LLMCallRecordSourceType.KNOWLEDGE_BASE_QA.getValue());
            callRecord.setSourceId(qaRecord.getId());
            callRecord.setUserId(user.getId());
            callRecord.setModelPlatform(aiModel.getPlatform());
            callRecord.setModelName(aiModel.getName());
            callRecord.setInputTokens(chatResponse.metadata().tokenUsage().inputTokenCount());
            callRecord.setOutputTokens(chatResponse.metadata().tokenUsage().outputTokenCount());
            callRecord.setDuration(llmDuration);
            llmCallRecordService.saveRecord(callRecord);
        }

        // Build response
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", uuid);
        data.put("answer", chatResponse.aiMessage().text());
        if (chatResponse.metadata() != null && chatResponse.metadata().tokenUsage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", chatResponse.metadata().tokenUsage().inputTokenCount());
            usage.put("completion_tokens", chatResponse.metadata().tokenUsage().outputTokenCount());
            usage.put("total_tokens", chatResponse.metadata().tokenUsage().totalTokenCount());
            data.put("usage", usage);
        }
        return data;
    }

    /**
     * Runs one isolated RAG question for offline evaluation. This method deliberately does not
     * create a QA record or attach chat memory, and it never receives reference answers.
     */
    public RagEvaluationAskResp evaluateAsk(User user, String kbUuid, RagEvaluationAskReq request) {
        KnowledgeBase knowledgeBase = getOrThrow(kbUuid);
        AiModel answerModel = aiModelService.getByIdOrThrow(request.answerModelId());
        AbstractLLMService answerService = null;
        if (!request.effectiveRetrievalOnly()) {
            answerService = LLMContext.getServiceById(answerModel.getId(), false);
            if (answerService == null) {
                throw new BaseException(A_ENABLE_MODEL_NOT_FOUND);
            }
        }

        int maxInputTokens = Optional.ofNullable(answerModel.getMaxInputTokens()).orElse(8192);
        int maxResults = Optional.ofNullable(knowledgeBase.getRetrieveMaxResults()).orElse(0);
        if (maxResults < 1) {
            maxResults = EmbeddingRag.getRetrieveMaxResults(request.question(), maxInputTokens);
        }
        if (maxResults < 1) {
            throw new BaseException(A_PARAMS_ERROR);
        }

        TokenEstimatorThreadLocal.setTokenEstimator(knowledgeBase.getIngestTokenEstimator());
        long totalStartedAt = System.currentTimeMillis();
        long retrievalMs;
        long generationMs = 0L;
        List<Content> selected = List.of();
        DeduplicatingContentRetriever retriever;
        ChatResponse chatResponse = null;
        String answer;
        try {
            if (request.retrievalMode() != null && request.retrievalRoutes() != null
                    && !request.retrievalRoutes().isEmpty()) {
                throw new IllegalArgumentException(
                        "Specify either retrievalMode or retrievalRoutes, not both");
            }
            Set<RetrievalRoute> retrievalRoutes = request.effectiveRetrievalRoutes();
            if (retrievalRoutes.isEmpty()) {
                throw new IllegalArgumentException("At least one retrieval route is required");
            }
            Set<String> kbScope = Set.of(kbUuid);
            if (retrievalRoutes.contains(RetrievalRoute.BM25)) {
                // Evaluation is explicit traffic: an unavailable requested branch is
                // an error instead of silently changing the experiment definition.
                bm25ReadinessService.requireReady(kbScope);
            }
            boolean queryEmbeddingRequired = retrievalRoutes.contains(RetrievalRoute.VECTOR)
                    || request.effectiveIncludeQueryEmbedding();
            RetrievalQueryContext queryContext = queryEmbeddingRequired
                    ? RetrievalQueryContext.create(request.question(), embeddingModel)
                    : null;
            boolean useReranker = request.useReranker() == null
                    ? knowledgeBase.getRerankModelId() != null && knowledgeBase.getRerankModelId() > 0
                    : request.useReranker();
            BgeReranker effectiveReranker = useReranker ? requireEvaluationReranker(knowledgeBase) : null;
            ChatModel graphQueryModel = retrievalRoutes.contains(RetrievalRoute.GRAPH)
                    ? LLMContext.getServiceById(knowledgeBase.getIngestModelId(), true)
                            .buildChatLLM(ChatModelBuilderProperties.builder()
                                    .temperature(knowledgeBase.getQueryLlmTemperature())
                                    .build())
                    : null;
            RetrieverCreateParam createParam = RetrieverCreateParam.builder()
                    .retrievalRoutes(retrievalRoutes)
                    .knowledgeBaseUuids(kbScope)
                    .queryEmbedding(queryContext == null ? null : queryContext.embedding())
                    .tokenEstimator(TokenEstimatorFactory.create(knowledgeBase.getIngestTokenEstimator()))
                    .chatModel(graphQueryModel)
                    .filter(new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, kbUuid))
                    .maxResults(maxResults)
                    .minScore(knowledgeBase.getRetrieveMinScore())
                    .breakIfSearchMissed(Boolean.TRUE.equals(knowledgeBase.getIsStrict()))
                    .graphHopDepth(knowledgeBase.getGraphHopDepth() == null ? 1 : knowledgeBase.getGraphHopDepth())
                    .reranker(effectiveReranker)
                    .rerankTopN(knowledgeBase.getRerankTopN() == null ? 5 : knowledgeBase.getRerankTopN())
                    .maxInputTokens(maxInputTokens)
                    .systemMessage(knowledgeBase.getQuerySystemMessage())
                    .build();
            List<RetrieverWrapper> wrappers = new CompositeRag(KNOWLEDGE_BASE).createRetriever(createParam);
            if (wrappers.size() != 1 || !(wrappers.get(0).getRetriever() instanceof DeduplicatingContentRetriever)) {
                throw new IllegalStateException("Evaluation requires the merged RAG retriever");
            }
            retriever = (DeduplicatingContentRetriever) wrappers.get(0).getRetriever();

            long retrievalStartedAt = System.currentTimeMillis();
            boolean noEvidence = false;
            try {
                selected = retriever.retrieve(Query.from(request.question()));
            } catch (BaseException exception) {
                if (B_BREAK_SEARCH.getCode().equals(exception.getCode())) {
                    noEvidence = true;
                } else {
                    throw exception;
                }
            }
            retrievalMs = System.currentTimeMillis() - retrievalStartedAt;

            if (request.effectiveRetrievalOnly()) {
                answer = null;
            } else if (noEvidence) {
                answer = "根据当前知识库无法确定。";
            } else {
                String knowledgeContext = selected.stream()
                        .map(content -> content.textSegment().text())
                        .collect(java.util.stream.Collectors.joining("\n"));
                String prompt = PromptUtil.createPrompt(request.question(), "", knowledgeContext, "");
                SseAskParam askParam = SseAskParam.builder()
                        .user(user)
                        .uuid(UuidUtil.createShort())
                        .modelProperties(ChatModelBuilderProperties.builder()
                                .temperature(request.effectiveTemperature())
                                .build())
                        .httpRequestParams(ChatModelRequest.builder()
                                .systemMessage(knowledgeBase.getQuerySystemMessage())
                                .userMessage(prompt)
                                .build())
                        .build();
                long generationStartedAt = System.currentTimeMillis();
                chatResponse = answerService.chat(askParam);
                generationMs = System.currentTimeMillis() - generationStartedAt;
                answer = chatResponse.aiMessage().text();
            }

            List<Content> candidates = retriever.getCandidateContents();
            List<String> allDocumentIds = RagEvaluationResultMapper.documentIds(candidates);
            Map<String, String> documentNames = getKnowledgeItemNames(allDocumentIds);
            List<String> retrievedDocumentIds = RagEvaluationResultMapper.documentIds(selected);
            List<String> retrievedDocumentNames = retrievedDocumentIds.stream()
                    .map(documentNames::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            TokenCountEstimator estimator = TokenEstimatorFactory.create(knowledgeBase.getIngestTokenEstimator());

            Map<String, Integer> usage = tokenUsage(chatResponse);
            if (usage.get("totalTokens") > 0) {
                userDayCostService.appendCostToUser(user, usage.get("totalTokens"), Boolean.TRUE.equals(answerModel.getIsFree()));
            }
            Map<String, Long> timing = new LinkedHashMap<>();
            timing.put("retrieval", retrievalMs);
            timing.put("generation", generationMs);
            timing.put("total", System.currentTimeMillis() - totalStartedAt);

            return RagEvaluationAskResp.builder()
                    .questionId(request.questionId())
                    .kbUuid(kbUuid)
                    .answerModelId(answerModel.getId())
                    .answerModel(answerModel.getName())
                    .answer(answer)
                    .contexts(selected.stream().map(content -> content.textSegment().text()).toList())
                    .retrievedDocumentIds(retrievedDocumentIds)
                    .retrievedDocumentNames(retrievedDocumentNames)
                    .retrievedSegmentIds(RagEvaluationResultMapper.segmentIds(selected))
                    .candidates(RagEvaluationResultMapper.candidates(candidates, selected, documentNames, estimator))
                    .routes(RagEvaluationResultMapper.routes(retriever.getRouteResults()))
                    .rerank(RagEvaluationResultMapper.rerank(retriever.getLastRerankResult()))
                    .graphTrace(retriever.getGraphTrace())
                    .timingMs(timing)
                    .usage(usage)
                    .configSnapshot(evaluationConfig(knowledgeBase, answerModel, request.effectiveTemperature(),
                            maxResults, request.retrievalMode(), retrievalRoutes, useReranker,
                            request.effectiveRetrievalOnly(),
                            request.effectiveIncludeQueryEmbedding()))
                    .queryEmbedding(request.effectiveIncludeQueryEmbedding() && queryContext != null
                            ? floats(queryContext.embedding().vector()) : null)
                    .build();
        } finally {
            TokenEstimatorThreadLocal.clearTokenEstimator();
        }
    }

    private Map<String, String> getKnowledgeItemNames(List<String> itemUuids) {
        if (itemUuids.isEmpty()) return Map.of();
        return knowledgeBaseItemService.list(new LambdaQueryWrapper<KnowledgeBaseItem>()
                        .in(KnowledgeBaseItem::getUuid, itemUuids)
                        ).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeBaseItem::getUuid,
                        KnowledgeBaseItem::getTitle, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, Integer> tokenUsage(ChatResponse response) {
        Map<String, Integer> usage = new LinkedHashMap<>();
        int input = 0;
        int output = 0;
        int total = 0;
        if (response != null && response.metadata() != null && response.metadata().tokenUsage() != null) {
            input = response.metadata().tokenUsage().inputTokenCount();
            output = response.metadata().tokenUsage().outputTokenCount();
            total = response.metadata().tokenUsage().totalTokenCount();
        }
        usage.put("inputTokens", input);
        usage.put("outputTokens", output);
        usage.put("totalTokens", total);
        return usage;
    }

    private Map<String, Object> evaluationConfig(KnowledgeBase kb, AiModel answerModel,
                                                  double temperature, int maxResults,
                                                  RetrievalMode retrievalMode,
                                                  Set<RetrievalRoute> retrievalRoutes,
                                                  boolean useReranker,
                                                  boolean retrievalOnly, boolean includeQueryEmbedding) {
        ZhiMeshProperties.Retrieval retrieval = adiProperties.getRetrieval();
        ZhiMeshProperties.Retrieval.Bm25 bm25 = retrieval.getBm25();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("kbUuid", kb.getUuid());
        config.put("embeddingModel", adiProperties.getEmbeddingModel());
        config.put("answerModelId", answerModel.getId());
        config.put("answerModel", answerModel.getName());
        config.put("temperature", temperature);
        config.put("retrievalMode", retrievalMode == null ? null : retrievalMode.toJson());
        config.put("retrievalRoutes", retrievalRoutes.stream()
                .sorted(Comparator.comparingInt(RetrievalRoute::ordinal))
                .map(RetrievalRoute::toJson)
                .toList());
        config.put("useReranker", useReranker);
        config.put("retrievalOnly", retrievalOnly);
        config.put("queryEmbeddingIncluded", includeQueryEmbedding);
        config.put("effectiveRerankModelId", useReranker ? kb.getRerankModelId() : null);
        config.put("retrieveMaxResults", maxResults);
        config.put("retrieveMinScore", kb.getRetrieveMinScore());
        config.put("strict", kb.getIsStrict());
        config.put("graphHopDepth", kb.getGraphHopDepth());
        config.put("rerankModelId", kb.getRerankModelId());
        config.put("rerankTopN", kb.getRerankTopN());
        config.put("chunkStrategy", kb.getIngestSplitStrategy());
        config.put("chunkMaxTokens", kb.getIngestMaxSegmentSize());
        config.put("chunkOverlap", kb.getIngestMaxOverlap());
        config.put("contextMaxTokens", retrieval.getContextMaxTokens());
        config.put("contextReservedOutputTokens", retrieval.getContextReservedOutputTokens());
        config.put("contextReservedHistoryTokens", retrieval.getContextReservedHistoryTokens());
        config.put("rerankTimeoutMs", retrieval.getRerankTimeoutMs());
        config.put("rerankCandidateLimit", retrieval.getRerankCandidateLimit());
        config.put("rerankRelativeScoreThreshold", retrieval.getRerankRelativeScoreThreshold());
        config.put("rerankMinCandidates", retrieval.getRerankMinCandidates());
        config.put("hybridProtectedVectorCount", retrieval.getHybridProtectedVectorCount());
        config.put("hybridVectorProtectionMinMargin",
                retrieval.getHybridVectorProtectionMinMargin());
        config.put("bm25Enabled", bm25.isEnabled());
        config.put("bm25TopK", bm25.getTopK());
        config.put("bm25K1", bm25.getK1());
        config.put("bm25B", bm25.getB());
        config.put("bm25TimeoutMs", bm25.getTimeoutMs());
        config.put("bm25MaxQueryTerms", bm25.getMaxQueryTerms());
        config.put("bm25AnalyzerVersion", bm25.getAnalyzerVersion());
        return config;
    }

    private static List<Float> floats(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) result.add(value);
        return List.copyOf(result);
    }

    private BgeReranker requireEvaluationReranker(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getRerankModelId() == null || knowledgeBase.getRerankModelId() <= 0) {
            throw new IllegalStateException(
                    "useReranker=true requires a valid rerank model configured on the knowledge base");
        }
        return createReranker(knowledgeBase);
    }

    /**
     * Star or unstar
     *
     * @param user   ??????
     * @param kbUuid ?????????uuid
     * @return true:star;false:unstar
     */
    @Transactional
    public boolean toggleStar(User user, String kbUuid) {

        KnowledgeBase knowledgeBase = self.getOrThrow(kbUuid);
        boolean star;
        KnowledgeBaseStar oldRecord = knowledgeBaseStarRecordService.getRecord(user.getId(), kbUuid);
        if (null == oldRecord) {
            KnowledgeBaseStar starRecord = new KnowledgeBaseStar();
            starRecord.setUserId(user.getId());
            starRecord.setUserUuid(user.getUuid());
            starRecord.setKbId(knowledgeBase.getId());
            starRecord.setKbUuid(kbUuid);
            knowledgeBaseStarRecordService.save(starRecord);

            star = true;
        } else {
            knowledgeBaseStarRecordService.removeById(oldRecord.getId());
            star = false;
        }
        int starCount = star ? knowledgeBase.getStarCount() + 1 : knowledgeBase.getStarCount() - 1;
        ChainWrappers.lambdaUpdateChain(baseMapper)
                .eq(KnowledgeBase::getId, knowledgeBase.getId())
                .set(KnowledgeBase::getStarCount, starCount)
                .update();
        return star;
    }

    /** Adds or removes a star without allowing the user workspace to touch a system KB. */
    public boolean toggleStarForUserWorkspace(User user, String kbUuid) {
        checkReadPrivilege(kbUuid);
        return toggleStar(user, kbUuid);
    }

    /**
     * ???????????????????????????
     */
    private void checkRequestTimesOrThrow() {
        String key = MessageFormat.format(RedisKeyConstant.AQ_ASK_TIMES, ThreadContext.getCurrentUserId(), LocalDateTimeUtil.format(LocalDateTime.now(), PATTERN_YYYY_MM_DD));
        String askTimes = stringRedisTemplate.opsForValue().get(key);
        String askQuota = SysConfigService.getByKey(QUOTA_BY_QA_ASK_DAILY);
        if (null != askQuota && null != askTimes && Integer.parseInt(askTimes) >= Integer.parseInt(askQuota)) {
            throw new BaseException(A_QA_ASK_LIMIT);
        }
        stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, Duration.ofDays(1));
    }

    /**
     * ?????????????????????????????????LLM
     *
     * @param user         ?????????????????????
     * @param sseUuid      SSE ???????????? / SSE request identifier
     * @param qaRecordUuid ?????????uuid
     */
    @Async("chatExecutor")
    public void retrieveAndPushToLLM(User user, String sseUuid, String qaRecordUuid) {
        log.info("retrieveAndPushToLLM,qaRecordUuid:{},userId:{}", qaRecordUuid, user.getId());
        KnowledgeBaseQa qaRecord = knowledgeBaseQaRecordService.getOwnedOrThrow(qaRecordUuid, user.getId());
        KnowledgeBase knowledgeBase = getOrThrow(qaRecord.getKbUuid());
        AiModel aiModel = aiModelService.getByIdOrThrow(qaRecord.getAiModelId());
        AbstractLLMService answerLlmService = LLMContext.getServiceById(aiModel.getId(), true);

        TokenEstimatorThreadLocal.setTokenEstimator(knowledgeBase.getIngestTokenEstimator());
        boolean processLockReleaseDeferred = false;

        try {
            int maxInputTokens = aiModel.getMaxInputTokens();
            int maxResults = knowledgeBase.getRetrieveMaxResults();
//maxResults < 1 means the system auto-calculates based on model maxInputTokens
            //maxResults < 1 ????????????????????????????????????maxInputTokens??????????????????
            if (maxResults < 1) {
                maxResults = EmbeddingRag.getRetrieveMaxResults(qaRecord.getQuestion(), maxInputTokens);
            }

            String memoryId = qaRecord.getKbUuid() + "_" + user.getUuid();
            SseAskParam sseAskParam = new SseAskParam();
            sseAskParam.setUuid(qaRecord.getUuid());
            sseAskParam.setHttpRequestParams(
                    ChatModelRequest.builder()
                            .memoryId(memoryId)
                            .systemMessage(knowledgeBase.getQuerySystemMessage())
                            .userMessage(qaRecord.getQuestion())
                            .build()
            );
            sseAskParam.setModelProperties(
                    ChatModelBuilderProperties.builder()
                            .temperature(knowledgeBase.getQueryLlmTemperature())
                            .build()
            );
            sseAskParam.setSseUuid(sseUuid);
            sseAskParam.setModelPlatform(answerLlmService.getPlatform().getName());
            sseAskParam.setModelName(answerLlmService.getAiModel().getName());
            sseAskParam.setUser(user);
            if (maxResults == 0) {
                log.info("User question too long, no need to retrieve docs; strict mode returns error, relaxed mode continues to LLM");
                if (Boolean.TRUE.equals(knowledgeBase.getIsStrict())) {
                    sseManager.sendErrorAndComplete(user.getId(), sseUuid, "Question too long, max " + maxInputTokens + " tokens");
                } else {
                    processLockReleaseDeferred = true;
                    streamWithoutRetrieval(answerLlmService, sseAskParam, qaRecord, aiModel, user, qaRecordUuid);
                }
            } else if (ruleIntentRecognizer.isDefiniteNoRag(qaRecord.getQuestion())) {
                log.info("Dedicated knowledge-base retrieval skipped before query embedding by definite NO_RAG rule");
                if (Boolean.TRUE.equals(knowledgeBase.getIsStrict())) {
                    completeStreamingWithoutKnowledgeEvidence(user, sseAskParam, qaRecord, 0);
                } else {
                    processLockReleaseDeferred = true;
                    streamWithoutRetrieval(answerLlmService, sseAskParam, qaRecord, aiModel, user, qaRecordUuid);
                }
            } else {
                log.info("Performing RAG request, maxResults:{}", maxResults);
                ResolvedKnowledgeQuery resolvedQuery = resolveKnowledgeQuery(
                        qaRecord.getQuestion(), memoryId, answerLlmService);
                RetrievalQueryContext queryContext = resolvedQuery.queryContext();
                sseAskParam.getHttpRequestParams().setRetrievalQuery(resolvedQuery.retrievalQuery());
                Set<String> kbScope = Set.of(qaRecord.getKbUuid());
                KnowledgeQueryRoutingResult routingResult = routeKnowledgeQuery(
                        qaRecord.getQuestion(), resolvedQuery, knowledgeBase);
                Set<RetrievalRoute> effectiveRoutes = routingResult.effectiveRoutes();
                if (routingResult.skipRetrieval()) {
                    if (Boolean.TRUE.equals(knowledgeBase.getIsStrict())) {
                        completeStreamingWithoutKnowledgeEvidence(user, sseAskParam, qaRecord,
                                NumberUtil.saturatedCastToInt(routingResult.scopeDecision().durationMs()));
                    } else {
                        processLockReleaseDeferred = true;
                        streamWithoutRetrieval(answerLlmService, sseAskParam, qaRecord, aiModel, user, qaRecordUuid);
                    }
                } else {
                    GraphRoutePreparation graphRoute = prepareGraphRoute(knowledgeBase, effectiveRoutes);
                    effectiveRoutes = graphRoute.routes();
                    ChatModel chatModel = graphRoute.chatModel();
                    RetrieverCreateParam createParam = RetrieverCreateParam.builder()
                         .retrievalRoutes(effectiveRoutes)
                         .knowledgeBaseUuids(kbScope)
                         .chatModel(chatModel)
                         .queryEmbedding(effectiveRoutes.contains(RetrievalRoute.VECTOR)
                                 ? queryContext.embedding() : null)
                         .tokenEstimator(TokenEstimatorFactory.create(knowledgeBase.getIngestTokenEstimator()))
                         .filter(new IsEqualTo(ZhiMeshConstant.MetadataKey.KB_UUID, qaRecord.getKbUuid()))
                            .maxResults(maxResults)
                            .minScore(knowledgeBase.getRetrieveMinScore())
                            .breakIfSearchMissed(knowledgeBase.getIsStrict())
                            .graphHopDepth(knowledgeBase.getGraphHopDepth() == null ? 1 : knowledgeBase.getGraphHopDepth())
                            .reranker(createReranker(knowledgeBase))
                            .rerankTopN(knowledgeBase.getRerankTopN() == null ? 5 : knowledgeBase.getRerankTopN())
                            .maxInputTokens(maxInputTokens)
                            .systemMessage(knowledgeBase.getQuerySystemMessage())
                            .build();
                    CompositeRag compositeRag = new CompositeRag(KNOWLEDGE_BASE);
                    List<RetrieverWrapper> retrieverWrappers = compositeRag.createRetriever(createParam);
                    List<ContentRetriever> retrievers = retrieverWrappers.stream().map(RetrieverWrapper::getRetriever).toList();
                    long llmStartTime = System.currentTimeMillis();
                    processLockReleaseDeferred = true;
                    try {
                        compositeRag.ragChat(retrievers, sseAskParam, (response, promptMeta, answerMeta) -> {
                            try {
                            answerMeta.setDuration(NumberUtil.saturatedCastToInt(System.currentTimeMillis() - llmStartTime));
                            applyKnowledgeEvidenceFlags(answerMeta, retrievers);
                            updateQaRecord(
                                    UpdateQaParam.builder()
                                            .user(user)
                                            .qaRecord(qaRecord)
                                            .retrievers(retrievers)
                                            .sseAskParam(sseAskParam)
                                            .response(response)
                                            .duration(answerMeta.getDuration())
                                            .isTokenFree(aiModel.getIsFree())
                                             .build());
                            sseManager.sendComplete(user.getId(), sseAskParam.getSseUuid(), promptMeta, answerMeta, null);
                            } finally {
                                releaseKnowledgeBaseQaProcessLock(qaRecordUuid);
                            }
                            }
                        );
                    } catch (RuntimeException exception) {
                        releaseKnowledgeBaseQaProcessLock(qaRecordUuid);
                        throw exception;
                    }
                }
            }
        } finally {
            TokenEstimatorThreadLocal.clearTokenEstimator();
            if (!processLockReleaseDeferred) {
                releaseKnowledgeBaseQaProcessLock(qaRecordUuid);
            }
        }
    }

    private Map<String, Object> completeBlockingWithoutKnowledgeEvidence(KnowledgeBaseQa qaRecord) {
        persistNoKnowledgeEvidenceAnswer(qaRecord);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", 0);
        usage.put("total_tokens", 0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", qaRecord.getUuid());
        data.put("answer", NO_KNOWLEDGE_EVIDENCE_ANSWER);
        data.put("usage", usage);
        return data;
    }

    private void completeStreamingWithoutKnowledgeEvidence(User user,
                                                            SseAskParam sseAskParam,
                                                            KnowledgeBaseQa qaRecord,
                                                            int duration) {
        persistNoKnowledgeEvidenceAnswer(qaRecord);
        SseManager.parseAndSendPartialMsg(sseAskParam.getSseUuid(), NO_KNOWLEDGE_EVIDENCE_ANSWER);
        PromptMeta promptMeta = PromptMeta.builder()
                .uuid(qaRecord.getUuid())
                .inputTokens(0)
                .build();
        AnswerMeta answerMeta = AnswerMeta.builder()
                .uuid(qaRecord.getUuid())
                .inputTokens(0)
                .outputTokens(0)
                .duration(Math.max(0, duration))
                .build();
        sseManager.sendComplete(user.getId(), sseAskParam.getSseUuid(),
                promptMeta, answerMeta, null);
    }

    private void persistNoKnowledgeEvidenceAnswer(KnowledgeBaseQa qaRecord) {
        KnowledgeBaseQa updateRecord = new KnowledgeBaseQa();
        updateRecord.setId(qaRecord.getId());
        updateRecord.setPrompt(qaRecord.getQuestion());
        updateRecord.setAnswer(NO_KNOWLEDGE_EVIDENCE_ANSWER);
        knowledgeBaseQaRecordService.updateById(updateRecord);
    }

    private void streamWithoutRetrieval(AbstractLLMService answerLlmService,
                                        SseAskParam sseAskParam,
                                        KnowledgeBaseQa qaRecord,
                                        AiModel aiModel,
                                        User user,
                                        String qaRecordUuid) {
        ChatModelRequest chatRequest = sseAskParam.getHttpRequestParams();
        String memoryId = chatRequest.getMemoryId();
        ShortTermMemoryTurnCoordinator.TurnLease turnLease;
        try {
            turnLease = shortTermMemoryTurnCoordinator.acquire(memoryId, sseAskParam.getSseUuid());
        } catch (RuntimeException exception) {
            log.warn("Unable to acquire knowledge-base short-memory turn lock, memoryId:{}, sseUuid:{}",
                    memoryId, sseAskParam.getSseUuid(), exception);
            sseManager.sendErrorAndComplete(user.getId(), sseAskParam.getSseUuid(), exception.getMessage());
            releaseKnowledgeBaseQaProcessLock(qaRecordUuid);
            return;
        }

        try {
            long llmStartTime = System.currentTimeMillis();
            sseManager.call(answerLlmService, sseAskParam, chatExecutor, (response, questionMeta, answerMeta) -> {
                try {
                    turnLease.requireValid();
                    appendKnowledgeBaseAiMessage(memoryId, chatRequest, answerLlmService,
                            AiMessage.builder()
                                    .text(response.getContent())
                                    .thinking(response.getThinkingContent())
                                    .build());
                    answerMeta.setDuration(NumberUtil.saturatedCastToInt(
                            System.currentTimeMillis() - llmStartTime));
                    updateQaRecord(UpdateQaParam.builder()
                            .user(user)
                            .qaRecord(qaRecord)
                            .retrievers(null)
                            .sseAskParam(sseAskParam)
                            .response(response.getContent())
                            .duration(answerMeta.getDuration())
                            .isTokenFree(aiModel.getIsFree())
                            .build());
                    sseManager.sendComplete(user.getId(), sseAskParam.getSseUuid(),
                            questionMeta, answerMeta, null);
                } finally {
                    releaseKnowledgeBaseQaProcessLock(qaRecordUuid);
                }
            });
        } catch (RuntimeException exception) {
            turnLease.close();
            log.error("Knowledge-base streaming chat failed, memoryId:{}, sseUuid:{}",
                    memoryId, sseAskParam.getSseUuid(), exception);
            sseManager.sendErrorAndComplete(user.getId(), sseAskParam.getSseUuid(),
                    exception.getMessage());
            releaseKnowledgeBaseQaProcessLock(qaRecordUuid);
        }
    }

    private void releaseKnowledgeBaseQaProcessLock(String qaRecordUuid) {
        if (StringUtils.isNotBlank(qaRecordUuid)) {
            stringRedisTemplate.delete(MessageFormat.format(KB_QA_PROCESS_LOCK, qaRecordUuid));
        }
    }

    private void appendKnowledgeBaseAiMessage(String memoryId,
                                              ChatModelRequest chatRequest,
                                              AbstractLLMService llmService,
                                              AiMessage aiMessage) {
        int maxTokens = chatRequest.getMemoryWindowMaxTokens() != null
                && chatRequest.getMemoryWindowMaxTokens() > 0
                ? chatRequest.getMemoryWindowMaxTokens()
                : llmService.getAiModel().getMaxInputTokens();
        ShortTermMemoryWindow.append(shortTermMemoryService, memoryId, maxTokens,
                llmService.resolveTokenCountEstimator(), aiMessage);
    }

    private BgeReranker createReranker(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.getRerankModelId() == null || knowledgeBase.getRerankModelId() <= 0) {
            return null;
        }
        AiModel rerankModel = aiModelService.getByIdOrThrow(knowledgeBase.getRerankModelId());
        if (!ZhiMeshConstant.ModelType.RERANK.equals(rerankModel.getType())) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        ModelPlatform platform = modelPlatformService.getByName(rerankModel.getPlatform());
        return new BgeReranker(rerankModel, platform);
    }
    private void updateQaRecord(UpdateQaParam updateQaParam) {

        Pair<Integer, Integer> inputOutputTokenCost = LLMTokenUtil.calAllTokenCostByUuid(stringRedisTemplate, updateQaParam.getSseAskParam().getUuid());

        KnowledgeBaseQa qaRecord = updateQaParam.getQaRecord();
        User user = updateQaParam.getUser();

        KnowledgeBaseQa updateRecord = new KnowledgeBaseQa();
        updateRecord.setId(qaRecord.getId());
        updateRecord.setPrompt(updateQaParam.getSseAskParam().getHttpRequestParams().getUserMessage());
        updateRecord.setAnswer(updateQaParam.getResponse());
        knowledgeBaseQaRecordService.updateById(updateRecord);

        //Save LLM call record
        LLMCallRecord callRecord = new LLMCallRecord();
        callRecord.setUuid(UuidUtil.createShort());
        callRecord.setSourceType(LLMCallRecordSourceType.KNOWLEDGE_BASE_QA.getValue());
        callRecord.setSourceId(qaRecord.getId());
        callRecord.setUserId(user.getId());
        callRecord.setModelPlatform(updateQaParam.getSseAskParam().getModelName());
        callRecord.setModelName(updateQaParam.getSseAskParam().getModelName());
        callRecord.setInputTokens(inputOutputTokenCost.getLeft());
        callRecord.setOutputTokens(inputOutputTokenCost.getRight());
        callRecord.setDuration(updateQaParam.getDuration());
        llmCallRecordService.saveRecord(callRecord);

        createRef(updateQaParam.getRetrievers(), user, qaRecord.getId());
        //???????????????????????????token??????????????????RAG??????????????????token????????????????????????????????????LLM??????
        int allToken = inputOutputTokenCost.getLeft() + inputOutputTokenCost.getRight();
        log.info("User {} total token consumption for this request: {}", user.getName(), allToken);
        if (allToken > 0) {
            userDayCostService.appendCostToUser(user, allToken, updateQaParam.isTokenFree());
        }
    }

    /**
     * ??????????????????
     *
     * @param retrievers ?????????
     * @param user       ??????
     * @param qaId       ??????id
     */
    private void createRef(List<ContentRetriever> retrievers, User user, Long qaId) {
        if (CollectionUtils.isEmpty(retrievers)) {
            return;
        }
        for (ContentRetriever retriever : retrievers) {
            for (ContentRetriever sourceRetriever : DeduplicatingContentRetriever.unwrapSourceRetrievers(retriever)) {
                if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever embeddingRetriever) {
                    if (!embeddingRetriever.getRetrievedEmbeddingToScore().isEmpty()) {
                        knowledgeBaseQaRecordService.createEmbeddingRefs(
                                user, qaId, embeddingRetriever.getRetrievedEmbeddingToScore());
                    }
                } else if (sourceRetriever instanceof GraphStoreContentRetriever graphRetriever) {
                    RefGraphDto graphRef = graphRetriever.getGraphRef();
                    if (graphRef != null && (!CollectionUtils.isEmpty(graphRef.getVertices())
                            || !CollectionUtils.isEmpty(graphRef.getEdges()))) {
                        knowledgeBaseQaRecordService.createGraphRefs(user, qaId, graphRef);
                    }
                }
            }
        }
    }

    private void applyKnowledgeEvidenceFlags(AnswerMeta answerMeta,
                                             List<ContentRetriever> retrievers) {
        boolean isRefEmbedding = false;
        boolean isRefGraph = false;
        if (!CollectionUtils.isEmpty(retrievers)) {
            for (ContentRetriever retriever : retrievers) {
                for (ContentRetriever sourceRetriever
                        : DeduplicatingContentRetriever.unwrapSourceRetrievers(retriever)) {
                    if (sourceRetriever instanceof ZhiMeshEmbeddingStoreContentRetriever embeddingRetriever) {
                        isRefEmbedding = isRefEmbedding
                                || !embeddingRetriever.getRetrievedEmbeddingToScore().isEmpty();
                    } else if (sourceRetriever instanceof GraphStoreContentRetriever graphRetriever) {
                        RefGraphDto graphRef = graphRetriever.getGraphRef();
                        isRefGraph = isRefGraph || graphRef != null
                                && (!CollectionUtils.isEmpty(graphRef.getVertices())
                                || !CollectionUtils.isEmpty(graphRef.getEdges()));
                    }
                }
            }
        }
        answerMeta.setIsRefEmbedding(isRefEmbedding);
        answerMeta.setIsRefGraph(isRefGraph);
    }
    public KnowledgeBase getOrThrow(String kbUuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(KnowledgeBase::getUuid, kbUuid)
                
                .oneOpt().orElseThrow(() -> new BaseException(A_DATA_NOT_FOUND));
    }

    /**
     * Read authorization for a knowledge base's content: allow the owner, an admin,
     * or anyone when the knowledge base is public. This mirrors the (owner-only)
     * write-side checkWritePrivilege so that the read endpoints are scoped too. Denials
     * are reported as A_DATA_NOT_FOUND to avoid leaking the existence of other
     * users' private knowledge bases.
     */
    public void checkReadPrivilege(String kbUuid) {
        KnowledgeBase kb = getOrThrow(kbUuid);
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            // System KBs are accessible to a bound system role at inference
            // time, but never through the user knowledge-base workspace.
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(kb.getIsPublic())) {
            return;
        }
        User user = ThreadContext.getCurrentUser();
        // A user session stays a user session even when that account also has
        // administrator rights. Cross-user and system-KB access belongs to the
        // management API, never to the regular user workspace.
        if (null != user && user.getId().equals(kb.getOwnerId())) {
            return;
        }
        throw new BaseException(A_DATA_NOT_FOUND);
    }

    /** Verify that a write comes from the user workspace and not a system KB. */
    public void checkUserWorkspaceWritePrivilege(String kbUuid) {
        KnowledgeBase kb = getOrThrow(kbUuid);
        if (Boolean.TRUE.equals(kb.getIsSystem())) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        User user = ThreadContext.getCurrentUser();
        if (user == null || !user.getId().equals(kb.getOwnerId())) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
    }

    /**
     * Set update knowledge base stat signal
     *
     * @param kbUuid ?????????uuid
     */
    public void updateStatistic(String kbUuid) {
        stringRedisTemplate.opsForSet().add(KB_STATISTIC_RECALCULATE_SIGNAL, kbUuid);
    }

    public int countTodayCreated() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), 0, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(1);
        return baseMapper.countCreatedByTimePeriod(beginTime, endTime);
    }

    public int countAllCreated() {
        return baseMapper.countAllCreated();
    }

    /**
     * Update knowledge base stat
     */
    public void updateStatistic() {
        try {
            Set<String> kbUuidList = stringRedisTemplate.opsForSet().members(KB_STATISTIC_RECALCULATE_SIGNAL);
            if (CollectionUtils.isEmpty(kbUuidList)) {
                return;
            }
            for (String kbUuid : kbUuidList) {
                try {
                    int embeddingCount = embeddingService.countByKbUuid(kbUuid);
                    baseMapper.updateStatByUuid(kbUuid, embeddingCount);
                } catch (Exception e) {
                    log.error("Failed to update knowledge base statistics, kbUuid:{}", kbUuid, e);
                } finally {
                    stringRedisTemplate.opsForSet().remove(KB_STATISTIC_RECALCULATE_SIGNAL, kbUuid);
                }
            }
        } catch (Exception e) {
            log.error("updateStatistic execution exception", e);
        }
    }

    private void checkWritePrivilege(Long kbId, String kbUuid) {
        if (null == kbId && StringUtils.isBlank(kbUuid)) {
            throw new BaseException(A_PARAMS_ERROR);
        }
        User user = ThreadContext.getCurrentUser();
        if (null == user) {
            throw new BaseException(A_USER_NOT_EXIST);
        }
        boolean privilege = user.getIsAdmin();
        if (privilege) {
            return;
        }
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getOwnerId, user.getId());
        if (null != kbId) {
            wrapper = wrapper.eq(KnowledgeBase::getId, kbId);
        } else if (StringUtils.isNotBlank(kbUuid)) {
            wrapper = wrapper.eq(KnowledgeBase::getUuid, kbUuid);
        }
        boolean exists = baseMapper.exists(wrapper);
        if (!exists) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
    }

}
