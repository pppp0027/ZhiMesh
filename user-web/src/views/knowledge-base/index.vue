<script setup lang='ts'>
import type { Ref } from 'vue'
import { computed, inject, nextTick, onActivated, onDeactivated, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCollapse, NCollapseItem, NDrawer, NDrawerContent, NInput, NModal, NSpin, useDialog, useLoadingBar, useMessage } from 'naive-ui'
import { Message } from '../chat/components'
import { useScroll } from '../chat/hooks/useScroll'
import HeaderComponent from './Header/index.vue'
import PCHeader from './Header/pc.vue'
import RefGraph from './RefGraph.vue'
import EvidenceMarkdown from '../chat/components/EvidenceMarkdown.vue'
import { useCopyCode } from '../chat/hooks/useCopyCode'
import LoginTip from '@/views/user/LoginTip.vue'
import { LLMSelector, SvgIcon } from '@/components/common'
import { useBasicLayout } from '@/hooks/useBasicLayout'
import { useAppStore, useAuthStore, useChatStore, useKbStore, useWfStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
import { debounce } from '@/utils/functions/debounce'
import { openDeleteDialog } from '@/utils/dialog'

let controller = new AbortController()
let qaRequestGeneration = 0
let activeQaRecord: KnowledgeBase.QaRecordInfo | null = null

const route = useRoute()
const router = useRouter()
const ms = useMessage()
const dialog = useDialog()
const appStore = useAppStore()
const kbStore = useKbStore()
const chatStore = useChatStore()
const wfStore = useWfStore()
const authStore = useAuthStore()
const loaddingBar = useLoadingBar()
const { isMobile } = useBasicLayout()
const { scrollRef, scrollToBottom, scrollToBottomIfAtBottom, scrollTo } = useScroll()
// 证据弹窗里的代码块渲染后绑定复制按钮
useCopyCode()
const { kbUuid: currKbUuid } = route.params as { kbUuid: string }
const showReferenceModal = ref<boolean>(false)
const showReferenceRecordUuid = ref<string>('')
const references = ref<KnowledgeBase.QaRecordEmbeddingRef[]>([])
const showRefGraphModal = ref<boolean>(false)
const showRefGraphRecordUuid = ref<string>('')
const prompt = ref<string>('')
const inputRef = ref<Ref | null>(null)
const mobileToolsOpen = ref(false)
const openPromptStore = inject<() => void>('openPromptStore', () => {})

const loadedAll = ref<boolean>(false)
const sseRequesting = ref<boolean>(false)
const pageSize = 20
let currentPage = 1
let prevScrollTop: number

async function handleSubmit() {
  if (!authStore.checkLoginOrShow())
    return

  const message = prompt.value

  if (!message || message.trim() === '')
    return

  if (sseRequesting.value)
    return

  sseRequesting.value = true
  const requestId = ++qaRequestGeneration
  controller = new AbortController()

  prompt.value = ''

  let qaRecordForError: KnowledgeBase.QaRecordInfo | null = null
  try {
    const { data: qaRecord } = await api.knowledgeBaseQaRecordAdd<KnowledgeBase.QaRecordInfo>(currKbUuid, { question: message, modelName: appStore.selectedLLM.modelName })
    qaRecordForError = qaRecord
    activeQaRecord = qaRecord
    // Keep the answer empty until the first chunk arrives so the shared
    // Message component renders the same analysing spinner as chat.
    qaRecord.answer = ''
    qaRecord.loading = true
    qaRecord.aiModelPlatform = appStore.selectedLLM.modelPlatform

    nextTick(() => {
      scrollToBottom()
    })

    kbStore.appendRecord(currKbUuid, qaRecord)

    await api.knowledgeBaseQaSseAsk({
      options: {
        qaRecordUuid: qaRecord.uuid,
      },
      signal: controller.signal,
      startCallback: () => {
        if (requestId !== qaRequestGeneration)
          return
        qaRecord.answer = ''
        kbStore.updateRecord(currKbUuid, qaRecord.uuid, qaRecord)
      },
      thinkingDataReceived: (chunk) => {
        // 思考数据暂不展示，仅保留回调占位
        void chunk
      },
      messageReceived: (chunk) => {
        if (requestId !== qaRequestGeneration)
          return
        try {
          kbStore.appendChunk(
            currKbUuid,
            qaRecord.uuid,
            chunk,
          )
        } catch (error) {
          console.error(error)
        }
        scrollToBottomIfAtBottom()
      },
      doneCallback: (chunk) => {
        if (requestId !== qaRequestGeneration)
          return
        if (chunk.includes('[META]')) {
          const meta = chunk.replace('[META]', '')
          let metaData: Chat.MetaData
          try {
            metaData = JSON.parse(meta)
          } catch (error) {
            console.error('Invalid knowledge-base SSE metadata', error)
            qaRecord.answer = `${t('common.systemTip')}${t('common.wrong')}`
            qaRecord.loading = false
            qaRecord.error = true
            kbStore.updateRecord(currKbUuid, qaRecord.uuid, qaRecord)
            sseRequesting.value = false
            activeQaRecord = null
            return
          }
          qaRecord.inputTokens = metaData.answer.inputTokens
          qaRecord.outputTokens = metaData.answer.outputTokens
          qaRecord.duration = metaData.answer.duration
          qaRecord.isRefEmbedding = metaData.answer.isRefEmbedding === true
          qaRecord.isRefGraph = metaData.answer.isRefGraph === true
        } else {
          kbStore.appendChunk(
            currKbUuid,
            qaRecord.uuid,
            chunk,
          )
        }
        qaRecord.loading = false
        qaRecord.error = false
        kbStore.updateRecord(currKbUuid, qaRecord.uuid, qaRecord)
        sseRequesting.value = false
        activeQaRecord = null
      },
      errorCallback: (error) => {
        if (requestId !== qaRequestGeneration)
          return
        sseRequesting.value = false
        ms.warning(`${t('common.systemTip')}${error}`)
        qaRecord.answer = `${t('common.systemTip')}${error}`
        qaRecord.loading = false
        qaRecord.error = true
        kbStore.updateRecord(currKbUuid, qaRecord.uuid, qaRecord)
        activeQaRecord = null
      },
    })
  } catch (error: any) {
    if (requestId !== qaRequestGeneration)
      return
    const errorMessage = error?.message ?? t('common.wrong')
    ms.error(errorMessage)
    if (qaRecordForError) {
      qaRecordForError.answer = errorMessage
      qaRecordForError.loading = false
      qaRecordForError.error = true
      kbStore.updateRecord(currKbUuid, qaRecordForError.uuid, qaRecordForError)
    } else {
      prompt.value = message
    }
    sseRequesting.value = false
    activeQaRecord = null
  }
}

async function loadMoreMessage(callback?: Function) {
  if (currKbUuid === 'default' || kbStore.loadingRecords.get(currKbUuid) || loadedAll.value)
    return

  loaddingBar.start()
  try {
    kbStore.setLoadingRecords(currKbUuid, true)

    const { data } = await api.knowledgeBaseQaRecordSearch<KnowledgeBase.QaRecordListResp>(currKbUuid, '', currentPage, pageSize)
    const records = data.records || []
    kbStore.appendRecords(currKbUuid, records)

    if (records.length < pageSize) {
      loadedAll.value = true
      ms.warning(t('common.noMore'), {
        duration: 3000,
      })
    }
    currentPage++
  } catch (error) {
    console.error(`loadMoreMessage${error}`)
  } finally {
    kbStore.setLoadingRecords(currKbUuid, false)
    loaddingBar.finish()

    if (callback)
      callback()
  }
}
const handleLoadMoreMessage = debounce(loadMoreMessage, 300)
async function handleScroll(event: any) {
  const scrollTop = event.target.scrollTop
  const lastScrollClient = event.target.scrollHeight
  if (scrollTop < 50 && (scrollTop < prevScrollTop || prevScrollTop === undefined)) {
    handleLoadMoreMessage(() => {
      nextTick(() => {
        scrollTo(event.target.scrollHeight - lastScrollClient)
      })
    })
  }
  prevScrollTop = scrollTop
}

function handleDelete(qaRecordUuid: string) {
  if (kbStore.loadingRecords.get(currKbUuid))
    return
  openDeleteDialog(dialog, {
    title: t('chat.deleteMessage'),
    content: t('knowledgeBase.questionAndAnswer'),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      try {
        await api.knowledgeBaseQaRecordDel(qaRecordUuid)
        kbStore.deleteRecord(currKbUuid, qaRecordUuid)
      } catch (error: any) {
        ms.error(error?.message || t('common.wrong'))
        return false
      }
    },
  })
}

function handleEnter(event: KeyboardEvent) {
  if (!isMobile.value) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      handleSubmit()
    }
  } else {
    if (event.key === 'Enter' && event.ctrlKey) {
      event.preventDefault()
      handleSubmit()
    }
  }
}

function handleStop() {
  if (sseRequesting.value) {
    qaRequestGeneration++
    controller.abort()
  }

  if (activeQaRecord?.loading) {
    if (!activeQaRecord.answer || activeQaRecord.answer === t('common.generating'))
      activeQaRecord.answer = `${t('common.systemTip')}${t('common.cancel')}`
    activeQaRecord.loading = false
    activeQaRecord.error = false
    kbStore.updateRecord(currKbUuid, activeQaRecord.uuid, activeQaRecord)
  }
  activeQaRecord = null
  sseRequesting.value = false
}

// 打开引用
async function handleReferenceClick(qaRecordUuid: string) {
  showReferenceModal.value = true
  showReferenceRecordUuid.value = qaRecordUuid
  references.value = []
  references.value = kbStore.getReferences(qaRecordUuid)
  if (references.value.length === 0) {
    try {
      const { data } = await api.knowledgeBaseEmbeddingRef(qaRecordUuid)
      kbStore.setQaRecordReferences(qaRecordUuid, data)

      // 显示最后一次点击的引用
      if (showReferenceRecordUuid.value === qaRecordUuid)
        references.value = kbStore.getReferences(qaRecordUuid)
    } catch (error) {
      console.error('load knowledge references failed', error)
      if (showReferenceRecordUuid.value === qaRecordUuid)
        ms.error(t('common.wrong'))
    }
  }
}

async function handleGraphClick(qaRecordUuid: string) {
  showRefGraphModal.value = true
  showRefGraphRecordUuid.value = qaRecordUuid
}

const qaRecords = computed(() => {
  return kbStore.getRecords(currKbUuid)
})

// 全角空格(U+3000)前缀：让占位提示右移一个字宽，与输入光标起点对齐（同 chat/InputEditor）
const placeholderPrefix = String.fromCharCode(0x3000)

const buttonDisabled = computed(() => {
  return sseRequesting.value || !prompt.value || prompt.value.trim() === ''
})

const hasPrompt = computed(() => prompt.value.trim().length > 0)
const loadingCurrRecords = computed(() => !!kbStore.loadingRecords.get(currKbUuid))
// 登录但三组分区均无库：路由停留在 'default' 占位，给出新建引导并禁用输入
const noKbAvailable = computed(() => !!authStore.token && currKbUuid === 'default' && kbStore.kbListLoaded && !kbStore.loaddingKbList)
const composerActionDisabled = computed(() => noKbAvailable.value || (isMobile.value ? sseRequesting.value : buttonDisabled.value))
const composerActionIcon = computed(() => {
  if (!isMobile.value || hasPrompt.value)
    return 'ri:send-plane-fill'
  return mobileToolsOpen.value ? 'ri:close-line' : 'ri:apps-2-line'
})

function setMobileToolsExpanded(expanded: boolean) {
  mobileToolsOpen.value = expanded
}

function handleComposerAction() {
  if (isMobile.value && !hasPrompt.value && !sseRequesting.value) {
    setMobileToolsExpanded(!mobileToolsOpen.value)
    return
  }
  handleSubmit()
}

const footerClass = computed(() => {
  // 桌面端横向 padding 交给 .knowledge-input-layout（0 16px），保证消息头像与输入框左缘对齐
  let classes = ['py-4']
  if (isMobile.value)
    classes = ['sticky', 'left-0', 'bottom-0', 'right-0', 'p-2', 'pr-3', 'overflow-hidden']
  return classes
})

watch(() => prompt.value, (value) => {
  if (value.trim() && mobileToolsOpen.value)
    setMobileToolsExpanded(false)
})

function closeMobileTools() {
  setMobileToolsExpanded(false)
}

function openMobileChat() {
  closeMobileTools()
  router.push({ name: 'ChatDetail', params: { uuid: chatStore.active } })
}

function openMobileWorkflow() {
  closeMobileTools()
  router.push({
    name: 'WfDetail',
    params: { uuid: wfStore.activeUuid || 'default' },
  })
}

function openMobileTools() {
  closeMobileTools()
  router.push({ name: 'Mcp' })
}

function goCreateKb() {
  router.push({ name: 'KnowledgeBaseManage' })
}

function openMobilePromptStore() {
  closeMobileTools()
  openPromptStore()
}

async function firstLoad() {
  if (!!qaRecords.value && !kbStore.loadingRecords.get(currKbUuid) && !kbStore.kbUuidToQaRecords.get(currKbUuid)) {
    try {
      kbStore.setLoadingRecords(currKbUuid, true)
      const resp = await api.knowledgeBaseQaRecordSearch<KnowledgeBase.QaRecordListResp>(currKbUuid, '', currentPage, pageSize)
      const records = resp.data.records || []
      kbStore.appendRecords(currKbUuid, records)
      if (records.length < pageSize)
        loadedAll.value = true
      currentPage++
    } catch (error) {
      console.error('load knowledge-base records failed', error)
      ms.error(t('common.wrong'))
    } finally {
      kbStore.setLoadingRecords(currKbUuid, false)
    }
    nextTick(() => {
      scrollToBottom()
    })
    if (inputRef.value && !isMobile.value)
      inputRef.value?.focus()
  }
}

watch(
  () => authStore.token,
  () => {
    // 'default' is only a route placeholder. The sider list resolves the real
    // kb uuid and router.replace re-creates this component, so fetching here
    // would only send a doomed qa/search?kbUuid=default request that pops an
    // error toast on first entry.
    if (authStore.token && currKbUuid !== 'default')
      firstLoad()
  },
  { immediate: true },
)

onUnmounted(() => {
  handleStop()
})

onDeactivated(() => {
  handleStop()
  mobileToolsOpen.value = false
})

onActivated(async () => {
  scrollToBottom()
})
</script>

<template>
  <div class="chat-box flex flex-col w-full h-full">
    <HeaderComponent v-if="isMobile" :using-context="false" />
    <PCHeader v-else :knowledge-base="kbStore.getSelectedKb as KnowledgeBase.Info" />
    <main class="flex-1 overflow-hidden">
      <div id="scrollRef" ref="scrollRef" class="h-full overflow-hidden overflow-y-auto" @scroll="handleScroll">
        <div
          id="image-wrapper" class="m-auto knowledge-message-surface" style="width: min(980px, 100%);"
          :class="[isMobile ? 'p-2' : 'p-4']"
        >
          <LoginTip v-if="!authStore.token" />
          <div v-else-if="noKbAvailable" class="resource-list-empty flex flex-col items-center mt-16 text-center">
            <SvgIcon icon="ri:book-2-line" class="mb-2 text-3xl" />
            <span>{{ t('knowledgeBase.emptyNoKbTitle') }}</span>
            <span class="mt-1 mb-4 text-xs">{{ t('knowledgeBase.emptyNoKbDesc') }}</span>
            <NButton type="primary" @click="goCreateKb">
              {{ t('knowledgeBase.goCreateKb') }}
            </NButton>
          </div>
          <div v-else-if="loadingCurrRecords && !qaRecords.length" class="flex justify-center py-8">
            <NSpin size="small" />
          </div>
          <div v-else-if="!qaRecords.length" class="resource-list-empty flex flex-col items-center mt-4 text-center">
            <SvgIcon icon="ri:inbox-line" class="mb-2 text-3xl" />
            <span>{{ t('knowledgeBase.emptyRecords') }}</span>
          </div>

          <template v-else>
            <div v-for="qaRecord of qaRecords" :key="qaRecord.uuid">
              <Message
                :date-time="qaRecord.createTime" :text="qaRecord.question" :regenerate="false" type="text"
                :inversion="true" :error="qaRecord.error" :loading="false" @delete="handleDelete(qaRecord.uuid)"
              />
              <Message
                :date-time="qaRecord.createTime"
                :text="qaRecord.loading ? qaRecord.answer : (qaRecord.answer || t('common.noAnswer'))"
                :regenerate="false" type="text" :inversion="false" :error="qaRecord.error" :loading="qaRecord.loading"
                :input-tokens="qaRecord.inputTokens" :output-tokens="qaRecord.outputTokens"
                :duration="qaRecord.duration"
                :ai-model-platform="qaRecord.aiModelPlatform" @delete="handleDelete(qaRecord.uuid)"
              >
                <template #actions>
                  <NButton
                    v-if="!!qaRecord.answer && !qaRecord.loading && qaRecord.isRefEmbedding" class="message-action-link readable-accent-button" size="tiny" quaternary type="primary"
                    @click="handleReferenceClick(qaRecord.uuid)"
                  >
                    {{ t('chat.reference') }}
                  </NButton>

                  <NButton
                    v-if="!!qaRecord.answer && !qaRecord.loading && qaRecord.isRefGraph" class="message-action-link readable-accent-button" size="tiny" quaternary type="primary"
                    @click="handleGraphClick(qaRecord.uuid)"
                  >
                    {{ t('chat.graph') }}
                  </NButton>
                </template>
              </Message>
            </div>
          </template>
        </div>
      </div>
      <div class="sticky bottom-0 left-0 flex justify-center">
        <NButton v-if="sseRequesting" size="tiny" @click="handleStop">
          <template #icon>
            <SvgIcon icon="ri:stop-circle-line" />
          </template>
          {{ t('common.stopRequest') }}
        </NButton>
      </div>
    </main>
    <footer class="knowledge-composer-footer" :class="footerClass">
      <div class="knowledge-input-layout">
        <div class="knowledge-composer-toolbar">
          <div class="knowledge-model-control">
            <LLMSelector name-only />
          </div>
        </div>
        <div class="knowledge-composer">
          <NInput
            ref="inputRef" v-model:value="prompt" class="knowledge-composer-input" type="textarea" :placeholder="placeholderPrefix + t('chat.placeholder')"
            :autosize="{ minRows: 1, maxRows: isMobile ? 4 : 8 }" :disabled="noKbAvailable" @keypress="handleEnter"
          />
          <NButton
            class="knowledge-composer-send" type="primary" circle size="large" :title="t('chat.sendMessage')"
            :class="{ 'knowledge-composer-send--expand': isMobile && !hasPrompt }"
            :disabled="composerActionDisabled" @click="handleComposerAction"
          >
            <template #icon>
              <Transition name="knowledge-composer-action-icon" mode="out-in">
                <span :key="composerActionIcon" :class="{ 'dark:text-black': !isMobile || hasPrompt }">
                  <SvgIcon :icon="composerActionIcon" />
                </span>
              </Transition>
            </template>
          </NButton>
        </div>
      </div>
    </footer>

    <NDrawer
      v-model:show="mobileToolsOpen" class="chat-capability-drawer" placement="bottom"
      height="calc(180px + env(safe-area-inset-bottom))" :aria-label="t('chat.resourcesAndTools')"
    >
      <NDrawerContent>
        <div class="chat-capability-grid">
          <button type="button" class="chat-capability-card" @click="openMobileChat">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:chat-3-line" /></span>
            <span>{{ t('menu.chat') }}</span>
          </button>
          <button type="button" class="chat-capability-card" @click="openMobileWorkflow">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:apps-2-line" /></span>
            <span>{{ t('menu.workflow') }}</span>
          </button>
          <button type="button" class="chat-capability-card" @click="openMobileTools">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:tools-line" /></span>
            <span>{{ t('chat.tools') }}</span>
          </button>
          <button type="button" class="chat-capability-card" @click="openMobilePromptStore">
            <span class="chat-capability-card-icon"><SvgIcon icon="ri:book-marked-line" /></span>
            <span>{{ t('store.siderButton') }}</span>
          </button>
        </div>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="showReferenceModal" class="evidence-modal" preset="card" :title="t('chat.referenceMaterial')">
      <div v-show="references.length === 0">
        {{ t('common.none') }}
      </div>
      <NCollapse v-show="references.length > 0" :default-expanded-names="['refer_0']">
        <NCollapseItem
          v-for="(reference, idx) of references" :key="reference.embeddingId" :title="`${t('chat.reference')}${idx + 1}`"
          :name="`refer_${idx}`"
        >
          <!-- 证据原文可能来自 Markdown 文档，按 Markdown 渲染；纯文本回落为段落 -->
          <EvidenceMarkdown :text="reference.text" />
        </NCollapseItem>
      </NCollapse>
    </NModal>

    <NModal v-model:show="showRefGraphModal" class="graph-modal evidence-modal" display-directive="show" preset="card" :title="t('chat.referenceGraph')">
      <RefGraph :qa-record-uuid="showRefGraphRecordUuid" />
    </NModal>
  </div>
</template>

<style scoped lang="less">
/* 与消息面 knowledge-message-surface（min(980px,100%) + p-4）同几何，
 * 桌面端 0 16px 横向 padding 使输入框左缘 == 消息头像左缘 */
.knowledge-input-layout {
  width: min(980px, 100%);
  margin: 0 auto;
}

@media (min-width: 768px) {
  .knowledge-input-layout {
    padding: 0 16px;
  }
}

.knowledge-composer-footer {
  flex: none;
}

.knowledge-composer-toolbar {
  display: flex;
  min-height: 35px;
  align-items: center;
  margin-bottom: 7px;
}

.knowledge-model-control {
  display: flex;
  min-height: 32px;
  align-items: center;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
}

.knowledge-model-control :deep(.n-button) {
  height: 30px;
  min-height: 30px;
  padding: 0 10px;
  border: 0;
  background: transparent;
  box-shadow: none;
  font-size: 11px;
}

.knowledge-composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.knowledge-composer-input {
  flex: 1;
  min-width: 0;
}

.knowledge-composer-input :deep(.n-input) {
  min-height: 42px;
  border-radius: 13px !important;
  background: var(--zhimesh-glass);
}

.knowledge-composer-input :deep(.n-input-wrapper) {
  padding: 0 !important;
}

.knowledge-composer-input :deep(.n-input__textarea-el) {
  display: block;
  box-sizing: border-box;
  min-height: 40px;
  padding: 9px 14px !important;
  line-height: 22px;
  text-indent: 0;
}

.knowledge-composer-send {
  width: 40px;
  height: 40px;
  flex: none;
  background: var(--zhimesh-control-primary) !important;
  box-shadow: none;
}

.knowledge-composer-send--expand {
  color: var(--zhimesh-primary) !important;
  background: var(--zhimesh-info-surface) !important;
  border: 1px solid var(--zhimesh-border) !important;
}

.knowledge-composer-action-icon-enter-active,
.knowledge-composer-action-icon-leave-active {
  display: inline-flex;
  transition: opacity 180ms ease, transform 220ms cubic-bezier(0.16, 1, 0.3, 1);
}

.knowledge-composer-action-icon-enter-from {
  opacity: 0;
  transform: scale(0.7) rotate(-12deg);
}

.knowledge-composer-action-icon-leave-to {
  opacity: 0;
  transform: scale(0.7) rotate(12deg);
}

.chat-capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.chat-capability-card {
  display: flex;
  min-width: 0;
  min-height: 68px;
  padding: 12px 10px;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 14px;
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-soft);
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  cursor: pointer;
  transition: color 180ms ease, border-color 180ms ease, background-color 180ms ease, transform 180ms ease;
}

.chat-capability-card:hover,
.chat-capability-card:focus-visible {
  border-color: var(--zhimesh-primary);
  color: var(--zhimesh-primary);
  outline: none;
  transform: translateY(-1px);
}

.chat-capability-card-icon {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  border-radius: 10px;
  color: var(--zhimesh-primary);
  background: var(--zhimesh-info-surface);
  font-size: 18px;
}

:global(.dark) .knowledge-composer-input :deep(.n-input) {
  background: var(--zhimesh-glass-strong);
}

:global(.dark) .knowledge-model-control {
  background: var(--zhimesh-glass-soft);
}

@media (max-width: 767px) {
  .knowledge-composer-footer {
    position: sticky !important;
    z-index: 20;
    bottom: 0;
    margin-top: auto;
    padding: 7px 10px 10px !important;
    border-top: 1px solid var(--zhimesh-border-subtle);
    background: var(--zhimesh-glass-nav-strong);
    box-shadow: 0 -8px 24px rgba(24, 45, 62, 0.08);
    backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
    -webkit-backdrop-filter: blur(var(--zhimesh-glass-blur)) saturate(120%);
  }

  .knowledge-composer-toolbar {
    margin-bottom: 5px;
  }

  .knowledge-composer-input :deep(.n-input) {
    min-height: 46px;
    border-radius: 16px !important;
  }

  .knowledge-composer-input :deep(.n-input__textarea-el) {
    min-height: 44px;
    padding: 11px 14px !important;
    font-size: 16px;
  }

  .knowledge-composer-send {
    width: 44px;
    height: 44px;
  }
}
</style>
