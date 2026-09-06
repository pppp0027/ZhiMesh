<template>
  <n-card :bordered="false" class="proCard">
    <BasicForm @register="register" @submit="handleSubmit" @reset="handleReset" />

    <div v-if="latestSyncRun" class="sync-status-bar" role="status" aria-live="polite">
      <div class="sync-status-copy">
        <div class="sync-status-title">
          <strong>{{ t('model.openRouterLastSync') }}</strong>
          <n-tag size="small" :bordered="false" :type="syncStatusType">
            {{ syncStatusText }}
          </n-tag>
        </div>
        <span>{{ syncRunSummary }}</span>
      </div>
      <time :datetime="latestSyncRun.completedAt || latestSyncRun.startedAt">
        {{ latestSyncRun.completedAt || latestSyncRun.startedAt }}
      </time>
    </div>

    <BasicTable
      :columns="columns"
      :request="loadDataTable"
      :row-key="(row: AiModelData) => row.id"
      ref="actionRef"
      :actionColumn="actionColumn"
      @update:checked-row-keys="onCheckedRow"
      :scroll-x="1180"
    >
      <template #tableTitle>
        <n-button type="primary" @click="addTable">
          <template #icon
            ><n-icon><PlusOutlined /></n-icon
          ></template>
          {{ t('common.create') }}
        </n-button>
        <n-button
          class="ml-2"
          @click="confirmOpenRouterSync"
          :loading="syncLoading"
          :disabled="syncLoading"
        >
          <template #icon
            ><n-icon><SyncOutlined /></n-icon
          ></template>
          {{ t('model.openRouterSyncNow') }}
        </n-button>
      </template>
    </BasicTable>

    <n-modal
      v-model:show="showEditModal"
      :show-icon="false"
      preset="dialog"
      :title="editFormParams.label"
      class="ai-config-modal"
      style="width: min(94vw, 800px)"
    >
      <n-form
        :model="editFormParams"
        :rules="newRecordRules"
        ref="formRef"
        label-placement="top"
        class="ai-config-form model-config-form"
        style="overflow-y: auto; overflow-x: hidden; max-height: min(72vh, 740px); width: 100%"
      >
        <div class="form-intro">
          <strong>{{ t('model.modelCreateIntroTitle') }}</strong>
          <span>{{ t('model.modelCreateIntro') }}</span>
        </div>

        <section class="config-section">
          <header class="config-section-header">
            <h3>{{ t('model.modelBasicSection') }}</h3>
            <p>{{ t('model.modelBasicDescription') }}</p>
          </header>
          <div class="form-grid">
            <n-form-item :label="t('model.modelDisplayName')" path="title">
              <n-input
                :placeholder="t('model.modelDisplayNamePlaceholder')"
                v-model:value="editFormParams.title"
              />
              <p class="field-description">{{ t('model.modelDisplayNameFeedback') }}</p>
            </n-form-item>
            <n-form-item :label="t('model.modelId')" path="name">
              <n-input
                :placeholder="t('model.modelIdPlaceholder')"
                v-model:value="editFormParams.name"
              />
              <p class="field-description">{{ t('model.modelIdFeedback') }}</p>
            </n-form-item>
            <n-form-item :label="t('model.platform')" path="platform">
              <n-select
                :placeholder="t('model.platformPlaceholder')"
                :options="allPlatforms"
                v-model:value="editFormParams.platform"
              />
              <p class="field-description">{{ t('model.platformFeedback') }}</p>
            </n-form-item>
            <n-form-item :label="t('model.type')" path="type">
              <n-select
                :placeholder="t('model.typePlaceholder')"
                :options="MODEL_TYPES"
                v-model:value="editFormParams.type"
              />
              <p class="field-description">{{ t('model.modelTypeFeedback') }}</p>
            </n-form-item>
          </div>
        </section>

        <section class="config-section">
          <header class="config-section-header">
            <h3>{{ t('model.modelCapabilitySection') }}</h3>
            <p>{{ t('model.modelCapabilityDescription') }}</p>
          </header>
          <div class="form-grid">
            <n-form-item :label="t('model.inputType')" path="inputTypeList">
              <n-select
                multiple
                :placeholder="t('model.inputTypePlaceholder')"
                :options="MODEL_INPUT_TYPES"
                v-model:value="editFormParams.inputTypeList"
              />
              <p class="field-description">{{ t('model.inputTypeFeedback') }}</p>
            </n-form-item>
            <n-form-item
              v-if="editFormParams.type === 'text'"
              :label="t('model.responseFormat')"
              path="responseFormatTypeList"
            >
              <n-select
                multiple
                :placeholder="t('model.responseFormatPlaceholder')"
                :options="MODEL_RESPONSE_FORMAT_TYPES"
                v-model:value="editFormParams.responseFormatTypeList"
              />
              <p class="field-description">{{ t('model.responseFormatFeedback') }}</p>
            </n-form-item>
          </div>

          <div class="publish-settings">
            <div class="setting-row">
              <span class="setting-copy">
                <strong>{{ t('model.isEnable') }}</strong>
                <small>{{ t('model.enableFeedback') }}</small>
              </span>
              <n-switch v-model:value="editFormParams.isEnable" />
            </div>
            <div class="setting-row">
              <span class="setting-copy">
                <strong>{{ t('model.isFree') }}</strong>
                <small>{{ t('model.freeFeedback') }}</small>
              </span>
              <n-switch v-model:value="editFormParams.isFree" />
            </div>
          </div>
        </section>

        <n-collapse class="advanced-settings" :default-expanded-names="[]">
          <n-collapse-item name="advanced">
            <template #header>
              <div class="advanced-settings-heading">
                <strong>{{ t('model.advancedSettings') }}</strong>
                <span>{{ t('model.advancedSettingsHint') }}</span>
              </div>
            </template>
            <div class="advanced-settings-grid">
              <n-form-item
                v-if="editFormParams.type === 'text'"
                :label="t('model.maxInputTokens')"
                path="maxInputTokens"
              >
                <n-input-number
                  :min="0"
                  :placeholder="t('model.maxInputTokensPlaceholder')"
                  v-model:value="editFormParams.maxInputTokens"
                  style="width: 100%"
                />
                <p class="field-description">{{ t('model.maxInputTokensFeedback') }}</p>
              </n-form-item>
              <n-form-item
                v-if="editFormParams.type === 'text'"
                :label="t('model.isReasoner')"
                path="isReasoner"
              >
                <n-switch v-model:value="editFormParams.isReasoner" />
                <p class="field-description">{{ t('model.reasonerFeedback') }}</p>
              </n-form-item>
              <n-form-item
                v-if="editFormParams.type === 'text'"
                :label="t('model.isThinkingClosable')"
                path="isThinkingClosable"
              >
                <n-switch
                  v-model:value="editFormParams.isThinkingClosable"
                  :disabled="!editFormParams.isReasoner"
                />
                <p class="field-description">{{ t('model.thinkingClosableFeedback') }}</p>
              </n-form-item>
              <n-form-item
                v-if="editFormParams.type === 'text'"
                :label="t('model.isSupportWebSearch')"
                path="isSupportWebSearch"
              >
                <n-switch
                  v-model:value="editFormParams.isSupportWebSearch"
                  :disabled="!canConfigureWebSearch"
                />
                <span v-if="!canConfigureWebSearch" class="field-help">{{
                  t('model.webSearchDashscopeOnly')
                }}</span>
                <p v-else class="field-description">{{ t('model.webSearchFeedback') }}</p>
              </n-form-item>
              <n-form-item class="advanced-field-wide" :label="t('model.remark')" path="remark">
                <n-input
                  type="textarea"
                  :placeholder="t('model.remarkPlaceholder')"
                  v-model:value="editFormParams.remark"
                />
                <p class="field-description">{{ t('model.remarkFeedback') }}</p>
              </n-form-item>
              <n-form-item
                class="advanced-field-wide"
                :label="t('model.properties')"
                path="properties"
              >
                <JsonEditorVue
                  v-model="editFormParams.properties"
                  :main-menu-bar="false"
                  :mode="Mode.text"
                  style="width: 100%; height: 180px"
                />
                <p class="field-description">{{ t('model.propertiesFeedback') }}</p>
              </n-form-item>
            </div>
          </n-collapse-item>
        </n-collapse>
      </n-form>
      <template #action>
        <n-space>
          <n-button @click="showEditModal = false">{{ t('common.cancel') }}</n-button>
          <n-button type="primary" :loading="formBtnLoading" @click="confirmForm">
            {{ t('model.saveModel') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </n-card>
</template>

<script lang="ts" setup>
  import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
  import { BasicTable, TableAction } from '@/components/Table'
  import { BasicForm, useForm } from '@/components/Form/index'
  import api from '@/api/aiModel'
  import openRouterSyncApi, {
    type OpenRouterModelState,
    type OpenRouterSyncRun,
  } from '@/api/openRouterModelSync'
  import platformApi from '@/api/modelPlatform'
  import {
    getModelTypes,
    getModelInputTypes,
    getModelResponseFormatTypes,
    getDefaultModelPlatforms,
  } from '@/utils/constants'
  import { getColumns } from './columns'
  import { PlusOutlined, SyncOutlined } from '@vicons/antd'
  import { AiModelData } from '/#/aiModel'
  import { useDialog } from 'naive-ui'
  import type { FormItemRule, FormRules } from 'naive-ui'
  import JsonEditorVue from 'json-editor-vue'
  import { t } from '@/locales'
  import { openDeleteDialog } from '@/utils/dialog'

  const columns = getColumns()
  const MODEL_TYPES = getModelTypes()
  const MODEL_INPUT_TYPES = getModelInputTypes()
  const MODEL_RESPONSE_FORMAT_TYPES = getModelResponseFormatTypes()
  const allPlatforms = getDefaultModelPlatforms()

  enum Mode {
    text = 'text',
  }

  const newRecordRules: FormRules = {
    name: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('model.modelNameRequired'),
    },
    title: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('model.modelDisplayNameRequired'),
    },
    type: { required: true, trigger: ['blur', 'input'], message: () => t('model.typeRequired') },
    platform: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('model.platformRequired'),
    },
    inputTypeList: {
      required: true,
      trigger: ['blur', 'change'],
      validator(_rule: FormItemRule, value: string[]) {
        return value?.length ? true : new Error(t('model.inputTypeRequired'))
      },
    },
    responseFormatTypeList: {
      required: true,
      trigger: ['blur', 'change'],
      validator(_rule: FormItemRule, value: string[]) {
        return value?.length ? true : new Error(t('model.responseFormatRequired'))
      },
    },
  }

  const dialog = useDialog()
  const formRef: any = ref(null)
  const actionRef = ref()
  const showEditModal = ref(false)
  const formBtnLoading = ref(false)
  const syncLoading = ref(false)
  const latestSyncRun = ref<OpenRouterSyncRun | null>(null)
  let syncPollTimer: ReturnType<typeof setTimeout> | undefined
  let syncPollAttempts = 0
  const SYNC_POLL_INTERVAL_MS = 2000
  const SYNC_POLL_MAX_ATTEMPTS = 900

  const syncStatusType = computed(() => {
    const status = latestSyncRun.value?.status
    if (status === 'SUCCESS') return 'success'
    if (status === 'FAILED') return 'error'
    if (status === 'PARTIAL_RATE_LIMIT' || status === 'SKIPPED_LOCKED') return 'warning'
    return 'info'
  })

  const syncStatusText = computed(() => {
    const status = latestSyncRun.value?.status || 'QUEUED'
    return t(`model.openRouterStatus${status}`)
  })

  const syncRunSummary = computed(() => {
    const run = latestSyncRun.value
    if (!run) return ''
    if (run.status === 'QUEUED' || run.status === 'RUNNING') {
      return t('model.openRouterSyncRunningHint')
    }
    if (run.status === 'FAILED') {
      return run.errorMessage || run.errorCode || t('model.openRouterSyncFailed')
    }
    const summary = run.summary || {}
    return t('model.openRouterSyncSummary', {
      available: summary.availableAfterSync ?? 0,
      target: summary.targetActiveCount ?? 10,
      added: run.addedCount || 0,
      disabled: run.disabledCount || 0,
    })
  })

  function createEditModel() {
    return {
      label: t('model.createModel'),
      id: '',
      name: '',
      title: '',
      type: 'text',
      platform: '',
      maxInputTokens: 0,
      inputTypeList: ['text'] as string[],
      responseFormatTypeList: ['text'] as string[],
      isReasoner: false,
      isThinkingClosable: false,
      isSupportWebSearch: false,
      isEnable: false,
      isFree: false,
      remark: '',
      properties: {} as Record<string, any>,
    }
  }

  const editFormParams = reactive(createEditModel())
  const canConfigureWebSearch = computed(() => editFormParams.platform === 'dashscope')

  watch(canConfigureWebSearch, (supported) => {
    if (!supported) editFormParams.isSupportWebSearch = false
  })

  watch(
    () => editFormParams.isReasoner,
    (isReasoner) => {
      if (!isReasoner) editFormParams.isThinkingClosable = false
    }
  )

  watch(
    () => editFormParams.type,
    (type) => {
      if (type !== 'text') {
        editFormParams.isReasoner = false
        editFormParams.isThinkingClosable = false
        editFormParams.isSupportWebSearch = false
      }
    }
  )

  const actionColumn = reactive({
    width: 300,
    title: t('common.action'),
    key: 'action',
    fixed: 'right',
    render(record) {
      return h(TableAction as any, {
        style: 'button',
        actions: [
          { label: t('common.edit'), onClick: handleEdit.bind(null, record) },
          {
            label: t('common.enable'),
            onClick: handleEnable.bind(null, record),
            ifShow: () => !record.isEnable,
          },
          {
            label: t('common.disable'),
            onClick: handleDisable.bind(null, record),
            ifShow: () => record.isEnable,
          },
          {
            label: t('model.setFree'),
            onClick: handleFree.bind(null, record, true),
            ifShow: () => !record.isFree,
          },
          {
            label: t('model.setPaid'),
            onClick: handleFree.bind(null, record, false),
            ifShow: () => record.isFree,
          },
        ],
        dropDownActions: [{ label: t('common.delete'), key: 'delete' }],
        select: (key) => {
          if (key === 'delete') {
            openDeleteDialog(dialog, {
              title: t('common.deleteConfirmTitle'),
              content: `${t('model.deleteModelConfirmPrefix')} ${record.name} ${t(
                'model.deleteModelConfirmSuffix'
              )}`,
              positiveText: t('common.delete'),
              negativeText: t('common.cancel'),
              onPositiveClick: () => handleDelete(record),
            })
          }
        },
      })
    },
  })

  const [register, { getFieldsValue }] = useForm({
    gridProps: { cols: '1 s:1 m:2 l:3 xl:4 2xl:4' },
    labelWidth: 120,
  })

  function requestSucceeded(response: Recordable) {
    return response?.success !== false
  }

  function resetEditForm() {
    Object.assign(editFormParams, createEditModel())
    formRef.value?.restoreValidation?.()
  }

  function addTable() {
    resetEditForm()
    showEditModal.value = true
  }

  const loadDataTable = async (res) => {
    const [modelResp, stateResp] = await Promise.all([
      api.search({ ...getFieldsValue() }, res),
      openRouterSyncApi.models().catch(() => ({ data: [] as OpenRouterModelState[] })),
    ])
    const statesByModelId = new Map(
      ((stateResp as any).data || [])
        .filter((state: OpenRouterModelState) => state.modelId != null)
        .map((state: OpenRouterModelState) => [String(state.modelId), state])
    )
    if (modelResp.data.records) {
      modelResp.data.records.forEach((item: AiModelData) => {
        item.inputTypeList = item.inputTypes?.split(',').filter(Boolean) || []
        item.responseFormatTypeList = item.responseFormatTypes?.split(',').filter(Boolean) || []
        item.isSupportWebSearch = item.platform === 'dashscope' && item.isSupportWebSearch
        item.openRouterState = statesByModelId.get(String(item.id))
      })
    }
    return modelResp.data
  }

  function confirmOpenRouterSync() {
    dialog.warning({
      title: t('model.openRouterSyncConfirmTitle'),
      content: t('model.openRouterSyncConfirmContent'),
      positiveText: t('model.openRouterSyncConfirmAction'),
      negativeText: t('common.cancel'),
      onPositiveClick: startOpenRouterSync,
    })
  }

  async function startOpenRouterSync() {
    syncLoading.value = true
    try {
      const response = await openRouterSyncApi.run()
      if (!requestSucceeded(response)) return false
      latestSyncRun.value = response.data
      window['$message'].info(t('model.openRouterSyncQueued'))
      beginPolling(response.data.uuid)
    } catch (error: any) {
      syncLoading.value = false
      window['$message'].error(error?.message || t('model.openRouterSyncFailed'))
      return false
    }
  }

  function beginPolling(runId: string) {
    if (syncPollTimer) clearTimeout(syncPollTimer)
    syncPollAttempts = 0
    syncLoading.value = true
    void pollSyncRun(runId)
  }

  async function pollSyncRun(runId: string) {
    try {
      const response = await openRouterSyncApi.getRun(runId)
      if (!requestSucceeded(response)) throw new Error(t('model.openRouterSyncStatusFailed'))
      latestSyncRun.value = response.data
      if (isTerminalSyncStatus(response.data.status)) {
        finishSyncPolling(response.data)
        return
      }
    } catch (error: any) {
      syncLoading.value = false
      window['$message'].error(error?.message || t('model.openRouterSyncStatusFailed'))
      return
    }
    syncPollAttempts += 1
    if (syncPollAttempts >= SYNC_POLL_MAX_ATTEMPTS) {
      syncLoading.value = false
      window['$message'].warning(t('model.openRouterSyncPollingTimeout'))
      return
    }
    syncPollTimer = setTimeout(() => void pollSyncRun(runId), SYNC_POLL_INTERVAL_MS)
  }

  function isTerminalSyncStatus(status: string) {
    return ['SUCCESS', 'PARTIAL_RATE_LIMIT', 'FAILED', 'SKIPPED_LOCKED'].includes(status)
  }

  function finishSyncPolling(run: OpenRouterSyncRun) {
    syncLoading.value = false
    syncPollTimer = undefined
    reloadTable()
    if (run.status === 'SUCCESS') {
      window['$message'].success(t('model.openRouterSyncCompleted'))
    } else if (run.status === 'PARTIAL_RATE_LIMIT') {
      window['$message'].warning(t('model.openRouterSyncRateLimited'))
    } else if (run.status === 'SKIPPED_LOCKED') {
      window['$message'].warning(t('model.openRouterSyncLocked'))
    } else {
      window['$message'].error(run.errorMessage || t('model.openRouterSyncFailed'))
    }
  }

  async function loadLatestSync() {
    try {
      const response = await openRouterSyncApi.latest()
      if (!requestSucceeded(response) || !response.data) return
      latestSyncRun.value = response.data
      if (response.data.status === 'QUEUED' || response.data.status === 'RUNNING') {
        beginPolling(response.data.uuid)
      }
    } catch {
      // The model table remains usable when audit status is temporarily unavailable.
    }
  }

  const loadPlatforms = async () => {
    const resp = await platformApi.search({}, { current: 1, size: 100 })
    if (!requestSucceeded(resp)) return
    allPlatforms.length = 0
    resp.data.records.forEach((item) =>
      allPlatforms.push({ label: item.title || item.name, value: item.name })
    )
  }

  function onCheckedRow(_rowKeys) {}

  function reloadTable() {
    actionRef.value?.reload()
  }

  function confirmForm(event: Event) {
    event.preventDefault()
    formBtnLoading.value = true
    formRef.value.validate(async (errors) => {
      if (errors) {
        window['$message'].error(t('common.fillCompleteInfo'))
        formBtnLoading.value = false
        return
      }
      try {
        let properties = editFormParams.properties
        if (typeof properties === 'string' && properties.trim()) {
          try {
            properties = JSON.parse(properties)
          } catch {
            window['$message'].error(t('model.propertiesInvalid'))
            return
          }
        }
        const {
          label: _label,
          inputTypeList,
          responseFormatTypeList,
          ...model
        } = editFormParams
        const submitData = {
          ...model,
          name: model.name.trim(),
          title: model.title.trim(),
          inputTypes: inputTypeList.join(','),
          responseFormatTypes: responseFormatTypeList.join(','),
          properties: properties && Object.keys(properties).length ? properties : null,
        }
        const response =
          editFormParams.id === '' ? await api.addOne(submitData) : await api.edit(submitData)
        if (!requestSucceeded(response)) return
        window['$message'].success(
          editFormParams.id === '' ? t('common.createSuccess') : t('common.editSuccess')
        )
        showEditModal.value = false
        reloadTable()
      } finally {
        formBtnLoading.value = false
      }
    })
  }

  async function handleEnable(record: Recordable) {
    const response = await api.enable(record.id)
    if (requestSucceeded(response)) {
      window['$message'].success(t('common.operationSuccess'))
      reloadTable()
    }
  }

  async function handleDisable(record: Recordable) {
    const response = await api.disable(record.id)
    if (requestSucceeded(response)) {
      window['$message'].success(t('common.operationSuccess'))
      reloadTable()
    }
  }

  async function handleFree(record: Recordable, isFree: boolean) {
    const response = await api.edit({ id: record.id, isFree })
    if (requestSucceeded(response)) {
      window['$message'].success(t('common.operationSuccess'))
      reloadTable()
    }
  }

  function handleEdit(record: Recordable) {
    resetEditForm()
    Object.assign(editFormParams, record)
    editFormParams.inputTypeList =
      record.inputTypeList || record.inputTypes?.split(',').filter(Boolean) || []
    editFormParams.responseFormatTypeList =
      record.responseFormatTypeList || record.responseFormatTypes?.split(',').filter(Boolean) || []
    try {
      editFormParams.properties =
        typeof record.properties === 'string'
          ? JSON.parse(record.properties)
          : record.properties || {}
    } catch {
      editFormParams.properties = {}
    }
    editFormParams.label = t('model.editModel')
    showEditModal.value = true
  }

  async function handleDelete(record: Recordable) {
    try {
      const response = await api.deleteOne(record.id)
      if (!requestSucceeded(response)) return false
      window['$message'].success(t('common.deleteSuccess'))
      reloadTable()
    } catch (error: any) {
      window['$message'].error(error?.message || t('common.operationFailed'))
      return false
    }
  }

  function handleSubmit(_values: Recordable) {
    reloadTable()
  }

  function handleReset(_values: Recordable) {}

  onMounted(() => {
    void loadPlatforms()
    void loadLatestSync()
  })

  onBeforeUnmount(() => {
    if (syncPollTimer) clearTimeout(syncPollTimer)
  })
</script>

<style lang="less" scoped>
  .sync-status-bar {
    display: flex;
    margin: 4px 0 16px;
    padding: 12px 14px;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    border: 1px solid var(--zhimesh-border-subtle);
    border-radius: 10px;
    color: var(--zhimesh-info-content);
    background: var(--zhimesh-info-surface);

    time {
      flex: none;
      color: var(--zhimesh-muted);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
    }
  }

  .sync-status-copy {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;

    > span {
      overflow-wrap: anywhere;
      font-size: 12px;
      line-height: 1.55;
    }
  }

  .sync-status-title {
    display: flex;
    align-items: center;
    gap: 8px;

    strong {
      color: var(--zhimesh-info-text);
      font-size: 13px;
      font-weight: 700;
    }
  }

  .form-intro {
    display: flex;
    margin-bottom: 24px;
    padding: 14px 16px;
    flex-direction: column;
    gap: 4px;
    border-radius: 12px;
    color: var(--zhimesh-info-text);
    background: var(--zhimesh-info-surface);

    strong {
      font-size: 14px;
      font-weight: 700;
    }

    span {
      font-size: 12px;
      line-height: 1.6;
    }
  }

  .config-section + .config-section {
    margin-top: 8px;
  }

  .config-section-header {
    margin: 0 0 14px;

    h3 {
      margin: 0 0 4px;
      color: var(--zhimesh-text);
      font-size: 15px;
      font-weight: 700;
    }

    p {
      margin: 0;
      color: var(--zhimesh-muted);
      font-size: 12px;
      line-height: 1.6;
    }
  }

  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: 18px;
  }

  .field-description {
    margin: 6px 0 0;
    color: var(--zhimesh-muted);
    font-size: 11px;
    line-height: 1.55;
  }

  .publish-settings {
    display: grid;
    margin: 4px 0 20px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .setting-row {
    display: flex;
    min-height: 64px;
    padding: 10px 12px;
    align-items: center;
    gap: 12px;
    border-radius: 12px;
    background: var(--zhimesh-neutral-surface);
  }

  .setting-copy {
    display: flex;
    flex: 1;
    min-width: 0;
    flex-direction: column;
    gap: 2px;

    strong {
      color: var(--zhimesh-text);
      font-size: 13px;
      font-weight: 650;
    }

    small {
      color: var(--zhimesh-muted);
      font-size: 11px;
      line-height: 1.45;
    }
  }

  .advanced-settings-heading {
    display: flex;
    align-items: baseline;
    gap: 8px;

    span {
      color: var(--zhimesh-muted);
      font-size: 12px;
    }
  }

  .advanced-settings {
    margin-top: 8px;
    padding-top: 8px;
    border-top: 1px solid var(--zhimesh-border-subtle);
  }

  .advanced-settings-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: 18px;
  }
  .advanced-field-wide {
    grid-column: 1 / -1;
  }

  .field-help {
    margin-left: 8px;
    color: var(--zhimesh-muted);
    font-size: 11px;
  }

  :deep(.n-form-item-label) {
    font-weight: 650;
  }

  :deep(.n-form-item-blank) {
    width: 100%;
    flex-direction: column;
    align-items: stretch;
  }

  :deep(.model-cell) {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  }
  :deep(.model-cell strong) {
    overflow: hidden;
    color: var(--zhimesh-text);
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  :deep(.model-cell small) {
    overflow: hidden;
    color: var(--zhimesh-muted);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  :deep(.runtime-state) {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  }
  :deep(.runtime-state strong) {
    font-size: 13px;
    font-weight: 650;
  }
  :deep(.runtime-state small) {
    overflow: hidden;
    color: var(--zhimesh-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  :deep(.capability-tags) {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }
  :deep(.capability-tag) {
    border-radius: 999px;
    padding: 2px 7px;
    color: var(--zhimesh-neutral-text);
    background: var(--zhimesh-neutral-surface);
    font-size: 11px;
    line-height: 18px;
  }

  @media (max-width: 620px) {
    .sync-status-bar {
      align-items: flex-start;
      flex-direction: column;
      gap: 8px;
    }

    .form-grid,
    .publish-settings,
    .advanced-settings-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
