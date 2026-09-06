<script setup lang='ts'>
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { NButton, NTabPane, NTabs } from 'naive-ui'
import SubList from './SubList.vue'
import { useAuthStore, useWfStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
import { SvgIcon } from '@/components/common'

const emit = defineEmits<{
  (e: 'collapse'): void
}>()

const currentPage = ref<number>(1)
const pageSize = 20
const wfStore = useWfStore()
const { activeUuid, myWorkflows, publicWorkflows, selectedType } = storeToRefs<any>(wfStore)
const authStore = useAuthStore()
const authStoreRef = ref<AuthState>(authStore)
const innerHeight = window.innerHeight < 800 ? 800 : window.innerHeight - 60

async function initAll() {
  await loadWfComponents()
  await initMyList()
  await initPublicList()
}

async function initWhenNotLogin() {
  await loadWfComponents()
  await initPublicList()
}

async function loadWfComponents() {
  if (wfStore.wfComponents.length > 0)
    return

  console.log('load wf components')
  const { data: components } = await api.workflowComponents<Workflow.WorkflowComponent[]>()
  if (components && components.length > 0)
    wfStore.setWorkflowComponents(components)
  else
    console.log('workflow components is null')
}

async function initMyList() {
  if (wfStore.loadingMyWorkflows || wfStore.myWorkflows.length > 0)
    return
  console.log('load my workflows')
  wfStore.setLoadingMyWorkflows(true)
  try {
    const { data } = await api.workflowSearchMine<Workflow.InfoListResp>('', currentPage.value, pageSize)
    console.log('loaded my workflows')
    if (data.records) {
      wfStore.appendWorkflows(data.records, true)
      // 首次打开工作流时，只在列表确实有数据时进入详情。
      if (activeUuid.value === 'default' && myWorkflows.value.length > 0) {
        console.log('首次打开工作流')
        wfStore.setActiveAndGo(myWorkflows.value[0].uuid)
      }
    }
  } catch (e) {
    console.error(e)
  } finally {
    wfStore.setLoadingMyWorkflows(false)
  }
}

async function initPublicList() {
  if (wfStore.loadingPublicWorkflows || wfStore.publicWorkflows.length > 0)
    return
  wfStore.setLoadingPublicWorkflows(true)
  try {
    const { data: publicData } = await api.workflowSearchPublic<Workflow.InfoListResp>('', currentPage.value, pageSize)
    if (publicData.records) {
      wfStore.appendWorkflows(publicData.records, false)
      // 没有私有工作流时，仍然进入第一个可用的公开应用。
      if (activeUuid.value === 'default' && publicWorkflows.value.length > 0) {
        selectedType.value = 'public'
        wfStore.setActiveAndGo(publicWorkflows.value[0].uuid)
      }
    }
  } catch (e) {
    console.error(e)
  } finally {
    wfStore.setLoadingPublicWorkflows(false)
  }
}

function handleAdd(this: any) {
  wfStore.setShowCreateView(true, '')
}

watch(
  () => authStoreRef.value.token,
  (newVal) => {
    if (newVal)
      initMyList()
  },
)

onMounted(() => {
  console.log('workflow list onMounted')
  if (authStoreRef.value.token)
    initAll()
  else
    initWhenNotLogin()
})
</script>

<template>
  <div class="workflow-list flex flex-col h-full">
    <div class="workflow-list__brand flex items-center justify-between px-5 pt-5 pb-4">
      <div class="flex items-center min-w-0">
        <div class="workflow-list__brand-icon flex items-center justify-center mr-3">
          <SvgIcon class="text-xl" icon="carbon:flow-data" />
        </div>
        <div class="min-w-0">
          <div class="workflow-list__title">
            {{ t('workflow.workspaceTitle') }}
          </div>
          <div class="workflow-list__hint">
            {{ t('workflow.workspaceHint') }}
          </div>
        </div>
      </div>
      <button
        class="workflow-list__collapse"
        type="button"
        :aria-label="t('common.collapseSidebar')"
        :title="t('common.collapseSidebar')"
        @click="emit('collapse')"
      >
        <span class="workflow-list__collapse-icon" />
      </button>
    </div>
    <NTabs v-model:value="selectedType" class="workflow-list__tabs" tab-class="h-11" pane-class="h-full" type="line" justify-content="space-evenly">
      <NTabPane name="mine" :tab="t('common.mine')" size="small">
        <div class="workflow-list__pane flex flex-col" :style="`height:${innerHeight - 76}px`">
          <div class="px-4 pt-4">
            <NButton class="workflow-list__create" type="primary" strong block @click="handleAdd">
              <template #icon>
                <SvgIcon icon="ri:add-line" />
              </template>
              {{ t('workflow.newWorkflow') }}
            </NButton>
            <div class="workflow-list__count">
              {{ myWorkflows.length }} {{ t('workflow.workflowCount') }}
            </div>
          </div>
          <SubList :list="myWorkflows" :active-wf-uuid="activeUuid" />
        </div>
      </NTabPane>
      <NTabPane name="public" :tab="t('common.public')">
        <div class="workflow-list__pane flex flex-col" :style="`height:${innerHeight - 76}px`">
          <div class="workflow-list__count px-4 pt-4">
            {{ publicWorkflows.length }} {{ t('workflow.workflowCount') }}
          </div>
          <SubList :list="publicWorkflows" :active-wf-uuid="activeUuid" />
        </div>
      </NTabPane>
    </NTabs>
  </div>
</template>

<style scoped>
.workflow-list {
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass);
}

.workflow-list__brand-icon {
  width: 38px;
  height: 38px;
  color: var(--zhimesh-primary);
  border: 1px solid var(--zhimesh-border);
  border-radius: 11px;
  background: var(--zhimesh-glass-soft);
}

.workflow-list__collapse {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  margin-left: 10px;
  place-items: center;
  color: var(--zhimesh-text-muted);
  border: 1px solid var(--zhimesh-border);
  border-radius: 8px;
  background: var(--zhimesh-glass-soft);
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.workflow-list__collapse:hover,
.workflow-list__collapse:focus-visible {
  color: var(--zhimesh-text);
  border-color: var(--zhimesh-border-hover, var(--zhimesh-border));
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: 0 3px 10px rgba(15, 23, 42, 0.09);
  outline: none;
}

.workflow-list__collapse-icon {
  position: relative;
  display: block;
  width: 16px;
  height: 16px;
  border: 2px solid currentColor;
  border-radius: 4px;
}

.workflow-list__collapse-icon::before {
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 4px;
  width: 2px;
  border-radius: 999px;
  background: currentColor;
  content: '';
}

.workflow-list__title {
  font-size: 15px;
  font-weight: 700;
  color: var(--zhimesh-text);
}

.workflow-list__hint,
.workflow-list__count {
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.workflow-list__tabs {
  min-height: 0;
  flex: 1;
}

.workflow-list__pane {
  min-height: 0;
}

.workflow-list__create {
  height: 42px;
  border-radius: 10px;
  box-shadow: none;
}

.workflow-list__count {
  padding-top: 10px;
  padding-bottom: 10px;
}
</style>
