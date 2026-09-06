<script setup lang='ts'>
import { computed, h, ref, watch } from 'vue'
import { NAlert, NButton, NDropdown, NIcon, NInput, NModal, NRadio, NRadioGroup, NTabPane, NTable, NTabs, NTooltip, useLoadingBar, useMessage } from 'naive-ui'
import { QuestionCircle16Regular } from '@vicons/fluent'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import mdKatex from '@traptitech/markdown-it-katex'
import mila from 'markdown-it-link-attributes'
import McpInfoList from './McpInfoList.vue'
import UserMcpList from './UserMcpList.vue'
import { t } from '@/locales'
import { useAuthStore, useMcpStore } from '@/store'
import { ApiKeyModal, SvgIcon } from '@/components/common'

import { emptyMcp, emptyUserMcp } from '@/utils/functions'
import api from '@/api'

const authStore = useAuthStore()
const ms = useMessage()
const mcpStore = useMcpStore()
const showConfigModal = ref<boolean>(false)
const showApiKeyModal = ref<boolean>(false)
const selectedMcp = ref<Mcp.McpInfo>(emptyMcp())
const selectedUserMcp = ref<Mcp.UserMcp>(emptyUserMcp())
const publicOrUser = ref<string>('serversView')
const loaddingBar = useLoadingBar()
const selectedTab = ref<string>('configTab')

function renderIcon(icon: string) {
  return () => h(SvgIcon, { icon, class: 'text-base cursor-pointer' })
}

const menuOptions = computed(() => [
  { label: t('extApi.apiAccess'), key: 'api', icon: renderIcon('carbon:api') },
])

function handleMenuSelect(key: string) {
  if (key === 'api')
    showApiKeyModal.value = true
}

const mdi = new MarkdownIt({
  linkify: true,
  highlight(code, language) {
    const validLang = !!(language && hljs.getLanguage(language))
    if (validLang) {
      const lang = language ?? ''
      return highlightBlock(hljs.highlight(code, { language: lang }).value, lang)
    }
    return highlightBlock(hljs.highlightAuto(code).value, '')
  },
})

mdi.use(mila, { attrs: { target: '_blank', rel: 'noopener' } })
mdi.use(mdKatex, { blockClass: 'katexmath-block rounded-md p-[10px]', errorColor: ' #cc0000' })

function highlightBlock(str: string, lang?: string) {
  return `<pre class="code-block-wrapper"><div class="code-block-header"><span class="code-block-header__lang">${lang}</span><span class="code-block-header__copy">${t('chat.copyCode')}</span></div><code class="hljs code-block-body ${lang}">${str}</code></pre>`
}

function onShowInfoModal(mcpInfo: Mcp.McpInfo) {
  selectedMcp.value = mcpInfo
  selectedTab.value = 'introTab'
  showConfigModal.value = true
}

function onShowConfigModal(mcpInfo: Mcp.McpInfo) {
  if (!authStore.checkLoginOrShow())
    return
  const userMcp = mcpStore.myUserMcpList.find(mcp => mcp.mcpId === mcpInfo.id)
  if (userMcp)
    selectedUserMcp.value = userMcp
  else
    selectedUserMcp.value = emptyUserMcp()
  selectedTab.value = 'configTab'
  selectedMcp.value = mcpInfo
  showConfigModal.value = true
}

async function onSaveConfig() {
  if (!authStore.checkLoginOrShow())
    return
  const customizedParams = selectedMcp.value.customizedParamDefinitions.map(param => ({
    name: param.name,
    value: param.value,
    enctrypted: param.enctrypted,
  }))
  const params = {
    mcpId: selectedMcp.value.id,
    mcpCustomizedParams: customizedParams,
    isEnable: selectedUserMcp.value.isEnable,
  }
  try {
    await api.userMcpSaveOrUpdate(params)
    await loadMyUserMcpList(false)
    ms.success(t('mcp.saveConfigSuccess'), {
      duration: 3000,
    })
    showConfigModal.value = false
  } catch (error) {
    console.error(error)
    ms.error(t('mcp.saveConfigFailed'))
  }
}

async function loadMyUserMcpList(showLoaddingBar = true) {
  if (mcpStore.userMcpLoading || !authStore.token || mcpStore.myUserMcpList.length > 0)
    return
  if (showLoaddingBar)
    loaddingBar.start()
  try {
    mcpStore.setUserMcpLoading(true)
    const { data } = await api.userMcpList<Mcp.UserMcpListResp>(1, 200)
    if (data.records.length > 0)
      mcpStore.appendMyUserMcpList(data.records)
  } catch (error) {
    console.error(error)
  } finally {
    mcpStore.setUserMcpLoading(false)
    if (showLoaddingBar)
      loaddingBar.finish()
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
  <div class="flex flex-col w-full h-full mcp-page-shell">
    <header class="mcp-page-header">
      <div class="mcp-page-header-inner">
        <div class="mcp-page-title">
          <span class="mcp-page-title-icon"><SvgIcon icon="ri:tools-line" /></span>
          <div>
            <strong>{{ t('mcp.title') }}</strong>
            <span>{{ t('mcp.pageHint') }}</span>
          </div>
        </div>
        <div class="mcp-view-switch">
          <NRadioGroup v-model:value="publicOrUser" name="displayStyleRadioGroup" size="small">
            <NRadio value="serversView">
              {{ t('mcp.servicesAndTools') }}
            </NRadio>
            <NRadio value="userView">
              {{ t('mcp.myTools') }}
            </NRadio>
          </NRadioGroup>
        </div>
        <div class="flex items-center mcp-page-menu">
          <NDropdown :options="menuOptions" trigger="click" @select="handleMenuSelect">
            <NButton quaternary circle size="small">
              <template #icon>
                <SvgIcon icon="ri:more-fill" />
              </template>
            </NButton>
          </NDropdown>
        </div>
      </div>
    </header>

    <main class="flex-1 overflow-y-auto h-full w-full max-w-screen-xl m-auto mcp-page-main">
      <McpInfoList
        v-show="publicOrUser === 'serversView'" @show-info-modal="onShowInfoModal"
        @show-config-modal="onShowConfigModal"
      />
      <UserMcpList
        v-show="publicOrUser === 'userView'" @show-info-modal="onShowInfoModal"
        @show-config-modal="onShowConfigModal"
      />
    </main>

    <NModal v-model:show="showConfigModal" style="width: 90%; max-width: 1000px;" preset="card">
      <template #header>
        <h2 class="text-xl font-bold">
          {{ selectedMcp.title }}-<span v-if="selectedTab === 'configTab'">{{ t('mcp.configLabel') }}</span><span
            v-if="selectedTab === 'introTab'"
          >{{ t('mcp.introLabel') }}</span>
        </h2>
      </template>
      <NTabs type="line" justify-content="space-evenly" :value="selectedTab" @update:value="val => selectedTab = val">
        <NTabPane name="introTab" :tab="t('mcp.introTab')">
          <div class="flex flex-col space-y-2 max-h-[720px] overflow-y-auto p-2">
            <div>
              <div class="w-full markdown-body" v-html="mdi.render(selectedMcp.remark)" />
            </div>
            <NAlert v-if="selectedMcp.website" :show-icon="false" type="info">
              {{ t('mcp.relatedWebsite') }}{{ selectedMcp.website }}
            </NAlert>
          </div>
        </NTabPane>
        <NTabPane name="configTab" :tab="t('mcp.configTab')">
          <div class="flex flex-col space-y-1 max-h-[720px] overflow-y-auto">
            <NAlert v-if="selectedMcp.customizedParamDefinitions.length === 0" :show-icon="false" type="info">
              {{ t('mcp.noConfigRequired') }}
            </NAlert>
            <div v-if="selectedMcp.customizedParamDefinitions.length > 0" class="flex flex-col space-y-2">
              <div class="font-bold text-base">
                {{ t('mcp.serviceParam') }}<span class="text-sm text-gray-500">{{ t('mcp.pleaseReferIntroConfig') }}</span>
              </div>
              <NTable :bordered="false" :single-line="false">
                <thead>
                  <tr>
                    <th>{{ t('common.param') }}</th>
                    <th>{{ t('common.value') }}</th>
                    <th class="flex justify-center">
                      {{ t('common.sensitiveInfo') }}
                      <NTooltip trigger="hover">
                        <template #trigger>
                          <NIcon style="padding-top: 0.1rem">
                            <QuestionCircle16Regular />
                          </NIcon>
                        </template>
                        <span>{{ t('common.sensitiveInfoEncryptTip') }}</span>
                      </NTooltip>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="uninitParam in selectedMcp.customizedParamDefinitions" :key="uninitParam.name">
                    <td class="max-w-[200px]">
                      {{ uninitParam.title }}
                    </td>
                    <td>
                      <NInput
                        v-model:value="uninitParam.value" class="flex-1"
                        :placeholder="`${t('common.pleaseInputValue')}${uninitParam.name}`"
                      />
                    </td>
                    <td class="flex justify-center">
                      <span v-if="uninitParam.require_encrypt">{{ t('common.yes') }}</span>
                      <span v-if="!uninitParam.require_encrypt">{{ t('common.no') }}</span>
                    </td>
                  </tr>
                </tbody>
              </NTable>
            </div>
            <div class="pt-4 flex flex-col space-y-2">
              <div class="font-bold text-base">
                {{ t('mcp.statusLabel') }}
              </div>
              <NRadioGroup v-model:value="selectedUserMcp.isEnable" name="enableGroup">
                <NRadio :value="true">
                  {{ t('mcp.statusEnable') }}
                </NRadio>
                <NRadio :value="false">
                  {{ t('mcp.statusDraft') }}
                </NRadio>
              </NRadioGroup>
            </div>
            <div class="flex justify-end p-2">
              <NButton type="primary" @click="onSaveConfig">
                {{ t('common.confirm') }}
              </NButton>
            </div>
          </div>
        </NTabPane>
      </NTabs>
    </NModal>

    <ApiKeyModal v-model:show="showApiKeyModal" type="mcp" uuid="" :title="t('mcp.title')" />
  </div>
</template>

<style lang="less" scoped>
.mcp-page-shell {
  background: transparent;
}

.mcp-page-header {
  flex: none;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-nav-strong);
  box-shadow: 0 1px 0 var(--zhimesh-glass-highlight);
  backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
  -webkit-backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
}

.mcp-page-header-inner {
  display: flex;
  min-height: 68px;
  max-width: 1180px;
  margin: 0 auto;
  padding: 10px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.mcp-page-title,
.mcp-page-title > div,
.mcp-view-switch {
  display: flex;
  align-items: center;
}

.mcp-page-title {
  gap: 10px;
}

.mcp-page-title-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 12px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
  font-size: 19px;
}

.mcp-page-title > div {
  flex-direction: column;
  align-items: flex-start;
}

.mcp-page-title strong {
  font-size: 17px;
}

.mcp-page-title span:not(.mcp-page-title-icon) {
  margin-top: 2px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.mcp-view-switch {
  padding: 4px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 12px;
  background: var(--zhimesh-glass-soft);
}

.mcp-view-switch :deep(.n-radio-group) {
  gap: 2px;
}

.mcp-view-switch :deep(.n-radio) {
  margin: 0;
  padding: 7px 11px;
  border-radius: 8px;
}

.mcp-view-switch :deep(.n-radio--checked) {
  background: var(--zhimesh-border-subtle);
}

.mcp-page-menu {
  margin-left: 2px;
}

.mcp-page-main {
  width: 100%;
}

:deep(.markdown-body ul) {
  list-style-type: inherit;
}

:deep(.markdown-body ol) {
  list-style-type: inherit;
}

@media (max-width: 767px) {
  .mcp-page-header {
    padding-top: env(safe-area-inset-top);
  }

  .mcp-page-header-inner {
    display: grid;
    min-height: 0;
    padding: 10px 12px;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 10px;
  }

  .mcp-page-title {
    min-width: 0;
  }

  .mcp-page-title > div {
    min-width: 0;
  }

  .mcp-page-title strong {
    overflow: hidden;
    max-width: 100%;
    font-size: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mcp-page-title span:not(.mcp-page-title-icon) {
    display: none;
  }

  .mcp-page-title-icon {
    width: 36px;
    height: 36px;
    border-radius: 10px;
  }

  .mcp-view-switch {
    width: 100%;
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .mcp-view-switch :deep(.n-radio-group) {
    display: grid;
    width: 100%;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mcp-view-switch :deep(.n-radio) {
    min-height: 40px;
    justify-content: center;
  }

  .mcp-page-menu :deep(.n-button) {
    width: 44px;
    height: 44px;
  }
}
</style>
