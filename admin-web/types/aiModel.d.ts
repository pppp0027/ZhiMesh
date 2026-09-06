export interface AiModelData {
  id: string
  type: string
  name: string
  title: string
  platform: string
  remark: string
  createTime: string
  updateTime: string
  isEnable: boolean
  isFree: boolean
  setting: string
  inputTypes: string
  responseFormatTypes: string
  isReasoner: boolean
  isThinkingClosable: boolean
  isSupportWebSearch: boolean
  maxInputTokens: number
  properties?: Record<string, any> | string | null
  openRouterState?: import('../src/api/openRouterModelSync').OpenRouterModelState

  //For ui
  inputTypeList: string[]
  responseFormatTypeList: string[]
}
