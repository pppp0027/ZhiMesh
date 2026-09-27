import { ss } from '@/utils/storage'
import { emptyCharacter } from '@/utils/functions'

const LOCAL_NAME = 'chatStorage'

const defaultCharacter = emptyCharacter()
defaultCharacter.uuid = 'default'
defaultCharacter.title = '默认对话'

export function defaultState(): Chat.ChatState {
  return {
    active: defaultCharacter.uuid,
    activeConversationUuid: '',
    presetCharacters: [],
    characters: [defaultCharacter],
    conversations: [],
    chats: [{ uuid: defaultCharacter.uuid, data: [] }],
    loadingMsgs: new Set<string>(),
    msgToMemoryRef: new Map<string, Chat.MemoryEmbedding[]>(),
    msgToEmbeddingRef: new Map<string, KnowledgeBase.QaRecordEmbeddingRef[]>(),
    msgToGraphRef: new Map<string, KnowledgeBase.QaRecordGraphRef>(),
    loadingGraphRef: new Map<string, boolean>(),
  }
}

export function getDefaultCharacter(): Chat.Character {
  return defaultCharacter
}

export function getLocalState(): Chat.ChatState {
  const localState = ss.get(LOCAL_NAME)
  return { ...defaultState(), ...localState }
}

export function setLocalState(state: Chat.ChatState) {
  ss.set(LOCAL_NAME, state)
}

export function findMessageFromCharacter(character: Chat.CharacterWithMessages, messageUuid: string): Chat.ChatMessage | null {
  const questions = character.data
  for (const question of questions) {
    if (question.uuid === messageUuid) {
      return question
    } else {
      const result = question.children.find(child => child.uuid === messageUuid)
      if (result)
        return result
    }
  }
  return null
}

/** 后端内联回答的引导文案（挂起不可用/达上限时 ask_user 不挂起）：历史派生时排除，不误渲染成挂起卡片 */
const INLINE_ANSWER_TEXTS = new Set([
  '已达追问上限，请基于已有信息直接回答',
  '当前会话不支持挂起提问，请基于已有信息直接回答',
])

/**
 * 历史回放派生挂起载荷：后端消息行不直接携带 suspension 时，由轨迹行
 * （ask_user / request_human_approval）与消息行（remark=问题文本）重建只读卡片
 * （checkpointUuid 已不可考置空；载荷键名按历史口径用 type）
 */
export function deriveHistorySuspension(message: Chat.ChatMessage) {
  deriveMessageSuspension(message)
  message.children.forEach(child => deriveMessageSuspension(child))
}

function deriveMessageSuspension(message: Chat.ChatMessage) {
  if (message.suspension || !message.toolCalls?.length)
    return
  const trace = message.toolCalls.find(tool =>
    (tool.toolName === 'ask_user' || tool.toolName === 'request_human_approval')
    && tool.success
    && !!tool.resultSummary
    && !INLINE_ANSWER_TEXTS.has(tool.resultSummary))
  if (!trace)
    return
  message.suspension = {
    type: trace.toolName === 'ask_user' ? 'ASK_USER' : 'APPROVAL',
    toolName: trace.toolName,
    question: trace.resultSummary || message.remark || '',
    checkpointUuid: '',
  }
}
