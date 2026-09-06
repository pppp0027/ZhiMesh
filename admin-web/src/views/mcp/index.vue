<template>
  <n-card :bordered="false" class="proCard" :title="t('mcp.title')">
    <BasicForm @register="register" @submit="handleSubmit" @reset="handleReset" />

    <BasicTable
      :columns="columns"
      :request="loadDataTable"
      :row-key="(row: McpInfo) => row.id"
      ref="actionRef"
      :actionColumn="actionColumn"
      @update:checked-row-keys="onCheckedRow"
      :scroll-x="1400"
    >
      <template #tableTitle>
        <n-button type="primary" @click="handleCreate">
          {{ t('common.create') }}
        </n-button>
      </template>
    </BasicTable>

    <n-modal
      v-model:show="showEditModal"
      :show-icon="false"
      preset="card"
      :title="editFormParams.label"
      class="mcp-editor-modal"
      :style="{ width: 'min(760px, calc(100vw - 32px))' }"
    >
      <div class="mcp-editor-body">
        <n-form
          :model="editFormParams"
          :rules="newDataRules"
          ref="formRef"
          label-placement="top"
          :label-width="120"
          class="mcp-editor-form"
        >
          <n-form-item :label="t('common.title')" path="title" class="mcp-span-full">
            <n-input
              :placeholder="t('mcp.titlePlaceholder')"
              v-model:value="editFormParams.title"
            />
          </n-form-item>
          <n-form-item :label="t('mcp.transportType')" path="transportType" class="mcp-span-full">
            <n-radio-group v-model:value="editFormParams.transportType" name="rg1">
              <n-radio v-for="opt in mcpTransportType" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </n-radio>
            </n-radio-group>
          </n-form-item>
          <n-form-item :label="t('mcp.installType')" path="installType" class="mcp-span-full">
            <n-radio-group v-model:value="editFormParams.installType" name="rg2">
              <n-radio v-for="opt in mcpInstallType" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </n-radio>
            </n-radio-group>
          </n-form-item>
          <n-form-item
            v-if="editFormParams.transportType === 'sse'"
            :label="t('mcp.sseUrl')"
            path="sseUrl"
            class="mcp-span-full"
          >
            <n-input
              :placeholder="t('mcp.sseUrlPlaceholder')"
              v-model:value="editFormParams.sseUrl"
            />
          </n-form-item>
          <n-form-item
            v-if="editFormParams.transportType === 'streamable_http'"
            :label="t('mcp.streamableHttpUrl')"
            path="streamableHttpUrl"
            class="mcp-span-full"
          >
            <n-input
              :placeholder="t('mcp.streamableHttpUrlPlaceholder')"
              v-model:value="editFormParams.streamableHttpUrl"
            />
          </n-form-item>
          <n-form-item
            v-if="['sse', 'streamable_http'].includes(editFormParams.transportType)"
            :label="t('mcp.networkTimeout')"
            path="sseTimeout"
          >
            <n-input-number v-model:value="editFormParams.sseTimeout" :min="1" />
          </n-form-item>
          <n-form-item
            v-if="editFormParams.transportType === 'stdio'"
            :label="t('mcp.stdioCommand')"
            path="stdioCommand"
            class="mcp-span-full"
          >
            <n-input
              :placeholder="t('mcp.stdioCommandPlaceholder')"
              v-model:value="editFormParams.stdioCommand"
            />
          </n-form-item>
          <n-form-item
            v-if="editFormParams.transportType === 'stdio'"
            :label="t('mcp.stdioArg')"
            path="stdioArg"
            class="mcp-span-full"
          >
            <n-input
              :placeholder="t('mcp.stdioArgPlaceholder')"
              v-model:value="editFormParams.stdioArg"
            />
          </n-form-item>
          <n-form-item :label="t('mcp.website')" path="website">
            <n-input
              :placeholder="t('mcp.websitePlaceholder')"
              v-model:value="editFormParams.website"
            />
          </n-form-item>
          <n-form-item :label="t('mcp.presetParams')" path="presetParams" class="mcp-span-full">
            <n-table :single-line="false" class="mcp-parameter-table">
              <thead>
                <tr>
                  <th>
                    {{ t('mcp.paramName') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.paramNameTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th>
                    {{ t('mcp.paramTitle') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.paramTitleTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th>{{ t('mcp.paramValue') }}</th>
                  <th class="flex justify-center">
                    {{ t('mcp.sensitiveInfo') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.sensitiveInfoTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th class="flex justify-center">
                    {{ t('mcp.cliArg') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.cliArgTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th>{{ t('mcp.cliPrefix') }}</th>
                  <th>{{ t('common.action') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(presetParam, idx) in editFormParams.presetParams"
                  :key="'preset_' + idx"
                >
                  <td class="max-w-[200px]">
                    <n-input
                      v-model:value="presetParam.name"
                      :placeholder="t('mcp.paramNameTip')"
                    />
                  </td>
                  <td>
                    <n-input
                      v-model:value="presetParam.title"
                      :placeholder="t('mcp.paramTitlePlaceholder')"
                    />
                  </td>
                  <td>
                    <n-input
                      v-model:value="presetParam.value"
                      class="flex-1"
                      :placeholder="t('mcp.paramValuePlaceholder')"
                    />
                  </td>
                  <td class="flex justify-center">
                    <n-switch v-model:value="presetParam.require_encrypt" />
                  </td>
                  <td>
                    <n-icon size="18" class="mx-2 cursor-pointer" @click="removePresetParam(idx)">
                      <DeleteOutlined />
                    </n-icon>
                  </td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td colspan="4" class="flex">
                    <n-button type="primary" dashed @click="addPresetParam">
                      {{ t('mcp.addParam') }}
                    </n-button>
                  </td>
                </tr>
              </tfoot>
            </n-table>
          </n-form-item>
          <n-form-item
            :label="t('mcp.paramConfigNote')"
            path="customizedParamDefinitions"
            class="mcp-span-full"
          >
            <n-table :single-line="false" class="mcp-parameter-table">
              <thead>
                <tr>
                  <th>
                    {{ t('mcp.paramName') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.paramNameTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th>
                    {{ t('mcp.paramTitle') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.paramTitleTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th class="flex justify-center">
                    {{ t('mcp.sensitiveInfo') }}
                    <n-tooltip trigger="hover">
                      <template #trigger>
                        <n-icon>
                          <QuestionCircleOutlined />
                        </n-icon>
                      </template>
                      <span>{{ t('mcp.sensitiveInfoTip') }}</span>
                    </n-tooltip>
                  </th>
                  <th>{{ t('common.action') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(uninitParam, idx) in editFormParams.customizedParamDefinitions"
                  :key="'definition_' + idx"
                >
                  <td class="max-w-[200px]">
                    <n-input
                      v-model:value="uninitParam.name"
                      class="flex-1"
                      :placeholder="t('mcp.paramNameTip')"
                    />
                  </td>
                  <td>
                    <n-input
                      v-model:value="uninitParam.title"
                      class="flex-1"
                      :placeholder="t('mcp.paramTitlePlaceholder')"
                    />
                  </td>
                  <td class="flex justify-center">
                    <n-switch v-model:value="uninitParam.require_encrypt" />
                  </td>
                  <td class="flex justify-center">
                    <n-switch v-model:value="uninitParam.cli_arg" />
                  </td>
                  <td>
                    <n-input
                      v-model:value="uninitParam.cli_prefix"
                      class="flex-1"
                      :placeholder="t('mcp.cliPrefixPlaceholder')"
                    />
                  </td>
                  <td>
                    <n-icon
                      size="18"
                      class="mx-2 cursor-pointer"
                      @click="removeParamDefinition(idx)"
                    >
                      <DeleteOutlined />
                    </n-icon>
                  </td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td colspan="6" class="flex">
                    <n-button type="primary" dashed @click="addParamDefinition">
                      {{ t('mcp.addParamDefinition') }}
                    </n-button>
                  </td>
                </tr>
              </tfoot>
            </n-table>
          </n-form-item>
          <n-form-item :label="t('common.description')" path="remark" class="mcp-span-full">
            <n-input
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 15 }"
              :placeholder="t('mcp.descriptionPlaceholder')"
              v-model:value="editFormParams.remark"
            />
          </n-form-item>
          <n-form-item :label="t('common.isEnable')" path="isEnable">
            <n-switch v-model:value="editFormParams.isEnable" />
          </n-form-item>
        </n-form>
      </div>
      <template #action>
        <div class="flex justify-end space-x-2">
          <n-button @click="() => (showEditModal = false)">{{ t('common.cancel') }}</n-button>
          <n-button type="info" :loading="formBtnLoading" @click="confirmEditForm">{{
            t('common.confirm')
          }}</n-button>
        </div>
      </template>
    </n-modal>
  </n-card>
</template>

<script lang="ts" setup>
  import { h, reactive, ref } from 'vue'
  import { BasicTable, TableAction } from '@/components/Table'
  import { BasicForm, FormSchema, useForm } from '@/components/Form/index'
  import { QuestionCircleOutlined, DeleteOutlined } from '@vicons/antd'
  import mcpApi from '@/api/mcp'
  import { getColumns } from './columns'
  const columns = getColumns()
  import { McpInfo, McpSearchReq, McpCustomizedParamDefinition, PresetParam } from '/#/mcp'
  import { getMcpTransportType, getMcpInstallType } from '@/utils/constants'
  const mcpTransportType = getMcpTransportType()
  const mcpInstallType = getMcpInstallType()
  import type { FormRules } from 'naive-ui'
  import { useDialog } from 'naive-ui'
  import { t } from '@/locales'
  import { openDeleteDialog } from '@/utils/dialog'

  const newDataRules: FormRules = {
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
        placeholder: t('mcp.titlePlaceholder'),
      },
    },
    {
      field: 'transportType',
      component: 'NSelect',
      label: t('mcp.transportType'),
      componentProps: {
        options: mcpTransportType,
      },
    },
    {
      field: 'installType',
      component: 'NSelect',
      label: t('mcp.installType'),
      componentProps: {
        options: mcpInstallType,
      },
    },
    {
      field: 'isEnable',
      component: 'NSelect',
      label: t('common.isEnable'),
      componentProps: {
        options: [
          {
            label: t('common.yes'),
            value: true,
          },
          {
            label: t('common.no'),
            value: false,
          },
        ],
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

  const formRef: any = ref(null)
  const actionRef = ref()
  const dialog = useDialog()
  const showEditModal = ref(false)
  const formBtnLoading = ref(false)
  const isCreating = ref(false)
  const editFormParams = reactive({
    label: t('common.edit'),
    uuid: '',
    title: '',
    transportType: 'sse',
    installType: 'remote',
    remark: '',
    isEnable: false,
    sseUrl: '',
    streamableHttpUrl: '',
    sseTimeout: 30,
    stdioCommand: '',
    stdioArg: '',
    website: '',
    presetParams: [] as PresetParam[],
    customizedParamDefinitions: [] as McpCustomizedParamDefinition[],
    repoUrl: '',
  })

  const actionColumn = reactive({
    width: 200,
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
            label: t('common.disable'),
            onClick: handleDisable.bind(null, record),
            ifShow: () => {
              return record.isEnable
            },
          },
          {
            label: t('common.enable'),
            onClick: handleEnable.bind(null, record),
            ifShow: () => {
              return !record.isEnable
            },
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
              onPositiveClick: () => handleDel(record),
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
    const resp = await mcpApi.mcpSearch({ ...getFieldsValue() } as McpSearchReq, res)
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
        if (!errors) {
          const response = isCreating.value
            ? await mcpApi.mcpAdd(editFormParams)
            : await mcpApi.mcpEdit(editFormParams)
          if (response.success === false) throw new Error(response.message)
          window['$message'].success(
            t(isCreating.value ? 'common.createSuccess' : 'common.editSuccess')
          )
          showEditModal.value = false
          reloadTable()
        } else {
          window['$message'].error(t('common.fillCompleteInfo'))
        }
      } catch (error) {
        window['$message'].error(t('common.operationFailed'))
      } finally {
        formBtnLoading.value = false
      }
    })
  }

  function resetEditForm() {
    Object.assign(editFormParams, {
      label: t('common.create'),
      uuid: '',
      title: '',
      transportType: 'sse',
      installType: 'remote',
      remark: '',
      isEnable: false,
      sseUrl: '',
      streamableHttpUrl: '',
      sseTimeout: 30,
      stdioCommand: '',
      stdioArg: '',
      website: '',
      presetParams: [],
      customizedParamDefinitions: [],
      repoUrl: '',
    })
  }

  function handleCreate() {
    isCreating.value = true
    resetEditForm()
    showEditModal.value = true
  }

  function handleEdit(record: Recordable) {
    isCreating.value = false
    Object.assign(editFormParams, {
      ...record,
      presetParams: (record.presetParams || []).map((param) => ({ ...param })),
      customizedParamDefinitions: (record.customizedParamDefinitions || []).map((definition) => ({
        ...definition,
      })),
    })
    editFormParams.label = t('common.edit')
    showEditModal.value = true
  }

  async function handleEnable(record: Recordable) {
    const response = await mcpApi.mcpSetEnable({ uuid: record.uuid, isEnable: true })
    if (response.success === false) {
      window['$message'].error(response.message || t('common.operationFailed'))
      return
    }
    window['$message'].success(t('common.operationSuccess'))
    reloadTable()
  }

  async function handleDisable(record: Recordable) {
    const response = await mcpApi.mcpSetEnable({ uuid: record.uuid, isEnable: false })
    if (response.success === false) {
      window['$message'].error(response.message || t('common.operationFailed'))
      return
    }
    window['$message'].success(t('common.operationSuccess'))
    reloadTable()
  }

  function handleSubmit(values: Recordable) {
    reloadTable()
  }

  function handleReset(values: Recordable) {
    console.log(values)
  }

  async function handleDel(record: Recordable) {
    try {
      const response = await mcpApi.mcpDel(record.uuid)
      if (response.success === false) {
        window['$message'].error(response.message || t('common.operationFailed'))
        return false
      }
      window['$message'].success(t('common.operationSuccess'))
      reloadTable()
    } catch (error) {
      window['$message'].error(t('common.operationFailed'))
      return false
    }
  }

  function addParamDefinition() {
    editFormParams.customizedParamDefinitions.push({
      name: '',
      title: '',
      require_encrypt: false,
      cli_arg: false,
      cli_prefix: '',
    })
  }

  function removeParamDefinition(idx: number) {
    editFormParams.customizedParamDefinitions.splice(idx, 1)
  }

  function addPresetParam() {
    editFormParams.presetParams.push({
      name: '',
      title: '',
      value: '',
      require_encrypt: false,
      encrypted: false,
    })
  }

  function removePresetParam(idx: number) {
    editFormParams.presetParams.splice(idx, 1)
  }
</script>
<style lang="less" scoped>
  .mcp-editor-modal {
    border: 1px solid rgba(119, 102, 255, 0.16);
    border-radius: 20px;
    box-shadow: 0 24px 80px rgba(49, 46, 129, 0.18);

    .mcp-editor-body {
      max-height: min(66vh, 580px);
      overflow-y: auto;
      padding: 4px 6px 6px 0;
    }

    .mcp-editor-form {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0 18px;
      padding: 4px 2px 8px;
    }

    .mcp-span-full {
      grid-column: 1 / -1;
    }

    ::v-deep(.n-form-item) {
      margin-bottom: 14px;
    }

    ::v-deep(.n-form-item-label__text) {
      font-weight: bold;
      border-left: 1px solid var(--zhimesh-primary);
      color: var(--zhimesh-text);
      padding-left: 0.45rem;
    }

    .mcp-parameter-table {
      border-radius: 12px;
      overflow: hidden;
      box-shadow: var(--zhimesh-shadow);
    }

    ::v-deep(.n-card__footer) {
      padding-top: 12px;
      border-top: 1px solid var(--zhimesh-border-subtle);
    }
  }

  @media (max-width: 640px) {
    .mcp-editor-modal .mcp-editor-form {
      grid-template-columns: 1fr;
    }
  }
</style>
