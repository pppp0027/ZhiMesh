<template>
  <n-card :bordered="false" class="proCard">
    <BasicForm @register="register" @submit="handleSubmit" @reset="handleReset" />
    <BasicTable
      :columns="columns"
      :request="loadDataTable"
      :row-key="(row: AiPlatformData) => row.id"
      ref="actionRef"
      :actionColumn="actionColumn"
      @update:checked-row-keys="onCheckedRow"
      :scroll-x="980"
    >
      <template #tableTitle>
        <n-button type="primary" @click="addTable">
          <template #icon
            ><n-icon><PlusOutlined /></n-icon
          ></template>
          {{ t('common.create') }}
        </n-button>
      </template>
    </BasicTable>

    <n-modal
      v-model:show="showEditModal"
      :show-icon="false"
      preset="dialog"
      :title="editFormParams.label"
      class="ai-config-modal"
      style="width: min(94vw, 760px)"
    >
      <n-form
        :model="editFormParams"
        :rules="newRecordRules"
        ref="formRef"
        label-placement="top"
        class="ai-config-form platform-config-form"
        style="overflow-y: auto; overflow-x: hidden; max-height: min(72vh, 720px); width: 100%"
      >
        <div class="form-intro">
          <strong>{{ t('model.platformCreateIntroTitle') }}</strong>
          <span>{{ t('model.platformCreateIntro') }}</span>
        </div>

        <section class="config-section">
          <header class="config-section-header">
            <h3>{{ t('model.platformIdentitySection') }}</h3>
            <p>{{ t('model.platformIdentityDescription') }}</p>
          </header>
          <div class="form-grid">
            <n-form-item :label="t('model.platformTitle')" path="title">
              <n-input
                :placeholder="t('model.platformTitlePlaceholder')"
                v-model:value="editFormParams.title"
              />
              <p class="field-description">{{ t('model.platformTitleFeedback') }}</p>
            </n-form-item>
            <n-form-item :label="t('model.platformName')" path="name">
              <n-input
                :placeholder="t('model.platformNamePlaceholder')"
                :disabled="isEditing"
                v-model:value="editFormParams.name"
              />
              <p class="field-description">{{ t('model.platformNameFeedback') }}</p>
            </n-form-item>
          </div>
        </section>

        <section class="config-section">
          <header class="config-section-header">
            <h3>{{ t('model.platformConnectionSection') }}</h3>
            <p>{{ t('model.platformConnectionDescription') }}</p>
          </header>
          <div class="form-grid">
            <n-form-item class="form-field-wide" :label="t('model.platformBaseUrl')" path="baseUrl">
              <n-input
                :placeholder="t('model.platformBaseUrlPlaceholder')"
                v-model:value="editFormParams.baseUrl"
              />
              <p class="field-description">{{ t('model.platformBaseUrlFeedback') }}</p>
            </n-form-item>
            <n-form-item class="form-field-wide" :label="t('model.apiKeyLabel')">
              <n-input
                :placeholder="t('model.apiKeyPlaceholder')"
                v-model:value="editFormParams.apiKey"
                type="password"
                show-password-on="click"
              />
              <p class="field-description">{{ t('model.apiKeyFeedback') }}</p>
            </n-form-item>
            <n-form-item class="form-field-wide" :label="t('model.protocolColumn')">
              <n-radio-group
                v-model:value="editFormParams.isOpenaiApiCompatible"
                name="platform-openai-compatible"
                class="protocol-options"
              >
                <n-radio :value="true">{{ t('model.openaiProtocol') }}</n-radio>
                <n-radio :value="false">{{ t('model.nativeProtocol') }}</n-radio>
              </n-radio-group>
              <p class="field-description">{{ t('model.protocolFeedback') }}</p>
            </n-form-item>
          </div>
        </section>
        <n-collapse class="advanced-settings" :default-expanded-names="[]">
          <n-collapse-item name="advanced">
            <template #header>
              <div class="advanced-settings-heading">
                <strong>{{ t('model.platformAdvancedSection') }}</strong>
                <span>{{ t('model.platformAdvancedHint') }}</span>
              </div>
            </template>
            <n-form-item :label="t('model.enableProxy')">
              <n-radio-group v-model:value="editFormParams.isProxyEnable" name="platform-proxy">
                <n-radio v-for="opt in YES_NO" :key="opt.value" :value="opt.value">{{
                  opt.label
                }}</n-radio>
              </n-radio-group>
              <p class="field-description">{{ t('model.proxyFeedback') }}</p>
            </n-form-item>
            <n-form-item :label="t('model.remark')">
              <n-input
                type="textarea"
                :placeholder="t('model.platformRemarkPlaceholder')"
                v-model:value="editFormParams.remark"
              />
            </n-form-item>
          </n-collapse-item>
        </n-collapse>
      </n-form>
      <template #action>
        <n-space>
          <n-button @click="showEditModal = false">{{ t('common.cancel') }}</n-button>
          <n-button type="primary" :loading="formBtnLoading" @click="confirmForm">
            {{ t('model.savePlatform') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </n-card>
</template>

<script lang="ts" setup>
  import { computed, h, reactive, ref } from 'vue'
  import { BasicTable, TableAction } from '@/components/Table'
  import { FormSchema, useForm } from '@/components/Form/index'
  import api from '@/api/modelPlatform'
  import { AiPlatformData, getColumns } from './platformColumns'
  import { PlusOutlined } from '@vicons/antd'
  import { getYesNo } from '@/utils/constants'
  import { useDialog } from 'naive-ui'
  import type { FormItemRule, FormRules } from 'naive-ui'
  import { t } from '@/locales'
  import { openDeleteDialog } from '@/utils/dialog'

  const columns = getColumns()
  const YES_NO = getYesNo()
  const showEditModal = ref(false)
  const formBtnLoading = ref(false)
  const actionRef = ref()
  const dialog = useDialog()
  const formRef: any = ref(null)

  function createEditPlatform() {
    return {
      label: t('model.createPlatform'),
      id: '',
      name: '',
      title: '',
      baseUrl: '',
      apiKey: '',
      isProxyEnable: false,
      isOpenaiApiCompatible: true,
      remark: '',
    }
  }

  const editFormParams = reactive(createEditPlatform())
  const isEditing = computed(() => Boolean(editFormParams.id))
  const newRecordRules: FormRules = {
    name: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('model.platformNameRequired'),
    },
    title: {
      required: true,
      trigger: ['blur', 'input'],
      message: () => t('model.platformTitleRequired'),
    },
    baseUrl: {
      required: true,
      trigger: ['blur', 'input'],
      validator(_rule: FormItemRule, value: string) {
        if (!value?.trim()) return new Error(t('model.platformBaseUrlRequired'))
        return /^https?:\/\//i.test(value.trim()) || new Error(t('model.platformBaseUrlInvalid'))
      },
    },
  }

  const actionColumn = reactive({
    width: 160,
    title: t('common.action'),
    key: 'action',
    fixed: 'right',
    render(record) {
      return h(TableAction as any, {
        style: 'button',
        actions: [{ label: t('common.edit'), onClick: handleEdit.bind(null, record) }],
        dropDownActions: [{ label: t('common.delete'), key: 'delete' }],
        select: (key) => {
          if (key === 'delete') {
            openDeleteDialog(dialog, {
              title: t('common.deleteConfirmTitle'),
              content: `${t('model.deletePlatformConfirmPrefix')}${record.name}${t(
                'model.deletePlatformConfirmSuffix'
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

  const schemas: FormSchema[] = [
    {
      field: 'name',
      component: 'NInput',
      label: t('model.platformName'),
      componentProps: { placeholder: t('model.platformNamePlaceholder') },
    },
    {
      field: 'title',
      component: 'NInput',
      label: t('model.platformTitle'),
      componentProps: { placeholder: t('model.platformTitlePlaceholder') },
    },
  ]

  const [register, { getFieldsValue }] = useForm({
    gridProps: { cols: '1 s:1 m:2 l:3 xl:4 2xl:4' },
    labelWidth: 120,
    schemas,
  })

  function requestSucceeded(response: Recordable) {
    return response?.success !== false
  }

  function resetEditForm() {
    Object.assign(editFormParams, createEditPlatform())
    formRef.value?.restoreValidation?.()
  }

  const loadDataTable = async () => {
    const resp = await api.search({ ...getFieldsValue() }, { current: 1, size: 100 })
    return resp.data
  }

  function addTable() {
    resetEditForm()
    showEditModal.value = true
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
        const { label: _label, ...platform } = editFormParams
        const submitData = {
          ...platform,
          name: platform.name.trim(),
          title: platform.title.trim(),
          baseUrl: platform.baseUrl.trim(),
          apiKey: platform.apiKey.trim(),
          remark: platform.remark.trim(),
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

  function handleSubmit(_values: Recordable) {
    reloadTable()
  }

  function handleReset(_values: Recordable) {}

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

  function handleEdit(record: Recordable) {
    resetEditForm()
    Object.assign(editFormParams, record)
    editFormParams.label = t('model.editPlatform')
    showEditModal.value = true
  }
</script>

<style lang="less" scoped>
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

  .form-field-wide {
    grid-column: 1 / -1;
  }

  .field-description {
    margin: 6px 0 0;
    color: var(--zhimesh-muted);
    font-size: 11px;
    line-height: 1.55;
  }

  .protocol-options {
    display: flex;
    min-height: 34px;
    align-items: center;
    gap: 18px;
  }

  .advanced-settings {
    margin-top: 8px;
    padding-top: 8px;
    border-top: 1px solid var(--zhimesh-border-subtle);
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
    color: var(--zhimesh-muted);
  }

  @media (max-width: 620px) {
    .form-grid {
      grid-template-columns: 1fr;
    }

    .form-field-wide {
      grid-column: auto;
    }
  }
</style>
