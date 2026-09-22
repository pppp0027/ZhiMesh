<script setup lang='ts'>
import type { DataTableColumns, DropdownOption } from 'naive-ui'
import { computed, h, reactive, ref, watch } from 'vue'
import { NBreadcrumb, NBreadcrumbItem, NButton, NCollapse, NCollapseItem, NDataTable, NDropdown, NIcon, NInput, NInputNumber, NModal, NRadio, NRadioButton, NRadioGroup, NSelect, NTag, NTooltip, useDialog, useMessage } from 'naive-ui'
import { RouterLink, useRoute, useRouter } from 'vue-router'
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
const route = useRoute()
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

// 三级归属：本页管理「我的个人库」与「我所在团队的团队库」两个分区；
// 企业库由管理端维护，不在此出现。
const ownerScope = ref<'mine' | 'team'>('mine')
// 详情页返回时通过 ?scope=team 保持原分区
if (route.query.scope === 'team')
  ownerScope.value = 'team'
const myTeams = ref<Team.Info[]>([])
// 新建/编辑弹窗中的归属选择（编辑时只读展示）
const tmpOwnerType = ref<'PERSONAL' | 'TEAM'>('PERSONAL')
const tmpTeamUuid = ref<string>('')
// 转移弹窗状态
const showTransferModal = ref(false)
const transferKb = ref<KnowledgeBase.Info | null>(null)
const transferTeamUuid = ref<string>('')
const transferSubmitting = ref(false)

const teamOptions = computed(() => myTeams.value.map(team => ({
  label: team.name,
  value: team.uuid,
})))

async function loadMyTeams() {
  try {
    const resp = await api.teamMyLite<Team.Info[]>()
    myTeams.value = resp.data || []
  } catch (error) {
    console.error('load my teams failed', error)
    myTeams.value = []
  }
}

const changeShowModal = (selected: KnowledgeBase.Info = knowledgeBaseEmptyInfo()) => {
  Object.assign(tmpKb, selected)
  tmpKb.rerankModelId = String(selected.rerankModelId || '0')
  tmpKb.rerankTopN = selected.rerankTopN || 5
  const isNew = !selected.uuid || selected.uuid === 'default'
  tmpOwnerType.value = isNew && ownerScope.value === 'team' ? 'TEAM' : 'PERSONAL'
  tmpTeamUuid.value = selected.teamUuid || ''
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
      title: t('knowledgeBase.ownership'),
      key: 'ownerType',
      width: 110,
      render(row) {
        // 三档归属统一 NTag：个人 default / 团队 info / 企业 warning
        if (row.ownerType === 'TEAM')
          return h(NTag, { size: 'small', type: 'info', bordered: false }, { default: () => row.teamName || t('knowledgeBase.ownerTypeTeam') })
        if (row.ownerType === 'COMPANY')
          return h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => t('knowledgeBase.ownerTypeCompany') })
        return h(NTag, { size: 'small', type: 'default', bordered: false }, { default: () => t('knowledgeBase.ownerTypePersonal') })
      },
    },
    {
      title: t('knowledgeBase.isStrict'),
      key: 'isStrict',
      width: 100,
      render(row) {
        return h(NTag, { size: 'small', type: row.isStrict ? 'success' : 'default', bordered: false }, { default: () => row.isStrict ? t('common.yes') : t('common.no') })
      },
    },
    {
      title: t('common.action'),
      key: 'actions',
      width: 200,
      align: 'center',
      render(row) {
        // Settings change and deletion are management-grade; the backend
        // resolves the effective access level per tier. Actions the current
        // user lacks permission for are simply not rendered.
        const canManage = row.accessLevel === 'MANAGE'
        const canTransferToTeam = row.ownerType !== 'TEAM' && row.ownerType !== 'COMPANY' && myTeams.value.length > 0
        const canTransferToPersonal = row.ownerType === 'TEAM' && row.myRole === 'OWNER'
        const moreOptions: DropdownOption[] = []
        if (canManage) {
          moreOptions.push({ label: t('extApi.apiAccess'), key: 'api' })
          moreOptions.push({ label: t('common.delete'), key: 'delete' })
        }
        if (canTransferToTeam)
          moreOptions.push({ label: t('knowledgeBase.transferToTeam'), key: 'transferTeam' })
        if (canTransferToPersonal)
          moreOptions.push({ label: t('knowledgeBase.transferToPersonal'), key: 'transferPersonal' })
        return h('div', { class: 'flex items-center justify-center gap-1' }, {
          default: () => [
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
            ...(canManage
              ? [h(
                NButton,
                {
                  tertiary: true,
                  class: 'readable-accent-button',
                  size: 'tiny',
                  type: 'info',
                  onClick: () => changeShowModal(row),
                },
                { default: () => t('common.edit') },
              )]
              : []),
            ...(moreOptions.length > 0
              ? [h(
                NDropdown,
                {
                  trigger: 'click',
                  options: moreOptions,
                  onSelect: (key: string | number) => onKbActionSelect(key, row),
                },
                { default: () => h(NButton, { tertiary: true, class: 'readable-accent-button', size: 'tiny', type: 'info' }, { default: () => t('common.more') }) },
              )]
              : []),
          ],
        })
      },
    },
  ]
}

function onKbActionSelect(key: string | number, row: KnowledgeBase.Info) {
  switch (key) {
    case 'api':
      activeKb.value = row
      showApiKeyModal.value = true
      break
    case 'delete':
      deleteKb(row)
      break
    case 'transferTeam':
      transferKb.value = row
      transferTeamUuid.value = ''
      showTransferModal.value = true
      break
    case 'transferPersonal':
      transferToPersonal(row)
      break
  }
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
    const resp = ownerScope.value === 'team'
      ? await api.knowledgeBaseSearchTeam<KnowledgeBase.InfoListResp>(searchValue.value, currentPage, paginationReactive.pageSize)
      : await api.knowledgeBaseSearchMine<KnowledgeBase.InfoListResp>(searchValue.value, currentPage, paginationReactive.pageSize)
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
  if (tmpOwnerType.value === 'TEAM' && !tmpTeamUuid.value) {
    ms.warning(t('knowledgeBase.teamSelectRequired'))
    return
  }
  try {
    submitting.value = true
    // Defensive strip: the user workspace must not send administration-only
    // flags (isSystem/isEnabled) even if stale browser state contains them.
    const userWorkspaceKb = { ...tmpKb } as KnowledgeBase.Info & {
      isSystem?: boolean
      isEnabled?: boolean
      ownerType?: string
      teamUuid?: string
    }
    delete userWorkspaceKb.isSystem
    delete userWorkspaceKb.isEnabled
    // Ownership applies on create only; the backend ignores it on edit.
    userWorkspaceKb.ownerType = tmpOwnerType.value
    userWorkspaceKb.teamUuid = tmpOwnerType.value === 'TEAM' ? tmpTeamUuid.value : undefined
    const res = await api.knowledgeBaseSaveOrUpdate<KnowledgeBase.Info>(userWorkspaceKb)
    if (tmpKb.id && tmpKb.id !== '0') {
      const hit = infoList.value.find(item => item.id === tmpKb.id)
      if (hit)
        Object.assign(hit, res.data)
    } else {
      infoList.value.push(res.data)
    }
    kbStore.upsertKbInfo(res.data)
    Object.assign(tmpKb, res.data)

    kbStore.setReloadKbInfosSignal(true)
    // 新建库归属与当前分区不一致时跳到归属分区，保证新库保存后可见
    const resultScope = res.data.ownerType === 'TEAM' ? 'team' : 'mine'
    if (ownerScope.value !== resultScope) {
      ownerScope.value = resultScope
      // ownerScope watcher 已触发 search(1)
    } else {
      await search(1)
    }
    showModal.value = false
  } catch (error: any) {
    console.error('save knowledge base failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    submitting.value = false
  }
}

async function confirmTransferToTeam() {
  if (!transferKb.value || !transferTeamUuid.value) {
    ms.warning(t('knowledgeBase.teamSelectRequired'))
    return
  }
  try {
    transferSubmitting.value = true
    await api.knowledgeBaseTransfer(transferKb.value.uuid, 'TEAM', transferTeamUuid.value)
    ms.success(t('knowledgeBase.transferToTeam'))
    showTransferModal.value = false
    kbStore.setReloadKbInfosSignal(true)
    await search(1)
  } catch (error: any) {
    console.error('transfer knowledge base failed', error)
    ms.error(error?.message || t('common.wrong'))
  } finally {
    transferSubmitting.value = false
  }
}

function transferToPersonal(row: KnowledgeBase.Info) {
  // 非破坏性操作：普通确认弹窗（主色确认键），不借用红色删除弹窗
  const instance = dialog.create({
    title: t('knowledgeBase.transfer'),
    content: t('knowledgeBase.transferToPersonalTip'),
    positiveText: t('common.confirm'),
    negativeText: t('common.cancel'),
    async onPositiveClick() {
      if (instance.loading)
        return false
      instance.loading = true
      try {
        await api.knowledgeBaseTransfer(row.uuid, 'PERSONAL')
        ms.success(t('knowledgeBase.transferToPersonal'))
        kbStore.setReloadKbInfosSignal(true)
        await search(1)
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      } finally {
        instance.loading = false
      }
    },
  })
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
    loadMyTeams(),
  ])
}

watch(ownerScope, () => {
  search(1)
})

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
        {{ ownerScope === 'team' ? t('team.myTeams') : t('knowledgeBase.myKnowledgeBase') }}
      </NBreadcrumbItem>
    </NBreadcrumb>
    <div class="flex gap-3 mb-2 mt-1" :class="[isMobile ? 'flex-col' : 'flex-row justify-between']">
      <div class="flex items-center space-x-4">
        <NRadioGroup v-model:value="ownerScope" name="owner-scope" size="small">
          <NRadioButton value="mine">
            {{ t('knowledgeBase.ownerTypePersonal') }}
          </NRadioButton>
          <NRadioButton value="team">
            {{ t('knowledgeBase.ownerTypeTeam') }}
          </NRadioButton>
        </NRadioGroup>
        <NButton type="primary" size="small" @click="changeShowModal()">
          {{ t('common.add') }}
        </NButton>
      </div>
      <div class="flex justify-between gap-2">
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
          <div>{{ t('knowledgeBase.ownership') }}</div>
          <!-- 归属仅创建时可选；编辑时只读展示，变更归属走“转移”操作 -->
          <template v-if="String(tmpKb.id) !== '0'">
            <NTag v-if="tmpKb.ownerType === 'TEAM'" size="small" type="info" :bordered="false">
              {{ tmpKb.teamName || t('knowledgeBase.ownerTypeTeam') }}
            </NTag>
            <NTag v-else-if="tmpKb.ownerType === 'COMPANY'" size="small" type="warning" :bordered="false">
              {{ t('knowledgeBase.ownerTypeCompany') }}
            </NTag>
            <NTag v-else size="small" type="default" :bordered="false">
              {{ t('knowledgeBase.ownerTypePersonal') }}
            </NTag>
          </template>
          <template v-else>
            <NRadioGroup v-model:value="tmpOwnerType" name="kb-owner-type" size="small">
              <NRadio value="PERSONAL">
                {{ t('knowledgeBase.ownerTypePersonal') }}
              </NRadio>
              <NRadio value="TEAM">
                {{ t('knowledgeBase.ownerTypeTeam') }}
              </NRadio>
            </NRadioGroup>
            <div v-if="tmpOwnerType === 'TEAM'" class="space-y-1">
              <NSelect
                v-model:value="tmpTeamUuid" :options="teamOptions"
                :placeholder="t('knowledgeBase.transferSelectTeam')"
              />
              <div v-if="myTeams.length === 0" class="text-xs text-red-400">
                {{ t('knowledgeBase.noTeamHint') }}
              </div>
            </div>
          </template>
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

  <NModal
    v-model:show="showTransferModal" :title="t('knowledgeBase.transferToTeam')"
    style="width: 90%; max-width: 440px;" preset="card"
  >
    <div class="flex flex-col space-y-2">
      <div class="text-sm">
        {{ transferKb?.title }}
      </div>
      <div>{{ t('knowledgeBase.transferSelectTeam') }}</div>
      <NSelect
        v-model:value="transferTeamUuid" :options="teamOptions"
        :placeholder="t('knowledgeBase.transferSelectTeam')"
      />
      <div class="text-xs opacity-60">
        {{ t('knowledgeBase.transferToTeamTip') }}
      </div>
    </div>
    <template #footer>
      <div class="flex space-x-2 justify-end">
        <NButton type="primary" size="small" :disabled="transferSubmitting" @click="confirmTransferToTeam">
          {{ t('common.confirm') }}
        </NButton>
        <NButton size="small" :disabled="transferSubmitting" @click="showTransferModal = false">
          {{ t('common.cancel') }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>
