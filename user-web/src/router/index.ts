import type { App } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import { shallowRef } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import { setupPageGuard } from './permission'
import { ChatLayout } from '@/views/chat/layout'
import { KnowledgeBaseLayout } from '@/views/knowledge-base/layout'
import { WorkflowBaseLayout } from '@/views/workflow/layout'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Root',
    redirect: '/chat/default',
  },
  {
    path: '/chat',
    name: 'Chat',
    component: ChatLayout,
    children: [
      {
        path: ':uuid',
        name: 'ChatDetail',
        component: () => import('@/views/chat/index.vue'),
      },
    ],
  },
  {
    path: '/active',
    name: 'Active',
    component: () => import('@/views/user/Active.vue'),
  },
  {
    path: '/draw',
    name: 'Draw',
    component: () => import('@/views/draw/index.vue'),
  },
  {
    path: '/gallery',
    name: 'Gallery',
    component: () => import('@/views/gallery/index.vue'),
  },
  {
    path: '/qa',
    component: KnowledgeBaseLayout,
    name: 'QAIndex',
    children: [
      {
        path: '',
        redirect: { name: 'QADetail', params: { kbUuid: 'default' } },
      },
      {
        path: ':kbUuid',
        name: 'QADetail',
        component: () => import('@/views/knowledge-base/index.vue'),
      },
    ],
  },
  {
    path: '/kb-manage',
    name: 'KnowledgeBaseManage',
    component: () => import('@/views/knowledge-base-manage/index.vue'),
  },
  {
    path: '/kb-manage/:kbUuid',
    name: 'KnowledgeBaseManageDetail',
    component: () => import('@/views/knowledge-base-manage/KnowledgeBaseDetail.vue'),
  },
  {
    path: '/team-manage',
    name: 'TeamManage',
    component: () => import('@/views/team-manage/index.vue'),
  },
  {
    path: '/workflow',
    component: WorkflowBaseLayout,
    name: 'WfIndex',
    children: [
      {
        path: '',
        redirect: { name: 'WfDetail', params: { uuid: 'default' } },
      },
      {
        path: ':uuid',
        name: 'WfDetail',
        component: () => import('@/views/workflow/index.vue'),
      },
    ],
  },
  {
    path: '/mcp',
    name: 'Mcp',
    component: () => import('@/views/mcp/index.vue'),
  },
  {
    path: '/404',
    name: '404',
    component: () => import('@/views/exception/404/index.vue'),
  },

  {
    path: '/500',
    name: '500',
    component: () => import('@/views/exception/500/index.vue'),
  },

  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    redirect: '/404',
  },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ left: 0, top: 0 }),
})

const ROUTE_CHUNK_RECOVERY_KEY = 'zhimesh:route-chunk-recovery'

export const routeLoadError = shallowRef<Error | null>(null)

function normalizeRouteError(error: unknown) {
  return error instanceof Error ? error : new Error(String(error))
}

function isChunkLoadError(error: Error) {
  return /Failed to fetch dynamically imported module|Importing a module script failed|ChunkLoadError|Loading chunk .+ failed|Unable to preload CSS/i.test(error.message)
}

function getChunkRecoveryAttempt() {
  try {
    return window.sessionStorage.getItem(ROUTE_CHUNK_RECOVERY_KEY)
  } catch {
    return null
  }
}

function setChunkRecoveryAttempt(routePath: string) {
  try {
    window.sessionStorage.setItem(ROUTE_CHUNK_RECOVERY_KEY, routePath)
  } catch {
    // Storage can be unavailable in private browsing. The visible error state
    // still gives the user a manual recovery path in that case.
  }
}

function clearChunkRecoveryAttempt(routePath: string) {
  try {
    if (window.sessionStorage.getItem(ROUTE_CHUNK_RECOVERY_KEY) === routePath)
      window.sessionStorage.removeItem(ROUTE_CHUNK_RECOVERY_KEY)
  } catch {
    // Ignore storage access failures; successful navigation already recovered.
  }
}

router.onError((error, to) => {
  const normalizedError = normalizeRouteError(error)
  routeLoadError.value = normalizedError
  console.error('Route loading failed', normalizedError)

  if (!isChunkLoadError(normalizedError))
    return

  const recoveryPath = to.fullPath || window.location.hash
  if (getChunkRecoveryAttempt() === recoveryPath)
    return

  setChunkRecoveryAttempt(recoveryPath)
  window.location.reload()
})

router.afterEach((to, _from, failure) => {
  if (failure)
    return

  routeLoadError.value = null
  clearChunkRecoveryAttempt(to.fullPath)
})

setupPageGuard(router)

export async function setupRouter(app: App) {
  app.use(router)
  try {
    await router.isReady()
  } catch (error) {
    const normalizedError = normalizeRouteError(error)
    routeLoadError.value = normalizedError
    console.error('Initial route loading failed', normalizedError)
  }
}
