import { defineStore } from 'pinia'
import { defaultState, findMessageFromCharacter, getDefaultCharacter } from './helper'
import { router } from '@/router'
import { emptyAudioPlayState } from '@/utils/functions'
import { CHAT_MESSAGE_CONTENT_TYPE } from '@/utils/constant'

export const useChatStore = defineStore('chat-store', {
  state: (): Chat.ChatState => defaultState(),

  getters: {
    allCharactersCount(state: Chat.ChatState) {
      return state.characters.length
    },

    getCurCharacter(state: Chat.ChatState) {
      const index = state.characters.findIndex(item => item.uuid === state.active)
      if (index !== -1)
        return state.characters[index]
      return null
    },

    getCurConversation(state: Chat.ChatState) {
      return state.conversations.find(item => item.uuid === state.activeConversationUuid) || null
    },

    activeChatKey(state: Chat.ChatState) {
      return state.activeConversationUuid || state.active
    },

    getConversationsByCharacter(state: Chat.ChatState) {
      return (characterId?: string | number) => state.conversations.filter(
        item => String(item.characterId) === String(characterId),
      )
    },

    getCharacterByUuid(state: Chat.ChatState) {
      return (uuid: string) => {
        return state.characters.find(item => item.uuid === uuid)
      }
    },

    getMsgsByCharacter(state: Chat.ChatState) {
      return (uuid?: string) => {
        if (uuid)
          return state.chats.find(item => item.uuid === uuid)?.data ?? []
        return state.chats.find(item => item.uuid === state.active)?.data ?? []
      }
    },

    getMsgByCurCharacter(state: Chat.ChatState) {
      return (messageUuid: string) => {
        const chatKey = state.activeConversationUuid || state.active
        const messages = state.chats.find(item => item.uuid === chatKey)?.data ?? []
        const hitMessage = messages.find(item => item.uuid === messageUuid)
        if (hitMessage)
          return hitMessage
        return null
      }
    },

    hasLoadedData(state: Chat.ChatState) {
      const chatKey = state.activeConversationUuid || state.active
      const currCharacter = state.chats.find(item => item.uuid === chatKey)
      if (currCharacter)
        return currCharacter.data.length > 0
      return false
    },

    answerContentType(_state: Chat.ChatState) {
      return (_character: Chat.Character, _userAudioUuid: string) => {
        // 语音服务已禁用，新的对话和重新生成始终使用文本回复。
        return CHAT_MESSAGE_CONTENT_TYPE.text
      }
    },

    getMemory(state: Chat.ChatState) {
      return (msgUuid: string) => {
        const references = state.msgToMemoryRef.get(msgUuid)
        if (references)
          return references
        return []
      }
    },
    getReferences(state: Chat.ChatState) {
      return (msgUuid: string) => {
        const references = state.msgToEmbeddingRef.get(msgUuid)
        if (references)
          return references
        return []
      }
    },
    getGraphRef(state: Chat.ChatState) {
      return (msgUuid: string) => {
        const graphRef = state.msgToGraphRef.get(msgUuid)
        if (graphRef)
          return graphRef
        return null
      }
    },
    isLoadingGraphRef(state: Chat.ChatState) {
      return (msgUuid: string) => {
        const loading = state.loadingGraphRef.get(msgUuid)
        if (loading)
          return loading
        return false
      }
    },
  },

  actions: {
    setUsingContext(context: boolean) {
      this.usingContext = context
    },

    clearDefault() {
      const index = this.characters.findIndex(item => item.uuid === 'default')
      if (index !== -1)
        this.characters.splice(index, 1)
      const chatIndex = this.chats.findIndex(item => item.uuid === 'default')
      if (chatIndex !== -1)
        this.chats.splice(chatIndex, 1)
    },

    addCharacters(characters: Chat.Character[]) {
      characters.forEach((item) => {
        if (this.characters.findIndex(innerItem => innerItem.uuid === item.uuid) !== -1)
          return

        this.characters.push(item)
        this.chats.push({ uuid: item.uuid, data: [] })
      })
    },

    setConversations(conversations: Chat.Conversation[]) {
      this.conversations = [...conversations]
      conversations.forEach((conversation) => {
        if (!this.chats.some(item => item.uuid === conversation.uuid))
          this.chats.push({ uuid: conversation.uuid, data: [] })
      })
    },

    addConversation(conversation: Chat.Conversation) {
      const index = this.conversations.findIndex(item => item.uuid === conversation.uuid)
      if (index === -1)
        this.conversations.unshift(conversation)
      else
        this.conversations[index] = conversation
      if (!this.chats.some(item => item.uuid === conversation.uuid))
        this.chats.push({ uuid: conversation.uuid, data: [] })
    },

    updateConversation(uuid: string, edit: Partial<Chat.Conversation>) {
      const index = this.conversations.findIndex(item => item.uuid === uuid)
      if (index !== -1)
        this.conversations[index] = { ...this.conversations[index], ...edit }
    },

    deleteConversation(uuid: string) {
      const index = this.conversations.findIndex(item => item.uuid === uuid)
      if (index !== -1)
        this.conversations.splice(index, 1)
      const chatIndex = this.chats.findIndex(item => item.uuid === uuid)
      if (chatIndex !== -1)
        this.chats.splice(chatIndex, 1)
      if (this.activeConversationUuid === uuid)
        this.activeConversationUuid = ''
    },

    clearConversations() {
      const conversationUuids = new Set(this.conversations.map(item => item.uuid))
      this.chats = this.chats.filter(item => !conversationUuids.has(item.uuid))
      this.conversations = []
      this.activeConversationUuid = ''
    },

    addCharacterAndActive(newCharacter: Chat.Character, chatData: Chat.ChatMessage[] = []) {
      if (this.characters.findIndex(item => item.uuid === newCharacter.uuid) !== -1)
        return

      this.characters.unshift(newCharacter)
      this.chats.unshift({ uuid: newCharacter.uuid, data: chatData })
      this.active = newCharacter.uuid
      this.activeConversationUuid = ''
      this.reloadRoute(newCharacter.uuid)
    },

    updateCharacter(characterUuid: string, edit: Partial<Chat.Character>) {
      const index = this.characters.findIndex(item => item.uuid === characterUuid)
      if (index !== -1)
        this.characters[index] = { ...this.characters[index], ...edit }
    },

    async deleteCharacter(uuid: string) {
      const index = this.characters.findIndex(item => item.uuid === uuid)
      if (index < 0)
        return

      this.activeConversationUuid = ''
      this.characters.splice(index, 1)
      const chatIndex = this.chats.findIndex(item => item.uuid === uuid)
      if (chatIndex >= 0)
        this.chats.splice(chatIndex, 1)

      if (this.characters.length === 0) {
        this.active = ''
        this.reloadRoute()
        return
      }

      if (index > 0 && index <= this.characters.length) {
        const uuid = this.characters[index - 1].uuid
        this.active = uuid
        this.reloadRoute(uuid)
        return
      }

      if (index === 0 && this.characters.length > 0) {
        const uuid = this.characters[0].uuid
        this.active = uuid
        this.reloadRoute(uuid)
      }

      if (index > this.characters.length) {
        const uuid = this.characters[this.characters.length - 1].uuid
        this.active = uuid
        this.reloadRoute(uuid)
      }
    },

    async setActive(uuid: string, conversationUuid = '') {
      this.active = uuid
      this.activeConversationUuid = conversationUuid
      return await this.reloadRoute(uuid, conversationUuid)
    },

    async setActiveConversation(characterUuid: string, conversationUuid: string) {
      this.active = characterUuid
      this.activeConversationUuid = conversationUuid
      if (!this.chats.some(item => item.uuid === conversationUuid))
        this.chats.push({ uuid: conversationUuid, data: [] })
      return await this.reloadRoute(characterUuid, conversationUuid)
    },

    getMsgsByCharacterAndIndex(uuid: string, index: number) {
      if (!uuid) {
        if (this.chats.length)
          return this.chats[0].data[index]
        return null
      }
      const chatIndex = this.chats.findIndex(item => item.uuid === uuid)
      if (chatIndex !== -1)
        return this.chats[chatIndex].data[index]
      return null
    },

    addMessage(uuid: string, message: Chat.ChatMessage, tail?: boolean) {
      if (undefined === message.inversion)
        message.inversion = message.messageRole !== 3
      this.initAudioText(message)

      if (this.characters.length === 0) {
        this.characters.push(getDefaultCharacter())
        this.chats.push({ uuid, data: [message] })
        this.active = uuid
        return
      }

      const chatIndex = this.chats.findIndex(item => item.uuid === uuid)
      if (chatIndex === -1)
        return

      const cachedMsgs = this.chats[chatIndex].data
      let hit = false
      cachedMsgs.forEach((item) => {
        // 将之前的聊天信息中的loading状态全部置为false，保留当前消息的loading状态
        if (message.loading) {
          item.loading = false
          if (item.children.length > 0)
            item.children.forEach(child => child.loading = false)
        }

        if (item.uuid === message.uuid)
          hit = true
      })
      if (!hit)
        tail ? this.chats[chatIndex].data.push(message) : this.chats[chatIndex].data.unshift(message)

      if (this.characters.find(item => item.uuid === uuid)?.title === 'New Character')
        this.characters[chatIndex].title = message.remark
    },

    unshiftMessages(uuid: string, messages: Chat.ChatMessage[]) {
      const chatIndex = this.chats.findIndex(item => item.uuid === uuid)
      if (chatIndex === -1)
        return
      const cachedMsgs = this.chats[chatIndex].data
      messages.forEach((item) => {
        this.initAudioText(item)
        cachedMsgs.unshift(item)
      })
    },

    updateMessage(uuid: string, index: number, chat: Chat.ChatMessage) {
      if (!uuid && this.chats.length) {
        this.chats[0].data[index] = chat
        return
      }

      const chatIndex = this.chats.findIndex(item => item.uuid === uuid)
      if (chatIndex !== -1)
        this.chats[chatIndex].data[index] = chat
    },

    unshiftChild(characterUuid: string, userMessageUuid: string, childMessage: Chat.ChatMessage) {
      const chatIndex = this.chats.findIndex(item => item.uuid === characterUuid)
      if (chatIndex !== -1)
        this.chats[chatIndex].data.find(item => item.uuid === userMessageUuid)?.children.unshift(childMessage)
    },

    appendChild(characterUuid: string, userMessageUuid: string, childMessage: Chat.ChatMessage) {
      const chatIndex = this.chats.findIndex(item => item.uuid === characterUuid)
      if (chatIndex !== -1)
        this.chats[chatIndex].data.find(item => item.uuid === userMessageUuid)?.children.push(childMessage)
    },

    // Append a chunk of text to an answer message
    appendChunk(characterUuid: string, answerUuid: string, chunk: string, thinking = false) {
      const chatIndex = this.chats.findIndex(item => item.uuid === characterUuid)
      if (chatIndex !== -1 && this.chats.length) {
        const answer = findMessageFromCharacter(this.chats[chatIndex], answerUuid)
        if (answer) {
          answer.thinking = thinking
          if (thinking) {
            answer.thinkingContent = answer.thinkingContent + chunk
          } else {
            answer.remark = answer.remark + chunk
            answer.loading = true
            if (!answer.audioPlayState)
              answer.audioPlayState = emptyAudioPlayState()
            answer.audioPlayState.text = answer.remark
          }
        }
      }
    },

    updateMessageSomeFields(characterUuid: string, messageUuid: string, chat: Partial<Chat.ChatMessage>) {
      const characterIndex = this.chats.findIndex(item => item.uuid === characterUuid)
      if (characterIndex !== -1) {
        const hitCharacter = this.chats[characterIndex]
        const hitMessage = findMessageFromCharacter(hitCharacter, messageUuid)
        console.log('hitMessage', hitMessage)
        if (hitMessage)
          Object.assign(hitMessage, chat)
      }
    },

    deleteQuestion(characterUuid: string, questionUuid: string) {
      const chatIndex = this.chats.findIndex(item => item.uuid === characterUuid)
      if (chatIndex !== -1) {
        const index = this.chats[chatIndex].data.findIndex(message => message.uuid === questionUuid)
        if (index !== -1)
          this.chats[chatIndex].data.splice(index, 1)
      }
    },

    // Delete one answer of the question
    deleteAnswer(characterUuid: string, questionUuid: string, answerUuid: string) {
      const chatIndex = this.chats.findIndex(item => item.uuid === characterUuid)
      if (chatIndex !== -1) {
        const index = this.chats[chatIndex].data.findIndex(message => message.uuid === questionUuid)
        if (index !== -1) {
          const questionMsg = this.chats[chatIndex].data[index]
          const answerIndex = questionMsg.children.findIndex(child => child.uuid === answerUuid)
          if (answerIndex !== -1)
            questionMsg.children.splice(answerIndex, 1)
        }
      }
    },

    async reloadRoute(uuid?: string, conversationUuid = '') {
      await router.push({
        name: 'ChatDetail',
        params: { uuid },
        query: conversationUuid ? { conversation: conversationUuid } : {},
      })
    },

    addLoadingMsg(characterUuid: string) {
      this.loadingMsgs.add(characterUuid)
    },

    deleteLoadingMsg(characterUuid: string) {
      this.loadingMsgs.delete(characterUuid)
    },

    setPresetCharacters(characters: Chat.CharacterPreset[]) {
      this.presetCharacters.splice(0, this.presetCharacters.length)
      characters.forEach((item) => {
        if (this.presetCharacters.findIndex(innerItem => innerItem.uuid === item.uuid) !== -1)
          return
        if (!item.used)
          item.used = false

        this.presetCharacters.push(item)
      })
    },

    setUsedPresetCharacter(rels: Chat.CharacterToPresetRel[]) {
      this.presetCharacters.forEach((item) => {
        const hit = rels.some(rel => String(rel.presetCharacterId) === String(item.id))
        item.used = hit
      })
    },

    markPresetCharacterUsed(presetCharacterUuid: string) {
      const item = this.presetCharacters.find(i => i.uuid === presetCharacterUuid)
      if (item)
        item.used = true
    },

    initAudioText(message: Chat.ChatMessage) {
      if (!message.audioPlayState)
        message.audioPlayState = emptyAudioPlayState()
      message.audioPlayState.audioUrl = message.audioUrl
      message.audioPlayState.audioUuid = message.audioUuid
      if (message.remark && !message.audioPlayState.text)
        message.audioPlayState.text = message.remark

      // AI answer message
      if (message?.children) {
        const childMessage = message.children[0]
        if (childMessage)
          this.initAudioText(childMessage)
      }
    },

    updateAudioPlayState(messageUuid: string, audioPlayState: AudioPlayState) {
      const chatIndex = this.chats.findIndex(item => item.uuid === (this.activeConversationUuid || this.active))
      if (chatIndex !== -1) {
        const message = this.chats[chatIndex].data.find(item => item.uuid === messageUuid)
        if (message)
          message.audioPlayState = audioPlayState
        if (message?.children) {
          const childMessage = message.children[0]
          if (childMessage)
            childMessage.audioPlayState = audioPlayState
        }
      }
    },

    setMemoryRefs(msgUuid: string, reference: Chat.MemoryEmbedding[]) {
      this.msgToMemoryRef.set(msgUuid, reference)
    },

    setKnowledgeEmbeddingRefs(msgUuid: string, references: KnowledgeBase.QaRecordEmbeddingRef[]) {
      this.msgToEmbeddingRef.set(msgUuid, !references ? [] : references)
    },

    setKnowledgeGraphRef(msgUuid: string, graphRef: KnowledgeBase.QaRecordGraphRef) {
      this.msgToGraphRef.set(msgUuid, graphRef)
    },

    setLoadingGraphRef(qaRecordUuid: string, loading: boolean) {
      this.loadingGraphRef.set(qaRecordUuid, loading)
    },

  },
})
