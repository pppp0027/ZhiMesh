declare namespace Chat {

	interface ChatMessage {
		uuid: string | '',
		contentType: number // 2: text, 3: audio
		createTime: string
		thinkingContent: string //思考过程
		remark: string
		audioUuid: string
		audioUrl: string
		audioDuration: number // in seconds
		messageRole?: number
		children: ChatMessage[] //AI回复的消息
		aiModelId?: string | number
		aiModelPlatform?: string
		attachmentUrls: string[]
		isRefMemoryEmbedding: boolean //是否引用记忆向量
		isRefEmbedding: boolean //是否是引用知识库向量
		isRefGraph: boolean //是否是引用知识库图谱
		isRefBm25: boolean //是否命中 BM25 关键词检索

		//Frontend only
		inversion?: boolean
		error?: boolean
		thinking?: boolean //是否正在思考
		loading?: boolean
		audioPlayState: AudioPlayState
		state?: Map<string, string> //消息状态描述

		//Token observability
		inputTokens?: number
		outputTokens?: number
		duration?: number

		//MCP tool call observability
		toolCalls?: ToolCall[]

		//Agent 协作挂起载荷（ask_user 追问 / 人工审批），实时轮由 SSE 挂起事件写入，历史轮由 meta/轨迹行派生
		suspension?: SuspensionPayload

		//Frontend only：挂起卡片是否可交互（实时挂起未应答为 true；历史回放/已应答只读）
		suspensionActive?: boolean
	}

	/**
	 * 挂起载荷：实时 SSE 事件（[AGENT_QUESTION]/[APPROVAL_REQUEST]）用 kind，
	 * 历史回放 AnswerMeta.suspension 用 type（后端已知键名不对称，前端按 kind ?? type 取值）
	 */
	interface SuspensionPayload {
		/** 挂起类型（实时事件键）：ASK_USER 追问 | APPROVAL 显式审批 | MCP_APPROVAL MCP 拦截审批 */
		kind?: 'ASK_USER' | 'APPROVAL' | 'MCP_APPROVAL'
		/** 挂起类型（历史回放/AnswerMeta.suspension 键），取值同 kind */
		type?: 'ASK_USER' | 'APPROVAL' | 'MCP_APPROVAL'
		/** 触发挂起的工具名（ask_user / request_human_approval / 被拦截的 MCP 工具名） */
		toolName?: string
		/** 问题文本（= 挂起轮消息内容） */
		question: string
		/** 可选项列表（ASK_USER 可空，后端空时整个键省略） */
		options?: string[]
		/** 审批动作名（APPROVAL/MCP_APPROVAL 可空） */
		action?: string
		/** 参数摘要（APPROVAL/MCP_APPROVAL 可空，可能被 ...[truncated] 截断） */
		summary?: string
		/** 风险等级（APPROVAL 可空，HIGH/MEDIUM/LOW） */
		riskLevel?: string
		/** 挂起检查点 uuid（恢复配对依据） */
		checkpointUuid?: string
	}

	//MCP 工具调用观测（实时 SSE toolCallReceived 与历史回放共用结构）
	interface ToolCall {
		toolName: string
		durationMs: number
		success: boolean
		/** 工具入参 JSON 字符串（可能较长，前端摘要展示） */
		args?: string
		/** 工具结果摘要（后端已截断约 200 字符） */
		resultSummary?: string
		/** 历史回放排序序号（SSE 实时事件不带该字段） */
		seq?: number
		/** 挂起节点标记：该步骤为协作挂起（等待用户应答/等待审批），非普通工具执行 */
		suspensionKind?: 'ASK_USER' | 'APPROVAL' | 'MCP_APPROVAL'
		/** 恢复节点标记：挂起后用户已提交应答（前端合成行） */
		resumed?: boolean
		/** 前端 only：步骤执行中（[TOOL_STARTED] 已点亮、[TOOL_CALL] 尚未回填），仅实时轮存在 */
		running?: boolean
	}

	interface CharacterPreset {
		id: string
		uuid: string
		title: string
		remark: string
		aiSystemMessage: string
		systemKnowledgeEnabled?: boolean
		mcpIds: string
		type: string
		isSystem: boolean

		used: boolean
	}

	interface CharacterToPresetRel {
		id: string
		uuid: string
		userCharacterId: string
		presetCharacterId: string
	}

	//会话关联的知识库信息
	interface CharacterKnowledge {
		id: string
		uuid: string
		title: string
		isMine: boolean
		kbInfo?: KnowledgeBase.Info
		isEnable: boolean //该知识库是否可用
		isSystem?: boolean
		isReadOnly?: boolean
	}

	interface ConfigVoice {
		param_name: string // 用于API请求的参数名称
		model: string
		platform: string
	}

	interface AudioConfig {
		voice: ConfigVoice
	}

	interface Character {
		id?: string | number
		title: string
		uuid: string
		remark: string
		aiSystemMessage: string
		understandContextEnable: boolean
		loadedAll: boolean
		loadedFirstPageMsg: boolean
		minMsgUuid?: string | ''
		mcpIds: string[]
		kbIds: string[] // 关联的知识库ID
		characterKnowledgeList: CharacterKnowledge[] //关联的知识库包装信息
		systemKnowledgeEnabled?: boolean
		/** Number of enabled system knowledge bases occupying the session limit. */
		systemKnowledgeCount?: number
		/** Server-enforced total knowledge-base limit for this character. */
		knowledgeBaseLimit?: number
		answerContentType: number // 1: auto, 2: text, 3: audio
		isAutoplayAnswer: boolean //聊天时音频类型的响应内容是否自动播放
		isEnableThinking: boolean //是否启用思考过程
		isEnableWebSearch: boolean //是否启用网络搜索
		isAgentic?: boolean //是否启用Agentic模式（自主调用知识库检索等工具完成任务）
		audioConfig: AudioConfig //语音配置
	}

	interface CharacterWithMessages {
		uuid: string
		data: ChatMessage[]
	}

	interface Conversation {
		id: string | number
		uuid: string
		characterId: string | number
		title: string
		status: number
		isDefault: boolean
		lastMessageTime?: string
		createTime: string
		updateTime: string
		loadedAll?: boolean
		loadedFirstPageMsg?: boolean
		minMsgUuid?: string
	}

	interface ConversationPage {
		total: number
		size: number
		current: number
		pages: number
		records: Conversation[]
	}

	interface ChatState {
		active: string
		activeConversationUuid: string
		characters: Character[]
		conversations: Conversation[]
		chats: CharacterWithMessages[]
		loadingMsgs: Set<string>
		presetCharacters: CharacterPreset[]
		msgToMemoryRef: Map<string, MemoryEmbedding[]>
		msgToEmbeddingRef: Map<string, KnowledgeBase.QaRecordEmbeddingRef[]>
    msgToGraphRef: Map<string, KnowledgeBase.QaRecordGraphRef>
    loadingGraphRef: Map<string, boolean>
	}

	interface CharacterRequest {
		prompt: string,
		characterUuid?: string
		parentMessageId?: string
	}

	interface CharacterResponse {
		text: string
	}

	interface AudioInfo {
		url: string
		uuid: string
		duration: number // in seconds
	}

	interface MetaData {
		conversationUuid?: string
		question: {
			inputTokens: number,
			uuid: string
		},
		answer: {
			inputTokens: number,
			outputTokens: number,
			uuid: string,
			duration?: number
			isRefEmbedding?: boolean
			isRefGraph?: boolean
			isRefMemoryEmbedding?: boolean
			isRefBm25?: boolean
			//AnswerMeta 事件同样可能携带工具调用数组（结构同 ToolCall）
			toolCalls?: ToolCall[]
			//挂起轮随 meta 事件下发的挂起载荷（键名为 type，与实时事件的 kind 不对称）
			suspension?: SuspensionPayload
		},
		audioInfo: AudioInfo
	}

	interface CharacterMsgListResp {
		minMsgUuid: string
		msgList: Chat.ChatMessage[]
	}

	interface CharactersResp {
		total: number
		records: Chat.Character[]
	}

	interface Prompt {
		renderKey: string
		renderValue: string
		id: number
		act: string
		prompt: string
	}

	interface DrawState {
		loadingUuid: string
		loading: boolean
		myDraws: Draw[] //倒序，队尾的为最新数据
	}


	interface Draw {
		id?: number
		uuid: string
		prompt: string
		aiModelName: string
		originalImageUuid?: string
		originalImageUrl: string
		maskImageUuid?: string
		maskImageUrl: string
		interactingMethod: number
		processStatus: number   //1:processing,2:fail,3:success
		processStatusRemark: string

		aiModelPlatform: string
		//绘图成功后生成的图片
		imageUuids: string[]
		imageUrls: string[]
		createTime: string
		isPublic: boolean
		isStar: boolean
		starCount: number
		userUuid: string
		userName: string
		dynamicParams: any
		duration?: number
	}

	interface DrawListResp {
		minId: number
		draws: Draw[]
	}

	interface DrawComment {
		uuid: string
		userUuid: string
		userName: string
		drawUuid: string
		remark: string
		createTime: string
	}

	interface DrawCommentsResp {
		records: Chat.DrawComment[]
		total: number
		current: number
	}

	interface GalleryState {
		loadingUuid: string
		loading: boolean
		publicDraws: Draw[]
		myStarDraws: Draw[]
	}

	 interface MemoryEmbedding {
    embeddingId: string
    text: string
    /**
     * 记忆类型：semantic 或 episodic。
     * Memory type: semantic or episodic.
     */
    memoryType?: string
    /**
     * 事件发生时间（仅 episodic）。格式 yyyy-MM-dd HH:mm:ss。
     * Event timestamp (episodic only). Format yyyy-MM-dd HH:mm:ss.
     */
    createTime?: string
    /**
     * 事件类型（仅 episodic）。
     * Event type (episodic only).
     */
    eventType?: string
    /**
     * 重要性 1-5（仅 episodic）。
     * Importance 1-5 (episodic only).
     */
    importance?: number
  }

  interface KeywordHit {
    chunkUuid: string
    kbUuid: string
    kbItemUuid: string
    text: string
    score: number
    rank: number
  }

  interface KeywordReference {
    terms: string[]
    hits: KeywordHit[]
  }
}
