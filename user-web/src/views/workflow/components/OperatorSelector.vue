<script setup lang="ts">
import { ref } from 'vue'
import { NSelect } from 'naive-ui'
import type { SelectGroupOption, SelectOption } from 'naive-ui'
import { useWfStore } from '@/store'
import { t } from '@/locales'

interface Props {
  selected: string
}
const props = withDefaults(defineProps<Props>(), {
  selected: () => '',
})

const emit = defineEmits<Emit>()
interface Emit {
  (e: 'operatorSelected', uuid: string): void
}
const wfStore = useWfStore()

const selected = ref<string>(props.selected || '')
const operatorLabels: Record<string, string> = {
  'contains': t('workflow.operatorContains'),
  'not contains': t('workflow.operatorNotContains'),
  'start with': t('workflow.operatorStartsWith'),
  'end with': t('workflow.operatorEndsWith'),
  'empty': t('workflow.operatorIsEmpty'),
  'not empty': t('workflow.operatorIsNotEmpty'),
  '=': t('workflow.operatorEquals'),
  '!=': t('workflow.operatorNotEquals'),
  '>': t('workflow.operatorGreaterThan'),
  '>=': t('workflow.operatorGreaterOrEqual'),
  '<': t('workflow.operatorLessThan'),
  '<=': t('workflow.operatorLessOrEqual'),
}
const options: Array<SelectOption | SelectGroupOption> = []
for (let i = 0; i < wfStore.operators.length; i++) {
  const operator = wfStore.operators[i]
  options.push({
    // Keep the persisted operator value unchanged. Only the displayed label is
    // localized so existing workflow conditions remain fully compatible.
    label: operatorLabels[operator.name] || operator.desc,
    value: operator.name,
  })
}

// if(options.length > 0) {
//   selected.value = options[0].key as string
// }

function handleSelect(value: string) {
  console.log('node selected', value)
  emit('operatorSelected', value)
}
</script>

<template>
  <NSelect
    v-model:value="selected" placement="top-start" trigger="click" :show-arrow="true" :options="options"
    :consistent-menu-width="false" @update:value="handleSelect"
  />
</template>
