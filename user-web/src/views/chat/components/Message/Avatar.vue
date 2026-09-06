<script lang="ts" setup>
import { computed } from 'vue'
import { NAvatar } from 'naive-ui'
import PlatformAvatar from '@/components/common/PlatformAvatar.vue'
import { useAppStore, useUserStore } from '@/store'
import { getAvatarPoolUrl, resolveUserAvatarUrl } from '@/utils/avatar'

interface Props {
  name?: string
  modelId?: string | number
  imageSize?: number
}
const props = defineProps<Props>()

const userStore = useUserStore()
const appStore = useAppStore()

const avatar = computed(() => userStore.userInfo.avatar)
const resolvedAvatar = computed(() => resolveUserAvatarUrl(
  avatar.value,
  userStore.userInfo.uuid,
  userStore.userInfo.uuid || userStore.userInfo.name,
))
const fallbackAvatar = computed(() => getAvatarPoolUrl(userStore.userInfo.uuid || userStore.userInfo.name))
const platformModel = computed(() => {
  const modelId = props.modelId == null ? '' : String(props.modelId)
  const platformName = props.name?.trim().toLowerCase() || ''

  return appStore.llms.find(item => modelId && String(item.modelId) === modelId)
    || appStore.llms.find(item => item.modelPlatform?.trim().toLowerCase() === platformName)
    || (appStore.selectedLLM.modelPlatform?.trim().toLowerCase() === platformName
      ? appStore.selectedLLM
      : undefined)
})
</script>

<template>
  <!-- User's avatar -->
  <template v-if="name === 'user'">
    <NAvatar
      :size="imageSize ? imageSize : 32"
      :src="resolvedAvatar"
      :fallback-src="fallbackAvatar"
      :style="{ backgroundColor: 'transparent' }"
      round
      object-fit="cover"
      :aria-label="`${userStore.userInfo.name || '用户'}的头像`"
    />
  </template>
  <!-- Model platform's avatar -->
  <PlatformAvatar
    v-else
    :name="platformModel?.modelPlatform || name"
    :label="platformModel?.platformTitle || name"
    :host="platformModel?.platformHost"
    :icon-url="platformModel?.platformIconUrl"
    :size="imageSize || 32"
  />
</template>
