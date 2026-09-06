<script setup lang="ts">
import { NButton, NInput } from 'naive-ui'
import NodePropertyInput from '../NodePropertyInput.vue'
import ReferComment from '../ReferComment.vue'
import { t } from '@/locales'
interface Props {
  workflow: Workflow.WorkflowInfo
  wfNode: Workflow.WorkflowNode
}

const props = defineProps<Props>()
const nodeConfig = props.wfNode.nodeConfig as Workflow.NodeConfigEnd
const passthroughTemplate = '{input}'
const headingTemplate = '# 执行结果\n\n{input}'

// Older end nodes used the generic "task completed" text, which discarded the
// upstream answer. Upgrade that default when the node is opened for editing.
if (!nodeConfig.result || nodeConfig.result === t('workflow.taskComplete'))
  nodeConfig.result = passthroughTemplate

function useResultTemplate(template: string) {
  nodeConfig.result = template
}
</script>

<template>
  <div class="flex flex-col w-full">
    <NodePropertyInput :workflow="workflow" :wf-node="wfNode" />
    <div class="mt-6">
      <div class="text-xl mb-1">
        {{ t('workflow.finalResultTemplate') }}
      </div>
      <div class="flex flex-col">
        <ReferComment />
        <div class="zhimesh-info-note mb-2 rounded-lg px-3 py-2 text-xs leading-5">
          {{ t('workflow.finalResultTemplateHint') }}
        </div>
        <div class="mb-2 flex flex-wrap gap-2">
          <NButton size="tiny" secondary type="primary" @click="useResultTemplate(passthroughTemplate)">
            {{ t('workflow.useUpstreamOutput') }}
          </NButton>
          <NButton size="tiny" secondary @click="useResultTemplate(headingTemplate)">
            {{ t('workflow.addResultHeading') }}
          </NButton>
        </div>
        <NInput v-model:value="nodeConfig.result" type="textarea" :autosize="{ minRows: 3, maxRows: 10 }" />
      </div>
    </div>
  </div>
</template>
