<script setup lang='ts'>
import { computed, ref } from 'vue'
import { NButton, NCollapse, NCollapseItem, NTable, useMessage } from 'naive-ui'
import SvgIcon from './SvgIcon/index.vue'
import { t } from '@/locales'
import { useCharacterDoc } from '@/hooks/api-doc/useCharacterDoc'
import { useKnowledgeDoc } from '@/hooks/api-doc/useKnowledgeDoc'
import { useWorkflowDoc } from '@/hooks/api-doc/useWorkflowDoc'
import { useDrawDoc } from '@/hooks/api-doc/useDrawDoc'
import { useMcpDoc } from '@/hooks/api-doc/useMcpDoc'

interface Props {
  type: string
  uuid?: string
  wfInputDefs?: Workflow.NodeIODefinition[]
  apiKey?: string
}
const props = defineProps<Props>()
const ms = useMessage()
const copiedIndex = ref(-1)
const copiedEndpointIndex = ref(-1)

const baseUrl = computed(() => {
  const apiBase = import.meta.env.VITE_GLOB_API_URL || ''
  const base = apiBase.replace(/\/$/, '')
  return `${window.location.origin}${base}/ext/v1`
})

const endpointInfo = computed(() => {
  const urlBase = baseUrl.value
  if (props.type === 'character')
    return useCharacterDoc(urlBase)
  if (props.type === 'knowledge')
    return useKnowledgeDoc(urlBase)
  if (props.type === 'draw')
    return useDrawDoc(urlBase)
  if (props.type === 'mcp')
    return useMcpDoc(urlBase)
  return useWorkflowDoc(urlBase, props.wfInputDefs)
})

function runnableCurl(curlExample: string) {
  return props.apiKey ? curlExample.replaceAll('YOUR_API_KEY', props.apiKey) : curlExample
}

function handleCopyCurl(index: number, curlExample: string) {
  navigator.clipboard.writeText(runnableCurl(curlExample)).then(() => {
    copiedIndex.value = index
    setTimeout(() => copiedIndex.value = -1, 2000)
  }).catch(() => ms.error(t('common.wrong')))
}

function handleCopyEndpoint(index: number, endpoint: string) {
  navigator.clipboard.writeText(endpoint).then(() => {
    copiedEndpointIndex.value = index
    setTimeout(() => copiedEndpointIndex.value = -1, 2000)
  }).catch(() => ms.error(t('common.wrong')))
}
</script>

<template>
  <section class="api-doc" aria-labelledby="api-doc-title">
    <div class="api-doc__heading">
      <div>
        <h3 id="api-doc-title">
          {{ t('extApi.quickStart') }}
        </h3>
        <p>{{ t('extApi.quickStartHint') }}</p>
      </div>
      <span v-if="apiKey" class="api-doc__key-state is-ready"><i />{{ t('extApi.apiKeyInjected') }}</span>
      <span v-else class="api-doc__key-state"><i />{{ t('extApi.apiKeyPlaceholder') }}</span>
    </div>

    <div
      v-for="(endpoint, index) in endpointInfo.endpoints"
      :id="`endpoint-${index}`"
      :key="index"
      class="endpoint-block"
    >
      <div class="endpoint-heading">
        <div>
          <h4>{{ endpoint.title }}</h4>
          <p v-if="endpoint.description">
            {{ endpoint.description }}
          </p>
        </div>
      </div>

      <div class="endpoint-address">
        <span>{{ endpoint.method }}</span>
        <code>{{ endpoint.endpoint }}</code>
        <NButton text size="small" @click="handleCopyEndpoint(index, endpoint.endpoint)">
          <template #icon>
            <SvgIcon icon="ri:file-copy-line" />
          </template>
          {{ copiedEndpointIndex === index ? t('chat.copied') : t('extApi.copyEndpoint') }}
        </NButton>
      </div>

      <div class="code-panel">
        <div class="code-panel__bar">
          <span>cURL</span>
          <NButton text size="small" @click="handleCopyCurl(index, endpoint.curlExample)">
            <template #icon>
              <SvgIcon icon="ri:file-copy-line" />
            </template>
            {{ copiedIndex === index ? t('chat.copied') : t('chat.copy') }}
          </NButton>
        </div>
        <pre><code>{{ runnableCurl(endpoint.curlExample) }}</code></pre>
      </div>

      <NCollapse class="endpoint-details">
        <NCollapseItem :title="t('extApi.requestDetails')" :name="`request-${index}`">
          <div class="request-headers">
            <div><span>Authorization</span><code>{{ apiKey || 'YOUR_API_KEY' }}</code></div>
            <div><span>Content-Type</span><code>application/json</code></div>
          </div>
          <NTable :bordered="true" :single-line="false" size="small">
            <thead>
              <tr>
                <th style="min-width: 120px">
                  {{ t('extApi.docParamName') }}
                </th>
                <th style="min-width: 60px">
                  Type
                </th>
                <th style="min-width: 70px">
                  Required
                </th>
                <th>{{ t('extApi.docParamDesc') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in endpoint.params" :key="p.name">
                <td>{{ p.name }}</td>
                <td>{{ p.type }}</td>
                <td>{{ p.required }}</td>
                <td v-html="p.desc" />
              </tr>
            </tbody>
          </NTable>
        </NCollapseItem>

        <NCollapseItem :title="t('extApi.responseDetails')" :name="`response-${index}`">
          <NTable v-if="endpoint.responseFields.length > 0" :bordered="true" :single-line="false" size="small" style="margin-bottom: 8px">
            <thead>
              <tr>
                <th style="min-width: 120px">
                  {{ t('extApi.docParamName') }}
                </th>
                <th style="min-width: 60px">
                  Type
                </th>
                <th>{{ t('extApi.docParamDesc') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in endpoint.responseFields" :key="f.name">
                <td>{{ f.name }}</td>
                <td>{{ f.type }}</td>
                <td v-html="f.desc" />
              </tr>
            </tbody>
          </NTable>
          <pre class="response-example"><code>{{ endpoint.responseExample }}</code></pre>
        </NCollapseItem>
      </NCollapse>
    </div>
  </section>
</template>

<style scoped>
.api-doc__heading,
.endpoint-heading,
.endpoint-address,
.code-panel__bar,
.request-headers > div {
  display: flex;
}

.api-doc__heading {
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.api-doc__heading h3,
.endpoint-heading h4 {
  margin: 0;
  color: var(--zhimesh-text);
  font-weight: 700;
}

.api-doc__heading h3 {
  margin-bottom: 4px;
  font-size: 15px;
}

.api-doc__heading p,
.endpoint-heading p {
  margin: 0;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.api-doc__key-state {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  border-radius: 999px;
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
  font-size: 11px;
}

.api-doc__key-state i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9aa4ad;
}

.api-doc__key-state.is-ready {
  color: #19724a;
  background: rgb(47 186 109 / 10%);
}

.api-doc__key-state.is-ready i {
  background: #2fba6d;
}

.endpoint-block + .endpoint-block {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--zhimesh-border-subtle);
}

.endpoint-heading {
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.endpoint-heading h4 {
  margin-bottom: 3px;
  font-size: 14px;
}

.endpoint-address {
  min-width: 0;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 9px 10px;
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
}

.endpoint-address > span {
  flex: none;
  padding: 3px 7px;
  border-radius: 6px;
  color: #fff;
  background: var(--zhimesh-control-primary);
  font-size: 10px;
  font-weight: 750;
}

.endpoint-address code {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-panel {
  overflow: hidden;
  border-radius: 12px;
  background: #18212b;
  color: #dce7ef;
}

.code-panel__bar {
  min-height: 38px;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border-bottom: 1px solid rgb(255 255 255 / 9%);
  color: #aebdca;
  font-size: 11px;
  font-weight: 700;
}

.code-panel__bar :deep(.n-button) {
  color: #dce7ef;
}

.code-panel pre,
.response-example {
  margin: 0;
  overflow-x: auto;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
}

.code-panel pre {
  max-height: 310px;
  padding: 14px 16px;
}

.endpoint-details {
  margin-top: 10px;
}

.request-headers {
  display: grid;
  gap: 8px;
  margin-bottom: 12px;
}

.request-headers > div {
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.request-headers span {
  width: 112px;
  flex: none;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
}

.request-headers code {
  min-width: 0;
  overflow: hidden;
  color: var(--zhimesh-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.response-example {
  padding: 13px 15px;
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
  color: var(--zhimesh-text);
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 680px) {
  .api-doc__heading {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .api-doc__key-state {
    align-self: flex-start;
  }

  .endpoint-address {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .endpoint-address code {
    width: calc(100% - 60px);
    flex: auto;
    white-space: normal;
    word-break: break-all;
  }

  .endpoint-address :deep(.n-button) {
    margin-left: 50px;
  }

  .request-headers > div {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}
</style>
