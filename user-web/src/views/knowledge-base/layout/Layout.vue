<script setup lang='ts'>
import { computed } from 'vue'
import { NLayout, NLayoutContent } from 'naive-ui'
import Sider from './sider/index.vue'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore } from '@/store'

const appStore = useAppStore()

const { isMobile } = useBasicLayout()

const collapsed = computed(() => appStore.pageSiderCollapsed.knowledgeBase)

const getMobileClass = computed(() => {
  if (isMobile.value)
    return ['rounded-none', 'shadow-none']
  return ['rounded-md', 'dark:border-neutral-800']
})

const getContainerClass = computed(() => {
  return [
    'h-full',
    { 'pl-[260px]': !isMobile.value && !collapsed.value },
  ]
})
</script>

<template>
  <div class="h-full transition-all" style="background: var(--zhimesh-page-bg)" :class="[isMobile ? 'p-0' : '']">
    <div class="h-full overflow-hidden" :class="getMobileClass">
      <NLayout class="z-40 transition knowledge-workspace-layout" :class="getContainerClass" has-sider>
        <Sider />
        <NLayoutContent class="h-full knowledge-workspace-content">
          <RouterView v-slot="{ Component, route }">
            <component :is="Component" :key="route.fullPath" />
          </RouterView>
        </NLayoutContent>
      </NLayout>
    </div>
  </div>
</template>
