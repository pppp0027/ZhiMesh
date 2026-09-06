package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.KbEditReq;
import com.pppp.zhimesh.common.dto.KbInfoResp;
import com.pppp.zhimesh.common.dto.KbItemIndexBatchReq;
import com.pppp.zhimesh.common.dto.KbUploadResult;
import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.DOC_INDEX_TYPE_EMBEDDING;

@RestController
@RequestMapping("/knowledge-base")
@Validated
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @PostMapping("/saveOrUpdate")
    public KnowledgeBase saveOrUpdate(@RequestBody KbEditReq kbEditReq) {
        return knowledgeBaseService.saveOrUpdateForUserWorkspace(kbEditReq);
    }

    @PostMapping(path = "/uploadDocs/{uuid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KbUploadResult> uploadDocs(@PathVariable String uuid,
                              @RequestParam(value = "indexAfterUpload", defaultValue = "true") Boolean indexAfterUpload,
                               @RequestParam(defaultValue = "") String indexTypes,
                               @RequestParam("files") MultipartFile[] docs) {
        knowledgeBaseService.checkUserWorkspaceWritePrivilege(uuid);
        List<String> indexTypeList = normalizeUploadIndexTypes(indexAfterUpload, indexTypes);
        return knowledgeBaseService.uploadDocs(uuid, indexAfterUpload, docs, indexTypeList);
    }

    /**
* Upload, parse and index documents
     * 上传、解析并索引文档
     *
* @param uuid             知识库uuid / Knowledge base UUID
* @param indexAfterUpload 是否上传完接着索引文档 / Whether to index documents after upload
* @param doc              二进制文件 / Binary file
     * @return 上传成功的文件信息
     */
    @PostMapping(path = "/upload/{uuid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ZhiMeshFile upload(@PathVariable String uuid,
                          @RequestParam(value = "indexAfterUpload", defaultValue = "true") Boolean indexAfterUpload,
                           @RequestParam(defaultValue = "") String indexTypes,
                           @RequestParam("file") MultipartFile doc) {
        knowledgeBaseService.checkUserWorkspaceWritePrivilege(uuid);
        // 防止前端传过来的indexTypes不干净，这里用List处理一下
        List<String> indexTypeList = normalizeUploadIndexTypes(indexAfterUpload, indexTypes);
        return knowledgeBaseService.uploadDoc(uuid, indexAfterUpload, doc, indexTypeList);
    }

    /**
* Search my knowledge bases
     * 搜索我的知识库
     *
* @param keyword             搜索关键词 / Search keyword
* @param includeOthersPublic 是否包含其他人公开的知识库 / Whether to include other users' public knowledge bases
* @param currentPage         当前页数 / Current page number
* @param pageSize            每页数量 / Page size
     * @return 我的知识库列表
     */
    @GetMapping("/mine/search")
    public Page<KbInfoResp> searchMine(@RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "false") Boolean includeOthersPublic,
                                       @NotNull @Min(1) Integer currentPage,
                                       @NotNull @Min(10) Integer pageSize) {
        return knowledgeBaseService.searchMine(keyword, includeOthersPublic, currentPage, pageSize);
    }

    /**
* Search public knowledge bases
     * 搜索公开的知识库
     *
* @param keyword     搜索关键词 / Search keyword
* @param currentPage 当前页数 / Current page number
* @param pageSize    每页数量 / Page size
     * @return 知识库列表
     */
    @GetMapping("/public/search")
    public Page<KbInfoResp> searchPublic(@RequestParam(defaultValue = "") String keyword,
                                         @NotNull @Min(1) Integer currentPage,
                                         @NotNull @Min(10) Integer pageSize) {
        return knowledgeBaseService.searchPublicForUserWorkspace(keyword, currentPage, pageSize);
    }

    /**
     * Knowledge base details
     * 知识库详情
     *
     * @param uuid 知识库uuid / Knowledge base UUID
     * @return 知识库详情
     */
    @GetMapping("/info/{uuid}")
   public KnowledgeBase info(@PathVariable String uuid) {
       knowledgeBaseService.checkReadPrivilege(uuid);
       return knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getUuid, uuid)
                
                .one();
    }

    /**
     * 删除知识库
     *
* @param uuid 知识库uuid / Knowledge base UUID
     * @return 成功或失败
     */
    @PostMapping("/del/{uuid}")
    public boolean softDelete(@PathVariable String uuid) {
        return knowledgeBaseService.softDeleteForUserWorkspace(uuid);
    }

    /**
* Index entire knowledge base
     * 索引整个知识库
     *
* @param uuid 知识库uuid / Knowledge base UUID
     * @return 成功或失败
     */
    @PostMapping("/indexing/{uuid}")
    public boolean indexing(@PathVariable String uuid, @RequestParam(defaultValue = "") String indexTypes) {
        knowledgeBaseService.checkUserWorkspaceWritePrivilege(uuid);
        List<String> indexTypeList = Arrays.stream(indexTypes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        return knowledgeBaseService.indexing(uuid, indexTypeList);
    }

    /**
* Batch index knowledge points
     * 批量索引知识点
     *
* @param req 知识点列表 / Knowledge point list
     * @return 成功或失败
     */
    @PostMapping(value = "/item/indexing-list", consumes = MediaType.APPLICATION_JSON_VALUE, params = "!uuids")
    public boolean indexItemsJson(@RequestBody KbItemIndexBatchReq req) {
        return knowledgeBaseService.indexItemsForUserWorkspace(List.of(req.getUuids()), List.of(req.getIndexTypes()));
    }

    /**
     * Backward-compatible form endpoint used by both the historical user client and
     * the admin workbench. Keeping both contracts prevents a stale frontend/backend
     * pair from failing in Spring's media-type negotiation before indexing starts.
     */
    @PostMapping(value = "/item/indexing-list", params = {"uuids", "indexTypes"})
    public boolean indexItemsForm(@RequestParam String[] uuids,
                                  @RequestParam(defaultValue = "embedding") String[] indexTypes) {
        return knowledgeBaseService.indexItemsForUserWorkspace(List.of(uuids), List.of(indexTypes));
    }

    /**
* Check if knowledge base indexing is complete
     * 检查知识库是否已经索引完成
     *
     * @return 成功或失败
     */
    @GetMapping("/indexing/check")
    public boolean checkIndex(@RequestParam(required = false) String kbUuid) {
        // The user workbench passes the active knowledge base so a task in a
        // different knowledge base owned by the same user cannot keep this
        // page polling. Keep the no-argument form for older clients.
        return kbUuid == null || kbUuid.isBlank()
                ? knowledgeBaseService.checkIndexIsFinish()
                : knowledgeBaseService.checkIndexIsFinish(kbUuid);
    }

    /**
* Like/Star
     * 点赞
     *
     * @return true:star;false:unstar
     */
    @PostMapping("/star/toggle")
    public boolean star(@RequestParam @NotBlank String kbUuid) {
        return knowledgeBaseService.toggleStarForUserWorkspace(ThreadContext.getCurrentUser(), kbUuid);
    }

    private List<String> normalizeUploadIndexTypes(Boolean indexAfterUpload, String indexTypes) {
        List<String> result = Arrays.stream(indexTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (Boolean.TRUE.equals(indexAfterUpload) && result.isEmpty()) {
            // Preserve the historical "upload and index" expectation: vector indexing
            // is the safe default when a client did not explicitly choose a type.
            result.add(DOC_INDEX_TYPE_EMBEDDING);
        }
        return result;
    }
}
