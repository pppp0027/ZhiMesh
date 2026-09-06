<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NButton, NInput, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { t } from '@/locales'

const ms = useMessage()
const resetPasswordReturnType = ref<string>('')
const modifyPasswordMsg = ref<string>('')
const loading = ref(false)
const oldPassword = ref<string>('')
const newPassword = ref<string>('')
const confirmNewPassword = ref<string>('')
const confirmPasswordStatus = computed(() => {
  if (!oldPassword.value || !newPassword.value || !confirmNewPassword.value)
    return undefined
  return newPassword.value !== confirmNewPassword.value ? 'error' : 'success'
})

async function handleModifyPassword() {
  const newPwd = newPassword.value.trim()
  const confirmNewPwd = confirmNewPassword.value.trim()

  if (!newPassword.value || !confirmNewPwd || newPwd !== confirmNewPwd) {
    ms.error(t('common.passwordNotMatch'))
    return
  }

  try {
    loading.value = true
    const result = await api.modifyPassword(oldPassword.value, newPassword.value)
    modifyPasswordMsg.value = result.data as string
    resetPasswordReturnType.value = 'success'
    ms.success(modifyPasswordMsg.value)
  } catch (error: any) {
    ms.error(error.message ?? 'error')
    modifyPasswordMsg.value = error.message as string
    resetPasswordReturnType.value = 'fail'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="password-settings">
    <div class="password-form">
      <label class="password-field">
        <span>{{ t('common.password') }}</span>
        <NInput
          v-model:value="oldPassword"
          type="password"
          :placeholder="t('common.password')"
          autocomplete="current-password"
          show-password-on="click"
        />
      </label>
      <label class="password-field">
        <span>{{ t('common.newPassword') }}</span>
        <NInput
          v-model:value="newPassword"
          type="password"
          :placeholder="t('common.newPassword')"
          autocomplete="new-password"
          show-password-on="click"
          :status="confirmPasswordStatus"
        />
      </label>
      <label class="password-field">
        <span>{{ t('common.newPasswordRepeat') }}</span>
        <NInput
          v-model:value="confirmNewPassword"
          type="password"
          :placeholder="t('common.confirmPassword')"
          autocomplete="new-password"
          show-password-on="click"
          :status="confirmPasswordStatus"
        />
      </label>
      <div v-if="modifyPasswordMsg" class="password-result" role="status">
        <NTag :type="resetPasswordReturnType ? 'success' : 'error'">
          {{ modifyPasswordMsg }}
        </NTag>
      </div>
      <div class="password-actions">
        <NButton
          type="primary"
          size="medium"
          :disabled="loading || !oldPassword || !newPassword || newPassword !== confirmNewPassword"
          :loading="loading"
          @click="handleModifyPassword"
        >
          {{ t('setting.modifyPassword') }}
        </NButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.password-settings {
  padding: 16px 2px 2px;
}

.password-form {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.password-field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.password-field > span {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 650;
}

.password-field :deep(.n-input) {
  min-height: 42px;
}

.password-result :deep(.n-tag) {
  max-width: 100%;
  white-space: normal;
}

.password-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
  border-top: 1px solid var(--zhimesh-border-subtle);
}

.password-actions :deep(.n-button) {
  min-width: 112px;
  min-height: 40px;
}
</style>
