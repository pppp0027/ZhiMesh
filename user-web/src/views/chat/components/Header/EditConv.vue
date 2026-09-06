<script lang="ts" setup>
import { ref, watch } from 'vue'
import { NModal } from 'naive-ui'
import EditConvDetail from './EditConvDetail.vue'
import { useAuthStore, useMcpStore } from '@/store'
import { emptyCharacter } from '@/utils/functions'
import api from '@/api'
import { SvgIcon } from '@/components/common'
import { t } from '@/locales'

interface Props {
  showModal: boolean
  character: Chat.Character
}
interface Emit {
  (ev: 'showModal', show: boolean): void
}
const props = withDefaults(defineProps<Props>(), {
  showModal: false,
})
const emit = defineEmits<Emit>()
const mcpStore = useMcpStore()
const authStore = useAuthStore()
const innerShow = ref<boolean>(props.showModal)
const tmpCharacter = ref<Chat.Character>(emptyCharacter())

function initEditCharacter(item: Chat.Character) {
  Object.assign(tmpCharacter.value, item)
}

function handleSubmitted() {
  emit('showModal', false)
}

watch(() => props.showModal, (val) => {
  innerShow.value = val
})

watch(() => props.character, (val) => {
  if (val)
    initEditCharacter(val)
})

watch(() => innerShow.value, (val) => {
  if (!val)
    emit('showModal', false)
})

// Load user MCP list when user is logged in
async function loadMyUserMcpList() {
  if (mcpStore.userMcpLoading || !authStore.token || mcpStore.myUserMcpList.length > 0)
    return
  try {
    mcpStore.setUserMcpLoading(true)
    const { data } = await api.userMcpList<Mcp.UserMcpListResp>(1, 200)
    if (data.records.length > 0)
      mcpStore.appendMyUserMcpList(data.records)
  } catch (error) {
    console.error(error)
  } finally {
    mcpStore.setUserMcpLoading(false)
  }
}
watch(
  () => authStore.token,
  () => {
    if (authStore.token)
      loadMyUserMcpList()
  },
  { immediate: true },
)
</script>

<template>
  <NModal v-model:show="innerShow" class="character-settings-modal" preset="card">
    <template #header>
      <div class="character-settings-heading">
        <span class="character-settings-heading-icon"><SvgIcon icon="ri:robot-2-line" /></span>
        <div>
          <strong>{{ t('chat.editCharacterModalTitle') }}<template v-if="tmpCharacter.title"> · {{ tmpCharacter.title }}</template></strong>
          <span>{{ t('chat.editCharacterModalHint') }}</span>
        </div>
      </div>
    </template>
    <EditConvDetail :character="tmpCharacter" compact @submitted="handleSubmitted" />
  </NModal>
</template>

<style lang="less">
.character-settings-heading {
  display: flex;
  align-items: center;
  gap: 11px;
}

.character-settings-heading-icon {
  display: grid;
  flex: none;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 12px;
  color: #fff;
  background: var(--zhimesh-control-primary);
  font-size: 19px;
}

.character-settings-heading > div {
  display: flex;
  flex-direction: column;
}

.character-settings-heading strong {
  font-size: 17px;
}

.character-settings-heading span:last-child {
  margin-top: 2px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.character-settings-modal {
  width: min(600px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
}

.character-settings-modal .n-card__content {
  overflow: hidden;
}

@media (max-width: 767px) {
  .character-settings-modal {
    width: calc(100vw - 20px);
  }
}
</style>
