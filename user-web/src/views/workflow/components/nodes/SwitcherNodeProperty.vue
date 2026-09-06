<script setup lang="ts">
import { v4 as uuidv4 } from 'uuid'
import { NButton, NCollapse, NCollapseItem, NInput } from 'naive-ui'
import NodeSelector from '../NodeSelector.vue'
import WfVariableSelector from '../WfVariableSelector.vue'
import OperatorSelector from '../OperatorSelector.vue'
import { deleteEdgesBySourceHandle, updateEdgeBySourceHandle } from '@/utils/workflow-util'
import { t } from '@/locales'
import { useWfStore } from '@/store'

interface Props {
  workflow: Workflow.WorkflowInfo
  wfNode: Workflow.WorkflowNode
  uiWorkflow: Workflow.UIWorkflow
}

const props = defineProps<Props>()
const wfStore = useWfStore()
const nodeConfig = props.wfNode.nodeConfig as Workflow.NodeConfigSwitcher

// Switcher no longer has a fallback output. Clean old saved fallback edges as
// soon as its configuration is opened, so a subsequent save persists the new model.
deleteEdgesBySourceHandle(props.workflow, props.uiWorkflow, props.wfNode.uuid, 'default_handle')
delete (props.wfNode.nodeConfig as Record<string, unknown>).default_target_node_uuid

function onConditionSelected(wfInput: Workflow.NodeConfigSwitcherCaseCondition, nodeUuidParamName: string[]) {
  wfInput.node_uuid = nodeUuidParamName[0]
  wfInput.node_param_name = nodeUuidParamName[1]
}

function onCaseNextNodeSelected(wfCase: Workflow.NodeConfigSwitcherCase, nodeUuid: string) {
  wfCase.target_node_uuid = nodeUuid
  updateEdgeBySourceHandle({
    workflow: props.workflow,
    uiWorkflow: props.uiWorkflow,
    source: props.wfNode.uuid,
    sourceHandle: wfCase.uuid,
    target: nodeUuid,
  })
}

function getCaseName(wfCase: Workflow.NodeConfigSwitcherCase, index: number) {
  return wfCase.name || t('workflow.branchCaseIndex', { index: index + 1 })
}

function onCaseNameUpdated(wfCase: Workflow.NodeConfigSwitcherCase, name: string) {
  wfCase.name = name.trim()
}

function createCondition(): Workflow.NodeConfigSwitcherCaseCondition {
  const startNode = wfStore.getStartNode(props.workflow.uuid)
  return {
    uuid: uuidv4().replace(/-/g, ''),
    node_uuid: startNode?.uuid || '',
    node_param_name: startNode?.inputConfig.user_inputs[0]?.name || '',
    operator: '=',
    value: '',
  }
}

function onAddCase() {
  // Each case is one branch output. An edge is created only after its target
  // is selected, so adding a condition never leaves an empty connection behind.
  nodeConfig.cases.push({
    uuid: uuidv4().replace(/-/g, ''),
    name: t('workflow.branchCaseIndex', { index: nodeConfig.cases.length + 1 }),
    operator: 'and',
    target_node_uuid: '',
    conditions: [createCondition()],
  })
}

function onDelCase(nodeCase: Workflow.NodeConfigSwitcherCase) {
  const index = nodeConfig.cases.findIndex(item => item.uuid === nodeCase.uuid)
  if (index === -1)
    return

  // Remove the persisted/UI edge before removing the matching output handle.
  deleteEdgesBySourceHandle(props.workflow, props.uiWorkflow, props.wfNode.uuid, nodeCase.uuid)
  nodeConfig.cases.splice(index, 1)
}
</script>

<template>
  <div class="flex flex-col w-full">
    <NCollapse :default-expanded-names="['0']">
      <NCollapseItem
        v-for="(wfCase, idx) in nodeConfig.cases" :key="wfCase.uuid" :name="`${idx}`"
        class="border border-gray-200 rounded-md m-2"
      >
        <template #header>
          <NInput
            size="small" :value="getCaseName(wfCase, idx)" class="w-52" maxlength="30"
            @click.stop @update:value="onCaseNameUpdated(wfCase, $event)"
          />
        </template>
        <template #header-extra>
          <button
            type="button" class="min-w-11 min-h-11 p-2 border-0 bg-transparent cursor-pointer text-gray-500 hover:text-red-500"
            :aria-label="t('common.delete')" @click.stop="onDelCase(wfCase)"
          >
            X
          </button>
        </template>
        <div class="flex flex-col w-full bg-gray-100 px-3 pb-3 pt-3">
          <!-- Older saved workflows can still contain grouped conditions; they remain editable. -->
          <div v-for="condition in wfCase.conditions" :key="condition.uuid" class="w-full mb-2 flex gap-1">
            <WfVariableSelector
              :workflow="workflow" :wf-node="wfNode" :wf-ref-var="condition"
              :exclude-nodes="[wfNode.wfComponent.name]"
              class="flex-1 h-full max-w-[150px]" @variable-selected="onConditionSelected(condition, $event)"
            />
            <OperatorSelector :selected="condition.operator" class="h-full max-w-[120px]" @operator-selected="(op) => condition.operator = op" />
            <NInput v-show="condition.operator !== 'empty' && condition.operator !== 'not empty'" v-model:value="condition.value" class="flex-1 min-w-16" />
          </div>
          <div class="my-3 border border-gray-200 rounded-md p-3">
            <div class="text-sm text-gray-500 mb-2">{{ t('workflow.nextStep') }}</div>
            <NodeSelector
              :workflow="props.workflow" :wf-node="props.wfNode" :selected="wfCase.target_node_uuid"
              @node-selected="onCaseNextNodeSelected(wfCase, $event)"
            />
          </div>
        </div>
      </NCollapseItem>
    </NCollapse>
    <NButton class="mt-2" dashed @click="onAddCase">
      <span class="font-bold">{{ t('workflow.newCondition') }}</span>
    </NButton>
  </div>
</template>
