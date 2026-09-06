<script lang="ts" setup>
import { ref, watch } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { AnswerNodeProperty, ClassifierNodeProperty, DocumentExtractorNodeProperty, EndNodeProperty, FaqExtractorNodeProperty, GoogleNodeProperty, HttpRequestNodeProperty, HumanFeedbackNodeProperty, KeywordExtractorNodeProperty, KnowledgeRetrievalNodeProperty, MailSendNodeProperty, StartNodeProperty, SwticherNodeProperty, TemplateNodeProperty, TextTransformNodeProperty, VariableAggregatorNodeProperty } from './components/nodes'
import { useWfStore } from '@/store'
import { t } from '@/locales'
import { SvgIcon } from '@/components/common'
import { getIconByComponentName, getIconClassByComponentName } from '@/utils/workflow-util'
import { emptyWorkflowNode } from '@/utils/functions'

interface Props {
  workflow: Workflow.WorkflowInfo
  uiWorkflow: Workflow.UIWorkflow
  hidePropertyPanel: boolean
  wfNode?: Workflow.WorkflowNode
}

const props = withDefaults(defineProps<Props>(), {
  hidePropertyPanel: false,
  wfNode: () => emptyWorkflowNode(),
})

const emit = defineEmits<{
  (e: 'close'): void
}>()

const wfStore = useWfStore()
const nodeTitle = ref<string>(props.wfNode.title)

watch(
  () => props.wfNode,
  (newVal) => {
    if (newVal)
      nodeTitle.value = newVal.title
  },
  { immediate: true, deep: true },
)

watch(
  () => nodeTitle.value,
  (newVal) => {
    if (newVal && props.wfNode)
      wfStore.updateWfNodeTitle(props.workflow.uuid, props.wfNode.uuid, newVal)
  },
  { immediate: true },
)
</script>

<template>
  <aside v-if="!hidePropertyPanel && wfNode" class="workflow-property-panel absolute right-4 top-4 bottom-4 rounded-xl shadow-xl">
    <div class="workflow-property-panel__inner flex flex-col h-full">
      <div class="workflow-property-panel__header flex items-start gap-2 px-4 pt-4 pb-3">
        <div class="workflow-property-panel__icon" :class="getIconClassByComponentName(wfNode.wfComponent.name)">
          <SvgIcon :icon="getIconByComponentName(wfNode.wfComponent.name)" />
        </div>
        <div class="flex-1 min-w-0">
          <div class="workflow-property-panel__title-row flex items-center h-9 mb-1">
            <NInput
              v-model:value="nodeTitle" :placeholder="t('workflow.nodeNamePlaceholder')" class="workflow-property-panel__title-input"
            />
          </div>
          <div class="workflow-property-panel__description">
            {{ t('workflow.componentFunction') }}{{ wfNode.wfComponent.remark || t('workflow.nodeConfigHint') }}
          </div>
        </div>
        <NButton quaternary circle size="small" aria-label="Close" @click="emit('close')">
          <template #icon><SvgIcon icon="ri:close-line" /></template>
        </NButton>
      </div>

      <div class="workflow-property-panel__body flex-1 overflow-y-auto px-4 pb-4">
        <StartNodeProperty
          v-if="wfNode.wfComponent.name === 'Start'" :key="wfNode.uuid" :workflow="workflow"
          :wf-node="wfNode"
        />
        <AnswerNodeProperty
          v-else-if="wfNode.wfComponent.name === 'Answer'" :key="`answer_${wfNode.uuid}`"
          :workflow="workflow" :wf-node="wfNode"
        />
        <ClassifierNodeProperty
          v-else-if="wfNode.wfComponent.name === 'Classifier'" :key="`classifier_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <SwticherNodeProperty
          v-else-if="wfNode.wfComponent.name === 'Switcher'" :key="`switcher_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <TemplateNodeProperty
          v-else-if="wfNode.wfComponent.name === 'Template'" :key="`template_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <TextTransformNodeProperty
          v-else-if="wfNode.wfComponent.name === 'TextTransform'" :key="`texttransform_${wfNode.uuid}`"
          :workflow="workflow" :wf-node="wfNode"
        />
        <VariableAggregatorNodeProperty
          v-else-if="wfNode.wfComponent.name === 'VariableAggregator'" :key="`variableaggregator_${wfNode.uuid}`"
          :workflow="workflow" :wf-node="wfNode"
        />
        <KeywordExtractorNodeProperty
          v-else-if="wfNode.wfComponent.name === 'KeywordExtractor'" :key="`keyword_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <DocumentExtractorNodeProperty
          v-else-if="wfNode.wfComponent.name === 'DocumentExtractor'" :key="`document_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <FaqExtractorNodeProperty
          v-else-if="wfNode.wfComponent.name === 'FaqExtractor'" :key="`faq_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <KnowledgeRetrievalNodeProperty
          v-else-if="wfNode.wfComponent.name === 'KnowledgeRetrieval'" :key="`knowledge_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <GoogleNodeProperty
          v-else-if="wfNode.wfComponent.name === 'Google'" :key="`google_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <HumanFeedbackNodeProperty
          v-else-if="wfNode.wfComponent.name === 'HumanFeedback'" :key="`feedback_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <MailSendNodeProperty
          v-else-if="wfNode.wfComponent.name === 'MailSend'" :key="`mailsend_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <HttpRequestNodeProperty
          v-else-if="wfNode.wfComponent.name === 'HttpRequest'" :key="`httprequest_${wfNode.uuid}`"
          :workflow="workflow" :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <EndNodeProperty
          v-else-if="wfNode.wfComponent.name === 'End'" :key="`end_${wfNode.uuid}`" :workflow="workflow"
          :ui-workflow="uiWorkflow" :wf-node="wfNode"
        />
        <div v-else class="py-10 text-center text-xs text-gray-400">
          {{ t('workflow.nodeConfigUnavailable') }}
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.workflow-property-panel {
  z-index: 15;
  width: min(408px, calc(100% - 32px));
  overflow: hidden;
  border: 1px solid var(--zhimesh-border);
  border-radius: 10px !important;
  background: var(--zhimesh-glass-strong);
  box-shadow: var(--zhimesh-shadow) !important;
}

.workflow-property-panel__header {
  flex: 0 0 auto;
  padding: 15px 16px 14px !important;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-soft);
}

.workflow-property-panel__icon {
  display: flex;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 8px;
  background: var(--zhimesh-glass-soft);
  color: var(--zhimesh-primary) !important;
  font-size: 18px;
}

.workflow-property-panel__title-input {
  --n-border: transparent !important;
  --n-border-hover: var(--zhimesh-primary) !important;
  --n-border-focus: var(--zhimesh-primary) !important;
  --n-box-shadow-focus: 0 0 0 3px var(--zhimesh-focus-ring) !important;
  flex: 1;
}

.workflow-property-panel__title-input :deep(.n-input-wrapper) {
  min-height: 34px;
  padding: 0 7px;
  border-radius: 8px;
  background: transparent;
}

.workflow-property-panel__title-input :deep(.n-input__input-el) {
  color: var(--zhimesh-text);
  font-size: 15px;
  font-weight: 700;
}

.workflow-property-panel__description {
  display: -webkit-box;
  overflow: hidden;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.workflow-property-panel__body {
  padding: 14px 16px 24px !important;
  background: var(--zhimesh-glass-strong);
}

/*
 * Shared property-form contract. All node property components render inside
 * this boundary, so existing nodes and new nodes automatically get the same
 * hierarchy and control treatment without duplicating styles in every node.
 */
.workflow-property-panel__body :deep(.mt-6) {
  margin-top: 18px !important;
  padding-top: 18px;
  border-top: 1px solid var(--zhimesh-border-subtle);
}

.workflow-property-panel__body :deep(.text-xl) {
  color: var(--zhimesh-text);
  font-size: 13px !important;
  font-weight: 650;
  line-height: 1.45;
}

.workflow-property-panel__body :deep(.text-xl.mb-1) {
  margin-bottom: 7px !important;
}

.workflow-property-panel__body :deep(.text-xs) {
  color: var(--zhimesh-text-muted);
  line-height: 1.6;
}

.workflow-property-panel__body :deep(.n-input),
.workflow-property-panel__body :deep(.n-base-selection),
.workflow-property-panel__body :deep(.n-input-number) {
  --n-border: var(--zhimesh-border) !important;
  --n-border-hover: var(--zhimesh-primary) !important;
  --n-border-focus: var(--zhimesh-primary) !important;
  --n-box-shadow-focus: 0 0 0 3px var(--zhimesh-focus-ring) !important;
  border-radius: 10px !important;
  background: var(--zhimesh-glass);
}

.workflow-property-panel__body :deep(.n-input-wrapper),
.workflow-property-panel__body :deep(.n-base-selection-label),
.workflow-property-panel__body :deep(.n-input-number .n-input-wrapper) {
  min-height: 36px;
  border-radius: 10px !important;
}

.workflow-property-panel__body :deep(.n-input--textarea .n-input-wrapper) {
  min-height: 82px;
  padding-top: 5px;
}

.workflow-property-panel__body :deep(.n-collapse) {
  --n-divider-color: var(--zhimesh-border-subtle) !important;
}

.workflow-property-panel__body :deep(.n-collapse-item) {
  overflow: hidden;
  margin: 0 0 10px !important;
  border: 1px solid var(--zhimesh-border-subtle) !important;
  border-radius: 8px !important;
  background: var(--zhimesh-glass);
}

.workflow-property-panel__body :deep(.n-collapse-item__header) {
  min-height: 42px;
  padding: 0 11px !important;
  color: var(--zhimesh-text) !important;
  font-size: 13px;
  font-weight: 650;
  background: var(--zhimesh-glass-soft);
}

.workflow-property-panel__body :deep(.n-collapse-item__content-inner) {
  padding: 10px 11px 12px !important;
}

.workflow-property-panel__body :deep(.n-button) {
  min-height: 34px;
  border-radius: 9px !important;
  font-weight: 600;
}

.workflow-property-panel__body :deep(.n-button--dashed-type) {
  border-color: var(--zhimesh-border);
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.workflow-property-panel__body :deep(.n-list) {
  border-radius: 9px;
  background: var(--zhimesh-glass-soft);
}

.workflow-property-panel__body :deep(.n-list-item) {
  padding: 8px !important;
  border-color: var(--zhimesh-border-subtle) !important;
}

.workflow-property-panel__body :deep(.n-switch) {
  --n-rail-color: var(--zhimesh-border) !important;
  --n-rail-color-active: var(--zhimesh-accent) !important;
}
</style>
