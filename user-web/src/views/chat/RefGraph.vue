<script setup lang='ts'>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NButton, NDivider, NFlex } from 'naive-ui'
import cytoscape from 'cytoscape'
import { useChatStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
interface Props {
  msgUuid: string
}
const props = withDefaults(defineProps<Props>(), {
  msgUuid: '',
})
const chatStore = useChatStore()
const loading = ref<boolean>(false)
const isEmpty = ref<boolean>(false)
const selectedVertex = ref<KnowledgeBase.KbVertex | null>()
const selectedEdge = ref<KnowledgeBase.KbEdge | null>()
const graphRef = ref<KnowledgeBase.QaRecordGraphRef | null>({ edges: [], vertices: [] })
const graphContainer = ref<HTMLElement | null>(null)
let cy: any = null
let resizeObserver: ResizeObserver | null = null

function getAndRenderGraph() {
  graphRef.value = chatStore.getGraphRef(props.msgUuid)
  if (!graphRef.value || (graphRef.value.vertices.length === 0 && graphRef.value.edges.length === 0))
    loadGraph()
  else
    parseAndRender(graphRef.value)
}

function parseAndRender(graphRef: KnowledgeBase.QaRecordGraphRef) {
  cy.$('node').remove()
  cy.$('edge').remove()
  const nodes = graphRef.vertices.map((item) => {
    return { group: 'nodes', data: { id: `${item.id}`, name: item.name, description: item.description } }
  })
  const edges = graphRef.edges.map((item) => {
    return { group: 'edges', data: { id: `${item.id}`, label: `${item.label}`, source: `${item.startId}`, target: `${item.endId}`, description: item.description } }
  })
  renderGraph(nodes, edges)
}

function renderGraph(nodes: any, edges: any) {
  if (nodes.length > 0) {
    cy.add(nodes)
    cy.nodes().on('click', (e: any) => {
      const clickedNode = e.target
      selectedVertex.value = clickedNode.data()
      selectedEdge.value = null
    })
  }
  if (edges.length > 0) {
    cy.add(edges)
    cy.edges().on('click', (e: any) => {
      const clickedNode = e.target
      selectedVertex.value = null
      selectedEdge.value = clickedNode.data()
    })
  }
  nextTick(() => {
    cy.resize()
    relayout()
  })
}

async function loadGraph() {
  const curQaRecordUuid = props.msgUuid
  if (chatStore.isLoadingGraphRef(curQaRecordUuid))
    return

  chatStore.setLoadingGraphRef(curQaRecordUuid, true)
  try {
    const resp = await api.messageGraphRef<KnowledgeBase.KbItemGraphResp>(curQaRecordUuid)
    if (resp.data)
      chatStore.setKnowledgeGraphRef(curQaRecordUuid, { ...resp.data })
  } finally {
    chatStore.setLoadingGraphRef(curQaRecordUuid, false)

    // 加载结束后判断是否还停留在加载时的页面，是的话则渲染图形
    if (curQaRecordUuid === props.msgUuid) {
      const loadedRef = chatStore.getGraphRef(curQaRecordUuid)
      if (loadedRef)
        parseAndRender(loadedRef)
    }

    loading.value = chatStore.isLoadingGraphRef(props.msgUuid)
  }
}

function initCy() {
  const isMobile = window.matchMedia('(max-width: 767px)').matches
  cy = cytoscape({
    container: graphContainer.value,
    elements: [],
    ...(isMobile
      ? {
          minZoom: 0.35,
          maxZoom: 3,
          wheelSensitivity: 0.18,
          boxSelectionEnabled: false,
        }
      : {}),
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
}

function relayout() {
  if (!cy)
    return
  const isMobile = window.matchMedia('(max-width: 767px)').matches
  const layout = cy.layout(isMobile
    ? {
        name: 'cose',
        animate: false,
        fit: true,
        padding: 28,
        nodeRepulsion: 1800,
        idealEdgeLength: 48,
      }
    : { name: 'cose' })
  layout.run()
  isEmpty.value = cy.elements().length === 0
}

function resizeGraph() {
  if (!cy || !graphContainer.value || graphContainer.value.clientWidth === 0)
    return
  cy.resize()
  if (cy.elements().length > 0)
    cy.fit(undefined, 28)
}

watch(() => props.msgUuid, (nextUuid, previousUuid) => {
  if (!nextUuid || nextUuid === previousUuid)
    return
  nextTick(() => {
    selectedVertex.value = null
    selectedEdge.value = null
    getAndRenderGraph()
  })
})

onMounted(() => {
  nextTick(() => {
    initCy()
    if (graphContainer.value) {
      resizeObserver = new ResizeObserver(() => requestAnimationFrame(resizeGraph))
      resizeObserver.observe(graphContainer.value)
    }
    getAndRenderGraph()
  })
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  cy?.destroy()
  cy = null
})
</script>

<template>
  <div class="zhimesh-graph-layout">
    <div ref="graphContainer" class="zhimesh-graph-canvas" aria-label="Knowledge graph">
      <div v-show="isEmpty" class="zhimesh-graph-empty">
        {{ t('common.noData') }}
      </div>
    </div>
    <div class="zhimesh-graph-inspector">
      <div class="zhimesh-graph-toolbar">
        <NButton v-show="!isEmpty" class="zhimesh-graph-action" size="small" :loading="loading" type="info" ghost @click="relayout">
          {{ t('chat.relayout') }}
        </NButton>
      </div>
      <NFlex v-if="selectedVertex" vertical>
        <NDivider title-placement="left">
          {{ t('chat.entity') }}
        </NDivider>
        <div>{{ selectedVertex.id }}</div>
        <NDivider title-placement="left">
          {{ t('common.name') }}
        </NDivider>
        <div>{{ selectedVertex.name }}</div>
        <NDivider title-placement="left">
          {{ t('common.description') }}
        </NDivider>
        <div>{{ selectedVertex.description }}</div>
      </NFlex>
      <NFlex v-if="selectedEdge" vertical>
        <NDivider title-placement="left">
          {{ t('chat.relation') }}
        </NDivider>
        <div>{{ selectedEdge.id }}</div>
        <NDivider title-placement="left">
          {{ t('common.description') }}
        </NDivider>
        <div>{{ selectedEdge.description }}</div>
      </NFlex>
    </div>
  </div>
</template>

<style scoped lang="less">
.zhimesh-graph-canvas {
  position: relative;
  border: 1px solid var(--zhimesh-border);
}

.zhimesh-graph-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--zhimesh-text-muted);
  font-size: 13px;
  pointer-events: none;
}
</style>
