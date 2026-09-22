declare namespace KnowledgeBase {
  interface Info {
    id: string
    uuid: string
    title: string
    remark: string
    isStrict: boolean
    /** 系统库标记：用户工作台对系统库只读展示，写操作仅管理员通道。 */
    isSystem?: boolean
    starCount: number
    ownerUuid: string
    ownerName: string
    /** 归属层级 PERSONAL/TEAM/COMPANY；缺省按 PERSONAL 处理。 */
    ownerType?: 'PERSONAL' | 'TEAM' | 'COMPANY'
    /** 企业库可见范围 STAFF/EXECUTIVE；非 COMPANY 归属恒为 STAFF。 */
    companyScope?: 'STAFF' | 'EXECUTIVE'
    /** TEAM 归属的团队 id。 */
    teamId?: string
    /** TEAM 归属的团队 uuid（列表查询联表填充）。 */
    teamUuid?: string
    /** TEAM 归属的团队名称（列表查询联表填充）。 */
    teamName?: string
    /** 当前用户在该库所属团队中的角色，仅 TEAM 归属且为成员时返回。 */
    myRole?: Team.TeamRole
    /** 当前用户的访问级别 READ/WRITE/MANAGE，后端裁决后填充。 */
    accessLevel?: 'READ' | 'WRITE' | 'MANAGE'
    loadingRecords?: boolean
    itemCount: number
    embeddingCount: number
    ingestMaxOverlap: number
    ingestSplitStrategy: string
    ingestMaxSegmentSize: number
    ingestCustomSeparator: string
    ingestModelId: string
    ingestTokenEstimator: string
    retrieveMaxResults: number
    retrieveMinScore: number
    queryLlmTemperature: number
    querySystemMessage: string
    graphHopDepth: number
    rerankModelId: string
    rerankTopN: number

    ingestModelName: string
  }
  interface InfoListResp {
    total: number,
    records: Info[]
  }
  interface Item {
    id: string
    uuid: string
    kbId: string
    kbUuid: string
    title: string
    brief: string
    remark: string
    embeddingStatus: string
    graphicalStatus: string
    fulltextStatus: string
    embeddingStatusChangeTime: string
    graphicalStatusChangeTime: string
    fulltextStatusChangeTime: string
    fulltextStartedAt?: string
    fulltextCompletedAt?: string
    fulltextChunkSetUuid?: string
    sourceFileName: string
    sourceFileUuid: string
    sourceFileUrl: string
  }
  interface KbItemEditReq {
    id?: string
    kbId: string
    title: string
    remark?: string
  }
  interface KbEmbedding {
    embeddingId: string
    embedding: number[]
    text: string
  }
  interface KbEdge {
    id: number
    label: string
    startId: number
    endId: number
    description: string
  }
  interface KbVertex {
    id: number
    name: string
    description: string
  }
  interface KbItemGraphResp {
    vertices: KbVertex[]
    edges: KbEdge[]
  }
  interface QaRecordListResp {
    total: number,
    records: KnowledgeBase.QaRecordInfo[]
  }
  interface QaRecordInfo {
    id: string
    uuid: string
    kbId: string
    kbUuid: string
    question: string
    answer: string
    createTime: string
    loading?: boolean
    error?: boolean
    aiModelPlatform?: string
    promptTokens?: number
    answerTokens?: number
    //SSE 实时写入的 token 数据 | Token data from SSE live stream
    inputTokens?: number
    outputTokens?: number
    //调用耗时（毫秒） | Call duration (ms)
    duration?: number
    isRefEmbedding?: boolean
    isRefGraph?: boolean
  }

  interface QaRecordEmbeddingRef {
    embeddingId: string
    text: string
  }

  interface QaRecordGraphRef {
    vertices: KbVertex[]
    edges: KbEdge[]
  }

  interface KbState {
    selectedKbType: 'mine' | 'team' | 'company'
    activeKbUuid: string
    myKbInfos: Info[]
    teamKbInfos: Info[]
    companyKbInfos: Info[]
    kbUuidToQaRecords: Map<string, QaRecordInfo[]>
    kbUuidToStarInfo: Map<string, KbStarInfo>
    qaRecordToEmbeddingRef: Map<string, KnowledgeBase.QaRecordEmbeddingRef[]>
    qaRecordToGraphRef: Map<string, KnowledgeBase.QaRecordGraphRef>
    loadingGraphRef: Map<string, boolean>
    loadingRecords: Map<string, boolean>
    loaddingKbList: boolean
    /** 三个分区列表是否已完成至少一次解析（未登录视为已解析的空态）。 */
    kbListLoaded: boolean
    reloadKbInfosSignal: boolean
  }

  interface KbStarInfo {
    kbUuid: string
    kbTitle: string
    star: boolean
  }

  interface KbStarListResp {
    total: number
    records: KnowledgeBase.KbStarInfo[]
  }
}
