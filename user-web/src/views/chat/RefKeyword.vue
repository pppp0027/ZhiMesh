<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { NCollapse, NCollapseItem, NSpin, NTag } from 'naive-ui'
import api from '@/api'
import { t } from '@/locales'
import EvidenceMarkdown from './components/EvidenceMarkdown.vue'

interface Props {
  msgUuid: string
}

const props = withDefaults(defineProps<Props>(), { msgUuid: '' })
const loading = ref(false)
const reference = ref<Chat.KeywordReference>({ terms: [], hits: [] })

async function load(msgUuid: string) {
  reference.value = { terms: [], hits: [] }
  if (!msgUuid)
    return
  loading.value = true
  try {
    const { data } = await api.messageKeywordRef(msgUuid)
    if (msgUuid === props.msgUuid)
      reference.value = data || { terms: [], hits: [] }
  } finally {
    loading.value = false
  }
}

onMounted(() => load(props.msgUuid))
watch(() => props.msgUuid, value => load(value))
</script>

<template>
  <div v-if="loading" class="keyword-empty-state">
    <NSpin size="medium" />
  </div>
  <div v-else-if="reference.hits.length === 0" class="keyword-empty-state">
    {{ t('common.noData') }}
  </div>
  <div v-else class="keyword-reference">
    <div class="keyword-terms">
      <span class="keyword-terms-label">{{ t('chat.searchTerms') }}</span>
      <div class="keyword-term-list">
        <NTag v-for="term in reference.terms" :key="term" size="small" :bordered="false">
          {{ term }}
        </NTag>
      </div>
    </div>
    <NCollapse :default-expanded-names="['keyword_0']">
      <NCollapseItem
        v-for="(hit, index) of reference.hits" :key="hit.chunkUuid"
        :name="`keyword_${index}`"
      >
        <template #header>
          <span>{{ t('chat.keywordHit') }} {{ index + 1 }}</span>
          <span class="keyword-score">BM25 {{ hit.score.toFixed(3) }}</span>
        </template>
        <div class="keyword-hit-text">
          <EvidenceMarkdown :text="hit.text" />
        </div>
      </NCollapseItem>
    </NCollapse>
  </div>
</template>

<style scoped lang="less">
.keyword-empty-state {
  display: grid;
  min-height: 220px;
  place-items: center;
  color: var(--zhimesh-text-muted);
}

.keyword-reference {
  display: grid;
  gap: 18px;
}

.keyword-terms {
  display: grid;
  gap: 8px;
}

.keyword-terms-label {
  color: var(--zhimesh-text-muted);
  font-size: 12px;
  font-weight: 650;
}

.keyword-term-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.keyword-score {
  margin-left: 8px;
  color: var(--zhimesh-text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.keyword-hit-text {
  max-width: 75ch;
}
</style>
