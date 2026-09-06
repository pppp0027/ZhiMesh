<script setup lang='ts'>
import { computed, onMounted } from 'vue'
import { NLayout, NLayoutContent } from 'naive-ui'
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import Sider from './sider/index.vue'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore, useWfStore } from '@/store'
import api from '@/api'

const appStore = useAppStore()
const wfStore = useWfStore()
const { activeUuid } = storeToRefs<any>(wfStore)
const uroute = useRoute()
const { uuid: currWfUuid } = uroute.params as { uuid: string; viewType: string }
const { isMobile } = useBasicLayout()
const collapsed = computed(() => appStore.pageSiderCollapsed.workflow)
const getMobileClass = computed(() => {
  if (isMobile.value)
    return ['rounded-none', 'shadow-none']
  return ['rounded-md', 'dark:border-neutral-800']
})
const getContainerClass = computed(() => {
  return [
    'h-full',
    { 'pl-[280px]': !isMobile.value && !collapsed.value },
  ]
})

// F5刷新时
if (currWfUuid !== 'default' && activeUuid.value === 'default') {
  console.log('在工作流列表中按F5刷新时')
  wfStore.setActive(currWfUuid)
}

onMounted(async () => {
  try {
    const { data: operators } = await api.workflowOperators<Workflow.Operator[]>()
    if (operators && operators.length > 0)
      wfStore.setOperators(operators)
    else
      console.log('operators为空')
  } catch (error) {
    console.error('load workflow operators failed', error)
  }
})
</script>

<template>
  <div class="h-full transition-all" style="background: var(--zhimesh-page-bg)" :class="[isMobile ? 'p-0' : '']">
    <div class="h-full overflow-hidden" :class="getMobileClass">
      <NLayout class="workflow-layout-shell z-40 transition" :class="getContainerClass" has-sider>
        <Sider />
        <NLayoutContent class="h-full">
          <RouterView v-slot="{ Component, route }">
            <KeepAlive :max="4">
              <component :is="Component" :key="route.fullPath" />
            </KeepAlive>
          </RouterView>
        </NLayoutContent>
      </NLayout>
    </div>
  </div>
</template>

<style scoped>
.workflow-layout-shell {
  position: relative;
}
</style>
