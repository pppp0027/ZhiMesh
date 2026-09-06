import { defineStore } from 'pinia'
import type { UserState } from './helper'
import { defaultSetting, getLocalState, setLocalState } from './helper'
import { resolveUserAvatarUrl } from '@/utils/avatar'

export const useUserStore = defineStore('user-store', {
  state: (): UserState => getLocalState(),
  actions: {
    replaceUserInfo(userInfo: User.Profile) {
      const uuid = userInfo.uuid || ''
      const name = userInfo.name || ''
      this.userInfo = {
        ...defaultSetting().userInfo,
        ...userInfo,
        avatar: resolveUserAvatarUrl(userInfo.avatar, uuid, uuid || name),
      }
      this.recordState()
    },

    updateUserInfo(userInfo: Partial<User.Profile>) {
      this.userInfo = { ...this.userInfo, ...userInfo }
      this.userInfo.avatar = resolveUserAvatarUrl(
        this.userInfo.avatar,
        this.userInfo.uuid,
        this.userInfo.uuid || this.userInfo.name,
      )
      this.recordState()
    },

    resetUserInfo() {
      this.userInfo = { ...defaultSetting().userInfo }
      this.recordState()
    },

    recordState() {
      setLocalState(this.$state)
    },
  },
})
