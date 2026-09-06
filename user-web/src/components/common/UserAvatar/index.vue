<script setup lang='ts'>
import { computed } from 'vue'
import { NAvatar } from 'naive-ui'
import { useUserStore } from '@/store'
import { isString } from '@/utils/is'
import { getAvatarPoolUrl, resolveUserAvatarUrl } from '@/utils/avatar'

const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)
const avatar = computed(() => resolveUserAvatarUrl(
  userInfo.value.avatar,
  userInfo.value.uuid,
  userInfo.value.uuid || userInfo.value.name,
))
const fallbackAvatar = computed(() => getAvatarPoolUrl(userInfo.value.uuid || userInfo.value.name))
</script>

<template>
  <div class="flex items-center overflow-hidden">
    <div class="w-10 h-10 overflow-hidden rounded-full shrink-0">
      <NAvatar
        size="large"
        round
        object-fit="cover"
        :src="avatar"
        :fallback-src="fallbackAvatar"
        :aria-label="`${userInfo.name || '用户'}的头像`"
      />
    </div>
    <div class="flex-1 min-w-0 ml-2">
      <h2 class="overflow-hidden font-bold text-md text-ellipsis whitespace-nowrap">
        {{ userInfo.name ?? '知脉' }}
      </h2>
      <p class="overflow-hidden text-xs text-gray-500 text-ellipsis whitespace-nowrap">
        <span v-if="isString(userInfo.description) && userInfo.description !== ''" v-html="userInfo.description" />
      </p>
    </div>
  </div>
</template>
