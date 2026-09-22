<script setup lang="ts">
import { NButton, NConfigProvider, NIcon, NLayout, NLayoutSider, NMenu, NSpace, NTooltip } from 'naive-ui'
import type { MenuOption } from 'naive-ui'
import type { Component } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { computed, defineAsyncComponent, h, nextTick, onBeforeUnmount, onErrorCaptured, onMounted, provide, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { AppsOutline, ChatboxEllipsesOutline, LibraryOutline, PersonCircleOutline, SettingsOutline } from '@vicons/ionicons5'
import { ToolKit } from '@vicons/carbon'
import { Prompt as PromptIcon } from '@vicons/tabler'
import { NaiveProvider, PromptStore } from '@/components/common'
import { useTheme } from '@/hooks/useTheme'
import { useLanguage } from '@/hooks/useLanguage'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { t } from '@/locales'
import { useAppStore, useAuthStore, useChatStore, useKbStore, useWfStore } from '@/store'
import { detectBrowserLocale } from '@/store/modules/app'
import Login from '@/views/user/Login.vue'
import api from '@/api'
import brandLogo from '@/assets/zhimesh-logo.svg'
import { routeLoadError } from '@/router'

const Setting = defineAsyncComponent(() => import('@/components/common/Setting/index.vue'))
type SettingsSection = 'General' | 'Quota' | 'ModifyPassword'
type SettingsMode = 'all' | 'profile' | 'account'

const appStore = useAppStore()
const chatStore = useChatStore()
const kbStore = useKbStore()
const wfStore = useWfStore()
const authStore = useAuthStore()
const { theme, themeOverrides } = useTheme()
const { language } = useLanguage()
const { isMobile } = useBasicLayout()
const route = useRoute()
const routeName = route.name as string
const isChatRoute = computed(() => route.name === 'Chat' || route.name === 'ChatDetail' || route.name === 'QADetail')
const activeKey = ref<string>('menu-chat')
const showPrompt = ref<boolean>(false)
const showSetting = ref<boolean>(false)
const settingInitialTab = ref<SettingsSection>('General')
const settingMode = ref<SettingsMode>('all')
const capturedWorkspaceError = shallowRef<Error | null>(null)
const workspaceFailure = computed(() => capturedWorkspaceError.value || routeLoadError.value)
const primaryMenuRef = ref<HTMLElement | null>(null)
const indicatorReady = ref(false)
const indicatorStyle = ref<Record<string, string>>({ opacity: '0' })
let indicatorFrame = 0
let menuResizeObserver: ResizeObserver | undefined
let modelRefreshTimer: number | undefined
let modelRefreshInFlight = false
let cancelWorkspacePrefetch: (() => void) | undefined
let workspacePrefetchCancelled = false

const workspacePrefetchers: Array<() => Promise<unknown>> = [
  () => import('@/views/knowledge-base/index.vue'),
  () => import('@/views/workflow/index.vue'),
  () => import('@/views/mcp/index.vue'),
]

function shouldPrefetchWorkspaces() {
  const connection = (navigator as Navigator & {
    connection?: { saveData?: boolean; effectiveType?: string }
  }).connection
  return !connection?.saveData && !['slow-2g', '2g'].includes(connection?.effectiveType || '')
}

function scheduleNextWorkspacePrefetch() {
  if (cancelWorkspacePrefetch || workspacePrefetchCancelled || !workspacePrefetchers.length || !shouldPrefetchWorkspaces())
    return

  const idleWindow = window as Window & typeof globalThis & {
    requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number
    cancelIdleCallback?: (handle: number) => void
  }
  const run = async () => {
    cancelWorkspacePrefetch = undefined
    if (workspacePrefetchCancelled || document.visibilityState !== 'visible')
      return
    const prefetch = workspacePrefetchers.shift()
    if (!prefetch)
      return
    try {
      await prefetch()
    } catch (error) {
      console.warn('Failed to prefetch a workspace route', error)
    }
    scheduleNextWorkspacePrefetch()
  }

  if (idleWindow.requestIdleCallback) {
    const handle = idleWindow.requestIdleCallback(run, { timeout: 2000 })
    cancelWorkspacePrefetch = () => idleWindow.cancelIdleCallback?.(handle)
  } else {
    const handle = window.setTimeout(run, 600)
    cancelWorkspacePrefetch = () => window.clearTimeout(handle)
  }
}

/**
 * The daily OpenRouter synchronizer refreshes the server-side model context,
 * but an already-open browser tab still has the previous model list in Pinia.
 * Keep that list convergent without requiring a full page reload. The request
 * is lightweight (it only reads enabled model metadata), and visibility/focus
 * checks avoid background-tab traffic.
 */
async function refreshAvailableModels() {
  if (modelRefreshInFlight || document.visibilityState !== 'visible')
    return

  modelRefreshInFlight = true
  try {
    const response = await api.loadLLMs<AiModelInfo[]>()
    appStore.setLLMs(response.data)
  } catch (error) {
    // Keep the last known-good selection when a refresh races with a backend
    // restart or a short-lived network failure. The selector can retry on
    // focus/open, and the next interval will retry automatically.
    console.warn('Failed to refresh the available model list automatically', error)
  } finally {
    modelRefreshInFlight = false
  }
}

function refreshModelsWhenVisible() {
  if (document.visibilityState === 'visible') {
    refreshAvailableModels()
    scheduleNextWorkspacePrefetch()
  }
}

const menuKeyToRouteNames = new Map<string, string[]>(
  [
    ['chat', ['Chat', 'ChatDetail']],
    ['knowledge-base', ['QAIndex', 'QADetail', 'KnowledgeBaseManage', 'KnowledgeBaseManageDetail', 'TeamManage']],
    ['workflow', ['WfDetail']],
    ['mcp', ['Mcp']],
  ])

menuKeyToRouteNames.forEach((val, key) => {
  if (val.includes(routeName))
    activeKey.value = `menu-${key.toLowerCase()}`
})

const menuOptions: MenuOption[] = [
  {
    key: 'menu-chat',
    icon: renderIcon(ChatboxEllipsesOutline),
    label: () =>
      h(
        RouterLink,
        {
          to: {
            name: 'ChatDetail',
            params: {
              uuid: chatStore.active,
            },
            query: chatStore.activeConversationUuid
              ? { conversation: chatStore.activeConversationUuid }
              : {},
          },
        },
        { default: () => t('menu.chat') },
      ),
  },
  {
    key: 'menu-knowledge-base',
    icon: renderIcon(LibraryOutline),
    label: () =>
      h(
        RouterLink,
        {
          to: {
            name: 'QADetail',
            params: {
              kbUuid: kbStore.activeKbUuid,
            },
          },
        },
        { default: () => t('menu.knowledgeBase') },
      ),
  },
  {
    key: 'menu-workflow',
    icon: renderIcon(AppsOutline),
    label: () =>
      h(
        RouterLink,
        {
          to: {
            name: 'WfDetail',
            params: {
              uuid: wfStore.activeUuid,
            },
          },
        },
        { default: () => t('menu.workflow') },
      ),
  },
  {
    key: 'menu-mcp',
    icon: renderIcon(ToolKit),
    label: () =>
      h(
        RouterLink,
        {
          to: {
            name: 'Mcp',
          },
        },
        { default: () => t('menu.mcp') },
      ),
  },
]
function renderIcon(icon: Component) {
  return () => h(NIcon, null, { default: () => h(icon) })
}

function getWorkspaceRouteKey(viewRoute: RouteLocationNormalizedLoaded) {
  const workspaceRoute = viewRoute.matched[0]
  return String(workspaceRoute?.name || workspaceRoute?.path || viewRoute.name || viewRoute.path)
}

function normalizeWorkspaceError(error: unknown) {
  return error instanceof Error ? error : new Error(String(error))
}

function recoverWorkspace() {
  capturedWorkspaceError.value = null
  routeLoadError.value = null
  window.location.reload()
}

onErrorCaptured((error, _instance, info) => {
  const normalizedError = normalizeWorkspaceError(error)
  console.error(`Workspace rendering failed (${info})`, normalizedError)
  capturedWorkspaceError.value = normalizedError
  return false
})

function updateMenuIndicator(initial = false) {
  nextTick(() => {
    cancelAnimationFrame(indicatorFrame)
    indicatorFrame = requestAnimationFrame(() => {
      const wrap = primaryMenuRef.value
      const selected = wrap?.querySelector<HTMLElement>('.n-menu-item-content--selected')
      if (!wrap || !selected) {
        indicatorStyle.value = { opacity: '0' }
        return
      }

      const wrapRect = wrap.getBoundingClientRect()
      const selectedRect = selected.getBoundingClientRect()
      indicatorStyle.value = {
        opacity: '1',
        width: `${selectedRect.width}px`,
        height: `${selectedRect.height}px`,
        transform: `translate3d(${selectedRect.left - wrapRect.left}px, ${selectedRect.top - wrapRect.top}px, 0)`,
      }

      if (initial)
        requestAnimationFrame(() => (indicatorReady.value = true))
      else
        indicatorReady.value = true
    })
  })
}

watch(
  () => route.name, // 监听 path 变化
  (newName, _oldName) => {
    // 代码中使用router.push()方法跳转到不同菜单的路径时，route.name会发生变化，activeKey不会变化，如果做以下处理，activeKey会是上一个路由的值，导致菜单高亮错误
    // 这里可以根据 newName 来判断当前路由，并设置 activeKey 的值
    menuKeyToRouteNames.forEach((val, key) => {
      if (val.includes(newName as string) && activeKey.value !== `menu-${key.toLowerCase()}`)
        activeKey.value = `menu-${key.toLowerCase()}`
    })
    updateMenuIndicator()
  },
)

watch(
  () => route.fullPath,
  () => {
    capturedWorkspaceError.value = null
  },
)

provide('openPromptStore', () => {
  showPrompt.value = true
})

function openAppSettings(section: SettingsSection = 'General', mode: SettingsMode = 'all') {
  settingInitialTab.value = section
  settingMode.value = mode
  showSetting.value = true
}

provide('openAppSettings', openAppSettings)

watch(activeKey, () => updateMenuIndicator())

watch(
  () => authStore.token,
  (newToken, oldToken) => {
    if (!newToken && oldToken)
      showSetting.value = false
    if (newToken)
      refreshAvailableModels()
  },
)

onMounted(async () => {
  const [llms, imageModels, engines, sysConfig] = await Promise.allSettled([
    api.loadLLMs<AiModelInfo[]>(),
    api.loadImageModels<AiModelInfo[]>(),
    api.loadSearchEngines<SearchEngineInfo[]>(),
    api.getSysConfig<SysConfigInfo>(),
  ])

  if (llms.status === 'fulfilled')
    appStore.setLLMs(llms.value.data)
  if (imageModels.status === 'fulfilled')
    appStore.setImageModels(imageModels.value.data)
  if (engines.status === 'fulfilled')
    appStore.setSearchEngines(engines.value.data)
  if (sysConfig.status === 'fulfilled')
    appStore.setSysConfig(sysConfig.value.data)

  const defaultLocale = sysConfig.status === 'fulfilled'
    ? sysConfig.value.data.defaultLocale
    : appStore.sysConfigInfo.defaultLocale
  const locale = authStore.token
    ? defaultLocale
    : (detectBrowserLocale() || defaultLocale)
  appStore.initLocale(locale)

  const failedRequests = [llms, imageModels, engines, sysConfig]
    .filter(result => result.status === 'rejected')
  if (failedRequests.length)
    console.error('Some application configuration requests failed', failedRequests)

  modelRefreshTimer = window.setInterval(() => {
    refreshAvailableModels()
  }, 60 * 1000)
  document.addEventListener('visibilitychange', refreshModelsWhenVisible)
})

onMounted(() => {
  updateMenuIndicator(true)
  scheduleNextWorkspacePrefetch()
  if (primaryMenuRef.value && 'ResizeObserver' in window) {
    menuResizeObserver = new ResizeObserver(() => updateMenuIndicator())
    menuResizeObserver.observe(primaryMenuRef.value)
  }
})

onBeforeUnmount(() => {
  workspacePrefetchCancelled = true
  cancelWorkspacePrefetch?.()
  cancelAnimationFrame(indicatorFrame)
  menuResizeObserver?.disconnect()
  if (modelRefreshTimer !== undefined)
    window.clearInterval(modelRefreshTimer)
  document.removeEventListener('visibilitychange', refreshModelsWhenVisible)
})
</script>

<template>
  <NConfigProvider class="h-full" :theme="theme" :theme-overrides="themeOverrides" :locale="language">
    <NaiveProvider>
      <NLayout class="h-full user-app-shell" :class="{ 'chat-mode': isMobile && isChatRoute }" has-sider>
        <NLayoutSider v-if="!isMobile || !isChatRoute" class="primary-navigation" aria-label="能力导航" :width="72" :collapsed-width="72" collapse-mode="width" :collapsed="!isMobile">
          <div class="brand-mark" title="知脉（ZhiMesh）">
            <img :src="brandLogo" alt="知脉（ZhiMesh）logo">
          </div>
          <div ref="primaryMenuRef" class="primary-menu-motion">
            <div
              v-if="!isMobile"
              class="primary-menu-indicator"
              :class="{ 'is-ready': indicatorReady }"
              :style="indicatorStyle"
              aria-hidden="true"
            />
            <NMenu v-model:value="activeKey" class="primary-menu" :options="menuOptions" />
          </div>
          <NSpace v-if="!isMobile" vertical class="absolute nav-actions">
            <NTooltip trigger="hover" placement="right" style="margin-left: 1.5rem;">
              <template #trigger>
                <NButton text aria-label="打开提示词库" style="font-size: 26px;" class="cursor-pointer" @click="showPrompt = true">
                  <NIcon>
                    <PromptIcon />
                  </NIcon>
                </NButton>
              </template>
              {{ t('store.siderButton') }}
            </NTooltip>
            <NTooltip v-if="authStore.token" trigger="hover" placement="right" style="margin-left: 1.5rem;">
              <template #trigger>
                <NButton text aria-label="打开设置" style="font-size: 26px;" class="cursor-pointer" @click="openAppSettings()">
                  <NIcon>
                    <SettingsOutline />
                  </NIcon>
                </NButton>
              </template>
              {{ t('setting.setting') }}
            </NTooltip>
            <NTooltip v-if="!authStore.token" trigger="hover" placement="right" style="margin-left: 1.5rem;">
              <template #trigger>
                <NButton text aria-label="打开登录" style="font-size: 26px;" class="cursor-pointer" @click="authStore.setLoginView(true)">
                  <NIcon>
                    <PersonCircleOutline />
                  </NIcon>
                </NButton>
              </template>
              {{ t('common.login') }}
            </NTooltip>
          </NSpace>
        </NLayoutSider>
        <NLayout class="workspace-shell">
          <RouterView v-slot="{ Component: RouteComponent, route: viewRoute }">
            <Transition name="workspace-swap" appear>
              <div
                v-if="workspaceFailure"
                :key="`workspace-error:${viewRoute.fullPath}`"
                class="workspace-error-state"
                role="alert"
              >
                <div class="workspace-error-state__panel">
                  <div class="workspace-error-state__mark" aria-hidden="true">
                    !
                  </div>
                  <h2>{{ t('common.workspaceLoadErrorTitle') }}</h2>
                  <p>{{ t('common.workspaceLoadErrorDescription') }}</p>
                  <NButton type="primary" @click="recoverWorkspace">
                    {{ t('common.retry') }}
                  </NButton>
                </div>
              </div>
              <KeepAlive v-else :max="4">
                <component :is="RouteComponent" :key="getWorkspaceRouteKey(viewRoute)" />
              </KeepAlive>
            </Transition>
          </RouterView>
        </NLayout>
      </NLayout>

      <PromptStore v-model:visible="showPrompt" />
      <Setting v-model:visible="showSetting" :initial-tab="settingInitialTab" :mode="settingMode" />
      <Login />
    </NaiveProvider>
  </NConfigProvider>
</template>
