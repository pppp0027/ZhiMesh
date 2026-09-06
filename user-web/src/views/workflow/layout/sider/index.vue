<script setup lang='ts'>
import type { CSSProperties } from 'vue'
import { computed, watch } from 'vue'
import { NLayoutSider } from 'naive-ui'
import List from './List.vue'
import CreateWorkflow from './CreateWorkflow.vue'
import { SiderAccountBar } from '@/components/common'
import { useAppStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { t } from '@/locales'

const appStore = useAppStore()

const { isMobile } = useBasicLayout()

const collapsed = computed(() => appStore.pageSiderCollapsed.workflow)

function handleUpdateCollapsed() {
  appStore.setPageSiderCollapsed('workflow', !collapsed.value)
}

const getMobileClass = computed<CSSProperties>(() => {
  if (isMobile.value) {
    return {
      position: 'fixed',
      top: '0',
      bottom: 'calc(68px + env(safe-area-inset-bottom))',
      height: 'auto',
      zIndex: 50,
    }
  }
  return {}
})

const mobileSafeArea = computed(() => {
  if (isMobile.value) {
    return {
      paddingTop: 'env(safe-area-inset-top)',
    }
  }
  return {}
})

watch(
  isMobile,
  (val) => {
    appStore.setPageSiderCollapsed('workflow', val)
  },
  {
    immediate: true,
    flush: 'post',
  },
)
</script>

<template>
  <NLayoutSider
    :collapsed="collapsed" :collapsed-width="0" :width="280" :show-trigger="false"
    position="absolute" bordered class="user-section-sider workflow-sider" :style="getMobileClass" style="z-index: 50" @update-collapsed="handleUpdateCollapsed"
  >
    <div class="flex flex-col h-full" :style="mobileSafeArea">
      <main class="flex flex-col flex-1 min-h-0">
        <List class="flex-1 min-h-0 pb-4" @collapse="handleUpdateCollapsed" />
      </main>
      <SiderAccountBar v-if="isMobile" />
    </div>
  </NLayoutSider>
  <button
    v-if="!isMobile && collapsed"
    class="workflow-sider-reveal"
    type="button"
    :aria-label="t('common.expandSidebar')"
    :title="t('common.expandSidebar')"
    @click="handleUpdateCollapsed"
  >
    <span class="workflow-sider-reveal__chevron" />
  </button>
  <template v-if="isMobile">
    <div v-show="!collapsed" class="fixed inset-0 z-40 bg-black/40" @click="handleUpdateCollapsed" />
  </template>
  <CreateWorkflow />
</template>

<style scoped>
.workflow-sider-reveal {
  position: absolute;
  top: 50%;
  left: 0;
  z-index: 65;
  display: flex;
  width: 28px;
  height: 64px;
  padding: 0;
  align-items: center;
  justify-content: center;
  color: var(--zhimesh-text-muted);
  border: 1px solid var(--zhimesh-glass-edge);
  border-left: 0;
  border-radius: 0 10px 10px 0;
  background: var(--zhimesh-glass-soft-alpha);
  box-shadow: var(--zhimesh-shadow-soft), inset 0 1px 0 var(--zhimesh-glass-highlight);
  cursor: pointer;
  transform: translateY(-50%);
  transition: color 180ms ease, border-color 180ms ease, background-color 180ms ease, box-shadow 180ms ease;
}

.workflow-sider-reveal:hover {
  color: var(--zhimesh-primary);
  border-color: var(--zhimesh-border);
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: var(--zhimesh-shadow-soft), inset 0 1px 0 var(--zhimesh-glass-highlight);
}

.workflow-sider-reveal:focus-visible {
  outline: 3px solid rgba(35, 74, 122, 0.28);
  outline-offset: 2px;
}

.workflow-sider-reveal__chevron {
  display: block;
  width: 9px;
  height: 9px;
  border-right: 2.5px solid currentColor;
  border-bottom: 2.5px solid currentColor;
  border-radius: 1px;
  transform: translateX(-2px) rotate(-45deg);
}
</style>
