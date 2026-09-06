<script setup lang='ts'>
import type { DataTableColumns } from 'naive-ui'
import { computed, h, reactive, ref, watch } from 'vue'
import { NAlert, NButton, NDataTable, NIcon, NInput, NInputGroup, NList, NListItem, NModal, NThing, useDialog, useMessage } from 'naive-ui'
import { Note24Regular } from '@vicons/fluent'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAuthStore } from '@/store'
import { t } from '@/locales'
import api from '@/api'
import { openDeleteDialog } from '@/utils/dialog'

interface Props {
  visible: boolean
}
interface Emit {
  (e: 'update:visible', visible: boolean): void
}
const props = defineProps<Props>()
const emit = defineEmits<Emit>()
const authStore = useAuthStore()
const dialog = useDialog()
const token = ref<string>(authStore.token)
const message = useMessage()
const show = computed({
  get: () => props.visible,
  set: (visible: boolean) => emit('update:visible', visible),
})
const loading = ref(false)
const showModal = ref(false)
const exportLoading = ref(false)
const searchValue = ref<string>('')
// 移动端自适应相关
const { isMobile } = useBasicLayout()
const promptList = ref<Chat.Prompt[]>([])
const paginationReactive = reactive({
  page: 1,
  pageSize: 10,
  itemCount: 0,
})
// 用于添加修改的临时prompt参数
const tmpPromptKey = ref('')
const tmpPromptValue = ref('')
const tmpPromptId = ref(0)
// Modal模式，根据不同模式渲染不同的Modal内容
const modalMode = ref('')
// 添加修改导入都使用一个Modal, 临时修改内容占用tempPromptKey,切换状态前先将内容都清楚
const changeShowModal = (mode: 'add' | 'modify' | 'local_import', selected = { act: '', prompt: '', id: 0 }) => {
  if (mode === 'add') {
    tmpPromptKey.value = ''
    tmpPromptValue.value = ''
  } else if (mode === 'modify') {
    tmpPromptId.value = selected.id
    tmpPromptKey.value = selected.act
    tmpPromptValue.value = selected.prompt
  } else if (mode === 'local_import') {
    tmpPromptKey.value = 'local_import'
    tmpPromptValue.value = ''
  }
  showModal.value = !showModal.value
  modalMode.value = mode
}

// 控制 input 按钮
const inputStatus = computed(() => tmpPromptKey.value.trim().length < 1 || tmpPromptValue.value.trim().length < 1)

// Prompt模板相关操作
const addPromptTemplate = async () => {
  for (const i of promptList.value) {
    if (i.act === tmpPromptKey.value) {
      message.error(t('store.addRepeatTitleTips'))
      return
    }
    if (i.prompt === tmpPromptValue.value) {
      message.error(t('store.addRepeatContentTips', { msg: tmpPromptKey.value }))
      return
    }
  }
  try {
    await api.promptsSave([{ act: tmpPromptKey.value, prompt: tmpPromptValue.value }])
    message.success(t('common.addSuccess'))
    changeShowModal('add')
    await initPrompts()
  } catch (error) {
    console.error('add prompt failed', error)
    message.error(t('common.wrong'))
  }
}

const modifyPromptTemplate = async () => {
  const index = promptList.value.findIndex(item => item.id === tmpPromptId.value)
  if (index < 0) {
    message.error(t('common.wrong'))
    return
  }
  const tempList = promptList.value.filter((_: any, i: number) => i !== index)

  // 搜索有冲突的部分
  for (const i of tempList) {
    if (i.act === tmpPromptKey.value) {
      message.error(t('store.editRepeatTitleTips'))
      return
    }
    if (i.prompt === tmpPromptValue.value) {
      message.error(t('store.editRepeatContentTips', { msg: i.act }))
      return
    }
  }

  try {
    await api.promptEdit(tmpPromptId.value, tmpPromptKey.value, tmpPromptValue.value)
    promptList.value = [...tempList.slice(0, index), { id: tmpPromptId.value, act: tmpPromptKey.value, prompt: tmpPromptValue.value, renderKey: tmpPromptKey.value, renderValue: tmpPromptValue.value }, ...tempList.slice(index)]
    message.success(t('common.editSuccess'))
    changeShowModal('modify')
  } catch (error) {
    console.error('edit prompt failed', error)
    message.error(t('common.wrong'))
  }
}

const deletePromptTemplate = (row: { id: number; act: string; prompt: string }) => {
  openDeleteDialog(dialog, {
    title: t('common.delete'),
    content: t('common.deleteNotRecover'),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.promptDel(row.id)
        promptList.value = promptList.value.filter(item => item.id !== row.id)
        message.success(t('common.deleteSuccess'))
      } catch (error) {
        console.error('delete prompt failed', error)
        message.error(t('common.wrong'))
        return false
      }
    },
  })
}

const importPromptTemplate = async (from = 'online') => {
  try {
    const jsonData = JSON.parse(tmpPromptValue.value)
    let key = ''
    let value = ''
    // 可以扩展加入更多模板字典的key
    if ('key' in jsonData[0]) {
      key = 'key'
      value = 'value'
    } else if ('act' in jsonData[0]) {
      key = 'act'
      value = 'prompt'
    } else {
      // 不支持的字典的key防止导入 以免破坏prompt商店打开
      message.warning('prompt key not supported.')
      throw new Error('prompt key not supported.')
    }
    const prompts = []
    for (const i of jsonData) {
      if (!(key in i) || !(value in i))
        throw new Error(t('store.importError'))
      prompts.push({ act: i[key], prompt: i[value] })
    }
    await api.promptsSave(prompts)
    message.success(t('common.importSuccess'))
    const resp = await api.searchPrompts<PageResponse>(1, 20)
    setResp(1, resp.data)
  } catch (error) {
    console.error(error)
    message.error(t('store.jsonFormatError'))
  }
  if (from === 'local')
    showModal.value = !showModal.value
}

// 模板导出
const exportPromptTemplate = async () => {
  exportLoading.value = true
  try {
    const resp = await api.searchPrompts<PageResponse>(1, 10000)
    const jsonDataStr = JSON.stringify(resp.data)
    const blob = new Blob([jsonDataStr], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'export_prompts.json'
    link.click()
    URL.revokeObjectURL(url)
  } finally {
    exportLoading.value = false
  }
}

// 移动端自适应相关
const renderTemplate = () => {
  const [keyLimit, valueLimit] = isMobile.value ? [10, 30] : [15, 50]

  return promptList.value.map((item: { id: number; act: string; prompt: string }) => {
    return {
      renderKey: item.act.length <= keyLimit ? item.act : `${item.act.substring(0, keyLimit)}...`,
      renderValue: item.prompt.length <= valueLimit ? item.prompt : `${item.prompt.substring(0, valueLimit)}...`,
      act: item.act,
      prompt: item.prompt,
      id: item.id,
    }
  })
}

// table相关
const createColumns = (): DataTableColumns<Chat.Prompt> => {
  return [
    {
      title: t('store.title'),
      key: 'renderKey',
      width: 180,
    },
    {
      title: t('store.description'),
      key: 'renderValue',
    },
    {
      title: t('common.action'),
      key: 'actions',
      width: 132,
      align: 'center',
      render(row) {
        return h('div', { class: 'prompt-row-actions' }, {
          default: () => [h(
            NButton,
            {
              tertiary: true,
              class: 'readable-accent-button',
              size: 'small',
              type: 'info',
              onClick: () => changeShowModal('modify', row),
            },
            { default: () => t('common.edit') },
          ),
          h(
            NButton,
            {
              tertiary: true,
              size: 'small',
              type: 'error',
              onClick: () => deletePromptTemplate(row),
            },
            { default: () => t('common.delete') },
          ),
          ],
        })
      },
    },
  ]
}

const columns = createColumns()

const dataSource = computed(() => {
  return renderTemplate()
})

async function handlePageChange(currentPage: number) {
  loading.value = true
  try {
    const resp = await api.searchPrompts<PageResponse>(currentPage, paginationReactive.pageSize, searchValue.value)
    setResp(currentPage, resp.data)
  } catch (error) {
    console.error('load prompts failed', error)
    message.error(t('common.wrong'))
  } finally {
    loading.value = false
  }
}

async function search(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    clickSearch()
  }
}

async function clickSearch() {
  try {
    const resp = await api.searchPrompts<PageResponse>(1, paginationReactive.pageSize, searchValue.value)
    setResp(1, resp.data)
  } catch (error) {
    console.error('search prompts failed', error)
    message.error(t('common.wrong'))
  }
}

async function initPrompts() {
  try {
    const resp = await api.searchPrompts<PageResponse>(1, paginationReactive.pageSize)
    setResp(1, resp.data)
  } catch (error) {
    console.error('init prompts failed', error)
    message.error(t('common.wrong'))
  }
}

function setResp(currentPage: number, data: PageResponse) {
  promptList.value = data.records
  paginationReactive.page = currentPage
  paginationReactive.itemCount = data.total
}

watch(
  () => authStore.token,
  (nextToken) => {
    token.value = nextToken
    if (nextToken)
      initPrompts()
  },
  { immediate: true },
)
</script>

<template>
  <NModal
    v-model:show="show"
    class="prompt-store-modal"
    preset="card"
    :title="t('store.siderButton')"
    :style="{
      width: isMobile ? 'calc(100vw - 24px)' : 'min(920px, calc(100vw - 48px))',
      maxHeight: 'calc(100vh - 24px)',
    }"
  >
    <div class="prompt-store-shell">
      <div class="prompt-store-toolbar">
        <div class="prompt-toolbar-actions">
          <NButton type="primary" size="small" @click="changeShowModal('add')">
            {{ t('common.add') }}
          </NButton>
          <NButton size="small" @click="changeShowModal('local_import')">
            {{ t('common.import') }}
          </NButton>
          <NButton size="small" :loading="exportLoading" @click="exportPromptTemplate()">
            {{ t('common.export') }}
          </NButton>
        </div>
        <div class="prompt-search">
          <NInputGroup>
            <NInput v-model:value="searchValue" style="width: 100%" @keyup="search" />
            <NButton ghost @click="clickSearch">
              {{ t('common.search') }}
            </NButton>
          </NInputGroup>
        </div>
      </div>
      <div class="prompt-store-results">
        <NDataTable
          v-if="!isMobile"
          class="prompt-table"
          remote
          flex-height
          :loading="loading"
          :columns="columns"
          :data="dataSource"
          :pagination="paginationReactive"
          :bordered="false"
          @update:page="handlePageChange"
        />
        <NList v-else class="prompt-mobile-list">
          <NListItem v-for="item of dataSource" :key="item.id">
            <NThing :title="item.renderKey" :description="item.renderValue" />
            <template #suffix>
              <div class="prompt-mobile-actions">
                <NButton class="readable-accent-button" tertiary size="small" type="info" @click="changeShowModal('modify', item)">
                  {{ t('common.edit') }}
                </NButton>
                <NButton tertiary size="small" type="error" @click="deletePromptTemplate(item)">
                  {{ t('common.delete') }}
                </NButton>
              </div>
            </template>
          </NListItem>
        </NList>
      </div>
    </div>
  </NModal>

  <NModal
    v-model:show="showModal"
    class="prompt-editor-modal"
    preset="card"
    :title="modalMode === 'add' ? t('common.add') : modalMode === 'modify' ? t('common.edit') : t('common.import')"
    :style="{
      width: isMobile ? 'calc(100vw - 24px)' : 'min(640px, calc(100vw - 48px))',
      maxHeight: 'calc(100vh - 24px)',
    }"
  >
    <div class="prompt-editor-body">
      <template v-if="modalMode === 'add' || modalMode === 'modify'">
        <label class="prompt-field-label">{{ t('store.title') }}</label>
        <NInput v-model:value="tmpPromptKey" />
        <label class="prompt-field-label">{{ t('store.description') }}</label>
        <NInput v-model:value="tmpPromptValue" class="prompt-editor-textarea" type="textarea" />
      </template>
      <template v-if="modalMode === 'local_import'">
        <NInput
          v-model:value="tmpPromptValue"
          class="prompt-import-textarea"
          :placeholder="t('store.importPlaceholder')"
          type="textarea"
        />
        <NAlert class="prompt-import-example" :title="t('store.example')">
          <template #icon>
            <NIcon>
              <Note24Regular />
            </NIcon>
          </template>
          [<br>
          &nbsp;&nbsp;{<br>
          &nbsp;&nbsp;&nbsp;&nbsp;"act":"充当英语翻译和改进者",<br>
          &nbsp;&nbsp;&nbsp;&nbsp;"prompt": "我希望你能担任英语翻译、拼写校对和修辞改进的角色。"<br>
          &nbsp;&nbsp;}<br>
          ]
        </NAlert>
      </template>
    </div>
    <template #footer>
      <NButton
        v-if="modalMode === 'add' || modalMode === 'modify'"
        block
        type="primary"
        :disabled="inputStatus"
        @click="() => { modalMode === 'add' ? addPromptTemplate() : modifyPromptTemplate() }"
      >
        {{ t('common.confirm') }}
      </NButton>
      <NButton
        v-else
        block
        type="primary"
        :disabled="inputStatus"
        @click="() => { importPromptTemplate('local') }"
      >
        {{ t('common.import') }}
      </NButton>
    </template>
  </NModal>
</template>

<style scoped>
.prompt-store-shell {
  display: flex;
  height: min(580px, calc(100vh - 170px));
  min-height: 360px;
  flex-direction: column;
  gap: 12px;
}

.prompt-store-toolbar {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.prompt-toolbar-actions,
.prompt-row-actions,
.prompt-mobile-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.prompt-toolbar-actions {
  flex-wrap: wrap;
}

.prompt-search {
  width: min(340px, 100%);
  flex: 0 1 340px;
}

.prompt-store-results {
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

.prompt-table,
.prompt-mobile-list {
  height: 100%;
}

.prompt-mobile-list {
  overflow-y: auto;
}

.prompt-mobile-actions {
  flex-direction: column;
}

.prompt-editor-body {
  display: flex;
  max-height: calc(100vh - 190px);
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  padding-right: 2px;
}

.prompt-field-label {
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.prompt-field-label:not(:first-child) {
  margin-top: 4px;
}

.prompt-editor-textarea {
  height: min(42vh, 360px);
  min-height: 180px;
}

.prompt-import-textarea {
  height: min(34vh, 280px);
  min-height: 150px;
}

.prompt-import-example {
  flex: 0 0 auto;
}

@media (max-width: 767px) {
  .prompt-store-shell {
    height: calc(100vh - 150px);
    min-height: 300px;
  }

  .prompt-store-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .prompt-search {
    width: 100%;
    flex-basis: auto;
  }

  .prompt-toolbar-actions > :deep(*) {
    flex: 1;
  }

  .prompt-editor-textarea,
  .prompt-import-textarea {
    min-height: 140px;
  }
}
</style>
