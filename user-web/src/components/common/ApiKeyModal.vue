<script setup lang='ts'>
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NInput, NModal, NPopconfirm, NSpin, useMessage } from 'naive-ui'
import ApiDocPanel from './ApiDocPanel.vue'
import SvgIcon from './SvgIcon/index.vue'
import api from '@/api'
import { t } from '@/locales'
import { useAuthStore } from '@/store'

interface Props {
  show: boolean
  type: string
  uuid: string
  title: string
  wfInputDefs?: Workflow.NodeIODefinition[]
}
interface Emit {
  (ev: 'update:show', value: boolean): void
}
const props = withDefaults(defineProps<Props>(), {
  show: false,
  type: '',
  uuid: '',
  title: '',
})
const emit = defineEmits<Emit>()
const ms = useMessage()
const authStore = useAuthStore()

const innerShow = ref(false)
const loading = ref(false)
const maskedKey = ref('')
const rawKey = ref('')
const showingRaw = ref(false)
const justGenerated = ref(false)
const canManage = ref(false)

// 判断是否为用户级 API Key 类型
const isUserLevelType = computed(() => ['draw', 'mcp'].includes(props.type))

const isLoggedIn = computed(() => !!authStore.token)
const resourceLabel = computed(() => props.type === 'knowledge'
  ? t('extApi.knowledgeResource')
  : props.type === 'workflow'
    ? t('extApi.workflowResource')
    : t('extApi.apiResource'))

watch(() => props.show, (val) => {
  innerShow.value = val
  if (val && isLoggedIn.value)
    fetchKeyInfo()
})

watch(() => innerShow.value, (val) => {
  if (!val) {
    emit('update:show', false)
    rawKey.value = ''
    showingRaw.value = false
    justGenerated.value = false
  }
})

async function fetchKeyInfo() {
  if (loading.value)
    return
  loading.value = true
  try {
    let data
    if (isUserLevelType.value)
      data = (await api.extApiKeyUserInfo(props.type)).data
    else
      data = (await api.extApiKeyInfo(props.type, props.uuid)).data
    maskedKey.value = data?.maskedKey ?? ''
    canManage.value = data?.canManage ?? false
    rawKey.value = ''
    showingRaw.value = false
    justGenerated.value = false
  } catch {
    maskedKey.value = ''
    rawKey.value = ''
    ms.error(t('common.wrong'))
  } finally {
    loading.value = false
  }
}

async function handleGenerate() {
  loading.value = true
  try {
    let data
    if (isUserLevelType.value)
      data = (await api.extApiKeyUserGenerate(props.type)).data
    else
      data = (await api.extApiKeyGenerate(props.type, props.uuid)).data
    maskedKey.value = data.maskedKey
    rawKey.value = data.rawKey
    justGenerated.value = true
    showingRaw.value = true
  } catch {
    ms.error(t('common.wrong'))
  } finally {
    loading.value = false
  }
}

async function handleReveal() {
  if (showingRaw.value) {
    showingRaw.value = false
    rawKey.value = ''
    return
  }
  loading.value = true
  try {
    let data
    if (isUserLevelType.value)
      data = (await api.extApiKeyUserReveal(props.type)).data
    else
      data = (await api.extApiKeyReveal(props.type, props.uuid)).data
    rawKey.value = data.rawKey
    showingRaw.value = true
  } catch {
    ms.error(t('common.wrong'))
  } finally {
    loading.value = false
  }
}

async function handleCopy() {
  if (!rawKey.value)
    return
  try {
    await navigator.clipboard.writeText(rawKey.value)
    ms.success(t('extApi.copySuccess'))
  } catch {
    ms.error(t('common.wrong'))
  }
}
</script>

<template>
  <NModal
    v-model:show="innerShow" class="api-access-modal" style="width: min(840px, calc(100vw - 32px))"
    preset="card" :mask-closable="false" :auto-focus="false"
    content-style="padding: 0; max-height: min(76vh, 720px); overflow-y: auto;"
  >
    <template #header>
      <div class="api-access-heading">
        <span class="api-access-heading__icon"><SvgIcon icon="carbon:api" /></span>
        <div class="api-access-heading__copy">
          <strong>{{ t('extApi.accessTitle') }}</strong>
          <span>{{ resourceLabel }} · {{ title }}</span>
        </div>
      </div>
    </template>

    <div class="api-access-body">
      <section v-if="isLoggedIn" class="credential-section" aria-labelledby="credential-title">
        <div class="section-heading">
          <div>
            <h3 id="credential-title">
              {{ t('extApi.credentialTitle') }}
            </h3>
            <p>{{ t('extApi.credentialHint') }}</p>
          </div>
          <span v-if="canManage" class="credential-status" :class="{ 'is-ready': !!maskedKey }">
            <i />{{ maskedKey ? t('extApi.credentialReady') : t('extApi.credentialEmpty') }}
          </span>
        </div>

        <NSpin :show="loading">
          <div v-if="canManage && !maskedKey" class="credential-empty">
            <div>
              <strong>{{ t('extApi.noKey') }}</strong>
              <span>{{ t('extApi.credentialEmptyDesc') }}</span>
            </div>
            <NButton type="primary" @click="handleGenerate">
              <template #icon>
                <SvgIcon icon="ri:key-2-line" />
              </template>
              {{ t('extApi.generateKey') }}
            </NButton>
          </div>

          <div v-else-if="canManage" class="credential-ready">
            <NAlert v-if="justGenerated" type="warning" :show-icon="true" class="mb-3">
              {{ t('extApi.closeWarning') }}
            </NAlert>
            <div class="credential-input-row">
              <NInput
                :value="showingRaw ? rawKey : maskedKey" readonly
                :aria-label="t('extApi.apiKeyLabel')" class="credential-input"
              />
              <NButton v-if="showingRaw" secondary type="primary" @click="handleCopy">
                <template #icon>
                  <SvgIcon icon="ri:file-copy-line" />
                </template>
                {{ t('extApi.copyKey') }}
              </NButton>
              <NButton secondary @click="handleReveal">
                <template #icon>
                  <SvgIcon :icon="showingRaw ? 'ri:eye-off-line' : 'ri:eye-line'" />
                </template>
                {{ showingRaw ? t('extApi.hideKey') : t('extApi.viewKey') }}
              </NButton>
              <NPopconfirm @positive-click="handleGenerate">
                <template #trigger>
                  <NButton quaternary :aria-label="t('extApi.regenerateKey')" :title="t('extApi.regenerateKey')">
                    <template #icon>
                      <SvgIcon icon="ri:refresh-line" />
                    </template>
                  </NButton>
                </template>
                {{ t('extApi.regenerateConfirm') }}
              </NPopconfirm>
            </div>
          </div>
        </NSpin>
      </section>

      <ApiDocPanel
        :type="props.type" :uuid="props.uuid" :wf-input-defs="props.wfInputDefs"
        :api-key="showingRaw ? rawKey : ''"
      />
    </div>
  </NModal>
</template>

<style scoped>
.api-access-heading,
.api-access-body,
.section-heading,
.credential-empty,
.credential-input-row {
  display: flex;
}

.api-access-heading {
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.api-access-heading__icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: none;
  place-items: center;
  border-radius: 12px;
  color: #fff;
  background: var(--zhimesh-control-primary);
  font-size: 19px;
}

.api-access-heading__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.api-access-heading__copy strong {
  color: var(--zhimesh-text);
  font-size: 17px;
}

.api-access-heading__copy span,
.section-heading p,
.credential-empty span {
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.api-access-heading__copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.api-access-body {
  flex-direction: column;
  gap: 28px;
  padding: 22px 24px 28px;
}

.credential-section {
  padding-bottom: 24px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.section-heading {
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.section-heading h3 {
  margin: 0 0 4px;
  color: var(--zhimesh-text);
  font-size: 15px;
  font-weight: 700;
}

.section-heading p {
  margin: 0;
}

.credential-status {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  border-radius: 999px;
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
  font-size: 11px;
  font-weight: 650;
}

.credential-status i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9aa4ad;
}

.credential-status.is-ready {
  color: #19724a;
  background: rgb(47 186 109 / 10%);
}

.credential-status.is-ready i {
  background: #2fba6d;
}

.credential-empty {
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 16px;
  border-radius: 14px;
  background: var(--zhimesh-glass-soft);
}

.credential-empty > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.credential-empty strong {
  color: var(--zhimesh-text);
  font-size: 13px;
}

.credential-input-row {
  align-items: center;
  gap: 8px;
}

.credential-input {
  min-width: 0;
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

:deep(.credential-input .n-input__input-el) {
  font-size: 12px;
}

@media (max-width: 680px) {
  .api-access-body {
    gap: 22px;
    padding: 18px 16px 22px;
  }

  .credential-empty,
  .credential-input-row {
    align-items: stretch;
    flex-direction: column;
  }

  .credential-input-row :deep(.n-button) {
    width: 100%;
  }
}
</style>
