<script setup lang='ts'>
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NInput, NModal, NScrollbar, NSpin, useDialog, useMessage } from 'naive-ui'
import { useRoute } from 'vue-router'
import { SvgIcon } from '@/components/common'
import { useAppStore, useAuthStore, useChatStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import EditConv from '@/views/chat/components/Header/EditConv.vue'
import api from '@/api'
import { t } from '@/locales'
import { openDeleteDialog } from '@/utils/dialog'

const { isMobile } = useBasicLayout()
const route = useRoute()
const appStore = useAppStore()
const chatStore = useChatStore()
const authStore = useAuthStore()
const dialog = useDialog()
const ms = useMessage()
const authStoreRef = ref<AuthState>(authStore)
const expandedCharacterUuids = ref<Set<string>>(new Set())
const showEditModal = ref(false)
const editCharacter = ref<Chat.Character>({} as Chat.Character)
const showRenameModal = ref(false)
const renameConversation = ref<Chat.Conversation | null>(null)
const renameTitle = ref('')
const renaming = ref(false)
const historyLoading = ref(false)
// 正在创建默认会话的请求去重表：同一角色并发点击只发一次请求
const defaultConversationInFlight = new Map<string, Promise<Chat.Conversation>>()

const conversationEnabled = computed(() => appStore.sysConfigInfo.conversationEnabled)
const characterList = computed(() => chatStore.characters)

function conversationsFor(character: Chat.Character) {
  return chatStore.getConversationsByCharacter(character.id)
}

function isActiveCharacter(uuid: string) {
  return chatStore.active === uuid
}

function isActiveConversation(uuid: string) {
  return chatStore.activeConversationUuid === uuid
}

function isCharacterExpanded(uuid: string) {
  return expandedCharacterUuids.value.has(uuid)
}

function toggleCharacterExpanded(uuid: string) {
  if (expandedCharacterUuids.value.has(uuid))
    expandedCharacterUuids.value.delete(uuid)
  else
    expandedCharacterUuids.value.add(uuid)
}

async function loadFirstPageByCharacter(characterUuid: string) {
  if (chatStore.loadingMsgs.has(characterUuid))
    return
  chatStore.addLoadingMsg(characterUuid)
  try {
    const character = chatStore.getCharacterByUuid(characterUuid)
    const cacheMessages = chatStore.getMsgsByCharacter(characterUuid)
    if (cacheMessages.length === 0) {
      const { data } = await api.fetchMessages<Chat.CharacterMsgListResp>(characterUuid, character?.minMsgUuid || '', 20)
      data.msgList.forEach(messageRecord => chatStore.addMessage(characterUuid, messageRecord, false))
      chatStore.updateCharacter(characterUuid, {
        minMsgUuid: data.minMsgUuid,
        loadedFirstPageMsg: true,
        loadedAll: data.msgList.length < 20,
      })
    }
  } catch (error) {
    console.error('load character history failed', error)
    ms.error(t('common.wrong'))
  } finally {
    chatStore.deleteLoadingMsg(characterUuid)
  }
}

async function loadFirstPageByConversation(conversation: Chat.Conversation) {
  if (chatStore.loadingMsgs.has(conversation.uuid))
    return
  chatStore.addLoadingMsg(conversation.uuid)
  try {
    const cacheMessages = chatStore.getMsgsByCharacter(conversation.uuid)
    if (cacheMessages.length === 0) {
      const { data } = await api.fetchConversationMessages(conversation.uuid, conversation.minMsgUuid || '', 20)
      data.msgList.forEach(messageRecord => chatStore.addMessage(conversation.uuid, messageRecord, false))
      chatStore.updateConversation(conversation.uuid, {
        minMsgUuid: data.minMsgUuid,
        loadedFirstPageMsg: true,
        loadedAll: data.msgList.length < 20,
      })
    }
  } catch (error) {
    console.error('load conversation history failed', error)
    ms.error(t('common.wrong'))
  } finally {
    chatStore.deleteLoadingMsg(conversation.uuid)
  }
}

async function handleSelectConversation(character: Chat.Character, conversation: Chat.Conversation) {
  if (isActiveConversation(conversation.uuid)) {
    await loadFirstPageByConversation(conversation)
    return
  }
  await chatStore.setActiveConversation(character.uuid, conversation.uuid)
  await loadFirstPageByConversation(conversation)
  if (isMobile.value)
    appStore.setPageSiderCollapsed('chat', true)
}

function getOrCreateDefaultConversation(character: Chat.Character) {
  const existing = conversationsFor(character).find(item => item.isDefault)
  if (existing)
    return Promise.resolve(existing)

  // 快速连点同一角色时复用在途请求，避免并发创建多个默认会话
  const inFlight = defaultConversationInFlight.get(character.uuid)
  if (inFlight)
    return inFlight

  const request = api.fetchDefaultConversation(character.uuid).then(({ data }) => {
    chatStore.addConversation(data)
    return data
  }).finally(() => {
    defaultConversationInFlight.delete(character.uuid)
  })
  defaultConversationInFlight.set(character.uuid, request)
  return request
}

async function handleSelectCharacter(character: Chat.Character) {
  expandedCharacterUuids.value.add(character.uuid)
  if (!conversationEnabled.value) {
    if (isActiveCharacter(character.uuid))
      return
    await chatStore.setActive(character.uuid)
    await loadFirstPageByCharacter(character.uuid)
  } else {
    const conversations = conversationsFor(character)
    const routeConversationUuid = route.query.conversation as string
    try {
      const target = conversations.find(item => item.uuid === routeConversationUuid)
        || await getOrCreateDefaultConversation(character)
      await handleSelectConversation(character, target)
    } catch (error) {
      console.error('load default conversation failed', error)
      ms.error(t('common.wrong'))
    }
  }
  if (isMobile.value)
    appStore.setPageSiderCollapsed('chat', true)
}

function openCharacterEdit(item: Chat.Character) {
  if (!authStore.checkLoginOrShow())
    return
  showEditModal.value = true
  editCharacter.value = item
}

function openRename(item: Chat.Conversation) {
  renameConversation.value = item
  renameTitle.value = item.title
  showRenameModal.value = true
}

async function submitRename() {
  const item = renameConversation.value
  const title = renameTitle.value.trim()
  if (!item || !title)
    return
  renaming.value = true
  try {
    await api.conversationEdit(item.uuid, title)
    chatStore.updateConversation(item.uuid, { title })
    showRenameModal.value = false
    ms.success(t('chat.conversationRenamed'))
  } catch (error: any) {
    console.error('rename conversation failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    renaming.value = false
  }
}

function handleDeleteConversation(character: Chat.Character, conversation: Chat.Conversation) {
  openDeleteDialog(dialog, {
    closable: true,
    title: t('chat.deleteConversation'),
    content: t('chat.deleteConversationConfirm', { title: conversation.title }),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    async onPositiveClick() {
      try {
        await api.conversationDelete(conversation.uuid)
        const wasActive = isActiveConversation(conversation.uuid)
        chatStore.deleteConversation(conversation.uuid)
        const remaining = conversationsFor(character)
        const defaultConversation = await getOrCreateDefaultConversation(character)
        if (!remaining.length)
          await handleSelectConversation(character, defaultConversation)
        else if (wasActive)
          await handleSelectConversation(character, remaining[0])
        ms.success(t('chat.conversationDeleted'))
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

async function fetchAllConversations() {
  const conversations: Chat.Conversation[] = []
  let currentPage = 1
  let pages = 1
  do {
    const { data } = await api.fetchConversations(currentPage, 100)
    conversations.push(...data.records)
    pages = data.pages || 1
    currentPage += 1
  } while (currentPage <= pages)
  chatStore.setConversations(conversations)
}

async function fetchHistory() {
  if (historyLoading.value)
    return
  historyLoading.value = true
  try {
    const { data: characters } = await api.fetchCharacters<Chat.Character[]>()
    if (!characters.length)
      return
    chatStore.clearDefault()
    chatStore.addCharacters(characters)

    if (conversationEnabled.value)
      await fetchAllConversations()
    else
      chatStore.clearConversations()

    const routeCharacterUuid = route.params.uuid as string
    const activeCharacter = characters.find(item => item.uuid === routeCharacterUuid) || characters[0]
    await handleSelectCharacter(activeCharacter)
    if (!conversationEnabled.value)
      await loadFirstPageByCharacter(activeCharacter.uuid)
  } catch (error) {
    console.error('load chat history failed', error)
    ms.error(t('common.wrong'))
  } finally {
    historyLoading.value = false
  }
}

watch(
  () => authStoreRef.value.token,
  (newVal) => {
    if (newVal)
      fetchHistory()
    else
      chatStore.clearConversations()
  },
)

watch(
  conversationEnabled,
  (enabled, previous) => {
    if (enabled !== previous && authStoreRef.value.token)
      fetchHistory()
  },
)

watch(
  () => [route.params.uuid as string, route.query.conversation as string],
  async ([characterUuid, conversationUuid]) => {
    if (!conversationEnabled.value || !conversationUuid)
      return
    const character = chatStore.getCharacterByUuid(characterUuid)
    const conversation = chatStore.conversations.find(item => item.uuid === conversationUuid)
    if (!character || !conversation || String(conversation.characterId) !== String(character.id))
      return
    chatStore.active = character.uuid
    chatStore.activeConversationUuid = conversation.uuid
    await loadFirstPageByConversation(conversation)
  },
)

onMounted(() => {
  if (authStoreRef.value.token)
    fetchHistory()
})
</script>

<template>
  <EditConv v-model:showModal="showEditModal" :character="editCharacter" @show-modal="show => showEditModal = show" />
  <NModal v-model:show="showRenameModal" preset="card" closable :title="t('chat.renameConversation')" style="width: min(480px, 92vw);">
    <NInput v-model:value="renameTitle" :maxlength="100" show-count @keyup.enter="submitRename" />
    <template #footer>
      <div class="flex justify-end gap-2">
        <NButton @click="showRenameModal = false">
          {{ t('common.cancel') }}
        </NButton>
        <NButton type="primary" :loading="renaming" :disabled="!renameTitle.trim()" @click="submitRename">
          {{ t('common.confirm') }}
        </NButton>
      </div>
    </template>
  </NModal>

  <NScrollbar class="character-list-scroll">
    <div class="character-list-content">
      <div class="character-list-heading">
        <div class="flex items-center gap-2">
          <SvgIcon icon="ri:robot-2-line" />
          <span>{{ t('chat.myCharacters') }}</span>
        </div>
        <span class="character-count">{{ characterList.length }}</span>
      </div>

      <div v-if="historyLoading" class="character-list-loading">
        <NSpin size="medium" />
      </div>

      <div v-else-if="!characterList.length" class="character-empty">
        <span class="character-empty-icon"><SvgIcon icon="ri:user-star-line" /></span>
        <strong>{{ t('chat.noCharacterTitle') }}</strong>
        <span>{{ t('chat.noCharacterHint') }}</span>
      </div>

      <div v-else class="character-list">
        <section v-for="character of characterList" :key="character.uuid" class="character-group">
          <div
            role="button"
            tabindex="0"
            class="character-card"
            :class="{ 'is-active': isActiveCharacter(character.uuid) }"
            @click="handleSelectCharacter(character)"
            @keydown.enter="handleSelectCharacter(character)"
            @keydown.space.prevent="handleSelectCharacter(character)"
          >
            <button
              v-if="conversationEnabled"
              type="button"
              class="character-expand"
              :aria-label="isCharacterExpanded(character.uuid) ? t('chat.collapseConversations') : t('chat.expandConversations')"
              :title="isCharacterExpanded(character.uuid) ? t('chat.collapseConversations') : t('chat.expandConversations')"
              @click.stop="toggleCharacterExpanded(character.uuid)"
            >
              <SvgIcon :icon="isCharacterExpanded(character.uuid) ? 'ri:arrow-down-s-line' : 'ri:arrow-right-s-line'" />
            </button>
            <span v-else class="character-edge-placeholder" />
            <span class="character-identity">
              <span class="character-avatar"><SvgIcon icon="ri:robot-2-line" /></span>
              <span class="character-title">{{ character.title }}</span>
            </span>
            <button
              type="button"
              class="character-menu"
              :aria-label="t('chat.configureCharacter')"
              :title="t('chat.configureCharacter')"
              @click.stop="openCharacterEdit(character)"
            >
              <SvgIcon icon="ri:more-2-fill" />
            </button>
          </div>

          <div v-if="conversationEnabled && isCharacterExpanded(character.uuid)" class="conversation-group">
            <div class="conversation-heading">
              <span>{{ t('chat.conversations') }}</span>
              <span>{{ conversationsFor(character).length }}</span>
            </div>
            <div v-if="!conversationsFor(character).length" class="conversation-empty">
              <SvgIcon icon="ri:chat-new-line" />
              <span>{{ t('chat.noConversation') }}</span>
            </div>
            <div
              v-for="conversation of conversationsFor(character)"
              :key="conversation.uuid"
              role="button"
              tabindex="0"
              class="conversation-item"
              :class="{ 'is-active': isActiveConversation(conversation.uuid) }"
              @click="handleSelectConversation(character, conversation)"
              @keydown.enter="handleSelectConversation(character, conversation)"
              @keydown.space.prevent="handleSelectConversation(character, conversation)"
            >
              <SvgIcon icon="ri:message-3-line" />
              <span class="conversation-title">{{ conversation.title }}</span>
              <span class="conversation-actions">
                <button type="button" :aria-label="t('chat.renameConversation')" :title="t('chat.renameConversation')" @click.stop="openRename(conversation)">
                  <SvgIcon icon="carbon:edit" />
                </button>
                <button type="button" :aria-label="t('chat.deleteConversation')" :title="t('chat.deleteConversation')" @click.stop="handleDeleteConversation(character, conversation)">
                  <SvgIcon icon="carbon:trash-can" />
                </button>
              </span>
            </div>
          </div>
        </section>
      </div>
    </div>
  </NScrollbar>
</template>

<style scoped lang="less">
.character-list-scroll {
  width: 100%;
  height: 100%;
}

.character-list-content {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: 0 14px 4px;
}

.character-list-heading,
.conversation-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.character-list-heading {
  margin: 3px 2px 10px;
  text-transform: uppercase;
}

.character-list-loading {
  display: grid;
  min-height: 160px;
  place-items: center;
}

.character-count {
  border-radius: 999px;
  background: var(--zhimesh-glass-soft);
  color: var(--zhimesh-primary);
  min-width: 22px;
  padding: 2px 7px;
  text-align: center;
}

.character-list,
.character-group {
  display: flex;
  width: 100%;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
}

.character-group + .character-group {
  margin-top: 2px;
}

.character-card,
.conversation-item {
  width: 100%;
  border: 1px solid transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.character-card {
  display: grid;
  box-sizing: border-box;
  min-width: 0;
  align-items: center;
  grid-template-columns: 32px minmax(0, 1fr) 32px;
  gap: 8px;
  min-height: 58px;
  padding: 8px;
  border-color: var(--zhimesh-border-subtle);
  border-radius: 14px;
  background: var(--zhimesh-glass);
}

.character-card:hover,
.character-card.is-active {
  border-color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.character-avatar {
  display: grid;
  flex: none;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: var(--zhimesh-control-primary);
}

.character-identity {
  display: flex;
  width: fit-content;
  max-width: 100%;
  min-width: 0;
  align-items: center;
  /* 左对齐而非居中：多角色名称长短不一时，头像与标题在各卡片间保持同一起点 */
  justify-self: start;
  gap: 9px;
}

.character-title,
.conversation-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.character-title {
  min-width: 0;
  font-size: 16px;
  font-weight: 720;
  letter-spacing: -0.01em;
}

.character-menu,
.character-expand,
.character-edge-placeholder {
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  place-items: center;
  border-radius: 8px;
  color: var(--zhimesh-text-muted);
  border: 0;
  background: transparent;
  font: inherit;
  cursor: pointer;
}

.character-menu,
.character-expand {
  justify-self: center;
}

.character-menu:hover,
.character-expand:hover {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.character-menu {
  font-size: 18px;
}

.conversation-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 0 2px 4px 22px;
  padding-left: 12px;
  border-left: 1px solid var(--zhimesh-border-subtle);
}

.conversation-heading {
  padding: 2px 5px 4px;
  font-size: 11px;
}

.conversation-item {
  position: relative;
  display: grid;
  align-items: center;
  grid-template-columns: 16px minmax(0, 1fr) auto;
  gap: 7px;
  min-height: 35px;
  padding: 7px 8px;
  border-radius: 10px;
  background: transparent;
  font-size: 12px;
}

.conversation-item:hover,
.conversation-item.is-active {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.conversation-title {
  min-width: 0;
  flex: 1;
}

.conversation-actions {
  display: flex;
  flex: none;
  gap: 2px;
  opacity: 0;
  visibility: hidden;
  transition: opacity 140ms ease, visibility 140ms ease;
}

.conversation-item:hover .conversation-actions,
.conversation-item:focus-within .conversation-actions,
.conversation-item.is-active .conversation-actions {
  opacity: 1;
  visibility: visible;
}

.conversation-actions > button {
  display: grid;
  width: 24px;
  height: 24px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 7px;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.conversation-actions > button:hover {
  background: var(--zhimesh-glass-soft);
}

.character-empty,
.conversation-empty {
  display: flex;
  align-items: center;
  color: var(--zhimesh-text-muted);
}

.character-empty {
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
  padding: 22px 14px;
  border: 1px dashed var(--zhimesh-border);
  border-radius: 14px;
  text-align: center;
}

.character-empty-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 13px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
  font-size: 21px;
}

.character-empty span:last-child {
  font-size: 12px;
}

.conversation-empty {
  gap: 6px;
  padding: 8px;
  font-size: 11px;
  line-height: 1.45;
}

:global(.dark) .character-card {
  background: var(--zhimesh-glass);
}

@media (max-width: 767px) {
  .conversation-item {
    min-height: 52px;
    padding: 4px 4px 4px 9px;
    grid-template-columns: 18px minmax(0, 1fr) auto;
    font-size: 14px;
  }

  .conversation-item:not(.is-active) .conversation-actions {
    display: none;
  }

  .conversation-actions {
    gap: 0;
  }

  .conversation-actions > button {
    width: 44px;
    height: 44px;
    border-radius: 9px;
    color: var(--zhimesh-text-muted);
    font-size: 16px;
  }

  .conversation-actions > button:active {
    color: var(--zhimesh-primary);
    background: var(--zhimesh-glass-soft);
  }
}
</style>
