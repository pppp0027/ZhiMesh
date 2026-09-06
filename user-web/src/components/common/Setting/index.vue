<script setup lang='ts'>
import { computed, onMounted, ref, watch } from 'vue'
import { NModal, NTabPane, NTabs } from 'naive-ui'
import General from './General.vue'
import Quota from './Quota.vue'
import ModifyPassword from './ModifyPassword.vue'
import api from '@/api'
import { SvgIcon } from '@/components/common'
import { useAuthStore } from '@/store'
import { emptyQuota } from '@/utils/functions'
import { t } from '@/locales'

interface Props {
  visible: boolean
  initialTab?: SettingsSection
  mode?: SettingsMode
}

type SettingsSection = 'General' | 'Quota' | 'ModifyPassword'
type SettingsMode = 'all' | 'profile' | 'account'

interface Emit {
  (e: 'update:visible', visible: boolean): void
}

const props = withDefaults(defineProps<Props>(), {
  initialTab: 'General',
  mode: 'all',
})
const emit = defineEmits<Emit>()
const active = ref<SettingsSection>(props.initialTab)
const loading = ref(false)
const userConfig = ref<User.Config>(emptyQuota())

const show = computed({
  get() {
    return props.visible
  },
  set(visible: boolean) {
    emit('update:visible', visible)
  },
})

const modalStyle = computed(() => {
  if (props.mode === 'profile') {
    return {
      width: 'min(460px, calc(100vw - 24px))',
      height: 'min(450px, calc(100dvh - 24px))',
    }
  }

  return {
    width: 'min(500px, calc(100vw - 24px))',
    height: 'min(500px, calc(100dvh - 24px))',
  }
})

const modalTitle = computed(() => {
  if (props.mode === 'profile')
    return t('setting.profileTitle')
  if (props.mode === 'account')
    return t('setting.accountSettingsTitle')
  return t('setting.setting')
})

const modalDescription = computed(() => {
  if (props.mode === 'profile')
    return t('setting.profileDescription')
  if (props.mode === 'account')
    return t('setting.accountSettingsDescription')
  return ''
})

function resolveActiveSection(section: SettingsSection, mode: SettingsMode): SettingsSection {
  if (mode === 'profile')
    return 'General'
  if (mode === 'account')
    return section === 'ModifyPassword' ? 'ModifyPassword' : 'Quota'
  return section
}

watch(
  active,
  (val) => {
    if (val === 'Quota')
      fetchConfig()
  },
)

watch(
  () => [props.visible, props.initialTab, props.mode] as const,
  ([visible, initialTab, mode]) => {
    if (visible)
      active.value = resolveActiveSection(initialTab, mode)
  },
)

async function fetchConfig() {
  if (!useAuthStore().token)
    return
  try {
    loading.value = true
    const { data } = await api.fetchUserConfig<User.Config>()
    userConfig.value = data
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (active.value === 'Quota')
    fetchConfig()
})
</script>

<template>
  <NModal
    v-model:show="show"
    :auto-focus="false"
    preset="card"
    class="settings-modal"
    :class="`settings-modal--${mode}`"
    :bordered="false"
    :style="modalStyle"
  >
    <div class="settings-shell">
      <header class="settings-heading">
        <h2>{{ modalTitle }}</h2>
        <p v-if="modalDescription">
          {{ modalDescription }}
        </p>
      </header>

      <div v-if="mode === 'profile'" class="settings-profile-pane">
        <General />
      </div>

      <NTabs v-else v-model:value="active" type="line" animated class="settings-tabs">
        <NTabPane v-if="mode === 'all'" name="General" tab="General">
          <template #tab>
            <SvgIcon class="text-lg" icon="ri:file-user-line" />
            <span class="ml-2">{{ t('setting.general') }}</span>
          </template>
          <div class="settings-pane">
            <General />
          </div>
        </NTabPane>
        <NTabPane name="Quota" tab="Quota">
          <template #tab>
            <SvgIcon class="text-lg" icon="eos-icons:quota-outlined" />
            <span class="ml-2">{{ t('setting.quota') }}</span>
          </template>
          <div class="settings-pane">
            <Quota :user-config="userConfig" :loading="loading" />
          </div>
        </NTabPane>
        <NTabPane name="ModifyPassword" tab="ModifyPassword">
          <template #tab>
            <SvgIcon class="text-lg" icon="carbon:password" />
            <span class="ml-2">{{ t('setting.modifyPassword') }}</span>
          </template>
          <div class="settings-pane">
            <ModifyPassword />
          </div>
        </NTabPane>
      </NTabs>
    </div>
  </NModal>
</template>

<style>
.settings-modal {
  overflow: hidden;
}

.settings-modal .n-card__content {
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
}

.settings-shell {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}

.settings-heading {
  padding: 0 44px 10px 0;
}

.settings-heading h2 {
  margin: 0;
  color: var(--zhimesh-text);
  font-size: 18px;
  font-weight: 720;
  letter-spacing: -0.02em;
}

.settings-heading p {
  margin: 4px 0 0;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.settings-profile-pane {
  min-height: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.settings-tabs {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.settings-tabs .n-tabs-tab {
  min-height: 42px;
  padding-inline: 10px;
}

.settings-tabs .n-tabs-pane-wrapper {
  min-height: 0;
  flex: 1;
}

.settings-tabs .n-tab-pane {
  height: 100%;
}

.settings-pane {
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

@media (max-width: 560px) {
  .settings-heading {
    padding-bottom: 8px;
  }

  .settings-tabs .n-tabs-tab {
    min-width: 0;
    flex: 1;
    justify-content: center;
    padding-inline: 6px;
    font-size: 13px;
  }

  .settings-tabs .n-tabs-tab .text-lg {
    display: none;
  }

  .settings-tabs .n-tabs-tab .ml-2 {
    margin-left: 0;
  }

  .settings-modal--account .settings-tabs .n-tabs-tab .text-lg {
    display: inline-flex;
  }

  .settings-modal--account .settings-tabs .n-tabs-tab .ml-2 {
    margin-left: 6px;
  }
}
</style>
