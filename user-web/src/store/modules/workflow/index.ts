import { defineStore } from 'pinia'
import { emptyWorkflowInfo } from '@/utils/functions'
import { router } from '@/router'

export const useWfStore = defineStore('wf-store', {
  state: (): Workflow.WorkflowState => {
    return {
      showCreateOrEditView: false,
      createOrEditWfUuid: '',
      selectedType: 'mine',
      activeWorkflowInfo: emptyWorkflowInfo(),
      activeUuid: 'default',
      wfComponents: [],
      wfUuidToUIWorkflow: new Map<string, Workflow.UIWorkflow>(),
      myWorkflows: [],
      publicWorkflows: [],
      loadingMyWorkflows: false,
      loadingPublicWorkflows: false,
      wfUuidToWfRuntimePageMeta: new Map<string, Workflow.WfRuntimePageMeta>(),
      wfUuidToWfRuntimes: new Map<string, Workflow.WorkflowRuntime[]>(),
      operators: [],
      submitting: false,
    }
  },

  getters: {
    getWfRuntimes(state: Workflow.WorkflowState) {
      return (wfUuid: string) => {
        const records = state.wfUuidToWfRuntimes.get(wfUuid)
        if (records)
          return records

        return []
      }
    },
    getWfRuntimePageMeta(state: Workflow.WorkflowState) {
      return (wfUuid: string) => state.wfUuidToWfRuntimePageMeta.get(wfUuid)
    },
    getStartOrFirstNode(_state: Workflow.WorkflowState) {
      return (wfUuid: string) => {
        const wf = this.getWorkflowInfo(wfUuid)
        if (!wf)
          return undefined
        const start = wf.nodes.find(item => item.wfComponent.name === 'Start')
        if (start)
          return start

        return wf.nodes[0]
      }
    },
    getStartNode(_state: Workflow.WorkflowState) {
      return (wfUuid: string) => {
        const wf = this.getWorkflowInfo(wfUuid)
        if (!wf)
          return undefined
        return wf.nodes.find(item => item.wfComponent && item.wfComponent.name === 'Start')
      }
    },
    getStartNodeByWfId(_state: Workflow.WorkflowState) {
      return (wfId: string) => {
        const wf = this.getWorkflowInfoById(wfId)
        if (!wf)
          return undefined
        return wf.nodes.find(item => item.wfComponent.name === 'Start')
      }
    },
    getWorkflowInfo(state: Workflow.WorkflowState) {
      return (wfUuid: string) => {
        const wf = state.myWorkflows.find(item => item.uuid === wfUuid)
        if (wf)
          return wf
        return state.publicWorkflows.find(item => item.uuid === wfUuid)
      }
    },
    getWorkflowInfoById(state: Workflow.WorkflowState) {
      return (id: string) => {
        const wf = state.myWorkflows.find(item => item.id === id)
        if (wf)
          return wf
        return state.publicWorkflows.find(item => item.id === id)
      }
    },
    getWfComponent(state: Workflow.WorkflowState) {
      return (name: string) => {
        return state.wfComponents.find(item => item.name === name)
      }
    },
    getOperatorDesc(state: Workflow.WorkflowState) {
      return (name: string) => {
        return state.operators.find(item => item.name === name)?.desc || ''
      }
    },
    getWfRuntime(state: Workflow.WorkflowState) {
      return (wfRuntimeUuid: string) => {
        let wfRuntime = null
        for (const rts of state.wfUuidToWfRuntimes.values()) {
          wfRuntime = rts.find((item: { uuid: string }) => item.uuid === wfRuntimeUuid)
          if (wfRuntime)
            break
        }
        if (!wfRuntime) {
          console.log(`wfRuntime not found: ${wfRuntimeUuid}`)
          return null
        }
        return wfRuntime
      }
    },
    getRuntimeNode(_state: Workflow.WorkflowState) {
      return (wfRuntimeUuid: string, runtimeNodeUuid: string) => {
        const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
        if (!wfRuntime)
          return null

        const runtimeNode = wfRuntime.nodes.find((item: { uuid: string }) => item.uuid === runtimeNodeUuid)
        if (!runtimeNode)
          console.log(`runtimeNode not found: ${runtimeNodeUuid}`)

        return runtimeNode
      }
    },
  },

  actions: {
    setShowCreateView(status: boolean, wfUuid: string) {
      console.log(`setShowCreateView: ${status}, ${wfUuid}`)
      this.showCreateOrEditView = status
      this.createOrEditWfUuid = wfUuid
    },
    setOperators(operators: Workflow.Operator[]) {
      this.operators = operators
    },
    setActive(wfUuid: string) {
      this.activeUuid = wfUuid
      const selected = this.getWorkflowInfo(wfUuid)
      if (selected)
        this.activeWorkflowInfo = selected
      else
        console.log(`setActive: ${wfUuid} workflow not found`)
    },
    setActiveAndGo(wfUuid: string, defaultViewType?: string) {
      this.setActive(wfUuid)
      this.reloadRoute(wfUuid, defaultViewType)
    },
    setLoadingMyWorkflows(status: boolean) {
      this.loadingMyWorkflows = status
    },
    setLoadingPublicWorkflows(status: boolean) {
      this.loadingPublicWorkflows = status
    },
    ensureWfRuntimePageMeta(wfUuid: string) {
      let meta = this.wfUuidToWfRuntimePageMeta.get(wfUuid)
      if (!meta) {
        meta = { total: 0, nextPage: 1, loadedAll: false, loading: false, error: '', loadedAt: 0 }
        this.wfUuidToWfRuntimePageMeta.set(wfUuid, meta)
      }
      return meta
    },
    setLoadingRuntimes(wfUuid: string, status: boolean) {
      this.ensureWfRuntimePageMeta(wfUuid).loading = status
    },
    setWfRuntimePageError(wfUuid: string, error: string) {
      this.ensureWfRuntimePageMeta(wfUuid).error = error
    },
    setWorkflowComponents(components: Workflow.WorkflowComponent[]) {
      this.wfComponents = components
    },
    addWorkflowAndActive(info: Workflow.WorkflowInfo) {
      this.initWorkflowFields(info)
      this.myWorkflows.unshift(info)
      this.setActiveAndGo(info.uuid, 'workflowDefine')
    },
    appendWorkflows(infos: Workflow.WorkflowInfo[], isMine: boolean) {
      const workflows = isMine ? this.myWorkflows : this.publicWorkflows
      infos.forEach((workflow) => {
        if (workflows.findIndex(wf => wf.uuid === workflow.uuid) !== -1)
          return

        this.initWorkflowFields(workflow)
        workflows.push(workflow)
      })
    },
    updateBaseInfo(uuid: string, info: { title: string; remark: string; isPublic: boolean }) {
      this.myWorkflows.forEach((item) => {
        if (item.uuid === uuid)
          Object.assign(item, { title: info.title, remark: info.remark, isPublic: info.isPublic })
      })
    },
    initWorkflowFields(workflow: Workflow.WorkflowInfo) {
      workflow.nodes.forEach((node) => {
        node.workflowUuid = workflow.uuid
        node.sourceHandleIds = []
        const wfComponent = this.wfComponents.find(component => component.id === node.workflowComponentId)
        if (wfComponent)
          node.wfComponent = wfComponent

        if (!node.inputConfig)
          node.inputConfig = { user_inputs: [], ref_inputs: [] }
      })
      workflow.edges.forEach((edge) => {
        edge.workflowUuid = workflow.uuid
      })
      workflow.deleteEdges = []
      workflow.deleteNodes = []
    },
    updateNodesAndEdges(uuid: string, info: Workflow.WorkflowInfo) {
      this.myWorkflows.forEach((item) => {
        if (item.uuid === uuid) {
          item.nodes.forEach((node) => {
            const nodeInfo = info.nodes.find(n => n.uuid === node.uuid)
            if (nodeInfo)
              Object.assign(node, { ...nodeInfo })
          })
          item.edges.forEach((edge) => {
            const edgeInfo = info.edges.find(e => e.uuid === edge.uuid)
            if (edgeInfo)
              Object.assign(edge, { ...edgeInfo })
          })
        }
      })
    },
    updateNodesAndEdgesId(uuid: string, updatedWorkflow: Workflow.WorkflowInfo) {
      this.myWorkflows.forEach((item) => {
        if (item.uuid === uuid) {
          item.nodes.forEach((node) => {
            if (!node.id) {
              const updatedNodeInfo = updatedWorkflow.nodes.find(updatedNode => updatedNode.uuid === node.uuid)
              if (updatedNodeInfo)
                node.id = updatedNodeInfo.id
            }
          })
          item.edges.forEach((edge) => {
            if (!edge.id) {
              const edgeInfo = updatedWorkflow.edges.find(updatedEdge => updatedEdge.uuid === edge.uuid)
              if (edgeInfo)
                edge.id = edgeInfo.id
            }
          })
        }
      })
    },
    setWorkflowPublic(uuid: string, publicOrNot: boolean) {
      const idx = this.myWorkflows.findIndex((item: { uuid: string }) => item.uuid === uuid)
      if (idx < 0)
        return

      this.myWorkflows[idx].isPublic = publicOrNot
      if (publicOrNot) {
        const publicIndex = this.publicWorkflows.findIndex(item => item.uuid === uuid)
        if (publicIndex >= 0)
          this.publicWorkflows[publicIndex] = this.myWorkflows[idx]
        else
          this.publicWorkflows.push(this.myWorkflows[idx])
      } else {
        this.publicWorkflows = this.publicWorkflows.filter((item: { uuid: string }) => item.uuid !== uuid)
      }
    },
    deleteWorkflow(uuid: string) {
      const idx = this.myWorkflows.findIndex((item: { uuid: string }) => item.uuid === uuid)
      if (idx !== -1)
        this.myWorkflows.splice(idx, 1)
    },
    updateWfNodeTitle(wfUuid: string, nodeUuid: string, newNodeTitle: string) {
      this.getWorkflowInfo(wfUuid)?.nodes.forEach((node) => {
        if (node.uuid === nodeUuid)
          node.title = newNodeTitle
      })
    },
    updateWfNode(wfUuid: string, nodeUuid: string, newNode: Workflow.WorkflowNode) {
      this.getWorkflowInfo(wfUuid)?.nodes.forEach((node) => {
        if (node.uuid === nodeUuid)
          Object.assign(node, { ...newNode })
      })
    },
    addRefInputToNode(wfUuid: string, nodeUuid: string, newInput: Workflow.NodeIORefDinition) {
      this.getWorkflowInfo(wfUuid)?.nodes.forEach((node) => {
        if (node.uuid === nodeUuid)
          node.inputConfig.ref_inputs.push(newInput)
      })
    },
    addUserInputToNode(wfUuid: string, nodeUuid: string, newInput: Workflow.NodeIODefinition) {
      this.getWorkflowInfo(wfUuid)?.nodes.forEach((node) => {
        if (node.uuid === nodeUuid)
          node.inputConfig.user_inputs.push(newInput)
      })
    },
    deleteRefInput(wfUuid: string, nodeUuid: string, idx: number) {
      this.getWorkflowInfo(wfUuid)?.nodes.forEach((node) => {
        if (node.uuid === nodeUuid)
          node.inputConfig.ref_inputs.splice(idx, 1)
      })
    },
    deleteUserInput(wfUuid: string, nodeUuid: string, idx: number) {
      this.getWorkflowInfo(wfUuid)?.nodes.forEach((node) => {
        if (node.uuid === nodeUuid)
          node.inputConfig.user_inputs.splice(idx, 1)
      })
    },
    initWfRuntime(wfRuntime: Workflow.WorkflowRuntime) {
      const hadDetailPayload = wfRuntime.input !== undefined || wfRuntime.output !== undefined
      if (!wfRuntime.input)
        wfRuntime.input = {}

      if (!wfRuntime.output)
        wfRuntime.output = {}

      if (!wfRuntime.nodes)
        wfRuntime.nodes = []
      if (wfRuntime.detailLoaded === undefined)
        wfRuntime.detailLoaded = hadDetailPayload
    },
    replaceWfRuntimePage(wfUuid: string, wfRuntimes: Workflow.WorkflowRuntime[], total: number) {
      const existing = this.wfUuidToWfRuntimes.get(wfUuid) || []
      const existingByUuid = new Map(existing.map(runtime => [runtime.uuid, runtime]))
      const firstPage = wfRuntimes.map((runtime) => {
        const cached = existingByUuid.get(runtime.uuid)
        if (cached) {
          const cachedDetail = {
            input: cached.input,
            output: cached.output,
            nodes: cached.nodes,
            detailLoaded: cached.detailLoaded,
          }
          return Object.assign(cached, runtime, cachedDetail)
        }
        this.initWfRuntime(runtime)
        return runtime
      })
      const firstPageUuids = new Set(firstPage.map(runtime => runtime.uuid))
      const retained = existing.filter(runtime => !firstPageUuids.has(runtime.uuid))
      this.wfUuidToWfRuntimes.set(wfUuid, [...firstPage, ...retained].slice(0, total))

      const meta = this.ensureWfRuntimePageMeta(wfUuid)
      meta.total = total
      meta.nextPage = 2
      meta.loadedAll = firstPage.length >= total
      meta.loadedAt = Date.now()
      meta.error = ''
    },
    appendWfRuntimePage(wfUuid: string, wfRuntimes: Workflow.WorkflowRuntime[], total: number) {
      const records = this.wfUuidToWfRuntimes.get(wfUuid) || []
      const known = new Set(records.map(runtime => runtime.uuid))
      wfRuntimes.forEach((runtime) => {
        this.initWfRuntime(runtime)
        if (!known.has(runtime.uuid)) {
          records.push(runtime)
          known.add(runtime.uuid)
        }
      })
      this.wfUuidToWfRuntimes.set(wfUuid, records)
      const meta = this.ensureWfRuntimePageMeta(wfUuid)
      meta.total = total
      meta.nextPage += 1
      meta.loadedAll = records.length >= total || wfRuntimes.length === 0
      meta.loadedAt = Date.now()
      meta.error = ''
    },
    setWfRuntimeNodes(wfRuntimeUuid: string, nodes: Workflow.WfRuntimeNode[]) {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (!wfRuntime)
        return

      const wfNodes = this.getWorkflowInfoById(wfRuntime.workflowId)?.nodes
      if (!wfNodes) {
        console.error('setWfRuntimeNodes wfNodes not found')
        return
      }
      const existingByUuid = new Map(wfRuntime.nodes.map(node => [node.uuid, node]))
      const nextNodes = nodes.map((node) => {
        const cached = existingByUuid.get(node.uuid)
        if (cached?.detailLoaded)
          node = Object.assign(cached, node, { input: cached.input, output: cached.output, detailLoaded: true })
        if (!node.input)
          node.input = {}
        if (!node.output)
          node.output = {}
        const wfNode = wfNodes.find(n => n.id === node.nodeId)
        if (!wfNode) {
          console.error('setWfRuntimeNodes wfNode not found')
        } else {
          node.nodeUuid = wfNode.uuid
          node.nodeTitle = wfNode.title
          node.wfComponent = wfNode.wfComponent
        }
        node.wfRuntimeUuid = wfRuntime.uuid
        if (node.detailLoaded === undefined)
          node.detailLoaded = false
        return node
      })
      wfRuntime.nodes.splice(0, wfRuntime.nodes.length, ...nextNodes)
      wfRuntime.nodesLoaded = true
    },
    setWfRuntimeDetail(wfRuntimeUuid: string, detail: Workflow.WorkflowRuntime) {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (!wfRuntime)
        return
      const nodes = wfRuntime.nodes
      Object.assign(wfRuntime, detail)
      wfRuntime.nodes = nodes
      wfRuntime.input ||= {}
      wfRuntime.output ||= {}
      wfRuntime.detailLoaded = true
      wfRuntime.detailLoading = false
      wfRuntime.detailError = ''
    },
    setWfRuntimeDetailState(wfRuntimeUuid: string, loading: boolean, error = '') {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (wfRuntime) {
        wfRuntime.detailLoading = loading
        wfRuntime.detailError = error
      }
    },
    setWfRuntimeNodeDetail(wfRuntimeUuid: string, detail: Workflow.WfRuntimeNode) {
      const runtimeNode = this.getRuntimeNode(wfRuntimeUuid, detail.uuid)
      if (!runtimeNode)
        return
      const presentation = {
        wfComponent: runtimeNode.wfComponent,
        wfRuntimeUuid: runtimeNode.wfRuntimeUuid,
        nodeUuid: runtimeNode.nodeUuid,
        nodeTitle: runtimeNode.nodeTitle,
      }
      Object.assign(runtimeNode, detail, presentation)
      runtimeNode.input ||= {}
      runtimeNode.output ||= {}
      runtimeNode.detailLoaded = true
      runtimeNode.detailLoading = false
      runtimeNode.detailError = ''
    },
    setWfRuntimeNodeDetailState(wfRuntimeUuid: string, runtimeNodeUuid: string, loading: boolean, error = '') {
      const runtimeNode = this.getRuntimeNode(wfRuntimeUuid, runtimeNodeUuid)
      if (runtimeNode) {
        runtimeNode.detailLoading = loading
        runtimeNode.detailError = error
      }
    },
    appendWfRuntimes(wfUuid: string, wfRuntimes: Workflow.WorkflowRuntime[]) {
      wfRuntimes.forEach((wfRuntime) => {
        this.initWfRuntime(wfRuntime)
        console.log('appendWfRuntime', wfRuntime)
      })
      const records = this.wfUuidToWfRuntimes.get(wfUuid) || []
      let added = 0
      const runtimesNewestFirst = [...wfRuntimes].reverse()
      runtimesNewestFirst.forEach((runtime) => {
        const existingIndex = records.findIndex(item => item.uuid === runtime.uuid)
        if (existingIndex >= 0) {
          Object.assign(records[existingIndex], runtime, { nodes: records[existingIndex].nodes })
        } else {
          records.unshift(runtime)
          added += 1
        }
      })
      this.wfUuidToWfRuntimes.set(wfUuid, records)
      const meta = this.ensureWfRuntimePageMeta(wfUuid)
      meta.total = Math.max(meta.total + added, records.length)
    },
    // 增加节点运行时信息
    appendRuntimeNode(wfRuntimeUuid: string, runtimeNode: Workflow.WfRuntimeNode) {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (!wfRuntime)
        return

      const wfNode = this.getWorkflowInfoById(wfRuntime.workflowId)?.nodes.find(node => node.id === runtimeNode.nodeId)
      if (wfNode) {
        runtimeNode.nodeUuid = wfNode.uuid
        runtimeNode.nodeTitle = wfNode.title
        runtimeNode.wfComponent = wfNode.wfComponent
      } else {
        console.log(`wfNode not found: ${runtimeNode.nodeId}`)
      }
      runtimeNode.wfRuntimeUuid = wfRuntime.uuid
      // NODE_RUN is emitted after the runtime row is created but before its DOING update is sent.
      runtimeNode.status = 2
      if (!runtimeNode.input)
        runtimeNode.input = {}

      if (!runtimeNode.output)
        runtimeNode.output = {}
      runtimeNode.detailLoaded = true

      wfRuntime.nodes.push(runtimeNode)
    },
    // Apply the terminal metrics snapshot pushed via the [RUNTIME_METRICS] SSE event so the
    // just-finished run shows its stats in the list without a page refresh.
    updateRuntimeMetrics(wfRuntimeUuid: string, metrics: Workflow.RuntimeMetrics) {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (!wfRuntime)
        return
      wfRuntime.inputTokens = metrics.inputTokens
      wfRuntime.outputTokens = metrics.outputTokens
      wfRuntime.duration = metrics.duration
    },
    updateRuntimeStatus(wfRuntimeUuid: string, status: number) {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (wfRuntime)
        wfRuntime.status = status
    },
    mergeRuntimeSummary(summary: Workflow.WorkflowRuntime) {
      const wfRuntime = this.getWfRuntime(summary.uuid)
      if (!wfRuntime)
        return
      const cached = {
        input: wfRuntime.input,
        output: wfRuntime.output,
        nodes: wfRuntime.nodes,
        detailLoaded: wfRuntime.detailLoaded,
      }
      Object.assign(wfRuntime, summary, cached)
    },
    markRuntimeCancelled(wfRuntimeUuid: string, message = '') {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      if (!wfRuntime)
        return
      wfRuntime.status = 7
      wfRuntime.statusRemark = message
      wfRuntime.nodes.forEach((node) => {
        if (node.status === 1 || node.status === 2) {
          node.status = 5
          node.statusRemark = message
        }
      })
    },
    completeRunningRuntimeNodes(wfRuntimeUuid: string) {
      const wfRuntime = this.getWfRuntime(wfRuntimeUuid)
      wfRuntime?.nodes.forEach((node) => {
        if (node.status === 1 || node.status === 2)
          node.status = 3
      })
    },
    appendInputToRuntimeNode(wfRuntimeUuid: string, runtimeNodeUuid: string, inputJson: string) {
      const runtimeNode = this.getRuntimeNode(wfRuntimeUuid, runtimeNodeUuid)
      if (runtimeNode) {
        // inputJson: {"input":{"value": "default input", type: 1},"input2", {"value": "input22", type: 1}}
        const obj = JSON.parse(inputJson)
        runtimeNode.input[obj.name] = obj.content
      }
    },
    appendOutputToRuntimeNode(wfRuntimeUuid: string, runtimeNodeUuid: string, outputJson: string) {
      const runtimeNode = this.getRuntimeNode(wfRuntimeUuid, runtimeNodeUuid)
      if (runtimeNode) {
        const obj = JSON.parse(outputJson)
        runtimeNode.output[obj.name] = obj.content
      }
    },
    appendChunkToRuntimeNode(wfRuntimeUuid: string, runtimeNodeUuid: string, chunk: string) {
      const runtimeNode = this.getRuntimeNode(wfRuntimeUuid, runtimeNodeUuid)
      // runtimeNode.output 格式： {"output": {value:"default output", type: 1}, "output2": {"value": "output22", type: 1}}
      if (runtimeNode) {
        if (!runtimeNode.output.output)
          runtimeNode.output.output = { value: '', type: 1 }
        runtimeNode.output.output.value = runtimeNode.output.output.value + chunk
      }
    },
    updateRuntimeNodeMetrics(wfRuntimeUuid: string, runtimeNodeUuid: string, metrics: Workflow.AnyNodeMetrics) {
      const runtimeNode = this.getRuntimeNode(wfRuntimeUuid, runtimeNodeUuid)
      if (runtimeNode) {
        runtimeNode.duration = metrics.durationMs ?? null
        runtimeNode.metadata = metrics
      }
    },
    deleteWfRuntime(wfUuid: string, wfRuntimeUuid: string) {
      const wfRuntimes = this.wfUuidToWfRuntimes.get(wfUuid)
      if (wfRuntimes) {
        const idx = wfRuntimes.findIndex((inst: { uuid: string }) => inst.uuid === wfRuntimeUuid)
        if (idx > -1) {
          wfRuntimes.splice(idx, 1)
          const meta = this.ensureWfRuntimePageMeta(wfUuid)
          meta.total = Math.max(0, meta.total - 1)
          meta.loadedAll = wfRuntimes.length >= meta.total
        }
      }
    },
    updateSuccess(wfUuid: string, wfRuntimeUuid: string, outputJson: string) {
      if (!wfRuntimeUuid) {
        console.log('updateSuccess instUuid is empty')
        return
      }
      const wfRuntimes = this.wfUuidToWfRuntimes.get(wfUuid)
      if (wfRuntimes) {
        const inst = wfRuntimes.find((inst: { uuid: string }) => inst.uuid === wfRuntimeUuid)
        if (inst) {
          inst.status = 3
          inst.nodes.forEach((node) => {
            if (node.status !== 4)
              node.status = 3
          })
          try {
            inst.output = JSON.parse(outputJson)
          } catch (e) {
            console.error(e)
            console.log('outputJson is not json', outputJson)
          }
        }
      }
    },
    updateErrorMsg(wfUuid: string, wfRuntimeUuid: string, errorMsg: string) {
      if (!wfRuntimeUuid) {
        console.log('updateSuccess instUuid is empty')
        return
      }
      const wfRuntimes = this.wfUuidToWfRuntimes.get(wfUuid)
      if (wfRuntimes) {
        const inst = wfRuntimes.find((inst: { uuid: string }) => inst.uuid === wfRuntimeUuid)
        if (inst) {
          inst.status = 4
          inst.statusRemark = errorMsg || 'error'
          const activeNode = [...inst.nodes].reverse().find(node => node.status === 2)
          if (activeNode) {
            activeNode.status = 4
            activeNode.statusRemark = errorMsg || 'error'
          }
        }
      }
    },
    clearWfRuntimes(wfUuid: string) {
      this.wfUuidToWfRuntimes.set(wfUuid, [])
      this.wfUuidToWfRuntimePageMeta.delete(wfUuid)
    },
    deleteNode(wfUuid: string, nodeUuid: string) {
      // Delete node
      const wf = this.getWorkflowInfo(wfUuid)
      if (!wf) {
        console.log('deleteNode wf not found')
        return
      }

      wf.deleteNodes.push(nodeUuid)

      const idx = wf.nodes.findIndex((node: { uuid: string }) => node.uuid === nodeUuid)
      if (idx > -1)
        wf.nodes.splice(idx, 1)

      this._deleteEdgesByNodeUuid(wf, nodeUuid)
      this._deleteUiNode(wfUuid, nodeUuid)
    },
    // 删除节点时，删除与之相关的边
    _deleteEdgesByNodeUuid(workflow: Workflow.WorkflowInfo, deletedNodeUuid: string) {
      const edges = workflow.edges.filter((edge: { sourceNodeUuid: string; targetNodeUuid: string }) => edge.sourceNodeUuid === deletedNodeUuid || edge.targetNodeUuid === deletedNodeUuid)
      edges.forEach((edge: { uuid: string }) => {
        const edgeIdx = workflow.edges.findIndex(
          (item: { uuid: string }) => item.uuid === edge.uuid,
        )
        if (edgeIdx > -1)
          workflow.edges.splice(edgeIdx, 1)

        workflow.deleteEdges.push(edge.uuid)

        this._deleteUiEdge(workflow.uuid, edge.uuid)
      })
    },
    deleteEdge(wfUuid: string, edgeUuid: string) {
      // Delete edge
      const wf = this.getWorkflowInfo(wfUuid)
      if (!wf) {
        console.log('deleteEdge wf not found')
        return
      }
      wf.deleteEdges.push(edgeUuid)
      const idx = wf.edges.findIndex((edge: { uuid: string }) => edge.uuid === edgeUuid)
      if (idx > -1)
        wf.edges.splice(idx, 1)

      this._deleteUiEdge(wfUuid, edgeUuid)
    },
    _deleteUiNode(wfUuid: string, nodeUuid: string) {
      const uiWorkflow = this.wfUuidToUIWorkflow.get(wfUuid)
      if (!uiWorkflow) {
        console.log('_deleteUiNode uiWorkflow not found')
        return
      }
      const idx = uiWorkflow.nodes.findIndex((node: { id: string }) => node.id === nodeUuid)
      if (idx > -1)
        uiWorkflow.nodes.splice(idx, 1)
    },
    _deleteUiEdge(wfUuid: string, edgeId: string) {
      const uiWorkflow = this.wfUuidToUIWorkflow.get(wfUuid)
      if (!uiWorkflow) {
        console.log('_deleteUiEdge uiWorkflow not found')
        return
      }
      const idx = uiWorkflow.edges.findIndex((edge: { id: string }) => edge.id === edgeId)
      if (idx > -1)
        uiWorkflow.edges.splice(idx, 1)
    },
    async reloadRoute(uuid?: string, defaultViewType?: string) {
      await router.replace({
        name: 'WfDetail',
        params: { uuid },
        query: { view: defaultViewType === 'instanceList' ? 'history' : undefined },
      })
    },
  },
})
