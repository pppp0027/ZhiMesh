<script setup lang='ts'>
import type { CSSProperties, Ref } from 'vue'
import { computed, inject, ref, watch } from 'vue'
import { NButton, NLayoutSider, useMessage } from 'naive-ui'
import List from './List.vue'
import { SiderAccountBar, SvgIcon } from '@/components/common'
import { useAppStore, useChatStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { t } from '@/locales'
const appStore = useAppStore()
const chatStore = useChatStore()
const ms = useMessage()
const { isMobile } = useBasicLayout()
const openCharacterCreator = inject<(tab?: 'presetCharacter' | 'newCharacter') => void>('openCharacterCreator', () => {})
const createConversation = inject<(character?: Chat.Character | null) => Promise<void>>('createConversation', async () => {})
const creatingConversation = inject<Ref<boolean>>('creatingConversation', ref(false))

const collapsed = computed(() => appStore.pageSiderCollapsed.chat)

const conversationEnabled = computed(() => appStore.sysConfigInfo.conversationEnabled)

function handleAddCharacter() {
  if (chatStore.allCharactersCount >= 50) {
    ms.warning(t('chat.characterReachLimit50'), {
      duration: 1000,
    })
    return
  }
  openCharacterCreator('presetCharacter')
}

async function handleAddConversation() {
  await createConversation(chatStore.getCurCharacter)
}

function handleUpdateCollapsed() {
  appStore.setPageSiderCollapsed('chat', !collapsed.value)
}

const getMobileClass = computed<CSSProperties>(() => {
  if (isMobile.value) {
    return {
      position: 'fixed',
      top: '0',
      bottom: '0',
      height: 'auto',
      zIndex: 50,
    }
  }
  return {}
})

const mobileSafeArea = computed(() => {
  if (isMobile.value) {
    return {
      paddingTop: 'env(safe-area-inset-top)',
      paddingBottom: '0',
      boxSizing: 'border-box',
    }
  }
  return {}
})

watch(
  isMobile,
  (val) => {
    appStore.setPageSiderCollapsed('chat', val)
  },
  {
    immediate: true,
    flush: 'post',
  },
)
</script>

<template>
  <NLayoutSider
    :collapsed="collapsed" :collapsed-width="0" :width="292" :show-trigger="false"
    position="absolute" class="conversation-sider" :style="getMobileClass" @update-collapsed="handleUpdateCollapsed"
  >
    <div class="flex flex-col h-full" :style="mobileSafeArea">
      <main class="flex flex-col flex-1 min-h-0">
        <div class="p-4">
          <div class="section-sider-heading">
            <div class="sider-intro">
              <div class="sider-kicker">
                {{ t('chat.chatWorkspaceTitle') }}
              </div>
              <h2>{{ chatStore.getCurCharacter?.title || t('chat.selectCharacterFirst') }}</h2>
            </div>
            <button
              v-if="!isMobile"
              class="section-sider-collapse"
              type="button"
              :aria-label="t('common.collapseSidebar')"
              :title="t('common.collapseSidebar')"
              @click="handleUpdateCollapsed"
            >
              <span class="section-sider-collapse__icon" />
            </button>
          </div>
          <div v-if="conversationEnabled" class="sider-create-flow">
            <NButton class="conversation-create-button" type="primary" block :disabled="!chatStore.getCurCharacter" :loading="creatingConversation" @click="handleAddConversation">
              <template #icon>
                <SvgIcon icon="ri:chat-new-line" />
              </template>
              {{ t('chat.newConversation') }}
            </NButton>
            <NButton tertiary block class="character-add-button" @click="handleAddCharacter">
              <template #icon>
                <SvgIcon icon="ri:user-add-line" />
              </template>
              {{ t('chat.newOtherCharacter') }}
            </NButton>
          </div>
          <NButton v-else type="primary" block @click="handleAddCharacter">
            <template #icon>
              <SvgIcon icon="ri:user-add-line" />
            </template>
            {{ t('chat.newChatButton') }}
          </NButton>
        </div>
        <div class="flex-1 min-h-0 pb-4 overflow-hidden">
          <List />
        </div>
      </main>
      <SiderAccountBar v-if="isMobile" />
    </div>
  </NLayoutSider>
  <button
    v-if="!isMobile && collapsed"
    class="section-sider-reveal"
    type="button"
    :aria-label="t('common.expandSidebar')"
    :title="t('common.expandSidebar')"
    @click="handleUpdateCollapsed"
  >
    <span class="section-sider-reveal__chevron" />
  </button>
  <template v-if="isMobile">
    <div v-show="!collapsed" class="fixed inset-0 z-40 bg-black/40" @click="handleUpdateCollapsed" />
  </template>
</template>

<style scoped>
.section-sider-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.section-sider-collapse {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  margin-left: 10px;
  padding: 0;
  place-items: center;
  color: var(--zhimesh-text-muted);
  border: 1px solid var(--zhimesh-border);
  border-radius: 8px;
  background: var(--zhimesh-glass-soft);
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.section-sider-collapse:hover,
.section-sider-collapse:focus-visible {
  color: var(--zhimesh-text);
  border-color: var(--zhimesh-border-hover, var(--zhimesh-border));
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: 0 3px 10px rgba(15, 23, 42, 0.09);
  outline: none;
}

.section-sider-collapse__icon {
  position: relative;
  display: block;
  width: 16px;
  height: 16px;
  border: 2px solid currentColor;
  border-radius: 4px;
}

.section-sider-collapse__icon::before {
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 4px;
  width: 2px;
  border-radius: 999px;
  background: currentColor;
  content: '';
}

.section-sider-reveal {
  position: absolute;
  top: 50%;
  left: 0;
  z-index: 65;
  display: flex;
  width: 28px;
  height: 64px;
  padding: 0;
  align-items: center;
  justify-content: center;
  color: var(--zhimesh-text-muted);
  border: 1px solid var(--zhimesh-glass-edge);
  border-left: 0;
  border-radius: 0 10px 10px 0;
  background: var(--zhimesh-glass-soft-alpha);
  box-shadow: var(--zhimesh-shadow-soft), inset 0 1px 0 var(--zhimesh-glass-highlight);
  cursor: pointer;
  transform: translateY(-50%);
  transition: color 180ms ease, border-color 180ms ease, background 180ms ease, box-shadow 180ms ease;
}

.section-sider-reveal:hover,
.section-sider-reveal:focus-visible {
  color: var(--zhimesh-primary);
  border-color: var(--zhimesh-border);
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: var(--zhimesh-shadow-soft), inset 0 1px 0 var(--zhimesh-glass-highlight);
  outline: none;
}

.section-sider-reveal__chevron {
  width: 9px;
  height: 9px;
  border-right: 2.5px solid currentColor;
  border-bottom: 2.5px solid currentColor;
  border-radius: 1px;
  transform: translateX(-2px) rotate(-45deg);
}
</style>
