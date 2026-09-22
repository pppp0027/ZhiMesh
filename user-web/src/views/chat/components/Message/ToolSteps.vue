<script setup lang="ts">
import { computed, ref } from 'vue'
import { SvgIcon } from '@/components/common'
import { t } from '@/locales'
import { formatDuration } from '@/utils/format'

interface Props {
  toolCalls: Chat.ToolCall[]
}

const props = defineProps<Props>()

const expanded = ref(false)

const failedCount = computed(() => props.toolCalls.filter(tool => !tool.success).length)

// 历史回放通道带 seq 时按序号稳定排序；实时 SSE 事件无 seq，保持原顺序
const orderedToolCalls = computed(() => {
  if (props.toolCalls.every(tool => tool.seq == null))
    return props.toolCalls
  return [...props.toolCalls].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
})

function toggle() {
  expanded.value = !expanded.value
}

function truncate(text: string | undefined | null, max: number): string {
  if (!text)
    return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}
</script>

<template>
  <span class="tool-steps">
    <button
      type="button" class="tool-steps-summary" :title="expanded ? t('chat.collapseToolCalls') : t('chat.toolCallDetails')"
      @click="toggle"
    >
      <SvgIcon class="tool-steps-arrow" :class="{ 'is-open': expanded }" icon="ri:arrow-right-s-line" />
      <SvgIcon icon="ri:tools-line" />
      <span>{{ toolCalls.length }} {{ t('chat.toolCallCount') }}</span>
      <span v-if="failedCount > 0" class="tool-steps-warn">
        <SvgIcon icon="ri:error-warning-line" />{{ failedCount }}
      </span>
    </button>
    <span v-show="expanded" class="tool-steps-list">
      <span v-for="(tool, idx) in orderedToolCalls" :key="idx" class="tool-step">
        <SvgIcon class="tool-step-status" :class="tool.success ? 'is-ok' : 'is-fail'" :icon="tool.success ? 'ri:check-line' : 'ri:close-line'" />
        <span class="tool-step-name">{{ tool.toolName }}</span>
        <span v-if="tool.args" class="tool-step-args" :title="tool.args">
          <span class="tool-step-label">{{ t('chat.toolCallArgs') }}</span>{{ truncate(tool.args, 60) }}
        </span>
        <span v-if="formatDuration(tool.durationMs)" class="tool-step-duration">
          <span class="tool-step-label">{{ t('chat.toolCallDuration') }}</span>{{ formatDuration(tool.durationMs) }}
        </span>
        <span v-if="tool.resultSummary" class="tool-step-result" :title="tool.resultSummary">
          <span class="tool-step-label">{{ t('chat.toolCallResult') }}</span>{{ truncate(tool.resultSummary, 120) }}
        </span>
      </span>
    </span>
  </span>
</template>

<style scoped lang="less">
.tool-steps {
  display: block;
}

.tool-steps-summary {
  display: inline-flex;
  padding: 0 6px;
  align-items: center;
  gap: 4px;
  border: none;
  border-radius: 7px;
  color: inherit;
  font-size: 11px;
  line-height: 1.45;
  background: transparent;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.tool-steps-summary:hover {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.tool-steps-arrow {
  font-size: 12px;
  transition: transform 0.2s ease;
}

.tool-steps-arrow.is-open {
  transform: rotate(90deg);
}

.tool-steps-warn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--zhimesh-warning-text);
  font-weight: 600;
}

.tool-steps-list {
  display: block;
  width: 100%;
  margin-top: 4px;
  padding: 6px 10px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 10px;
  background: var(--zhimesh-glass);
}

:global(.dark) .tool-steps-list {
  background: var(--zhimesh-glass-strong);
}

.tool-step {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 3px 8px;
  padding: 3px 0;
  font-size: 11px;
  line-height: 1.5;
}

.tool-step + .tool-step {
  border-top: 1px dashed var(--zhimesh-border-subtle);
}

.tool-step-status {
  flex: none;
  align-self: center;
  font-size: 12px;
}

.tool-step-status.is-ok {
  color: var(--zhimesh-success-text);
}

.tool-step-status.is-fail {
  color: var(--zhimesh-danger-text);
}

.tool-step-label {
  margin-right: 3px;
  color: var(--zhimesh-text-muted);
}

.tool-step-name {
  flex: none;
  color: var(--zhimesh-text);
  font-weight: 600;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.tool-step-args {
  max-width: 100%;
  overflow-wrap: anywhere;
  opacity: 0.75;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.tool-step-duration {
  flex: none;
  color: var(--zhimesh-text-muted);
}

.tool-step-result {
  max-width: 100%;
  overflow-wrap: anywhere;
  opacity: 0.8;
}
</style>
