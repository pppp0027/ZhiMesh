<script setup lang="ts">
import { NButton } from 'naive-ui'
import { t } from '@/locales'

interface Props {
  answer: Chat.ChatMessage
}

defineProps<Props>()

const emit = defineEmits<{
  (event: 'memory', uuid: string): void
  (event: 'reference', uuid: string): void
  (event: 'graph', uuid: string): void
  (event: 'keyword', uuid: string): void
}>()
</script>

<template>
  <NButton
    v-if="!answer.loading && answer.isRefMemoryEmbedding"
    class="message-action-link" size="tiny" text type="primary"
    @click="emit('memory', answer.uuid)"
  >
    {{ t('chat.memory') }}
  </NButton>
  <NButton
    v-if="!answer.loading && answer.isRefEmbedding"
    class="message-action-link" size="tiny" text type="primary"
    @click="emit('reference', answer.uuid)"
  >
    {{ t('chat.reference') }}
  </NButton>
  <NButton
    v-if="!answer.loading && answer.isRefGraph"
    class="message-action-link" size="tiny" text type="primary"
    @click="emit('graph', answer.uuid)"
  >
    {{ t('chat.graph') }}
  </NButton>
  <NButton
    v-if="!answer.loading && answer.isRefBm25"
    class="message-action-link" size="tiny" text type="primary"
    @click="emit('keyword', answer.uuid)"
  >
    {{ t('chat.keyword') }}
  </NButton>
</template>
