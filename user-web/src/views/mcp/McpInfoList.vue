<script setup lang='ts'>
import { onMounted, ref } from 'vue'
import { NButton, NPagination, useLoadingBar, useMessage } from 'naive-ui'
import { useMcpStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
import { debounce } from '@/utils/functions/debounce'
import { SvgIcon } from '@/components/common'

const emit = defineEmits<Emit>()
const ms = useMessage()
const loaddingBar = useLoadingBar()
const mcpStore = useMcpStore()
const mcpInfoList = ref<Mcp.McpInfo[]>([])
const currentPage = ref<number>(1)
const totalPage = ref<number>(0)
const pageSize = 21

function cleanDescription(markdown = '') {
  return markdown
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[`*_>#~]/g, '')
    .replace(/(^|\s)-\s/g, '$1')
    .replace(/\s+/g, ' ')
    .trim()
}

function transportLabel(transportType = '') {
  return transportType.replace('_', ' ').toUpperCase() || 'MCP'
}

interface Emit {
  (ev: 'showInfoModal', mcpInfo: Mcp.McpInfo): void
  (ev: 'showConfigModal', mcpInfo: Mcp.McpInfo): void
}
/**
 * 加载公开列表
 */
async function loadMcpPage(page: number) {
  if (mcpStore.loading)
    return
  console.log('loadMcpPage', page)
  loaddingBar.start()
  mcpStore.setLoading(true)
  try {
    if (page > 1000) {
      ms.warning(t('mcp.maxPageLimit'), {
        duration: 3000,
      })
      return
    }
    const { data } = await api.mcpSearch<Mcp.McpInfoListResp>('', page, pageSize)
    data.records.forEach((mcp) => {
      const userMcp = mcpStore.myUserMcpList.find(userMcp => userMcp.mcpId === mcp.id)
      if (userMcp) {
        mcp.configured = true
        mcp.customizedParamDefinitions.forEach((uninitParam) => {
          const paramSetting = userMcp.mcpCustomizedParams.find(varItem => varItem.name === uninitParam.name)
          // 将已设置好的参数赋值给mcp的customizedParamDefinition
          if (paramSetting)
            uninitParam.value = paramSetting.value
          else
            uninitParam.value = ''
        })
      } else {
        mcp.configured = false
      }
    })
    mcpInfoList.value = data.records
    totalPage.value = data.pages
  } catch (error) {
    console.error(error)
  } finally {
    mcpStore.setLoading(false)
    loaddingBar.finish()
  }
}

function onShowInfoModal(mcpInfo: Mcp.McpInfo) {
  emit('showInfoModal', mcpInfo)
}

function onShowConfigModal(mcpInfo: Mcp.McpInfo) {
  emit('showConfigModal', mcpInfo)
}

const handleLoadNext = debounce(loadMcpPage, 300)
onMounted(() => {
  if (mcpInfoList.value.length === 0)
    handleLoadNext(currentPage.value)
})
</script>

<template>
  <div class="flex flex-col w-full h-full pb-3">
    <div v-if="mcpInfoList.length === 0 && !mcpStore.loading" class="mcp-empty-state">
      <span class="mcp-empty-icon"><SvgIcon icon="ri:tools-line" /></span>
      <strong>{{ t('mcp.emptyPublic') }}</strong>
    </div>
    <div v-else class="mcp-grid">
      <div
        v-for="mcpInfo in mcpInfoList" :key="mcpInfo.uuid"
        class="mcp-card"
      >
        <div class="mcp-card-header">
          <span class="mcp-card-icon"><SvgIcon icon="ri:command-line" /></span>
          <div class="mcp-card-title-wrap">
            <strong>{{ mcpInfo.title }}</strong>
            <span>{{ transportLabel(mcpInfo.transportType) }}</span>
          </div>
          <span v-if="mcpInfo.configured" class="mcp-status is-configured">{{ t('mcp.configured') }}</span>
        </div>
        <p class="mcp-card-description">
          {{ cleanDescription(mcpInfo.remark) || t('mcp.noDescription') }}
        </p>
        <div class="mcp-card-footer">
          <span class="mcp-transport">{{ t('mcp.transportLabel') }} · {{ transportLabel(mcpInfo.transportType) }}</span>
          <div class="mcp-card-actions">
            <NButton class="readable-accent-button" size="small" quaternary type="primary" @click="onShowInfoModal(mcpInfo)">
              {{ t('mcp.viewDetails') }}
            </NButton>
            <NButton size="small" type="primary" secondary @click="onShowConfigModal(mcpInfo)">
              {{ mcpInfo.configured ? t('mcp.configLabel') : t('mcp.useTool') }}
            </NButton>
          </div>
        </div>
      </div>
    </div>
    <div class="flex justify-end w-full pr-2">
      <NPagination
        v-show="totalPage > 1" v-model:page="currentPage" :page-size="pageSize" :page-count="totalPage"
        @update:page="loadMcpPage"
      />
    </div>
  </div>
</template>

<style scoped lang="less">
.mcp-grid {
  display: grid;
  width: 100%;
  padding: 16px;
  gap: 14px;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
}

.mcp-card {
  display: flex;
  min-height: 196px;
  padding: 17px;
  flex-direction: column;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: var(--zhimesh-radius-md);
  background: var(--zhimesh-glass-strong);
  box-shadow: var(--zhimesh-shadow-soft);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.mcp-card:hover {
  border-color: var(--zhimesh-primary);
  box-shadow: var(--zhimesh-shadow);
}

.mcp-card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.mcp-card-icon,
.mcp-empty-icon {
  display: grid;
  flex: none;
  place-items: center;
  border-radius: 12px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.mcp-card-icon {
  width: 38px;
  height: 38px;
  font-size: 19px;
}

.mcp-card-title-wrap {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.mcp-card-title-wrap strong {
  overflow: hidden;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mcp-card-title-wrap span,
.mcp-transport {
  color: var(--zhimesh-text-muted);
  font-size: 10px;
}

.mcp-status {
  padding: 4px 7px;
  border-radius: 999px;
  font-size: 10px;
  white-space: nowrap;
}

.mcp-status.is-configured {
  color: #15803d;
  background: rgba(34, 197, 94, 0.12);
}

.mcp-card-description {
  display: -webkit-box;
  min-height: 54px;
  margin: 18px 0 12px;
  overflow: hidden;
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.mcp-card-footer {
  display: flex;
  margin-top: auto;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}

.mcp-card-actions {
  display: flex;
  gap: 4px;
}

.mcp-empty-state {
  display: flex;
  min-height: 300px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  color: var(--zhimesh-text-muted);
}

.mcp-empty-icon {
  width: 52px;
  height: 52px;
  font-size: 24px;
}

:global(.dark) .mcp-card {
  background: rgba(30, 41, 59, 0.34);
}

@media (max-width: 767px) {
  .mcp-grid {
    padding: 10px;
    gap: 10px;
    grid-template-columns: 1fr;
  }

  .mcp-card {
    min-height: 0;
    padding: 15px;
    border-radius: 14px;
    box-shadow: none;
  }

  .mcp-card-description {
    min-height: 0;
    margin: 14px 0 12px;
    font-size: 13px;
    -webkit-line-clamp: 2;
  }

  .mcp-card-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .mcp-card-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .mcp-card-actions :deep(.n-button) {
    min-height: 44px;
  }

  .mcp-empty-state {
    min-height: 220px;
  }
}
</style>
