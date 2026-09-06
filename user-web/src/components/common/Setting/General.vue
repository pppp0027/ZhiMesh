<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NButton, NImage, NInput, NSelect, useMessage } from 'naive-ui'
import type { Language, Theme } from '@/store/modules/app/helper'
import { SvgIcon } from '@/components/common'
import { useAppStore, useAuthStore, useUserStore } from '@/store'
import { t } from '@/locales'
import api from '@/api'
import { getAvatarPoolUrl, resolveUserAvatarUrl } from '@/utils/avatar'

const appStore = useAppStore()
const userStore = useUserStore()
const authStore = useAuthStore()
const message = useMessage()

const theme = computed(() => appStore.theme)

const userInfo = computed(() => userStore.userInfo)

const avatar = computed(() => resolveUserAvatarUrl(
  userInfo.value.avatar,
  userInfo.value.uuid,
  userInfo.value.uuid || userInfo.value.name,
))
const fallbackAvatar = computed(() => getAvatarPoolUrl(userInfo.value.uuid || userInfo.value.name))

const name = computed(() => userInfo.value.name?.trim() || t('setting.unnamedUser'))
const editingName = ref(false)
const nameDraft = ref('')
const nameSaving = ref(false)
const normalizedNameDraft = computed(() => nameDraft.value.trim())
const nameError = computed(() => {
  if (!normalizedNameDraft.value)
    return t('setting.nameRequired')
  if (normalizedNameDraft.value.length > 45)
    return t('setting.nameTooLong')
  return ''
})
const canSaveName = computed(() => !nameSaving.value
  && !nameError.value
  && normalizedNameDraft.value !== (userInfo.value.name?.trim() || ''))

const submitting = ref(false)

function startNameEdit() {
  nameDraft.value = userInfo.value.name ?? ''
  editingName.value = true
}

function cancelNameEdit() {
  nameDraft.value = userInfo.value.name ?? ''
  editingName.value = false
}

async function saveName() {
  if (!canSaveName.value)
    return

  const nextName = normalizedNameDraft.value
  nameSaving.value = true
  try {
    await api.userEdit<void>({ name: nextName })
    userStore.updateUserInfo({ name: nextName })
    editingName.value = false
    message.success(t('setting.nameUpdateSuccess'))
  } catch (error: any) {
    message.error(error?.message || t('setting.nameUpdateFailed'))
  } finally {
    nameSaving.value = false
  }
}

const language = computed({
  get() {
    return appStore.language
  },
  set(value: Language) {
    appStore.setLanguage(value)
    if (authStore.token)
      api.userEdit({ locale: value })
  },
})

const themeOptions: { labelKey: string; key: Theme; icon: string }[] = [
  {
    labelKey: 'setting.themeAuto',
    key: 'auto',
    icon: 'ri:contrast-line',
  },
  {
    labelKey: 'setting.themeLight',
    key: 'light',
    icon: 'ri:sun-foggy-line',
  },
  {
    labelKey: 'setting.themeDark',
    key: 'dark',
    icon: 'ri:moon-foggy-line',
  },
]

const languageOptions: { label: string; key: Language; value: Language }[] = [
  { label: '简体中文', key: 'zh-CN', value: 'zh-CN' },
  { label: 'English', key: 'en-US', value: 'en-US' },
]

async function logout() {
  if (submitting.value)
    return
  submitting.value = true
  try {
    await api.logout()
  } catch (error) {
    console.error(error)
  } finally {
    submitting.value = false
  }
  authStore.removeToken()
  userStore.resetUserInfo()
  window.location.reload()
}
</script>

<template>
  <div class="general-settings">
    <div class="account-row">
      <NImage
        :src="avatar"
        :fallback-src="fallbackAvatar"
        :width="52"
        :height="52"
        preview-disabled
        object-fit="cover"
        class="account-avatar"
        :alt="`${name || '用户'}的头像`"
      />
      <div class="account-copy">
        <template v-if="editingName">
          <div class="name-editor">
            <NInput
              v-model:value="nameDraft"
              size="medium"
              :maxlength="45"
              :placeholder="t('setting.namePlaceholder')"
              :status="nameError ? 'error' : undefined"
              :input-props="{ 'aria-label': t('setting.name'), 'autocomplete': 'nickname' }"
              autofocus
              @keyup.enter="saveName"
              @keyup.esc="cancelNameEdit"
            />
            <NButton type="primary" size="medium" :loading="nameSaving" :disabled="!canSaveName" @click="saveName">
              {{ t('common.save') }}
            </NButton>
            <NButton size="medium" secondary :disabled="nameSaving" @click="cancelNameEdit">
              {{ t('common.cancel') }}
            </NButton>
          </div>
          <span class="name-hint" :class="{ 'name-hint-error': nameError }">
            {{ nameError || t('setting.nameRepeatableHint') }}
          </span>
        </template>
        <template v-else>
          <div class="name-display">
            <h3 id="account-name" :title="name">
              {{ name }}
            </h3>
            <NButton text type="primary" class="name-edit-button" :aria-label="t('setting.editName')" @click="startNameEdit">
              <template #icon>
                <SvgIcon icon="ri:edit-line" />
              </template>
              {{ t('common.edit') }}
            </NButton>
          </div>
          <span>{{ t('setting.nameRepeatableHint') }}</span>
        </template>
      </div>
    </div>

    <div class="setting-row">
      <span class="setting-label">{{ t('setting.theme') }}</span>
      <div class="theme-options" role="group" :aria-label="t('setting.theme')">
        <template v-for="item of themeOptions" :key="item.key">
          <NButton
            class="theme-option-button"
            size="medium"
            :type="item.key === theme ? 'primary' : undefined"
            :secondary="item.key !== theme"
            :aria-label="t(item.labelKey)"
            :title="t(item.labelKey)"
            :aria-pressed="item.key === theme"
            @click="appStore.setTheme(item.key)"
          >
            <template #icon>
              <SvgIcon :icon="item.icon" />
            </template>
            <span class="theme-option-label">{{ t(item.labelKey) }}</span>
          </NButton>
        </template>
      </div>
    </div>

    <div class="setting-row">
      <span class="setting-label">{{ t('setting.language') }}</span>
      <NSelect
        class="language-select"
        :value="language"
        :options="languageOptions"
        @update-value="(value: Language) => language = value"
      />
    </div>

    <div class="general-actions">
      <NButton type="error" secondary size="medium" :loading="submitting" :disabled="submitting" @click="logout">
        <template #icon>
          <SvgIcon icon="ri:logout-box-r-line" />
        </template>
        {{ t('common.logout') }}
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.general-settings {
  display: flex;
  flex-direction: column;
  padding: 16px 2px 2px;
}

.account-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 2px 0 16px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.account-avatar {
  width: 52px !important;
  height: 52px !important;
  flex: none;
  overflow: hidden;
  border-radius: 10px;
  box-shadow: none !important;
}

.account-avatar :deep(img) {
  width: 52px !important;
  height: 52px !important;
}

.account-copy {
  flex: 1;
  min-width: 0;
}

.account-copy > span {
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.name-display {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.name-display h3 {
  min-width: 0;
  flex: 0 1 auto;
  margin: 0;
  color: var(--zhimesh-text);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.015em;
}

.name-display h3 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-edit-button {
  flex: none;
}

.name-editor {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
}

.name-editor :deep(.n-input) {
  min-width: 0;
}

.name-hint {
  display: block;
  margin-top: 5px;
}

.name-hint-error {
  color: var(--n-color-error, #d03050) !important;
}

.setting-row {
  display: grid;
  min-height: 58px;
  grid-template-columns: 76px minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.setting-label {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 650;
}

.theme-options {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.theme-options :deep(.n-button) {
  min-height: 40px;
}

.language-select {
  width: min(100%, 220px);
}

.general-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.general-actions :deep(.n-button) {
  min-height: 40px;
}

@media (max-width: 767px) {
  .theme-options {
    display: flex;
    gap: 6px;
  }

  .theme-option-button {
    width: 44px;
    min-width: 44px;
    height: 44px;
    min-height: 44px !important;
    padding: 0 !important;
  }

  .theme-option-button :deep(.n-button__icon) {
    margin: 0 !important;
    font-size: 19px;
  }

  .theme-option-label {
    display: none;
  }
}

@media (max-width: 430px) {
  .account-row {
    align-items: flex-start;
  }

  .name-editor {
    grid-template-columns: 1fr 1fr;
  }

  .name-editor :deep(.n-input) {
    grid-column: 1 / -1;
  }

  .setting-row {
    grid-template-columns: 1fr;
    gap: 8px;
    padding: 12px 0;
  }

  .language-select {
    width: 100%;
  }
}
</style>
