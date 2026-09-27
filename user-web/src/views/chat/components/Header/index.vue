<script lang="ts" setup>
import { computed } from 'vue'
import { SvgIcon } from '@/components/common'
import { useAppStore, useChatStore } from '@/store'

interface Emit {
  (ev: 'export'): void
  (ev: 'scrollToTop'): void
}

const emit = defineEmits<Emit>()

const appStore = useAppStore()
const chatStore = useChatStore()

const collapsed = computed(() => appStore.pageSiderCollapsed.chat)
const currentChatHistory = computed(() => chatStore.getCurCharacter)

function handleUpdateCollapsed() {
  appStore.setPageSiderCollapsed('chat', !collapsed.value)
}

function onScrollToTop() {
  // 滚动容器由父页面持有，通过事件让其滚动到顶部（替代 querySelector 找不到元素的旧实现）
  emit('scrollToTop')
}

// function handleExport() {
//   emit('export')
// }
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
        {{ currentChatHistory?.title ?? '' }}
      </h1>
      <!-- 连续对话/深度思考开关已移除：上下文恒开启，深度思考按模型能力自动判定 -->
      <div class="flex items-center space-x-2">
        <!-- <HoverButton @click="handleExport">
          <span class="text-xl text-[#4f555e] dark:text-white">
            <SvgIcon icon="ri:download-2-line" />
          </span>
        </HoverButton> -->
      </div>
    </div>
  </header>
</template>

