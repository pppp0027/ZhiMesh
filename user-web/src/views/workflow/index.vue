<script setup lang='ts'>
import { computed } from 'vue'
import { NButton, NSpin } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import HeaderComponent from './Header/index.vue'
import PCHeader from './Header/pc.vue'
import WfRuntimeList from './WfRuntimeList.vue'
import WorkflowDefine from './WorkflowDefine.vue'
import LoginTip from '@/views/user/LoginTip.vue'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore, useAuthStore, useWfStore } from '@/store'
import { emptyWorkflowInfo } from '@/utils/functions'
import { t } from '@/locales'
import { SvgIcon } from '@/components/common'

console.log('workflow index')
const route = useRoute()
const router = useRouter()
const workflowStore = useWfStore()
const authStore = useAuthStore()
const appStore = useAppStore()
const { isMobile } = useBasicLayout()
const currWfUuid = computed(() => String(route.params.uuid || ''))
const selectedViewType = computed(() => route.query.view === 'history' ? 'instanceList' : 'workflowDefine')
console.log('authStore.token', authStore.token)
const currWorkflowInfo = computed(() => {
  return workflowStore.getWorkflowInfo(currWfUuid.value) || emptyWorkflowInfo()
})
const workflowLoading = computed(() => workflowStore.loadingMyWorkflows || workflowStore.loadingPublicWorkflows)

function openWorkflowList() {
  appStore.setPageSiderCollapsed('workflow', false)
}

function createWorkflow() {
  workflowStore.setShowCreateView(true, '')
}

function showView(viewType: string) {
  const view = viewType === 'instanceList' ? 'history' : undefined
  router.replace({
    name: 'WfDetail',
    params: { uuid: currWfUuid.value },
    query: { ...route.query, view },
  })
}
</script>

<template>
  <div class="chat-box flex flex-col w-full h-full">
    <HeaderComponent v-if="isMobile" :using-context="false" />
    <nav v-if="isMobile" class="workflow-mobile-view-switch" :aria-label="t('workflow.workspaceView')">
      <NButton
        size="small" :type="selectedViewType === 'workflowDefine' ? 'primary' : 'default'"
        :secondary="selectedViewType !== 'workflowDefine'" @click="showView('workflowDefine')"
      >
        {{ t('workflow.designWorkspace') }}
      </NButton>
      <NButton
        size="small" :type="selectedViewType === 'instanceList' ? 'primary' : 'default'"
        :secondary="selectedViewType !== 'instanceList'" @click="showView('instanceList')"
      >
        {{ t('workflow.runHistory') }}
      </NButton>
    </nav>
    <PCHeader
      v-else
      :workflow="currWorkflowInfo"
      :active-view="selectedViewType"
      @show-view="showView"
    />
    <template v-if="!authStore.token">
      <LoginTip />
    </template>
    <template v-if="authStore.token && currWorkflowInfo.uuid">
      <WfRuntimeList
        :workflow="currWorkflowInfo"
        :show="selectedViewType === 'instanceList'"
      />
      <WorkflowDefine v-show="selectedViewType === 'workflowDefine'" :workflow="currWorkflowInfo" />
    </template>
    <section v-else-if="authStore.token" class="workflow-empty-state" aria-live="polite">
      <NSpin v-if="workflowLoading" size="medium" />
      <template v-else>
        <div class="workflow-empty-state__icon" aria-hidden="true">
          <SvgIcon icon="ri:apps-2-line" />
        </div>
        <h2>{{ t('workflow.selectWorkflowTitle') }}</h2>
        <p>{{ t('workflow.selectWorkflowHint') }}</p>
        <div class="workflow-empty-state__actions">
          <NButton size="large" @click="openWorkflowList">
            {{ t('workflow.openWorkflowList') }}
          </NButton>
          <NButton type="primary" size="large" @click="createWorkflow">
            {{ t('workflow.newWorkflow') }}
          </NButton>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.workflow-mobile-view-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-nav);
}

.workflow-empty-state {
  display: flex;
  min-height: 0;
  flex: 1;
  padding: 32px 20px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  text-align: center;
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-soft);
}

.workflow-empty-state__icon {
  display: grid;
  width: 52px;
  height: 52px;
  margin-bottom: 16px;
  place-items: center;
  border-radius: 14px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
  font-size: 25px;
}

.workflow-empty-state h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
}

.workflow-empty-state p {
  max-width: 36ch;
  margin: 8px 0 20px;
  color: var(--zhimesh-text-muted);
  font-size: 14px;
  line-height: 1.6;
}

.workflow-empty-state__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

@media (max-width: 767px) {
  .workflow-empty-state {
    padding-bottom: calc(32px + env(safe-area-inset-bottom));
  }

  .workflow-empty-state__actions :deep(.n-button) {
    min-width: 132px;
    min-height: 44px;
  }
}
</style>
