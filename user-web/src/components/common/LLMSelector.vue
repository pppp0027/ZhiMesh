<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { NButton, NDropdown, NTooltip } from 'naive-ui'
import type { DropdownOption } from 'naive-ui'
import PlatformAvatar from '@/components/common/PlatformAvatar.vue'
import { useAppStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'

interface Props {
  nameOnly?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  nameOnly: false,
})

const appStore = useAppStore()
const isRefreshing = ref(false)

const currentLLM = computed(() => {
  const id = appStore.selectedLLM.modelId
  // Keep the selected model's platform metadata while the refreshed list is
  // loading, so the selector never briefly loses the same logo used in chat.
  return (id ? appStore.getLLMById(id) : undefined) || appStore.selectedLLM
})

function renderLabel(option: DropdownOption) {
  const val = option.value as string
  const llm = appStore.getLLMById(val)
  const isUnhealthy = llm?.healthStatus === 'UNHEALTHY'
  const platformLabel = llm?.platformTitle || llm?.modelPlatform || ''
  const children = [
    h(
      'span',
      {
        'class': `model-health-dot ${isUnhealthy ? 'is-unhealthy' : 'is-healthy'}`,
        'aria-label': isUnhealthy ? '模型异常' : '模型可用',
      },
    ),
    h(
      PlatformAvatar,
      {
        name: llm?.modelPlatform,
        label: platformLabel,
        host: llm?.platformHost,
        iconUrl: llm?.platformIconUrl,
        size: 24,
      },
    ),
    h(
      'span',
      {
        class: `model-option-name${option.disabled ? ' is-disabled' : ''}`,
      },
      option.label as string,
    ),
  ]
  if (isUnhealthy) {
    return h(NTooltip, { placement: 'right' }, {
      trigger: () => h('div', { class: 'model-option' }, children),
      default: () => llm?.healthReason || 'Unavailable',
    })
  }
  return h('div', { class: 'model-option' }, children)
}

function handleSelect(key: string | number) {
  appStore.setSelectedLLM(`${key}`)
}

async function handleVisibilityChange(show: boolean) {
  if (!show || isRefreshing.value)
    return
  isRefreshing.value = true
  try {
    const response = await api.loadLLMs<AiModelInfo[]>()
    appStore.setLLMs(response.data)
  } catch (error) {
    console.error('Failed to refresh the available model list', error)
  } finally {
    isRefreshing.value = false
  }
}
</script>

<template>
  <NDropdown
    size="small" placement="top-start" trigger="click" :show-arrow="true" :render-label="renderLabel"
    :options="appStore.llms" @select="handleSelect" @update:show="handleVisibilityChange"
  >
    <NButton icon-placement="right" :loading="isRefreshing">
      <PlatformAvatar
        v-if="!props.nameOnly && currentLLM"
        class="mr-1.5"
        :name="currentLLM.modelPlatform"
        :label="currentLLM.platformTitle"
        :host="currentLLM.platformHost"
        :icon-url="currentLLM.platformIconUrl"
        :size="22"
      />
      <span class="model-selector-label">{{ currentLLM?.modelTitle || currentLLM?.modelName || t('draw.noModel') }}</span>
    </NButton>
  </NDropdown>
</template>

<style>
.model-option {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  padding: 1px 0;
}

.model-health-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: #2fba6d;
  box-shadow: 0 0 0 2px rgb(47 186 109 / 12%);
}

.model-health-dot.is-unhealthy {
  background: #c93d56;
  box-shadow: 0 0 0 2px rgb(201 61 86 / 12%);
}

.model-option-name {
  min-width: 0;
  overflow: hidden;
  color: inherit;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* line-height 默认继承按钮的 1，11px 字号下 g/p/y 等下伸部会被 overflow 裁掉 */
.model-selector-label {
  line-height: 1.4;
  white-space: nowrap;
}

.model-option-name.is-disabled {
  opacity: 0.58;
}
</style>
