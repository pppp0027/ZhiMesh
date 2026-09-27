import { EventStreamContentType, fetchEventSource } from '@microsoft/fetch-event-source'
import { get, getRawAxios, post } from '@/utils/request'
import { useAuthStore, useUserStore } from '@/store'
import { t } from '@/locales'

class FatalError extends Error { }

function getSysConfig<T = any>() {
  return get<T>({
    url: '/sys/config/public/info',
  })
}

function fetchUserConfig<T = any>() {
  return get<T>({
    url: '/user/config',
  })
}

function fetchCharacters<T = any>() {
  return get<T>({
    url: '/character/list',
  })
}

function characterAdd<T = any>(params: { title: string; remark: string; aiSystemMessage: string }) {
  return post<T>({
    url: '/character/add',
    data: {
      ...params,
    },
  })
}

function characterEdit<T = any>(uuid: string, params: Partial<Chat.Character>) {
  return post<T>({
    url: `/character/edit/${uuid}`,
    data: {
      ...params,
    },
  })
}

function characterDel<T = any>(uuid: string) {
  return post<T>({
    url: `/character/del/${uuid}`,
  })
}

function searchPresetCharacters<T = any>(keyword = '') {
  return post<T>({
    url: '/character-preset/search?currentPage=1&pageSize=100',
    data: {
      keyword,
    },
  })
}

// 搜索会话与预设会话关联关系
function listCharacterPresetRels<T = any>() {
  return get<T>({
    url: '/character-preset-rel/mine?limit=100',
  })
}

function characterAddByPreset<T = any>(params: { presetCharacterUuid: string }) {
  return post<T>({
    url: `/character/addByPreset?presetUuid=${params.presetCharacterUuid}`,
  })
}

function fetchMessages<T = any>(characterUuid: string, maxMsgUuid: string, pageSize: number) {
  return get<T>({
    url: `/character/${characterUuid}?maxMsgUuid=${maxMsgUuid}&pageSize=${pageSize}`,
  })
}

function fetchConversations<T = Chat.ConversationPage>(currentPage = 1, pageSize = 100) {
  return get<T>({
    url: `/conversation/list?currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function conversationAdd<T = Chat.Conversation>(characterUuid: string, title: string) {
  return post<T>({
    url: '/conversation/add',
    data: { characterUuid, title },
  })
}

function fetchDefaultConversation<T = Chat.Conversation>(characterUuid: string) {
  return get<T>({
    url: `/conversation/default?characterUuid=${encodeURIComponent(characterUuid)}`,
  })
}

function conversationEdit<T = boolean>(uuid: string, title: string) {
  return post<T>({
    url: `/conversation/edit/${uuid}`,
    data: { title },
  })
}

function conversationDelete<T = boolean>(uuid: string) {
  return post<T>({
    url: `/conversation/del/${uuid}`,
  })
}

function fetchConversationMessages<T = Chat.CharacterMsgListResp>(conversationUuid: string, maxMsgUuid: string, pageSize: number) {
  return get<T>({
    url: `/conversation/${conversationUuid}/messages?maxMsgUuid=${maxMsgUuid}&pageSize=${pageSize}`,
  })
}

function commonSseProcess(
  url: string,
  params: {
    options: any
    signal?: AbortSignal
    startCallback: (chunk: string) => void
    thinkingDataReceived: (chunk: string) => void
    messageReceived: (chunk: string, eventName: string) => void
    audioDataReceived?: (chunk: string) => void
    stateChanged?: (state: string) => void
    toolCallReceived?: (data: Chat.ToolCall) => void
    toolStartedReceived?: (data: { toolName: string; args?: string }) => void
    suspensionReceived?: (data: Chat.SuspensionPayload) => void
    /** 具名事件缺省忽略开关：会话流开启（未知事件安全忽略），工作流等依赖动态具名事件的调用方保持透传 */
    ignoreUnknownEvents?: boolean
    doneCallback: (chunk: string) => void
    errorCallback: (error: string) => void
  },
): Promise<void> {
  return fetchEventSource(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': useAuthStore().token,
    },
    signal: params.signal,
    body: JSON.stringify({
      ...params.options,
    }),
    async onopen(response) {
      const contentType = response.headers.get('content-type') || ''
      if (response.ok && contentType.startsWith(EventStreamContentType)) {
        // everything's good
      } else if (response.status === 401) {
        console.log(t('api.noPermission'))
        const authStore = useAuthStore()
        authStore.removeToken()
        const userStore = useUserStore()
        userStore.resetUserInfo()
        throw new FatalError(t('api.noPermission'))
      } else {
        console.log('response', response)
        throw new FatalError()
      }
    },
    onmessage(eventMessage) {
      if (eventMessage.event === '[START]') {
        params.startCallback(eventMessage.data)
        return
      } else if (eventMessage.event === '[ERROR]') {
        console.log(`error:${eventMessage}`)
        params.errorCallback(eventMessage.data)
        return
      } else if (eventMessage.event === '[DONE]') {
        params.doneCallback(eventMessage.data)
        return
      } else if (eventMessage.event === '[AUDIO]') {
        params.audioDataReceived && params.audioDataReceived(eventMessage.data)
        return
      } else if (eventMessage.event === '[THINKING]') {
        params.thinkingDataReceived && params.thinkingDataReceived(eventMessage.data)
        return
      } else if (eventMessage.event === '[STATE_CHANGED]') {
        params.stateChanged && params.stateChanged(eventMessage.data)
        return
      } else if (eventMessage.event === '[TOOL_CALL]') {
        try {
          params.toolCallReceived && params.toolCallReceived(JSON.parse(eventMessage.data))
        } catch (e) {
          console.warn('[TOOL_CALL] parse error', e)
        }
        return
      } else if (eventMessage.event === '[TOOL_STARTED]') {
        try {
          params.toolStartedReceived && params.toolStartedReceived(JSON.parse(eventMessage.data))
        } catch (e) {
          console.warn('[TOOL_STARTED] parse error', e)
        }
        return
      } else if (eventMessage.event === '[AGENT_QUESTION]' || eventMessage.event === '[APPROVAL_REQUEST]') {
        // Agent 协作挂起事件（ask_user 追问 / 人工审批）：data 为挂起卡片载荷 JSON
        try {
          params.suspensionReceived && params.suspensionReceived(JSON.parse(eventMessage.data))
        } catch (e) {
          console.warn(`${eventMessage.event} parse error`, e)
        }
        return
      }
      // 未识别的具名事件：会话流按缺省忽略（安全兜底，避免未知事件数据混入消息文本）；
      // 未开启忽略的调用方（工作流动态节点事件）保持原有透传行为
      if (eventMessage.event && params.ignoreUnknownEvents)
        return
      if (eventMessage.data.indexOf('-_wrap_-') === 0)
        eventMessage.data = eventMessage.data.replace('-_wrap_-', '\n')

      // 会自动处理后端返回内容的首个空格，需在后端的返回内容前多加个空格，相关源码：https://github.com/Azure/fetch-event-source/blob/45ac3cfffd30b05b79fbf95c21e67d4ef59aa56a/src/parse.ts#L129-L133
      params.messageReceived(eventMessage.data, eventMessage.event ? eventMessage.event : '')
    },
    onerror(error) {
      const errorMessage = error instanceof Error ? error.message : String(error)
      console.log(`sse error:${errorMessage}`)
      params.errorCallback(errorMessage)
      throw error
    },
    openWhenHidden: true,
  })
}

function sseProcess(params: {
  options: { prompt?: string; characterUuid?: string; conversationUuid?: string; parentMessageId?: string; regenerateQuestionUuid?: string; modelName?: String; modelPlatform?: string; imageUrls?: string[]; audioUuid?: string; audioDuration?: number }
  signal?: AbortSignal
  startCallback: (chunk: string) => void
  messageReceived: (chunk: string, eventName?: string) => void
  thinkingDataReceived: (chunk: string) => void
  audioDataReceived?: (pcmPart: any) => void
  stateChanged?: (state: string) => void
  toolCallReceived?: (data: Chat.ToolCall) => void
  toolStartedReceived?: (data: { toolName: string; args?: string }) => void
  suspensionReceived?: (data: Chat.SuspensionPayload) => void
  doneCallback: (chunk: string) => void
  errorCallback: (error: string) => void
}): Promise<void> {
  // 会话流对未知具名事件缺省忽略（工作流/QA 等调用方不受影响）
  return commonSseProcess('/api/chat/process', { ...params, ignoreUnknownEvents: true })
}

function login<T>(email: string, password: string, captchaId: string, captchaCode: string) {
  return post<T>({
    url: '/auth/login',
    data: { email, password, captchaId, captchaCode },
  })
}

function register<T>(email: string, password: string, captchaId: string, captchaCode: string) {
  return post<T>({
    url: '/auth/register',
    data: { email, password, captchaId, captchaCode },
  })
}

function logout<T>() {
  return post<T>({
    url: '/user/logout',
  })
}

function userEdit<T>(config: User.ConfigUpdate) {
  return post<T>({
    url: '/user/edit',
    data: config,
  })
}

function passwordReset<T>() {
  return post<T>({
    url: '/auth/password/reset',
  })
}

function passwordFind<T>(email: string) {
  return post<T>({
    url: `/auth/password/forgot?email=${encodeURIComponent(email)}`,
  })
}

function loadSearchEngines<T>() {
  return get<T>({
    url: '/auth/search-engine/list',
  })
}

function modifyPassword<T>(oldPassword: string, newPassword: string) {
  return post<T>({
    url: '/user/password/modify',
    data: { oldPassword, newPassword },
  })
}

function searchPrompts<T>(currentPage: number, pageSize: number, keyword?: string) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/prompt/my/search?${search}currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function promptAutocomplete<T>(keyword: string) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}`
  return get<T>({
    url: `/prompt/my/autocomplete?${search}`,
  })
}

function promptsSave<T>(prompts: { act: string; prompt: string }[]) {
  return post<T>({
    url: '/prompt/save',
    data: { prompts },
  })
}

function promptDel<T>(id: number) {
  return post<T>({
    url: `/prompt/del/${id}`,
  })
}

function promptEdit<T>(id: number, title: string, remark: string) {
  return post<T>({
    url: `/prompt/edit/${id}`,
    data: { title, remark },
  })
}

function fetchDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/${uuid}`,
  })
}

function fetchNewerPublicDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/newer-public/${uuid}`,
  })
}

function fetchOlderPublicDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/older-public/${uuid}`,
  })
}

function fetchNewerStarredDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/newer-starred/${uuid}`,
  })
}

function fetchOlderStarredDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/older-starred/${uuid}`,
  })
}

function fetchNewerMineDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/newer-mine/${uuid}`,
  })
}

function fetchOlderMineDraw<T = any>(uuid: string) {
  return get<T>({
    url: `/draw/detail/older-mine/${uuid}`,
  })
}

function fetchDraws<T = any>(maxId: number, pageSize: number) {
  return get<T>({
    url: `/draw/list?maxId=${maxId}&pageSize=${pageSize}`,
  })
}

function fetchStarDraws<T = any>(maxId: number, pageSize: number) {
  return get<T>({
    url: `/draw/star/mine?maxId=${maxId}&pageSize=${pageSize}`,
  })
}

function fetchPublicDraws<T = any>(maxId: number, pageSize: number) {
  return get<T>({
    url: `/draw/public/list?maxId=${maxId}&pageSize=${pageSize}`,
  })
}

function fetchDrawComments<T = any>(uuid: string, curentPage: number, pageSize: number) {
  return get<T>({
    url: `/draw/comment/list/${uuid}?curentPage=${curentPage}&pageSize=${pageSize}`,
  })
}

function drawStarOrUnStar<T = any>(uuid: string) {
  return post<T>({
    url: `/draw/star/toggle/${uuid}`,
  })
}

function drawCommentAdd<T = any>(uuid: string, comment: string) {
  return post<T>({
    url: '/draw/comment/add',
    data: { drawUuid: uuid, comment },
  })
}

function fileUpload<T = any>(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return post<T>({
    url: '/file/upload',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data',
    },
  })
}

function fileDel<T = any>(uuid: string) {
  return post<T>({
    url: `/file/del/${uuid}`,
  })
}

function imageGenerate<T = any>(params: ImageGenerationParams) {
  return post<T>({
    url: '/draw/generation',
    data: params,
  })
}

/**
 * 删除作图任务
 * @param uuid 作图任务uuid
 * @returns
 */
function drawDel<T = any>(uuid: string) {
  return post<T>({
    url: `/draw/del/${uuid}`,
  })
}

function drawSetPublic<T = any>(uuid: string, isPublic: boolean) {
  return post<T>({
    url: `/draw/set-public/${uuid}?isPublic=${isPublic}`,
  })
}

/**
 * 删除作图任务中的一张图片
 * @param uuid 作图任务uuid
 * @param fileUuid 图片文件uuid
 * @returns
 */
function drawFileDel<T = any>(uuid: string, fileUuid: string) {
  return post<T>({
    url: `/draw/file/del/${fileUuid}?uuid=${uuid}`,
  })
}

function drawPublicImage<T = any>(uuid: string, imageUuid: string) {
  return post<T>({
    url: `/draw/public/image/${uuid}/${imageUuid}`,
  })
}

function drawThumbnailImage<T = any>(uuid: string, imageUuid: string) {
  return post<T>({
    url: `/draw/public/thumnbnail/${uuid}/${imageUuid}`,
  })
}

function messageDel<T = any>(uuid: string) {
  return post<T>({
    url: `/character/message/del/${uuid}`,
  })
}

function messageTextByAudio<T = any>(audioUuid: string) {
  return get<T>({
    url: `/character/message/text/${audioUuid}`,
  })
}

function memoryEmbeddingRef<T = any>(msgUuid: string) {
  return get<T>({
    url: `/character/message/memory-embedding-ref/${msgUuid}`,
  })
}

function knowledgeEmbeddingRef<T = any>(msgUuid: string) {
  return get<T>({
    url: `/character/message/knowledge-embedding-ref/${msgUuid}`,
  })
}

function messageGraphRef<T = any>(msgUuid: string) {
  return get<T>({
    url: `/character/message/graph-ref/${msgUuid}`,
  })
}

function messageKeywordRef<T = Chat.KeywordReference>(msgUuid: string) {
  return get<T>({
    url: `/character/message/keyword-ref/${msgUuid}`,
  })
}

function knowledgeBaseSearchMine<T>(keyword: string, currentPage: number, pageSize: number, includeVisible?: boolean) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  const includeOthers = includeVisible ? '&includeVisible=true' : ''
  return get<T>({
    url: `/knowledge-base/mine/search?${search}currentPage=${currentPage}&pageSize=${pageSize}${includeOthers}`,
  })
}

function knowledgeBaseSearchTeam<T>(keyword: string, currentPage: number, pageSize: number) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/knowledge-base/team/search?${search}currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function knowledgeBaseSearchCompany<T>(keyword: string, currentPage: number, pageSize: number) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/knowledge-base/company/search?${search}currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function knowledgeBaseTransfer<T = any>(kbUuid: string, ownerType: string, teamUuid?: string) {
  return post<T>({
    url: '/knowledge-base/transfer',
    data: {
      kbUuid,
      ownerType,
      teamUuid,
    },
  })
}

function teamSearchMine<T = any>(keyword: string, currentPage: number, pageSize: number) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/team/my/search?${search}currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function teamMyLite<T = any>() {
  return get<T>({
    url: '/team/my/lite',
  })
}

function teamSaveOrUpdate<T = any>(obj: Team.EditReq) {
  return post<T>({
    url: '/team/saveOrUpdate',
    data: obj,
  })
}

function teamDelete<T = any>(uuid: string) {
  return post<T>({
    url: `/team/del/${uuid}`,
  })
}

function teamMemberList<T = any>(teamUuid: string) {
  return get<T>({
    url: `/team/member/list?teamUuid=${teamUuid}`,
  })
}

function teamMemberAdd<T = any>(obj: Team.MemberAddReq) {
  return post<T>({
    url: '/team/member/add',
    data: obj,
  })
}

function teamMemberUpdateRole<T = any>(obj: Team.MemberUpdateReq) {
  return post<T>({
    url: '/team/member/updateRole',
    data: obj,
  })
}

function teamMemberRemove<T = any>(teamUuid: string, userId: string) {
  return post<T>({
    url: `/team/member/remove/${teamUuid}/${userId}`,
  })
}

function teamLeave<T = any>(uuid: string) {
  return post<T>({
    url: `/team/leave/${uuid}`,
  })
}

function knowledgeBaseSaveOrUpdate<T = any>(obj: KnowledgeBase.Info) {
  return post<T>({
    url: '/knowledge-base/saveOrUpdate',
    data: obj,
  })
}

function knowledgeBaseInfo<T = any>(uuid: string) {
  return get<T>({
    url: `/knowledge-base/info/${uuid}`,
  })
}

function knowledgeBaseStar<T = any>(uuid: string) {
  return post<T>({
    url: `/knowledge-base/star/toggle?kbUuid=${uuid}`,
  })
}

function knowledgeBaseDelete<T = any>(uuid: string) {
  return post<T>({
    url: `/knowledge-base/del/${uuid}`,
  })
}

function knowledgeBaseItemsIndexing<T = any>(uuids: string[], indexTypes: string[]) {
  return post<T>({
    url: '/knowledge-base/item/indexing-list',
    data: {
      uuids,
      indexTypes,
    },
  })
}

function knowledgeBaseIndexingCheck<T = any>(kbUuid?: string) {
  return get<T>({
    url: kbUuid
      ? `/knowledge-base/indexing/check?kbUuid=${encodeURIComponent(kbUuid)}`
      : '/knowledge-base/indexing/check',
  })
}

function knowledgeBaseItemSearch<T>(currentPage: number, pageSize: number, kbUuid: string, keyword?: string) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/knowledge-base-item/search?${search}kbUuid=${kbUuid}&currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function knowledgeBaseItemSaveOrUpdate<T = any>(obj: KnowledgeBase.Item) {
  return post<T>({
    url: '/knowledge-base-item/saveOrUpdate',
    data: obj,
  })
}

function knowledgeBaseItemDelete<T = any>(uuid: string) {
  return post<T>({
    url: `/knowledge-base-item/del/${uuid}`,
  })
}

function knowledgeBaseEmbedding<T = any>(kbItemUuid: string, currentPage: number, pageSize: number) {
  return get<T>({
    url: `/knowledge-base-embedding/list/${kbItemUuid}?currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function knowledgeBaseGraph<T = any>(kbItemUuid: string, maxVertextId: number, maxEdgeId: number, limit: number) {
  return get<T>({
    url: `/knowledge-base-graph/list/${kbItemUuid}?limit=${limit}&maxEdgeId=${maxEdgeId}&maxVertexId=${maxVertextId}`,
  })
}

function knowledgeBaseEmbeddingRef<T = any>(recordUuid: string) {
  return get<T>({
    url: `/knowledge-base/qa/embedding-ref/${recordUuid}`,
  })
}

function knowledgeBaseGraphRef<T = any>(recordUuid: string) {
  return get<T>({
    url: `/knowledge-base/qa/graph-ref/${recordUuid}`,
  })
}

function knowledgeBaseQaSseAsk(params: {
  options: { qaRecordUuid: string }
  signal?: AbortSignal
  startCallback: (chunk: string) => void
  messageReceived: (chunk: string, eventName?: string) => void
  thinkingDataReceived: (chunk: string) => void
  doneCallback: (chunk: string) => void
  errorCallback: (error: string) => void
}): Promise<void> {
  return commonSseProcess(`/api/knowledge-base/qa/process/${params.options.qaRecordUuid}`, params)
}

function knowledgeBaseQaRecordSearch<T = any>(kbUuid: string, keyword: string, currentPage: number, pageSize: number) {
  return get<T>({
    url: `/knowledge-base/qa/search?kbUuid=${encodeURIComponent(kbUuid)}&keyword=${encodeURIComponent(keyword)}&currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function knowledgeBaseQaRecordAdd<T = any>(uuid: string, QaContent: { question: string; modelName: string }) {
  return post<T>({
    url: `/knowledge-base/qa/add/${uuid}`,
    data: QaContent,
  })
}

function knowledgeBaseQaRecordDel<T = any>(uuid: string) {
  return post<T>({
    url: `/knowledge-base/qa/del/${uuid}`,
  })
}

function knowledgeBaseQaRecordClear<T = any>(kbUuid: string) {
  return post<T>({
    url: `/knowledge-base/qa/clear/${kbUuid}`,
  })
}

function knowledgeBaseStarListMine<T = any>(currentPage: number, pageSize: number) {
  return get<T>({
    url: `/knowledge-base/star/mine?currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function loadLLMs<T = any>() {
  return get<T>({
    url: '/model/llms',
  })
}

function loadRerankModels<T = any>() {
  return get<T>({
    url: '/model/rerankModels',
  })
}

function loadImageModels<T = any>() {
  return get<T>({
    url: '/model/imageModels',
  })
}

function loadFileContent(fileUrl: string) {
  return getRawAxios().get(fileUrl, {
    responseType: 'text',
  })
}

function workflowAdd<T = any>(data: { title: string; remark: string; isPublic: boolean }) {
  return post<T>({
    url: '/workflow/add',
    data,
  })
}

function workflowCopy<T = any>(wfUuid: string) {
  return post<T>({
    url: `/workflow/copy/${wfUuid}`,
  })
}

function workflowUpdate<T = any>(data: Workflow.WorkflowUpdateReq) {
  return post<T>({
    url: '/workflow/update',
    data,
  })
}

function workflowDel<T = any>(uuid: string) {
  return post<T>({
    url: `/workflow/del/${uuid}`,
  })
}

function workflowSetPublic<T = any>(uuid: string, isPublic?: boolean) {
  return post<T>({
    url: `/workflow/set-public/${uuid}?isPublic=${isPublic}`,
  })
}

function workflowBaseInfoUpdate<T = any>(data: { uuid: string; title: string; remark: string }) {
  return post<T>({
    url: '/workflow/base-info/update',
    data,
  })
}

function workflowRun(params: {
  options: { uuid: string; inputs: Workflow.UserInput[] }
  signal?: AbortSignal
  startCallback: (chunk: string) => void
  messageReceived: (chunk: string, eventName: string) => void
  thinkingDataReceived: (chunk: string) => void
  doneCallback: (chunk: string) => void
  errorCallback: (error: string) => void
}): Promise<void> {
  return commonSseProcess(`/api/workflow/run/${params.options.uuid}`, params)
}

function workflowRuntimeResume<T = any>(params: {
  runtimeUuid: string
  feedbackContent: string
}) {
  return post<T>({
    url: `/workflow/runtime/resume/${params.runtimeUuid}`,
    data: {
      ...params,
    },
  })
}

function workflowComponents<T = any>() {
  return get<T>({
    url: '/workflow/public/component/list',
  })
}

function workflowSearchMine<T = any>(keyword: string, currentPage: number, pageSize: number) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/workflow/mine/search?${search}currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function workflowSearchPublic<T = any>(keyword: string, currentPage: number, pageSize: number) {
  const search = keyword === undefined ? '' : `keyword=${encodeURIComponent(keyword)}&`
  return get<T>({
    url: `/workflow/public/search?${search}currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function workflowRuntimes<T = any>(wfUuid: string, currentPage: number, pageSize: number) {
  return get<T>({
    url: `/workflow/runtime/page?wfUuid=${wfUuid}&currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function workflowRuntimeNodes<T = any>(wfRuntimeUuid: string) {
  return get<T>({
    url: `/workflow/runtime/nodes/${wfRuntimeUuid}`,
  })
}

function workflowRuntimeDetail<T = any>(wfRuntimeUuid: string) {
  return get<T>({
    url: `/workflow/runtime/${wfRuntimeUuid}`,
  })
}

function workflowRuntimeNodeDetail<T = any>(wfRuntimeUuid: string, runtimeNodeUuid: string) {
  return get<T>({
    url: `/workflow/runtime/nodes/${wfRuntimeUuid}/${runtimeNodeUuid}`,
  })
}

function workflowRuntimeCancel<T = any>(wfRuntimeUuid: string) {
  return post<T>({
    url: `/workflow/runtime/cancel/${wfRuntimeUuid}`,
  })
}

function workflowRuntimesClear<T = any>(wfUuid: string) {
  return post<T>({
    url: `/workflow/runtime/clear?wfUuid=${encodeURIComponent(wfUuid)}`,
  })
}

function workflowOperators<T = any>() {
  return get<T>({
    url: '/workflow/public/operators',
  })
}

function workflowRuntimeDelete<T = any>(wfRuntimeUuid: string) {
  return post<T>({
    url: `/workflow/runtime/del/${wfRuntimeUuid}`,
  })
}

function mcpSearch<T = any>(keyword: string, currentPage: number, pageSize: number) {
  return get<T>({
    url: `/mcp/public/search?keyword=${encodeURIComponent(keyword)}&currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function userMcpList<T = any>(currentPage: number, pageSize: number) {
  return get<T>({
    url: `/user/mcp/list?currentPage=${currentPage}&pageSize=${pageSize}`,
  })
}

function userMcpSaveOrUpdate<T = any>(data: Mcp.UserMcpUpdateReq) {
  return post<T>({
    url: '/user/mcp/saveOrUpdate',
    data,
  })
}

// External API Key management (resource-level)
function extApiKeyGenerate<T = any>(type: string, uuid: string) {
  return post<T>({ url: `/external-api-key/${type}/${uuid}` })
}
function extApiKeyInfo<T = any>(type: string, uuid: string) {
  return get<T>({ url: `/external-api-key/${type}/${uuid}` })
}
function extApiKeyReveal<T = any>(type: string, uuid: string) {
  return post<T>({ url: `/external-api-key/${type}/${uuid}/reveal` })
}

// External API Key management (user-level: draw, mcp)
function extApiKeyUserGenerate<T = any>(type: string) {
  return post<T>({ url: `/external-api-key/user/${type}` })
}
function extApiKeyUserInfo<T = any>(type: string) {
  return get<T>({ url: `/external-api-key/user/${type}` })
}
function extApiKeyUserReveal<T = any>(type: string) {
  return post<T>({ url: `/external-api-key/user/${type}/reveal` })
}

export default {
  getSysConfig,
  login,
  register,
  logout,
  passwordReset,
  passwordFind,
  modifyPassword,
  fetchUserConfig,
  userEdit,
  fetchCharacters,
  characterAdd,
  characterAddByPreset,
  characterEdit,
  characterDel,
  searchPresetCharacters,
  listCharacterPresetRels,
  fetchMessages,
  fetchConversations,
  fetchDefaultConversation,
  conversationAdd,
  conversationEdit,
  conversationDelete,
  fetchConversationMessages,
  sseProcess,
  searchPrompts,
  promptsSave,
  promptDel,
  promptEdit,
  promptAutocomplete,
  fileUpload,
  fileDel,
  fetchDraw,
  fetchNewerPublicDraw,
  fetchOlderPublicDraw,
  fetchNewerStarredDraw,
  fetchOlderStarredDraw,
  fetchNewerMineDraw,
  fetchOlderMineDraw,
  fetchDraws,
  fetchStarDraws,
  fetchPublicDraws,
  fetchDrawComments,
  drawFileDel,
  drawStarOrUnStar,
  imageGenerate,
  drawDel,
  drawSetPublic,
  drawPublicImage,
  drawThumbnailImage,
  drawCommentAdd,
  messageDel,
  messageTextByAudio,
  memoryEmbeddingRef,
  knowledgeEmbeddingRef,
  messageGraphRef,
  messageKeywordRef,
  knowledgeBaseInfo,
  knowledgeBaseStar,
  knowledgeBaseSearchMine,
  knowledgeBaseSearchTeam,
  knowledgeBaseSearchCompany,
  knowledgeBaseTransfer,
  teamSearchMine,
  teamMyLite,
  teamSaveOrUpdate,
  teamDelete,
  teamMemberList,
  teamMemberAdd,
  teamMemberUpdateRole,
  teamMemberRemove,
  teamLeave,
  knowledgeBaseSaveOrUpdate,
  knowledgeBaseDelete,
  knowledgeBaseItemSaveOrUpdate,
  knowledgeBaseItemSearch,
  knowledgeBaseItemDelete,
  knowledgeBaseItemsIndexing,
  knowledgeBaseIndexingCheck,
  knowledgeBaseEmbedding,
  knowledgeBaseGraph,
  knowledgeBaseQaSseAsk,
  knowledgeBaseQaRecordSearch,
  knowledgeBaseQaRecordAdd,
  knowledgeBaseQaRecordDel,
  knowledgeBaseQaRecordClear,
  knowledgeBaseEmbeddingRef,
  knowledgeBaseGraphRef,
  knowledgeBaseStarListMine,
  loadSearchEngines,
  loadLLMs,
  loadRerankModels,
  loadImageModels,
  loadFileContent,
  workflowAdd,
  workflowCopy,
  workflowUpdate,
  workflowSetPublic,
  workflowDel,
  workflowBaseInfoUpdate,
  workflowRun,
  workflowComponents,
  workflowSearchPublic,
  workflowSearchMine,
  workflowRuntimes,
  workflowRuntimeNodes,
  workflowRuntimeDetail,
  workflowRuntimeNodeDetail,
  workflowRuntimeCancel,
  workflowRuntimesClear,
  workflowOperators,
  workflowRuntimeDelete,
  workflowRuntimeResume,
  mcpSearch,
  userMcpList,
  userMcpSaveOrUpdate,
  extApiKeyGenerate,
  extApiKeyInfo,
  extApiKeyReveal,
  extApiKeyUserGenerate,
  extApiKeyUserInfo,
  extApiKeyUserReveal,
}
