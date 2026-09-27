<script setup lang='ts'>
import type { Ref } from 'vue'
import { computed, inject, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCollapse, NCollapseItem, NDrawer, NDrawerContent, NModal, NSpin, NTabPane, NTabs, useDialog, useLoadingBar, useMessage } from 'naive-ui'
import { v4 as uuidv4 } from 'uuid'
import { AudioMessage, Message } from './components'
import { useScroll } from './hooks/useScroll'
import { useChat } from './hooks/useChat'
import { useCopyCode } from './hooks/useCopyCode'
import HeaderComponent from './components/Header/index.vue'
import PcHeader from './components/Header/pc.vue'
import InputToolbar from './InputToolbar.vue'
import InputEditor from './InputEditor.vue'
import RefGraph from './RefGraph.vue'
import RefMemory from './RefMemory.vue'
import RefKeyword from './RefKeyword.vue'
import AnswerEvidenceActions from './components/AnswerEvidenceActions.vue'
import EvidenceMarkdown from './components/EvidenceMarkdown.vue'
import LoginTip from '@/views/user/LoginTip.vue'
import brandLogo from '@/assets/zhimesh-logo.svg'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore, useAuthStore, useChatStore, useKbStore, useWfStore } from '@/store'
import { getDefaultCharacter } from '@/store/modules/chat/helper'
import { AUDIO_SYNTHESIZER_SIDE, CHAT_MESSAGE_CONTENT_TYPE } from '@/utils/constant'
import { SvgIcon } from '@/components/common'
import api from '@/api'
import { t } from '@/locales'
import { debounce } from '@/utils/functions/debounce'
import { emptyAudioPlayState } from '@/utils/functions'
import { openDeleteDialog } from '@/utils/dialog'
let controller = new AbortController()
let chatRequestGeneration = 0

const pageSize = 10
const appStore = useAppStore()
const route = useRoute()
const router = useRouter()
const ms = useMessage()
const dialog = useDialog()
const chatStore = useChatStore()
const kbStore = useKbStore()
const wfStore = useWfStore()
const authStore = useAuthStore()
const loaddingBar = useLoadingBar()
const { isMobile } = useBasicLayout()
const { unshiftAnswer, updateMessageSomeFields, appendChunk } = useChat()
const { scrollRef, scrollToBottom, scrollToBottomIfAtBottom, scrollTo, scrollToTop } = useScroll()
const { uuid: curCharacterUuid } = route.params as { uuid: string }
const initialConversationUuid = typeof route.query.conversation === 'string'
  ? route.query.conversation
  : ''
const conversationEnabled = computed(() => appStore.sysConfigInfo.conversationEnabled)
const curConversationUuid = computed(() => conversationEnabled.value ? initialConversationUuid : '')
const chatKey = computed(() => curConversationUuid.value || curCharacterUuid)
const regenerateQuestionUuid = ref<string>('')
const inputEditorRef = ref()
const mobileToolsOpen = ref(false)
const tabsActiveTab = ref<string[]>([])
const messages = computed(() => {
  return chatStore.getMsgsByCharacter(chatKey.value)
})
const currCharacter = computed(() => chatStore.getCharacterByUuid(curCharacterUuid) || getDefaultCharacter())
const currentHistory = computed(() => curConversationUuid.value
  ? (chatStore.conversations.find(item => item.uuid === curConversationUuid.value) || null)
  : currCharacter.value)
const hasCharacter = computed(() => !!currCharacter.value.uuid && currCharacter.value.uuid !== 'default')
const needsConversation = computed(() => hasCharacter.value && conversationEnabled.value && !curConversationUuid.value)
const canChat = computed(() => hasCharacter.value && !needsConversation.value)
const openCharacterCreator = inject<(tab?: 'presetCharacter' | 'newCharacter') => void>('openCharacterCreator', () => {})
const createConversation = inject<(character?: Chat.Character | null) => Promise<void>>('createConversation', async () => {})
const creatingConversation = inject<Ref<boolean>>('creatingConversation', ref(false))
const openPromptStore = inject<() => void>('openPromptStore', () => {})
const imageUuids = ref<string[]>([])
const isChatting = ref<boolean>(false)
const loadingMsgs = ref<boolean>(false)
const loaddingEmbeddingRef = ref<boolean>(false)
const inputRef = ref<Ref | null>(null)
const showMemoryModal = ref<boolean>(false)
const selectedMemoryMsgUuid = ref<string>('')
const showRefEmbeddingModal = ref<boolean>(false)
const showRefEmbeddingMsgUuid = ref<string>('')
const knowledgeEmbeddingRef = ref<KnowledgeBase.QaRecordEmbeddingRef[]>([])
const showRefGraphModal = ref<boolean>(false)
const showRefGraphMsgUuid = ref<string>('')
const showKeywordModal = ref<boolean>(false)
const selectedKeywordMsgUuid = ref<string>('')

let prevScrollTop: number
let restoreBottomOnActivate = true
useCopyCode()

// 未知原因刷新页面，loading 状态不会重置，手动重置
messages.value.forEach((item: { loading?: boolean; uuid: string }) => {
  if (item.loading)
    updateMessageSomeFields(chatKey.value, item.uuid, { loading: false })
})

function sseStarted() {
  nextTick(() => {
    scrollToBottom()
  })
}

function chatMessageReceiving(questionUuid: string) {
  nextTick(() => {
    scrollToBottomIfAtBottom()
  })
}

function messageComplelted(questionUuid: string) {
  nextTick(() => {
    scrollToBottom()
  })
}

function handleStop() {
  if (isChatting.value) {
    chatRequestGeneration++
    controller.abort()
    isChatting.value = false
  }
  inputEditorRef.value?.handleStop()
}

// 打开记忆
function handleMemoryRefClick(qaRecordUuid: string) {
  showMemoryModal.value = true
  selectedMemoryMsgUuid.value = qaRecordUuid
}

// 打开知识库引用
async function handleEmbeddingRefClick(qaRecordUuid: string) {
  showRefEmbeddingModal.value = true
  showRefEmbeddingMsgUuid.value = qaRecordUuid
  knowledgeEmbeddingRef.value = []
  knowledgeEmbeddingRef.value = chatStore.getReferences(qaRecordUuid)
  if (knowledgeEmbeddingRef.value.length === 0) {
    loaddingEmbeddingRef.value = true
    try {
      const { data } = await api.knowledgeEmbeddingRef(qaRecordUuid)
      chatStore.setKnowledgeEmbeddingRefs(qaRecordUuid, data)

      // 显示最后一次点击的引用，避免较早的请求覆盖当前选择。
      if (showRefEmbeddingMsgUuid.value === qaRecordUuid)
        knowledgeEmbeddingRef.value = chatStore.getReferences(qaRecordUuid)
    } catch (error) {
      if (showRefEmbeddingMsgUuid.value === qaRecordUuid)
        ms.error(t('common.wrong'))
      console.error('load knowledge references failed', error)
    } finally {
      if (showRefEmbeddingMsgUuid.value === qaRecordUuid)
        loaddingEmbeddingRef.value = false
    }
  }
}

async function handleGraphClick(msgUuid: string) {
  showRefGraphModal.value = true
  showRefGraphMsgUuid.value = msgUuid
}

function handleKeywordClick(msgUuid: string) {
  selectedKeywordMsgUuid.value = msgUuid
  showKeywordModal.value = true
}

const fetchChatAPIOnce = async (regenerateQuestionUuid: string, childAudioPlayState: AudioPlayState, requestId: number) => {

  const characterUuid = currCharacter.value.uuid
  const requestConversationUuid = curConversationUuid.value
  const requestChatKey = requestConversationUuid || characterUuid
  const requestQuestion = messages.value.find((q: { uuid: string }) => q.uuid === regenerateQuestionUuid)
  if (!requestQuestion) {
    ms.error(t('chat.questionNotFound'))
    return
  }
  const character = chatStore.getCharacterByUuid(characterUuid)
  if (!character) {
    ms.error(t('chat.characterNotFound'))
    return
  }
  const isCurrentRequest = () => requestId === chatRequestGeneration
  // 流式渲染批量 flush：chunk 先入请求闭包内的缓冲（每请求独立，防 regenerate/多页签串包），
  // ~60ms 定时一次性 appendChunk，消除逐字符 append 触发全量 markdown 重渲染的 O(n²) 卡顿；
  // done/suspension/error 入口先同步 flush，防丢尾字符、防挂起卡片与文本错位
  let textBuffer = ''
  let thinkingBuffer = ''
  let flushTimer: ReturnType<typeof setTimeout> | undefined
  const flushBuffers = () => {
    if (flushTimer) {
      clearTimeout(flushTimer)
      flushTimer = undefined
    }
    const question = requestQuestion
    if (!question || !isCurrentRequest()) {
      textBuffer = ''
      thinkingBuffer = ''
      return
    }
    try {
      if (thinkingBuffer) {
        appendChunk(
          requestChatKey,
          question.children[0].uuid,
          thinkingBuffer,
          true, // thinking is true
        )
        chatMessageReceiving(question.uuid)
      }
      if (textBuffer) {
        appendChunk(
          requestChatKey,
          question.children[0].uuid,
          textBuffer,
        )
        chatMessageReceiving(question.uuid)
      }
    } catch (error) {
      console.error(error)
    }
    textBuffer = ''
    thinkingBuffer = ''
  }
  return api.sseProcess({
    options: {
      prompt: '',
      characterUuid,
      conversationUuid: requestConversationUuid || undefined,
      regenerateQuestionUuid,
      modelPlatform: appStore.selectedLLM.modelPlatform,
      modelName: appStore.selectedLLM.modelName,
      imageUrls: imageUuids.value,
      audioUuid: '',
      audioDuration: 0,
    },
    signal: controller.signal,
    startCallback(_chunk) {
      if (isCurrentRequest())
        sseStarted()
    },
    stateChanged: (state) => {
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question)
        return

      try {
        question.state = new Map(Object.entries(JSON.parse(state)))
      } catch (error) {
        console.warn('Invalid SSE state payload', error)
      }
    },
    thinkingDataReceived: (chunk) => {
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question) {
        ms.error(t('chat.questionNotFound'))
        return
      }
      thinkingBuffer += chunk
      if (!flushTimer)
        flushTimer = setTimeout(flushBuffers, 60)
      // 推理阶段无需显示状态
      question.state = new Map<string, string>()
    },
    messageReceived: (chunk) => {
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question) {
        ms.error(t('chat.questionNotFound'))
        return
      }
      textBuffer += chunk
      if (!flushTimer)
        flushTimer = setTimeout(flushBuffers, 60)
      const answerContentType = chatStore.answerContentType(character, question.audioUuid)
      const ttsPartText = chunk.replace('\n', '')
      if (ttsPartText && appStore.audioSynthesizerSide === AUDIO_SYNTHESIZER_SIDE.client && answerContentType === CHAT_MESSAGE_CONTENT_TYPE.audio && character.isAutoplayAnswer) {
        // settimeout是防止执行太快导致 AudioMessage 中的 watch 没有触发
        setTimeout(() => {
          childAudioPlayState.msgPart = chunk
        }, 0)
      }
    },
    audioDataReceived(audioFrame) {
      if (!isCurrentRequest())
        return
      // AudioMessage 监听pcmPart的变化并决定要不要自动播放
      if (appStore.audioSynthesizerSide !== AUDIO_SYNTHESIZER_SIDE.client && audioFrame)
        childAudioPlayState.audioFrame = audioFrame
    },
    toolStartedReceived: (data) => {
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question)
        return
      // 工具开始执行：先点亮一条 running 步骤，完成事件（[TOOL_CALL]）再回填时长/结果
      const answer = question.children[0]
      if (!answer.toolCalls)
        answer.toolCalls = []
      answer.toolCalls.push({
        toolName: data.toolName,
        args: data.args,
        running: true,
        durationMs: 0,
        success: true,
      })
    },
    toolCallReceived: (data) => {
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question)
        return
      const answer = question.children[0]
      if (!answer.toolCalls)
        answer.toolCalls = []
      // 回填策略：从后往前找同名 running 步骤（[TOOL_STARTED] 已点亮）回填；
      // 未命中（无 started 的完成事件/历史回放/乱序）按旧逻辑直插，兼容 TOOL_LIMIT_MARKER
      let runningIdx = -1
      for (let i = answer.toolCalls.length - 1; i >= 0; i--) {
        const tool = answer.toolCalls[i]
        if (tool.toolName === data.toolName && tool.running === true) {
          runningIdx = i
          break
        }
      }
      if (runningIdx !== -1) {
        const step = answer.toolCalls[runningIdx]
        step.running = false
        step.durationMs = data.durationMs
        step.success = data.success
        if (data.resultSummary !== undefined)
          step.resultSummary = data.resultSummary
      } else {
        answer.toolCalls.push(data)
      }
    },
    suspensionReceived: (payload) => {
      // 先同步 flush 已缓冲文本，防挂起卡片与文本错位
      flushBuffers()
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question)
        return
      // 挂起事件：在回答消息上挂卡片载荷（可交互），并在步骤条补挂起节点
      const answer = question.children[0]
      answer.suspension = payload
      answer.suspensionActive = true
      if (!answer.toolCalls)
        answer.toolCalls = []
      // 同名 running 步骤原地翻挂起节点：协作工具（ask_user 等）的 [TOOL_STARTED]
      // 已点亮一条 running 行，挂起路径后端不会再发 [TOOL_CALL]，直接 push 会双行
      // 且 spinner 无人回填；未命中（无 started 的异常序/历史路径）才补新行
      // Rewrite the same-name running step in place: collaborative tools
      // already lit a running row via [TOOL_STARTED] and the suspension path
      // never sends [TOOL_CALL], so pushing would duplicate the row and leave
      // a spinner unbackfilled; append only on a miss (abnormal order/history)
      const toolName = payload.toolName || ''
      let suspendIdx = -1
      for (let i = answer.toolCalls.length - 1; i >= 0; i--) {
        const tool = answer.toolCalls[i]
        if (tool.toolName === toolName && tool.running === true) {
          suspendIdx = i
          break
        }
      }
      if (suspendIdx !== -1) {
        const step = answer.toolCalls[suspendIdx]
        step.running = false
        step.resultSummary = payload.question
        step.suspensionKind = payload.kind ?? payload.type
      } else {
        answer.toolCalls.push({
          toolName,
          durationMs: 0,
          success: true,
          resultSummary: payload.question,
          suspensionKind: payload.kind ?? payload.type,
        })
      }
    },
    doneCallback: (chunk) => {
      // 先同步 flush 已缓冲文本，防丢尾字符
      flushBuffers()
      if (!isCurrentRequest())
        return
      const question = requestQuestion
      if (!question) {
        ms.error(t('chat.questionNotFound'))
        return
      }
      const answer = question.children[0]
      if (chunk.includes('[META]')) {
        const meta = chunk.replace('[META]', '')
        let metaData: Chat.MetaData
        try {
          metaData = JSON.parse(meta)
        } catch (error) {
          console.error('Invalid SSE metadata payload', error)
          updateMessageSomeFields(requestChatKey, question.uuid, { loading: false })
          updateMessageSomeFields(requestChatKey, answer.uuid, { remark: `${t('common.systemTip')}${t('common.wrong')}`, loading: false })
          isChatting.value = false
          return
        }
        if (metaData.conversationUuid && metaData.conversationUuid !== requestConversationUuid)
          console.error('SSE conversation mismatch', { expected: requestConversationUuid, actual: metaData.conversationUuid })
        updateMessageSomeFields(requestChatKey, question.uuid, { ...metaData.question, inputTokens: metaData.question.inputTokens, loading: false, state: new Map<string, string>() })
        // inputTokens/outputTokens 由 AnswerMeta 直接提供 | inputTokens/outputTokens provided directly by AnswerMeta
        // 挂起载荷兜底：实时事件未达时 [META] 同样携带（键名 type）；实时载荷（kind+
        // toolName 更全）优先防覆盖，兜底命中才置交互态（原判断在 assign 后恒 false 属死代码）
        const liveSuspension = answer.suspension
        updateMessageSomeFields(requestChatKey, answer.uuid, { ...metaData.answer, suspension: liveSuspension || metaData.answer.suspension, inputTokens: metaData.answer.inputTokens, outputTokens: metaData.answer.outputTokens, duration: metaData.answer.duration, uuid: answer.uuid, loading: false })
        if (answer.suspension && !answer.suspensionActive)
          answer.suspensionActive = true
        if (metaData.audioInfo) {
          answer.audioPlayState.audioUrl = metaData.audioInfo.url
          answer.audioDuration = metaData.audioInfo.duration
          answer.audioUuid = metaData.audioInfo.uuid
        }
      } else {
        updateMessageSomeFields(requestChatKey, regenerateQuestionUuid, { loading: false })
        updateMessageSomeFields(requestChatKey, answer.uuid, { uuid: answer.uuid, loading: false })
      }
      messageComplelted(regenerateQuestionUuid)
      isChatting.value = false
    },
    errorCallback: (error) => {
      // 先同步 flush 已缓冲文本，防错误提示覆盖丢尾字符
      flushBuffers()
      if (!isCurrentRequest())
        return
      ms.warning(error)
      isChatting.value = false
      const question = requestQuestion
      if (!question) {
        ms.error(t('chat.questionNotFound'))
        return
      }
      updateMessageSomeFields(requestChatKey, question.children[0].uuid, { remark: `${t('common.systemTip')}${error}`, loading: false })
    },
  })
}

async function onRegenerate(questionUuid: string) {
  if (isChatting.value)
    return

  regenerateQuestionUuid.value = questionUuid
  const message = chatStore.getMsgByCurCharacter(questionUuid)
  if (!message)
    return

  // 重新生成把 ACTIVE 检查点置 SUPERSEDED：旧答案页签上的挂起卡片全部翻只读，点击旧
  // 卡不再发出游离的用户消息
  messages.value.forEach((qa) => {
    qa.children?.forEach((child) => {
      if (child.suspensionActive)
        child.suspensionActive = false
    })
  })

  isChatting.value = true
  const requestId = ++chatRequestGeneration
  controller = new AbortController()

  try {
    const answerContentType = chatStore.answerContentType(currCharacter.value, message.audioUuid)
    const answerUuid = uuidv4().replace(/-/g, '')
    const audioPlayState = emptyAudioPlayState()
    unshiftAnswer(
      chatKey.value,
      questionUuid,
      {
        uuid: answerUuid,
        contentType: answerContentType,
        createTime: new Date().toLocaleString(),
        thinkingContent: '',
        remark: '',
        audioUuid: '',
        audioUrl: '',
        audioDuration: 0,
        children: [],
        inversion: false,
        error: false,
        loading: true,
        attachmentUrls: [],
        isRefMemoryEmbedding: false,
        isRefEmbedding: false,
        isRefGraph: false,
        isRefBm25: false,
        aiModelId: appStore.selectedLLM.modelId,
        aiModelPlatform: appStore.selectedLLM.modelPlatform,
        audioPlayState,
      },
    )
    await fetchChatAPIOnce(questionUuid, audioPlayState, requestId)
    selectedLatestAnswer(questionUuid)
  } catch (error: any) {
    console.error(error)
    ms.error(error ?? 'error')
  } finally {
    isChatting.value = false
  }
}

function selectedLatestAnswer(questionUuid: string) {
  nextTick(() => {
    const index = messages.value.findIndex((msg: { uuid: string }) => msg.uuid === questionUuid)
    if (index !== -1 && messages.value[index].children[0]) {
      tabsActiveTab.value[index] = `tab_${messages.value[index].children[0].uuid}`
    }
  })
}

// 挂起卡片应答（选项/审批结论）：走既有消息发送入口当普通用户消息提交；
// 同时把挂起卡片翻只读并在步骤条补恢复节点
function handleSuspensionAnswer(questionUuid: string, answerUuid: string, text: string) {
  if (isChatting.value) {
    ms.warning(t('chat.suspension.chatInProgress'))
    return
  }
  const question = messages.value.find((msg: { uuid: string }) => msg.uuid === questionUuid)
  const answer = question?.children.find(child => child.uuid === answerUuid)
  // 先提交后翻只读：submitMessage 内部守卫（未登录/未选会话）静默退出时卡片保持可交
  // 互可重试，不出现「卡片已杀死但消息没发出」
  inputEditorRef.value?.submitMessage(text)
  if (answer) {
    answer.suspensionActive = false
    if (!answer.toolCalls)
      answer.toolCalls = []
    answer.toolCalls.push({ toolName: '', durationMs: 0, success: true, resumed: true })
  }
}

async function loadMoreMessage(callback?: Function) {
  if (currentHistory.value?.loadedAll || loadingMsgs.value)
    return

  loadingMsgs.value = true
  loaddingBar.start()
  try {
    const minMsgUuid = currentHistory.value?.minMsgUuid || ''
    const { data } = curConversationUuid.value
      ? await api.fetchConversationMessages(curConversationUuid.value, minMsgUuid, pageSize)
      : await api.fetchMessages<Chat.CharacterMsgListResp>(curCharacterUuid, minMsgUuid, pageSize)

    if (data.msgList.length < pageSize) {
      if (curConversationUuid.value)
        chatStore.updateConversation(curConversationUuid.value, { minMsgUuid: data.minMsgUuid, loadedAll: true })
      else
        chatStore.updateCharacter(curCharacterUuid, { minMsgUuid: data.minMsgUuid, loadedAll: true })
      ms.info(t('common.noMore'))
    } else {
      if (curConversationUuid.value)
        chatStore.updateConversation(curConversationUuid.value, { minMsgUuid: data.minMsgUuid })
      else
        chatStore.updateCharacter(curCharacterUuid, { minMsgUuid: data.minMsgUuid })
    }
    chatStore.unshiftMessages(chatKey.value, data.msgList)
  } catch (error) {
    console.error(`loadMoreMessage${error}`)
  } finally {
    loadingMsgs.value = false
    loaddingBar.finish()

    if (callback)
      callback()
  }
}

const handleLoadMoreMessage = debounce(loadMoreMessage, 300)
async function handleScroll(event: any) {
  const scrollTop = event.target.scrollTop
  const lastScrollClient = event.target.scrollHeight
  if (scrollTop < 50 && (scrollTop < prevScrollTop || prevScrollTop === undefined)) {
    handleLoadMoreMessage(() => {
      nextTick(() => {
        scrollTo(event.target.scrollHeight - lastScrollClient)
      })
    })
  }
  prevScrollTop = scrollTop
}

function handleDelete(questionUuid: string, answerUuid: string, isQuestion = false) {
  if (isChatting.value)
    return

  let tip = t('chat.deleteMessageConfirm')
  if (isQuestion)
    tip = t('chat.deleteQuestionAlsoDeleteAnswer')

  openDeleteDialog(dialog, {
    title: t('chat.deleteMessage'),
    content: tip,
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        if (isQuestion) {
          await api.messageDel(questionUuid)
          chatStore.deleteQuestion(chatKey.value, questionUuid)
        } else {
          await api.messageDel(answerUuid)
          chatStore.deleteAnswer(chatKey.value, questionUuid, answerUuid)
          selectedLatestAnswer(questionUuid)
        }
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

const footerClass = computed(() => {
  let classes = ['p-4']
  if (isMobile.value)
    classes = ['sticky', 'left-0', 'bottom-0', 'right-0', 'p-2', 'pr-3', 'overflow-hidden']
  return classes
})

function handleMobileToolsChange(expanded: boolean) {
  mobileToolsOpen.value = expanded
}

function closeMobileTools() {
  mobileToolsOpen.value = false
  inputEditorRef.value?.setMobileToolsExpanded?.(false)
}

watch(mobileToolsOpen, (expanded) => {
  if (!expanded)
    inputEditorRef.value?.setMobileToolsExpanded?.(false)
})

function openMobileKnowledge() {
  closeMobileTools()
  router.push({ name: 'QADetail', params: { kbUuid: kbStore.activeKbUuid } })
}

function openMobileTools() {
  closeMobileTools()
  router.push({ name: 'Mcp' })
}

function openMobileWorkflow() {
  closeMobileTools()
  router.push({
    name: 'WfDetail',
    params: { uuid: wfStore.activeUuid || 'default' },
  })
}

function openMobilePromptStore() {
  closeMobileTools()
  openPromptStore()
}

function imagesChange(uuids: string[]) {
  imageUuids.value = uuids
}

// The first page is restored asynchronously after refresh. Scroll only when
// that restoration completes (for either a character or a conversation), so
// loading older messages at the top does not unexpectedly jump to the bottom.
watch(
  () => [
    chatKey.value,
    currentHistory.value?.loadedFirstPageMsg,
  ],
  ([, loaded], previous) => {
    const wasLoaded = previous?.[1] === true
    if (loaded === true && !wasLoaded)
      nextTick(() => scrollToBottom())
  },
  { immediate: true },
)

onMounted(() => {
  nextTick(() => {
    scrollToBottom()
  })
  if (inputRef.value && !isMobile.value)
    inputRef.value?.focus()
})

onUnmounted(() => {
  chatRequestGeneration++
  if (isChatting.value)
    controller.abort()
})

onActivated(async () => {
  if (!curCharacterUuid && chatStore.active)
    await chatStore.setActive(chatStore.active)

  if (restoreBottomOnActivate && scrollRef.value)
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
})

onDeactivated(() => {
  const element = scrollRef.value
  restoreBottomOnActivate = !element
    || element.scrollHeight - element.scrollTop - element.clientHeight <= 100
})
</script>

<template>
  <div class="chat-box flex flex-col w-full h-full">
    <HeaderComponent
      v-if="isMobile"
      @scroll-to-top="scrollToTop"
    />
    <PcHeader v-if="!isMobile" :character="currCharacter" />
    <main class="flex-1 overflow-hidden">
      <div ref="scrollRef" class="h-full overflow-hidden overflow-y-auto" @scroll="handleScroll">
        <div
          class="w-full max-w-screen-xl m-auto"
          :class="[isMobile ? 'p-2' : 'p-4', { 'chat-empty-stage': authStore.token && !messages.length }]"
        >
          <template v-if="!authStore.token">
            <LoginTip />
          </template>
          <template v-else-if="!messages.length">
            <div class="chat-onboarding">
              <div class="onboarding-intro">
                <div class="onboarding-mark-shell" aria-hidden="true">
                  <img
                    class="onboarding-mark" :src="brandLogo" alt="" width="64" height="64"
                    draggable="false"
                  >
                </div>
                <div class="onboarding-copy">
                  <h1 v-if="!hasCharacter">{{ t('chat.noCharacterTitle') }}</h1>
                  <h1 v-else>{{ t('chat.startChatWith', { name: currCharacter.title }) }}</h1>
                  <p v-if="!canChat" class="onboarding-lead">
                    {{ !hasCharacter ? t('chat.noCharacterLead') : needsConversation ? t('chat.noConversationLead') : t('chat.readyToChatLead') }}
                  </p>
                </div>
              </div>

              <div class="onboarding-status">
                <div :class="{ complete: hasCharacter }">
                  <span>1</span>
                  <div>
                    <strong>{{ t('chat.chooseCharacterStep') }}</strong>
                    <small>{{ hasCharacter ? currCharacter.title : t('chat.notCompleted') }}</small>
                  </div>
                </div>
                <i :class="{ complete: hasCharacter }" />
                <div :class="{ complete: !conversationEnabled || !!curConversationUuid }">
                  <span>2</span>
                  <div>
                    <strong>{{ t('chat.createConversationStep') }}</strong>
                    <small>{{ curConversationUuid ? (currentHistory?.title || t('chat.completed')) : (conversationEnabled ? t('chat.notCompleted') : t('chat.notRequired')) }}</small>
                  </div>
                </div>
                <i :class="{ complete: !conversationEnabled || !!curConversationUuid }" />
                <div :class="{ complete: canChat }">
                  <span>3</span>
                  <div>
                    <strong>{{ t('chat.sendMessageStep') }}</strong>
                    <small>{{ canChat ? t('chat.ready') : t('chat.waiting') }}</small>
                  </div>
                </div>
              </div>

              <div v-if="!canChat" class="onboarding-actions">
                <template v-if="!hasCharacter">
                  <NButton type="primary" size="large" @click="openCharacterCreator('presetCharacter')">
                    <template #icon><SvgIcon icon="ri:user-star-line" /></template>
                    {{ t('chat.choosePresetCharacter') }}
                  </NButton>
                  <NButton size="large" @click="openCharacterCreator('newCharacter')">
                    {{ t('chat.customCharacter') }}
                  </NButton>
                </template>
                <template v-else-if="needsConversation">
                  <NButton type="primary" size="large" :loading="creatingConversation" @click="createConversation(currCharacter)">
                    <template #icon><SvgIcon icon="ri:chat-new-line" /></template>
                    {{ t('chat.createFirstConversation') }}
                  </NButton>
                  <NButton size="large" @click="openCharacterCreator('presetCharacter')">
                    {{ t('chat.chooseOtherCharacter') }}
                  </NButton>
                </template>
              </div>
            </div>
          </template>

          <template v-else>
            <TransitionGroup name="message-list" tag="div" class="message-thread">
              <div v-for="(qaMessage, index) of messages" :key="qaMessage.uuid" class="pb-3">
              <!-- 用户消息 start -->

              <!-- 多模态的请求消息，携带有附件 -->
              <template v-if="qaMessage.attachmentUrls.length > 0">
                <!-- 语音聊天 -->
                <AudioMessage
                  v-if="qaMessage.contentType === CHAT_MESSAGE_CONTENT_TYPE.audio" :inversion="true"
                  :character="currCharacter" :message-uuid="qaMessage.uuid" :date-time="qaMessage.createTime"
                  :audio-play-state="qaMessage.audioPlayState" :duration="qaMessage.audioDuration"
                  @delete="handleDelete(qaMessage.uuid, '', true)"
                />
                <!-- 文本聊天 -->
                <Message
                  v-else :date-time="qaMessage.createTime" :text="qaMessage.remark"
                  :image-urls="qaMessage.attachmentUrls" type="text-image" :inversion="true" :error="qaMessage.error"
                  :loading="false" @regenerate="onRegenerate(qaMessage.uuid)"
                  @delete="handleDelete(qaMessage.uuid, '', true)"
                />
              </template>
              <!-- 非多模态的请求消息，没有附件 -->
              <template v-if="qaMessage.attachmentUrls.length === 0">
                <!-- 语音聊天 -->
                <AudioMessage
                  v-if="qaMessage.contentType === CHAT_MESSAGE_CONTENT_TYPE.audio" :inversion="true"
                  :character="currCharacter" :message-uuid="qaMessage.uuid" :date-time="qaMessage.createTime"
                  :audio-play-state="qaMessage.audioPlayState" :duration="qaMessage.audioDuration"
                  @delete="handleDelete(qaMessage.uuid, '', true)"
                />
                <!-- 文本聊天 -->
                <Message
                  v-else :date-time="qaMessage.createTime" :text="qaMessage.remark" type="text" :inversion="true"
                  :error="qaMessage.error" :loading="false" @regenerate="onRegenerate(qaMessage.uuid)"
                  @delete="handleDelete(qaMessage.uuid, '', true)"
                />
              </template>

              <!-- 用户消息 end -->

              <!-- LLM回复 start -->
              <!-- LLM的多条回复消息 -->
              <template v-if="qaMessage.children.length > 1">
                <NTabs
                  v-model:value="tabsActiveTab[index]" type="bar" placement="left" size="small"
                  pane-style="padding: 0 0 0 10px;" animated
                >
                  <NTabPane
                    v-for="(answer, index) of qaMessage.children" :key="`tab_${answer.uuid}`"
                    :name="`tab_${answer.uuid}`" :tab="`${t('chat.answer')} ${index + 1}`"
                  >
                    <AudioMessage
                      v-if="answer.contentType === CHAT_MESSAGE_CONTENT_TYPE.audio" :character="currCharacter"
                      :duration="answer.audioDuration" :inversion="false" :message-uuid="answer.uuid"
                      :date-time="answer.createTime" :audio-play-state="answer.audioPlayState" :loading="answer.loading"
                      :ai-model-id="answer.aiModelId" :ai-model-platform="answer.aiModelPlatform"
                      @delete="handleDelete(qaMessage.uuid, answer.uuid)"
                    >
                      <template #actions>
                        <AnswerEvidenceActions
                          :answer="answer" @memory="handleMemoryRefClick" @reference="handleEmbeddingRefClick"
                          @graph="handleGraphClick" @keyword="handleKeywordClick"
                        />
                      </template>
                    </AudioMessage>
                    <Message
                      v-else :show-avatar="false" :date-time="answer.createTime" :thinking="answer.thinking"
                      :thinking-content="answer.thinkingContent" :text="answer.remark" type="text" :inversion="false"
                      :regenerate="true" :regenerate-disabled="isChatting" :error="answer.error" :loading="answer.loading"
                      :input-tokens="answer.inputTokens" :output-tokens="answer.outputTokens"
                      :duration="answer.duration"
                      :tool-calls="answer.toolCalls"
                      :state="qaMessage.state"
                      :suspension="answer.suspension" :suspension-interactive="answer.suspensionActive"
                      :ai-model-id="answer.aiModelId" :ai-model-platform="answer.aiModelPlatform"
                      @regenerate="onRegenerate(qaMessage.uuid)"
                      @delete="handleDelete(qaMessage.uuid, answer.uuid)"
                      @suspension-answer="text => handleSuspensionAnswer(qaMessage.uuid, answer.uuid, text)"
                    >
                      <template #actions>
                        <AnswerEvidenceActions
                          :answer="answer" @memory="handleMemoryRefClick" @reference="handleEmbeddingRefClick"
                          @graph="handleGraphClick" @keyword="handleKeywordClick"
                        />
                      </template>
                    </Message>
                  </NTabPane>
                </NTabs>
              </template>

              <!-- LLM的单条回复消息 -->
              <template v-if="qaMessage.children.length === 1">
                <AudioMessage
                  v-if="qaMessage.children[0].contentType === CHAT_MESSAGE_CONTENT_TYPE.audio"
                  :character="currCharacter" :duration="qaMessage.children[0].audioDuration" :inversion="false"
                  :message-uuid="qaMessage.children[0].uuid" :date-time="qaMessage.children[0].createTime"
                  :audio-play-state="qaMessage.children[0].audioPlayState" :loading="qaMessage.children[0].loading"
                  :ai-model-id="qaMessage.children[0].aiModelId"
                  :ai-model-platform="qaMessage.children[0].aiModelPlatform"
                  @delete="handleDelete(qaMessage.uuid, qaMessage.children[0].uuid)"
                >
                  <template #actions>
                    <AnswerEvidenceActions
                      :answer="qaMessage.children[0]" @memory="handleMemoryRefClick"
                      @reference="handleEmbeddingRefClick" @graph="handleGraphClick" @keyword="handleKeywordClick"
                    />
                  </template>
                </AudioMessage>
                <Message
                  v-else :date-time="qaMessage.children[0].createTime" :thinking="qaMessage.children[0].thinking"
                  :thinking-content="qaMessage.children[0].thinkingContent" :text="qaMessage.children[0].remark"
                  type="text" :inversion="qaMessage.children[0].inversion" :regenerate="true"
                  :regenerate-disabled="isChatting" :error="qaMessage.children[0].error" :loading="qaMessage.children[0].loading"
                  :input-tokens="qaMessage.children[0].inputTokens" :output-tokens="qaMessage.children[0].outputTokens"
                  :duration="qaMessage.children[0].duration"
                  :tool-calls="qaMessage.children[0].toolCalls"
                  :state="qaMessage.state"
                  :suspension="qaMessage.children[0].suspension" :suspension-interactive="qaMessage.children[0].suspensionActive"
                  :ai-model-id="qaMessage.children[0].aiModelId"
                  :ai-model-platform="qaMessage.children[0].aiModelPlatform" @regenerate="onRegenerate(qaMessage.uuid)"
                  @delete="handleDelete(qaMessage.uuid, qaMessage.children[0].uuid)"
                  @suspension-answer="text => handleSuspensionAnswer(qaMessage.uuid, qaMessage.children[0].uuid, text)"
                >
                  <template #actions>
                    <AnswerEvidenceActions
                      :answer="qaMessage.children[0]" @memory="handleMemoryRefClick"
                      @reference="handleEmbeddingRefClick" @graph="handleGraphClick" @keyword="handleKeywordClick"
                    />
                  </template>
                </Message>
              </template>

              <!-- LLM回复 end -->

              </div>
            </TransitionGroup>
          </template>
        </div>
        <div class="sticky bottom-0 left-0 z-10 flex justify-center py-2">
          <NButton v-if="isChatting" size="small" type="primary" secondary round @click="handleStop">
            <template #icon>
              <SvgIcon icon="ri:stop-circle-line" />
            </template>
            {{ t('common.stopRequest') }}
          </NButton>
        </div>
      </div>
    </main>
    <footer v-if="canChat" class="chat-composer-footer" :class="footerClass">
      <div class="chat-input-layout">
        <InputToolbar @images-change="imagesChange" />
        <div class="chat-input-panel">
          <InputEditor
            ref="inputEditorRef" :character-uuid="curCharacterUuid" :conversation-uuid="curConversationUuid"
            :chat-key="chatKey" :image-uuids="imageUuids"
            @sse-started="sseStarted" @message-receiving="chatMessageReceiving" @message-complelted="messageComplelted"
            @is-chatting="(chatting) => isChatting = chatting"
            @mobile-tools-change="handleMobileToolsChange"
          />
        </div>
      </div>
    </footer>

    <NDrawer
      v-model:show="mobileToolsOpen" class="chat-capability-drawer" placement="bottom"
      height="calc(180px + env(safe-area-inset-bottom))" :aria-label="t('chat.resourcesAndTools')"
    >
      <NDrawerContent>
        <div class="chat-capability-grid">
          <button type="button" class="chat-capability-card" @click="openMobileKnowledge">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:book-2-line" /></span>
            <span>{{ t('chat.knowledgeBase') }}</span>
          </button>
          <button type="button" class="chat-capability-card" @click="openMobileWorkflow">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:apps-2-line" /></span>
            <span>{{ t('menu.workflow') }}</span>
          </button>
          <button type="button" class="chat-capability-card" @click="openMobileTools">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:tools-line" /></span>
            <span>{{ t('chat.tools') }}</span>
          </button>
          <button type="button" class="chat-capability-card" @click="openMobilePromptStore">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:book-marked-line" /></span>
            <span>{{ t('store.siderButton') }}</span>
          </button>
        </div>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="showRefEmbeddingModal" style="width: min(640px, 92vw);" preset="card" :title="t('chat.referenceMaterial')">
      <div v-show="knowledgeEmbeddingRef.length === 0" class="flex items-center justify-center h-64">
        <span v-show="!loaddingEmbeddingRef">{{ t('common.noData') }}</span>
        <NSpin v-show="loaddingEmbeddingRef" size="medium" />
      </div>
      <NCollapse v-show="knowledgeEmbeddingRef.length > 0" :default-expanded-names="['refer_0']">
        <NCollapseItem
          v-for="(reference, idx) of knowledgeEmbeddingRef" :key="reference.embeddingId" :title="`${t('chat.reference')}${idx + 1}`"
          :name="`refer_${idx}`"
        >
          <!-- 证据原文可能来自 Markdown 文档，按 Markdown 渲染；纯文本回落为段落 -->
          <EvidenceMarkdown :text="reference.text" />
        </NCollapseItem>
      </NCollapse>
    </NModal>

    <NModal
      v-model:show="showMemoryModal" display-directive="show" style="width: min(640px, 92vw);"
      preset="card" :title="t('chat.hitMemory')"
    >
      <RefMemory :msg-uuid="selectedMemoryMsgUuid" />
    </NModal>

    <NModal
      v-model:show="showRefGraphModal" class="graph-modal" display-directive="show" style="width: min(860px, 92vw);" preset="card"
      :title="t('chat.referenceGraph')"
    >
      <RefGraph :msg-uuid="showRefGraphMsgUuid" />
    </NModal>

    <NModal
      v-model:show="showKeywordModal" display-directive="show" style="width: min(640px, 92vw);"
      preset="card" :title="t('chat.keywordReference')"
    >
      <RefKeyword :msg-uuid="selectedKeywordMsgUuid" />
    </NModal>
  </div>
</template>

<style scoped lang="less">
.chat-empty-stage {
  display: flex;
  min-height: 100%;
  align-items: center;
  justify-content: center;
}

.chat-empty-stage .chat-onboarding {
  margin: 0 auto;
}

.chat-onboarding {
  display: flex;
  width: min(760px, calc(100% - 24px));
  min-height: 0;
  margin: clamp(20px, 4vh, 44px) auto 20px;
  padding: 30px 34px;
  align-items: center;
  flex-direction: column;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 14px;
  background: var(--zhimesh-glass-strong);
  box-shadow: none;
  text-align: center;
}

.onboarding-orb {
  display: none;
  width: 62px;
  height: 62px;
  margin-bottom: 14px;
  place-items: center;
  border-radius: 20px;
  color: var(--zhimesh-on-primary);
  background: var(--zhimesh-control-primary);
  box-shadow: none;
}

.onboarding-kicker {
  display: none;
  color: var(--zhimesh-primary);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.chat-onboarding h1 {
  margin: 7px 0 8px;
  font-size: clamp(23px, 3vw, 32px);
  font-weight: 760;
  letter-spacing: -0.03em;
}

.onboarding-lead {
  max-width: 560px;
  margin: 0;
  color: var(--zhimesh-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.onboarding-status {
  display: grid;
  width: 100%;
  margin: 26px 0 0;
  align-items: center;
  grid-template-columns: minmax(0, 1fr) 34px minmax(0, 1fr) 34px minmax(0, 1fr);
}

.onboarding-status > div {
  display: flex;
  min-width: 0;
  padding: 13px;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 14px;
  background: var(--zhimesh-glass-soft-alpha);
  text-align: left;
}

.onboarding-status > div > span {
  display: grid;
  flex: none;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 10px;
  color: var(--zhimesh-text-muted);
  background: rgba(100, 116, 139, 0.1);
  font-weight: 700;
}

.onboarding-status > div.complete > span {
  color: var(--zhimesh-on-primary);
  background: var(--zhimesh-control-primary);
}

.onboarding-status > div > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.onboarding-status strong,
.onboarding-status small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.onboarding-status strong {
  font-size: 13px;
}

.onboarding-status small {
  margin-top: 2px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.onboarding-status i {
  height: 1px;
  background: var(--zhimesh-border);
}

.onboarding-actions {
  display: flex;
  margin-top: 22px;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.chat-input-layout {
  width: min(980px, 100%);
  margin: 0 auto;
}

.chat-composer-footer {
  flex: none;
}

.chat-input-panel {
  margin-top: 7px;
}

.chat-capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.chat-capability-card {
  display: flex;
  min-width: 0;
  min-height: 68px;
  padding: 12px 10px;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 14px;
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-soft);
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  cursor: pointer;
  transition: color 180ms ease, border-color 180ms ease, background-color 180ms ease, transform 180ms ease;
}

.chat-capability-card:hover,
.chat-capability-card:focus-visible {
  border-color: var(--zhimesh-primary);
  color: var(--zhimesh-primary);
  outline: none;
  transform: translateY(-1px);
}

.chat-capability-card-icon {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  border-radius: 10px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
  font-size: 18px;
}

:global(.dark) .chat-onboarding {
  background: var(--zhimesh-glass-strong);
}

:global(.dark) .onboarding-status > div {
  background: var(--zhimesh-glass-soft-alpha);
}

@media (max-width: 767px) {
  .chat-empty-stage {
    display: block;
  }

  .chat-onboarding {
    min-height: 0;
    margin-top: 20px;
    padding: 24px 16px;
  }

  .onboarding-status {
    margin: 24px 0;
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .onboarding-status i {
    display: none;
  }

  .onboarding-actions {
    width: 100%;
    flex-direction: column;
  }

  .onboarding-actions :deep(.n-button) {
    width: 100%;
  }

  .chat-input-panel {
    margin-top: 5px;
  }
}

/* The empty state introduces the capability network without turning into a marketing hero. */
.chat-onboarding {
  align-items: stretch;
  padding: 32px;
  border: 1px solid var(--zhimesh-border) !important;
  border-radius: 14px !important;
  background: var(--zhimesh-glass-strong) !important;
  box-shadow: none !important;
  text-align: left;
}

.onboarding-orb,
.onboarding-kicker {
  display: none;
}

.onboarding-mark {
  display: block;
  width: 64px;
  height: 64px;
  margin-bottom: 20px;
  flex: none;
}

.chat-onboarding h1 {
  margin-top: 0;
  color: var(--zhimesh-text);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.025em;
}

.onboarding-lead {
  max-width: 620px;
  color: var(--zhimesh-text-muted);
  line-height: 1.65;
}

.onboarding-status {
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr) 24px minmax(0, 1fr);
  margin-top: 28px;
}

.onboarding-status > div {
  padding: 12px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 8px;
    background: var(--zhimesh-glass-soft);
}

.onboarding-status > div.complete > span {
  color: var(--zhimesh-on-primary);
  background: var(--zhimesh-control-primary);
}

.onboarding-status i {
  background: var(--zhimesh-border);
}

.onboarding-actions {
  justify-content: flex-start;
  margin-top: 24px;
}

:global(.dark) .chat-onboarding {
  background: var(--zhimesh-glass-strong) !important;
}

:global(.dark) .onboarding-status > div {
  background: #20333f;
  border-color: #3a515d;
}

@media (max-width: 767px) {
  .chat-onboarding {
    padding: 22px 16px;
  }

  .onboarding-mark {
    width: 56px;
    height: 56px;
    margin-bottom: 16px;
  }

  .onboarding-status {
    margin: 24px 0 0;
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .onboarding-status i {
    display: none;
  }

  .chat-onboarding h1 {
    font-size: 23px;
  }
}

/* The empty workspace reads as a connection path, not a centered template card. */
.chat-onboarding {
  width: min(900px, calc(100% - 40px));
  padding: 34px 0 30px;
  border: 0 !important;
  border-block: 1px solid var(--zhimesh-border-subtle) !important;
  border-radius: 0 !important;
  background: transparent !important;
}

.onboarding-intro {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 30px;
}

.onboarding-mark-shell {
  position: relative;
  display: grid;
  width: 72px;
  height: 72px;
  place-items: center;
}

.onboarding-mark-shell::after {
  position: absolute;
  top: 50%;
  left: 68px;
  width: 34px;
  height: 1px;
  background: var(--zhimesh-border);
  content: '';
  transform: scaleX(1);
  transform-origin: left center;
}

.onboarding-mark {
  width: 64px;
  height: 64px;
  margin: 0;
}

.onboarding-copy {
  min-width: 0;
}

.chat-onboarding h1 {
  margin: 0 0 8px;
  font-size: clamp(26px, 3vw, 34px);
  letter-spacing: -0.03em;
}

.onboarding-status {
  width: min(100%, 720px);
  margin: 30px auto 0;
  grid-template-columns: minmax(0, 1fr) 28px minmax(0, 1fr) 28px minmax(0, 1fr);
  column-gap: 12px;
}

.onboarding-status > div {
  padding: 15px 0;
  border: 0;
  border-top: 1px solid var(--zhimesh-border-subtle);
  border-radius: 0;
  background: transparent !important;
}

.onboarding-status > div > span {
  border-radius: 8px;
  transition: color 180ms ease, background-color 180ms ease, transform 180ms cubic-bezier(0.16, 1, 0.3, 1);
}

.onboarding-status > div.complete > span {
  transform: scale(1);
}

.onboarding-status > i {
  position: relative;
  height: 1px;
  overflow: hidden;
  background: var(--zhimesh-border-subtle);
}

.onboarding-status > i::after {
  position: absolute;
  inset: 0;
  background: var(--zhimesh-accent);
  content: '';
  transform: scaleX(0);
  transform-origin: left center;
  transition: transform 380ms cubic-bezier(0.16, 1, 0.3, 1);
}

.onboarding-status > i.complete::after {
  transform: scaleX(1);
}

.onboarding-actions {
  justify-content: flex-start;
  margin-top: 26px;
  margin-left: 102px;
}

.message-thread {
  display: block;
}

.message-list-enter-active {
  transition: opacity 260ms cubic-bezier(0.16, 1, 0.3, 1), transform 260ms cubic-bezier(0.16, 1, 0.3, 1);
}

.message-list-leave-active {
  transition: opacity 120ms ease;
}

.message-list-enter-from {
  opacity: 0;
  transform: translate3d(0, 10px, 0);
}

.message-list-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: no-preference) {
  .onboarding-mark {
    animation: onboarding-mark-reveal 620ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }

  .onboarding-mark-shell::after {
    animation: onboarding-link-reveal 520ms 120ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }

  .onboarding-status > div.complete > span {
    animation: onboarding-status-lock 260ms cubic-bezier(0.16, 1, 0.3, 1) both;
  }
}

@keyframes onboarding-mark-reveal {
  from { opacity: 0.35; clip-path: inset(0 100% 0 0); }
  to { opacity: 1; clip-path: inset(0 0 0 0); }
}

@keyframes onboarding-link-reveal {
  from { opacity: 0; transform: scaleX(0); }
  to { opacity: 1; transform: scaleX(1); }
}

@keyframes onboarding-status-lock {
  from { transform: scale(0.9); }
  to { transform: scale(1); }
}

@media (max-width: 767px) {
  .chat-onboarding {
    width: 100%;
    margin: clamp(12px, 3vh, 22px) auto 12px;
    padding: 16px 8px 14px;
    border: 0 !important;
    background: transparent !important;
  }

  .onboarding-intro {
    padding: 0 4px;
    grid-template-columns: 48px minmax(0, 1fr);
    gap: 12px;
  }

  .onboarding-mark-shell,
  .onboarding-mark {
    width: 48px;
    height: 48px;
  }

  .onboarding-mark-shell::after {
    display: none;
  }

  .chat-onboarding h1 {
    margin: 0;
    font-size: clamp(20px, 5.8vw, 24px);
    line-height: 1.25;
    overflow-wrap: anywhere;
  }

  .onboarding-lead {
    margin-top: 5px;
    font-size: 13px;
    line-height: 1.5;
  }

  .onboarding-status {
    margin: 18px 0 0;
    padding: 14px 8px 12px;
    align-items: start;
    grid-template-columns: minmax(0, 1fr) 14px minmax(0, 1fr) 14px minmax(0, 1fr);
    gap: 0;
    border: 1px solid var(--zhimesh-border-subtle);
    border-radius: 14px;
    background: var(--zhimesh-glass-soft-alpha);
  }

  .onboarding-status > div {
    padding: 0;
    align-items: center;
    flex-direction: column;
    gap: 7px;
    border: 0;
    background: transparent !important;
    text-align: center;
  }

  .onboarding-status > div > span {
    width: 30px;
    height: 30px;
  }

  .onboarding-status > div > div {
    width: 100%;
    align-items: center;
  }

  .onboarding-status strong,
  .onboarding-status small {
    width: 100%;
    overflow: visible;
    line-height: 1.35;
    text-overflow: clip;
    white-space: normal;
  }

  .onboarding-status strong {
    font-size: 13px;
  }

  .onboarding-status small {
    margin-top: 3px;
    font-size: 11px;
  }

  .onboarding-status > i {
    display: block;
    width: 100%;
    height: 1px;
    margin: 15px 0 0;
  }

  .onboarding-status > i::after {
    transform: scaleX(0);
    transform-origin: left center;
  }

  .onboarding-status > i.complete::after {
    transform: scaleX(1);
  }

  .onboarding-actions {
    margin-top: 16px;
    margin-left: 0;
  }

  .chat-composer-footer {
    position: sticky !important;
    z-index: 20;
    bottom: 0;
    margin-top: auto;
    padding: 7px 10px 10px !important;
    border-top: 1px solid var(--zhimesh-border-subtle);
    background: var(--zhimesh-glass-nav-strong);
    box-shadow: 0 -8px 24px rgba(24, 45, 62, 0.08);
    backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
    -webkit-backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
  }

  .chat-input-layout {
    width: 100%;
  }

  .chat-input-panel {
    margin-top: 4px;
  }
}

@media (max-width: 360px) {
  .onboarding-intro {
    grid-template-columns: 44px minmax(0, 1fr);
    gap: 10px;
  }

  .onboarding-mark-shell,
  .onboarding-mark {
    width: 44px;
    height: 44px;
  }

  .onboarding-mark-shell::after {
    display: none;
  }

  .chat-onboarding h1 {
    font-size: 20px;
  }

  .onboarding-status {
    padding-right: 4px;
    padding-left: 4px;
    grid-template-columns: minmax(0, 1fr) 10px minmax(0, 1fr) 10px minmax(0, 1fr);
  }
}
</style>
