<script setup lang="ts">
import { computed } from 'vue'
import { NInput, NSelect } from 'naive-ui'
import NodePropertyInput from '../NodePropertyInput.vue'
import ReferComment from '../ReferComment.vue'
import { t } from '@/locales'

interface Props {
  workflow: Workflow.WorkflowInfo
  wfNode: Workflow.WorkflowNode
}

const props = defineProps<Props>()
const nodeConfig = props.wfNode.nodeConfig as Workflow.NodeConfigTextTransform
const operationOptions = computed(() => [
  { label: t('workflow.textTransformOperation.trim'), value: 'trim' },
  { label: t('workflow.textTransformOperation.uppercase'), value: 'uppercase' },
  { label: t('workflow.textTransformOperation.lowercase'), value: 'lowercase' },
  { label: t('workflow.textTransformOperation.replace'), value: 'replace' },
])
</script>

<template>
  <div class="flex flex-col w-full">
    <NodePropertyInput :workflow="workflow" :wf-node="wfNode" />
    <div class="mt-6">
      <div class="text-xl mb-1">
        {{ t('workflow.textTransformOperationLabel') }}
      </div>
      <NSelect v-model:value="nodeConfig.operation" :options="operationOptions" />
    </div>
    <template v-if="nodeConfig.operation === 'replace'">
      <div class="mt-6">
        <div class="text-xl mb-1">
          {{ t('workflow.textTransformFind') }}
        </div>
        <NInput v-model:value="nodeConfig.find_text" :placeholder="t('workflow.textTransformFindPlaceholder')" />
      </div>
      <div class="mt-6">
        <div class="text-xl mb-1">
          {{ t('workflow.textTransformReplace') }}
        </div>
        <ReferComment />
        <NInput v-model:value="nodeConfig.replace_text" :placeholder="t('workflow.textTransformReplacePlaceholder')" />
      </div>
    </template>
  </div>
</template>
