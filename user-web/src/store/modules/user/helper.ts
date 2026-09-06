import { ss } from '@/utils/storage'
import { getAvatarPoolUrl, resolveUserAvatarUrl } from '@/utils/avatar'

const LOCAL_NAME = 'userStorage'

export interface UserState {
  userInfo: User.Profile
}

export function defaultSetting(): UserState {
  return {
    userInfo: {
      avatar: getAvatarPoolUrl('guest'),
      name: 'zhimesh',
      description: '',
      uuid: '',
    },
  }
}

export function getLocalState(): UserState {
  const localSetting: UserState | undefined = ss.get(LOCAL_NAME)
  const state = { ...defaultSetting(), ...localSetting }
  state.userInfo = { ...defaultSetting().userInfo, ...state.userInfo }
  state.userInfo.avatar = resolveUserAvatarUrl(
    state.userInfo.avatar,
    state.userInfo.uuid,
    state.userInfo.uuid || state.userInfo.name,
  )
  return state
}

export function setLocalState(setting: UserState): void {
  ss.set(LOCAL_NAME, setting)
}
