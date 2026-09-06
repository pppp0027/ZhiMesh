<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { NButton, NDrawer, NDrawerContent, NSpin, useDialog, useLoadingBar, useMessage } from 'naive-ui'
import RuntimeNodes from './components/RuntimeNodes.vue'
import RunDetail from './components/RunDetail.vue'
import LoginTip from '@/views/user/LoginTip.vue'
import { SvgIcon } from '@/components/common'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAuthStore, useWfStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
import { debounce } from '@/utils/functions/debounce'
import { emptyWorkflowInfo } from '@/utils/functions'
import { formatDuration } from '@/utils/format'
import { openDeleteDialog } from '@/utils/dialog'

interface Props {
  workflow: Workflow.WorkflowInfo
  show: boolean
}

const props = withDefaults(defineProps<Props>(), {
  workflow: () => emptyWorkflowInfo(),
})

const dialog = useDialog()
const ms = useMessage()
const wfStore = useWfStore()
const authStore = useAuthStore()
const loadingBar = useLoadingBar()
const { isMobile } = useBasicLayout()
const pageSize = 20

const showRunDrawer = ref(false)
const showDetailDrawer = ref(false)
const selectedRuntime = ref<Workflow.WorkflowRuntime>()
const detailLoading = ref(false)
const detailLoadError = ref('')
let activeRefreshTimer: ReturnType<typeof setInterval> | undefined

const currWfUuid = computed(() => props.workflow.uuid)
const wfRuntimes = computed(() => wfStore.getWfRuntimes(currWfUuid.value))
const displayRuntimes = computed(() => wfRuntimes.value)
const pageMeta = computed<Workflow.WfRuntimePageMeta>(() => wfStore.getWfRuntimePageMeta(currWfUuid.value) || {
  total: wfRuntimes.value.length,
  nextPage: 1,
  loadedAll: false,
  loading: false,
  error: '',
  loadedAt: 0,
})
const loading = computed(() => pageMeta.value.loading)
const detailNodes = computed(() => selectedRuntime.value?.nodes || [])
const hasActiveRuns = computed(() => wfRuntimes.value.some(runtime => [1, 2, 5, 6].includes(runtime.status)))

function runtimeState(runtime: Workflow.WorkflowRuntime) {
  if (runtime.status === 3)
    return { label: t('workflow.runtimeSuccess'), className: 'is-success', icon: 'ri:check-line' }
  if (runtime.status === 4)
    return { label: t('workflow.runtimeFailed'), className: 'is-failed', icon: 'ri:close-line' }
  if (runtime.status === 5)
    return { label: t('workflow.runtimeWaitingInput'), className: 'is-waiting', icon: 'ri:question-answer-line' }
  if (runtime.status === 6)
    return { label: t('workflow.runtimeCancelling'), className: 'is-cancelling', icon: 'line-md:loading-twotone-loop' }
  if (runtime.status === 7)
    return { label: t('workflow.runtimeCancelled'), className: 'is-cancelled', icon: 'ri:stop-circle-line' }
  if (runtime.loading || runtime.status === 2)
    return { label: t('workflow.runtimeRunning'), className: 'is-running', icon: 'line-md:loading-twotone-loop' }
  return { label: t('workflow.runtimePending'), className: 'is-pending', icon: 'ri:time-line' }
}

function valuePreview(value: unknown) {
  if (value === null || value === undefined || value === '')
    return t('common.noContent')
  if (Array.isArray(value))
    return value.join(', ')
  if (typeof value === 'object')
    return JSON.stringify(value)
  return String(value)
}

function ioPreview(ioObject: Record<string, Workflow.NodeIOData> | undefined) {
  if (!ioObject || Object.keys(ioObject).length === 0)
    return t('common.noContent')
  return Object.entries(ioObject)
    .map(([name, content]) => `${name}: ${valuePreview(content?.value)}`)
    .join(' · ')
}

function errorMessage(error: unknown, fallback: string) {
  return (error instanceof Error && error.message) ? error.message : fallback
}

async function loadRuntimePage(page: number, reset: boolean, silent = false) {
  if (loading.value || !currWfUuid.value || currWfUuid.value === 'default')
    return
  if (!silent)
    loadingBar.start()
  wfStore.setLoadingRuntimes(currWfUuid.value, true)
  wfStore.setWfRuntimePageError(currWfUuid.value, '')
  try {
    const { data } = await api.workflowRuntimes<Workflow.WfRuntimesResp>(currWfUuid.value, page, pageSize)
    if (reset)
      wfStore.replaceWfRuntimePage(currWfUuid.value, data.records || [], data.total || 0)
    else
      wfStore.appendWfRuntimePage(currWfUuid.value, data.records || [], data.total || 0)
  } catch (error) {
    wfStore.setWfRuntimePageError(currWfUuid.value, errorMessage(error, t('workflow.loadRunHistoryFailed')))
  } finally {
    wfStore.setLoadingRuntimes(currWfUuid.value, false)
    if (!silent)
      loadingBar.finish()
  }
}

async function loadMoreRuntimes() {
  if (pageMeta.value.loadedAll)
    return
  await loadRuntimePage(pageMeta.value.nextPage, false)
}

const handleLoadMoreRuntimes = debounce(loadMoreRuntimes, 300)

function handleScroll(event: Event) {
  const target = event.target as HTMLElement
  if (target.scrollHeight - target.scrollTop - target.clientHeight < 120)
    handleLoadMoreRuntimes()
}

function canDelete(runtime: Workflow.WorkflowRuntime) {
  return [3, 4, 7].includes(runtime.status)
}

function handleDelete(runtime: Workflow.WorkflowRuntime) {
  if (loading.value || !canDelete(runtime))
    return
  openDeleteDialog(dialog, {
    title: t('common.delete'),
    content: t('workflow.inputAndOutputDeleteConfirm'),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.workflowRuntimeDelete(runtime.uuid)
        wfStore.deleteWfRuntime(currWfUuid.value, runtime.uuid)
        if (selectedRuntime.value?.uuid === runtime.uuid)
          showDetailDrawer.value = false
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

async function refreshFirstPage(force = false) {
  if (!authStore.token || !props.show)
    return
  const stale = Date.now() - pageMeta.value.loadedAt > 15_000
  if (force || stale || pageMeta.value.nextPage === 1)
    await loadRuntimePage(1, true, force)
}

async function showRuntimeDetail(runtime: Workflow.WorkflowRuntime) {
  selectedRuntime.value = runtime
  showDetailDrawer.value = true
  detailLoading.value = true
  detailLoadError.value = ''
  wfStore.setWfRuntimeDetailState(runtime.uuid, true)

  const requests: Promise<unknown>[] = []
  if (!runtime.detailLoaded) {
    requests.push(api.workflowRuntimeDetail<Workflow.WorkflowRuntime>(runtime.uuid)
      .then(({ data }) => wfStore.setWfRuntimeDetail(runtime.uuid, data)))
  }
  requests.push(api.workflowRuntimeNodes<Workflow.WfRuntimeNode[]>(runtime.uuid)
    .then(({ data }) => wfStore.setWfRuntimeNodes(runtime.uuid, data)))

  const results = await Promise.allSettled(requests)
  const failed = results.find(result => result.status === 'rejected') as PromiseRejectedResult | undefined
  if (failed) {
    detailLoadError.value = errorMessage(failed.reason, t('workflow.loadRunDetailFailed'))
    wfStore.setWfRuntimeDetailState(runtime.uuid, false, detailLoadError.value)
  } else {
    wfStore.setWfRuntimeDetailState(runtime.uuid, false)
  }
  detailLoading.value = false
}

async function loadNodeDetail(node: Workflow.WfRuntimeNode) {
  if (!selectedRuntime.value || node.detailLoading)
    return
  wfStore.setWfRuntimeNodeDetailState(selectedRuntime.value.uuid, node.uuid, true)
  try {
    const { data } = await api.workflowRuntimeNodeDetail<Workflow.WfRuntimeNode>(selectedRuntime.value.uuid, node.uuid)
    wfStore.setWfRuntimeNodeDetail(selectedRuntime.value.uuid, data)
  } catch (error) {
    wfStore.setWfRuntimeNodeDetailState(
      selectedRuntime.value.uuid,
      node.uuid,
      false,
      errorMessage(error, t('workflow.loadNodeDetailFailed')),
    )
  }
}

function runDone() {
  refreshFirstPage(true)
}

function runError() {
  refreshFirstPage(true)
}

function configureActiveRefresh() {
  if (activeRefreshTimer)
    clearInterval(activeRefreshTimer)
  activeRefreshTimer = undefined
  if (!props.show || !hasActiveRuns.value)
    return
  activeRefreshTimer = setInterval(() => {
    if (document.visibilityState === 'visible')
      loadRuntimePage(1, true, true)
  }, 8_000)
}

watch(
  [() => authStore.token, currWfUuid, () => props.show],
  ([token, , show]) => {
    if (token && show)
      refreshFirstPage()
  },
  { immediate: true },
)
watch([() => props.show, hasActiveRuns], configureActiveRefresh, { immediate: true })

onUnmounted(() => {
  if (activeRefreshTimer)
    clearInterval(activeRefreshTimer)
})
</script>

<template>
  <main v-show="show" class="workflow-runtime">
    <div class="workflow-runtime__scroll" @scroll="handleScroll">
      <div class="workflow-runtime__page">
        <header class="workflow-runtime__header">
          <div class="workflow-runtime__heading">
            <span class="workflow-runtime__heading-icon">
              <SvgIcon icon="ri:history-line" />
            </span>
            <div>
              <h2>{{ t('workflow.runHistoryTitle') }}</h2>
              <p v-if="wfRuntimes.length">
                {{ t('workflow.runHistoryHint') }}
              </p>
            </div>
          </div>
          <div class="workflow-runtime__header-actions">
            <span class="workflow-runtime__count">{{ pageMeta.total }} {{ t('workflow.runCount') }}</span>
            <NButton type="primary" size="small" @click="showRunDrawer = true">
              <template #icon>
                <SvgIcon icon="carbon:play-outline" />
              </template>
              {{ t('workflow.runNow') }}
            </NButton>
          </div>
        </header>

        <LoginTip v-if="!authStore.token" />

        <section v-else class="workflow-runtime__panel">
          <div v-if="pageMeta.error && !wfRuntimes.length" class="workflow-runtime__error" role="alert">
            <SvgIcon icon="ri:error-warning-line" />
            <p>{{ pageMeta.error }}</p>
            <NButton size="small" secondary @click="refreshFirstPage(true)">
              {{ t('common.retry') }}
            </NButton>
          </div>

          <div v-else-if="loading && !wfRuntimes.length" class="workflow-runtime__loading">
            <NSpin size="small" />
          </div>

          <div v-else-if="!wfRuntimes.length" class="workflow-runtime__empty">
            <span class="workflow-runtime__empty-icon"><SvgIcon icon="carbon:flow-data" /></span>
            <h3>{{ t('workflow.noRunHistory') }}</h3>
            <p>{{ t('workflow.noRunHistoryHint') }}</p>
            <NButton type="primary" secondary @click="showRunDrawer = true">
              <template #icon>
                <SvgIcon icon="carbon:play-outline" />
              </template>
              {{ t('workflow.runNow') }}
            </NButton>
          </div>

          <div v-else class="workflow-runtime__list">
            <div v-if="pageMeta.error" class="workflow-runtime__inline-error" role="alert">
              <span>{{ pageMeta.error }}</span>
              <NButton text size="small" @click="refreshFirstPage(true)">
                {{ t('common.retry') }}
              </NButton>
            </div>
            <article
              v-for="runtime in displayRuntimes"
              :key="runtime.uuid"
              class="workflow-runtime-card"
              role="button"
              tabindex="0"
              :aria-label="`${t('workflow.runDetail')} #${runtime.uuid.slice(0, 8)}`"
              @click="showRuntimeDetail(runtime)"
              @keydown.enter="showRuntimeDetail(runtime)"
              @keydown.space.prevent="showRuntimeDetail(runtime)"
            >
              <span class="workflow-runtime-card__status-icon" :class="runtimeState(runtime).className">
                <SvgIcon :icon="runtimeState(runtime).icon" />
              </span>

              <div class="workflow-runtime-card__body">
                <div class="workflow-runtime-card__title-row">
                  <strong>{{ t('workflow.execution') }} #{{ runtime.uuid.slice(0, 8) }}</strong>
                  <span>{{ runtime.createTime }}</span>
                </div>
                <div v-if="runtime.statusRemark" class="workflow-runtime-card__preview">
                  <span>{{ t('workflow.statusRemark') }}</span>
                  <p>{{ runtime.statusRemark }}</p>
                </div>
                <div class="workflow-runtime-card__metrics">
                  <span v-if="runtime.duration != null">{{ formatDuration(runtime.duration) }}</span>
                  <span v-if="runtime.inputTokens != null">{{ t('workflow.metricInputTokens', { count: runtime.inputTokens }) }}</span>
                  <span v-if="runtime.outputTokens != null">{{ t('workflow.metricOutputTokens', { count: runtime.outputTokens }) }}</span>
                </div>
              </div>

              <div class="workflow-runtime-card__actions">
                <span class="workflow-runtime-card__status" :class="runtimeState(runtime).className">
                  {{ runtimeState(runtime).label }}
                </span>
                <NButton
                  v-if="canDelete(runtime)"
                  quaternary circle size="small"
                  :title="t('common.delete')" :aria-label="t('workflow.deleteRunRecord')"
                  @click.stop="handleDelete(runtime)"
                >
                  <template #icon>
                    <SvgIcon icon="ri:delete-bin-line" />
                  </template>
                </NButton>
                <SvgIcon class="workflow-runtime-card__arrow" icon="ri:arrow-right-s-line" />
              </div>
            </article>

            <div v-if="!pageMeta.loadedAll" class="workflow-runtime__load-more">
              <NButton text :loading="loading" @click="handleLoadMoreRuntimes">
                {{ t('workflow.loadMoreRuns') }}
              </NButton>
            </div>
          </div>
        </section>
      </div>
    </div>
  </main>

  <NDrawer
    v-model:show="showRunDrawer"
    placement="right"
    :width="isMobile ? '100%' : 520"
  >
    <NDrawerContent :title="t('workflow.runInputTitle')" closable>
      <RunDetail
        :workflow="workflow" :show-header="false"
        @run-done="runDone" @run-error="runError" @run-cancelled="runError"
      />
    </NDrawerContent>
  </NDrawer>

  <NDrawer
    v-model:show="showDetailDrawer"
    placement="right"
    :width="isMobile ? '100%' : 560"
  >
    <NDrawerContent :title="t('workflow.runDetail')" closable>
      <div v-if="selectedRuntime" class="workflow-runtime-detail__summary">
        <div>
          <span>{{ t('workflow.execution') }}</span>
          <strong>#{{ selectedRuntime.uuid.slice(0, 8) }}</strong>
        </div>
        <span class="workflow-runtime-card__status" :class="runtimeState(selectedRuntime).className">
          {{ runtimeState(selectedRuntime).label }}
        </span>
      </div>
      <div v-if="detailLoading" class="workflow-runtime__loading workflow-runtime-detail__loading">
        <NSpin size="small" />
      </div>
      <div v-else-if="detailLoadError" class="workflow-runtime__error workflow-runtime-detail__error" role="alert">
        <p>{{ detailLoadError }}</p>
        <NButton size="small" secondary @click="selectedRuntime && showRuntimeDetail(selectedRuntime)">
          {{ t('common.retry') }}
        </NButton>
      </div>
      <div v-else-if="selectedRuntime?.detailLoaded" class="workflow-runtime-detail__io-summary">
        <div>
          <span>{{ t('common.input') }}</span>
          <p>{{ ioPreview(selectedRuntime.input) }}</p>
        </div>
        <div>
          <span>{{ t('common.output') }}</span>
          <p>{{ ioPreview(selectedRuntime.output) }}</p>
        </div>
      </div>
      <RuntimeNodes
        v-if="!detailLoading"
        :workflow="workflow"
        :nodes="detailNodes"
        :error-msg="selectedRuntime?.status === 4 ? selectedRuntime.statusRemark : ''"
        class="workflow-runtime-detail__nodes"
        @load-detail="loadNodeDetail"
      />
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.workflow-runtime {
  min-height: 0;
  flex: 1;
  overflow: hidden;
  background: var(--zhimesh-page-bg);
}

.workflow-runtime__scroll {
  height: 100%;
  overflow-y: auto;
}

.workflow-runtime__page {
  width: min(1120px, calc(100% - 40px));
  min-height: 100%;
  margin: 0 auto;
  padding: 24px 0 40px;
}

.workflow-runtime__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.workflow-runtime__heading,
.workflow-runtime__header-actions,
.workflow-runtime-card__title-row,
.workflow-runtime-card__actions,
.workflow-runtime-detail__summary {
  display: flex;
  align-items: center;
}

.workflow-runtime__heading {
  gap: 11px;
}

.workflow-runtime__heading-icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  color: var(--zhimesh-primary);
  border: 1px solid var(--zhimesh-border);
  border-radius: 11px;
  background: var(--zhimesh-glass-soft);
  font-size: 18px;
}

.workflow-runtime__heading h2 {
  margin: 0;
  color: var(--zhimesh-text);
  font-size: 18px;
  font-weight: 700;
}

.workflow-runtime__heading p {
  margin: 3px 0 0;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.workflow-runtime__header-actions {
  gap: 10px;
}

.workflow-runtime__count {
  padding: 5px 9px;
  color: var(--zhimesh-text-muted);
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 999px;
  background: var(--zhimesh-glass);
  font-size: 11px;
}

.workflow-runtime__panel {
  min-height: 420px;
  padding: 8px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 16px;
  background: var(--zhimesh-glass-nav);
  box-shadow: 0 8px 30px rgba(15, 23, 42, 0.04);
}

.workflow-runtime__loading,
.workflow-runtime__empty,
.workflow-runtime__error {
  display: flex;
  min-height: 400px;
  align-items: center;
  justify-content: center;
}

.workflow-runtime__error {
  flex-direction: column;
  gap: 10px;
  padding: 32px;
  color: var(--zhimesh-danger-text);
  text-align: center;
}

.workflow-runtime__error > svg {
  font-size: 24px;
}

.workflow-runtime__error p {
  max-width: 52ch;
  margin: 0;
  overflow-wrap: anywhere;
}

.workflow-runtime__empty {
  flex-direction: column;
  padding: 40px;
  text-align: center;
}

.workflow-runtime__empty-icon {
  display: grid;
  width: 54px;
  height: 54px;
  margin-bottom: 14px;
  place-items: center;
  color: var(--zhimesh-text-muted);
  border-radius: 16px;
  background: var(--zhimesh-glass-soft);
  font-size: 24px;
}

.workflow-runtime__empty h3 {
  margin: 0;
  color: var(--zhimesh-text);
  font-size: 16px;
}

.workflow-runtime__empty p {
  max-width: 420px;
  margin: 7px 0 18px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  line-height: 1.7;
}

.workflow-runtime__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.workflow-runtime__inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 11px;
  color: var(--zhimesh-danger-text);
  border-radius: 9px;
  background: var(--zhimesh-danger-surface);
  font-size: 12px;
}

.workflow-runtime-card {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  padding: 14px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: var(--zhimesh-glass);
  cursor: pointer;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.workflow-runtime-card:hover,
.workflow-runtime-card:focus-visible {
  border-color: var(--zhimesh-border);
  box-shadow: 0 7px 20px rgba(15, 23, 42, 0.07);
  outline: none;
  transform: translateY(-1px);
}

.workflow-runtime-card__status-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  font-size: 16px;
}

.workflow-runtime-card__title-row {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 9px;
}

.workflow-runtime-card__title-row strong {
  color: var(--zhimesh-text);
  font-size: 13px;
}

.workflow-runtime-card__title-row > span {
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.workflow-runtime-card__preview {
  display: grid;
  min-width: 0;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px;
  margin-top: 4px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.workflow-runtime-card__preview > span {
  color: var(--zhimesh-text-muted);
}

.workflow-runtime-card__preview p {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-runtime-card__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}

.workflow-runtime-card__metrics span {
  padding: 2px 6px;
  color: var(--zhimesh-text-muted);
  border-radius: 5px;
  background: var(--zhimesh-glass-soft);
  font-size: 10px;
}

.workflow-runtime-card__actions {
  align-self: center;
  gap: 5px;
}

.workflow-runtime-card__status {
  display: inline-flex;
  padding: 3px 8px;
  align-items: center;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
}

.is-success {
  color: var(--zhimesh-success-text);
  background: var(--zhimesh-success-surface);
}

.is-failed {
  color: var(--zhimesh-danger-text);
  background: var(--zhimesh-danger-surface);
}

.is-running {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.is-waiting {
  color: var(--zhimesh-warning-text);
  background: var(--zhimesh-warning-surface);
}

.is-cancelling,
.is-cancelled {
  color: var(--zhimesh-warning-text);
  background: var(--zhimesh-warning-surface);
}

.is-pending {
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
}

.workflow-runtime-card__arrow {
  color: var(--zhimesh-text-muted);
  font-size: 17px;
}

.workflow-runtime__load-more {
  display: flex;
  padding: 10px;
  justify-content: center;
}

.workflow-runtime-detail__summary {
  justify-content: space-between;
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 11px;
  background: var(--zhimesh-glass-soft);
}

.workflow-runtime-detail__summary > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.workflow-runtime-detail__summary > div > span {
  color: var(--zhimesh-text-muted);
  font-size: 10px;
}

.workflow-runtime-detail__summary strong {
  color: var(--zhimesh-text);
  font-size: 13px;
}

.workflow-runtime-detail__io-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
  margin-bottom: 12px;
}

.workflow-runtime-detail__io-summary > div {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
}

.workflow-runtime-detail__io-summary span {
  color: var(--zhimesh-text-muted);
  font-size: 10px;
  font-weight: 650;
}

.workflow-runtime-detail__io-summary p {
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 11px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-runtime-detail__nodes {
  max-height: calc(100vh - 150px);
  overflow-y: auto;
}

.workflow-runtime-detail__loading {
  min-height: 220px;
}

@media (max-width: 767px) {
  .workflow-runtime__page {
    width: calc(100% - 20px);
    padding-top: 14px;
  }

  .workflow-runtime__header {
    align-items: flex-start;
  }

  .workflow-runtime__count {
    display: none;
  }

  .workflow-runtime-detail__io-summary {
    grid-template-columns: minmax(0, 1fr);
  }

  .workflow-runtime-card {
    grid-template-columns: 30px minmax(0, 1fr);
  }

  .workflow-runtime-card__actions {
    grid-column: 2;
    justify-content: flex-end;
  }

  .workflow-runtime-card__title-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
}
</style>
