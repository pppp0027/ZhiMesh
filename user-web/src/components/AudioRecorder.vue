<script setup lang='ts'>
import { onBeforeUnmount, ref } from 'vue'
import { NButton, useMessage, useThemeVars } from 'naive-ui'
import { MediaRecorder, deregister, register } from 'extendable-media-recorder'
import { connect } from 'extendable-media-recorder-wav-encoder'
import { format } from 'date-fns'
import { SvgIcon } from '@/components/common'
import AudioWaveIcon from '@/icons/AudioWave.vue'
import api from '@/api'
import { t } from '@/locales'

const emit = defineEmits<Emit>()
interface Emit {
  (e: 'recorded', audioUrl: string, audioBlob: Blob, audioDuration: number): void
  (e: 'submitted', uuid: string, url: string, audioDuration: number): void
  (e: 'exit'): void
}

const themeVars = useThemeVars()
const audioChunks = ref<Blob[]>([])
const audioUrl = ref('')
const recording = ref(false)
const submitting = ref(false)
const errorMsg = ref('')
const mediaType = 'audio/wav'
const ms = useMessage()
const recordingDuration = ref(0)
const starting = ref(false)
let mediaRecorder: any = null
let mediaStream: MediaStream | null = null
let registeredPort: MessagePort | null = null
let recordingTimer: ReturnType<typeof setTimeout> | undefined
let stopPromise: Promise<void> | null = null
let pendingStopResolve: (() => void) | null = null
let pendingStopEmit = false
let disposed = false
let startRequestId = 0
let cancelStopResult = false

function clearRecordingTimer() {
  if (recordingTimer !== undefined)
    clearTimeout(recordingTimer)
  recordingTimer = undefined
}

function stopMediaStream() {
  mediaStream?.getTracks().forEach(track => track.stop())
  mediaStream = null
}

async function releaseRegisteredPort() {
  const currentPort = registeredPort
  registeredPort = null
  if (!currentPort)
    return
  try {
    await deregister(currentPort)
  } catch (error) {
    console.warn('Failed to deregister audio encoder', error)
  }
}

function revokeAudioUrl() {
  if (audioUrl.value) {
    URL.revokeObjectURL(audioUrl.value)
    audioUrl.value = ''
  }
}

function getRecordingErrorMessage(exception: unknown) {
  const message = exception instanceof Error ? exception.message : String(exception)
  if (/permission denied|notallowederror/i.test(message))
    return t('audio.getRecordDeviceFailed')
  if (/requested device not found|notfounderror/i.test(message))
    return t('audio.noRecordDevice')
  return t('audio.getRecordDeviceFailed')
}

function handleRecorderStop() {
  const shouldEmit = pendingStopEmit && !cancelStopResult && !disposed
  const blob = shouldEmit ? new Blob(audioChunks.value, { type: mediaType }) : null
  if (blob) {
    revokeAudioUrl()
    audioUrl.value = URL.createObjectURL(blob)
  } else {
    revokeAudioUrl()
  }

  recording.value = false
  clearRecordingTimer()
  stopMediaStream()
  mediaRecorder = null
  const resolve = pendingStopResolve
  pendingStopResolve = null
  pendingStopEmit = false
  releaseRegisteredPort().then(() => {
    if (shouldEmit && blob)
      emit('recorded', audioUrl.value, blob, recordingDuration.value / 1000)
    resolve?.()
  })
}

async function startRecording() {
  if (starting.value || recording.value || disposed)
    return
  const requestId = ++startRequestId
  errorMsg.value = ''
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    errorMsg.value = t('audio.browserNotSupportRecord')
    return
  }
  starting.value = true
  let encoderPort: MessagePort | null = null
  try {
    revokeAudioUrl()
    audioChunks.value = []
    recordingDuration.value = 0
    // Acquire the device before registering the encoder so a denied permission
    // does not leave an encoder port behind.
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    if (disposed || requestId !== startRequestId) {
      stream.getTracks().forEach(track => track.stop())
      return
    }
    mediaStream = stream
    encoderPort = await connect()
    await register(encoderPort)
    if (disposed || requestId !== startRequestId) {
      await deregister(encoderPort)
      stopMediaStream()
      return
    }
    registeredPort = encoderPort
    mediaRecorder = new MediaRecorder(stream, { mimeType: mediaType })
    mediaRecorder.addEventListener('dataavailable', (event: any) => {
      if (event.data.size > 0)
        audioChunks.value.push(event.data)
    })
    mediaRecorder.addEventListener('stop', handleRecorderStop, { once: true })
    recording.value = true
    recordingDurationCount()
    mediaRecorder.start()
  } catch (exception: any) {
    console.error('startRecording error', exception)
    recording.value = false
    clearRecordingTimer()
    stopMediaStream()
    mediaRecorder = null
    let unregisteredPort: MessagePort | null = null
    if (encoderPort && encoderPort !== registeredPort)
      unregisteredPort = encoderPort
    await releaseRegisteredPort()
    if (unregisteredPort) {
      try {
        await deregister(unregisteredPort)
      } catch (error) {
        console.warn('Failed to deregister audio encoder', error)
      }
    }
    errorMsg.value = getRecordingErrorMessage(exception)
  } finally {
    starting.value = false
  }
}

function recordingDurationCount() {
  clearRecordingTimer()
  if (!recording.value || disposed)
    return
  recordingDuration.value = recordingDuration.value + 200
  recordingTimer = setTimeout(recordingDurationCount, 200)
}

async function stopRecording(emitResult = true) {
  if (!emitResult && stopPromise)
    cancelStopResult = true
  if (stopPromise)
    return stopPromise

  const recorder = mediaRecorder
  if (!recorder) {
    recording.value = false
    clearRecordingTimer()
    stopMediaStream()
    await releaseRegisteredPort()
    cancelStopResult = false
    return
  }

  stopPromise = new Promise<void>((resolve) => {
    pendingStopResolve = resolve
    pendingStopEmit = emitResult
    try {
      if (recorder.state === 'inactive')
        handleRecorderStop()
      else
        recorder.stop()
    } catch (error) {
      console.warn('Failed to stop audio recorder', error)
      recorder.removeEventListener('stop', handleRecorderStop)
      handleRecorderStop()
    }
  }).finally(() => {
    stopPromise = null
    cancelStopResult = false
  })

  return stopPromise
}
async function submit() {
  if (audioChunks.value.length === 0) {
    ms.error(t('audio.noRecordToUpload'))
    return
  }
  if (submitting.value) {
    ms.warning(t('audio.uploadingPleaseWait'))
    return
  }
  try {
    submitting.value = true
    const file = new File([new Blob(audioChunks.value, { type: mediaType })], `record_${format(new Date(), 'yyyyMMddHHmmss')}.wav`, { type: mediaType, lastModified: Date.now() })
    const resp = await api.fileUpload<FileUploaded>(file)
    if (resp.success)
      emit('submitted', resp.data.uuid, resp.data.url, recordingDuration.value / 1000)
    else
      ms.error(t('audio.uploadAudioFailed'))
  } catch (error) {
    ms.error('上传音频失败')
  } finally {
    submitting.value = false
  }
}

function exit() {
  if (submitting.value) {
    ms.warning(t('audio.uploadingPleaseWait'))
    return
  }
  startRequestId++
  recording.value = false
  audioChunks.value = []
  revokeAudioUrl()
  stopRecording(false).catch(error => console.warn('Failed to cancel audio recorder', error))
  emit('exit')
}

function toggleRecording() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    ms.error(t('audio.browserNotSupportRecord'))
    return
  }
  if (submitting.value) {
    ms.warning(t('audio.uploadingPleaseWait'))
    return
  }
  if (recording.value)
    stopRecording().catch(error => console.warn('Failed to stop audio recorder', error))
  else
    startRecording()
}

onBeforeUnmount(() => {
  disposed = true
  startRequestId++
  recording.value = false
  clearRecordingTimer()
  stopMediaStream()
  revokeAudioUrl()
  stopRecording(false).catch(error => console.warn('Failed to release audio recorder', error))
})
</script>

<template>
  <div class="flex flex-col items-center space-y-3">
    <button
      type="button" class="audio-record-toggle custom-hover"
      :aria-label="recording ? t('common.stopRequest') : t('audio.clickToStart')"
      :aria-pressed="recording" @click="toggleRecording"
    >
      <SvgIcon
        v-if="!recording" class="text-6xl" icon="pepicons-pop:microphone-circle-filled"
        aria-hidden="true"
      />
      <AudioWaveIcon v-else :placeholder="t('audio.dialogPlaceholder')" aria-hidden="true" />
    </button>
    <div v-if="!recording" class="text-sm text-gray-500">
      {{ t('audio.clickToStart') }}
    </div>
    <div v-if="recording" class="text-sm text-gray-500">
      {{ t('audio.dialogCount', { count: recordingDuration / 1000 }) }}
    </div>
    <div v-if="errorMsg" class="text-sm text-red-500">
      {{ errorMsg }}
    </div>
    <div v-if="audioUrl" class="py-2">
      <audio ref="audio" :src="audioUrl" controls />
    </div>
    <div v-if="!recording" class="flex items-center space-x-2 justify-items-end pt-4">
      <NButton ghost class="mt-6" :loading="submitting" :disabled="submitting" @click="exit">
        {{ t('common.cancel') }}
      </NButton>
      <NButton v-if="audioUrl" ghost class="mt-6" :loading="submitting" :disabled="!audioUrl || submitting" @click="submit">
        {{ t('audio.sendLabel') }}
      </NButton>
    </div>
  </div>
</template>

<style lang="less" scoped>
.custom-hover {
  color: v-bind('themeVars.primaryColor');

  :hover {
    color: v-bind('themeVars.primaryColorHover');
  }
}

.audio-record-toggle {
  display: grid;
  min-width: 64px;
  min-height: 64px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
}

.audio-record-toggle:focus-visible {
  outline: 2px solid v-bind('themeVars.primaryColor');
  outline-offset: 3px;
}
</style>
