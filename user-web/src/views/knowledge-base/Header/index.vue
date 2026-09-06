<script lang="ts" setup>
import { computed, nextTick } from 'vue'
import { SvgIcon } from '@/components/common'
import { useAppStore, useKbStore } from '@/store'

const appStore = useAppStore()
const kbStore = useKbStore()

const collapsed = computed(() => appStore.pageSiderCollapsed.knowledgeBase)
const currentKb = computed(() => kbStore.getSelectedKb)

function handleUpdateCollapsed() {
  appStore.setPageSiderCollapsed('knowledgeBase', !collapsed.value)
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
        {{ currentKb?.title ?? '' }}
      </h1>
    </div>
  </header>
</template>
