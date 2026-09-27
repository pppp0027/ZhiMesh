<script setup lang='ts'>
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NCheckboxGroup, NFlex, NList, NListItem, NModal, NPopover, NUpload, useMessage } from 'naive-ui'
import type { UploadFileInfo } from 'naive-ui'
import ConvKnowledgeSelector from './ConvKnowledgeSelector.vue'
import { LLMSelector, SvgIcon } from '@/components/common'
import { useAppStore, useAuthStore, useChatStore, useMcpStore } from '@/store'
import { getDefaultCharacter } from '@/store/modules/chat/helper'
import { router } from '@/router'
import { t } from '@/locales'
import api from '@/api'
const emit = defineEmits<Emit>()
const allowedImageTypes = ['image/png', 'image/jpeg']
interface Emit {
  (e: 'imagesChange', imageUuids: string[]): void
}
const appStore = useAppStore()
const authStore = useAuthStore()
const chatStore = useChatStore()
const mcpStore = useMcpStore()
const token = computed(() => authStore.token)
const ms = useMessage()
const uploadedFileInfoList = ref<UploadFileInfo[]>([])
const uploadedUuidList = ref<string[]>([])
const currCharacter = computed(() => chatStore.getCurCharacter || getDefaultCharacter())
const canUploadImage = ref<boolean>(false)
const savingMcps = ref(false)
const mcpModalShow = ref<boolean>(false)
const knowledgeModalShow = ref<boolean>(false)
const tmpMcpIds = ref<string[]>([])
const tmpCharacterKbs = ref<Chat.CharacterKnowledge[]>([])
const tmpCharacterKbIds = ref<string[]>([])
const selectedModelLabel = computed(() => appStore.selectedLLM.modelTitle || appStore.selectedLLM.modelName || t('draw.noModel'))
const knowledgeSummary = computed(() => currCharacter.value.characterKnowledgeList.map(item => item.title).join('、') || t('common.none'))
const toolSummary = computed(() => mcpStore.myUserMcpList
  .filter(item => currCharacter.value.mcpIds.includes(item.mcpInfo.id))
  .map(item => item.mcpInfo.title)
  .join('、') || t('common.none'))

async function beforeUpload(data: { file: UploadFileInfo; fileList: UploadFileInfo[] }) {
  const file = data.file.file
  if (!file) {
    ms.error(t('chat.fileNotExist'))
    return false
  }
  if (allowedImageTypes.findIndex(item => item === file.type) === -1) {
    ms.error(t('chat.imageFormatError'))
    return false
  }
  if (file.size > 4 * 1024 * 1024) {
    ms.error(t('chat.fileSizeExceed'))
    return false
  }
  return true
}

function handleFinish({ file, event }: { file: UploadFileInfo; event?: ProgressEvent }) {
  let res: any
  try {
    const responseText = (event?.target as XMLHttpRequest | undefined)?.response
    res = typeof responseText === 'string' ? JSON.parse(responseText) : responseText
  } catch (error) {
    console.error('image upload response parse failed', error)
    ms.error(t('common.uploadFailed'))
    return
  }
  if (!res) {
    ms.error(t('common.uploadFailed'))
    return
  }
  if (res.success) {
    uploadedUuidList.value.push(res.data.uuid)
    uploadedFileInfoList.value.push(file)
  } else {
  }
  emit('imagesChange', [...uploadedUuidList.value])
}

async function handlerRemove({ file }: { file: UploadFileInfo }) {
  const itemIndex = uploadedFileInfoList.value.findIndex(item => item.id === file.id)
  if (itemIndex < 0)
    return
  const removeUuid = uploadedUuidList.value[itemIndex]
  if (removeUuid) {
    try {
      await api.fileDel(removeUuid)
    } catch (error) {
      console.error('delete uploaded image failed', error)
      ms.error(t('common.wrong'))
      return
    }
    uploadedUuidList.value.splice(itemIndex, 1)
    uploadedFileInfoList.value.splice(itemIndex, 1)
  }
  emit('imagesChange', [...uploadedUuidList.value])
}

// DeepSeek 深度思考与工具/联网的互斥 workaround 已移除：深度思考改由后端按模型能力自动判定，
// 前端不再提供开关（保留注释说明历史行为，防止误恢复旧 watch 链）
function handleMcpModalShow() {
  mcpModalShow.value = true
  tmpMcpIds.value = [...currCharacter.value.mcpIds]
}

function handleKnowledgeModalShow() {
  knowledgeModalShow.value = true
  tmpCharacterKbs.value = [...currCharacter.value.characterKnowledgeList]
  tmpCharacterKbIds.value = currCharacter.value.characterKnowledgeList.map(kb => kb.id)
}

function handleKnowledgeSave() {
  knowledgeModalShow.value = false
}

async function handleSaveMcps() {
  if (savingMcps.value)
    return
  const previousMcpIds = [...currCharacter.value.mcpIds]
  const nextMcpIds = [...tmpMcpIds.value]
  savingMcps.value = true
  try {
    await api.characterEdit(currCharacter.value.uuid, { mcpIds: nextMcpIds })
    currCharacter.value.mcpIds = nextMcpIds
    chatStore.updateCharacter(currCharacter.value.uuid, currCharacter.value)
    mcpModalShow.value = false
  } catch (error) {
    console.error('handleSaveMcps error', error)
    currCharacter.value.mcpIds = previousMcpIds
    ms.error(t('chat.operationFailed'))
  } finally {
    savingMcps.value = false
  }
}

function gotoMcp() {
  router.push({ name: 'Mcp' })
  mcpModalShow.value = false
}

async function toogleWebSearch() {
  if (!appStore.selectedLLM.isSupportWebSearch) {
    return
  }
  const previousValue = currCharacter.value.isEnableWebSearch
  const nextValue = !previousValue
  try {
    await api.characterEdit(currCharacter.value.uuid, { isEnableWebSearch: nextValue })
    currCharacter.value.isEnableWebSearch = nextValue
  } catch (err) {
    console.error('toogleWebSearch error', err)
    ms.error(`${t('chat.operationFailed')}${err}`, { duration: 2000 })
    return
  }
  if (nextValue)
    ms.success(t('chat.webSearchEnabled'))
  else
    ms.warning(t('chat.webSearchDisabled'))
}

watch(
  () => appStore.selectedLLM,
  async (newVal) => {
    if (newVal.inputTypes?.includes('image'))
      canUploadImage.value = true
    else
      canUploadImage.value = false
    if (!newVal.isSupportWebSearch && currCharacter.value.isEnableWebSearch) {
      currCharacter.value.isEnableWebSearch = false
      try {
        await api.characterEdit(currCharacter.value.uuid, { isEnableWebSearch: false })
        ms.warning(t('chat.webSearchAutoDisabledUnsupported'))
      } catch (error) {
        currCharacter.value.isEnableWebSearch = true
        console.error('auto disable unsupported web search error', error)
        ms.error(t('chat.operationFailed'))
      }
    }
  },
  {
    immediate: true,
  },
)
</script>

<template>
  <div class="input-tool-bar">
    <div class="toolbar-row">
      <div class="model-control" :title="selectedModelLabel">
        <LLMSelector name-only />
      </div>

      <NPopover v-if="appStore.selectedLLM.isSupportWebSearch" trigger="hover">
        <template #trigger>
          <button
            type="button" class="capability-pill"
            :class="{ active: currCharacter.isEnableWebSearch }"
            :aria-pressed="currCharacter.isEnableWebSearch"
            :aria-label="t('chat.webSearch')"
            @click="toogleWebSearch"
          >
            <SvgIcon icon="ri:global-line" />
            <span>{{ t('chat.webSearch') }}</span>
            <span class="capability-state">{{ currCharacter.isEnableWebSearch ? t('common.enable') : t('common.disable') }}</span>
          </button>
        </template>
        <span>{{ t('chat.webSearch') }}</span>
      </NPopover>

      <NUpload
        v-if="canUploadImage"
        class="image-upload-control" :action="`/api/image/upload?token=${token}`" response-type="text"
        @before-upload="beforeUpload" @finish="handleFinish"
      >
        <NPopover trigger="hover">
          <template #trigger>
            <button type="button" class="capability-pill" :aria-label="t('chat.uploadImage')">
              <SvgIcon icon="ri:image-add-line" />
              <span>{{ t('chat.uploadImage') }}</span>
            </button>
          </template>
          <span>{{ t('chat.uploadImageTip') }}</span>
        </NPopover>
      </NUpload>

      <button
        type="button"
        class="menu-pill resource-pill"
        :class="{ active: currCharacter.characterKnowledgeList.length > 0 }"
        :aria-label="`${t('chat.knowledgeBase')}：${knowledgeSummary}`"
        :title="knowledgeSummary"
        @click="handleKnowledgeModalShow"
      >
        <SvgIcon icon="ri:book-2-line" />
        <span>{{ t('chat.knowledgeBase') }}</span>
        <span class="resource-pill-count">{{ currCharacter.characterKnowledgeList.length }}</span>
      </button>

      <button
        type="button"
        class="menu-pill resource-pill"
        :class="{ active: currCharacter.mcpIds.length > 0 }"
        :aria-label="`${t('chat.tools')}：${toolSummary}`"
        :title="toolSummary"
        @click="handleMcpModalShow"
      >
        <SvgIcon icon="ri:tools-line" />
        <span>{{ t('chat.tools') }}</span>
        <span class="resource-pill-count">{{ currCharacter.mcpIds.length }}</span>
      </button>

    </div>
    <NList hoverable show-divider>
      <NListItem v-for="fileInfo in uploadedFileInfoList" :key="fileInfo.id">
        <div class="flex">
          <span class="flex-1 text-xs">{{ fileInfo.name }}</span>
          <SvgIcon
            class="flex-none cursor-pointer text-sm" icon="clarity:remove-line"
            @click="handlerRemove({ file: fileInfo })"
          />
        </div>
      </NListItem>
    </NList>
    <NModal
      v-model:show="knowledgeModalShow" display-directive="show" style="width: min(860px, 92vw);"
      preset="card" :title="t('chat.configCharacterKnowledge')"
    >
      <ConvKnowledgeSelector :tmp-save="false" :character="currCharacter" @submitted="handleKnowledgeSave" />
    </NModal>
    <NModal v-model:show="mcpModalShow" style="width: min(640px, 92vw);" preset="card" :title="t('chat.configMcp')">
      <NCheckboxGroup v-model:value="tmpMcpIds" class="my-2 flex flex-wrap space-x-2">
        <NCheckbox
          v-for="userMcp in mcpStore.myUserMcpList" :key="userMcp.uuid" :value="userMcp.mcpInfo.id"
          :label="userMcp.mcpInfo.title"
        />
      </NCheckboxGroup>
      <span v-if="mcpStore.myUserMcpList.length === 0" class="mr-1">{{ t('common.noData') }}</span>
      <NFlex justify="space-between" class="mt-4">
        <NButton type="primary" text tag="a" @click="gotoMcp">
          {{ t('chat.goEnableMoreTools') }}
        </NButton>
        <NButton type="primary" :loading="savingMcps" :disabled="savingMcps" @click="handleSaveMcps()">
          {{ t('common.save') }}
        </NButton>
      </NFlex>
    </NModal>
  </div>
</template>

<style scoped lang="less">
.toolbar-row {
  display: flex;
  width: 100%;
  min-width: 0;
  flex-wrap: nowrap;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  padding: 1px;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
}

.toolbar-row::-webkit-scrollbar {
  display: none;
}

.model-control {
  box-sizing: border-box;
  display: flex;
  min-width: max-content;
  flex: none;
  height: 34px;
  min-height: 34px;
  max-height: 34px;
  align-self: center;
  align-items: center;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
}

.model-control :deep(.n-button) {
  width: auto;
  min-width: max-content;
  height: 32px;
  min-height: 32px !important;
  padding: 0 10px;
  border: 0;
  background: transparent;
  box-shadow: none;
  font-size: 11px;
}

.model-control :deep(.n-button__content) {
  min-width: 0;
  overflow: hidden;
}

.model-control :deep(.model-selector-label) {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-pill,
.menu-pill {
  box-sizing: border-box;
  height: 34px;
  min-height: 34px;
  max-height: 34px;
  align-self: center;
  flex: none;
  border: 1px solid var(--zhimesh-border-subtle);
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass);
  font: inherit;
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease, background 0.2s ease, transform 0.2s ease;
}

.capability-pill {
  display: flex;
  padding: 0 10px;
  align-items: center;
  gap: 5px;
  border-radius: 10px;
  font-size: 11px;
  white-space: nowrap;
}

.capability-pill:hover,
.menu-pill:hover {
  border-color: var(--zhimesh-primary);
  transform: translateY(-1px);
}

.capability-pill.active {
  border-color: var(--zhimesh-primary);
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.capability-state,
.resource-pill-count {
  box-sizing: border-box;
  height: 16px;
  border-radius: 999px;
  padding: 1px 5px;
  color: var(--zhimesh-text-muted);
  background: var(--zhimesh-glass-soft);
  font-size: 9px;
  font-weight: 650;
  line-height: 16px;
}

.capability-pill.active .capability-state {
  color: var(--zhimesh-primary);
  background: color-mix(in srgb, var(--zhimesh-primary) 10%, transparent);
}

.capability-pill:disabled {
  cursor: wait;
  opacity: 0.65;
  transform: none;
}

.menu-pill {
  display: inline-flex;
  padding: 0 9px;
  align-items: center;
  gap: 5px;
  border-radius: 10px;
  font-size: 11px;
  white-space: nowrap;
}

.resource-pill {
  flex: none;
  gap: 6px;
}

.resource-pill.active {
  border-color: var(--zhimesh-primary);
  color: var(--zhimesh-primary);
  background: var(--zhimesh-glass-soft);
}

.resource-pill-count {
  box-sizing: border-box;
  height: 16px;
  min-width: 18px;
  padding-inline: 4px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
  text-align: center;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.image-upload-control {
  flex: none;
}

.image-upload-control :deep(.n-upload-trigger) {
  display: block;
}

:global(.dark) .capability-pill,
:global(.dark) .menu-pill {
  background: rgba(30, 41, 59, 0.34);
}

@media (max-width: 767px) {
  .toolbar-row {
    gap: 4px;
    overflow: hidden;
  }

  .model-control,
  .capability-pill,
  .menu-pill {
    height: 38px;
    min-height: 38px;
    max-height: 38px;
  }

  .model-control :deep(.n-button) {
    width: 100%;
    min-width: 0;
    height: 36px;
    min-height: 36px !important;
  }

  .model-control {
    width: clamp(86px, 28vw, 110px);
    min-width: clamp(86px, 28vw, 110px);
    max-width: clamp(86px, 28vw, 110px);
    flex: 0 0 clamp(86px, 28vw, 110px);
  }

  .model-control :deep(.n-button__content) {
    min-width: 0;
    overflow: hidden;
  }

  .model-control :deep(.n-button) {
    padding-inline: 8px;
    font-size: 11px;
  }

  .capability-pill,
  .menu-pill {
    min-width: 0;
    padding-inline: 5px;
    gap: 3px;
    font-size: 10px;
  }

  .capability-pill > span:not(.capability-state),
  .menu-pill > span:not(.resource-pill-count) {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .capability-state {
    display: none;
  }

  .resource-pill-count {
    box-sizing: border-box;
    height: 16px;
    min-width: 14px;
    padding-inline: 3px;
    font-size: 9px;
  }
}

@media (max-width: 360px) {
  .model-control {
    width: 84px;
    min-width: 84px;
    max-width: 84px;
    flex-basis: 84px;
  }

  .model-control :deep(.n-button) {
    padding-inline: 6px;
  }

  .capability-pill,
  .menu-pill {
    padding-inline: 4px;
  }
}

.input-tool-bar .n-upload-file-list {
  display: none
}
</style>
