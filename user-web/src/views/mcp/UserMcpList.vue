<script setup lang='ts'>
import { NButton } from 'naive-ui'
import { useAuthStore, useMcpStore } from '@/store'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import LoginTip from '@/views/user/LoginTip.vue'
import { t } from '@/locales'
import { SvgIcon } from '@/components/common'

const emit = defineEmits<Emit>()
const authStore = useAuthStore()
const mcpStore = useMcpStore()
const { isMobile } = useBasicLayout()
interface Emit {
  (ev: 'showInfoModal', mcpInfo: Mcp.McpInfo): void
  (ev: 'showConfigModal', mcpInfo: Mcp.McpInfo): void
}

function onShowInfoModal(mcpInfo: Mcp.McpInfo) {
  emit('showInfoModal', mcpInfo)
}

function onShowConfigModal(mcpInfo: Mcp.McpInfo) {
  emit('showConfigModal', mcpInfo)
}

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
</script>

<template>
  <div class="flex flex-col w-full h-full pb-3">
    <div class="flex flex-wrap justify-start items-start overflow-y-auto">
      <div v-if="!authStore.token" class="w-full max-w-screen-xl m-auto" :class="[isMobile ? 'p-2' : 'p-4']">
        <LoginTip />
      </div>
      <div
        v-if="authStore.token && mcpStore.myUserMcpList.length === 0"
        class="mcp-empty-state"
      >
        <span class="mcp-empty-icon"><SvgIcon icon="ri:tools-line" /></span>
        <strong>{{ t('mcp.emptyMine') }}</strong>
        <span>{{ t('mcp.emptyMineHint') }}</span>
      </div>
      <div v-if="authStore.token && mcpStore.myUserMcpList.length > 0" class="mcp-grid">
        <div
          v-for="userMcp in mcpStore.myUserMcpList" :key="userMcp.uuid"
          class="mcp-card"
        >
          <div class="mcp-card-header">
            <span class="mcp-card-icon"><SvgIcon icon="ri:tools-line" /></span>
            <div class="mcp-card-title-wrap">
              <strong>{{ userMcp.mcpInfo.title }}</strong>
              <span>{{ transportLabel(userMcp.mcpInfo.transportType) }}</span>
            </div>
            <span class="mcp-status" :class="userMcp.isEnable ? 'is-enabled' : 'is-disabled'">
              {{ userMcp.isEnable ? t('mcp.enabledLabel') : t('mcp.disabledLabel') }}
            </span>
          </div>
          <p class="mcp-card-description">
            {{ cleanDescription(userMcp.mcpInfo.remark) || t('mcp.noDescription') }}
          </p>
          <div class="mcp-card-footer">
            <span class="mcp-transport">{{ t('mcp.transportLabel') }} · {{ transportLabel(userMcp.mcpInfo.transportType) }}</span>
            <div class="mcp-card-actions">
              <NButton class="readable-accent-button" size="small" quaternary type="primary" @click="onShowInfoModal(userMcp.mcpInfo)">
                {{ t('mcp.viewDetails') }}
              </NButton>
              <NButton size="small" type="primary" secondary @click="onShowConfigModal(userMcp.mcpInfo)">
                {{ t('mcp.configLabel') }}
              </NButton>
            </div>
          </div>
        </div>
      </div>
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

.mcp-card-header,
.mcp-card-footer,
.mcp-card-actions {
  display: flex;
  align-items: center;
}

.mcp-card-header {
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
.mcp-transport,
.mcp-empty-state > span:last-child {
  color: var(--zhimesh-text-muted);
  font-size: 10px;
}

.mcp-status {
  padding: 4px 7px;
  border-radius: 999px;
  font-size: 10px;
  white-space: nowrap;
}

.mcp-status.is-enabled {
  color: #15803d;
  background: rgba(34, 197, 94, 0.12);
}

.mcp-status.is-disabled {
  color: #a16207;
  background: rgba(234, 179, 8, 0.13);
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
  margin-top: auto;
  justify-content: space-between;
  gap: 8px;
}

.mcp-card-actions {
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
  text-align: center;
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
    padding: 24px;
  }
}
</style>
