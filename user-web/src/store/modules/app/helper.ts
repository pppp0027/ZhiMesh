import { ss } from '@/utils/storage'
import { emptyAiModel, emptySysConfigInfo } from '@/utils/functions'

const LOCAL_NAME = 'appSetting'

export type Theme = 'light' | 'dark' | 'auto'

export type Language = 'zh-CN' | 'en-US'

export type SiderPage = 'chat' | 'knowledgeBase' | 'workflow'

export interface AppState {
  siderCollapsed: boolean
  pageSiderCollapsed: Record<SiderPage, boolean>
  theme: Theme
  language: Language | ''

  selectedSearchEngine: string
  selectedLLM: AiModelInfo
  selectedImageModel: AiModelInfo
  searchEngines: SearchEngineInfo[]
  llms: AiModelInfo[]
  imageModels: AiModelInfo[]
  sysConfigInfo: SysConfigInfo
}

export function defaultSetting(): AppState {
  return {
    siderCollapsed: false,
    pageSiderCollapsed: {
      chat: false,
      knowledgeBase: false,
      workflow: false,
    },
    theme: 'light',
    language: '',
    selectedSearchEngine: '',
    selectedLLM: emptyAiModel(),
    selectedImageModel: emptyAiModel(),
    searchEngines: [],
    llms: [],
    imageModels: [],
    sysConfigInfo: emptySysConfigInfo(),
  }
}

export function getLocalSetting(): AppState {
  const localSetting: AppState | undefined = ss.get(LOCAL_NAME)
  const defaults = defaultSetting()
  return {
    ...defaults,
    ...localSetting,
    // API-owned collections must never be restored from a previous browser session.
    // Keeping them here made deleted or disabled models reappear when a refresh failed.
    searchEngines: [],
    llms: [],
    imageModels: [],
    pageSiderCollapsed: {
      ...defaults.pageSiderCollapsed,
      ...localSetting?.pageSiderCollapsed,
    },
  }
}

export function setLocalSetting(setting: AppState): void {
  ss.set(LOCAL_NAME, {
    ...setting,
    searchEngines: [],
    llms: [],
    imageModels: [],
  })
}
