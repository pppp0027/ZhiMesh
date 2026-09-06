<template>
  <div class="graph-detail">
    <div ref="graphRef" class="graph-canvas"></div>
    <aside class="graph-inspector">
      <n-button
        v-show="!isEmpty"
        size="small"
        :loading="loading"
        type="info"
        ghost
        @click="relayout"
      >
        重新布局
      </n-button>
      <n-empty
        v-show="isEmpty && !loading"
        size="small"
        description="图谱处理已完成，但未抽取到可显示的实体；请调整图谱模型后重新处理文档。"
      />

      <n-flex v-if="selectedVertex" vertical>
        <n-divider title-placement="left">实体</n-divider>
        <div>{{ selectedVertex.id }}</div>
        <n-divider title-placement="left">名称</n-divider>
        <div>{{ selectedVertex.name }}</div>
        <n-divider title-placement="left">描述</n-divider>
        <div class="detail-description">{{ selectedVertex.description || '暂无描述' }}</div>
      </n-flex>

      <n-flex v-if="selectedEdge" vertical>
        <n-divider title-placement="left">关系</n-divider>
        <div>{{ selectedEdge.id }}</div>
        <n-divider title-placement="left">描述</n-divider>
        <div class="detail-description">{{ selectedEdge.description || '暂无描述' }}</div>
      </n-flex>

      <n-empty
        v-if="!loading && !isEmpty && !selectedVertex && !selectedEdge"
        size="small"
        description="点击实体或关系查看详情"
      />
    </aside>
  </div>
</template>

<script lang="ts" setup>
  import cytoscape, { type Core, type LayoutOptions } from 'cytoscape'
  import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
  import api from '@/api/knowledgeBase'

  interface GraphVertex {
    id: string | number
    name?: string
    description?: string
    [key: string]: unknown
  }

  interface GraphEdge {
    id: string | number
    startId: string | number
    endId: string | number
    description?: string
    [key: string]: unknown
  }

  const props = defineProps<{ itemUuid: string }>()
  const graphRef = ref<HTMLElement | null>(null)
  const loading = ref(false)
  const isEmpty = ref(true)
  const selectedVertex = ref<GraphVertex | null>(null)
  const selectedEdge = ref<GraphEdge | null>(null)
  let cy: Core | null = null
  let observer: ResizeObserver | null = null

  function initGraph() {
    if (!graphRef.value || cy) return

    cy = cytoscape({
      container: graphRef.value,
      elements: [],
      style: [
        {
          selector: 'node',
          style: {
            content: 'data(name)',
            width: 30,
            height: 30,
          },
        },
      ],
    })

    cy.on('tap', 'node', (event) => {
      selectedVertex.value = event.target.data() as GraphVertex
      selectedEdge.value = null
    })
    cy.on('tap', 'edge', (event) => {
      selectedVertex.value = null
      selectedEdge.value = event.target.data() as GraphEdge
    })
  }

  function relayout() {
    if (!cy || cy.elements().empty()) return

    const options: LayoutOptions = {
      name: 'cose',
      animate: true,
      animationThreshold: 250,
      refresh: 20,
      fit: true,
      padding: 30,
      nodeDimensionsIncludeLabels: false,
      randomize: false,
      componentSpacing: 40,
      nodeRepulsion: () => 2048,
      nodeOverlap: 4,
      idealEdgeLength: () => 32,
      edgeElasticity: () => 32,
      nestingFactor: 1.2,
      gravity: 1,
      numIter: 1000,
      initialTemp: 1000,
      coolingFactor: 0.99,
      minTemp: 1,
    } as LayoutOptions

    cy.layout(options).run()
  }

  async function loadGraph() {
    if (!props.itemUuid || loading.value) return

    loading.value = true
    selectedVertex.value = null
    selectedEdge.value = null
    try {
      initGraph()
      cy?.elements().remove()

      const response = await api.listGraph(props.itemUuid, 100)
      const vertices: GraphVertex[] = response.data?.vertices || []
      const edges: GraphEdge[] = response.data?.edges || []
      const nodes = vertices.map((item) => ({
        group: 'nodes' as const,
        data: { ...item, id: String(item.id), name: item.name || `#${item.id}` },
      }))
      const relations = edges.map((item) => ({
        group: 'edges' as const,
        data: {
          ...item,
          id: String(item.id),
          source: String(item.startId),
          target: String(item.endId),
        },
      }))

      cy?.add([...nodes, ...relations])
      isEmpty.value = !cy || cy.elements().empty()
      if (!isEmpty.value) relayout()
    } finally {
      loading.value = false
    }
  }

  watch(
    () => props.itemUuid,
    async () => {
      await nextTick()
      loadGraph()
    }
  )

  onMounted(async () => {
    await nextTick()
    initGraph()
    observer = new ResizeObserver(() => {
      cy?.resize()
      cy?.fit(undefined, 30)
    })
    if (graphRef.value) observer.observe(graphRef.value)
    loadGraph()
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    cy?.destroy()
    cy = null
  })
</script>

<style lang="less" scoped>
  .graph-detail {
    display: flex;
    min-height: 400px;
    overflow: hidden;
    border: 1px solid var(--zhimesh-border);
    background: var(--zhimesh-glass-strong);
  }

  .graph-canvas {
    width: 80%;
    height: 400px;
    min-width: 0;
  }

  .graph-inspector {
    width: 20%;
    min-width: 180px;
    height: 400px;
    padding: 14px;
    overflow-y: auto;
    border-left: 1px solid var(--zhimesh-border-subtle);
    background: var(--zhimesh-glass);
  }

  .detail-description {
    line-height: 1.7;
    word-break: break-word;
  }

  @media (max-width: 760px) {
    .graph-detail {
      flex-direction: column;
    }

    .graph-canvas,
    .graph-inspector {
      width: 100%;
    }

    .graph-inspector {
      height: auto;
      min-height: 160px;
      border-top: 1px solid var(--zhimesh-border-subtle);
      border-left: 0;
    }
  }
</style>
