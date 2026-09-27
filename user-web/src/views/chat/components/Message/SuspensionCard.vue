<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { SvgIcon } from '@/components/common'
import { t } from '@/locales'

interface Props {
  suspension: Chat.SuspensionPayload
  /** 实时挂起未应答时可交互；历史回放/已应答只读（不显示可点按钮） */
  interactive?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  interactive: false,
})

const emit = defineEmits<Emit>()
interface Emit {
  (ev: 'answer', text: string): void
}

// 实时 SSE 事件载荷键为 kind，历史回放 AnswerMeta.suspension 键为 type（后端已知不对称）
const kind = computed(() => props.suspension.kind ?? props.suspension.type ?? '')
const isAskUser = computed(() => kind.value === 'ASK_USER')
const isApproval = computed(() => kind.value === 'APPROVAL' || kind.value === 'MCP_APPROVAL')
const options = computed(() => (Array.isArray(props.suspension.options) ? props.suspension.options : []))

const rejecting = ref(false)
const rejectReason = ref('')

const riskClass = computed(() => {
  switch (props.suspension.riskLevel) {
    case 'HIGH': return 'is-high'
    case 'MEDIUM': return 'is-medium'
    default: return 'is-low'
  }
})

function handleOption(option: string) {
  emit('answer', option)
}

// 同意类文本由用户可改（输入框自由文本同样视为批准）；拒绝必须走结构化前缀，逐字符精确
function handleApprove() {
  emit('answer', t('chat.suspension.approve'))
}

function handleReject() {
  rejecting.value = true
}

function cancelReject() {
  rejecting.value = false
  rejectReason.value = ''
}

function confirmReject() {
  const reason = rejectReason.value.trim()
  emit('answer', reason ? `[APPROVAL_REJECTED] ${reason}` : '[APPROVAL_REJECTED]')
  cancelReject()
}
</script>

<template>
  <div class="suspension-card" :class="{ 'is-interactive': interactive }">
    <div class="suspension-head">
      <span class="suspension-icon" :class="isApproval ? 'is-approval' : 'is-ask'">
        <SvgIcon :icon="isApproval ? 'ri:shield-check-line' : 'ri:question-answer-line'" />
      </span>
      <strong class="suspension-title">
        {{ isApproval
          ? (kind === 'MCP_APPROVAL' ? t('chat.suspension.mcpApprovalTitle') : t('chat.suspension.approvalTitle'))
          : t('chat.suspension.askTitle') }}
      </strong>
      <span v-if="isApproval && suspension.toolName" class="suspension-tool">{{ suspension.toolName }}</span>
      <span v-if="isApproval && suspension.riskLevel" class="suspension-risk" :class="riskClass">
        {{ t('chat.suspension.riskLevel') }}：{{ suspension.riskLevel }}
      </span>
    </div>
    <div class="suspension-question">
      {{ suspension.question }}
    </div>
    <template v-if="isApproval">
      <div v-if="suspension.action" class="suspension-detail">
        <span class="suspension-detail-label">{{ t('chat.suspension.action') }}</span>{{ suspension.action }}
      </div>
      <div v-if="suspension.summary" class="suspension-detail" :title="suspension.summary">
        <span class="suspension-detail-label">{{ t('chat.suspension.summary') }}</span>{{ suspension.summary }}
      </div>
    </template>
    <template v-if="interactive">
      <!-- ask_user 无选项：引导用户在主输入框自由作答（卡片本身不提供输入） -->
      <div v-if="isAskUser && !options.length" class="suspension-hint">
        {{ t('chat.suspension.freeAnswerHint') }}
      </div>
      <!-- ask_user 选项按钮：提交该选项文本作为普通用户消息 -->
      <div v-if="isAskUser && options.length" class="suspension-options">
        <NButton v-for="option in options" :key="option" size="small" secondary round @click="handleOption(option)">
          {{ option }}
        </NButton>
      </div>
      <!-- 审批卡片：同意/拒绝；拒绝拼接 [APPROVAL_REJECTED] 前缀 + 理由（前缀为后端恢复装配的硬匹配标记，勿改） -->
      <template v-if="isApproval">
        <div v-if="!rejecting" class="suspension-actions">
          <NButton type="primary" size="small" @click="handleApprove">
            {{ t('chat.suspension.approve') }}
          </NButton>
          <NButton type="error" size="small" secondary @click="handleReject">
            {{ t('chat.suspension.reject') }}
          </NButton>
        </div>
        <div v-else class="suspension-reject">
          <NInput
            v-model:value="rejectReason" size="small" type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }" :placeholder="t('chat.suspension.rejectReasonPlaceholder')"
          />
          <div class="suspension-reject-actions">
            <NButton size="tiny" quaternary @click="cancelReject">
              {{ t('common.cancel') }}
            </NButton>
            <NButton type="error" size="tiny" @click="confirmReject">
              {{ t('chat.suspension.rejectConfirm') }}
            </NButton>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped lang="less">
.suspension-card {
  width: min(760px, 100%);
  margin-bottom: 6px;
  padding: 11px 14px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 14px;
  background: var(--zhimesh-glass);
}

:global(.dark) .suspension-card {
  background: var(--zhimesh-glass-strong);
}

.suspension-card.is-interactive {
  border-color: var(--zhimesh-primary);
}

.suspension-head {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}

.suspension-icon {
  display: grid;
  width: 26px;
  height: 26px;
  flex: none;
  place-items: center;
  border-radius: 9px;
  font-size: 15px;
}

.suspension-icon.is-ask {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
}

.suspension-icon.is-approval {
  color: var(--zhimesh-warning-text);
  background: rgba(234, 179, 8, 0.12);
}

.suspension-title {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 700;
}

.suspension-tool {
  padding: 1px 7px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 7px;
  color: var(--zhimesh-text-muted);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.suspension-risk {
  padding: 1px 7px;
  border-radius: 7px;
  font-size: 11px;
  font-weight: 700;
}

.suspension-risk.is-high {
  color: var(--zhimesh-danger-text);
  background: rgba(239, 68, 68, 0.12);
}

.suspension-risk.is-medium {
  color: var(--zhimesh-warning-text);
  background: rgba(234, 179, 8, 0.14);
}

.suspension-risk.is-low {
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
}

.suspension-hint {
  margin-top: 8px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.suspension-question {
  margin-top: 8px;
  color: var(--zhimesh-text);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.suspension-detail {
  margin-top: 5px;
  color: var(--zhimesh-text);
  font-size: 12px;
  line-height: 1.55;
  overflow-wrap: anywhere;
  opacity: 0.85;
}

.suspension-detail-label {
  margin-right: 4px;
  color: var(--zhimesh-text-muted);
}

.suspension-options {
  display: flex;
  margin-top: 10px;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.suspension-actions {
  display: flex;
  margin-top: 10px;
  align-items: center;
  gap: 8px;
}

.suspension-reject {
  margin-top: 10px;
}

.suspension-reject-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 7px;
  gap: 6px;
}
</style>
