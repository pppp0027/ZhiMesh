<script setup lang='ts'>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { NAlert, NBreadcrumb, NBreadcrumbItem, NButton, NCard, NCheckbox, NCheckboxGroup, NDataTable, NFlex, NIcon, NInput, NModal, NP, NSpace, NTag, NText, NUpload, NUploadDragger, useDialog, useMessage } from 'naive-ui'
import { ArchiveOutline } from '@vicons/ionicons5'
import { Building24Regular, PeopleTeam24Regular, Person24Regular } from '@vicons/fluent'
import { useRoute } from 'vue-router'
import type { UploadFileInfo, UploadInst } from 'naive-ui'
import ItemEmbeddingList from './ItemEmbeddingList.vue'
import ItemGraph from './ItemGraph.vue'
import { createColumns } from './itemColumns'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAuthStore, useKbStore, useUserStore } from '@/store'
import { knowledgeBaseEmptyInfo, knowledgeBaseEmptyItem } from '@/utils/functions'
import { t } from '@/locales'
import api from '@/api'
import { openDeleteDialog } from '@/utils/dialog'

const ms = useMessage()
const dialog = useDialog()
const route = useRoute()
const { kbUuid: curKbUuid } = route.params as { kbUuid: string; kbId: string }

const showEmbeddingListModal = ref<boolean>(false)
const showGraphModal = ref<boolean>(false)
const kbItemUuidForEmbeddingList = ref<string>('')
const kbItemUuidForGraph = ref<string>('')

const modalMainHeight = ref<number>(500)
const tableMaxHeight = ref<number>(500)
const loading = ref<boolean>(false)
const submitting = ref<boolean>(false)
const showItemEditModal = ref<boolean>(false)
const showUploadModal = ref<boolean>(false)
const showIndexModal = ref<boolean>(false)
const itemList = ref<KnowledgeBase.Item[]>([])
const indexAfterUpload = ref(false)
const indexTypeSelected = ref<string[]>(['embedding'])
const uploadAction = computed(() => {
  const params = new URLSearchParams({
    indexAfterUpload: String(indexAfterUpload.value),
  })
  if (indexAfterUpload.value)
    params.set('indexTypes', indexTypeSelected.value.join(','))
  return `/api/knowledge-base/upload/${curKbUuid}?${params.toString()}`
})
const uploadRef = ref<UploadInst | null>(null)
const headers = { Authorization: '' }
const fileListLength = ref(0)
const fileList = ref<UploadFileInfo[]>([])
// 提交后是否有文件仍在上传中；全部 settle 后才自动关窗刷新
const uploadInFlight = ref(false)
const paginationReactive = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
})
const searchValue = ref<string>('')
const tmpItem = reactive<KnowledgeBase.Item>(knowledgeBaseEmptyItem())
// 控制 input 按钮
const inputStatus = computed(() => tmpItem.title.trim().length < 1 || submitting.value)
const { isMobile } = useBasicLayout()
const authStore = useAuthStore()
const userStore = useUserStore()
const kbStore = useKbStore()
const token = ref<string>(authStore.token)
const checkedItemRowKeys = ref<string[]>([])
const checkedItems = ref<KnowledgeBase.Item[]>([])
const curKnowledgeBase: KnowledgeBase.Info = reactive<KnowledgeBase.Info>(knowledgeBaseEmptyInfo())
const itemBoxClass = 'space-y-1'

// 面包屑按归属动态展示列表分区，返回时保留原分区（scope 入 query）
const listScopeLabel = computed(() => {
  if (curKnowledgeBase.ownerType === 'TEAM')
    return t('team.myTeams')
  if (curKnowledgeBase.ownerType === 'COMPANY')
    return t('knowledgeBase.ownerTypeCompany')
  return t('knowledgeBase.myKnowledgeBase')
})
const listScopeHref = computed(() =>
  curKnowledgeBase.ownerType === 'TEAM' ? '/#/kb-manage?scope=team' : '/#/kb-manage')

// 归属标签：可见性按归属推导，团队库带团队名、企业库按可见范围标注
function ownerTierLabel(kbInfo: KnowledgeBase.Info) {
  if (kbInfo.ownerType === 'TEAM')
    return kbInfo.teamName ? `${t('knowledgeBase.ownerTypeTeam')}·${kbInfo.teamName}` : t('knowledgeBase.ownerTypeTeam')
  if (kbInfo.ownerType === 'COMPANY')
    return kbInfo.companyScope === 'EXECUTIVE'
      ? `${t('knowledgeBase.ownerTypeCompany')}·${t('knowledgeBase.companyScopeExecutive')}`
      : t('knowledgeBase.ownerTypeCompany')
  return t('knowledgeBase.ownerTypePersonal')
}

// 分级访问门控（纯 UX，后端已强制）：优先取列表行携带的 accessLevel；
// 直接输 URL 进入时按实体字段推导，推导不出的一律落到只读安全侧。
const canWrite = computed<boolean>(() => {
  if (curKnowledgeBase.isSystem)
    return true
  const listRow = [kbStore.myKbInfos, kbStore.teamKbInfos, kbStore.companyKbInfos]
    .flatMap(list => list)
    .find(item => item.uuid === curKnowledgeBase.uuid && item.accessLevel)
  if (listRow)
    return listRow.accessLevel === 'WRITE' || listRow.accessLevel === 'MANAGE'
  if (curKnowledgeBase.ownerType === 'TEAM' || curKnowledgeBase.ownerType === 'COMPANY')
    return false
  return !!curKnowledgeBase.ownerUuid && curKnowledgeBase.ownerUuid === (userStore.userInfo?.uuid || '')
})

// 文件预览
const showFileContentModal = ref<boolean>(false)
const previewFileUrl = ref<string>('')
const previewMimeType = ref<string>('')
const previewFileContent = ref<string>('')
const previewFileName = ref<string>('')
let previewRequestId = 0
let indexingTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let indexingPollStartedAt = 0
let indexingCheckInFlight = false
let indexingPollingDisabled = false
const INDEXING_POLL_INTERVAL_MS = 3000
const INDEXING_POLL_TIMEOUT_MS = 15 * 60 * 1000

const openFileInNewTab = function (url: string) {
  const x = new window.XMLHttpRequest()
  x.open('GET', url, true)
  x.responseType = 'blob'
  x.onload = () => {
    if (x.status < 200 || x.status >= 300)
      return
    const downloadUrl = window.URL.createObjectURL(x.response)
    const a = document.createElement('a')
    a.href = downloadUrl
    a.download = previewFileName.value
    a.click()
    setTimeout(() => window.URL.revokeObjectURL(downloadUrl), 0)
  }
  x.send()
}

const showFileContent = (selected: KnowledgeBase.Item = knowledgeBaseEmptyItem()) => {
  const requestId = ++previewRequestId
  // window.open(`/api${selected.sourceFileUrl}?token=${token.value}`, '_blank')
  previewFileContent.value = ''
  previewFileName.value = ''
  const fileUrl = `${selected.sourceFileUrl}?token=${token.value}`
  previewFileUrl.value = fileUrl
  previewFileName.value = selected.sourceFileName
  const ext = selected.sourceFileName.substring(selected.sourceFileName.lastIndexOf('.') + 1)
  switch (ext) {
    case 'pdf':
      previewMimeType.value = 'application/pdf'
      break
    case 'doc':
    case 'docx':
      previewMimeType.value = 'application/msword'
      break
    case 'ppt':
    case 'pptx':
      previewMimeType.value = 'application/vnd.ms-powerpoint'
      break
    case 'xls':
    case 'xlsx':
      previewMimeType.value = 'application/vnd.ms-excel'
      break
    case 'html':
      previewMimeType.value = 'text/html'
      break
    case 'txt':
      previewMimeType.value = 'text/plain'
      api.loadFileContent(fileUrl).then((resp) => {
        if (requestId === previewRequestId)
          previewFileContent.value = resp.data
      }).catch((err) => {
        console.error('loadFileContent error', err)
      })
      break
    default:
      previewMimeType.value = 'text/plain'
  }
  showFileContentModal.value = true
}

const showEmbeddingList = (selected: KnowledgeBase.Item = knowledgeBaseEmptyItem()) => {
  showEmbeddingListModal.value = true
  kbItemUuidForEmbeddingList.value = selected.uuid
}

const showGraph = (selected: KnowledgeBase.Item = knowledgeBaseEmptyItem()) => {
  showGraphModal.value = true
  kbItemUuidForGraph.value = selected.uuid
}

const changeEditModal = (selected: KnowledgeBase.Item = knowledgeBaseEmptyItem()) => {
  if (selected.kbId !== '0') {
    Object.assign(tmpItem, selected)
  } else {
    Object.assign(tmpItem, knowledgeBaseEmptyItem())
    tmpItem.kbId = curKnowledgeBase.id
    tmpItem.kbUuid = curKnowledgeBase.uuid
  }
  showItemEditModal.value = !showItemEditModal.value
}

function rowKey(row: KnowledgeBase.Item) {
  return row.uuid
}

// canWrite 变化时（实体/列表行加载完成）需要重建列以显隐勾选列与行内写操作
const columns = computed(() => createColumns(showEmbeddingList, showGraph, showFileContent, changeEditModal, deleteKbItem, retryItemIndex, () => canWrite.value))

function changeIndexModal() {
  showIndexModal.value = true
}

/**
 * 索引文档（批量选中与失败重试共用）
 */
async function triggerIndexing(uuids: string[], indexTypes: string[]) {
  if (loading.value) {
    ms.warning(t('knowledgeBase.indexTaskRunning'))
    return
  }
  loading.value = true
  indexingPollingDisabled = false
  try {
    await api.knowledgeBaseItemsIndexing(uuids, indexTypes)
    indexingPollStartedAt = Date.now()
    indexingCheck()
    ms.success(t('knowledgeBase.indexTaskRunning'))
    search(1)
  } catch (error: any) {
    ms.error(error?.message || t('common.wrong'))
  } finally {
    loading.value = false
  }
}

async function textIndexing() {
  if (checkedItemRowKeys.value.length === 0) {
    ms.warning(t('knowledgeBase.selectAtLeastOneRow'))
    return
  }
  if (indexTypeSelected.value.length === 0) {
    ms.warning(t('knowledgeBase.selectAtLeastOneIndexType'))
    return
  }
  showIndexModal.value = false
  try {
    await triggerIndexing(checkedItemRowKeys.value, indexTypeSelected.value)
  } finally {
    kbItemUuidForGraph.value = ''
  }
}

/**
 * 状态列失败重试：仅重建该条目的对应索引类型
 */
function retryItemIndex(row: KnowledgeBase.Item, indexType: 'embedding' | 'graphical' | 'fulltext') {
  triggerIndexing([row.uuid], [indexType])
}

/**
 * 检查索引是否已经完成，如果已完成，则刷新列表
 */
async function indexingCheck() {
  if (disposed || indexingCheckInFlight)
    return
  if (!indexingPollStartedAt)
    indexingPollStartedAt = Date.now()
  if (Date.now() - indexingPollStartedAt >= INDEXING_POLL_TIMEOUT_MS) {
    indexingPollStartedAt = 0
    indexingPollingDisabled = true
    indexingTimer = undefined
    // Refresh once so the table exposes terminal/failed statuses. Do not keep
    // issuing requests when a backend or Redis task is stale.
    await search(paginationReactive.page, false)
    if (!disposed)
      ms.warning(t('knowledgeBase.indexCheckTimeout'))
    return
  }
  indexingCheckInFlight = true
  try {
    const response = await api.knowledgeBaseIndexingCheck(curKbUuid)
    if (response.data) {
      indexingPollStartedAt = 0
      await search(paginationReactive.page, false)
    } else if (!disposed) {
      indexingTimer = setTimeout(() => {
        indexingTimer = undefined
        indexingCheck()
      }, INDEXING_POLL_INTERVAL_MS)
    }
  } catch (error) {
    console.error('indexing status check failed', error)
    if (!disposed)
      ms.error(t('common.wrong'))
  } finally {
    indexingCheckInFlight = false
  }
}

function onHandleCheckedRowKeys(keys: Array<string | number>, rows: object[], meta: { row: object | undefined; action: 'check' | 'uncheck' | 'checkAll' | 'uncheckAll' }) {
  checkedItemRowKeys.value = keys.map((key) => {
    return `${key}`
  })
  // 跨页面选择时，rows 中的非当前页的数据为 null，所以将 null 过滤掉，并将非当前页的值填充
  const itemMap = new Map<string, KnowledgeBase.Item>()
  const tmpItems = [] as KnowledgeBase.Item[]
  tmpItems.push(...(rows as KnowledgeBase.Item[]))
  tmpItems.push(...checkedItems.value)
  tmpItems.forEach((item) => {
    if (item)
      itemMap.set(item.uuid, item)
  })
  checkedItems.value = Array.from(itemMap.entries())
    .filter(([key]) => checkedItemRowKeys.value.includes(key))
    .map(([, value]) => value)
}

function removeCheckedItem(item: KnowledgeBase.Item) {
  checkedItemRowKeys.value = checkedItemRowKeys.value.filter((key) => {
    return key !== item.uuid
  })
  checkedItems.value = checkedItems.value.filter((row) => {
    return row.uuid !== item.uuid
  })
}

async function onHandlePageChange(currentPage: number) {
  search(currentPage)
}

async function onKeyUpSearch(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    search(1)
  }
}

async function onUploadBefore(data: {
  file: UploadFileInfo
  fileList: UploadFileInfo[]
}) {
  return true
}

function onUploadChange({ fileList: currentFileList }: { file: UploadFileInfo, fileList: UploadFileInfo[] }) {
  fileList.value = currentFileList
  fileListLength.value = currentFileList.filter(file => file.status !== 'removed').length
  if (!uploadInFlight.value)
    return
  // 全部文件 finish/error 后才关窗刷新；失败文件保留在列表供重传
  const unsettled = currentFileList.filter(file => file.status === 'pending' || file.status === 'uploading')
  if (unsettled.length > 0)
    return
  uploadInFlight.value = false
  const failed = currentFileList.filter(file => file.status === 'error')
  if (failed.length > 0) {
    ms.error(t('common.uploadFailed'))
    return
  }
  showUploadModal.value = false
  search(1)
}

function onUploadSubmit() {
  if (indexAfterUpload.value && indexTypeSelected.value.length === 0) {
    ms.warning(t('knowledgeBase.selectAtLeastOneIndexType'))
    return
  }
  // 待上传 + 失败待重传的文件一起提交（naive-ui 需按 id 逐个重提交失败项）
  const targets = fileList.value.filter(file => file.status === 'pending' || file.status === 'error')
  if (targets.length === 0)
    return
  uploadInFlight.value = true
  targets.forEach((file) => {
    uploadRef.value?.submit(file.id)
  })
}

function onUploadFinish({
  file,
  event,
}: {
  file: UploadFileInfo
  event?: ProgressEvent
}) {
  let respData: any
  try {
    const responseText = (event?.target as XMLHttpRequest | undefined)?.response
    respData = typeof responseText === 'string' ? JSON.parse(responseText) : responseText
  } catch (error) {
    console.error('upload response parse failed', error)
    ms.error(t('knowledgeBase.uploadFailedResponseError'))
    return { ...file, status: 'error' as const }
  }
  if (!respData) {
    ms.error(t('knowledgeBase.uploadFailedResponseError'))
    return { ...file, status: 'error' as const }
  }
  const { success, message } = respData
  if (success) {
    ms.success(t('common.uploadSuccess'))
    return file
  }
  ms.error(message || t('common.uploadFailed'))
  // 标记为 error 使文件保留在列表中，可通过内置重试按钮或再次提交重传
  return { ...file, status: 'error' as const }
}

async function search(currentPage: number, allowAutoPolling = true) {
  loading.value = true
  try {
    const resp = await api.knowledgeBaseItemSearch<PageResponse>(currentPage, paginationReactive.pageSize, curKbUuid, searchValue.value)
    setResp(currentPage, resp.data, allowAutoPolling)
  } catch (error) {
    console.error('knowledge base item search failed', error)
    ms.error(t('common.wrong'))
  } finally {
    loading.value = false
  }
}

function setResp(currentPage: number, data: PageResponse, allowAutoPolling = true) {
  const records = data.records as KnowledgeBase.Item[]
  itemList.value = records
  paginationReactive.page = currentPage
  paginationReactive.itemCount = data.total
  if (allowAutoPolling && !disposed && !indexingPollingDisabled && !indexingTimer && records.some(item =>
    item.embeddingStatus === 'DOING'
    || item.graphicalStatus === 'DOING'
    || item.fulltextStatus === 'DOING')) {
    indexingTimer = setTimeout(() => {
      indexingTimer = undefined
      indexingCheck()
    }, 3000)
  }
}

async function saveOrUpdate() {
  try {
    submitting.value = true
    const resp = await api.knowledgeBaseItemSaveOrUpdate<KnowledgeBase.Item>(tmpItem)
    Object.assign(tmpItem, resp.data)
    showItemEditModal.value = false
    Object.assign(tmpItem, knowledgeBaseEmptyItem())
    await search(1)
  } catch (error: any) {
    ms.error(error?.message || t('common.wrong'))
  } finally {
    submitting.value = false
  }
}

function deleteKbItem(row: KnowledgeBase.Item) {
  openDeleteDialog(dialog, {
    title: t('knowledgeBase.deleteConfirmTitle'),
    content: t('knowledgeBase.deleteItemConfirm', { title: row.title }),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.knowledgeBaseItemDelete(row.uuid)
        nextTick(() => {
          itemList.value = itemList.value.filter(item => item.uuid !== row.uuid)
        })
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

async function initData() {
  try {
    const [_, resp] = await Promise.all([
      search(1),
      api.knowledgeBaseInfo<KnowledgeBase.Info>(curKbUuid),
    ])
    Object.assign(curKnowledgeBase, resp.data)
  } catch (error) {
    console.error('knowledge base detail load failed', error)
    ms.error(t('common.wrong'))
  }
}

onMounted(async () => {
  modalMainHeight.value = window.innerHeight - 150
  tableMaxHeight.value = window.innerHeight - 420
})
watch(
  () => authStore.token,
  (nextToken) => {
    token.value = nextToken
    if (nextToken) {
      headers.Authorization = nextToken
      initData()
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  disposed = true
  previewRequestId++
  if (indexingTimer)
    clearTimeout(indexingTimer)
  indexingTimer = undefined
})

// 关闭上传弹窗时重置文件列表，避免下次打开携带旧文件重复上传
watch(showUploadModal, (show) => {
  if (show)
    return
  fileList.value = []
  fileListLength.value = 0
  uploadInFlight.value = false
})
</script>

<template>
  <div class="p-4">
    <NBreadcrumb separator=">">
      <NBreadcrumbItem href="/">
        {{ t('common.home') }}
      </NBreadcrumbItem>
      <NBreadcrumbItem :href="listScopeHref">
        {{ listScopeLabel }}
      </NBreadcrumbItem>
      <NBreadcrumbItem :clickable="false">
        {{ curKnowledgeBase.title }}
      </NBreadcrumbItem>
    </NBreadcrumb>
    <NCard
      style="margin-top: 12px"
      :title="`${t('knowledgeBase.knowledgeBase')}: ${curKnowledgeBase.title}(${ownerTierLabel(curKnowledgeBase)})`" hoverable
    >
      <template #header-extra>
        <NTag v-if="!canWrite" size="small" type="warning" class="mr-2">
          {{ t('knowledgeBase.readOnlyKb') }}
        </NTag>
        <NIcon v-if="curKnowledgeBase.ownerType === 'COMPANY'" :component="Building24Regular" />
        <NIcon v-else-if="curKnowledgeBase.ownerType === 'TEAM'" :component="PeopleTeam24Regular" />
        <NIcon v-else :component="Person24Regular" />
      </template>
      {{ curKnowledgeBase.remark }}
    </NCard>
    <NCard style="margin-top: 12px" :title="t('knowledgeBase.generatedKnowledge')" hoverable>
      <div class="flex gap-3 mb-4" :class="[isMobile ? 'flex-col' : 'flex-row justify-between']">
        <div class="flex items-left gap-2">
          <NButton v-if="canWrite" type="primary" size="small" @click="changeEditModal()">
            {{ t('knowledgeBase.addByForm') }}
          </NButton>
          <NButton v-if="canWrite" type="primary" size="small" @click="() => showUploadModal = !showUploadModal">
            {{ t('knowledgeBase.addByFile') }}
          </NButton>
          <NButton v-if="canWrite" type="primary" size="small" @click="changeIndexModal()">
            {{ t('knowledgeBase.indexSelected') }}
            <template v-if="checkedItemRowKeys.length > 0">
              ({{ checkedItemRowKeys.length }}{{ t('knowledgeBase.item') }})
            </template>
          </NButton>
        </div>
        <div class="flex items-center gap-2">
          <NInput v-model:value="searchValue" style="width: 100%" @keyup="onKeyUpSearch" />
          <NButton type="primary" ghost @click="search(1)">
            {{ t('common.search') }}
          </NButton>
        </div>
      </div>
      <NDataTable
        remote :loading="loading" :max-height="tableMaxHeight" :columns="columns" :data="itemList" :pagination="paginationReactive"
        :single-line="false" :bordered="true" :scroll-x="1240" :row-key="rowKey" :checked-row-keys="checkedItemRowKeys"
        @update:checked-row-keys="onHandleCheckedRowKeys" @update:page="onHandlePageChange"
      />
    </NCard>
  </div>

  <NModal
    v-model:show="showItemEditModal" style="width: 90%; max-width: 550px;" preset="card"
    :title="t('knowledgeBase.knowledgeItemAddEdit')"
  >
    <div class="flex flex-col space-y-2">
      <div :class="itemBoxClass">
        <div>{{ t('common.title') }}<span class="text-red-400"> *</span></div>
        <NInput v-model:value="tmpItem.title" maxlength="100" show-count />
      </div>
      <div :class="itemBoxClass">
        <div>{{ t('knowledgeBase.brief') }}</div>
        <NInput v-model:value="tmpItem.brief" type="textarea" show-count :autosize="{ minRows: 2, maxRows: 5 }" />
      </div>
      <div :class="itemBoxClass">
        <div>{{ t('common.content') }}</div>
        <NInput v-model:value="tmpItem.remark" type="textarea" show-count :rows="10" />
      </div>
    </div>
    <template #footer>
      <div class="flex space-x-2 justify-end">
        <NButton type="primary" :disabled="inputStatus" @click="() => { saveOrUpdate() }">
          {{ t('common.confirm') }}
        </NButton>
        <NButton :disabled="submitting" @click="showItemEditModal = false">
          {{ t('common.cancel') }}
        </NButton>
      </div>
    </template>
  </NModal>

  <!-- Upload files -->
  <NModal v-model:show="showUploadModal" style="width: 90%; max-width: 700px;" preset="card" :title="t('knowledgeBase.knowledgeItemUpload')">
    <NCard style="margin-top: 12px" :title="t('knowledgeBase.uploadDocToGenerate')" hoverable>
      <NSpace vertical>
        <NUpload
          ref="uploadRef" multiple v-model:file-list="fileList" directory-dnd
          :action="uploadAction"
          :file-list-style="{ maxHeight: '260px', overflowY: 'auto' }"
          :default-upload="false" :max="20" :headers="headers" @before-upload="onUploadBefore" @finish="onUploadFinish"
          @change="onUploadChange"
        >
          <NUploadDragger>
            <div style="margin-bottom: 12px">
              <NIcon size="48" :depth="3">
                <ArchiveOutline />
              </NIcon>
            </div>
            <NText style="font-size: 16px">
              {{ t('knowledgeBase.clickOrDragToUpload') }}
            </NText>
            <NP depth="3" style="margin: 8px 0 0 0">
              {{ t('knowledgeBase.supportedFileFormats') }}<br>
              {{ t('knowledgeBase.fileSizeLimit') }}
            </NP>
          </NUploadDragger>
        </NUpload>
        <div class="rounded border border-solid border-gray-200 p-3">
          <NFlex vertical size="small">
            <NCheckbox v-model:checked="indexAfterUpload">
              {{ t('knowledgeBase.indexAfterUpload') }}
            </NCheckbox>
            <NCheckboxGroup v-if="indexAfterUpload" v-model:value="indexTypeSelected">
              <NFlex wrap>
                <NCheckbox value="embedding" :label="t('knowledgeBase.vectorize')" />
                <NCheckbox value="graphical" :label="t('knowledgeBase.graphitize')" />
                <NCheckbox value="fulltext" :label="t('knowledgeBase.fulltextIndex')" />
              </NFlex>
            </NCheckboxGroup>
            <NText v-if="indexAfterUpload" depth="3">
              {{ t('knowledgeBase.fulltextExplanation') }}
            </NText>
          </NFlex>
        </div>
        <NFlex>
          <NButton
            type="primary"
            :loading="uploadInFlight"
            :disabled="!fileListLength || (indexAfterUpload && indexTypeSelected.length === 0)"
            @click="onUploadSubmit"
          >
            {{ t('knowledgeBase.uploadAndGenerate') }}
          </NButton>
        </NFlex>
      </NSpace>
    </NCard>
  </NModal>

  <NModal v-model:show="showEmbeddingListModal" style="width: 90%; max-width: 700px;" preset="card" :title="t('knowledgeBase.embeddingList')">
    <ItemEmbeddingList :kb-item-uuid="kbItemUuidForEmbeddingList" />
  </NModal>
  <NModal v-model:show="showGraphModal" class="graph-modal" style="width: 90%; max-width: 700px;" display-directive="show" preset="card" :title="t('knowledgeBase.graphLabel')">
    <ItemGraph :kb-item-uuid="kbItemUuidForGraph" />
  </NModal>
  <NModal v-model:show="showIndexModal" style="width: 90%; max-width:550px" preset="card" :title="t('knowledgeBase.selectIndexType')">
    <NFlex vertical>
      <NAlert :title="t('common.tip')" type="info">
        {{ t('knowledgeBase.indexTypeExplanation') }}
      </NAlert>
      <NCheckboxGroup v-model:value="indexTypeSelected" class="my-2">
        <NFlex vertical>
          <NCheckbox value="embedding" :label="t('knowledgeBase.vectorize')" />
          <NCheckbox value="graphical" :label="t('knowledgeBase.graphitize')" />
          <NCheckbox value="fulltext" :label="t('knowledgeBase.fulltextIndex')" />
        </NFlex>
      </NCheckboxGroup>
      <div class="flex flex-wrap space-x-2">
        <NTag
          v-for="checkedItem in checkedItems" :key="`_${checkedItem.uuid}`" :bordered="false" type="info" closable
          size="small" class="mt-1" @close="removeCheckedItem(checkedItem)"
        >
          {{ checkedItem.title }}
        </NTag>
        <NTag v-if="checkedItems.length === 0" :bordered="false" type="warning" size="small">
          {{ t('knowledgeBase.selectKnowledgeFirst') }}
        </NTag>
      </div>
      <NButton
        type="primary" size="small" :disabled="checkedItems.length === 0 || indexTypeSelected.length === 0"
        @click="textIndexing()"
      >
        {{ t('common.confirm') }}
      </NButton>
    </NFlex>
  </NModal>
  <NModal v-model:show="showFileContentModal" style="width: 90%; max-width: 700px;" preset="card" :title="`${t('workflow.filePreviewTitle')}${previewFileName}`">
    <div style="text-align: center;max-height:700px;overflow-y: auto">
      <div v-if="previewFileUrl && previewMimeType === 'text/plain'">
        {{ previewFileContent }}
      </div>
      <object
        v-if="previewFileUrl && previewMimeType !== 'text/plain' && previewMimeType !== 'application/pdf'"
        :data="previewFileUrl" width="100%" height="90%" :type="previewMimeType"
      >
        <p>{{ t('workflow.browserNotSupportEmbed') }}</p>
      </object>
    </div>
    <template #footer>
      <NButton type="primary" text tag="a" size="small" @click="openFileInNewTab(previewFileUrl)">
        {{ t('workflow.clickToDownload') }}{{ previewFileName }}
      </NButton>
    </template>
  </NModal>
</template>
