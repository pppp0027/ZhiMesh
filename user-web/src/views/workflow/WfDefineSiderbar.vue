<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NInput } from 'naive-ui'
import { SvgIcon } from '@/components/common'
import { useWfStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { getIconByComponentName, getIconClassByComponentName } from '@/utils/workflow-util'
import { t } from '@/locales'

const emit = defineEmits<{
  (event: 'add', component: Workflow.WorkflowComponent): void
}>()
const wfStore = useWfStore()
const { isMobile } = useBasicLayout()
const search = ref('')
const collapsedGroups = ref<Record<string, boolean>>({})

// Image generation is not part of the current workflow product scope. Keep the
// renderers for historical workflows elsewhere, but do not expose these node
// types to authors through the node library or its search results.
const hiddenComponentNames = new Set(['Agent', 'OpenAiImage', 'Tongyiwanx'])
const groupOrder = ['base', 'ai', 'knowledge', 'logic', 'tools', 'other']
const groupNames: Record<string, string> = {
  base: 'workflow.paletteGroupBase',
  ai: 'workflow.paletteGroupAi',
  knowledge: 'workflow.paletteGroupKnowledge',
  logic: 'workflow.paletteGroupLogic',
  tools: 'workflow.paletteGroupTools',
  other: 'workflow.paletteGroupOther',
}

function getGroup(name: string) {
  if (['Start', 'End'].includes(name))
    return 'base'
  if (['Answer', 'Classifier', 'KeywordExtractor', 'FaqExtractor'].includes(name))
    return 'ai'
  if (['KnowledgeRetrieval', 'DocumentExtractor', 'Template'].includes(name))
    return 'knowledge'
  if (['Switcher', 'HumanFeedback', 'TextTransform', 'VariableAggregator'].includes(name))
    return 'logic'
  if (['Google', 'MailSend', 'HttpRequest'].includes(name))
    return 'tools'
  return 'other'
}

function getTitle(component: Workflow.WorkflowComponent) {
  const translated = t(`workflow.componentTitle.${component.name}`)
  return translated.startsWith('workflow.') ? component.title : translated
}

const enabledComponents = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return wfStore.wfComponents.filter((component) => {
    if (!component.isEnable || hiddenComponentNames.has(component.name))
      return false
    if (!keyword)
      return true
    return `${component.name} ${component.title} ${component.remark} ${getTitle(component)}`.toLowerCase().includes(keyword)
  })
})

const componentGroups = computed(() => {
  const grouped = new Map<string, Workflow.WorkflowComponent[]>()
  enabledComponents.value.forEach((component) => {
    const group = getGroup(component.name)
    const items = grouped.get(group) || []
    items.push(component)
    grouped.set(group, items)
  })
  return groupOrder
    .filter(group => grouped.has(group))
    .map(group => ({
      key: group,
      title: t(groupNames[group]),
      items: grouped.get(group) || [],
    }))
})

function toggleGroup(group: string) {
  collapsedGroups.value[group] = !collapsedGroups.value[group]
}

function onDragStart(event: DragEvent, component: Workflow.WorkflowComponent) {
  if (event.dataTransfer) {
    event.dataTransfer.setData('application/vueflow', component.name)
    event.dataTransfer.effectAllowed = 'move'
  }
}

function handleComponentActivate(component: Workflow.WorkflowComponent) {
  if (isMobile.value)
    emit('add', component)
}
</script>

<template>
  <aside class="workflow-palette flex flex-col h-full">
    <div class="workflow-palette__header">
      <div class="flex items-start justify-between gap-2">
        <div>
          <div class="workflow-palette__title">{{ t('workflow.nodeLibrary') }}</div>
          <div class="workflow-palette__hint">{{ t('workflow.nodeLibraryHint') }}</div>
        </div>
        <span class="workflow-palette__count">{{ enabledComponents.length }}</span>
      </div>
      <NInput
        v-model:value="search"
        size="small"
        clearable
        :placeholder="t('workflow.searchNodes')"
        class="mt-3"
      >
        <template #prefix><SvgIcon icon="ri:search-line" /></template>
      </NInput>
    </div>

    <div class="workflow-palette__body flex-1 overflow-y-auto">
      <div v-if="!enabledComponents.length" class="workflow-palette__empty">
        <SvgIcon icon="ri:search-eye-line" class="text-2xl" />
        <span>{{ t('workflow.noEnabledNodes') }}</span>
      </div>

      <div v-else>
        <section v-for="group in componentGroups" :key="group.key" class="workflow-palette__group">
          <button class="workflow-palette__group-title" @click="toggleGroup(group.key)">
            <span>{{ group.title }}</span>
            <span class="flex items-center gap-2">
              <small>{{ group.items.length }}</small>
              <SvgIcon :icon="collapsedGroups[group.key] ? 'ri:arrow-down-s-line' : 'ri:arrow-up-s-line'" />
            </span>
          </button>
          <div v-show="!collapsedGroups[group.key]" class="workflow-palette__items">
            <div
              v-for="component in group.items"
              :key="component.uuid"
              class="workflow-palette__item"
              :title="component.remark || getTitle(component)"
              draggable="true"
              @dragstart="(event: DragEvent) => onDragStart(event, component)"
              @click="handleComponentActivate(component)"
            >
              <div class="workflow-palette__item-icon" :class="getIconClassByComponentName(component.name)">
                <SvgIcon :icon="getIconByComponentName(component.name)" />
              </div>
              <div class="min-w-0">
                <div class="workflow-palette__item-title">{{ getTitle(component) }}</div>
                <div class="workflow-palette__item-desc">{{ component.remark || t('workflow.dragToCanvas') }}</div>
              </div>
              <SvgIcon class="workflow-palette__drag" icon="ri:drag-move-2-line" />
            </div>
          </div>
        </section>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.workflow-palette {
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-strong);
}

.workflow-palette__header {
  padding: 14px 14px 11px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.workflow-palette__title {
  color: var(--zhimesh-text);
  font-size: 14px;
  font-weight: 700;
}

.workflow-palette__hint,
.workflow-palette__item-desc {
  overflow: hidden;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-palette__count {
  display: inline-flex;
  min-width: 22px;
  height: 20px;
  align-items: center;
  justify-content: center;
  color: var(--zhimesh-primary);
  border-radius: 999px;
  background: var(--zhimesh-glass-soft);
  font-size: 11px;
}

.workflow-palette__body {
  padding: 6px 8px 14px;
}

.workflow-palette__group {
  padding-bottom: 4px;
}

.workflow-palette__group-title {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 8px 5px 5px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.workflow-palette__group-title:hover {
  color: var(--zhimesh-primary);
}

.workflow-palette__group-title small {
  color: var(--zhimesh-text-muted);
  font-size: 10px;
  font-weight: 500;
}

.workflow-palette__items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.workflow-palette__item {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 7px;
  padding: 5px 7px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 8px;
  background: var(--zhimesh-glass);
  cursor: grab;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}

.workflow-palette__item:hover {
  border-color: var(--zhimesh-border);
  box-shadow: var(--zhimesh-shadow-soft);
  transform: translateX(2px);
}

.workflow-palette__item:active {
  cursor: grabbing;
}

.workflow-palette__item-icon {
  display: flex;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--zhimesh-glass-soft);
  font-size: 15px;
}

.workflow-palette__item-title {
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 11.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-palette__drag {
  margin-left: auto;
  color: var(--zhimesh-text-muted);
  font-size: 13px;
  opacity: 0.45;
  transition: opacity 0.15s ease;
}

.workflow-palette__item:hover .workflow-palette__drag {
  opacity: 1;
}

.workflow-palette__empty {
  display: flex;
  min-height: 180px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  text-align: center;
}

@media (max-width: 767px) {
  .workflow-palette__header {
    padding-right: 54px;
  }

  .workflow-palette__item {
    min-height: 52px;
    cursor: pointer;
  }

  .workflow-palette__drag {
    display: none;
  }
}
</style>
