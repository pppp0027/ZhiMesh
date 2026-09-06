<script setup lang='ts'>
import type { DataTableColumns } from 'naive-ui'
import { computed, h, reactive, ref, watch } from 'vue'
import { NBreadcrumb, NBreadcrumbItem, NButton, NCollapse, NCollapseItem, NDataTable, NIcon, NInput, NInputNumber, NModal, NRadio, NRadioGroup, NSelect, NTooltip, useDialog, useMessage } from 'naive-ui'
import { RouterLink, useRouter } from 'vue-router'
import { QuestionCircle16Regular } from '@vicons/fluent'
import { ApiKeyModal } from '@/components/common'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore, useAuthStore, useKbStore } from '@/store'
import { knowledgeBaseEmptyInfo } from '@/utils/functions'
import { SPLIT_STRATEGY, TOKEN_ESTIMATOR } from '@/utils/constant'
import { t } from '@/locales'
import api from '@/api'
import { openDeleteDialog } from '@/utils/dialog'

const router = useRouter()
const dialog = useDialog()
const ms = useMessage()
const appStore = useAppStore()
const loading = ref(false)
const submitting = ref(false)
const showModal = ref(false)
const infoList = ref<KnowledgeBase.Info[]>([])
const rerankModelOptions = ref<Array<{
  label: string
  value: string
}>>([])
const paginationReactive = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
})
const searchValue = ref<string>('')
const tmpKb = reactive<KnowledgeBase.Info>(knowledgeBaseEmptyInfo())
// 控制 input 按钮
const inputStatus = computed(() => tmpKb.title.trim().length < 1 || submitting.value)
const { isMobile } = useBasicLayout()
const authStore = useAuthStore()
const kbStore = useKbStore()
const token = ref<string>(authStore.token)
const itemBoxClass = 'space-y-1'
const showApiKeyModal = ref(false)
const activeKb = ref<KnowledgeBase.Info>(knowledgeBaseEmptyInfo())
let searchGeneration = 0

const changeShowModal = (selected: KnowledgeBase.Info = knowledgeBaseEmptyInfo()) => {
  Object.assign(tmpKb, selected)
  tmpKb.rerankModelId = String(selected.rerankModelId || '0')
  tmpKb.rerankTopN = selected.rerankTopN || 5
  showModal.value = !showModal.value
  if (!tmpKb.ingestModelName) {
    const firstEnableModel = appStore.llms.find((item: { enable: any }) => item.enable)
    if (firstEnableModel) {
      tmpKb.ingestModelName = firstEnableModel.modelName
      tmpKb.ingestModelId = firstEnableModel.modelId
    }
  } else {
    tmpKb.ingestModelName = appStore.llms.find(item => item.modelName === tmpKb.ingestModelName)?.modelName || ''
  }
  if (!tmpKb.ingestTokenEstimator)
    tmpKb.ingestTokenEstimator = TOKEN_ESTIMATOR[0].value
}
// table相关
const createColumns = (): DataTableColumns<KnowledgeBase.Info> => {
  return [
    {
      title: t('common.title'),
      key: 'title',
      width: 200,
      render(row) {
        return h(
          RouterLink,
          {
            class: 'hljs-link',
            to: {
              name: 'KnowledgeBaseManageDetail',
              params: {
                kbUuid: row.uuid,
              },
            },
          },
          { default: () => row.title },
        )
      },
    },
    {
      title: t('common.description'),
      key: 'remark',
    },
    {
      title: t('knowledgeBase.isPublic'),
      key: 'isPublic',
      width: 100,
      render(row) {
        return row.isPublic ? t('common.yes') : t('common.no')
      },
    },
    {
      title: t('knowledgeBase.isStrict'),
      key: 'isStrict',
      width: 100,
      render(row) {
        return row.isStrict ? t('common.yes') : t('common.no')
      },
    },
    {
      title: t('common.action'),
      key: 'actions',
      width: 100,
      align: 'center',
      render(row) {
        return h('div', { class: 'grid gap-1' }, {
          default: () => [
            h('div', { class: 'flex gap-1' }, [
              h(
                NButton,
                {
                  tertiary: true,
                  class: 'readable-accent-button',
                  size: 'tiny',
                  type: 'info',
                  onClick: () => router.push({ name: 'KnowledgeBaseManageDetail', params: { kbUuid: row.uuid } }),
                },
                { default: () => t('common.view') },
              ),
              h(
                NButton,
                {
                  tertiary: true,
                  class: 'readable-accent-button',
                  size: 'tiny',
                  type: 'info',
                  onClick: () => {
                    activeKb.value = row
                    showApiKeyModal.value = true
                  },
                },
                { default: () => t('extApi.apiAccess') },
              ),
            ]),
            h('div', { class: 'flex gap-1' }, [
              h(
                NButton,
                {
                  tertiary: true,
                  class: 'readable-accent-button',
                  size: 'tiny',
                  type: 'info',
                  onClick: () => changeShowModal(row),
                },
                { default: () => t('common.edit') },
              ),
              h(
                NButton,
                {
                  tertiary: true,
                  size: 'tiny',
                  type: 'error',
                  onClick: () => deleteKb(row),
                },
                { default: () => t('common.delete') },
              ),
            ]),
          ],
        })
      },
    },
  ]
}

const columns = createColumns()

async function onHandlePageChange(currentPage: number) {
  search(currentPage)
}

async function onKeyUpSearch(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    search(1)
  }
}

async function search(currentPage: number) {
  const requestId = ++searchGeneration
  loading.value = true
  try {
    const resp = await api.knowledgeBaseSearchMine<KnowledgeBase.InfoListResp>(searchValue.value, currentPage, paginationReactive.pageSize)
    if (requestId !== searchGeneration)
      return
    infoList.value = resp.data.records
    paginationReactive.page = currentPage
    paginationReactive.itemCount = resp.data.total
  } catch (error) {
    if (requestId !== searchGeneration)
      return
    console.error('knowledge base search failed', error)
    ms.error(t('common.wrong'))
  } finally {
    if (requestId === searchGeneration)
      loading.value = false
  }
}

async function saveOrUpdateKb() {
  if (tmpKb.ingestSplitStrategy === 'custom' && !tmpKb.ingestCustomSeparator?.trim()) {
    ms.warning(t('knowledgeBase.customSeparatorRequired'))
    return
  }
  try {
    submitting.value = true
    // The user workspace only supports private or public libraries. Do not send
    // administration-only flags even if a stale browser state contains them.
    const userWorkspaceKb = { ...tmpKb } as KnowledgeBase.Info & {
      isSystem?: boolean
      isEnabled?: boolean
    }
    delete userWorkspaceKb.isSystem
    delete userWorkspaceKb.isEnabled
    const res = await api.knowledgeBaseSaveOrUpdate<KnowledgeBase.Info>(userWorkspaceKb)
    if (tmpKb.id && tmpKb.id !== '0') {
      const hit = infoList.value.find(item => item.id === tmpKb.id)
      if (hit)
        Object.assign(hit, res.data)
    } else {
      infoList.value.push(res.data)
    }
    kbStore.upsertMyKbInfo(res.data)
    Object.assign(tmpKb, res.data)

    kbStore.setReloadKbInfosSignal(true)
    await search(1)
    showModal.value = false
  } catch (error: any) {
    console.error('save knowledge base failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    submitting.value = false
  }
}

function deleteKb(row: KnowledgeBase.Info) {
  openDeleteDialog(dialog, {
    title: t('knowledgeBase.deleteConfirmTitle'),
    content: t('knowledgeBase.deleteKbConfirm', { title: row.title }),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.knowledgeBaseDelete(row.uuid)
        const index = infoList.value.findIndex(item => item.uuid === row.uuid)
        if (index !== -1)
          infoList.value.splice(index, 1)
        paginationReactive.itemCount = Math.max(0, paginationReactive.itemCount - 1)
        kbStore.deleteKbInfo(row.uuid)
        kbStore.setReloadKbInfosSignal(true)
        ms.success(t('common.deleteSuccess'))
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

function onModelChange(modelName: string) {
  tmpKb.ingestModelName = modelName
  tmpKb.ingestModelId = appStore.llms.find(item => item.modelName === modelName)?.modelId || ''
}

function onTokenEstimatorChange(tokenEstimator: string) {
  tmpKb.ingestTokenEstimator = tokenEstimator
}

async function loadRerankModels() {
  try {
    const resp = await api.loadRerankModels<Array<{
      modelId: string | number
      modelName: string
      modelTitle?: string
      modelPlatform: string
    }>>()
    rerankModelOptions.value = [
      {
        label: t('knowledgeBase.rerankDisabled'),
        value: '0',
      },
      ...resp.data.map(model => ({
        label: `${model.modelTitle || model.modelName} (${model.modelPlatform})`,
        value: String(model.modelId),
      })),
    ]
  } catch (error) {
    console.error('load rerank models failed', error)
    rerankModelOptions.value = [{ label: t('knowledgeBase.rerankDisabled'), value: '0' }]
  }
}

async function initData() {
  await Promise.all([
    search(1),
    loadRerankModels(),
  ])
}

watch(
  () => authStore.token,
  (nextToken) => {
    token.value = nextToken
    if (nextToken)
      initData()
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex flex-col w-full p-4">
    <NBreadcrumb separator=">">
      <NBreadcrumbItem href="/">
        {{ t('common.home') }}
      </NBreadcrumbItem>
      <NBreadcrumbItem :href="`#/qa/${kbStore.activeKbUuid}`">
        {{ t('menu.knowledgeBase') }}
      </NBreadcrumbItem>
      <NBreadcrumbItem :clickable="false">
        {{ t('knowledgeBase.myKnowledgeBase') }}
      </NBreadcrumbItem>
    </NBreadcrumb>
    <div class="flex gap-3 mb-2 mt-1" :class="[isMobile ? 'flex-col' : 'flex-row justify-between']">
      <div class="flex items-center space-x-4">
        <NButton type="primary" size="small" @click="changeShowModal()">
          {{ t('common.add') }}
        </NButton>
      </div>
      <div class="flex justify-between">
        <NInput v-model:value="searchValue" style="width: 100%" @keyup="onKeyUpSearch" />
        <NButton type="primary" ghost @click="search(1)">
          {{ t('common.search') }}
        </NButton>
      </div>
    </div>
    <NDataTable
      remote :loading="loading" :columns="columns" :data="infoList" :pagination="paginationReactive"
      :single-line="false" :bordered="true" @update:page="onHandlePageChange"
    />
  </div>

  <NModal
    v-model:show="showModal" :title="tmpKb.id === '0' ? t('common.newCreate') : t('common.edit')" style="width: 90%; max-width: 700px; "
    preset="card"
  >
    <div class="max-h-[600px] overflow-y-auto pr-2">
      <div class="flex flex-col space-y-2">
        <div :class="itemBoxClass">
          <div>{{ t('common.title') }}<span class="text-red-400"> *</span></div>
          <NInput v-model:value="tmpKb.title" maxlength="100" :placeholder="t('store.title')" show-count />
        </div>
        <div :class="itemBoxClass">
          <div>{{ t('common.description') }}</div>
          <NInput
            v-model:value="tmpKb.remark" type="textarea" :placeholder="t('store.description')" maxlength="500"
            show-count :autosize="{ minRows: 3, maxRows: 10 }"
          />
        </div>
        <div :class="itemBoxClass">
          <div>{{ t('knowledgeBase.isPublic') }}</div>
          <NRadioGroup v-model:value="tmpKb.isPublic" name="radiogroup">
            <NRadio key="public_yes" :value="true">
              {{ t('common.public') }}
            </NRadio>
            <NRadio key="public_no" :value="false">
              {{ t('common.private') }}
            </NRadio>
          </NRadioGroup>
        </div>
        <div :class="itemBoxClass">
          <div>
            {{ t('knowledgeBase.strictMode') }}
            <NTooltip trigger="hover">
              <template #trigger>
                <NIcon style="padding-top: 0.1rem">
                  <QuestionCircle16Regular />
                </NIcon>
              </template>
              <div>{{ t('knowledgeBase.strictModeDescShort') }}</div>
              <div>{{ t('knowledgeBase.looseModeDescShort') }}</div>
            </NTooltip>
          </div>
          <NRadioGroup v-model:value="tmpKb.isStrict" name="radiogroup">
            <NRadio key="strict_yes" :value="true">
              {{ t('common.yes') }}
            </NRadio>
            <NRadio key="strict_no" :value="false">
              {{ t('common.no') }}
            </NRadio>
          </NRadioGroup>
        </div>
        <NCollapse>
          <NCollapseItem :title="t('knowledgeBase.docIndexSettingVector')">
            <div class="flex flex-col space-y-2" :class="itemBoxClass">
              <div>
                <div>{{ t('knowledgeBase.docOverlapCount') }}</div>
                <NInputNumber v-model:value="tmpKb.ingestMaxOverlap" />
              </div>
              <div>
                <div>{{ t('knowledgeBase.splitStrategy') }}</div>
                <NSelect v-model:value="tmpKb.ingestSplitStrategy" :options="SPLIT_STRATEGY" />
              </div>
              <div v-if="tmpKb.ingestSplitStrategy === 'custom'">
                <div>{{ t('knowledgeBase.customSeparator') }}</div>
                <NInput v-model:value="tmpKb.ingestCustomSeparator" :placeholder="t('knowledgeBase.customSeparatorPlaceholder')" />
              </div>
              <div>
                <div>{{ t('knowledgeBase.maxSegmentSize') }}</div>
                <NInputNumber v-model:value="tmpKb.ingestMaxSegmentSize" :min="100" />
              </div>
              <div>
                <div>
                  {{ t('knowledgeBase.tokenCounter') }}
                </div>
                <NSelect
                  :value="tmpKb.ingestTokenEstimator" :options="TOKEN_ESTIMATOR"
                  :on-update:value="onTokenEstimatorChange"
                />
              </div>
            </div>
          </NCollapseItem>
          <NCollapseItem :title="t('knowledgeBase.docIndexSettingGraph')">
            <div class="flex flex-col space-y-2">
              <div :class="itemBoxClass">
                <div>
                  {{ t('knowledgeBase.graphExtractionModel') }}
                  <NTooltip trigger="hover">
                    <template #trigger>
                      <NIcon style="padding-top: 0.1rem">
                        <QuestionCircle16Regular />
                      </NIcon>
                    </template>
                    <div>{{ t('knowledgeBase.modelExtractTip') }}</div>
                  </NTooltip>
                </div>
                <NSelect :value="tmpKb.ingestModelName" :options="appStore.llms" :on-update:value="onModelChange" />
              </div>
            </div>
          </NCollapseItem>
          <NCollapseItem :title="t('knowledgeBase.docRecallSetting')">
            <div class="flex flex-col space-y-2">
              <div :class="itemBoxClass">
                <div>{{ t('knowledgeBase.docRecallMaxCount') }}</div>
                <NInputNumber v-model:value="tmpKb.retrieveMaxResults" />
              </div>
              <div :class="itemBoxClass">
                <div>{{ t('knowledgeBase.docRecallMinScore') }}</div>
                <NInputNumber v-model:value="tmpKb.retrieveMinScore" :precision="1" :min="0" :max="1" />
              </div>
              <div :class="itemBoxClass">
                <div>{{ t('knowledgeBase.rerankModel') }}</div>
                <NSelect
                  v-model:value="tmpKb.rerankModelId"
                  :options="rerankModelOptions"
                  :placeholder="t('knowledgeBase.rerankModelPlaceholder')"
                />
              </div>
              <div v-if="Number(tmpKb.rerankModelId) > 0" :class="itemBoxClass">
                <div>{{ t('knowledgeBase.rerankTopN') }}</div>
                <NInputNumber v-model:value="tmpKb.rerankTopN" :min="1" :max="10" />
              </div>
              <div class="text-xs opacity-60">
                {{ t('knowledgeBase.rerankTip') }}
              </div>
              <div :class="itemBoxClass">
                <div>{{ t('knowledgeBase.graphHopDepth') }}</div>
                <NInputNumber v-model:value="tmpKb.graphHopDepth" :min="1" :max="2" :precision="0" />
              </div>
            </div>
          </NCollapseItem>
          <NCollapseItem :title="t('knowledgeBase.llmParamSetting')">
            <div class="flex flex-col space-y-2">
              <div :class="itemBoxClass">
                <div>{{ t('knowledgeBase.systemPromptRole') }}</div>
                <NInput
                  v-model:value="tmpKb.querySystemMessage" type="textarea"
                  :autosize="{ minRows: 2, maxRows: 5 }"
                />
              </div>
              <div :class="itemBoxClass">
                <div>{{ t('knowledgeBase.responseCreativity') }}</div>
                <NInputNumber v-model:value="tmpKb.queryLlmTemperature" :precision="1" :min="0" :max="1" />
              </div>
            </div>
          </NCollapseItem>
        </NCollapse>
      </div>
    </div>
    <template #footer>
      <div class="flex space-x-2 justify-end">
        <NButton type="primary" size="small" :disabled="inputStatus" @click="() => { saveOrUpdateKb() }">
          {{ t('common.confirm') }}
        </NButton>
        <NButton size="small" :disabled="inputStatus" @click="() => { showModal = false }">
          {{ t('common.cancel') }}
        </NButton>
      </div>
    </template>
  </NModal>

  <ApiKeyModal v-model:show="showApiKeyModal" type="knowledge" :uuid="activeKb.uuid" :title="activeKb.title" />
</template>
