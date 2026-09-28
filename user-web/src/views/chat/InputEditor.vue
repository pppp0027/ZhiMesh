<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { NAutoComplete, NButton, NInput, useMessage } from 'naive-ui'
import { storeToRefs } from 'pinia'
import { v4 as uuidv4 } from 'uuid'
import { useChat } from './hooks/useChat'
import { SvgIcon } from '@/components/common'
import { useAppStore, useAuthStore, useChatStore, usePromptStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { emptyAudioPlayState, emptyChatMessage } from '@/utils/functions'
import { CHAT_MESSAGE_CONTENT_TYPE } from '@/utils/constant'
import { t } from '@/locales'
import api from '@/api'
interface Props {
  characterUuid: string
  conversationUuid: string
  chatKey: string
  imageUuids: string[]
}
const props = withDefaults(defineProps<Props>(), {
  characterUuid: '',
  conversationUuid: '',
  chatKey: '',
})
const emit = defineEmits<Emit>()
interface Emit {
  (ev: 'sseStarted', questionUuid: string): void
  (ev: 'messageReceiving', questionUuid: string): void
  (ev: 'messageComplelted', questionUuid: string): void
  (ev: 'isChatting', isChatting: boolean): void
  (ev: 'mobileToolsChange', expanded: boolean): void
}
const prompt = ref<string>('')
const { isMobile } = useBasicLayout()
// 全角空格(U+3000)前缀：让占位提示右移一个字宽，与输入光标起点对齐
const placeholderPrefix = String.fromCharCode(0x3000)
const { addMessage, updateMessageSomeFields, appendChunk } = useChat()
const appStore = useAppStore()
const chatStore = useChatStore()
const authStore = useAuthStore()
const ms = useMessage()
const promptStore = usePromptStore()
// 使用storeToRefs，保证store修改后，联想部分能够重新渲染
const { promptList: promptTemplateList } = storeToRefs<any>(promptStore)
const isChatting = ref<boolean>(false)
const mobileToolsExpanded = ref(false)
const chattingMsg = ref<Chat.ChatMessage>(emptyChatMessage())
const messages = computed(() => {
  return chatStore.getMsgsByCharacter(props.chatKey || props.characterUuid)
})
let controller = new AbortController()
let arrowKeyIdx = -1
let promptSearchTimer: ReturnType<typeof setTimeout> | undefined
let promptSearchRequestId = 0
let activeRequestId = 0

async function searchRemote(keyword: string, requestId: number) {
  try {
    const resp = await api.searchPrompts<PageResponse>(1, 10, keyword)
    if (requestId !== promptSearchRequestId)
      return
    promptTemplateList.value.splice(0, promptTemplateList.value.length)
    if (resp.success && resp.data.records) {
      resp.data.records.forEach((item: Chat.Prompt) => {
        promptTemplateList.value.push({
          label: item.act,
          value: item.prompt,
        })
      })
    }
  } catch (error) {
    if (requestId === promptSearchRequestId)
      console.warn('prompt search failed', error)
  }
}
function getShow(value: string) {
  if (value.indexOf('/') === 0)
    return true

  return false
}

function handleUp(event: KeyboardEvent) {
  if (event.key === 'ArrowUp' && prompt.value.indexOf('/') !== 0) {
    event.preventDefault()
    const msgLength = messages.value.length
    if (msgLength === 0)
      return

    if (arrowKeyIdx === -1)
      arrowKeyIdx = msgLength - 1
    else
      arrowKeyIdx--

    const nextMessage = messages.value[arrowKeyIdx]
    if (nextMessage)
      prompt.value = nextMessage.remark
    else
      arrowKeyIdx++
  }
}

function handleDown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown' && prompt.value.indexOf('/') !== 0) {
    event.preventDefault()
    const msgLength = messages.value.length
    if (msgLength === 0)
      return

    if (arrowKeyIdx === -1)
      arrowKeyIdx = 0
    else
      arrowKeyIdx++

    const preMessage = messages.value[arrowKeyIdx]
    if (preMessage)
      prompt.value = preMessage.remark
    else
      arrowKeyIdx--
  }
}

function handleEnter(event: KeyboardEvent) {
  if (!isMobile.value) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      handleSubmit()
    }
  } else {
    if (event.key === 'Enter' && event.ctrlKey) {
      event.preventDefault()
      handleSubmit()
    }
  }
  arrowKeyIdx = -1
}

function handleStop() {
  if (isChatting.value) {
    activeRequestId++
    controller.abort()
    isChatting.value = false
    const requestKey = props.chatKey || props.characterUuid
    if (chattingMsg.value.uuid)
      updateMessageSomeFields(requestKey, chattingMsg.value.uuid, { loading: false, thinking: false })
    const answer = chattingMsg.value.children?.[0]
    if (answer?.uuid)
      updateMessageSomeFields(requestKey, answer.uuid, { loading: false, thinking: false })
  }
}

function handleSubmit() {
  createChatTask()
}

// 供挂起卡片等外部入口按普通用户消息提交文本（恢复 = 正常发消息）
async function submitMessage(text: string) {
  if (!text || !text.trim())
    return
  if (isChatting.value)
    return
  // 后端恢复语义是「下一条消息即消费检查点」：新一轮消息发出时，既有挂起卡片全部翻
  // 只读——用户绕过卡片直接在输入框应答（或挂起轮搁置后再发消息）时旧卡不应再可点
  deactivateSuspensionCards()
  prompt.value = text
  await createChatTask()
}

// 把当前会话所有答案上的挂起交互态清零（新一轮消息已把恢复权消费掉）
function deactivateSuspensionCards() {
  chatStore.getMsgsByCharacter(props.chatKey || props.characterUuid)?.forEach((qa: Chat.ChatMessage) => {
    qa.children?.forEach((child) => {
      if (child.suspensionActive)
        child.suspensionActive = false
    })
  })
}

const fetchChatAPIOnce = async (message: string, requestId: number) => {
  const requestCharacterUuid = props.characterUuid
  const requestConversationUuid = props.conversationUuid
  const requestChatKey = props.chatKey || requestCharacterUuid
  const character = chatStore.getCharacterByUuid(requestCharacterUuid)
  if (!character) {
    ms.error(t('chat.characterNotFound'))
    return
  }
  const requestMessage = chattingMsg.value
  const isCurrentRequest = () => requestId === activeRequestId
  // 流式渲染批量 flush：chunk 先入请求闭包内的缓冲（每请求独立），~60ms 定时一次性 appendChunk，
  // 消除逐字符 append 触发全量 markdown 重渲染的 O(n²) 卡顿；
  // done/suspension/error 入口先同步 flush，防丢尾字符、防挂起卡片与文本错位
  let textBuffer = ''
  let thinkingBuffer = ''
  let flushTimer: ReturnType<typeof setTimeout> | undefined
  const flushBuffers = () => {
    if (flushTimer) {
      clearTimeout(flushTimer)
      flushTimer = undefined
    }
    if (!isCurrentRequest() || !requestMessage.children[0]) {
      textBuffer = ''
      thinkingBuffer = ''
      return
    }
    try {
      const answer = requestMessage.children[0]
      if (thinkingBuffer) {
        appendChunk(
          requestChatKey,
          answer.uuid,
          thinkingBuffer,
          true, // thinking is true
        )
        emit('messageReceiving', requestMessage.uuid)
      }
      if (textBuffer) {
        appendChunk(
          requestChatKey,
          answer.uuid,
          textBuffer,
          false, // thinking is false
        )
        emit('messageReceiving', requestMessage.uuid)
      }
    } catch (error) {
      console.error(error)
    }
    textBuffer = ''
    thinkingBuffer = ''
  }
  return api.sseProcess({
    options: {
      prompt: message,
      characterUuid: requestCharacterUuid,
      conversationUuid: requestConversationUuid || undefined,
      regenerateQuestionUuid: '',
      modelPlatform: appStore.selectedLLM.modelPlatform,
      modelName: appStore.selectedLLM.modelName,
      imageUrls: props.imageUuids,
      audioUuid: '',
      audioDuration: 0,
    },
    signal: controller.signal,
    startCallback(_chunk) {
      if (isCurrentRequest())
        emit('sseStarted', requestMessage.uuid)
    },
    stateChanged: (state) => {
      if (!isCurrentRequest())
        return
      if (state) {
        try {
          requestMessage.state = new Map(Object.entries(JSON.parse(state)))
        } catch (error) {
          console.warn('Invalid SSE state payload', error)
        }
      }
    },
    thinkingDataReceived: (chunk) => {
      if (!isCurrentRequest())
        return
      // 处理思考数据
      if (!requestMessage.children[0])
        return
      thinkingBuffer += chunk
      if (!flushTimer)
        flushTimer = setTimeout(flushBuffers, 60)
      // 推理阶段无需显示状态
      requestMessage.state = new Map<string, string>()
    },
    messageReceived: (chunk) => {
      if (!isCurrentRequest())
        return
      if (!requestMessage.children[0])
        return
      textBuffer += chunk
      if (!flushTimer)
        flushTimer = setTimeout(flushBuffers, 60)
      // 回复阶段无需显示状态
      requestMessage.state = new Map<string, string>()
    },
    toolStartedReceived: (data) => {
      if (!isCurrentRequest())
        return
      if (!requestMessage.children[0])
        return
      // 工具开始执行：先点亮一条 running 步骤，完成事件（[TOOL_CALL]）再回填时长/结果
      const answer = requestMessage.children[0]
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
      if (!requestMessage.children[0])
        return
      const answer = requestMessage.children[0]
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
      // 挂起事件：在回答消息上挂卡片载荷（可交互），并在步骤条补挂起节点
      const answer = requestMessage.children[0]
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
      emit('messageReceiving', requestMessage.uuid)
    },
    doneCallback: (chunk) => {
      // 先同步 flush 已缓冲文本，防丢尾字符
      flushBuffers()
      if (!isCurrentRequest())
        return
      const answer = requestMessage.children[0]
      if (chunk.includes('[META]')) {
        const meta = chunk.replace('[META]', '')
        let metaData: Chat.MetaData
        try {
          metaData = JSON.parse(meta)
        } catch (error) {
          console.error('Invalid SSE metadata payload', error)
          updateMessageSomeFields(requestChatKey, requestMessage.uuid, { loading: false, thinking: false })
          updateMessageSomeFields(requestChatKey, answer.uuid, { remark: `${t('common.systemTip')}${t('common.wrong')}`, loading: false, thinking: false })
          isChatting.value = false
          return
        }
        if (metaData.conversationUuid && metaData.conversationUuid !== requestConversationUuid)
          console.error('SSE conversation mismatch', { expected: requestConversationUuid, actual: metaData.conversationUuid })
        updateMessageSomeFields(requestChatKey, requestMessage.uuid, { ...metaData.question, thinking: false, loading: false })
        // 挂起载荷兜底：实时事件未达（断连竞态）时 [META] 同样携带（键名 type）。实时
        // 载荷（kind+toolName 更全）优先，防 meta 贫载荷覆盖丢 toolName；兜底命中才置
        // 交互态（判断须在 assign 生效后按 suspensionActive 互斥，原 "!answer.suspension"
        // 在 assign 之后恒 false 属死代码）
        const liveSuspension = answer.suspension
        updateMessageSomeFields(requestChatKey, answer.uuid, { ...metaData.answer, suspension: liveSuspension || metaData.answer.suspension, thinking: false, loading: false })
        if (answer.suspension && !answer.suspensionActive)
          answer.suspensionActive = true
        if (metaData.audioInfo) {
          answer.audioPlayState.audioUrl = metaData.audioInfo.url
          answer.audioDuration = metaData.audioInfo.duration
          answer.audioUuid = metaData.audioInfo.uuid
        }
      } else {
        updateMessageSomeFields(requestChatKey, requestMessage.uuid, { thinking: false, loading: false })
        updateMessageSomeFields(requestChatKey, answer.uuid, { thinking: false, loading: false })
      }
      if (requestConversationUuid && chatStore.getMsgsByCharacter(requestChatKey).length === 1) {
        chatStore.updateConversation(requestConversationUuid, {
          title: requestMessage.remark.trim().slice(0, 100),
          lastMessageTime: new Date().toISOString(),
        })
      }
      emit('messageComplelted', requestMessage.uuid)
      isChatting.value = false
      requestMessage.state = new Map<string, string>()
    },
    errorCallback: (error) => {
      // 先同步 flush 已缓冲文本，防错误提示覆盖丢尾字符
      flushBuffers()
      if (!isCurrentRequest())
        return
      ms.warning(error)
      isChatting.value = false
      const answer = requestMessage.children?.[0]
      if (answer?.uuid)
        updateMessageSomeFields(requestChatKey, answer.uuid, { remark: `${t('common.systemTip')}${error}`, thinking: false, loading: false })
      requestMessage.state = new Map<string, string>()
    },
  })
}

async function createChatTask() {
  if (!authStore.token) {
    authStore.setLoginView(true)
    return
  }
  if (appStore.sysConfigInfo.conversationEnabled && !props.conversationUuid) {
    ms.warning(t('chat.selectConversationFirst'))
    return
  }

  const message = prompt.value

  if (isChatting.value)
    return

  if (!message || message.trim() === '')
    return

  isChatting.value = true
  prompt.value = ''
  const requestId = ++activeRequestId
  try {
    const questionUuid = uuidv4().replace(/-/g, '')
    const answerUuid = uuidv4().replace(/-/g, '')
    controller = new AbortController()

    const character = chatStore.getCharacterByUuid(props.characterUuid)
    if (!character) {
      ms.error(t('chat.characterNotFound'))
      return
    }
    const audioPlayState = emptyAudioPlayState()
    chattingMsg.value = {
      uuid: questionUuid,
      contentType: CHAT_MESSAGE_CONTENT_TYPE.text,
      createTime: new Date().toLocaleString(),
      thinkingContent: '',
      remark: message,
      audioUuid: '',
      audioUrl: '',
      audioDuration: 0,
      children: [{
        uuid: answerUuid,
        contentType: CHAT_MESSAGE_CONTENT_TYPE.text,
        createTime: new Date().toLocaleString(),
        thinkingContent: '', // 思考过程
        remark: '',
        audioUuid: '',
        audioUrl: '',
        audioDuration: 0,
        children: [],
        loading: true,
        inversion: false,
        error: false,
        aiModelPlatform: appStore.selectedLLM.modelPlatform,
        attachmentUrls: [],
        isRefEmbedding: false,
        isRefGraph: false,
        isRefMemoryEmbedding: false,
        isRefBm25: false,
        audioPlayState: emptyAudioPlayState(),
      }],
      inversion: true,
      error: false,
      attachmentUrls: [],
      isRefEmbedding: false,
      isRefGraph: false,
      isRefMemoryEmbedding: false,
      isRefBm25: false,
      audioPlayState,
    }
    // add my question
    addMessage(
      props.chatKey || props.characterUuid,
      chattingMsg.value,
      true,
    )
    await fetchChatAPIOnce(message, requestId)
  } catch (error: any) {
    console.error(`fetchChatAPIOnce error:${error}`)
    const errorMessage = error?.message ?? t('common.wrong')
    ms.error(errorMessage)
  } finally {
    isChatting.value = false
  }
}

const buttonDisabled = computed(() => {
  return isChatting.value
    || (appStore.sysConfigInfo.conversationEnabled && !props.conversationUuid)
    || !prompt.value
    || prompt.value.trim() === ''
})

const hasPrompt = computed(() => prompt.value.trim().length > 0)
const composerActionDisabled = computed(() => isMobile.value ? isChatting.value : buttonDisabled.value)
const composerActionIcon = computed(() => {
  if (!isMobile.value || hasPrompt.value)
    return 'ri:send-plane-fill'
  return mobileToolsExpanded.value ? 'ri:close-line' : 'ri:apps-2-line'
})

function handleComposerAction() {
  if (isMobile.value && !hasPrompt.value && !isChatting.value) {
    setMobileToolsExpanded(!mobileToolsExpanded.value)
    return
  }
  handleSubmit()
}

function setMobileToolsExpanded(expanded: boolean) {
  mobileToolsExpanded.value = expanded
  emit('mobileToolsChange', expanded)
}

watch(() => isChatting.value, () => {
  emit('isChatting', isChatting.value)
})

const searchOptions = computed(() => {
  return promptTemplateList.value
})

watch(() => prompt.value, (value) => {
  arrowKeyIdx = -1
  if (value.trim() && mobileToolsExpanded.value)
    setMobileToolsExpanded(false)
  if (promptSearchTimer)
    clearTimeout(promptSearchTimer)
  const requestId = ++promptSearchRequestId
  if (!value.startsWith('/')) {
    promptTemplateList.value.splice(0, promptTemplateList.value.length)
    return
  }
  promptSearchTimer = setTimeout(() => {
    promptSearchTimer = undefined
    searchRemote(value.substring(1), requestId)
  }, 180)
})

onUnmounted(() => {
  activeRequestId++
  controller.abort()
  if (promptSearchTimer)
    clearTimeout(promptSearchTimer)
  promptSearchTimer = undefined
})

defineExpose({
  handleStop,
  setMobileToolsExpanded,
  submitMessage,
})
</script>

<template>
  <div class="chat-composer">
    <NAutoComplete v-model:value="prompt" class="composer-input" :options="searchOptions" :get-show="getShow">
      <template #default="{ handleInput, handleBlur, handleFocus }">
        <NInput
          ref="inputRef" v-model:value="prompt" type="textarea"
          :placeholder="placeholderPrefix + t('chat.placeholder')"
          :autosize="{ minRows: 1, maxRows: isMobile ? 4 : 8 }" @input="handleInput" @focus="handleFocus"
          @blur="handleBlur" @keyup.up="handleUp" @keyup.down="handleDown" @keypress="handleEnter"
        />
      </template>
    </NAutoComplete>
    <NButton
      class="composer-send"
      :class="{ 'composer-send--expand': isMobile && !hasPrompt }"
      type="primary"
      circle
      size="large"
      :title="isMobile && !hasPrompt ? t('chat.resourcesAndTools') : t('chat.sendMessage')"
      :aria-label="isMobile && !hasPrompt ? t('chat.resourcesAndTools') : t('chat.sendMessage')"
      :disabled="composerActionDisabled"
      @click="handleComposerAction"
    >
      <template #icon>
        <Transition name="composer-action-icon" mode="out-in">
          <span :key="composerActionIcon" :class="{ 'dark:text-black': !isMobile || hasPrompt }">
            <SvgIcon :icon="composerActionIcon" />
          </span>
        </Transition>
      </template>
    </NButton>
  </div>
</template>

<style scoped lang="less">
.chat-composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.composer-input {
  flex: 1;
  min-width: 0;
}

.composer-input :deep(.n-input) {
  min-height: 40px;
  border-radius: 13px !important;
  background: var(--zhimesh-glass-nav);
}

.composer-input :deep(.n-input-wrapper) {
  padding: 0 !important;
}

.composer-input :deep(.n-input__textarea-el) {
  display: block;
  box-sizing: border-box;
  min-height: 40px;
  padding: 9px 14px !important;
  line-height: 22px;
  text-indent: 0;
}

.composer-send {
  flex: none;
  width: 40px;
  height: 40px;
  background: var(--zhimesh-control-primary) !important;
  box-shadow: none;
}

.composer-action-icon-enter-active,
.composer-action-icon-leave-active {
  display: inline-flex;
  transition: opacity 180ms ease, transform 220ms cubic-bezier(0.16, 1, 0.3, 1);
}

.composer-action-icon-enter-from {
  opacity: 0;
  transform: scale(0.7) rotate(-12deg);
}

.composer-action-icon-leave-to {
  opacity: 0;
  transform: scale(0.7) rotate(12deg);
}

:global(.dark) .composer-input :deep(.n-input) {
  background: var(--zhimesh-glass-soft-alpha);
}

@media (max-width: 767px) {
  .chat-composer {
    gap: 7px;
  }

  .composer-input :deep(.n-input) {
    min-height: 44px;
    border-radius: 16px !important;
  }

  .composer-input :deep(.n-input__textarea-el) {
    min-height: 44px;
    padding: 11px 14px !important;
    font-size: 16px;
  }

  .composer-send {
    width: 44px;
    height: 44px;
  }

  .composer-send--expand {
    color: var(--zhimesh-primary) !important;
    background: var(--zhimesh-info-surface) !important;
    border: 1px solid var(--zhimesh-border) !important;
  }
}
</style>
