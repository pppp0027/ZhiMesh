<script setup lang="ts">
import { h, inject } from 'vue'
import { NDropdown } from 'naive-ui'
import { SvgIcon } from '@/components/common'
import { getIconByComponentName, getIconClassByComponentName } from '@/utils/workflow-util'
import { useWfStore } from '@/store'
import { t } from '@/locales'
const props = defineProps<Props>()
const options = [
  {
    label: t('common.delete'),
    key: 'delete',
    icon: renderIcon('ri:delete-bin-line'),
  },
]
interface Props {
  wfNode: Workflow.WorkflowNode
}
const wfStore = useWfStore()
const deleteWorkflowNode = inject<(node: Workflow.WorkflowNode) => void>('deleteWorkflowNode')

function renderIcon(icon: string) {
  return () => {
    return h(
      SvgIcon,
      {
        icon,
        class: 'text-base cursor-pointer',
      })
  }
}

function handleSelect(key: string | number) {
  if (key !== 'delete')
    return
  if (deleteWorkflowNode)
    deleteWorkflowNode(props.wfNode)
  else
    wfStore.deleteNode(props.wfNode.workflowUuid, props.wfNode.uuid)
}
</script>

<template>
  <div
    class="workflow-node-header w-full flex border-b divide-gray-400 pb-2 mb-2 font-bold text-base text-left items-center"
  >
    <div class="workflow-node-header__icon flex items-center justify-center w-7 h-7 mr-2 rounded-lg">
      <SvgIcon
        class="workflow-node-header__glyph text-base" :class="getIconClassByComponentName(wfNode.wfComponent.name)"
        :icon="getIconByComponentName(wfNode.wfComponent.name)"
      />
    </div>
    <div class="flex-1 max-h-6 overflow-hidden text-nowrap text-sm">
      {{ wfNode.title }}
    </div>
    <div class="w-6 ml-2">
      <NDropdown v-if="wfNode.wfComponent.name !== 'Start'" trigger="click" :options="options" @select="handleSelect">
        <button
          type="button"
          class="workflow-node-header__more nodrag nopan"
          :aria-label="t('common.action')"
          @click.stop
        >
          <SvgIcon icon="ri:more-fill" />
        </button>
      </NDropdown>
    </div>
  </div>
</template>

<style scoped>
.workflow-node-header {
  border-color: var(--zhimesh-border-subtle);
  color: var(--zhimesh-text);
}

.workflow-node-header__icon {
  background: var(--zhimesh-glass-soft);
}

.workflow-node-header__glyph {
  color: var(--zhimesh-primary) !important;
}

.workflow-node-header__more {
  display: grid;
  width: 24px;
  height: 24px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 7px;
  color: var(--zhimesh-text-muted);
  background: transparent;
  cursor: pointer;
}

.workflow-node-header__more:hover,
.workflow-node-header__more:focus-visible {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}
</style>
