<script setup lang='ts'>
import { computed, provide, ref } from 'vue'
import { NLayout, NLayoutContent, useMessage } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import Sider from './sider/index.vue'
import CreateConv from './sider/CreateConv.vue'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore, useAuthStore, useChatStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const chatStore = useChatStore()
const authStore = useAuthStore()
const ms = useMessage()
interface CharacterCreatorRef {
  openModal: (tab?: 'presetCharacter' | 'newCharacter') => void
}
const createCharacterRef = ref<CharacterCreatorRef | null>(null)
const creatingConversation = ref(false)

function openCharacterCreator(tab: 'presetCharacter' | 'newCharacter' = 'presetCharacter') {
  createCharacterRef.value?.openModal(tab)
}

async function createConversation(character = chatStore.getCurCharacter) {
  if (!authStore.checkLoginOrShow())
    return
  if (!character || character.uuid === 'default') {
    ms.warning(t('chat.selectCharacterFirst'))
    return
  }
  creatingConversation.value = true
  try {
    const { data } = await api.conversationAdd(character.uuid, t('chat.newConversation'))
    chatStore.addConversation(data)
    await chatStore.setActiveConversation(character.uuid, data.uuid)
    ms.success(t('chat.conversationCreated'))
  } finally {
    creatingConversation.value = false
  }
}

provide('openCharacterCreator', openCharacterCreator)
provide('createConversation', createConversation)
provide('creatingConversation', creatingConversation)

const { uuid: curCharacterUuid } = route.params as { uuid: string }
if (!curCharacterUuid) {
  router.replace({ name: 'Chat', params: { uuid: chatStore.active } })
} else if (curCharacterUuid !== chatStore.active) {
  chatStore.active = curCharacterUuid
}
const routeConversationUuid = route.query.conversation as string
if (routeConversationUuid)
  chatStore.activeConversationUuid = routeConversationUuid

const { isMobile } = useBasicLayout()

const collapsed = computed(() => appStore.pageSiderCollapsed.chat)

const getMobileClass = computed(() => {
  if (isMobile.value)
    return ['rounded-none', 'shadow-none']
  return ['rounded-md', 'dark:border-neutral-800']
})

const getContainerClass = computed(() => {
  return [
    'h-full',
    { 'pl-[292px]': !isMobile.value && !collapsed.value },
  ]
})
</script>

<template>
  <div class="h-full transition-all" :class="[isMobile ? 'p-0' : '']">
    <div class="h-full overflow-hidden" :class="getMobileClass">
      <NLayout class="z-40 transition chat-workspace" :class="getContainerClass" has-sider>
        <Sider />
        <NLayoutContent class="h-full chat-content">
          <RouterView v-slot="{ Component, route }">
            <KeepAlive :max="8"><component :is="Component" :key="route.fullPath" /></KeepAlive>
          </RouterView>
        </NLayoutContent>
      </NLayout>
    </div>
    <CreateConv ref="createCharacterRef" @character-created="createConversation" />
  </div>
</template>
