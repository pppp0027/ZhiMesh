export interface Character {
  id: string
  uuid: string
  title: string
  aiSystemMessage: string
  tokens: number
  understandContextEnable: boolean
  createTime: string
  updateTime: string
}

export interface CharacterPreset {
  id: string
  uuid: string
  title: string
  remark: string
  aiSystemMessage: string
  kbTitle: string
  systemKbIds: string
  mcpIds: string
  type: string
  isSystem: boolean
  createTime: string
  updateTime: string
}
