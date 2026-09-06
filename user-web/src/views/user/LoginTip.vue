<script setup lang='ts'>
import { NButton } from 'naive-ui'
import { useAuthStore } from '@/store'
import { t } from '@/locales'
import brandLogo from '@/assets/zhimesh-logo.svg'

const authStore = useAuthStore()
</script>

<template>
  <div class="login-tip" role="status">
    <img
      class="login-tip-mark" :src="brandLogo" alt="" width="40" height="40"
      aria-hidden="true" draggable="false"
    >
    <div class="login-tip-copy">
      <strong>登录后继续使用</strong>
      <NButton text type="primary" @click="authStore.setLoginView(true)">
        {{ t('common.clickToLogin') }}
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="less">
.login-tip {
  display: flex;
  width: min(520px, calc(100% - 24px));
  margin: 32px auto;
  padding: 18px 20px;
  align-items: center;
  gap: 14px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 10px;
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-strong);
}

.login-tip-mark {
  display: block;
  width: 40px;
  height: 40px;
  flex: none;
}

.login-tip-copy {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.login-tip-copy strong {
  font-size: 13px;
  font-weight: 650;
}

@media (max-width: 520px) {
  .login-tip-copy {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}
</style>
