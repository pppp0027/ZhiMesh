/**
 * 聊天 SSE 处理块的共享状态迁移逻辑。

/**
 * 聊天 SSE 处理块的共享状态迁移逻辑。
 *
 * 历史背景：这套逻辑曾在 index.vue（重新生成流）与 InputEditor.vue（新问题流）
 * 各持一份 ~250 行近乎逐字相同的拷贝（含同一段双语注释），修一处漏一处即产生
 * 「重新生成正常、新问题不正常」式漂移——清 state 时机就真实漂移过一次。本模块
 * 把纯状态迁移部分收敛到唯一实现，两个调用方只保留各自的边界差异
 * （emit vs 本地函数、TTS、标题更新、thinking 标志）。
 *
 * Shared state-migration logic for the chat SSE handler blocks. This logic used
 * to live as two near-verbatim ~250-line copies (comments included) in
 * index.vue (the regenerate flow) and InputEditor.vue (the new-question flow);
 * fixing one copy and missing the other caused real drift (the state-clear
 * timing diverged). This module is the single implementation; the two callers
 * keep only their boundary differences (emit vs local functions, TTS, title
 * update, thinking flags).
 */

/**
 * 流式渲染批量 flush：chunk 先入请求闭包内的缓冲（每请求一个实例，防
 * regenerate/多页签串包），~60ms 定时一次性 appendChunk，消除逐字符 append
 * 触发全量 markdown 重渲染的 O(n²) 卡顿；done/suspension/error 入口必须先
 * 同步 flush，防丢尾字符、防挂起卡片与文本错位。
 *
 * Batched streaming flush: chunks buffer in a per-request closure and are
 * appended in one ~60ms tick, removing the O(n²) full-markdown re-render caused
 * by per-character appends. Terminal entries (done/suspension/error) must
 * flush synchronously first, or trailing characters drop and suspension cards
 * misalign with text.
 */
export function createChunkFlusher(deps: {
  isCurrentRequest: () => boolean
  /** 返回当前答案 uuid；undefined 表示答案行未就绪，缓冲照常丢弃 */
  getAnswerUuid: () => string | undefined
  appendChunk: (chatKey: string, answerUuid: string, chunk: string, thinking: boolean) => void
  chatKey: string
  /** 每次真实落地后的滚动/通知钩子 */
  onReceiving: () => void
}) {
  let textBuffer = ''
  let thinkingBuffer = ''
  let flushTimer: ReturnType<typeof setTimeout> | undefined

  const flush = () => {
    if (flushTimer) {
      clearTimeout(flushTimer)
      flushTimer = undefined
    }
    const answerUuid = deps.getAnswerUuid()
    if (!deps.isCurrentRequest() || !answerUuid) {
      textBuffer = ''
      thinkingBuffer = ''
      return
    }
    try {
      if (thinkingBuffer) {
        deps.appendChunk(deps.chatKey, answerUuid, thinkingBuffer, true)
        deps.onReceiving()
      }
      if (textBuffer) {
        deps.appendChunk(deps.chatKey, answerUuid, textBuffer, false)
        deps.onReceiving()
      }
    } catch (error) {
      console.error(error)
    }
    textBuffer = ''
    thinkingBuffer = ''
  }

  return {
    /** 追加推理文本 chunk（内部按 60ms 批量落地） */
    pushThinking(chunk: string) {
      thinkingBuffer += chunk
      if (!flushTimer)
        flushTimer = setTimeout(flush, 60)
    },
    /** 追加正文 chunk（内部按 60ms 批量落地） */
    pushText(chunk: string) {
      textBuffer += chunk
      if (!flushTimer)
        flushTimer = setTimeout(flush, 60)
    },
    /** 同步落地全部缓冲（终态入口先调） */
    flush,
  }
}

/** [TOOL_STARTED]：点亮一条 running 步骤，完成事件（[TOOL_CALL]）再回填时长/结果 */
export function appendToolStart(answer: Chat.ChatMessage, data: { toolName: string; args?: string }) {
  if (!answer.toolCalls)
    answer.toolCalls = []
  answer.toolCalls.push({
    toolName: data.toolName,
    args: data.args,
    running: true,
    durationMs: 0,
    success: true,
  })
}

/**
 * [TOOL_CALL] 回填：从后往前找同名 running 步骤（[TOOL_STARTED] 已点亮）回填；
 * 未命中（无 started 的完成事件/历史回放/乱序）按直插兜底，兼容 TOOL_LIMIT_MARKER
 */
export function backfillToolCall(answer: Chat.ChatMessage, data: Chat.ToolCall) {
  if (!answer.toolCalls)
    answer.toolCalls = []
  for (let i = answer.toolCalls.length - 1; i >= 0; i--) {
    const tool = answer.toolCalls[i]
    if (tool.toolName === data.toolName && tool.running === true) {
      tool.running = false
      tool.durationMs = data.durationMs
      tool.success = data.success
      if (data.resultSummary !== undefined)
        tool.resultSummary = data.resultSummary
      return
    }
  }
  answer.toolCalls.push(data)
}

/**
 * 挂起事件：同名 running 步骤原地翻挂起节点。协作工具（ask_user 等）的
 * [TOOL_STARTED] 已点亮一条 running 行，挂起路径后端不会再发 [TOOL_CALL]，
 * 直接 push 会双行且 spinner 无人回填；未命中（无 started 的异常序/历史路径）才补新行
 */
export function applySuspension(answer: Chat.ChatMessage, payload: Chat.SuspensionPayload) {
  answer.suspension = payload
  answer.suspensionActive = true
  if (!answer.toolCalls)
    answer.toolCalls = []
  const toolName = payload.toolName || ''
  for (let i = answer.toolCalls.length - 1; i >= 0; i--) {
    const tool = answer.toolCalls[i]
    if (tool.toolName === toolName && tool.running === true) {
      tool.running = false
      tool.resultSummary = payload.question
      tool.suspensionKind = payload.kind ?? payload.type
      return
    }
  }
  answer.toolCalls.push({
    toolName,
    durationMs: 0,
    success: true,
    resultSummary: payload.question,
    suspensionKind: payload.kind ?? payload.type,
  })
}

/**
 * 解析 [DONE] 里的 [META] 载荷；坏载荷返回 null（调用方走错误收尾）。
 * 无 META 前缀返回 undefined（调用方走无 meta 的普通收尾）。
 */
export function parseMetaData(chunk: string): Chat.MetaData | null | undefined {
  if (!chunk.includes('[META]'))
    return undefined
  try {
    return JSON.parse(chunk.replace('[META]', ''))
  } catch (error) {
    console.error('Invalid SSE metadata payload', error)
    return null
  }
}
