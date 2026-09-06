<script setup lang="ts">
import { computed } from 'vue'
import { NInput, NSelect } from 'naive-ui'
import NodePropertyInput from '../NodePropertyInput.vue'
import { t } from '@/locales'

interface Props {
  workflow: Workflow.WorkflowInfo
  wfNode: Workflow.WorkflowNode
}

const props = defineProps<Props>()
const nodeConfig = props.wfNode.nodeConfig as Workflow.NodeConfigVariableAggregator
const separatorOptions = computed(() => [
  { label: t('workflow.aggregatorSeparatorNewline'), value: '\n' },
  { label: t('workflow.aggregatorSeparatorSpace'), value: ' ' },
  { label: t('workflow.aggregatorSeparatorComma'), value: ',' },
  { label: t('workflow.aggregatorSeparatorCustom'), value: '__custom__' },
])

function onSeparatorChange(value: string) {
  if (value === '__custom__')
    nodeConfig.separator = ''
  else
    nodeConfig.separator = value
}

const selectedSeparator = computed(() => separatorOptions.value.some(item => item.value === nodeConfig.separator) ? nodeConfig.separator : '__custom__')
</script>

<template>
  <div class="flex flex-col w-full">
    <NodePropertyInput :workflow="workflow" :wf-node="wfNode" />
    <div class="mt-6">
      <div class="text-xl mb-1">
        {{ t('workflow.aggregatorSeparator') }}
      </div>
      <NSelect :value="selectedSeparator" :options="separatorOptions" @update:value="onSeparatorChange" />
      <NInput
        v-if="selectedSeparator === '__custom__'" v-model:value="nodeConfig.separator"
        class="mt-2" :placeholder="t('workflow.aggregatorSeparatorCustomPlaceholder')"
      />
      <div class="mt-2 text-xs text-gray-400">
        {{ t('workflow.aggregatorHint') }}
      </div>
    </div>
  </div>
</template>
