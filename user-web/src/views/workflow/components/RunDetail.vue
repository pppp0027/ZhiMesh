<script lang="ts" setup>
import { nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { NButton, NInput, NInputNumber, NP, NSelect, NSwitch, NText, NUpload, NUploadDragger, useMessage } from 'naive-ui'
import type { UploadFileInfo, UploadInst } from 'naive-ui'
import RuntimeNodes from './RuntimeNodes.vue'
import TextComponent from '@/views/chat/components/Message/Text.vue'
import { useAuthStore, useWfStore } from '@/store'
import { SvgIcon } from '@/components/common'
import api from '@/api'
import { t } from '@/locales'
interface Props {
  workflow: Workflow.WorkflowInfo
  showHeader?: boolean
}
interface TabObj {
  name: string
  tab: string
  defaultTab: string
}
interface Emit {
  (e: 'runDone'): void
  (e: 'runError', errorMsg: string): void
  (e: 'runCancelled'): void
}
const props = withDefaults(defineProps<Props>(), {
  showHeader: true,
})
const emit = defineEmits<Emit>()
const headers = { Authorization: '' }
const wfStore = useWfStore()
const authStore = useAuthStore()
const token = ref<string>(authStore.token)
const ms = useMessage()
const submitting = ref<boolean>(wfStore.submitting)
const startNode = wfStore.getStartNode(props.workflow.uuid)
const wfRuntimeUuid = ref<string>('')
const runtimeNodes = reactive<Workflow.WfRuntimeNode[]>([])
const runtimeErrorMsg = ref<string>('')
const finalOutputText = ref<string>('')
const userInputs = ref<Workflow.UserInput[]>(startNode?.inputConfig.user_inputs.map((input) => {
  return {
    uuid: input.uuid,
    name: input.name,
    content: {
      title: input.title,
      value: null,
      type: input.type,
    },
    required: input.required,
  }
}) || [])
const errorMsg = ref<string>('')
const currWfUuid = props.workflow.uuid
console.log('instance list currWfUuid', currWfUuid)
const showCurrentExecution = ref<boolean>(false)
const tabObj = ref<TabObj>({ name: 'runtimes', defaultTab: t('workflow.flowRunDetail'), tab: `${t('workflow.flowRunDetail')} ↓` })
const fileListLength = ref(0)
const uploadRef = ref<UploadInst | null>(null)
const uploadedFileUuids = ref<string[]>([])
const humanFeedback = ref<boolean>(false)
const humanFeedbackTip = ref<string>('')
const humanFeedbackContent = ref<string>('')
const inputsCollapsed = ref<boolean>(false)
const cancelling = ref(false)
const resuming = ref(false)
const validationErrors = reactive<Record<string, string>>({})
let controller = new AbortController()

function getInputDefinition(input: Workflow.UserInput) {
  return startNode?.inputConfig.user_inputs.find(item => item.uuid === input.uuid)
}

function optionSelectValue(input: Workflow.UserInput): string | string[] | null {
  const keys = Object.keys((input.content.value as Record<string, unknown> | null) || {})
  return getInputDefinition(input)?.multiple ? keys : (keys[0] || null)
}

function updateOptionValue(input: Workflow.UserInput, selected: string | string[] | null) {
  const values = Array.isArray(selected) ? selected : (selected ? [selected] : [])
  input.content.value = Object.fromEntries(values.map(value => [value, value]))
  delete validationErrors[input.uuid]
}

function clearValidation(inputUuid: string) {
  delete validationErrors[inputUuid]
}

function isMissingRequiredValue(input: Workflow.UserInput) {
  if (!input.required)
    return false
  if (input.content.type === 1)
    return typeof input.content.value !== 'string' || input.content.value.trim().length === 0
  if (input.content.type === 3)
    return Object.keys((input.content.value as Record<string, unknown> | null) || {}).length === 0
  if (input.content.type === 4)
    return input.content.value === null && fileListLength.value === 0
  return input.content.value === null || input.content.value === undefined
}

async function uploadBeforeRun() {
  uploadedFileUuids.value = []
  if (uploadRef.value && Array.isArray(uploadRef.value) && uploadRef.value.length > 0)
    uploadRef.value[0]?.submit()
  else if (uploadRef.value)
    uploadRef.value?.submit()
}

async function resetInputs() {
  if (uploadRef.value && Array.isArray(uploadRef.value) && uploadRef.value.length > 0) {
    uploadRef.value.forEach((item) => {
      item.clear()
    })
  } else if (uploadRef.value) {
    uploadRef.value?.clear()
  }
  uploadedFileUuids.value = []

  userInputs.value.forEach((input) => {
    input.content.value = null
  })
}

function getFinalOutputText(outputJson: string) {
  try {
    const output = JSON.parse(outputJson) as Record<string, { value?: unknown; content?: { value?: unknown } }>
    return Object.values(output)
      .map(item => item.value ?? item.content?.value ?? '')
      .map(value => Array.isArray(value) ? value.join('\n') : String(value))
      .filter(Boolean)
      .join('\n\n')
  } catch (error) {
    console.warn('Unable to parse workflow final output', error)
    return outputJson || ''
  }
}

async function run() {
  if (!authStore.checkLoginOrShow())
    return

  if (submitting.value)
    return

  Object.keys(validationErrors).forEach(key => delete validationErrors[key])
  userInputs.value.forEach((input) => {
    if (isMissingRequiredValue(input))
      validationErrors[input.uuid] = input.content.type === 4 ? t('workflow.pleaseUploadFile') : t('workflow.fieldRequired')
  })
  if (Object.keys(validationErrors).length > 0) {
    ms.warning(t('workflow.pleaseInputAllRequired'))
    return
  }

  if (fileListLength.value > 0 && uploadedFileUuids.value.length !== fileListLength.value) {
    console.log('先执行文件上传操作')
    uploadBeforeRun()
    return
  } else {
    const fileInput = userInputs.value.find(input => input.content.type === 4 && input.content.value === null)
    if (fileInput)
      fileInput.content.value = uploadedFileUuids.value
  }

  submitting.value = true
  cancelling.value = false
  resuming.value = false
  showCurrentExecution.value = true
  inputsCollapsed.value = true
  tabObj.value.tab = showCurrentExecution.value ? `${tabObj.value.defaultTab} ↓` : `${tabObj.value.defaultTab} ↑`

  controller = new AbortController()
  try {
    wfRuntimeUuid.value = ''
    const nodeUuidToRuntimeNodeUuid = new Map<string, string>()
    runtimeNodes.splice(0, runtimeNodes.length)
    finalOutputText.value = ''
    await api.workflowRun({
      options: {
        uuid: currWfUuid,
        inputs: userInputs.value,
      },
      signal: controller.signal,
      startCallback: (wfRuntimeJson) => {
        if (!wfRuntimeJson) {
          ms.error(t('workflow.startFailed'))
          return
        }
        let wfRuntime: Workflow.WorkflowRuntime
        try {
          wfRuntime = JSON.parse(wfRuntimeJson) as Workflow.WorkflowRuntime
        } catch (error) {
          console.error('Invalid workflow runtime payload', error)
          ms.error(t('workflow.startFailed'))
          return
        }
        wfRuntime.input = {}
        userInputs.value.forEach((item) => {
          wfRuntime.input[item.name] = { ...item.content }
        })
        // The START payload is created as READY before the backend transitions it to DOING.
        // Keep the live list aligned with the execution that has already started.
        wfRuntime.status = 2
        wfRuntime.detailLoaded = true
        wfRuntimeUuid.value = wfRuntime.uuid
        wfStore.appendWfRuntimes(
          currWfUuid,
          [wfRuntime],
        )
      },
      thinkingDataReceived: (chunk) => {
        // 处理思考数据
        console.log('Thinking data received:', chunk)
      },
      messageReceived: (chunk, event) => {
        const eventName = event || ''
        try {
          if (eventName.includes('[NODE_RUN_')) {
            const nodeUuid = eventName.replace('[NODE_RUN_', '').replace(']', '')
            console.log(`${nodeUuid}开始运行`)
            const runtimeNode = JSON.parse(chunk) as Workflow.WfRuntimeNode
            nodeUuidToRuntimeNodeUuid.set(nodeUuid, runtimeNode.uuid)
            wfStore.appendRuntimeNode(
              wfRuntimeUuid.value,
              runtimeNode,
            )
            runtimeNodes.push(runtimeNode)
          } else if (eventName.includes('[NODE_CHUNK_')) {
            const nodeUuid = eventName.replace('[NODE_CHUNK_', '').replace(']', '')
            const runtimeNodeUuid = nodeUuidToRuntimeNodeUuid.get(nodeUuid) || ''
            wfStore.appendChunkToRuntimeNode(
              wfRuntimeUuid.value,
              runtimeNodeUuid,
              chunk,
            )
          } else if (eventName.includes('[NODE_INPUT_')) {
            const nodeUuid = eventName.replace('[NODE_INPUT_', '').replace(']', '')
            const runtimeNodeUuid = nodeUuidToRuntimeNodeUuid.get(nodeUuid) || ''
            wfStore.appendInputToRuntimeNode(
              wfRuntimeUuid.value,
              runtimeNodeUuid,
              chunk,
            )
          } else if (eventName.includes('[NODE_OUTPUT_')) {
            const nodeUuid = eventName.replace('[NODE_OUTPUT_', '').replace(']', '')
            const runtimeNodeUuid = nodeUuidToRuntimeNodeUuid.get(nodeUuid) || ''
            wfStore.appendOutputToRuntimeNode(
              wfRuntimeUuid.value,
              runtimeNodeUuid,
              chunk,
            )
          } else if (eventName.includes('[NODE_WAIT_FEEDBACK_BY_')) {
            humanFeedback.value = true
            humanFeedbackTip.value = chunk || ''
            resuming.value = false
            inputsCollapsed.value = false
            wfStore.updateRuntimeStatus(wfRuntimeUuid.value, 5)
            wfStore.completeRunningRuntimeNodes(wfRuntimeUuid.value)
            ms.info(humanFeedbackTip.value)
          } else if (eventName.includes('[NODE_METRICS_')) {
            const nodeUuid = eventName.replace('[NODE_METRICS_', '').replace(']', '')
            const runtimeNodeUuid = nodeUuidToRuntimeNodeUuid.get(nodeUuid) || ''
            const metrics = JSON.parse(chunk) as Workflow.AnyNodeMetrics
            wfStore.updateRuntimeNodeMetrics(wfRuntimeUuid.value, runtimeNodeUuid, metrics)
          } else if (eventName.includes('[RUNTIME_METRICS]')) {
            const metrics = JSON.parse(chunk) as Workflow.RuntimeMetrics
            wfStore.updateRuntimeMetrics(wfRuntimeUuid.value, metrics)
          } else if (eventName.includes('[RUNTIME_CANCELLED]')) {
            const cancellation = JSON.parse(chunk) as { message?: string }
            submitting.value = false
            cancelling.value = false
            resuming.value = false
            runtimeErrorMsg.value = ''
            wfStore.markRuntimeCancelled(wfRuntimeUuid.value, cancellation.message || t('workflow.runtimeCancelled'))
            resetInputs()
            ms.info(t('workflow.runtimeCancelled'))
            emit('runCancelled')
          }
        } catch (error) {
          console.error(error)
        }
      },
      doneCallback: (chunk) => {
        nextTick(() => {
          submitting.value = false
          cancelling.value = false
          resuming.value = false
          resetInputs()
          wfStore.updateSuccess(currWfUuid, wfRuntimeUuid.value, chunk)
          finalOutputText.value = getFinalOutputText(chunk)
          runtimeErrorMsg.value = ''
          ms.success(t('workflow.runSuccess'))
          emit('runDone')
        })
      },
      errorCallback: (error) => {
        submitting.value = false
        cancelling.value = false
        resuming.value = false
        if (controller.signal.aborted)
          return
        resetInputs()
        ms.error(`${t('common.systemTip')}${error}`)
        wfStore.updateErrorMsg(currWfUuid, wfRuntimeUuid.value, error)
        runtimeErrorMsg.value = error || ''
        emit('runError', error)
      },
    })
  } catch (error: any) {
    if (controller.signal.aborted) {
      submitting.value = false
      cancelling.value = false
      return
    }
    const errorMessage = error?.message ?? t('common.wrong')
    ms.error(errorMessage)
    submitting.value = false
  }
}

async function resume() {
  if (resuming.value)
    return
  resuming.value = true
  submitting.value = true
  wfStore.updateRuntimeStatus(wfRuntimeUuid.value, 2)
  try {
    await api.workflowRuntimeResume({
      runtimeUuid: wfRuntimeUuid.value,
      feedbackContent: humanFeedbackContent.value,
    },
    )
    humanFeedback.value = false
    humanFeedbackTip.value = ''
    humanFeedbackContent.value = ''
  } catch (e) {
    wfStore.updateRuntimeStatus(wfRuntimeUuid.value, 5)
    submitting.value = false
    resuming.value = false
    ms.error(`${t('common.systemTip')}${e}`)
  }
}

function onUploadChange(options: { fileList: UploadFileInfo[] }) {
  console.log('onUploadChange', options)
}

function handleFileListChange(fileList: UploadFileInfo[]) {
  console.log('handleFileListChange', fileList)
  fileListLength.value = fileList.length
  if (uploadedFileUuids.value.length === fileListLength.value)
    run()
}

function onUploadFinish({ file, event }: { file: UploadFileInfo; event?: ProgressEvent }) {
  console.log('onUploadFinish', file, event)
  let res: any
  try {
    const responseText = (event?.target as XMLHttpRequest | undefined)?.response
    res = typeof responseText === 'string' ? JSON.parse(responseText) : responseText
  } catch (error) {
    console.error('workflow upload response parse failed', error)
    ms.error(t('common.uploadFailed'))
    return file
  }
  if (!res) {
    ms.error(t('common.uploadFailed'))
    return file
  }
  if (res.success) {
    console.log('uploaded file data:', res.data)
    uploadedFileUuids.value.push(res.data.uuid)
  } else {
    console.log(`onUploadFinish err:${res.data}`)
  }
  return file
}

async function handleStop() {
  if (!submitting.value || cancelling.value)
    return
  if (!wfRuntimeUuid.value) {
    controller.abort()
    submitting.value = false
    return
  }

  cancelling.value = true
  try {
    const { data } = await api.workflowRuntimeCancel<Workflow.WorkflowRuntime>(wfRuntimeUuid.value)
    wfStore.mergeRuntimeSummary(data)
    if (data.status === 7) {
      submitting.value = false
      cancelling.value = false
      wfStore.markRuntimeCancelled(wfRuntimeUuid.value, data.statusRemark || t('workflow.runtimeCancelled'))
      resetInputs()
      emit('runCancelled')
    } else if (data.status === 6) {
      ms.info(t('workflow.runtimeCancelling'))
    } else {
      cancelling.value = false
      ms.warning(t('workflow.cancelTooLate'))
    }
  } catch (error: any) {
    cancelling.value = false
    ms.error(error?.message || t('workflow.cancelFailed'))
  }
}

function handleClick() {
  showCurrentExecution.value = !showCurrentExecution.value
  tabObj.value.tab = showCurrentExecution.value ? `${tabObj.value.defaultTab} ↓` : `${tabObj.value.defaultTab} ↑`
}

watch(
  () => authStore.token,
  (nextToken) => {
    token.value = nextToken
    if (nextToken)
      headers.Authorization = nextToken
  },
  { immediate: true },
)

onUnmounted(() => {
  if (submitting.value)
    controller.abort()
})
</script>

<template>
  <div class="workflow-run-detail w-full max-w-screen-xl m-auto z-10" :class="{ 'is-executing': showCurrentExecution }">
    <div v-if="showHeader" class="workflow-run-detail__header flex items-center justify-between">
      <div>
        <div class="workflow-run-detail__title">
          {{ t('workflow.runInputTitle') }}
        </div>
        <div class="workflow-run-detail__hint">
          {{ t('workflow.runInputHint') }}
        </div>
      </div>
      <NButton size="small" quaternary @click="handleClick">
        <template #icon>
          <SvgIcon :icon="showCurrentExecution ? 'ri:arrow-up-s-line' : 'ri:arrow-down-s-line'" />
        </template>
        {{ showCurrentExecution ? t('workflow.hideExecution') : t('workflow.showExecution') }}
      </NButton>
    </div>
    <transition name="collapse">
      <div v-show="showCurrentExecution" class="workflow-run-detail__execution">
        <div class="workflow-run-detail__execution-title">
          <span>{{ t('workflow.currentExecution') }}</span>
          <NButton v-show="submitting" size="small" :loading="cancelling" :disabled="cancelling" @click="handleStop">
            <template #icon>
              <SvgIcon icon="ri:stop-circle-line" />
            </template>
            {{ cancelling ? t('workflow.runtimeCancelling') : t('common.stopRequest') }}
          </NButton>
        </div>
        <div class="workflow-run-detail__execution-list">
          <RuntimeNodes :nodes="runtimeNodes" :workflow="workflow" :error-msg="runtimeErrorMsg" />
        </div>
      </div>
    </transition>
    <div v-if="errorMsg">
      {{ errorMsg }}
    </div>
    <section v-if="finalOutputText && !submitting" class="workflow-run-detail__final-output">
      <div class="workflow-run-detail__final-output-title">
        <SvgIcon icon="ri:file-list-3-line" />
        <span>{{ t('workflow.finalOutput') }}</span>
      </div>
      <TextComponent :inversion="false" :text="finalOutputText" :as-raw-text="false" />
    </section>
    <div class="workflow-run-detail__input-section" :class="{ 'is-collapsed': inputsCollapsed }">
      <div v-if="!showHeader || showCurrentExecution" class="workflow-run-detail__input-heading">
        <div>
          <div class="workflow-run-detail__input-title">
            {{ t('workflow.runInputTitle') }}
          </div>
          <div v-if="!inputsCollapsed && !showHeader" class="workflow-run-detail__input-hint">
            {{ t('workflow.runInputHint') }}
          </div>
        </div>
        <NButton v-if="showCurrentExecution" text size="small" @click="inputsCollapsed = !inputsCollapsed">
          <template #icon>
            <SvgIcon :icon="inputsCollapsed ? 'ri:arrow-down-s-line' : 'ri:arrow-up-s-line'" />
          </template>
          {{ inputsCollapsed ? t('workflow.showRunInputs') : t('workflow.hideRunInputs') }}
        </NButton>
      </div>
      <transition name="collapse">
        <div v-show="!inputsCollapsed" class="workflow-run-detail__form flex flex-col items-center justify-between space-y-3">
          <template v-if="!humanFeedback">
            <div v-for="(userInput, idx) in userInputs" :key="`${idx}_${userInput.name}`" class="workflow-run-detail__field w-full flex flex-col gap-1">
              <label :id="`workflow-input-label-${userInput.uuid}`" class="workflow-run-detail__label">
                {{ userInput.content.title }}
                <span v-if="userInput.required" class="workflow-run-detail__required" aria-hidden="true">*</span>
              </label>
              <!-- 文本 -->
              <NInput
                v-if="userInput.content.type === 1" v-model:value="userInput.content.value" type="textarea"
                :aria-labelledby="`workflow-input-label-${userInput.uuid}`"
                :aria-invalid="!!validationErrors[userInput.uuid]"
                placeholder="" :autosize="{ minRows: 1, maxRows: 5 }"
                @update:value="clearValidation(userInput.uuid)"
              />
              <!-- 数字 -->
              <NInputNumber
                v-if="userInput.content.type === 2" v-model:value="userInput.content.value" placeholder=""
                :aria-labelledby="`workflow-input-label-${userInput.uuid}`"
                :aria-invalid="!!validationErrors[userInput.uuid]"
                @update:value="clearValidation(userInput.uuid)"
              />
              <!-- 下拉列表 -->
              <NSelect
                v-if="userInput.content.type === 3"
                :value="optionSelectValue(userInput)"
                :multiple="!!getInputDefinition(userInput)?.multiple"
                :options="(getInputDefinition(userInput)?.options || []).map(value => ({ label: value, value }))"
                :placeholder="t('workflow.selectOptionPlaceholder')"
                :aria-labelledby="`workflow-input-label-${userInput.uuid}`"
                :aria-invalid="!!validationErrors[userInput.uuid]"
                @update:value="updateOptionValue(userInput, $event)"
              />
              <!-- 文件列表 -->
              <NUpload
                v-if="userInput.content.type === 4" ref="uploadRef" multiple directory-dnd action="/api/file/upload"
                :default-upload="false"
                :max="startNode?.inputConfig.user_inputs.find(item => item.uuid === userInput.uuid)?.limit || 10"
                :headers="headers" :aria-label="userInput.content.title" @update:file-list="handleFileListChange" @finish="onUploadFinish"
                @change="onUploadChange"
              >
                <NUploadDragger>
                  <NText style="font-size: 16px">
                    {{ t('workflow.clickOrDragToUpload') }}
                  </NText>
                  <NP depth="2" style="margin: 4px 0 0 0">
                    {{ t('workflow.fileFormatSizeLimit') }}
                  </NP>
                </NUploadDragger>
              </NUpload>
              <!-- 布尔值 -->
              <NSwitch
                v-if="userInput.content.type === 5" v-model:value="userInput.content.value"
                :aria-labelledby="`workflow-input-label-${userInput.uuid}`"
                @update:value="clearValidation(userInput.uuid)"
              />
              <p v-if="validationErrors[userInput.uuid]" class="workflow-run-detail__field-error" role="alert">
                {{ validationErrors[userInput.uuid] }}
              </p>
            </div>
            <div class="w-full flex justify-end pt-1">
              <NButton type="primary" :disabled="submitting" :loading="submitting" @click="run">
                {{ t('common.submit') }}
              </NButton>
            </div>
          </template>
          <!-- 流程执行过程中用户的输入 -->
          <template v-if="humanFeedback">
            <div class="flex flex-col p-2 w-full space-y-2">
              <div class="workflow-feedback-alert flex px-2 py-1 rounded-md">
                <div class="text-base workflow-feedback-alert__text" role="status" aria-live="polite">
                  {{ t('workflow.flowPausedWaitingInput') }}
                </div>
              </div>
              <div class="flex flex-col w-full">
                <label id="workflow-human-feedback-label" class="text-sm leading-8">
                  {{ t('workflow.inputTip') }}{{ humanFeedbackTip }}
                </label>
                <NInput
                  v-model:value="humanFeedbackContent" type="textarea" placeholder=""
                  aria-labelledby="workflow-human-feedback-label" :autosize="{ minRows: 2, maxRows: 5 }"
                />
              </div>
              <div class="flex justify-end">
                <NButton type="primary" :disabled="resuming" :loading="resuming" @click="resume">
                  {{ t('common.submit') }}
                </NButton>
              </div>
            </div>
          </template>
        </div>
      </transition>
    </div>
  </div>
</template>

<style scoped>
.workflow-run-detail {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  padding: 2px 4px 8px;
  color: var(--zhimesh-text);
}

.workflow-run-detail__header {
  padding: 2px 2px 12px;
  border-bottom: 1px solid var(--zhimesh-border-subtle);
}

.workflow-run-detail__title {
  color: var(--zhimesh-text);
  font-size: 14px;
  font-weight: 700;
}

.workflow-run-detail__hint {
  margin-top: 3px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.workflow-run-detail__execution {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  margin: 0 0 10px;
  padding: 10px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 10px;
  background: var(--zhimesh-glass-soft);
}

.workflow-run-detail__execution-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 6px;
  color: var(--zhimesh-primary);
  font-size: 12px;
  font-weight: 600;
}

.workflow-run-detail__execution-list {
  min-height: 0;
  flex: 1;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 0 2px 2px;
}

.workflow-run-detail__final-output {
  max-height: min(38vh, 320px);
  flex: 0 1 auto;
  overflow: auto;
  margin: 0 0 10px;
  padding: 12px 14px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 12px;
  background: var(--zhimesh-glass);
  box-shadow: none;
  overscroll-behavior: contain;
}

.workflow-run-detail__final-output-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: var(--zhimesh-primary);
  font-size: 12px;
  font-weight: 700;
}

.workflow-run-detail__final-output :deep(.markdown-body) {
  font-size: 13px;
}

.workflow-run-detail__field {
  max-width: none;
}

.workflow-feedback-alert {
  border: 1px solid var(--zhimesh-warning-text);
  color: var(--zhimesh-warning-text);
  background: var(--zhimesh-warning-surface);
}

.workflow-feedback-alert__text {
  color: var(--zhimesh-warning-text);
}

.workflow-run-detail__label {
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.workflow-run-detail__required {
  margin-inline-start: 3px;
  color: var(--zhimesh-danger-text);
}

.workflow-run-detail__field-error {
  margin: 2px 0 0;
  color: var(--zhimesh-danger-text);
  font-size: 12px;
  line-height: 1.45;
}

.workflow-run-detail__form :deep(.n-input),
.workflow-run-detail__form :deep(.n-input-number),
.workflow-run-detail__form :deep(.n-upload) {
  flex: 1;
}

.workflow-run-detail__form {
  min-height: 0;
  padding: 14px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 12px;
  background: var(--zhimesh-glass);
  box-shadow: 0 5px 18px rgba(15, 23, 42, 0.035);
}

.workflow-run-detail__input-section {
  flex: 0 0 auto;
  padding-top: 2px;
}

.workflow-run-detail__input-section.is-collapsed {
  padding: 8px 10px;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 10px;
  background: var(--zhimesh-glass);
}

.workflow-run-detail__input-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 34px;
  padding: 0 2px 8px;
}

.workflow-run-detail__input-section.is-collapsed .workflow-run-detail__input-heading {
  padding: 0;
}

.workflow-run-detail__input-title {
  color: var(--zhimesh-text);
  font-size: 13px;
  font-weight: 700;
}

.workflow-run-detail__input-hint {
  margin-top: 2px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
}

.workflow-run-detail__field :deep(.n-upload-dragger) {
  padding: 18px 12px;
  border-radius: 10px;
}

@media (max-width: 767px) {
  .workflow-run-detail__field {
    flex-direction: column;
    gap: 4px;
  }

  .workflow-run-detail__label {
    padding-top: 0;
  }
}
</style>
