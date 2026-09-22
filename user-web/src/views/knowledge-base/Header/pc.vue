<script lang="ts" setup>
import { h, ref } from 'vue'
import { NButton, NDropdown } from 'naive-ui'
import { ApiKeyModal, SvgIcon } from '@/components/common'
import { useAuthStore } from '@/store'
import { t } from '@/locales'
import { knowledgeBaseEmptyInfo } from '@/utils/functions'
import KbInfo from '@/views/knowledge-base/Header/KbInfo.vue'

interface Props {
  knowledgeBase: KnowledgeBase.Info
}
withDefaults(defineProps<Props>(), {
  knowledgeBase: () => knowledgeBaseEmptyInfo(),
})
const showEditModal = ref(false)
const showApiKeyModal = ref(false)
const authStore = useAuthStore()

const options = [
  {
    label: t('common.detail'),
    key: 'detail',
    icon: () => h(SvgIcon, { icon: 'carbon:information', class: 'text-base cursor-pointer' }),
  },
  {
    label: t('extApi.apiAccess'),
    key: 'api',
    icon: () => h(SvgIcon, { icon: 'carbon:api', class: 'text-base cursor-pointer' }),
  },
]

function handleSelect(key: string) {
  if (!authStore.checkLoginOrShow())
    return
  if (key === 'detail') {
    showEditModal.value = true
  } else if (key === 'api') {
    showApiKeyModal.value = true
  }
}
function showOrCloseModal(show: boolean) {
  showEditModal.value = show
}
</script>

<template>
  <header class="knowledge-title-header">
    <div class="knowledge-title-header-inner">
      <div class="knowledge-title-group">
        <span class="knowledge-title-mark"><SvgIcon icon="ri:book-2-line" /></span>
        <h1>{{ knowledgeBase?.title ?? '' }}</h1>
      </div>
      <NDropdown :options="options" @select="handleSelect">
        <NButton class="knowledge-header-actions" quaternary circle size="small">
          <template #icon>
            <SvgIcon icon="ri:more-fill" />
          </template>
        </NButton>
      </NDropdown>
    </div>
    <KbInfo v-if="knowledgeBase && knowledgeBase.uuid" :show-modal="showEditModal" :knowledge-base="knowledgeBase" @showModal="showOrCloseModal" />
    <ApiKeyModal v-model:show="showApiKeyModal" type="knowledge" :uuid="knowledgeBase?.uuid ?? ''" :title="knowledgeBase?.title ?? t('knowledgeBase.knowledgeBase')" />
  </header>
</template>

<style scoped lang="less">
.knowledge-title-header {
  position: sticky;
  z-index: 20;
  top: 0;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-strong);
}

.knowledge-title-header-inner {
  position: relative;
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: center;
  padding: 0 56px;
}

.knowledge-title-group {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
}

.knowledge-title-mark {
  display: grid;
  width: 32px;
  height: 32px;
  flex: none;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: var(--zhimesh-control-primary);
  font-size: 16px;
  box-shadow: none;
}

.knowledge-title-group h1 {
  max-width: min(60vw, 680px);
  margin: 0;
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 18px;
  font-weight: 760;
  letter-spacing: -0.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge-header-actions {
  position: absolute;
  right: 12px;
}
</style>
