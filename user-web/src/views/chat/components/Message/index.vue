<script setup lang='ts'>
import { computed, h, ref, watch } from 'vue'
import { NButton, NCollapse, NCollapseItem, NDropdown, NEmpty, NIcon, NImage, NSpace, NSpin, useDialog } from 'naive-ui'
import type { ImageRenderToolbarProps } from 'naive-ui'
import { Delete24Regular } from '@vicons/fluent'
import { Reload } from '@vicons/ionicons5'
import AvatarComponent from './Avatar.vue'
import TextComponent from './Text.vue'
import ToolSteps from './ToolSteps.vue'
import SuspensionCard from './SuspensionCard.vue'
import { SvgIcon } from '@/components/common'
import { copyText, formatDuration } from '@/utils/format'
import { useIconRender } from '@/hooks/useIconRender'
import { t } from '@/locales'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAuthStore } from '@/store'
import { getRealFileUrl } from '@/utils/functions'
import { openDeleteDialog } from '@/utils/dialog'

import NoPic from '@/assets/no_pic.png'
const props = withDefaults(defineProps<Props>(), {
  showAvatar: true,
  regenerateDisabled: false,
})
const emit = defineEmits<Emit>()
const dialog = useDialog()
const authStore = useAuthStore()
const token = ref<string>(authStore.token)

interface Props {
  dateTime?: string
  thinkingContent?: string
  text?: string
  imageUrls?: string[]
  inversion?: boolean
  regenerate?: boolean
  showAvatar?: boolean
  error?: boolean
  // 思考中
  thinking?: boolean
  // 最终答案的加载中
  loading?: boolean
  type: string // text,text-image,image

  aiModelPlatform?: string // openai,dashscope,qianfan,ollama,deepseek
  aiModelId?: string | number
  inputTokens?: number
  outputTokens?: number
  duration?: number
  toolCalls?: Chat.ToolCall[]
  /** 提问侧状态 Map（question.state）：等待期状态条按 state 键流转文案 */
  state?: Map<string, string>
  /** Agent 协作挂起载荷（ask_user 追问 / 人工审批卡片） */
  suspension?: Chat.SuspensionPayload
  /** 挂起卡片是否可交互（实时挂起未应答 true；历史回放/已应答只读） */
  suspensionInteractive?: boolean
  /** 正在生成/请求中时禁用重新生成入口，避免点击被静默吞掉 */
  regenerateDisabled?: boolean
}

interface Emit {
  (ev: 'regenerate'): void
  (ev: 'delete'): void
  (ev: 'delOneImage', fileUrl: string): void
  (ev: 'suspensionAnswer', text: string): void
}

const { isMobile } = useBasicLayout()

const { iconRender } = useIconRender()

const textRef = ref<HTMLElement>()

const asRawText = ref(props.inversion)

const messageRef = ref<HTMLElement>()

const expandedNames = ref<string[]>(['finalAnswer'])

// 工具是否有执行中步骤（[TOOL_STARTED] 已点亮、[TOOL_CALL] 未回填）
const hasRunningTool = computed(() => !!props.toolCalls?.some(tool => tool.running))

// 等待期状态条键：工具执行中优先；否则跟随 question.state 的 state 键；缺省问题分析中
const statusKey = computed(() => {
  if (hasRunningTool.value)
    return 'tool_running'
  return props.state?.get('state') || 'question_analysing'
})

const options = computed(() => {
  const common = [
    {
      label: t('chat.copy'),
      key: 'copyText',
      icon: iconRender({ icon: 'ri:file-copy-2-line' }),
    },
    {
      label: t('common.delete'),
      key: 'delete',
      icon: iconRender({ icon: 'ri:delete-bin-line' }),
    },
  ]

  if (!props.inversion) {
    common.unshift({
      label: asRawText.value ? t('chat.preview') : t('chat.showRawText'),
      key: 'toggleRenderType',
      icon: iconRender({ icon: asRawText.value ? 'ic:outline-code-off' : 'ic:outline-code' }),
    })
  }

  return common
})

function itemHeadClick(data: { name: string | number; expanded: boolean; event: MouseEvent }) {
  const idx = expandedNames.value.findIndex(name => name === data.name.toString())
  if (idx === -1 && data.expanded)
    expandedNames.value.push(data.name.toString())

  if (idx !== -1 && !data.expanded)
    expandedNames.value.splice(idx, 1)
}

function handleSelect(key: 'copyText' | 'delete' | 'toggleRenderType') {
  switch (key) {
    case 'copyText':
      copyText({ text: props.text ?? '' })
      return
    case 'toggleRenderType':
      asRawText.value = !asRawText.value
      return
    case 'delete':
      emit('delete')
  }
}

function handleRegenerate() {
  messageRef.value?.scrollIntoView()
  emit('regenerate')
}

function handleDelImage(imageUrl: string) {
  openDeleteDialog(dialog, {
    title: t('chat.deleteImageConfirmTitle'),
    content: t('chat.deleteImageConfirmContent'),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      emit('delOneImage', imageUrl)
    },
  })
}
function renderToolbarOut2(imageUrl: string) {
  return ({ nodes }: ImageRenderToolbarProps) => {
    return [
      ...Object.values(nodes),
      h(
        NButton,
        {
          quaternary: true,
          circle: true,
          color: 'white',
          onClick: () => {
            handleDelImage(imageUrl)
          },
        },
        {
          icon: () => h(Delete24Regular),
        },
      ),
    ]
  }
}

// 相位：仅"思考中且正文未开始"算 thinking；正文首字到达即跃迁为 answer，思考面板随之收起
const phase = computed(() => (props.thinking && !props.text) ? 'thinking' : 'answer')
// watch computed 只在相位跃迁时触发：流式期间用户手动展开思考不会被后续 chunk 重置；历史回放/挂起中途挂载（text 已非空）直接进 answer 相位
watch(phase, (p) => {
  expandedNames.value = p === 'thinking' ? ['thinking'] : ['finalAnswer']
}, { immediate: true })
</script>

<template>
  <div ref="messageRef" class="message-row" :class="inversion ? 'is-user' : 'is-assistant'">
    <div
      v-if="showAvatar"
      class="message-avatar"
    >
      <AvatarComponent :name="inversion ? 'user' : aiModelPlatform" :model-id="aiModelId" />
    </div>
    <div class="message-body">
      <p class="message-meta" :class="inversion ? 'text-right' : 'text-left'">
        {{ dateTime }}
        <span v-if="inputTokens != null" class="message-meta-item" :title="t('chat.metaTokensCumulativeTip')">
          <SvgIcon icon="ri:download-2-line" />{{ t('chat.metaInputTokens') }} {{ inputTokens }}
        </span>
        <span v-if="outputTokens != null" class="message-meta-item" :title="t('chat.metaTokensCumulativeTip')">
          <SvgIcon icon="ri:upload-2-line" />{{ t('chat.metaOutputTokens') }} {{ outputTokens }}
        </span>
        <span v-if="duration != null" class="message-meta-item">
          <SvgIcon icon="ri:time-line" />{{ formatDuration(duration) }}
        </span>
      </p>
      <ToolSteps v-if="toolCalls && toolCalls.length" :tool-calls="toolCalls" class="message-tool-steps" />
      <!-- Agent 协作挂起卡片：实时未应答可交互（选项/审批按钮），历史回放只读 -->
      <SuspensionCard
        v-if="suspension"
        :suspension="suspension"
        :interactive="!!suspensionInteractive"
        class="message-tool-steps"
        @answer="text => emit('suspensionAnswer', text)"
      />
      <div class="message-content">
        <!-- 消息框侧边下拉选择列表 -->
        <template v-if="type === 'text' || type === 'text-image'">
          <NCollapse
            v-if="thinkingContent" class="thinking-collapse" :default-expanded-names="['finalAnswer']" :expanded-names="expandedNames"
            @item-header-click="itemHeadClick"
          >
            <NCollapseItem name="thinking">
              <template #header>
                <span class="thinking-section-title">
                  <SvgIcon icon="ri:brain-line" />
                  {{ t('chat.deepThinking') }}
                  <i v-if="thinking" />
                </span>
              </template>
              <TextComponent
                ref="textRef" :inversion="inversion" :error="error" :text="thinkingContent"
                :loading="thinking" :as-raw-text="asRawText"
              />
            </NCollapseItem>
            <NCollapseItem name="finalAnswer">
              <template #header>
                <span class="thinking-section-title">
                  <SvgIcon icon="ri:message-3-line" />
                  {{ t('chat.finalAnswer') }}
                </span>
              </template>
              <TextComponent
                ref="textRef" :inversion="inversion" :error="error" :text="text"
                :loading="loading" :as-raw-text="asRawText" :status-key="statusKey"
              />
            </NCollapseItem>
          </NCollapse>
          <TextComponent
            v-else ref="textRef" :inversion="inversion" :error="error" :text="text"
            :loading="loading" :as-raw-text="asRawText" :status-key="statusKey"
          />
        </template>
      </div>
      <div v-if="type === 'text' || type === 'text-image'" class="message-actions">
        <button
          v-if="regenerate"
          type="button" class="message-action-button is-regenerate" :title="t('chat.regenerateAnswer')"
          :disabled="regenerateDisabled" @click="handleRegenerate"
        >
          <SvgIcon icon="ri:restart-line" />
          <span>{{ t('chat.regenerateAnswer') }}</span>
        </button>
        <NDropdown
          :trigger="isMobile ? 'click' : 'hover'" :placement="!inversion ? 'right' : 'left'"
          :options="options" @select="handleSelect"
        >
          <button type="button" class="message-action-button" :title="t('chat.moreActions')">
            <SvgIcon icon="ri:more-2-fill" />
          </button>
        </NDropdown>
        <slot name="actions" />
      </div>
      <NSpace class="mt-1" :style="inversion ? 'justify-content:flex-end;' : ''">
        <!-- render image -->
        <template v-if="type === 'image' || type === 'text-image'">
          <template v-if="loading">
            <NSpin size="medium">
              <template #icon>
                <NIcon>
                  <Reload />
                </NIcon>
              </template>
            </NSpin>
          </template>
          <template v-else>
            <template v-if="!imageUrls || imageUrls.length === 0">
              <NEmpty :description="t('chat.imageNotFound')" />
            </template>
            <template v-if="imageUrls && imageUrls.length > 0">
              <!-- <NImageGroup :render-toolbar="renderToolbar"> -->
              <NSpace>
                <template v-for="imageUrl in imageUrls" :key="imageUrl">
                  <NImage
                    v-if="imageUrl" width="100" :src="`${getRealFileUrl(imageUrl)}?token=${token}`"
                    :fallback-src="NoPic" :render-toolbar="renderToolbarOut2(imageUrl)"
                  />
                </template>
              </NSpace>
              <!-- </NImageGroup> -->
            </template>
          </template>
        </template>
      </NSpace>
      <slot />
    </div>
  </div>
</template>

<style scoped lang="less">
.message-row {
  display: flex;
  width: 100%;
  margin-bottom: 22px;
  align-items: flex-start;
  gap: 10px;
  overflow: visible;
}

.message-row.is-user {
  flex-direction: row-reverse;
}

.message-avatar {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 12px;
  background: var(--zhimesh-glass);
  box-shadow: none;
}

.message-body {
  display: flex;
  min-width: 0;
  max-width: min(900px, calc(100% - 46px));
  flex-direction: column;
  align-items: flex-start;
}

.is-assistant .message-body {
  width: min(900px, calc(100% - 46px));
}

.is-user .message-body {
  max-width: min(72%, 680px);
  align-items: flex-end;
}

.message-meta {
  min-height: 18px;
  margin: 0 4px 5px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.message-meta-item {
  display: inline-flex;
  margin-left: 4px;
  align-items: center;
  gap: 3px;
}

.message-meta-item :deep(.iconify) {
  font-size: 11px;
}

.message-tool-steps {
  margin: -3px 4px 5px;
}

.message-content {
  max-width: 100%;
}

.message-actions {
  display: flex;
  min-height: 28px;
  margin-top: 5px;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  opacity: 0.66;
  transition: opacity 0.2s ease;
}

.message-row:hover .message-actions,
.message-actions:focus-within {
  opacity: 1;
}

.is-user .message-actions {
  justify-content: flex-end;
}

.message-action-button {
  display: inline-flex;
  width: 28px;
  height: 28px;
  padding: 0;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 9px;
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass);
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease, background 0.2s ease, transform 0.2s ease;
}

.message-action-button.is-regenerate {
  width: auto;
  padding: 0 9px;
  font-size: 11px;
  font-weight: 600;
}

.message-action-button:hover {
  border-color: var(--zhimesh-primary);
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.message-action-button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
  transform: none;
}

.message-actions :deep(.message-action-link) {
  height: 28px;
  padding: 0 8px;
  border-radius: 9px !important;
  font-size: 11px;
  font-weight: 600;
}

.thinking-collapse {
  width: min(760px, 100%);
  padding: 3px 10px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 14px;
  background: var(--zhimesh-glass);
}

.thinking-collapse :deep(.n-collapse-item__header-main) {
  padding: 8px 0;
}

.thinking-section-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  font-weight: 650;
}

.thinking-section-title i {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--zhimesh-accent);
  box-shadow: 0 0 0 4px rgba(22, 135, 125, 0.12);
  animation: thinking-live-pulse 1.2s infinite ease-in-out;
}

@keyframes thinking-live-pulse {
  50% {
    opacity: 0.45;
    transform: scale(0.8);
  }
}

:global(.dark) .message-avatar,
:global(.dark) .message-action-button,
:global(.dark) .thinking-collapse {
  background: var(--zhimesh-glass-strong);
}

@media (max-width: 767px) {
  .message-row {
    gap: 7px;
  }

  .message-avatar {
    width: 32px;
    height: 32px;
    flex-basis: 32px;
  }

  .message-body,
  .is-assistant .message-body {
    width: calc(100% - 39px);
    max-width: calc(100% - 39px);
  }

  .is-user .message-body {
    max-width: 84%;
  }

  .message-actions {
    opacity: 1;
  }

  .message-action-button,
  .message-actions :deep(.message-action-link) {
    min-width: 40px;
    height: 40px;
  }
}
</style>
