<script lang="ts" setup>
import { ref, watch } from 'vue'
import { NButton, NCollapse, NCollapseItem, NImage, NImageGroup, NSpin } from 'naive-ui'
import { SvgIcon } from '@/components/common'
import { getIconByComponentName, getIconClassByComponentName } from '@/utils/workflow-util'
import { useAuthStore } from '@/store'
import { formatDuration } from '@/utils/format'
import { t } from '@/locales'
import { getRealFileUrl } from '@/utils/functions'
import TextComponent from '@/views/chat/components/Message/Text.vue'

interface Props {
  nodes: Workflow.WfRuntimeNode[]
  workflow: Workflow.WorkflowInfo
  errorMsg: string
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'loadDetail', node: Workflow.WfRuntimeNode): void
}>()
const authStore = useAuthStore()
const expandedNodeUuids = ref<string[]>([])

function runtimeState(node: Workflow.WfRuntimeNode) {
  if (node.status === 4)
    return { label: t('workflow.runtimeFailed'), className: 'is-failed' }
  if (node.status === 3)
    return { label: t('workflow.runtimeSuccess'), className: 'is-success' }
  if (node.status === 2)
    return { label: t('workflow.runtimeRunning'), className: 'is-running' }
  if (node.status === 5)
    return { label: t('workflow.runtimeCancelled'), className: 'is-cancelled' }
  return { label: t('workflow.runtimePending'), className: 'is-pending' }
}

function valuePreview(value: unknown) {
  if (value === null || value === undefined || value === '')
    return t('common.noContent')
  if (Array.isArray(value))
    return value.join(', ')
  if (typeof value === 'object')
    return JSON.stringify(value, null, 2)
  return String(value)
}

// Keep the active/latest node readable as the stream advances. Previous nodes
// collapse into a compact timeline, exposing the entire execution at a glance.
watch(
  () => props.nodes.length,
  () => {
    const focusNode = props.nodes.find(node => node.status === 4) || props.nodes.at(-1)
    if (focusNode && !expandedNodeUuids.value.includes(focusNode.uuid))
      expandedNodeUuids.value = [...expandedNodeUuids.value, focusNode.uuid]
  },
  { immediate: true },
)

watch(
  expandedNodeUuids,
  (uuids) => {
    uuids.forEach((uuid) => {
      const node = props.nodes.find(item => item.uuid === uuid)
      if (node && !node.detailLoaded && !node.detailLoading)
        emit('loadDetail', node)
    })
  },
  { deep: true, immediate: true },
)
</script>

<template>
  <div class="runtime-node-list">
    <div v-if="errorMsg" class="py-2 text-red-500">
      {{ t('workflow.errorLabel') }}{{ errorMsg }}
    </div>
    <div v-if="nodes.length === 0" class="text-center py-2 text-neutral-400">
      {{ t('common.noContent') }}
    </div>
    <NCollapse v-if="nodes.length" v-model:expanded-names="expandedNodeUuids" arrow-placement="right" class="runtime-node-list__timeline">
      <NCollapseItem v-for="(node, index) in nodes" :key="node.uuid" :name="node.uuid" :title="node.nodeTitle || t('workflow.nodeTitleNotFound')">
        <template #header>
          <div class="runtime-node-card__header" :title="node.nodeTitle">
            <span class="runtime-node-card__index">{{ index + 1 }}</span>
            <SvgIcon
              v-if="node.wfComponent" class="runtime-node-card__icon" :class="getIconClassByComponentName(node.wfComponent.name)"
              :icon="getIconByComponentName(node.wfComponent.name)"
            />
            <span class="runtime-node-card__title">{{ node.nodeTitle || t('workflow.nodeTitleNotFound') }}</span>
            <span class="runtime-node-card__status" :class="runtimeState(node).className">{{ runtimeState(node).label }}</span>
          </div>
        </template>
        <div class="runtime-node-card">
          <div v-if="node.status === 4 && node.statusRemark" class="runtime-node-card__error">
            {{ node.statusRemark.replace(/^process error:/, '') }}
          </div>
          <div v-if="node.duration != null || node.metadata" class="flex flex-wrap gap-1.5 px-1 pb-2 text-xs text-gray-500">
            <span v-if="node.duration != null" class="metric-chip">
              ⏱ {{ formatDuration(node.duration) }}
            </span>
            <!-- LLM / Agent: token + model -->
            <template v-if="node.metadata?.type === 'llm' || node.metadata?.type === 'agent'">
              <span v-if="node.metadata.inputTokens != null" class="metric-chip">
                {{ t('workflow.metricInputTokens', { count: node.metadata.inputTokens }) }}
              </span>
              <span v-if="node.metadata.outputTokens != null" class="metric-chip">
                {{ t('workflow.metricOutputTokens', { count: node.metadata.outputTokens }) }}
              </span>
              <span v-if="node.metadata.modelName" class="metric-chip">
                {{ t('workflow.metricModel', { model: node.metadata.modelName }) }}
              </span>
            </template>
            <!-- Agent: extra RAG retrieval count -->
            <template v-if="node.metadata?.type === 'agent'">
              <span v-if="node.metadata.retrievalCount != null" class="metric-chip">
                {{ t('workflow.metricChunks', { count: node.metadata.retrievalCount }) }}
              </span>
            </template>
            <!-- HTTP request -->
            <template v-if="node.metadata?.type === 'http_request'">
              <span
                v-if="node.metadata.httpStatusCode"
                class="px-1.5 py-0.5 rounded"
                :class="node.metadata.httpStatusCode === 200 ? 'runtime-http-status is-ok' : 'runtime-http-status is-error'"
              >
                HTTP {{ node.metadata.httpStatusCode }}
              </span>
            </template>
            <!-- Search -->
            <template v-if="node.metadata?.type === 'search'">
              <span v-if="node.metadata.searchResultCount != null" class="metric-chip">
                {{ t('workflow.metricResults', { count: node.metadata.searchResultCount }) }}
              </span>
            </template>
            <!-- Knowledge retrieval -->
            <template v-if="node.metadata?.type === 'knowledge_retrieval'">
              <span v-if="node.metadata.retrievalCount != null" class="metric-chip">
                {{ t('workflow.metricChunks', { count: node.metadata.retrievalCount }) }}
              </span>
            </template>
            <!-- Image generation -->
            <template v-if="node.metadata?.type === 'image'">
              <span v-if="node.metadata.imageModelName" class="metric-chip">
                {{ t('workflow.metricImageModel', { model: node.metadata.imageModelName }) }}
              </span>
              <span v-if="node.metadata.imageSize" class="metric-chip">
                {{ t('workflow.metricImageSize', { size: node.metadata.imageSize }) }}
              </span>
            </template>
            <!-- Mail send -->
            <template v-if="node.metadata?.type === 'mail'">
              <span v-if="node.metadata.sendSuccess === true" class="runtime-success-chip px-1.5 py-0.5 rounded">
                {{ t('workflow.mailSubmitted', { count: node.metadata.recipientCount }) }}
              </span>
            </template>
            <!-- Document extractor -->
            <template v-if="node.metadata?.type === 'document'">
              <span v-if="node.metadata.fileCount != null" class="metric-chip">
                {{ t('workflow.metricFiles', { count: node.metadata.fileCount }) }}
              </span>
              <span v-if="node.metadata.extractedCharCount != null" class="metric-chip">
                {{ t('workflow.metricCharacters', { count: node.metadata.extractedCharCount }) }}
              </span>
            </template>
          </div>
          <div v-if="node.detailLoading" class="runtime-node-card__detail-state" role="status">
            <NSpin size="small" />
            <span>{{ t('workflow.loadingNodeDetail') }}</span>
          </div>
          <div v-else-if="node.detailError" class="runtime-node-card__detail-state is-error" role="alert">
            <span>{{ node.detailError }}</span>
            <NButton size="small" secondary @click="emit('loadDetail', node)">
              {{ t('common.retry') }}
            </NButton>
          </div>
          <div v-else class="runtime-node-card__io">
            <div class="text-base border-b border-gray-200 py-1">
              {{ t('common.input') }}
            </div>
            <div v-for="(content, name) in node.input" :key="`input_${name}`" class="flex">
              <div class="min-w-24 pr-2">
                {{ name }}
              </div>
              <div>
                {{ valuePreview(content.value) }}
              </div>
            </div>
            <div v-if="Object.keys(node.input || {}).length === 0" class="runtime-node-card__empty-value">
              {{ t('common.noContent') }}
            </div>
            <div class="text-base border-b border-gray-200 py-1">
              {{ t('common.output') }}
            </div>
            <div v-for="(content, name) in node.output" :key="`onput_${name}`" class="flex">
              <template v-if="content.type === 4">
                <NImageGroup>
                  <NImage
                    v-for="url in content.value" :key="url" :src="`${getRealFileUrl(url)}?token=${authStore.token}`"
                    width="100" :alt="`${node.nodeTitle || t('workflow.nodeTitleNotFound')} - ${String(name)}`"
                  />
                </NImageGroup>
              </template>
              <template v-else>
                <div class="min-w-24 pr-2">
                  {{ name }}
                </div>
                <div>
                  <TextComponent :inversion="false" :text="valuePreview(content.value)" :as-raw-text="false" />
                </div>
              </template>
            </div>
            <div v-if="Object.keys(node.output || {}).length === 0" class="runtime-node-card__empty-value">
              {{ t('common.noContent') }}
            </div>
          </div>
        </div>
      </NCollapseItem>
    </NCollapse>
  </div>
</template>

<style scoped>
/* Compact info chip used to display per-node observability metrics (tokens, model, counts...). */
.metric-chip {
  background-color: var(--zhimesh-glass-soft);
  padding: 0.125rem 0.375rem;          /* tailwind py-0.5 px-1.5 */
  border-radius: 0.25rem;              /* tailwind rounded */
}

.runtime-node-card {
  padding: 8px 10px 10px;
}

.runtime-node-list__timeline {
  overflow: hidden;
  border: 1px solid var(--zhimesh-border);
  border-radius: 10px;
  background: var(--zhimesh-glass);
}

.runtime-node-list__timeline :deep(.n-collapse-item) {
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.runtime-node-list__timeline :deep(.n-collapse-item:last-child) {
  border-bottom: 0;
}

.runtime-node-list__timeline :deep(.n-collapse-item__header) {
  min-height: 48px;
  padding: 0 12px;
}

.runtime-node-list__timeline :deep(.n-collapse-item__content-wrapper) {
  background: var(--zhimesh-glass-soft);
}

.runtime-node-card__header {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 8px;
}

.runtime-node-card__index {
  display: grid;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  place-items: center;
  color: var(--zhimesh-text-muted);
  border-radius: 999px;
  background: var(--zhimesh-info-surface);
  font-size: 11px;
  font-weight: 700;
}

.runtime-node-card__icon {
  flex: 0 0 auto;
  font-size: 16px;
}

.runtime-node-card__title {
  min-width: 0;
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-node-card__status {
  margin-inline-start: auto;
  padding: 3px 7px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 650;
}

.runtime-node-card__status.is-running {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.runtime-node-card__status.is-pending {
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
}

.runtime-node-card__status.is-success {
  color: var(--zhimesh-success-text);
  background: var(--zhimesh-success-surface);
}

.runtime-node-card__status.is-failed {
  color: var(--zhimesh-danger-text);
  background: var(--zhimesh-danger-surface);
}

.runtime-node-card__status.is-cancelled {
  color: var(--zhimesh-warning-text);
  background: var(--zhimesh-warning-surface);
}

.runtime-node-card__io {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
}

.runtime-node-card__detail-state {
  display: flex;
  min-height: 72px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.runtime-node-card__detail-state.is-error {
  flex-direction: column;
  color: var(--zhimesh-danger-text);
  text-align: center;
}

.runtime-node-card__empty-value {
  color: var(--zhimesh-text-muted);
}

.runtime-node-card__error {
  margin-bottom: 8px;
  padding: 7px 9px;
  color: var(--zhimesh-danger-text);
  border: 1px solid var(--zhimesh-danger-text);
  border-radius: 7px;
  background: var(--zhimesh-danger-surface);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.runtime-http-status,
.runtime-success-chip {
  border-radius: 5px;
  font-weight: 600;
}

.runtime-http-status.is-ok,
.runtime-success-chip {
  color: var(--zhimesh-success-text);
  background: var(--zhimesh-success-surface);
}

.runtime-http-status.is-error {
  color: var(--zhimesh-danger-text);
  background: var(--zhimesh-danger-surface);
}

.runtime-node-card__io > div:not(.text-base) {
  min-width: 0;
  overflow-wrap: anywhere;
}

.runtime-node-card :deep(.markdown-body) {
  font-size: 12px;
}
</style>
