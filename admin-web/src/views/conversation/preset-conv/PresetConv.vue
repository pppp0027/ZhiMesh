<template>
  <n-card :bordered="false" class="proCard">
    <BasicForm @register="register" @submit="handleSubmit" @reset="handleReset" />

    <BasicTable
      :columns="columns"
      :request="loadDataTable"
      :row-key="(row: CharacterPreset) => row.id"
      ref="actionRef"
      :actionColumn="actionColumn"
      @update:checked-row-keys="onCheckedRow"
      :scroll-x="1480"
    >
      <template #tableTitle>
        <n-button type="primary" @click="addTable">
          <template #icon>
            <n-icon>
              <PlusOutlined />
            </n-icon>
          </template>
          {{ t('common.create') }}
        </n-button>
      </template>
    </BasicTable>

    <n-modal
      v-model:show="showEditModal"
      :show-icon="false"
      preset="dialog"
      :title="editFormParams.label"
      class="agent-preset-modal"
    >
      <n-alert
        v-if="editFormParams.isSystem"
        type="info"
        :show-icon="false"
        class="system-agent-tip"
      >
        {{ t('character.systemPresetTip') }}
      </n-alert>
      <n-form
        :model="editFormParams"
        :rules="formRules"
        ref="formRef"
        label-placement="left"
        :label-width="100"
        class="py-4"
      >
        <n-form-item :label="t('common.title')" path="title">
          <n-input
            :placeholder="t('character.titlePlaceholder')"
            v-model:value="editFormParams.title"
            maxlength="45"
            show-count
          />
        </n-form-item>
        <n-form-item :label="t('character.presetType')" path="type">
          <n-select
            :placeholder="t('character.presetTypePlaceholder')"
            v-model:value="editFormParams.type"
            :options="presetTypeOptions"
            clearable
          />
        </n-form-item>
        <n-form-item :label="t('common.description')" path="remark">
          <n-input
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 10 }"
            :placeholder="t('character.descriptionPlaceholder')"
            v-model:value="editFormParams.remark"
          />
        </n-form-item>
        <n-form-item :label="t('character.aiSystemMessage')" path="aiSystemMessage">
          <n-input
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 10 }"
            :placeholder="t('character.aiSystemMessagePlaceholder')"
            v-model:value="editFormParams.aiSystemMessage"
          />
        </n-form-item>
        <n-form-item :label="t('character.systemKnowledgeBases')" path="systemKbIds">
          <n-select
            v-model:value="editFormParams.systemKbIds"
            multiple
            filterable
            clearable
            :options="systemKbOptions"
            :placeholder="t('character.systemKnowledgeBasesPlaceholder')"
          >
            <template #empty>{{ t('character.noSystemKnowledgeBases') }}</template>
          </n-select>
        </n-form-item>
        <n-form-item :label="t('character.mcpServices')" path="mcpIds">
          <n-select
            v-model:value="editFormParams.mcpIds"
            multiple
            filterable
            clearable
            :options="mcpOptions"
            :placeholder="t('character.mcpServicesPlaceholder')"
          >
            <template #empty>{{ t('character.noMcpServices') }}</template>
          </n-select>
        </n-form-item>
      </n-form>
      <template #action>
        <n-space>
          <n-button @click="() => (showEditModal = false)">{{ t('common.cancel') }}</n-button>
          <n-button type="info" :loading="formBtnLoading" @click="confirmEditForm">{{
            t('common.confirm')
          }}</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-card>
</template>

<script lang="ts" setup>
  import { h, onMounted, reactive, ref } from 'vue'
  import { BasicTable, TableAction } from '@/components/Table'
  import { BasicForm, FormSchema, useForm } from '@/components/Form/index'
  import characterApi from '@/api/conversation'
  import mcpApi from '@/api/mcp'
  import knowledgeBaseApi from '@/api/knowledgeBase'
  import { getColumns } from './columns'
  const columns = getColumns()
  import { CharacterPreset } from '/#/conversation'
  import { PlusOutlined } from '@vicons/antd'
  import { type FormRules } from 'naive-ui'
  import { useDialog } from 'naive-ui'
  import { t } from '@/locales'
  import { openDeleteDialog } from '@/utils/dialog'

  const formRules: FormRules = {
    title: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('common.title'),
    },
    remark: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('character.descriptionPlaceholder'),
    },
    aiSystemMessage: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('character.aiSystemMessagePlaceholder'),
    },
  }

  const schemas: FormSchema[] = [
    {
      field: 'title',
      component: 'NInput',
      label: t('common.title'),
      componentProps: {
        placeholder: t('character.titlePlaceholder'),
      },
    },
    {
      field: 'remark',
      component: 'NInput',
      label: t('common.description'),
      componentProps: {
        placeholder: t('character.descriptionPlaceholder'),
      },
    },
    {
      field: 'createTime',
      component: 'NDatePicker',
      label: t('common.createTime'),
      componentProps: {
        type: 'datetimerange',
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

  const dialog = useDialog()
  const formRef: any = ref(null)

  const presetTypeOptions = [
    { label: () => t('character.presetTypeTechnology'), value: 'technology' },
    { label: () => t('character.presetTypeCreative'), value: 'creative' },
    { label: () => t('character.presetTypeEducation'), value: 'education' },
    { label: () => t('character.presetTypeBusiness'), value: 'business' },
    {
      label: () => t('character.presetTypeProfessional'),
      value: 'professional',
    },
    { label: () => t('character.presetTypeDesign'), value: 'design' },
    { label: () => t('character.presetTypeMarketing'), value: 'marketing' },
    { label: () => t('character.presetTypeService'), value: 'service' },
    {
      label: () => t('character.presetTypeAdministration'),
      value: 'administration',
    },
    { label: () => t('character.presetTypeUtility'), value: 'utility' },
  ]
  const actionRef = ref()
  const mcpOptions = ref<{ label: string; value: number; disabled: boolean }[]>([])
  const systemKbOptions = ref<{ label: string; value: number; disabled?: boolean }[]>([])

  const showEditModal = ref(false)
  const formBtnLoading = ref(false)
  const editFormParams = reactive({
    label: t('common.create'),
    uuid: '',
    title: '',
    remark: '',
    aiSystemMessage: '',
    systemKbIds: [] as number[],
    mcpIds: [] as number[],
    type: '',
    isSystem: false,
  })

  const actionColumn = reactive({
    // Keep enough room for both Edit and More without clipping the dropdown.
    width: 180,
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
              )}\n${t('character.permanentDeletePreset')}`,
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

  function addTable() {
    showEditModal.value = true
    editFormParams.label = t('common.create')
    editFormParams.uuid = ''
    editFormParams.title = ''
    editFormParams.remark = ''
    editFormParams.aiSystemMessage = ''
    editFormParams.systemKbIds = []
    editFormParams.mcpIds = []
    editFormParams.type = ''
    editFormParams.isSystem = false
  }

  const loadDataTable = async (res) => {
    const resp = await characterApi.searchPresetCharacters({ ...getFieldsValue() }, res)
    return resp.data
  }

  function onCheckedRow(rowKeys) {
    // 选中行回调
  }

  function reloadTable() {
    actionRef.value.reload()
  }

  function confirmEditForm(e) {
    e.preventDefault()
    formBtnLoading.value = true
    formRef.value.validate(async (errors) => {
      try {
        if (errors) {
          window['$message'].error(t('common.fillCompleteInfo'))
          return
        }
        const payload = {
          title: editFormParams.title,
          remark: editFormParams.remark,
          aiSystemMessage: editFormParams.aiSystemMessage,
          systemKbIds: editFormParams.systemKbIds,
          mcpIds: editFormParams.mcpIds,
          type: editFormParams.type,
        }
        if (editFormParams.uuid === '') await characterApi.addPresetCharacter(payload)
        else await characterApi.editPresetCharacter(editFormParams.uuid, payload)
        window['$message'].success(
          editFormParams.uuid === '' ? t('common.createSuccess') : t('common.editSuccess')
        )
        showEditModal.value = false
        reloadTable()
      } catch (error: any) {
        window['$message'].error(error?.message || t('common.operationFailed'))
      } finally {
        formBtnLoading.value = false
      }
    })
  }

  function handleEdit(record: Recordable) {
    showEditModal.value = true
    Object.assign(editFormParams, record)
    editFormParams.mcpIds = String(record.mcpIds || '')
      .split(',')
      .filter(Boolean)
      .map(Number)
    editFormParams.systemKbIds = String(record.systemKbIds || '')
      .split(',')
      .filter(Boolean)
      .map(Number)
    editFormParams.label = t('common.edit')
  }

  async function handleDelete(record: Recordable) {
    try {
      await characterApi.deletePresetCharacter(record.uuid)
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

  async function loadMcpOptions() {
    try {
      const resp = await mcpApi.mcpSearch({}, { current: 1, size: 1000 })
      mcpOptions.value = (resp.data?.records || []).map((item) => ({
        label: item.title,
        value: Number(item.id),
        disabled: !item.isEnable,
      }))
    } catch {
      mcpOptions.value = []
    }
  }

  async function loadSystemKbOptions() {
    try {
      const resp = await knowledgeBaseApi.search(
        { isSystem: true, isEnabled: true },
        { current: 1, size: 1000 },
      )
      systemKbOptions.value = (resp.data?.records || []).map((item) => ({
        label: item.title,
        value: Number(item.id),
      }))
    } catch {
      systemKbOptions.value = []
    }
  }

  onMounted(() => {
    loadMcpOptions()
    loadSystemKbOptions()
  })
</script>

<style lang="less" scoped>
  .system-agent-tip {
    margin-bottom: 16px;
    border-radius: 12px;
  }

  :global(.agent-preset-modal) {
    width: min(680px, calc(100vw - 32px));
  }
</style>
