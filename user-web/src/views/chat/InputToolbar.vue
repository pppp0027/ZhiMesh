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
const isReasoner = ref<boolean>(false)
const isThinkingClosable = ref<boolean>(false)
const contextUpdating = ref(false)
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
    console.log(`image uuid:${res.data.uuid}`)
  } else {
    console.log(`handleOriginalFinish err:${res.data}`)
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

// DeepSeek 深度思考模式与工具调用不兼容（langchain4j #3461: partialArguments cannot be null）
// TODO: 升级 langchain4j 后移除此 workaround，恢复工具调用支持
const isDeepSeekThinking = computed(() => {
  const modelName = appStore.selectedLLM?.modelName?.toLowerCase() || ''
  return currCharacter.value.isEnableThinking && isReasoner.value && modelName.includes('deepseek')
})

function handleMcpModalShow() {
  if (isDeepSeekThinking.value) {
    ms.warning(t('chat.deepThinkingIncompatibleWithTool'))
    return
  }
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
  const previousMcpIds = [...currCharacter.value.mcpIds]
  const nextMcpIds = [...tmpMcpIds.value]
  try {
    await api.characterEdit(currCharacter.value.uuid, { mcpIds: nextMcpIds })
    currCharacter.value.mcpIds = nextMcpIds
    chatStore.updateCharacter(currCharacter.value.uuid, currCharacter.value)
    mcpModalShow.value = false
  } catch (error) {
    console.error('handleSaveMcps error', error)
    currCharacter.value.mcpIds = previousMcpIds
    ms.error(t('chat.operationFailed'))
  }
}

function gotoMcp() {
  router.push({ name: 'Mcp' })
  mcpModalShow.value = false
}

async function toggleUsingContext() {
  if (contextUpdating.value)
    return
  const previousValue = currCharacter.value.understandContextEnable
  const nextValue = !previousValue
  currCharacter.value.understandContextEnable = nextValue
  contextUpdating.value = true
  try {
    await api.characterToggleUsingContext(currCharacter.value.uuid, nextValue)
  } catch (error) {
    currCharacter.value.understandContextEnable = previousValue
    console.error('toggle context failed', error)
    ms.error(t('common.wrong'))
    return
  } finally {
    contextUpdating.value = false
  }
  if (nextValue)
    ms.success(t('chat.turnOnContext'))
  else
    ms.warning(t('chat.turnOffContext'))
}

async function toogleThinking() {
  if (!isReasoner.value || !isThinkingClosable.value) {
    console.log('该模型不支持对深度思考功能的开启或关闭')
    return
  }
  const previousValue = currCharacter.value.isEnableThinking
  const nextValue = !previousValue
  try {
    await api.characterToggleThinking(currCharacter.value.uuid, nextValue)
    currCharacter.value.isEnableThinking = nextValue
    if (nextValue)
      ms.success(t('chat.deepThinkingEnabled'))
    else
      ms.warning(t('chat.deepThinkingDisabled'))
  } catch (error) {
    currCharacter.value.isEnableThinking = previousValue
    console.error('toggle thinking failed', error)
    ms.error(t('chat.operationFailed'))
  }
}

async function toogleWebSearch() {
  if (!appStore.selectedLLM.isSupportWebSearch) {
    console.log('该模型不支持联网搜索功能的开启或关闭')
    return
  }
  if (isDeepSeekThinking.value) {
    ms.warning(t('chat.deepThinkingIncompatibleWithWebSearch'))
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
    isReasoner.value = newVal.isReasoner
    isThinkingClosable.value = newVal.isThinkingClosable
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

watch(isDeepSeekThinking, async (newVal) => {
  if (newVal) {
    if (currCharacter.value.mcpIds.length > 0) {
      try {
        await api.characterEdit(currCharacter.value.uuid, { mcpIds: [] })
        currCharacter.value.mcpIds = []
        ms.warning(t('chat.deepThinkingAutoCloseTool'))
      } catch (error) {
        console.error('auto disable tools error', error)
        currCharacter.value.isEnableThinking = false
        ms.error(t('chat.operationFailed'))
      }
    }
    if (currCharacter.value.isEnableWebSearch) {
      currCharacter.value.isEnableWebSearch = false
      try {
        await api.characterEdit(currCharacter.value.uuid, { isEnableWebSearch: false })
      } catch (err) {
        console.error('auto disable webSearch error', err)
      }
      ms.warning(t('chat.deepThinkingAutoCloseWebSearch'))
    }
  }
}, { immediate: true })
</script>

<template>
  <div class="input-tool-bar">
    <div class="toolbar-row">
      <div class="model-control" :title="selectedModelLabel">
        <LLMSelector name-only />
      </div>

      <NPopover v-if="isReasoner && isThinkingClosable" trigger="hover">
        <template #trigger>
          <button
            type="button" class="capability-pill"
            :class="{ active: currCharacter.isEnableThinking }"
            :aria-pressed="currCharacter.isEnableThinking"
            :aria-label="t('chat.deepThinking')"
            @click="toogleThinking"
          >
            <SvgIcon icon="ri:brain-line" />
            <span>{{ t('chat.deepThinking') }}</span>
            <span class="capability-state">{{ currCharacter.isEnableThinking ? t('common.enable') : t('common.disable') }}</span>
          </button>
        </template>
        <span>{{ t('chat.deepThinking') }}</span>
      </NPopover>

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
        type="button" class="capability-pill context-pill"
        :class="{ active: currCharacter.understandContextEnable }"
        :aria-pressed="currCharacter.understandContextEnable"
        :aria-busy="contextUpdating"
        :disabled="contextUpdating"
        :aria-label="`${t('chat.continuousConversation')}，${currCharacter.understandContextEnable ? t('chat.contextEnabledStatus') : t('chat.contextDisabledStatus')}`"
        :title="currCharacter.understandContextEnable ? t('chat.contextEnabledStatus') : t('chat.contextDisabledStatus')"
        @click="toggleUsingContext"
      >
        <SvgIcon icon="ri:chat-history-line" />
        <span>{{ t('chat.continuousConversation') }}</span>
        <i class="context-status-light" aria-hidden="true" />
      </button>

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
      v-model:show="knowledgeModalShow" display-directive="show" style="width: 90%; max-width: 800px"
      preset="card" :title="t('chat.configCharacterKnowledge')"
    >
      <ConvKnowledgeSelector :tmp-save="false" :character="currCharacter" @submitted="handleKnowledgeSave" />
    </NModal>
    <NModal v-model:show="mcpModalShow" style="width: 90%; max-width: 640px" preset="card" :title="t('chat.configMcp')">
      <NCheckboxGroup v-model:value="tmpMcpIds" class="my-2 flex flex-wrap space-x-2">
        <NCheckbox
          v-for="userMcp in mcpStore.myUserMcpList" :key="userMcp.uuid" :value="userMcp.mcpInfo.id"
          :label="userMcp.mcpInfo.title"
        />
      </NCheckboxGroup>
      <span v-if="mcpStore.myUserMcpList.length === 0" class="mr-1">{{ t('common.noData') }}</span>
      <NFlex justify="space-between" class="mt-4">
        <NButton type="primary" text tag="a" class="mt-4" @click="gotoMcp">
          {{ t('chat.goEnableMoreTools') }}
        </NButton>
        <NButton type="primary" @click="handleSaveMcps()">
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

.context-pill.active {
  border-color: var(--zhimesh-border-subtle);
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass);
}

.context-status-light {
  display: block;
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.45), 0 1px 2px rgba(15, 23, 42, 0.22);
  transition: background 0.2s ease, box-shadow 0.2s ease;
}

.context-pill.active .context-status-light {
  background: #22c55e;
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.55), 0 1px 5px rgba(34, 197, 94, 0.58);
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

:global(.dark) .context-pill.active {
  color: var(--zhimesh-text);
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

  .context-status-light {
    width: 6px;
    height: 6px;
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
