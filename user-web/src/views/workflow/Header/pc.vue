<script lang="ts" setup>
import { computed, h, ref, watch } from 'vue'
import { NButton, NDropdown, NTag, useMessage } from 'naive-ui'
import { ApiKeyModal, SvgIcon } from '@/components/common'
import { emptyWorkflowInfo } from '@/utils/functions'
import { useAuthStore, useUserStore, useWfStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
interface Props {
  workflow: Workflow.WorkflowInfo
  activeView?: string
}
interface Emit {
  (ev: 'showView', type: string): void
}
const props = withDefaults(defineProps<Props>(), {
  workflow: () => emptyWorkflowInfo(),
  activeView: 'workflowDefine',
})
const emit = defineEmits<Emit>()
const ms = useMessage()
const wfStore = useWfStore()
const authStore = useAuthStore()
const userStore = useUserStore()
const showViewType = ref<string>(props.activeView)
const submitting = ref<boolean>(false)
const showApiKeyModal = ref(false)

const startNodeUserInputs = computed(() => {
  const startNode = props.workflow.nodes?.find(n => n.wfComponent?.name === 'Start')
  return startNode?.inputConfig?.user_inputs ?? []
})

watch(() => props.activeView, (value) => {
  showViewType.value = value || 'workflowDefine'
})
const options = computed(() => {
  const mine = props.workflow.userUuid === userStore.userInfo.uuid
  const common = [
    {
      label: mine ? t('common.edit') : t('common.view'),
      key: 'edit',
      icon: renderIcon(mine ? 'carbon:edit' : 'carbon:information'),
    },
  ]
  if (authStore.token) {
    common.push({
      label: t('workflow.copyLabel'),
      key: 'copy',
      icon: renderIcon('ri:file-copy-2-line'),
    })
  }
  common.push({
    label: t('extApi.apiAccess'),
    key: 'api',
    icon: renderIcon('carbon:api'),
  })
  return common
})

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

function toogleView(viewType?: string) {
  if (!authStore.checkLoginOrShow())
    return
  showViewType.value = viewType || (showViewType.value === 'instanceList' ? 'workflowDefine' : 'instanceList')
  emit('showView', showViewType.value)
}

function showEditView() {
  if (!authStore.checkLoginOrShow())
    return
  wfStore.setShowCreateView(true, props.workflow.uuid)
}

async function onCopy() {
  const { data: newWorkflow } = await api.workflowCopy(props.workflow.uuid)
  wfStore.appendWorkflows([newWorkflow], true)
  ms.success(t('workflow.copySuccess'))
}

function handleSelect(key: string | number) {
  if (submitting.value)
    return
  submitting.value = true
  try {
    if (key === 'edit')
      showEditView()
    else if (key === 'copy')
      onCopy()
    else if (key === 'api')
      extApiKey()
  } catch (e: any) {
    ms.error(e)
  } finally {
    submitting.value = false
  }
}

function extApiKey() {
  showApiKeyModal.value = true
}
</script>

<template>
  <header
    class="workflow-header sticky top-0 left-0 z-30 border-b dark:border-neutral-800"
  >
    <div class="workflow-header__inner relative flex items-center justify-between px-4 min-h-[56px]">
      <div class="workflow-header__identity flex items-center min-w-0">
        <div class="workflow-header__icon flex items-center justify-center mr-3">
          <SvgIcon class="text-xl" icon="carbon:flow-data" />
        </div>
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <h1 class="workflow-header__title overflow-hidden text-ellipsis whitespace-nowrap">
              {{ workflow?.title || t('workflow.workflowLabel') }}
            </h1>
            <NTag v-if="workflow?.isPublic" size="small" round :bordered="false" type="success">
              {{ t('common.public') }}
            </NTag>
            <NTag v-else size="small" round :bordered="false" type="default">
              {{ t('common.private') }}
            </NTag>
          </div>
        </div>
      </div>
      <div class="workflow-header__actions flex items-center gap-2 ml-4">
        <nav class="workflow-header__view-switch" :aria-label="t('workflow.workspaceView')">
          <NButton
            size="small" :type="showViewType === 'workflowDefine' ? 'primary' : 'default'"
            :secondary="showViewType !== 'workflowDefine'" @click="toogleView('workflowDefine')"
          >
            <template #icon>
              <SvgIcon icon="carbon:flow-data" />
            </template>
            {{ t('workflow.designWorkspace') }}
          </NButton>
          <NButton
            size="small" :type="showViewType === 'instanceList' ? 'primary' : 'default'"
            :secondary="showViewType !== 'instanceList'" @click="toogleView('instanceList')"
          >
            <template #icon>
              <SvgIcon icon="ri:history-line" />
            </template>
            {{ t('workflow.runHistory') }}
          </NButton>
        </nav>
        <NDropdown :options="options" @select="handleSelect">
          <NButton quaternary circle size="small" :aria-label="t('workflow.moreActions')">
            <template #icon>
              <SvgIcon icon="ri:more-fill" />
            </template>
          </NButton>
        </NDropdown>
      </div>
    </div>
    <ApiKeyModal v-model:show="showApiKeyModal" type="workflow" :uuid="workflow?.uuid ?? ''" :title="workflow?.title || t('workflow.workflowLabel')" :wf-input-defs="startNodeUserInputs" />
  </header>
</template>

<style scoped>
.workflow-header__view-switch {
  display: flex;
  gap: 4px;
  padding: 3px;
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
}

.workflow-header__icon {
  width: 32px;
  height: 32px;
  color: var(--zhimesh-primary);
  border: 1px solid var(--zhimesh-border);
  border-radius: 11px;
  background: var(--zhimesh-glass-soft);
}

.workflow-header__title {
  max-width: min(42vw, 560px);
  color: var(--zhimesh-text);
  font-size: 16px;
  font-weight: 700;
}

@media (max-width: 900px) {
  .workflow-header .n-tag {
    display: none;
  }

  .workflow-header__actions {
    margin-left: 8px;
  }
}
</style>
