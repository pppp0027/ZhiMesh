<script lang="ts" setup>
import { computed, nextTick } from 'vue'
import { SvgIcon } from '@/components/common'
import { useAppStore, useWfStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'

const appStore = useAppStore()
const workflowStore = useWfStore()
const { isMobile } = useBasicLayout()

const collapsed = computed(() => appStore.pageSiderCollapsed.workflow)
const currentWorkflow = computed(() => workflowStore.getWorkflowInfo(workflowStore.activeUuid))

function handleUpdateCollapsed() {
  appStore.setPageSiderCollapsed('workflow', !collapsed.value)
}

function onScrollToTop() {
  const scrollRef = document.querySelector('#scrollRef')
  if (scrollRef)
    nextTick(() => scrollRef.scrollTop = 0)
}
</script>

<template>
  <header
    class="zhimesh-workspace-header sticky top-0 left-0 right-0 z-30 border-b dark:border-neutral-800"
  >
    <div class="relative flex items-center justify-between min-w-0 overflow-hidden h-14">
      <button
        v-if="isMobile"
        class="flex items-center justify-center w-11 h-11"
        @click="handleUpdateCollapsed"
      >
        <SvgIcon v-if="collapsed" class="text-2xl" icon="ri:align-justify" />
        <SvgIcon v-else class="text-2xl" icon="ri:align-right" />
      </button>
      <h1
        class="flex-1 px-4 pr-6 overflow-hidden cursor-pointer select-none text-ellipsis whitespace-nowrap"
        @dblclick="onScrollToTop"
      >
        {{ currentWorkflow?.title ?? '' }}
      </h1>
    </div>
  </header>
</template>
