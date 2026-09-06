<script setup lang="ts">
import { inject } from 'vue'
import SvgIcon from './SvgIcon/index.vue'
import UserAvatar from './UserAvatar/index.vue'
import { useAuthStore } from '@/store'
import { t } from '@/locales'

type SettingsSection = 'General' | 'Quota' | 'ModifyPassword'
type SettingsMode = 'all' | 'profile' | 'account'

const authStore = useAuthStore()
const openAppSettings = inject<(section?: SettingsSection, mode?: SettingsMode) => void>('openAppSettings', () => {})

function openAccount() {
  if (!authStore.token) {
    authStore.setLoginView(true)
    return
  }
  openAppSettings('General', 'profile')
}

function openSettings() {
  openAppSettings('Quota', 'account')
}
</script>

<template>
  <footer class="sider-account-bar" :aria-label="t('setting.accountActions')">
    <button
      type="button"
      class="sider-account-profile"
      :aria-label="authStore.token ? t('setting.viewAccount') : t('common.login')"
      @click="openAccount"
    >
      <UserAvatar v-if="authStore.token" class="sider-account-user" />
      <span v-else class="sider-account-login">
        <span class="sider-account-login-icon"><SvgIcon icon="ri:user-3-line" /></span>
        <span>
          <strong>{{ t('common.login') }}</strong>
          <small>{{ t('setting.loginToManageAccount') }}</small>
        </span>
      </span>
      <SvgIcon class="sider-account-chevron" icon="ri:arrow-right-s-line" />
    </button>

    <button
      v-if="authStore.token"
      type="button"
      class="sider-account-settings"
      :aria-label="t('setting.openSettings')"
      :title="t('setting.setting')"
      @click="openSettings"
    >
      <SvgIcon icon="ri:settings-3-line" />
    </button>
  </footer>
</template>

<style scoped>
.sider-account-bar {
  display: flex;
  flex: none;
  min-width: 0;
  padding: 9px 12px max(9px, env(safe-area-inset-bottom));
  align-items: center;
  gap: 8px;
  border-top: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-nav-strong);
  backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
  -webkit-backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
}

.sider-account-profile,
.sider-account-settings {
  border: 0;
  color: var(--zhimesh-text);
  background: transparent;
  font: inherit;
  cursor: pointer;
  transition: color 160ms ease, background-color 180ms ease, transform 160ms cubic-bezier(0.22, 1, 0.36, 1);
}

.sider-account-profile {
  display: flex;
  min-width: 0;
  min-height: 52px;
  flex: 1;
  padding: 6px 8px;
  align-items: center;
  gap: 6px;
  border-radius: 12px;
  text-align: left;
}

.sider-account-profile:hover,
.sider-account-profile:focus-visible,
.sider-account-settings:hover,
.sider-account-settings:focus-visible {
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
  outline: none;
}

.sider-account-profile:active,
.sider-account-settings:active {
  transform: scale(0.97);
}

.sider-account-user {
  min-width: 0;
  flex: 1;
}

.sider-account-user :deep(h2) {
  margin: 0;
  color: var(--zhimesh-text);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.3;
}

.sider-account-user :deep(p) {
  margin: 2px 0 0;
  color: var(--zhimesh-text-muted);
  line-height: 1.3;
}

.sider-account-login {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 10px;
}

.sider-account-login > span:last-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.sider-account-login strong {
  font-size: 14px;
  line-height: 1.35;
}

.sider-account-login small {
  overflow: hidden;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sider-account-login-icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 50%;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
  font-size: 20px;
}

.sider-account-chevron {
  flex: none;
  color: var(--zhimesh-text-muted);
  font-size: 18px;
}

.sider-account-settings {
  display: grid;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  place-items: center;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 12px;
  background: var(--zhimesh-glass-soft);
  font-size: 21px;
}

@media (prefers-reduced-transparency: reduce) {
  .sider-account-bar {
    background: var(--zhimesh-glass-strong);
    backdrop-filter: none;
    -webkit-backdrop-filter: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .sider-account-profile,
  .sider-account-settings {
    transition: none;
  }
}
</style>
