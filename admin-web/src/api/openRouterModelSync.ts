import { http } from '@/utils/http/axios'
import type { Result } from '@/utils/http/axios/types'

export type OpenRouterSyncStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'PARTIAL_RATE_LIMIT'
  | 'FAILED'
  | 'SKIPPED_LOCKED'

export interface OpenRouterSyncRun {
  id: number
  uuid: string
  triggerType: 'SCHEDULED' | 'MANUAL' | 'STARTUP_CATCH_UP'
  status: OpenRouterSyncStatus
  catalogCount: number
  freeCount: number
  eligibleCount: number
  probedCount: number
  addedCount: number
  updatedCount: number
  enabledCount: number
  disabledCount: number
  skippedCount: number
  startedAt: string
  completedAt?: string | null
  errorCode?: string
  errorMessage?: string
  summary?: {
    platform?: string
    rateLimited?: boolean
    targetActiveCount?: number
    availableAfterSync?: number
    changes?: unknown[]
  } | null
}

export interface OpenRouterModelState {
  id: number
  platform: string
  modelName: string
  modelId?: number | null
  isManaged: boolean
  lifecycleStatus: 'ENABLED' | 'DISABLED' | 'PROTECTED' | 'DISCOVERED' | string
  catalogStatus: string
  probeStatus: string
  lastDecision: string
  disableReason?: string
  catalogLatencyP50Ms?: number | null
  catalogThroughputP50?: number | null
  catalogUptime1d?: number | null
  actualTtftMs?: number | null
  actualTotalLatencyMs?: number | null
  lastErrorCode?: string
  lastErrorMessage?: string
  lastSeenAt?: string | null
  lastProbeAt?: string | null
  lastSuccessAt?: string | null
  lastDisabledAt?: string | null
}

function run() {
  return http.request<Result<OpenRouterSyncRun>>({
    url: '/admin/openrouter-model-sync/run',
    method: 'post',
  })
}

function getRun(runId: string) {
  return http.request<Result<OpenRouterSyncRun>>({
    url: `/admin/openrouter-model-sync/runs/${encodeURIComponent(runId)}`,
    method: 'get',
  })
}

function latest() {
  return http.request<Result<OpenRouterSyncRun | null>>({
    url: '/admin/openrouter-model-sync/latest',
    method: 'get',
  })
}

function models() {
  return http.request<Result<OpenRouterModelState[]>>({
    url: '/admin/openrouter-model-sync/models',
    method: 'get',
  })
}

export default { run, getRun, latest, models }
