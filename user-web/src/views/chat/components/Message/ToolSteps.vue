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

// 执行中的步骤数（[TOOL_STARTED] 已点亮、[TOOL_CALL] 未回填），摘要行据此追加进行中文案
const runningCount = computed(() => props.toolCalls.filter(tool => tool.running).length)

// 历史回放通道带 seq 时按序号稳定排序；实时 SSE 事件无 seq，保持原顺序
const orderedToolCalls = computed(() => {
  if (props.toolCalls.every(tool => tool.seq == null))
    return props.toolCalls
  return [...props.toolCalls].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
})

// fetch 类工具抓取的来源域名（去重并计数，按执行顺序）；args 非法或无合法 URL 时整行不渲染
const sourceHosts = computed(() => {
  const counts = new Map<string, number>()
  orderedToolCalls.value.forEach((tool) => {
    if (!tool.toolName?.toLowerCase().includes('fetch') || !tool.args)
      return
    try {
      const { url } = JSON.parse(tool.args)
      const hostname = new URL(url).hostname
      if (hostname)
        counts.set(hostname, (counts.get(hostname) ?? 0) + 1)
    } catch {
      // args 非法 JSON / 无 url / URL 不合法，跳过该条
    }
  })
  return [...counts.entries()].map(([host, count]) => ({ host, count }))
})

// 内置协作工具名 → 挂起节点类型（历史回放的轨迹行按工具名识别挂起/审批节点）
const SUSPENSION_TOOL_KINDS: Record<string, Chat.SuspensionPayload['kind']> = {
  ask_user: 'ASK_USER',
  request_human_approval: 'APPROVAL',
}

function stepSuspensionKind(tool: Chat.ToolCall): Chat.SuspensionPayload['kind'] | undefined {
  if (tool.suspensionKind)
    return tool.suspensionKind
  return SUSPENSION_TOOL_KINDS[tool.toolName]
}

// running 步骤用 is-wait 同款 accent 色（与挂起节点一致）
function stepStatusClass(tool: Chat.ToolCall): string {
  if (tool.running || stepSuspensionKind(tool))
    return 'is-wait'
  if (tool.resumed || tool.success)
    return 'is-ok'
  return 'is-fail'
}

function stepStatusIcon(tool: Chat.ToolCall): string {
  if (tool.running)
    return 'line-md:loading-twotone-loop'
  const kind = stepSuspensionKind(tool)
  if (kind === 'ASK_USER')
    return 'ri:question-answer-line'
  if (kind)
    return 'ri:shield-check-line'
  return tool.resumed ? 'ri:play-circle-line' : tool.success ? 'ri:check-line' : 'ri:close-line'
}

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
      <span v-if="runningCount > 0" class="tool-steps-running">{{ t('chat.toolStepRunning') }}</span>
      <span v-if="failedCount > 0" class="tool-steps-warn">
        <SvgIcon icon="ri:error-warning-line" />{{ failedCount }}
      </span>
    </button>
    <!-- 信息来源行常显：折叠时也可见（审查 Important #1，来源标注是产品决策的可见性核心） -->
    <span v-if="sourceHosts.length" class="tool-step-sources">
      <span class="tool-step-label">{{ t('chat.sourceList') }}</span>
      <span v-for="source in sourceHosts" :key="source.host" class="tool-step-source">
        {{ source.host }}<template v-if="source.count > 1"> ×{{ source.count }}</template>
      </span>
    </span>
    <span v-show="expanded" class="tool-steps-list">
      <span v-for="(tool, idx) in orderedToolCalls" :key="idx" class="tool-step">
        <!-- running 步骤点亮 spinner；挂起节点（等待用户应答/等待审批）与恢复节点（应答已提交）用专属图标区分普通执行成败 -->
        <SvgIcon class="tool-step-status" :class="stepStatusClass(tool)" :icon="stepStatusIcon(tool)" />
        <span v-if="tool.toolName" class="tool-step-name">{{ tool.toolName }}</span>
        <span v-if="stepSuspensionKind(tool) === 'ASK_USER'" class="tool-step-node">{{ t('chat.stepSuspensionAskUser') }}</span>
        <span v-else-if="stepSuspensionKind(tool)" class="tool-step-node">{{ t('chat.stepSuspensionApproval') }}</span>
        <span v-else-if="tool.resumed" class="tool-step-node">{{ t('chat.stepResumed') }}</span>
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

.tool-steps-running {
  color: var(--zhimesh-accent);
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

.tool-step-status.is-wait {
  color: var(--zhimesh-accent);
}

.tool-step-node {
  flex: none;
  padding: 0 7px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 7px;
  color: var(--zhimesh-text-muted);
  font-size: 10px;
  line-height: 1.6;
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

.tool-step-sources {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 3px 6px;
  margin-top: 4px;
  padding-top: 4px;
  border-top: 1px dashed var(--zhimesh-border-subtle);
  font-size: 11px;
  line-height: 1.5;
}

.tool-step-source {
  flex: none;
  padding: 0 7px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 7px;
  color: var(--zhimesh-text-muted);
  font-size: 10px;
  line-height: 1.6;
}
</style>
