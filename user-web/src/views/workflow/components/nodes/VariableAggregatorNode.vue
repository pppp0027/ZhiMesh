<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import CommonNodeHeader from '../CommonNodeHeader.vue'
import { t } from '@/locales'

const props = defineProps<NodeProps>()
const separatorLabel = computed(() => {
  const separator = (props.data.nodeConfig as Workflow.NodeConfigVariableAggregator).separator
  if (separator === ',')
    return t('workflow.aggregatorSeparatorComma')
  if (separator === ' ')
    return t('workflow.aggregatorSeparatorSpace')
  return (separator === '\n' || !separator)
    ? t('workflow.aggregatorSeparatorNewline')
    : t('workflow.aggregatorSeparatorCustom')
})
</script>

<template>
  <div class="flex flex-col w-full">
    <Handle type="target" :position="Position.Left" />
    <Handle type="source" :position="Position.Right" />
    <CommonNodeHeader :wf-node="data" />
    <div class="content_line flex items-center px-2 text-left">
      <span class="truncate">{{ separatorLabel }}</span>
    </div>
  </div>
</template>
