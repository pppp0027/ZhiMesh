package com.pppp.zhimesh.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("adi_knowledge_base")
@Schema(title = "知识库实体 | Knowledge Base Entity", description = "知识库表 | Knowledge Base Table")
public class KnowledgeBase extends BaseEntity {

    @Schema(title = "uuid")
    @TableField("uuid")
    private String uuid;

    @Schema(title = "名称 | Name")
    @TableField("title")
    private String title;

    @Schema(title = "描述 | Description")
    @TableField("remark")
    private String remark;

    /** 企业库可见范围：STAFF 全员 / EXECUTIVE 仅管理员；非 COMPANY 归属恒为 STAFF。 */
    @TableField("company_scope")
    private String companyScope;

    /** 仅供管理员维护并由系统角色按白名单绑定的知识库。 */
    @TableField("is_system")
    private Boolean isSystem;

    /** 系统知识库可停用但不删除，停用后不会参与角色检索。 */
    @TableField("is_enabled")
    private Boolean isEnabled;

    @Schema(title = "是否严格模式 | Is Strict Mode")
    @TableField("is_strict")
    private Boolean isStrict;

    @Schema(title = "点赞数 | Star Count")
    @TableField("star_count")
    private Integer starCount;

    @Schema(title = "知识点数量 | Knowledge Item Count")
    @TableField("item_count")
    private Integer itemCount;

    @Schema(title = "向量数 | Embedding Count")
    @TableField("embedding_count")
    private Integer embeddingCount;

    /** Current route-profile lifecycle state: NONE/STALE/BUILDING/READY/FAILED. */
    @TableField("route_profile_status")
    private String routeProfileStatus;

    /** Latest source generation that must eventually be represented by a profile. */
    @TableField("route_profile_generation")
    private Long routeProfileGeneration;

    /** Generation of the immutable Redis profile bundle currently serving requests. */
    @TableField("route_profile_active_generation")
    private Long routeProfileActiveGeneration;

    @TableField("route_profile_set_uuid")
    private String routeProfileSetUuid;

    @TableField("route_profile_source_hash")
    private String routeProfileSourceHash;

    /** Database model id when one exists; zero is valid for built-in local embedding models. */
    @TableField("route_profile_model_id")
    private Long routeProfileModelId;

    /** Stable configured model identity, authoritative for cache compatibility checks. */
    @TableField("route_profile_model_identity")
    private String routeProfileModelIdentity;

    @TableField("route_profile_status_change_time")
    private LocalDateTime routeProfileStatusChangeTime;

    @Schema(title = "所属人uuid | Owner UUID")
    @TableField("owner_uuid")
    private String ownerUuid;

    @Schema(title = "所属人id | Owner ID")
    @TableField("owner_id")
    private Long ownerId;

    @Schema(title = "所属人名称 | Owner Name")
    @TableField("owner_name")
    private String ownerName;

    /** 归属层级：PERSONAL/TEAM/COMPANY；存量数据与默认值均为 PERSONAL。 */
    @TableField("owner_type")
    private String ownerType;

    /** TEAM 归属时的 adi_team.id，其余归属为 0；owner_* 三列始终保留创建者信息。 */
    @TableField("team_id")
    private Long teamId;

    @Schema(title = "文档切割时重叠数量(按token来计) | Document Chunking Overlap Count (by Token)")
    @TableField("ingest_max_overlap")
    private Integer ingestMaxOverlap;

    @Schema(title = "分段策略: recursive/paragraph/line/sentence/custom | Split strategy")
    @TableField("ingest_split_strategy")
    private String ingestSplitStrategy;

    @Schema(title = "每段最大token数 | Max segment size in tokens")
    @TableField("ingest_max_segment_size")
    private Integer ingestMaxSegmentSize;

    @Schema(title = "自定义分隔符,仅custom策略生效 | Custom separator, only used when strategy is custom")
    @TableField("ingest_custom_separator")
    private String ingestCustomSeparator;

    @Schema(title = "索引(图谱化)文档时使用的LLM,如不指定的话则使用第1个可用的LLM | LLM Used for Indexing (Graph) - Defaults to First Available")
    @TableField("ingest_model_name")
    private String ingestModelName;

    @Schema(title = "索引(图谱化)文档时使用的LLM,如不指定的话则使用第1个可用的LLM | LLM Used for Indexing (Graph) - Defaults to First Available")
    @TableField("ingest_model_id")
    private Long ingestModelId;

    @Schema(title = "token数量估计器,默认使用OpenAiTokenizer | Token Estimator (Default: OpenAiTokenizer)")
    @TableField("ingest_token_estimator")
    private String ingestTokenEstimator;

    @Schema(title = "文档召回最大数量 | Max Document Recall Count")
    @TableField("retrieve_max_results")
    private Integer retrieveMaxResults;

    @Schema(title = "文档召回最小分数 | Min Document Recall Score")
    @TableField("retrieve_min_score")
    private Double retrieveMinScore;

    @Schema(title = "Graph Retrieval Hop Depth (1 or 2)")
    @TableField("graph_hop_depth")
    private Integer graphHopDepth;

    @Schema(title = "Rerank model ID for bge-reranker-base")
    @TableField("rerank_model_id")
    private Long rerankModelId;

    @Schema(title = "Rerank result count")
    @TableField("rerank_top_n")
    private Integer rerankTopN;
    @Schema(title = "请求LLM时的temperature | LLM Temperature")
    @TableField("query_llm_temperature")
    private Double queryLlmTemperature;

    @Schema(title = "请求LLM时的系统提示词 | System Prompt for LLM")
    @TableField("query_system_message")
    private String querySystemMessage;

    @Schema(title = "外部系统对接密钥 | API key for external system integration")
    @TableField("api_key")
    private String apiKey;
}
