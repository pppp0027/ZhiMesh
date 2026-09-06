<template>
  <n-modal
    :show="show"
    preset="card"
    :style="{ width: '92%', maxWidth: '1080px' }"
    class="workbench-modal"
    @update:show="emit('update:show', $event)"
  >
    <template #header>
      <div class="workbench-title">
        <div>
          <div class="title-main">{{ t('knowledgeBase.documentWorkbench') }}</div>
          <div class="title-sub">{{ knowledgeBase?.title || '' }}</div>
        </div>
        <n-space>
          <n-tag v-if="knowledgeBase?.isSystem" type="info" :bordered="false">系统私有</n-tag>
          <n-tag
            :type="knowledgeBase?.isEnabled === false ? 'warning' : 'success'"
            :bordered="false"
          >
            {{ knowledgeBase?.isEnabled === false ? '已停用' : '已启用' }}
          </n-tag>
        </n-space>
      </div>
    </template>

    <div class="workbench-scroll">
      <section class="overview-panel">
        <div class="process-steps">
          <div v-for="(step, index) in steps" :key="step.title" class="process-step">
            <div class="step-index">{{ index + 1 }}</div>
            <div>
              <div class="step-title">{{ step.title }}</div>
              <div class="step-desc">{{ step.description }}</div>
            </div>
          </div>
        </div>
        <div class="summary-row">
          <div class="summary-item">
            <span>文档</span
            ><strong>{{ pagination.itemCount || knowledgeBase?.itemCount || 0 }}</strong
            ><small>份</small>
          </div>
          <div class="summary-item">
            <span>向量片段</span><strong>{{ knowledgeBase?.embeddingCount || 0 }}</strong
            ><small>条</small>
          </div>
          <div class="summary-item model-summary">
            <span>图谱抽取模型</span
            ><strong>{{ modelLabel(props.knowledgeBase?.ingestModelId) }}</strong>
          </div>
        </div>
      </section>

      <section class="operation-panel">
        <div class="panel-heading">
          <div>
            <div class="panel-title">上传与处理</div>
            <div class="panel-description">选择文档和索引方式。</div>
          </div>
          <n-alert
            v-if="knowledgeBase?.isSystem"
            type="info"
            :bordered="false"
            class="privacy-note"
          >
            仅系统角色可用，不会展示在用户端。
          </n-alert>
        </div>

        <div class="operation-grid">
          <input
            ref="fileInput"
            class="hidden-file-input"
            type="file"
            multiple
            accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.html,.csv"
            @change="handleFileSelection"
          />
          <button type="button" class="upload-zone" @click="fileInput?.click()">
            <div class="upload-icon">↑</div>
            <div class="upload-title">
              {{ selectedFiles.length ? `已选择 ${selectedFiles.length} 个文档` : '点击选择文档' }}
            </div>
            <div class="upload-help">支持 PDF、Office、TXT、Markdown、HTML、CSV</div>
          </button>

          <div class="process-settings">
            <div class="setting-group">
              <div class="setting-label">处理方式</div>
              <n-checkbox-group v-model:value="uploadIndexTypes">
                <n-space>
                  <n-checkbox value="embedding">向量化入库</n-checkbox>
                  <n-checkbox value="graphical">抽取知识图谱</n-checkbox>
                  <n-checkbox value="fulltext">{{ t('knowledgeBase.fulltextIndex') }}</n-checkbox>
                </n-space>
              </n-checkbox-group>
            </div>
            <div v-if="uploadIndexTypes.includes('graphical')" class="setting-group">
              <div class="setting-label">图谱抽取模型</div>
              <div class="setting-help">使用知识库中已配置的图谱模型。</div>
            </div>
            <n-button
              type="primary"
              size="large"
              block
              :loading="uploading"
              :disabled="selectedFiles.length === 0 || uploadIndexTypes.length === 0"
              @click="uploadSelectedFiles"
            >
              上传并开始处理
            </n-button>
          </div>
        </div>

        <div v-if="selectedFiles.length" class="file-tags">
          <n-tag
            v-for="file in selectedFiles"
            :key="`${file.name}-${file.size}`"
            closable
            @close="removeFile(file)"
          >
            {{ file.name }}
          </n-tag>
        </div>
        <div v-if="uploadResults.length" class="upload-results">
          <n-alert
            v-for="result in uploadResults"
            :key="`${result.fileName}-${result.itemUuid || result.message}`"
            :type="result.parsed ? 'success' : 'error'"
            :title="result.fileName"
            :bordered="false"
          >
            {{ uploadResultText(result) }}
          </n-alert>
        </div>
      </section>

      <section class="result-panel">
        <div class="result-heading">
          <div>
            <div class="panel-title">处理结果</div>
            <div class="panel-description">
              勾选已有文档后，可单独批量建立一种索引，不会重复处理其他已完成索引。
            </div>
          </div>
          <div class="result-actions">
            <n-button
              secondary
              :loading="indexingType === 'embedding'"
              :disabled="checkedRowKeys.length === 0 || indexingType !== null"
              @click="reindexSelected('embedding')"
            >
              批量向量化{{ selectedCountLabel }}
            </n-button>
            <n-button
              secondary
              :loading="indexingType === 'graphical'"
              :disabled="checkedRowKeys.length === 0 || indexingType !== null"
              @click="reindexSelected('graphical')"
            >
              批量图谱化{{ selectedCountLabel }}
            </n-button>
            <n-button
              type="primary"
              secondary
              :loading="indexingType === 'fulltext'"
              :disabled="checkedRowKeys.length === 0 || indexingType !== null"
              @click="reindexSelected('fulltext')"
            >
              批量建立 BM25{{ selectedCountLabel }}
            </n-button>
            <n-button :loading="loading" @click="loadItems(pagination.page)">刷新</n-button>
            <n-input
              v-model:value="keyword"
              clearable
              placeholder="搜索文档"
              class="workbench-search"
              @keyup.enter="loadItems(1)"
            />
          </div>
        </div>
        <n-data-table
          remote
          :loading="loading"
          :columns="itemColumns"
          :data="items"
          :pagination="pagination"
          :row-key="(row: KbItem) => row.uuid"
          :checked-row-keys="checkedRowKeys"
          :scroll-x="1350"
          :max-height="390"
          @update:checked-row-keys="checkedRowKeys = $event"
          @update:page="loadItems"
        />
      </section>
    </div>
  </n-modal>

  <n-modal
    v-model:show="showDetail"
    preset="card"
    :title="detailTitle"
    :style="{ width: '90%', maxWidth: '1020px' }"
  >
    <EmbeddingDetail
      v-if="detailMode === 'embedding' && detailItemUuid"
      :key="detailItemUuid"
      :item-uuid="detailItemUuid"
    />
    <GraphDetail
      v-if="detailMode === 'graph' && detailItemUuid"
      :key="detailItemUuid"
      :item-uuid="detailItemUuid"
    />
  </n-modal>
</template>

<script lang="ts" setup>
  import { computed, h, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { NButton, NEllipsis, NSpace, NTag, type DataTableColumns, useMessage } from 'naive-ui'
  import api from '@/api/knowledgeBase'
  import { t } from '@/locales'
  import EmbeddingDetail from './EmbeddingDetail.vue'
  import GraphDetail from './GraphDetail.vue'

  interface SelectOption {
    label: string
    value: number
  }
  interface UploadResult {
    fileName: string
    itemUuid?: string
    parsed: boolean
    indexQueued: boolean
    message: string
  }
  type IndexType = 'embedding' | 'graphical' | 'fulltext'
  interface KbItem {
    uuid: string
    title: string
    sourceFileName?: string
    embeddingStatus: 'NONE' | 'DOING' | 'DONE' | 'FAIL'
    graphicalStatus: 'NONE' | 'DOING' | 'DONE' | 'FAIL'
    fulltextStatus: 'NONE' | 'DOING' | 'DONE' | 'FAIL'
    embeddingStatusChangeTime?: string
    graphicalStatusChangeTime?: string
    fulltextStatusChangeTime?: string
    fulltextStartedAt?: string
    fulltextCompletedAt?: string
    fulltextChunkSetUuid?: string
    graphicalModelId?: number
    createTime?: string
  }

  const props = withDefaults(
    defineProps<{
      show: boolean
      knowledgeBase: Record<string, any> | null
      modelOptions: SelectOption[]
    }>(),
    {
      show: false,
      knowledgeBase: null,
      modelOptions: () => [],
    }
  )
  const emit = defineEmits(['update:show', 'refresh'])
  const message = useMessage()
  const steps = [
    { title: '上传并解析', description: '读取原始文档内容' },
    { title: '生成知识索引', description: '向量化、图谱抽取与关键词索引' },
    { title: '检查处理结果', description: '查看片段、实体与关系' },
  ]

  const loading = ref(false)
  const uploading = ref(false)
  const indexingType = ref<IndexType | null>(null)
  const fileInput = ref<HTMLInputElement | null>(null)
  const selectedFiles = ref<File[]>([])
  const uploadResults = ref<UploadResult[]>([])
  const items = ref<KbItem[]>([])
  const checkedRowKeys = ref<Array<string | number>>([])
  const keyword = ref('')
  const uploadIndexTypes = ref<IndexType[]>(['embedding'])
  const selectedCountLabel = computed(() =>
    checkedRowKeys.value.length ? `（${checkedRowKeys.value.length}）` : ''
  )
  const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0 })
  const showDetail = ref(false)
  const detailTitle = ref('')
  const detailItemUuid = ref('')
  const detailMode = ref<'embedding' | 'graph'>('embedding')
  let pollTimer: number | undefined
  let loadGeneration = 0

  function modelLabel(modelId?: number | null) {
    if (!modelId || Number(modelId) <= 0) return '未配置'
    return (
      props.modelOptions.find((option) => option.value === Number(modelId))?.label || `#${modelId}`
    )
  }
  function statusLabel(
    status: KbItem['embeddingStatus'],
    type: 'embedding' | 'graph' | 'fulltext'
  ) {
    if (status === 'DOING') return '处理中'
    if (status === 'DONE') {
      if (type === 'embedding') return '向量化成功'
      if (type === 'graph') return '图谱化成功'
      return 'BM25 已建立'
    }
    if (status === 'FAIL') return '处理失败'
    return '未处理'
  }
  function statusTagType(status: KbItem['embeddingStatus']) {
    if (status === 'DONE') return 'success'
    if (status === 'DOING') return 'info'
    if (status === 'FAIL') return 'error'
    return 'default'
  }
  function renderStatus(row: KbItem, type: 'embedding' | 'graph' | 'fulltext') {
    const status =
      type === 'embedding'
        ? row.embeddingStatus
        : type === 'graph'
        ? row.graphicalStatus
        : row.fulltextStatus || 'NONE'
    const time =
      type === 'embedding'
        ? row.embeddingStatusChangeTime
        : type === 'graph'
        ? row.graphicalStatusChangeTime
        : row.fulltextStatusChangeTime
    return h('div', { class: 'status-cell' }, [
      h(
        NTag,
        { type: statusTagType(status), size: 'small', bordered: false },
        { default: () => statusLabel(status, type) }
      ),
      time ? h('span', { class: 'status-time' }, time) : null,
      type === 'graph' && Number(row.graphicalModelId) > 0
        ? h('span', { class: 'status-time' }, modelLabel(row.graphicalModelId))
        : null,
    ])
  }

  const itemColumns = computed<DataTableColumns<KbItem>>(() => [
    { type: 'selection', fixed: 'left' },
    {
      title: '文档名称',
      key: 'title',
      width: 230,
      fixed: 'left',
      render: (row) =>
        h(NEllipsis, { tooltip: true }, { default: () => row.sourceFileName || row.title }),
    },
    {
      title: '解析状态',
      key: 'parseStatus',
      width: 110,
      render: () =>
        h(NTag, { type: 'success', size: 'small', bordered: false }, { default: () => '解析成功' }),
    },
    {
      title: '向量化状态',
      key: 'embeddingStatus',
      width: 190,
      render: (row) => renderStatus(row, 'embedding'),
    },
    {
      title: '图谱化状态',
      key: 'graphicalStatus',
      width: 210,
      render: (row) => renderStatus(row, 'graph'),
    },
    {
      title: t('knowledgeBase.fulltextStatus'),
      key: 'fulltextStatus',
      width: 190,
      render: (row) => renderStatus(row, 'fulltext'),
    },
    { title: '创建时间', key: 'createTime', width: 180 },
    {
      title: '详情',
      key: 'action',
      width: 210,
      fixed: 'right',
      render: (row) =>
        h(NSpace, { size: 6, wrap: false }, () => [
          h(
            NButton,
            {
              size: 'tiny',
              secondary: true,
              type: 'info',
              disabled: row.embeddingStatus !== 'DONE',
              onClick: () => openDetail(row, 'embedding'),
            },
            { default: () => '查看向量' }
          ),
          h(
            NButton,
            {
              size: 'tiny',
              secondary: true,
              type: 'success',
              disabled: row.graphicalStatus !== 'DONE',
              onClick: () => openDetail(row, 'graph'),
            },
            { default: () => '查看图谱' }
          ),
        ]),
    },
  ])

  function handleFileSelection(event: Event) {
    const input = event.target as HTMLInputElement
    selectedFiles.value = Array.from(input.files || [])
    uploadResults.value = []
  }
  function removeFile(file: File) {
    selectedFiles.value = selectedFiles.value.filter((item) => item !== file)
  }
  async function ensureGraphModel(types: IndexType[]) {
    if (!types.includes('graphical')) return true
    if (Number(props.knowledgeBase?.ingestModelId) > 0) return true
    message.warning('请先在“编辑知识库”中配置图谱抽取模型')
    return false
  }
  async function uploadSelectedFiles() {
    if (
      !props.knowledgeBase?.uuid ||
      !selectedFiles.value.length ||
      !(await ensureGraphModel(uploadIndexTypes.value))
    )
      return
    uploading.value = true
    try {
      const response = await api.uploadDocs(
        props.knowledgeBase.uuid,
        selectedFiles.value,
        uploadIndexTypes.value
      )
      uploadResults.value = Array.isArray(response.data) ? response.data : []
      const parsedCount = uploadResults.value.filter((item) => item.parsed).length
      if (parsedCount) message.success(`已解析 ${parsedCount} 个文档，索引任务已进入后台处理`)
      selectedFiles.value = []
      if (fileInput.value) fileInput.value.value = ''
      await loadItems(1)
      emit('refresh')
      startPolling()
    } catch (error: any) {
      message.error(error?.message || '上传处理失败')
    } finally {
      uploading.value = false
    }
  }
  function uploadResultText(result: UploadResult) {
    if (!result.parsed)
      return result.message === 'UNSUPPORTED_OR_EMPTY' ? '文档为空或格式不受支持' : '上传或解析失败'
    if (result.message === 'INDEX_QUEUE_FAILED') return '解析成功，但索引任务加入队列失败'
    return result.indexQueued ? '解析成功，已开始生成索引' : '解析成功，未生成索引'
  }
  async function reindexSelected(type: IndexType) {
    if (
      !checkedRowKeys.value.length ||
      indexingType.value !== null ||
      !(await ensureGraphModel([type]))
    )
      return
    indexingType.value = type
    try {
      await api.indexItems(checkedRowKeys.value.map(String), [type])
      const actionLabel =
        type === 'embedding' ? '向量化' : type === 'graphical' ? '图谱化' : 'BM25 索引'
      message.success(`${actionLabel}批量任务已提交`)
      checkedRowKeys.value = []
      await loadItems(pagination.page)
      startPolling()
    } catch (error: any) {
      message.error(error?.message || '批量任务提交失败')
    } finally {
      indexingType.value = null
    }
  }
  async function loadItems(page = 1, allowAutoPolling = true) {
    if (!props.show || !props.knowledgeBase?.uuid) return
    const requestId = ++loadGeneration
    loading.value = true
    try {
      const response = await api.searchItems(
        props.knowledgeBase.uuid,
        { current: page, size: pagination.pageSize },
        keyword.value
      )
      if (requestId !== loadGeneration) return
      items.value = response.data?.records || []
      pagination.page = page
      pagination.itemCount = response.data?.total || 0
      if (
        allowAutoPolling &&
        items.value.some(
          (item) =>
            item.embeddingStatus === 'DOING' ||
            item.graphicalStatus === 'DOING' ||
            item.fulltextStatus === 'DOING'
        )
      )
        startPolling()
    } catch (error: any) {
      if (requestId === loadGeneration) message.error(error?.message || '文档列表加载失败')
    } finally {
      if (requestId === loadGeneration) loading.value = false
    }
  }
  function clearPolling() {
    if (pollTimer) window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
  function startPolling() {
    clearPolling()
    if (props.show) pollTimer = window.setTimeout(pollIndexing, 2500)
  }
  async function pollIndexing() {
    if (!props.show || !props.knowledgeBase?.uuid) return
    try {
      const response = await api.checkIndexing(props.knowledgeBase.uuid)
      await loadItems(pagination.page, false)
      emit('refresh')
      if (response.data === false) startPolling()
      else clearPolling()
    } catch {
      clearPolling()
    }
  }
  function openDetail(row: KbItem, mode: 'embedding' | 'graph') {
    detailMode.value = mode
    detailItemUuid.value = row.uuid
    detailTitle.value = `${mode === 'embedding' ? '文档向量详情' : '知识图谱'} · ${
      row.sourceFileName || row.title
    }`
    showDetail.value = true
  }

  watch(
    () => [props.show, props.knowledgeBase?.uuid],
    async ([visible]) => {
      clearPolling()
      if (!visible || !props.knowledgeBase) {
        loadGeneration++
        loading.value = false
        return
      }
      selectedFiles.value = []
      uploadResults.value = []
      checkedRowKeys.value = []
      await loadItems(1)
    },
    { immediate: true }
  )
  onBeforeUnmount(clearPolling)
</script>

<style lang="less" scoped>
  .workbench-scroll {
    max-height: calc(100vh - 170px);
    overflow-y: auto;
    padding: 2px 6px 6px 2px;
  }
  .workbench-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    width: 100%;
  }
  .title-main {
    color: var(--zhimesh-text);
    font-size: 20px;
    font-weight: 750;
  }
  .title-sub {
    margin-top: 3px;
    color: var(--zhimesh-muted);
    font-size: 13px;
    font-weight: 400;
  }
  .overview-panel,
  .operation-panel,
  .result-panel {
    border: 1px solid var(--zhimesh-border);
    border-radius: 10px;
    background: var(--zhimesh-glass);
    box-shadow: none;
  }
  .overview-panel {
    padding: 18px 20px;
    background: var(--zhimesh-glass-soft);
  }
  .process-steps {
    display: grid;
    width: min(100%, 760px);
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr) minmax(0, 1fr);
    gap: 32px;
  }
  .process-step {
    position: relative;
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .process-step:not(:last-child)::after {
    position: absolute;
    right: -24px;
    width: 16px;
    height: 1px;
    background: var(--zhimesh-border);
    content: '';
  }
  .step-index {
    display: grid;
    flex: 0 0 32px;
    width: 32px;
    height: 32px;
    place-items: center;
    border-radius: 7px;
    background: var(--zhimesh-control-primary);
    color: white;
    font-weight: 700;
  }
  .step-title {
    color: var(--zhimesh-text);
    font-weight: 700;
  }
  .step-desc {
    margin-top: 2px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }
  .summary-row {
    display: flex;
    gap: 12px;
    margin-top: 16px;
    padding-top: 14px;
    border-top: 1px solid var(--zhimesh-border-subtle);
  }
  .summary-item {
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 120px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }
  .summary-item strong {
    color: var(--zhimesh-text);
    font-size: 20px;
  }
  .summary-item small {
    font-size: 12px;
  }
  .model-summary {
    flex: 1;
    justify-content: flex-end;
  }
  .model-summary strong {
    font-size: 15px;
  }
  .operation-panel,
  .result-panel {
    margin-top: 14px;
    padding: 20px;
  }
  .panel-heading,
  .result-heading {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 20px;
    margin-bottom: 16px;
  }
  .panel-title {
    color: var(--zhimesh-text);
    font-size: 17px;
    font-weight: 750;
  }
  .panel-description {
    margin-top: 4px;
    color: var(--zhimesh-muted);
    font-size: 12px;
  }
  .privacy-note {
    max-width: 430px;
    font-size: 12px;
  }
  .operation-grid {
    display: grid;
    grid-template-columns: minmax(300px, 0.9fr) minmax(360px, 1.1fr);
    gap: 18px;
  }
  .upload-zone {
    display: flex;
    min-height: 190px;
    cursor: pointer;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    border: 1px dashed var(--zhimesh-border);
    border-radius: 8px;
    background: var(--zhimesh-glass-soft);
    color: inherit;
    font: inherit;
    text-align: center;
    transition: border-color 0.2s ease;
  }
  .upload-zone:hover {
    border-color: var(--zhimesh-primary);
  }
  .upload-icon {
    display: grid;
    width: 42px;
    height: 42px;
    place-items: center;
    border-radius: 8px;
    background: var(--zhimesh-control-primary);
    color: white;
    font-size: 24px;
  }
  .upload-title {
    margin-top: 13px;
    color: var(--zhimesh-text);
    font-weight: 700;
  }
  .upload-help,
  .setting-help {
    margin-top: 5px;
    color: var(--zhimesh-muted);
    font-size: 11px;
  }
  .process-settings {
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    gap: 14px;
    padding: 4px 0;
  }
  .setting-group {
    transition: opacity 0.2s;
  }
  .setting-group.muted {
    opacity: 0.58;
  }
  .setting-label {
    margin-bottom: 8px;
    color: var(--zhimesh-text);
    font-size: 13px;
    font-weight: 700;
  }
  .hidden-file-input {
    display: none;
  }
  .file-tags,
  .upload-results {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 14px;
  }
  .upload-results {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .result-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .workbench-search {
    width: 220px;
  }
  :deep(.status-cell) {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }
  :deep(.status-time) {
    color: var(--zhimesh-muted);
    font-size: 11px;
  }
  :deep(.n-data-table) {
    border-radius: 14px;
    overflow: hidden;
  }
  @media (max-width: 820px) {
    .process-steps {
      width: 100%;
    }

    .process-steps,
    .operation-grid {
      grid-template-columns: 1fr;
    }
    .process-step::after {
      display: none;
    }
    .panel-heading,
    .result-heading,
    .result-actions {
      align-items: stretch;
      flex-direction: column;
    }
    .privacy-note,
    .workbench-search {
      width: 100%;
      max-width: none;
    }
    .summary-row {
      flex-wrap: wrap;
    }
    .model-summary {
      justify-content: flex-start;
    }
    .upload-results {
      grid-template-columns: 1fr;
    }
  }
</style>
