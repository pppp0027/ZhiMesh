<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, provide, reactive, ref } from 'vue'
import { NButton, NDrawer, NDrawerContent, NTooltip, useMessage } from 'naive-ui'
import type { Connection, Edge, Node, NodeChange } from '@vue-flow/core'
import { ConnectionLineType, VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { AnswerNode, ClassifierNode, DocumentExtractorNode, EndNode, FaqExtractorNode, GoogleNode, HttpRequestNode, HumanFeedbackNode, KeywordExtractorNode, KnowledgeRetrievalNode, MailSendNode, SpecialNode, StartNode, SwitcherNode, TemplateNode, TextTransformNode, VariableAggregatorNode } from './components/nodes'
import CustomEdge from './components/edges/CustomEdge.vue'
import CustomEdge2 from './components/edges/CustomEdge2.vue'
import RunDetail from '@/views/workflow/components/RunDetail.vue'
import WfDefineRightPanel from '@/views/workflow/WfDefineRightPanel.vue'
import WfDefineSidebar from '@/views/workflow/WfDefineSiderbar.vue'
import { SvgIcon } from '@/components/common'
import { emptyWorkflowInfo } from '@/utils/functions'
import { createNewEdge, createNewNode } from '@/utils/workflow-util'
import { useUserStore, useWfStore } from '@/store'
import api from '@/api'
import { t } from '@/locales'
import { useBasicLayout } from '@/hooks/useBasicLayout'

interface Props {
  workflow: Workflow.WorkflowInfo
}

const props = withDefaults(defineProps<Props>(), {
  workflow: () => emptyWorkflowInfo(),
})

const NODE_PALETTE_MIN_WIDTH = 248
const NODE_PALETTE_MAX_WIDTH = 420
const showRunModal = ref<boolean>(false)
const showMobilePalette = ref(false)
const nodePaletteWidth = ref(NODE_PALETTE_MIN_WIDTH)
const resizingNodePalette = ref(false)
const workflowEditorRef = ref<HTMLElement | null>(null)
const ms = useMessage()
const submitting = ref<boolean>(false)
const autoSaving = ref<boolean>(false)
const hidePropertyPanel = ref<boolean>(true)
const isNodeDragging = ref(false)
const selectedWfNode = ref<Workflow.WorkflowNode>()
const selectedWfEdgeId = ref<string>('')
const wfStore = useWfStore()
const userStore = useUserStore()
const { isMobile } = useBasicLayout()

const nodePaletteStyle = computed(() => ({
  width: `${nodePaletteWidth.value}px`,
  flexBasis: `${nodePaletteWidth.value}px`,
}))
const connectionLineOptions = {
  // Keep the in-progress connection visually identical to the rendered edge, so it
  // does not snap from a straight/step path into a Bezier curve on drop.
  type: ConnectionLineType.Bezier,
  style: { stroke: '#234a7a', strokeWidth: 2 },
}

let previousBodyCursor = ''
let previousBodyUserSelect = ''
let autoSaveTimer: number | undefined
let renderGraphTimer: ReturnType<typeof setTimeout> | undefined
let initialFitFrame = 0
let initialViewportFitted = false

function resizeNodePalette(width: number) {
  nodePaletteWidth.value = Math.min(NODE_PALETTE_MAX_WIDTH, Math.max(NODE_PALETTE_MIN_WIDTH, width))
}

function onNodePalettePointerMove(event: PointerEvent) {
  const editorLeft = workflowEditorRef.value?.getBoundingClientRect().left || 0
  resizeNodePalette(event.clientX - editorLeft)
}

function stopNodePaletteResize() {
  if (!resizingNodePalette.value)
    return
  resizingNodePalette.value = false
  window.removeEventListener('pointermove', onNodePalettePointerMove)
  window.removeEventListener('pointerup', stopNodePaletteResize)
  document.body.style.cursor = previousBodyCursor
  document.body.style.userSelect = previousBodyUserSelect
}

function startNodePaletteResize(event: PointerEvent) {
  event.preventDefault()
  resizingNodePalette.value = true
  previousBodyCursor = document.body.style.cursor
  previousBodyUserSelect = document.body.style.userSelect
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onNodePalettePointerMove)
  window.addEventListener('pointerup', stopNodePaletteResize)
}

function resizeNodePaletteByKeyboard(offset: number) {
  resizeNodePalette(nodePaletteWidth.value + offset)
}

const saveDisabledTip = computed(() => {
  if (userStore.userInfo.uuid !== props.workflow.userUuid)
    return t('workflow.onlyCreatorCanSave')
  if (submitting.value)
    return t('workflow.saving')
  return ''
})

const { onInit, onNodesInitialized, fitView, findNode, getSelectedNodes, getSelectedEdges, onNodeClick, onEdgeClick, onNodesChange, onEdgesChange, onNodeDragStart, onNodeDragStop, addSelectedNodes, screenToFlowCoordinate } = useVueFlow()

const uw = wfStore.wfUuidToUIWorkflow.get(props.workflow.uuid) || { nodes: [] as Array<Node>, edges: [] as Array<Edge> }
wfStore.wfUuidToUIWorkflow.set(props.workflow.uuid, uw)
const uiWorkflow = reactive(uw)

function scheduleInitialFit() {
  if (initialViewportFitted || initialFitFrame || !uiWorkflow.nodes.length)
    return

  nextTick(() => {
    if (initialViewportFitted || initialFitFrame || !uiWorkflow.nodes.length)
      return
    initialFitFrame = requestAnimationFrame(async () => {
      initialFitFrame = 0
      if (initialViewportFitted || !uiWorkflow.nodes.length)
        return
      try {
        const fitted = await fitView({ padding: 0.18 })
        if (fitted)
          initialViewportFitted = true
      } catch (error) {
        console.error('Initial workflow viewport fit failed', error)
      }
    })
  })
}

function lockCanvasViewport() {
  initialViewportFitted = true
  if (initialFitFrame)
    cancelAnimationFrame(initialFitFrame)
  initialFitFrame = 0
}

function persistNodePositions(nodes: Node[]) {
  nodes.forEach((node) => {
    node.data.positionX = node.position.x
    node.data.positionY = node.position.y
    const uiNode = uiWorkflow.nodes.find(item => item.id === node.id)
    if (uiNode)
      uiNode.position = { ...node.position }
  })
}

function renderGraph() {
  // Edges created by older editor versions used a custom HTML label renderer.
  // The built-in edge renderer updates its SVG path in the same render cycle as
  // node movement, which is noticeably smoother while dragging.
  uiWorkflow.edges.forEach((edge) => {
    if (edge.type === 'special')
      edge.type = 'default'
  })
  if (uiWorkflow.nodes.length > 0) {
    scheduleInitialFit()
    return
  }

  const initX = 10
  const initY = 50
  const wfNodes = props.workflow.nodes
  const wfEdges = props.workflow.edges
  for (let i = 0; i < wfNodes.length; i++) {
    const node = wfNodes[i]
    const px = Number.isFinite(node.positionX) ? node.positionX : initX + 210 * i
    const py = Number.isFinite(node.positionY) ? node.positionY : initY
    uiWorkflow.nodes.push({
      id: node.uuid,
      type: node.wfComponent.name.toLowerCase(),
      data: node,
      position: { x: px, y: py },
    })
  }
  for (let i = 0; i < wfEdges.length; i++) {
    const wfEdge = wfEdges[i]
    const sourceNode = props.workflow.nodes.find((item: Workflow.WorkflowNode) => item.uuid === wfEdge.sourceNodeUuid)
    if (!sourceNode)
      continue
    uiWorkflow.edges.push({
      id: wfEdge.uuid,
      source: wfEdge.sourceNodeUuid,
      target: wfEdge.targetNodeUuid,
      sourceHandle: wfEdge.sourceHandle,
      type: 'default',
      animated: false,
      data: wfEdge,
    })
  }
  scheduleInitialFit()
}

onNodesChange((changes: NodeChange[]) => {
  // Position changes are intentionally committed in onNodeDragStop instead of
  // on every pointer move. Replacing the reactive node collection per frame
  // makes SVG edge updates visibly stutter on larger workflows.
  let nodeUnSelected = false
  for (const change of changes) {
    if (change.type === 'remove')
      removeWorkflowNode(change.id)
    if ('selected' in change && !change.selected && selectedWfNode.value?.uuid === change.id)
      nodeUnSelected = true
  }
  if (nodeUnSelected) {
    hidePropertyPanel.value = true
    selectedWfNode.value = undefined
  }
})

function clearBranchTarget(workflowEdge: Workflow.WorkflowEdge) {
  const sourceNode = props.workflow.nodes.find(node => node.uuid === workflowEdge.sourceNodeUuid)
  if (!sourceNode)
    return

  if (sourceNode.wfComponent.name === 'Switcher') {
    const nodeConfig = sourceNode.nodeConfig as Workflow.NodeConfigSwitcher
    const branchCase = nodeConfig.cases.find(item => item.uuid === workflowEdge.sourceHandle)
    if (branchCase)
      branchCase.target_node_uuid = ''
  } else if (sourceNode.wfComponent.name === 'Classifier') {
    const nodeConfig = sourceNode.nodeConfig as Workflow.NodeConfigClassifier
    const category = nodeConfig.categories.find(item => item.category_uuid === workflowEdge.sourceHandle)
    if (category)
      category.target_node_uuid = ''
  }
}

function removeWorkflowEdge(edgeUuid: string) {
  const workflowEdge = props.workflow.edges.find(edge => edge.uuid === edgeUuid)
  if (workflowEdge) {
    clearBranchTarget(workflowEdge)
    // Newly-added edges have no database id. They disappear locally without
    // creating an invalid delete request for an edge that was never persisted.
    if (workflowEdge.id && !props.workflow.deleteEdges.includes(edgeUuid))
      // The editor owns this mutable draft object; it is persisted only on save.
      // eslint-disable-next-line vue/no-mutating-props
      props.workflow.deleteEdges.push(edgeUuid)
    const workflowEdgeIndex = props.workflow.edges.indexOf(workflowEdge)
    if (workflowEdgeIndex >= 0)
      // eslint-disable-next-line vue/no-mutating-props
      props.workflow.edges.splice(workflowEdgeIndex, 1)
  }

  const uiEdgeIndex = uiWorkflow.edges.findIndex(edge => edge.id === edgeUuid)
  if (uiEdgeIndex >= 0)
    uiWorkflow.edges.splice(uiEdgeIndex, 1)
  if (selectedWfEdgeId.value === edgeUuid)
    selectedWfEdgeId.value = ''
}

function removeWorkflowNode(nodeOrUuid: Workflow.WorkflowNode | string) {
  const nodeUuid = typeof nodeOrUuid === 'string' ? nodeOrUuid : nodeOrUuid.uuid
  const uiNode = uiWorkflow.nodes.find(node => node.id === nodeUuid)
  const draftNode = props.workflow.nodes.find(node => node.uuid === nodeUuid)
  const workflowNode = draftNode || (typeof nodeOrUuid === 'string' ? uiNode?.data : nodeOrUuid)
  if (!workflowNode && !uiNode)
    return false
  const componentName = workflowNode?.wfComponent?.name || uiNode?.type || ''
  if (componentName.toLowerCase() === 'start')
    return false

  if (!draftNode)
    console.warn('Removing an orphan workflow canvas node', nodeUuid)

  const connectedEdgeIds = new Set<string>()
  props.workflow.edges.forEach((edge) => {
    if (edge.sourceNodeUuid === nodeUuid || edge.targetNodeUuid === nodeUuid)
      connectedEdgeIds.add(edge.uuid)
  })
  uiWorkflow.edges.forEach((edge) => {
    if (edge.source === nodeUuid || edge.target === nodeUuid)
      connectedEdgeIds.add(edge.id)
  })
  connectedEdgeIds.forEach(edgeId => removeWorkflowEdge(edgeId))

  if (workflowNode?.id && !props.workflow.deleteNodes.includes(nodeUuid))
    // The editor owns this mutable draft object; it is persisted only on save.
    // eslint-disable-next-line vue/no-mutating-props
    props.workflow.deleteNodes.push(nodeUuid)

  const workflowNodeIndex = props.workflow.nodes.findIndex(node => node.uuid === nodeUuid)
  if (workflowNodeIndex >= 0)
    // eslint-disable-next-line vue/no-mutating-props
    props.workflow.nodes.splice(workflowNodeIndex, 1)

  const uiNodeIndex = uiWorkflow.nodes.findIndex(node => node.id === nodeUuid)
  if (uiNodeIndex >= 0)
    uiWorkflow.nodes.splice(uiNodeIndex, 1)

  if (selectedWfNode.value?.uuid === nodeUuid) {
    selectedWfNode.value = undefined
    hidePropertyPanel.value = true
  }
  return true
}

provide('deleteWorkflowNode', (node: Workflow.WorkflowNode) => removeWorkflowNode(node))

onEdgesChange((changes) => {
  changes.forEach((change) => {
    if (change.type === 'remove')
      removeWorkflowEdge(change.id)
  })
})

onNodeDragStart(() => {
  isNodeDragging.value = true
})

function handleConnect(connection: Connection) {
  if (!connection.source || !connection.target || connection.source === connection.target)
    return

  const sourceHandle = connection.sourceHandle || ''
  const edgeAlreadyExists = props.workflow.edges.some(edge =>
    edge.sourceNodeUuid === connection.source
    && edge.targetNodeUuid === connection.target
    && (edge.sourceHandle || '') === sourceHandle,
  )
  if (edgeAlreadyExists)
    return

  createNewEdge({
    workflow: props.workflow,
    uiWorkflow,
    source: connection.source,
    sourceHandle,
    target: connection.target,
  })
}

onInit(scheduleInitialFit)
onNodesInitialized(scheduleInitialFit)

function onDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer)
    event.dataTransfer.dropEffect = 'move'
}

function createNodeWithoutReflow(component: Workflow.WorkflowComponent, position: { x: number; y: number }) {
  lockCanvasViewport()
  const stablePositions = new Map(
    uiWorkflow.nodes.map(node => [node.id, { ...node.position }]),
  )
  createNewNode(props.workflow, uiWorkflow, component, position)
  const addedNode = uiWorkflow.nodes[uiWorkflow.nodes.length - 1]

  nextTick(() => {
    stablePositions.forEach((stablePosition, nodeId) => {
      const node = uiWorkflow.nodes.find(item => item.id === nodeId)
      if (!node)
        return
      node.position = { ...stablePosition }
      node.data.positionX = stablePosition.x
      node.data.positionY = stablePosition.y
    })
    const graphNode = findNode(addedNode?.id)
    if (graphNode)
      addSelectedNodes([graphNode])
  })

  return addedNode
}

function onDrop(event: DragEvent) {
  const comName = event.dataTransfer?.getData('application/vueflow') as string
  const component = wfStore.getWfComponent(comName)
  if (!component)
    return
  if (comName === 'Start') {
    ms.warning(t('workflow.startNodeOnlyOne'))
    return
  }

  const position = screenToFlowCoordinate({
    x: event.clientX,
    y: event.clientY,
  })
  createNodeWithoutReflow(component, position)
}

function getAvailablePalettePosition(origin: { x: number; y: number }) {
  let position = { ...origin }
  for (let attempt = 0; attempt < 10; attempt++) {
    const overlaps = uiWorkflow.nodes.some(node =>
      Math.abs(node.position.x - position.x) < 170
      && Math.abs(node.position.y - position.y) < 100,
    )
    if (!overlaps)
      return position
    position = { x: origin.x + 36 * (attempt + 1), y: origin.y + 36 * (attempt + 1) }
  }
  return position
}

function addNodeFromPalette(component: Workflow.WorkflowComponent) {
  if (component.name === 'Start') {
    ms.warning(t('workflow.startNodeOnlyOne'))
    return
  }
  const canvas = workflowEditorRef.value?.querySelector<HTMLElement>('.vue-flow')
  const canvasRect = canvas?.getBoundingClientRect()
  const position = screenToFlowCoordinate({
    x: canvasRect ? canvasRect.left + canvasRect.width / 2 : window.innerWidth / 2,
    y: canvasRect ? canvasRect.top + canvasRect.height / 2 : window.innerHeight / 2,
  })
  createNodeWithoutReflow(component, getAvailablePalettePosition(position))
  showMobilePalette.value = false
}

onNodeDragStop(({ nodes, node }) => {
  persistNodePositions(nodes)
  isNodeDragging.value = false
  console.log('Node drag stop:', nodes, node)
})

onNodeClick(({ event, node }) => {
  selectedWfEdgeId.value = ''
  if (node.selected) {
    hidePropertyPanel.value = false
    selectedWfNode.value = node.data
  }
  console.log('Node clicked:', node, event)
})

onEdgeClick(({ event, edge }) => {
  selectedWfNode.value = undefined
  hidePropertyPanel.value = true
  selectedWfEdgeId.value = edge.id
  console.log('Edge clicked:', edge, event)
})

function isTypingTarget(target: EventTarget | null) {
  if (!(target instanceof HTMLElement))
    return false
  return Boolean(target.closest('input, textarea, [contenteditable="true"], .n-input'))
}

function isWorkflowEditorShortcutActive() {
  const editor = workflowEditorRef.value
  if (!editor || editor.offsetParent === null)
    return false
  const activeElement = document.activeElement
  return !activeElement || activeElement === document.body || editor.contains(activeElement)
}

function handleWorkflowKeydown(event: KeyboardEvent) {
  const isCutShortcut = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'x'
  if (!isCutShortcut || isTypingTarget(event.target) || !isWorkflowEditorShortcutActive())
    return

  const selectedNodes = getSelectedNodes.value.filter(node => node.data.wfComponent?.name !== 'Start')
  if (selectedNodes.length) {
    event.preventDefault()
    selectedNodes.forEach(node => removeWorkflowNode(node.data))
    return
  }
  const selectedEdgeIds = new Set(getSelectedEdges.value.map(edge => edge.id))
  if (selectedWfEdgeId.value)
    selectedEdgeIds.add(selectedWfEdgeId.value)
  if (!selectedEdgeIds.size)
    return
  event.preventDefault()
  selectedEdgeIds.forEach(edgeId => removeWorkflowEdge(edgeId))
}

function onRun() {
  showRunModal.value = true
}

async function onSave(silent = false) {
  if (submitting.value || autoSaving.value || userStore.userInfo.uuid !== props.workflow.userUuid)
    return

  if (silent)
    autoSaving.value = true
  else
    submitting.value = true
  try {
    const { data: updatedWorkflow } = await api.workflowUpdate(props.workflow)
    if (!silent)
      ms.success(t('common.saveSuccessTip'))
    wfStore.updateNodesAndEdgesId(props.workflow.uuid, updatedWorkflow)
  } catch (e) {
    console.log(e)
  } finally {
    if (silent)
      autoSaving.value = false
    else
      submitting.value = false
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleWorkflowKeydown)
  autoSaveTimer = window.setInterval(() => {
    onSave(true).catch(error => console.error('Workflow auto-save failed', error))
  }, 3 * 60 * 1000)
  nextTick(() => {
    if (wfStore.wfComponents.length === 0) {
      renderGraphTimer = setTimeout(() => {
        renderGraphTimer = undefined
        renderGraph()
      }, 600)
    } else {
      renderGraph()
    }
  })
})

onUnmounted(() => {
  stopNodePaletteResize()
  if (autoSaveTimer)
    window.clearInterval(autoSaveTimer)
  if (renderGraphTimer)
    clearTimeout(renderGraphTimer)
  if (initialFitFrame)
    cancelAnimationFrame(initialFitFrame)
  initialFitFrame = 0
  renderGraphTimer = undefined
  window.removeEventListener('keydown', handleWorkflowKeydown)
  console.log('workflow define unmounted')
})
</script>

<template>
  <div class="chat-box flex flex-col w-full h-full">
    <main class="flex-1 overflow-hidden">
      <div ref="workflowEditorRef" class="workflow-editor h-full">
        <button
          v-if="isMobile && showMobilePalette"
          type="button"
          class="workflow-palette-backdrop"
          :aria-label="t('common.cancel')"
          @click="showMobilePalette = false"
        />
        <div
          class="workflow-node-palette-shell"
          :class="{ 'is-mobile-open': showMobilePalette }"
          :style="isMobile ? undefined : nodePaletteStyle"
        >
          <button
            v-if="isMobile"
            type="button"
            class="workflow-mobile-palette-close"
            :aria-label="t('common.cancel')"
            @click="showMobilePalette = false"
          >
            <SvgIcon icon="ri:close-line" />
          </button>
          <aside class="workflow-node-palette">
            <WfDefineSidebar @add="addNodeFromPalette" />
          </aside>
          <button
            class="workflow-palette-resizer"
            :class="{ 'is-resizing': resizingNodePalette }"
            type="button"
            :aria-label="t('workflow.nodeLibrary')"
            @pointerdown="startNodePaletteResize"
            @keydown.left.prevent="resizeNodePaletteByKeyboard(-16)"
            @keydown.right.prevent="resizeNodePaletteByKeyboard(16)"
          />
        </div>
        <section class="workflow-canvas" :class="{ 'is-node-dragging': isNodeDragging }" @drop="onDrop">
          <VueFlow
            :nodes="uiWorkflow.nodes" :edges="uiWorkflow.edges"
            :nodes-connectable="true" :nodes-draggable="true" :edges-updatable="true" :edges-selectable="true"
            :apply-default="true"
            :delete-key-code="null"
            :connection-radius="28" :edge-updater-radius="24" connect-on-click
            :connection-line-options="connectionLineOptions"
            @dragover="onDragOver" @connect="handleConnect"
          >
            <Background />
            <template #node-start="nodeProps"><StartNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-end="nodeProps"><EndNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-answer="nodeProps"><AnswerNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-classifier="nodeProps"><ClassifierNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-documentextractor="nodeProps"><DocumentExtractorNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-knowledgeretrieval="nodeProps"><KnowledgeRetrievalNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-keywordextractor="nodeProps"><KeywordExtractorNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-google="nodeProps"><GoogleNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-switcher="nodeProps"><SwitcherNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-template="nodeProps"><TemplateNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-texttransform="nodeProps"><TextTransformNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-variableaggregator="nodeProps"><VariableAggregatorNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-faqextractor="nodeProps"><FaqExtractorNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-humanfeedback="nodeProps"><HumanFeedbackNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-mailsend="nodeProps"><MailSendNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-httprequest="nodeProps"><HttpRequestNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #node-special="nodeProps"><SpecialNode v-bind="nodeProps" :workflow="workflow" /></template>
            <template #edge-custom="edgeProps"><CustomEdge v-bind="edgeProps" /></template>
            <template #edge-custom2="edgeProps"><CustomEdge2 v-bind="edgeProps" /></template>
          </VueFlow>

          <div v-if="hidePropertyPanel" class="workflow-canvas-toolbar flex items-center gap-2">
            <NButton v-if="isMobile" size="small" quaternary class="workflow-palette-button" @click="showMobilePalette = true">
              <template #icon><SvgIcon icon="ri:layout-left-line" /></template>
              {{ t('workflow.nodeLibrary') }}
            </NButton>
            <NButton :disabled="submitting" size="small" quaternary class="workflow-run-button" @click="onRun">
              <template #icon><SvgIcon icon="carbon:play-outline" /></template>
              {{ t('workflow.runDebug') }}
            </NButton>
            <NTooltip v-if="saveDisabledTip" :disabled="!saveDisabledTip">
              <template #trigger>
                <NButton :disabled="!!saveDisabledTip" :loading="submitting" type="primary" size="small" class="workflow-save-button" @click="() => onSave()">
                  <template #icon><SvgIcon icon="ri:save-3-line" /></template>
                  {{ t('workflow.save') }}
                </NButton>
              </template>
              {{ saveDisabledTip }}
            </NTooltip>
            <NButton v-else :loading="submitting" type="primary" size="small" class="workflow-save-button" @click="() => onSave()">
              <template #icon><SvgIcon icon="ri:save-3-line" /></template>
              {{ t('workflow.save') }}
            </NButton>
          </div>

          <WfDefineRightPanel
            :workflow="workflow" :ui-workflow="uiWorkflow"
            :hide-property-panel="hidePropertyPanel" :wf-node="selectedWfNode"
            @close="hidePropertyPanel = true"
          />
        </section>
      </div>
    </main>
    <NDrawer
      v-model:show="showRunModal"
      placement="right"
      :width="isMobile ? '100%' : 640"
      :show-mask="false"
      :mask-closable="false"
      :block-scroll="false"
      :trap-focus="false"
      class="workflow-debug-drawer"
    >
      <NDrawerContent :title="t('workflow.runDebug')" closable class="workflow-debug-drawer__content">
        <RunDetail :workflow="workflow" :show-header="false" />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style>
@import '@vue-flow/core/dist/style.css';
@import '@vue-flow/core/dist/theme-default.css';

.workflow-editor {
  position: relative;
  display: flex;
  min-height: 0;
  background: var(--zhimesh-page-bg);
}

.workflow-node-palette-shell {
  position: relative;
  z-index: 4;
  width: 248px;
  min-width: 248px;
  max-width: 420px;
  flex: 0 0 248px;
}

.workflow-node-palette {
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-right: 1px solid var(--zhimesh-border-subtle);
  background: var(--zhimesh-glass-strong);
  box-shadow: 4px 0 18px rgba(15, 23, 42, 0.04);
}

.workflow-palette-resizer {
  position: absolute;
  top: 0;
  right: -8px;
  bottom: 0;
  z-index: 5;
  width: 8px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: col-resize;
}

.workflow-palette-resizer::after {
  position: absolute;
  top: 50%;
  right: 2px;
  width: 3px;
  height: 54px;
  border-radius: 999px;
  background: var(--zhimesh-border);
  content: '';
  opacity: 0;
  transform: translateY(-50%);
  transition: opacity 0.16s ease, background 0.16s ease;
}

.workflow-palette-resizer:hover::after,
.workflow-palette-resizer:focus-visible::after,
.workflow-palette-resizer.is-resizing::after {
  background: var(--zhimesh-primary);
  opacity: 1;
}

.workflow-canvas {
  position: relative;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  background: var(--zhimesh-page-bg);
}

.workflow-canvas-toolbar {
  position: absolute;
  z-index: 20;
  top: 8px;
  right: 8px;
  padding: 4px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 12px;
  background: var(--zhimesh-glass-strong);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.09);
}

.workflow-run-button {
  color: var(--zhimesh-primary);
  border-radius: 8px;
}

.workflow-save-button {
  border-radius: 8px;
  box-shadow: none;
}

/* Debugging stays beside the canvas instead of taking over the lower half. */
.workflow-debug-drawer {
  box-shadow: -16px 0 38px rgba(15, 23, 42, 0.14);
}

.workflow-debug-drawer .n-drawer-content {
  background: var(--zhimesh-glass-soft);
}

.workflow-debug-drawer .n-drawer-body-content-wrapper {
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.vue-flow__node {
  width: 190px;
  min-width: 190px;
  padding: 6px;
  border: 1px solid var(--zhimesh-border);
  border-radius: 10px;
  background: var(--zhimesh-glass);
  color: var(--zhimesh-text);
  box-shadow: var(--zhimesh-shadow-soft);
  contain: layout style;
}

.vue-flow__node.selected {
  border: 1.5px solid var(--zhimesh-primary);
  box-shadow: 0 0 0 3px var(--zhimesh-focus-ring), var(--zhimesh-shadow-soft);
}

.workflow-canvas.is-node-dragging .vue-flow__node {
  box-shadow: none;
}

.workflow-canvas.is-node-dragging .vue-flow__node.selected {
  box-shadow: 0 0 0 2px var(--zhimesh-focus-ring);
}

/*
 * The canvas owns the node position while a pointer is down.  In particular,
 * do not allow application-level visual transitions to interpolate a new
 * transform or SVG path after Vue Flow has calculated it for the current
 * pointer event.  An interpolated node is exactly what makes an edge appear
 * to chase its source/target node.
 */
.workflow-canvas.is-node-dragging .vue-flow__node,
.workflow-canvas.is-node-dragging .vue-flow__edge-path,
.workflow-canvas.is-node-dragging .vue-flow__edge-interaction,
.workflow-canvas.is-node-dragging .vue-flow__connection-path {
  transition: none !important;
}

.workflow-canvas .vue-flow__edges {
  contain: layout style;
}

.vue-flow__node.selected .vue-flow__handle {
  background: var(--zhimesh-primary);
}

.vue-flow__handle {
  width: 10px;
  height: 10px;
  border: 2px solid var(--zhimesh-glass-strong);
  border-radius: 50%;
  background: var(--zhimesh-text-muted);
  cursor: crosshair;
  box-shadow: 0 0 0 1px var(--zhimesh-border);
  transition: background 0.15s ease, box-shadow 0.15s ease;
}

.vue-flow__handle::after {
  position: absolute;
  inset: -9px;
  border-radius: 50%;
  content: '';
}

.vue-flow__handle:hover,
.vue-flow__handle.connecting,
.vue-flow__handle.valid {
  background: var(--zhimesh-primary);
  box-shadow: 0 0 0 4px var(--zhimesh-focus-ring);
}

.vue-flow__edge.selected .vue-flow__edge-path {
  stroke: var(--zhimesh-primary);
  stroke-width: 2;
}

.vue-flow__node .header {
  height: 30px;
  line-height: 30px;
  margin-bottom: 5px;
  text-align: center;
  font-weight: 600;
}

.vue-flow__node .content_line {
  min-height: 26px;
  height: auto;
  line-height: 26px;
  margin-bottom: 4px;
  padding: 0 5px;
  border-radius: 6px;
  border: 1px solid var(--zhimesh-border-subtle);
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-soft);
  font-size: 11px;
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.vue-flow__node .workflow-node-copy {
  min-height: 30px;
  overflow: hidden;
  border: 1px solid var(--zhimesh-border-subtle);
  border-radius: 6px;
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-soft);
  line-height: 1.55;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.vue-flow__node .workflow-node-branch {
  overflow: hidden;
  border: 1px solid var(--zhimesh-info-surface-border);
  color: var(--zhimesh-info-surface-text);
  background: var(--zhimesh-info-surface);
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vue-flow__node .workflow-node-condition {
  border: 1px solid var(--zhimesh-border-subtle);
  color: var(--zhimesh-text);
  background: var(--zhimesh-glass-soft);
}

.vue-flow__node .workflow-node-condition__value {
  min-width: 0;
  color: var(--zhimesh-text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vue-flow__node .workflow-node-condition__operator {
  flex: 0 0 auto;
  color: var(--zhimesh-text);
  font-weight: 650;
  white-space: nowrap;
}

.vue-flow__node .workflow-node-condition__join {
  color: var(--zhimesh-primary);
  font-weight: 700;
}

.right_side .n-scrollbar {
  min-width: 360px !important;
}

@media (max-width: 900px) {
  .workflow-node-palette-shell {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 20;
    width: 248px !important;
    flex-basis: 248px !important;
    box-shadow: 12px 0 28px rgba(15, 23, 42, 0.16);
  }

  .workflow-palette-resizer {
    display: none;
  }

  .workflow-canvas-toolbar {
    right: 8px;
  }

}

@media (max-width: 767px) {
  .workflow-node-palette-shell {
    z-index: 40;
    width: min(320px, calc(100vw - 48px)) !important;
    max-width: calc(100vw - 48px);
    flex-basis: auto !important;
    opacity: 0;
    pointer-events: none;
    transform: translateX(-104%);
    transition: opacity 180ms ease, transform 240ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  .workflow-node-palette-shell.is-mobile-open {
    opacity: 1;
    pointer-events: auto;
    transform: translateX(0);
  }

  .workflow-palette-backdrop {
    position: absolute;
    inset: 0;
    z-index: 35;
    border: 0;
    background: rgba(8, 15, 23, 0.42);
  }

  .workflow-mobile-palette-close {
    position: absolute;
    top: 8px;
    right: 8px;
    z-index: 45;
    display: grid;
    width: 40px;
    height: 40px;
    padding: 0;
    place-items: center;
    border: 1px solid var(--zhimesh-border-subtle);
    border-radius: 12px;
    color: var(--zhimesh-text);
    background: var(--zhimesh-glass-strong);
    font-size: 20px;
  }

  .workflow-canvas-toolbar {
    right: 6px;
    left: 6px;
    justify-content: flex-end;
    overflow-x: auto;
  }

  .workflow-canvas-toolbar :deep(.n-button) {
    min-height: 40px;
    white-space: nowrap;
  }

  .right_side .n-scrollbar {
    min-width: 0 !important;
  }
}
</style>
