<template>
  <div class="detail-view">
    <n-alert type="info" :bordered="false" class="detail-hint">
      共生成 {{ pagination.itemCount }} 个向量片段。点击片段内容或向量预览可查看完整数据。
    </n-alert>
    <n-data-table
      remote
      :loading="loading"
      :columns="columns"
      :data="rows"
      :pagination="pagination"
      :scroll-x="860"
      :max-height="480"
      @update:page="load"
    />
  </div>

  <n-modal v-model:show="showContent" preset="card" title="片段详情" :style="{ width: '86%', maxWidth: '760px' }">
    <n-scrollbar style="max-height: 460px">
      <pre class="detail-content">{{ content }}</pre>
    </n-scrollbar>
  </n-modal>
</template>

<script lang="ts" setup>
  import { h, onMounted, reactive, ref } from 'vue'
  import { NButton, NEllipsis, type DataTableColumns } from 'naive-ui'
  import api from '@/api/knowledgeBase'

  const props = defineProps<{ itemUuid: string }>()
  const loading = ref(false)
  const rows = ref<Record<string, any>[]>([])
  const showContent = ref(false)
  const content = ref('')
  const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0 })

  function openContent(value: unknown) {
    content.value = Array.isArray(value) ? value.join(', ') : String(value || '')
    showContent.value = true
  }

  const columns: DataTableColumns<Record<string, any>> = [
    { title: '片段 ID', key: 'embeddingId', width: 190 },
    {
      title: '文档片段',
      key: 'text',
      minWidth: 360,
      render: (row) =>
        h(NEllipsis, { lineClamp: 3, tooltip: false, class: 'clickable-cell', onClick: () => openContent(row.text) }, {
          default: () => row.text || '-',
        }),
    },
    {
      title: '向量数据',
      key: 'embedding',
      width: 180,
      render: (row) =>
        h(NButton, { size: 'small', secondary: true, onClick: () => openContent(row.embedding) }, {
          default: () => Array.isArray(row.embedding) ? `${row.embedding.length} 维 · 查看` : '查看',
        }),
    },
  ]

  async function load(page = 1) {
    loading.value = true
    try {
      const response = await api.listEmbeddings(props.itemUuid, page, pagination.pageSize)
      rows.value = response.data?.records || []
      pagination.page = page
      pagination.itemCount = response.data?.total || 0
    } finally {
      loading.value = false
    }
  }

  onMounted(() => load(1))
</script>

<style lang="less" scoped>
  .detail-hint { margin-bottom: 12px; }
  .detail-content { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: inherit; line-height: 1.7; }
  :deep(.clickable-cell) { cursor: pointer; }
</style>
