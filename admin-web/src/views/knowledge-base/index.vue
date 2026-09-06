<template>
  <n-card :bordered="false" class="proCard">
    <BasicForm @register="register" @submit="handleSubmit" @reset="handleReset" />

    <BasicTable
      :columns="columns"
      :request="loadDataTable"
      :row-key="(row: KbInfoData) => row.id"
      ref="actionRef"
      :actionColumn="actionColumn"
      @update:checked-row-keys="onCheckedRow"
      :scroll-x="2000"
    >
      <template #tableTitle>
        <n-button type="primary" @click="handleCreate">{{ t('common.create') }}</n-button>
      </template>
    </BasicTable>

    <n-modal
      v-model:show="showEditModal"
      :show-icon="false"
      preset="card"
      :title="editFormParams.id ? t('common.edit') : t('common.create')"
      style="width: 90%; max-width: 700px"
      closable
    >
      <div class="max-h-[600px] overflow-y-auto pr-2">
        <n-form :model="editFormParams" :rules="newRecordRules" ref="formRef" label-placement="top">
          <div class="flex flex-col space-y-2">
            <n-form-item :label="t('common.title')" path="title" class="mb-0">
              <n-input
                :placeholder="t('knowledgeBase.titlePlaceholder')"
                v-model:value="editFormParams.title"
                maxlength="100"
                show-count
              />
            </n-form-item>
            <n-form-item :label="t('common.description')" path="remark" class="mb-0">
              <n-input
                type="textarea"
                :placeholder="t('common.description')"
                :autosize="{ minRows: 3, maxRows: 10 }"
                v-model:value="editFormParams.remark"
                maxlength="500"
                show-count
              />
            </n-form-item>

            <div class="space-y-1">
              <div>{{ t('common.isPublic') }}</div>
              <n-radio-group
                v-model:value="editFormParams.isPublic"
                :disabled="editFormParams.isSystem"
              >
                <n-radio :value="true">{{ t('common.public') }}</n-radio>
                <n-radio :value="false">{{ t('common.private') }}</n-radio>
              </n-radio-group>
            </div>

            <div class="space-y-1">
              <div>{{ t('knowledgeBase.isSystem') }}</div>
              <div class="flex items-center space-x-2">
                <n-switch v-model:value="editFormParams.isSystem" />
                <span class="text-xs opacity-60">{{ t('knowledgeBase.isSystemPlaceholder') }}</span>
              </div>
            </div>

            <div class="space-y-1">
              <div>{{ t('knowledgeBase.isEnabled') }}</div>
              <n-switch v-model:value="editFormParams.isEnabled" />
            </div>

            <div class="space-y-1">
              <div>{{ t('knowledgeBase.isStrict') }}</div>
              <n-radio-group v-model:value="editFormParams.isStrict">
                <n-radio :value="true">{{ t('common.yes') }}</n-radio>
                <n-radio :value="false">{{ t('common.no') }}</n-radio>
              </n-radio-group>
            </div>

            <n-collapse>
              <n-collapse-item :title="t('knowledgeBase.documentSettings')" name="document">
                <n-form-item :label="t('knowledgeBase.ingestMaxOverlap')" path="ingestMaxOverlap">
                  <n-input-number
                    :placeholder="t('knowledgeBase.ingestMaxOverlap')"
                    v-model:value="editFormParams.ingestMaxOverlap"
                    class="w-full"
                  />
                </n-form-item>
                <n-form-item
                  :label="t('knowledgeBase.ingestSplitStrategy')"
                  path="ingestSplitStrategy"
                >
                  <n-select
                    :placeholder="t('knowledgeBase.ingestSplitStrategy')"
                    :options="splitStrategyOpts"
                    v-model:value="editFormParams.ingestSplitStrategy"
                  />
                </n-form-item>
                <n-form-item
                  v-if="editFormParams.ingestSplitStrategy === 'custom'"
                  :label="t('knowledgeBase.ingestCustomSeparator')"
                  path="ingestCustomSeparator"
                >
                  <n-select
                    :placeholder="t('knowledgeBase.ingestCustomSeparatorPlaceholder')"
                    :options="customSeparatorOpts"
                    v-model:value="editFormParams.ingestCustomSeparator"
                    filterable
                    tag
                  />
                </n-form-item>
                <n-form-item
                  :label="t('knowledgeBase.ingestMaxSegmentSize')"
                  path="ingestMaxSegmentSize"
                >
                  <n-input-number
                    :placeholder="t('knowledgeBase.ingestMaxSegmentSize')"
                    v-model:value="editFormParams.ingestMaxSegmentSize"
                    :min="100"
                    class="w-full"
                  />
                </n-form-item>
                <n-form-item :label="t('knowledgeBase.tokenEstimator')" path="ingestTokenEstimator">
                  <n-select
                    :placeholder="t('knowledgeBase.tokenEstimatorPlaceholder')"
                    :options="tokenEstimatorOpts"
                    v-model:value="editFormParams.ingestTokenEstimator"
                    clearable
                  />
                </n-form-item>
              </n-collapse-item>

              <n-collapse-item :title="t('knowledgeBase.modelSettings')" name="model">
                <n-form-item :label="t('knowledgeBase.ingestModelName')" path="ingestModelId">
                  <n-select
                    :placeholder="t('knowledgeBase.ingestModelNamePlaceholder')"
                    :options="aiModelOpts"
                    v-model:value="editFormParams.ingestModelId"
                    filterable
                    clearable
                  />
                </n-form-item>
              </n-collapse-item>

              <n-collapse-item :title="t('knowledgeBase.recallSettings')" name="recall">
                <n-form-item
                  :label="t('knowledgeBase.retrieveMaxResults')"
                  path="retrieveMaxResults"
                >
                  <n-input-number
                    :placeholder="t('knowledgeBase.retrieveMaxResults')"
                    v-model:value="editFormParams.retrieveMaxResults"
                    class="w-full"
                  />
                </n-form-item>
                <n-form-item :label="t('knowledgeBase.retrieveMinScore')" path="retrieveMinScore">
                  <n-input-number
                    :placeholder="t('knowledgeBase.retrieveMinScore')"
                    v-model:value="editFormParams.retrieveMinScore"
                    :precision="1"
                    :min="0"
                    :max="1"
                    class="w-full"
                  />
                </n-form-item>
                <n-form-item :label="t('knowledgeBase.graphHopDepth')" path="graphHopDepth">
                  <n-input-number
                    v-model:value="editFormParams.graphHopDepth"
                    :min="1"
                    :max="2"
                    :precision="0"
                    class="w-full"
                  />
                </n-form-item>
                <n-form-item :label="t('knowledgeBase.rerankModel')" path="rerankModelId">
                  <n-select
                    v-model:value="editFormParams.rerankModelId"
                    :options="rerankModelOpts"
                    :placeholder="t('knowledgeBase.rerankModelPlaceholder')"
                    clearable
                    filterable
                  />
                </n-form-item>
                <n-form-item
                  v-if="Number(editFormParams.rerankModelId) > 0"
                  :label="t('knowledgeBase.rerankTopN')"
                  path="rerankTopN"
                >
                  <n-input-number
                    v-model:value="editFormParams.rerankTopN"
                    :min="1"
                    :max="10"
                    :precision="0"
                    class="w-full"
                  />
                </n-form-item>
                <div class="text-xs opacity-60 mt-1">{{ t('knowledgeBase.rerankTip') }}</div>
              </n-collapse-item>

              <n-collapse-item :title="t('knowledgeBase.promptSettings')" name="prompt">
                <n-form-item
                  :label="t('knowledgeBase.querySystemMessage')"
                  path="querySystemMessage"
                >
                  <n-input
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 5 }"
                    :placeholder="t('knowledgeBase.querySystemMessage')"
                    v-model:value="editFormParams.querySystemMessage"
                  />
                </n-form-item>
                <n-form-item
                  :label="t('knowledgeBase.queryLlmTemperature')"
                  path="queryLlmTemperature"
                >
                  <n-input-number
                    :placeholder="t('knowledgeBase.queryLlmTemperature')"
                    v-model:value="editFormParams.queryLlmTemperature"
                    :precision="1"
                    :min="0"
                    :max="1"
                    class="w-full"
                  />
                </n-form-item>
              </n-collapse-item>
            </n-collapse>
          </div>
        </n-form>
      </div>
      <template #footer>
        <div class="flex space-x-2 justify-end">
          <n-button type="primary" size="small" :loading="formBtnLoading" @click="confirmForm">{{
            t('common.confirm')
          }}</n-button>
          <n-button size="small" @click="() => (showEditModal = false)">{{
            t('common.cancel')
          }}</n-button>
        </div>
      </template>
    </n-modal>

    <KnowledgeWorkbench
      v-model:show="showWorkbench"
      :knowledge-base="activeWorkbenchKb"
      :model-options="aiModelOpts"
      @refresh="reloadTable"
    />
  </n-card>
</template>

<script lang="ts" setup>
  import { h, onMounted, reactive, ref } from 'vue'
  import { BasicTable, TableAction } from '@/components/Table'
  import { BasicForm, FormSchema, useForm } from '@/components/Form/index'
  import api from '@/api/knowledgeBase'
  import aiModelApi from '@/api/aiModel'
  import { getColumns, KbInfoData } from './columns'
  import KnowledgeWorkbench from './KnowledgeWorkbench.vue'
  const columns = getColumns()
  import { type FormRules } from 'naive-ui'
  import { useDialog } from 'naive-ui'
  import { t } from '@/locales'
  import { openDeleteDialog } from '@/utils/dialog'

  interface SelectOpt {
    label: string
    value: number
  }
  const showEditModal = ref(false)
  const formBtnLoading = ref(false)
  const editFormParams = reactive({
    id: undefined as number | undefined,
    uuid: '',
    title: '',
    remark: '',
    isPublic: false,
    isSystem: false,
    isEnabled: true,
    isStrict: false,
    ingestMaxOverlap: 0,
    ingestSplitStrategy: 'recursive',
    ingestMaxSegmentSize: 1000,
    ingestCustomSeparator: '',
    ingestModelId: 0,
    ingestTokenEstimator: 'openai',
    retrieveMaxResults: 0,
    retrieveMinScore: 0.0,
    graphHopDepth: 1,
    rerankModelId: 0,
    rerankTopN: 5,
    queryLlmTemperature: 0.0,
    querySystemMessage: '',
  })
  const publicOpts = [
    {
      label: t('common.yes'),
      value: 1,
    },
    {
      label: t('common.no'),
      value: 0,
    },
  ]
  const tokenEstimatorOpts = [
    { label: 'OpenAI', value: 'openai' },
    { label: 'HuggingFace', value: 'huggingface' },
    { label: 'Qwen', value: 'qwen' },
  ]
  const splitStrategyOpts = [
    { label: t('knowledgeBase.splitStrategyRecursive'), value: 'recursive' },
    { label: t('knowledgeBase.splitStrategyParagraph'), value: 'paragraph' },
    { label: t('knowledgeBase.splitStrategyLine'), value: 'line' },
    { label: t('knowledgeBase.splitStrategySentence'), value: 'sentence' },
    { label: t('knowledgeBase.splitStrategyCustom'), value: 'custom' },
  ]
  const customSeparatorOpts = [
    { label: t('knowledgeBase.separatorParagraph'), value: '\n\n' },
    { label: t('knowledgeBase.separatorLine'), value: '\n' },
  ]
  const aiModelOpts = ref<SelectOpt[]>([])
  const rerankModelOpts = ref<SelectOpt[]>([{ label: t('knowledgeBase.rerankDisabled'), value: 0 }])
  const dialog = useDialog()
  const formRef: any = ref(null)
  const showWorkbench = ref(false)
  const activeWorkbenchKb = ref<KbInfoData | null>(null)
  const newRecordRules: FormRules = {
    title: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('common.title'),
    },
  }
  const schemas: FormSchema[] = [
    {
      field: 'title',
      component: 'NInput',
      label: t('common.title'),
      componentProps: {
        placeholder: t('knowledgeBase.titlePlaceholder'),
      },
    },
    {
      field: 'ownerName',
      component: 'NInput',
      label: t('knowledgeBase.ownerName'),
      componentProps: {
        placeholder: t('knowledgeBase.ownerNamePlaceholder'),
      },
    },
    {
      field: 'isPublic',
      component: 'NSelect',
      label: t('common.isPublic'),
      componentProps: {
        options: publicOpts,
      },
    },
    {
      field: 'createDate',
      component: 'NDatePicker',
      label: t('common.createTime'),
      componentProps: {
        type: 'datetimerange',
        'value-format': 'yyyy.MM.dd HH:mm:ss',
        clearable: true,
      },
    },
    {
      field: 'updateTime',
      component: 'NDatePicker',
      label: t('common.updateTime'),
      componentProps: {
        type: 'datetimerange',
        clearable: true,
      },
    },
  ]

  const actionRef = ref()
  const actionColumn = reactive({
    width: 260,
    title: t('common.action'),
    key: 'action',
    fixed: 'right',
    render(record) {
      return h(TableAction as any, {
        style: 'button',
        actions: [
          {
            label: t('common.edit'),
            onClick: handleEdit.bind(null, record),
          },
          {
            label: t('knowledgeBase.documentWorkbench'),
            onClick: handleWorkbench.bind(null, record),
          },
        ],
        dropDownActions: [
          {
            label: t('common.delete'),
            key: 'delete',
          },
        ],
        select: (key) => {
          if (key === 'delete') {
            openDeleteDialog(dialog, {
              title: t('common.deleteConfirmTitle'),
              content: `${t('common.deleteConfirmPrefix')} ${record.title} ${t(
                'common.deleteConfirmSuffix'
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
    schemas,
  })

  const loadDataTable = async (res) => {
    const resp = await api.search({ ...getFieldsValue() }, res)
    return resp.data
  }

  function onCheckedRow(rowKeys) {
    // 选中行回调
  }

  function reloadTable() {
    actionRef.value.reload()
  }

  function handleCreate() {
    Object.assign(editFormParams, {
      id: undefined,
      uuid: '',
      title: '',
      remark: '',
      isPublic: false,
      isSystem: false,
      isEnabled: true,
      isStrict: false,
      ingestMaxOverlap: 0,
      ingestSplitStrategy: 'recursive',
      ingestMaxSegmentSize: 1000,
      ingestCustomSeparator: '',
      ingestModelId: aiModelOpts.value[0]?.value || 0,
      ingestTokenEstimator: 'openai',
      retrieveMaxResults: 0,
      retrieveMinScore: 0,
      graphHopDepth: 1,
      rerankModelId: 0,
      rerankTopN: 5,
      queryLlmTemperature: 0,
      querySystemMessage: '',
    })
    showEditModal.value = true
  }

  function handleWorkbench(record: KbInfoData) {
    activeWorkbenchKb.value = record
    showWorkbench.value = true
  }

  function confirmForm(e) {
    e.preventDefault()
    formBtnLoading.value = true
    formRef.value.validate(async (errors) => {
      try {
        if (errors) {
          window['$message'].error(t('common.fillCompleteInfo'))
          return
        }
        await api.edit({
          ...editFormParams,
          isPublic: editFormParams.isSystem ? false : editFormParams.isPublic,
        })
        window['$message'].success(
          editFormParams.id ? t('common.editSuccess') : t('common.createSuccess')
        )
        showEditModal.value = false
        reloadTable()
      } catch (error: any) {
        if (!error?.isBusinessError)
          window['$message'].error(error?.message || t('common.operationFailed'))
      } finally {
        formBtnLoading.value = false
      }
    })
  }

  function handleEdit(record: Recordable) {
    showEditModal.value = true
    Object.assign(editFormParams, {
      ...record,
      ingestTokenEstimator: record.ingestTokenEstimator || 'openai',
      graphHopDepth: Number(record.graphHopDepth) || 1,
      rerankModelId: Number(record.rerankModelId) || 0,
      rerankTopN: Number(record.rerankTopN) || 5,
    })
  }

  async function handleDelete(record: Recordable) {
    try {
      await api.deleteOne(record.uuid)
      window['$message'].success(t('common.deleteSuccess'))
      reloadTable()
    } catch (error: any) {
      window['$message'].error(error?.message || t('common.operationFailed'))
      return false
    }
  }

  function handleSubmit(values: Recordable) {
    reloadTable()
  }

  function handleReset(values: Recordable) {
    // 重置回调
  }

  onMounted(async () => {
    if (aiModelOpts.value.length > 0) {
      return
    }
    try {
      const [textModels, rerankModels] = await Promise.all([
        aiModelApi.search({ isEnable: true, type: 'text' }, { current: 1, size: 100 }),
        aiModelApi.search({ isEnable: true, type: 'rerank' }, { current: 1, size: 100 }),
      ])
      if (textModels.data?.records) {
        textModels.data.records.forEach((item) => {
          aiModelOpts.value.push({ label: item.name, value: item.id })
        })
      }
      if (rerankModels.data?.records) {
        rerankModels.data.records.forEach((item) => {
          rerankModelOpts.value.push({ label: item.name, value: item.id })
        })
      }
    } catch (error: any) {
      if (!error?.isBusinessError)
        window['$message'].error(error?.message || t('common.operationFailed'))
    }
  })
</script>
