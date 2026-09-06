<script setup lang='ts'>
import type { CSSProperties } from 'vue'
import { computed, onMounted, watch } from 'vue'
import { NButton, NLayoutSider } from 'naive-ui'
import List from './List.vue'
import { SiderAccountBar } from '@/components/common'
import { useAppStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { t } from '@/locales'

const appStore = useAppStore()

const { isMobile } = useBasicLayout()

const collapsed = computed(() => appStore.pageSiderCollapsed.knowledgeBase)

function handleUpdateCollapsed() {
  appStore.setPageSiderCollapsed('knowledgeBase', !collapsed.value)
}

const getMobileClass = computed<CSSProperties>(() => {
  if (isMobile.value) {
    return {
      position: 'fixed',
      top: '0',
      bottom: '0',
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
      paddingBottom: '0',
      boxSizing: 'border-box',
    }
  }
  return {}
})

watch(
  isMobile,
  (val) => {
    appStore.setPageSiderCollapsed('knowledgeBase', val)
  },
  {
    immediate: true,
    flush: 'post',
  },
)

onMounted(async () => {
  console.info('kb index,onmounted')
})
</script>

<template>
  <NLayoutSider
    :collapsed="collapsed" :collapsed-width="0" :width="260" :show-trigger="false"
    position="absolute" bordered class="user-section-sider knowledge-sider" :style="getMobileClass" @update-collapsed="handleUpdateCollapsed"
  >
    <div class="relative flex flex-col h-full" :style="mobileSafeArea">
      <main class="flex flex-col flex-1 min-h-0">
        <div v-if="!isMobile" class="knowledge-sider-heading">
          <span>{{ t('menu.knowledgeBase') }}</span>
          <button
            class="section-sider-collapse"
            type="button"
            :aria-label="t('common.collapseSidebar')"
            :title="t('common.collapseSidebar')"
            @click="handleUpdateCollapsed"
          >
            <span class="section-sider-collapse__icon" />
          </button>
        </div>
        <List class="flex-1 min-h-0" />
      </main>
      <div class="knowledge-sider-footer">
        <div class="knowledge-manage-action">
          <NButton secondary block @click="$router.push({ name: 'KnowledgeBaseManage' })">
            {{ t('chat.knowledgeBaseManage') }}
          </NButton>
        </div>
        <SiderAccountBar v-if="isMobile" />
      </div>
    </div>
  </NLayoutSider>
  <button
    v-if="!isMobile && collapsed"
    class="section-sider-reveal"
    type="button"
    :aria-label="t('common.expandSidebar')"
    :title="t('common.expandSidebar')"
    @click="handleUpdateCollapsed"
  >
    <span class="section-sider-reveal__chevron" />
  </button>
  <template v-if="isMobile">
    <div v-show="!collapsed" class="fixed inset-0 z-40 bg-black/40" @click="handleUpdateCollapsed" />
  </template>
</template>

<style scoped>
.knowledge-sider-heading {
  display: flex;
  height: 48px;
  padding: 8px 12px 6px 16px;
  align-items: center;
  justify-content: space-between;
  color: var(--zhimesh-text);
  font-size: 14px;
  font-weight: 700;
}

.section-sider-collapse {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  margin-left: 10px;
  padding: 0;
  place-items: center;
  color: var(--zhimesh-text-muted);
  border: 1px solid var(--zhimesh-border);
  border-radius: 8px;
  background: var(--zhimesh-glass-soft);
  cursor: pointer;
  transition: color 0.16s ease, border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease;
}

.section-sider-collapse:hover,
.section-sider-collapse:focus-visible {
  color: var(--zhimesh-text);
  border-color: var(--zhimesh-border-hover, var(--zhimesh-border));
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: 0 3px 10px rgba(15, 23, 42, 0.09);
  outline: none;
}

.section-sider-collapse__icon {
  position: relative;
  display: block;
  width: 16px;
  height: 16px;
  border: 2px solid currentColor;
  border-radius: 4px;
}

.section-sider-collapse__icon::before {
  position: absolute;
  top: 2px;
  bottom: 2px;
  left: 4px;
  width: 2px;
  border-radius: 999px;
  background: currentColor;
  content: '';
}

.section-sider-reveal {
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
  transition: color 180ms ease, border-color 180ms ease, background 180ms ease, box-shadow 180ms ease;
}

.section-sider-reveal:hover,
.section-sider-reveal:focus-visible {
  color: var(--zhimesh-primary);
  border-color: var(--zhimesh-border);
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: var(--zhimesh-shadow-soft), inset 0 1px 0 var(--zhimesh-glass-highlight);
  outline: none;
}

.section-sider-reveal__chevron {
  width: 9px;
  height: 9px;
  border-right: 2.5px solid currentColor;
  border-bottom: 2.5px solid currentColor;
  border-radius: 1px;
  transform: translateX(-2px) rotate(-45deg);
}

.knowledge-sider-footer {
  position: relative;
  z-index: 2;
  flex: none;
  border-top: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-soft-alpha);
}

.knowledge-manage-action {
  padding: 12px 16px;
}

@supports (backdrop-filter: blur(1px)) {
  .knowledge-sider-footer {
    backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(115%);
    -webkit-backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(115%);
  }
}

@supports not (backdrop-filter: blur(1px)) {
  .knowledge-sider-footer {
    background: var(--zhimesh-glass-strong);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .knowledge-sider-footer {
    background: var(--zhimesh-glass-strong);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}
</style>
