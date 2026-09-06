<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  NButton,
  NCollapse,
  NCollapseItem,
  NDynamicInput,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSwitch,
} from 'naive-ui'
import { v4 as uuidv4 } from 'uuid'
import { SvgIcon } from '@/components/common'
import { getNameByInputType } from '@/utils/workflow-util'
import { useWfStore } from '@/store'
import { t } from '@/locales'
interface Props {
  workflow: Workflow.WorkflowInfo
  wfNode: Workflow.WorkflowNode
}
const props = defineProps<Props>()
const wfStore = useWfStore()
const showModal = ref<boolean>(false)
const tmpItem = reactive<Workflow.NodeIODefinition>({
  uuid: '',
  type: 1,
  name: '',
  title: '',
  required: false,

  limit: 10,
  multiple: false,
  options: [],
})
const options = [
  {
    label: t('workflow.variableTypeText'),
    value: 1,
  },
  {
    label: t('workflow.variableTypeNumber'),
    value: 2,
  },
  {
    label: t('workflow.variableTypeOptions'),
    value: 3,
  },
  {
    label: t('workflow.variableTypeFile'),
    value: 4,
  },
  {
    label: t('workflow.variableTypeBoolean'),
    value: 5,
  },
]
const submitStatus = computed(() => {
  if (!tmpItem.name.trim() || !tmpItem.title.trim())
    return false
  return tmpItem.type !== 3 || normalizeOptions(tmpItem.options).length > 0
})

function normalizeOptions(values: string[] | undefined) {
  return [...new Set((values || []).map(value => value.trim()).filter(Boolean))]
}

function onEdit(row: Workflow.NodeIODefinition) {
  showModal.value = true
  const idx = props.wfNode.inputConfig.user_inputs.findIndex(item => item.uuid === row.uuid)
  Object.assign(tmpItem, { limit: 10, multiple: false, options: [] }, props.wfNode.inputConfig.user_inputs[idx])
}

function onDelete(row: Workflow.NodeIODefinition) {
  const idx = props.wfNode.inputConfig.user_inputs.findIndex(item => item.uuid === row.uuid)
  wfStore.deleteUserInput(props.workflow.uuid, props.wfNode.uuid, idx)
}

function onShowModal() {
  showModal.value = true
  Object.assign(tmpItem, {
    uuid: uuidv4().replace(/-/g, ''),
    type: 1,
    name: '',
    title: '',
    required: false,
    limit: 10,
    multiple: false,
    options: [],
  })
}

function submitForm() {
  tmpItem.name = tmpItem.name.trim()
  tmpItem.title = tmpItem.title.trim()
  tmpItem.options = tmpItem.type === 3 ? normalizeOptions(tmpItem.options) : []
  showModal.value = false
  const idx = props.wfNode.inputConfig.user_inputs.findIndex(item => item.uuid === tmpItem.uuid)
  if (idx > -1) {
    Object.assign(props.wfNode.inputConfig.user_inputs[idx], { ...tmpItem })
  } else {
    wfStore.addUserInputToNode(props.workflow.uuid, props.wfNode.uuid, { ...tmpItem })
    Object.assign(tmpItem, { uuid: '', type: 1, name: '', label: '', required: false })
  }
}
</script>

<template>
  <div class="start-node-property flex flex-col w-full">
    <NCollapse :default-expanded-names="['1']" class="start-node-property__collapse">
      <NCollapseItem name="1">
        <template #header>
          <div class="start-node-property__section-title">
            {{ t('workflow.inputLabel') }}
          </div>
        </template>
        <div v-if="wfNode.inputConfig.user_inputs.length" class="start-input-list">
          <article v-for="item in wfNode.inputConfig.user_inputs" :key="item.uuid" class="start-input-card">
            <div class="start-input-card__main">
              <div class="start-input-card__title" :title="item.title || item.name">
                {{ item.title || item.name }}
              </div>
              <div class="start-input-card__name" :title="item.name">
                {{ item.name }}
              </div>
            </div>
            <div class="start-input-card__tags">
              <span class="start-input-card__tag">{{ getNameByInputType(item.type) }}</span>
              <span class="start-input-card__tag" :class="{ 'is-required': item.required }">
                {{ item.required ? t('common.yes') : t('common.no') }}{{ t('workflow.variableRequired') }}
              </span>
            </div>
            <div class="start-input-card__actions">
              <NButton quaternary circle size="small" :aria-label="t('common.edit')" @click="onEdit(item)">
                <template #icon>
                  <SvgIcon icon="carbon:edit" />
                </template>
              </NButton>
              <NButton quaternary circle size="small" :aria-label="t('common.delete')" @click="onDelete(item)">
                <template #icon>
                  <SvgIcon icon="carbon:delete" />
                </template>
              </NButton>
            </div>
          </article>
        </div>
        <div v-else class="start-input-empty">
          {{ t('workflow.nodeConfigHint') }}
        </div>
      </NCollapseItem>
    </NCollapse>
    <NButton class="start-node-property__add" dashed @click="onShowModal">
      <template #icon>
        <SvgIcon icon="ri:add-line" />
      </template>
      {{ t('workflow.nodeAddNew') }}
    </NButton>
  </div>
  <NModal v-model:show="showModal" style="width: 90%; max-height: 700px; max-width: 600px" preset="card" :title="t('workflow.nodeSetting')">
    <div class="start-input-modal flex flex-col w-full">
      <div class="start-input-modal__field">
        <label>{{ t('workflow.variableType') }}</label>
        <NSelect v-model:value="tmpItem.type" :options="options" />
      </div>
      <div class="start-input-modal__field">
        <label>{{ t('workflow.nodeName') }}</label>
        <NInput v-model:value="tmpItem.name" maxlength="50" show-count />
      </div>
      <div class="start-input-modal__field">
        <label>{{ t('workflow.variableDisplayTitle') }}</label>
        <NInput v-model:value="tmpItem.title" maxlength="50" show-count />
      </div>
      <div class="start-input-modal__switch-row">
        <label>{{ t('workflow.variableIsRequired') }}</label>
        <NSwitch v-model:value="tmpItem.required" size="small" />
      </div>
      <div v-if="tmpItem.type === 3" class="start-input-modal__switch-row">
        <label>{{ t('workflow.variableMultiSelect') }}</label>
        <NSwitch v-model:value="tmpItem.multiple" />
      </div>
      <div v-if="tmpItem.type === 3" class="start-input-modal__field">
        <label>{{ t('workflow.variableOptions') }}</label>
        <NDynamicInput v-model:value="tmpItem.options" :min="1" :on-create="() => ''">
          <template #default="{ value, index }">
            <NInput
              :value="value"
              :placeholder="t('workflow.variableOptionPlaceholder', { index: index + 1 })"
              maxlength="80"
              @update:value="tmpItem.options[index] = $event"
            />
          </template>
        </NDynamicInput>
      </div>
      <div v-if="tmpItem.type === 4" class="start-input-modal__field">
        <label>{{ t('workflow.variableMaxFileCount') }}</label>
        <NInputNumber v-model:value="tmpItem.limit" />
      </div>
      <NButton block type="primary" :disabled="!submitStatus" @click="submitForm">
        {{ t('common.confirm') }}
      </NButton>
    </div>
  </NModal>
</template>

<style scoped>
.start-node-property__section-title {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 700;
}

.start-input-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.start-input-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  min-height: 58px;
  padding: 10px 8px 10px 11px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 10px;
  background: var(--zhimesh-glass);
}

.start-input-card__main {
  min-width: 0;
}

.start-input-card__title,
.start-input-card__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.start-input-card__title {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 650;
}

.start-input-card__name {
  margin-top: 3px;
  color: var(--zhimesh-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 10px;
}

.start-input-card__tags {
  display: flex;
  gap: 4px;
  align-items: center;
}

.start-input-card__tag {
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
  font-size: 10px;
  line-height: 1.2;
  white-space: nowrap;
}

.start-input-card__tag.is-required {
  color: var(--zhimesh-warning-text);
  background: var(--zhimesh-warning-surface);
}

.start-input-card__actions {
  display: flex;
  gap: 1px;
}

.start-input-card__actions :deep(.n-button) {
  color: var(--zhimesh-text-muted);
}

.start-input-card__actions :deep(.n-button):last-child:hover {
  color: var(--zhimesh-danger-text);
  background: var(--zhimesh-danger-surface);
}

.start-input-empty {
  padding: 20px 8px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  text-align: center;
}

.start-node-property__add {
  width: 100%;
  margin-top: 10px;
}

.start-input-modal {
  gap: 16px;
}

.start-input-modal__field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.start-input-modal label {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 600;
}

.start-input-modal__switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 40px;
  padding: 0 11px;
  border-radius: 9px;
  background: var(--zhimesh-glass-soft);
}
</style>
