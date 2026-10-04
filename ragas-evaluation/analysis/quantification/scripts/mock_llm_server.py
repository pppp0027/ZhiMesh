#!/usr/bin/env python3
"""C2 压测轨道用的 OpenAI 兼容 mock LLM 服务器（纯标准库）。

行为契约（模拟真实答案模型的节奏，隔离平台自身容量）：
  1. 请求带 tools 且最后一条消息不是 tool 结果 → 流式返回一个 search_knowledge
     工具调用（agentic 检索两步走的第一步）；
  2. 最后一条消息是 tool 结果（或请求不带 tools）→ 流式返回固定答案
     （首 token ~200ms，后续 ~30ms/token，约 60 token）；
  3. 同时支持非流式调用与 GET /v1/models，便于客户端自举；
  4. 每个请求记一行日志（含 turn 类型与 tools 名单），供事后核对调用形态。

仅监听压测服务器本机/容器网桥，不对外网开放。
"""
from __future__ import annotations

import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST, PORT = "0.0.0.0", 9998
LOG = Path(__file__).resolve().parent.parent / "results" / "mock-llm" / "server.log"
LOG.parent.mkdir(parents=True, exist_ok=True)

TTFT_S = 0.2          # 首 token 延迟（模拟推理排队+首字生成）
TOKEN_GAP_S = 0.03    # 后续 token 间隔
ANSWER = (
    "根据知识库检索到的内容，该问题的答案要点如下：系统在设计与实现上采用了分层架构，"
    "检索层通过向量、全文与图谱三路召回并进行融合重排，保障了召回覆盖率与排序精度；"
    "编排层以工具化方式挂载检索能力，由模型按需调用；整体链路在并发场景下通过流式输出"
    "降低首字延迟，并以引用留痕保证答案可溯源。以上为压测轨道固定话术，仅用于容量测量。"
)
N_CHUNKS = 60


def now_ms() -> int:
    return int(time.time() * 1000)


def pick_tool(tools: list) -> dict | None:
    """优先选知识检索工具，否则第一个工具；并按 schema 造最小合法参数。"""
    target = None
    for t in tools or []:
        name = ((t.get("function") or {}).get("name")) or t.get("name") or ""
        if "search" in name.lower() or "knowledge" in name.lower() or "rag" in name.lower():
            target = t
            break
    if target is None and tools:
        target = tools[0]
    if target is None:
        return None
    fn = target.get("function") or target
    name = fn.get("name", "tool")
    schema = fn.get("parameters") or {}
    props = schema.get("properties") or {}
    required = schema.get("required") or list(props.keys())
    args: dict = {}
    for k in required:
        if k not in props:
            continue
        typ = props[k].get("type", "string")
        if typ == "integer":
            args[k] = 5
        elif typ == "boolean":
            args[k] = True
        elif props[k].get("enum"):
            args[k] = props[k]["enum"][0]
        else:
            args[k] = "并发压测检索查询"
    return {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静默默认访问日志，走自己的结构化日志
        pass

    # ---------- helpers ----------
    def _reply_json(self, obj: dict, status: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _sse_start(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()

    def _send_event(self, obj: dict) -> None:
        self.wfile.write(b"data: " + json.dumps(obj, ensure_ascii=False).encode() + b"\n\n")
        self.wfile.flush()

    # ---------- routes ----------
    def do_GET(self):
        if self.path.rstrip("/") in ("", "/health"):
            self._reply_json({"status": "ok"})
        elif self.path.rstrip("/") == "/v1/models":
            self._reply_json({"object": "list", "data": [{"id": "mock-chat", "object": "model"}]})
        else:
            self._reply_json({"error": "not found"}, 404)

    def do_POST(self):
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self._reply_json({"error": "not found"}, 404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._reply_json({"error": "bad json"}, 400)
            return
        stream = bool(req.get("stream"))
        tools = req.get("tools") or []
        msgs = req.get("messages") or []
        last = msgs[-1] if msgs else {}
        turn = "answer" if last.get("role") == "tool" or not tools else "tool_call"
        tool = pick_tool(tools) if turn == "tool_call" else None
        cid = f"chatcmpl-mock-{uuid.uuid4().hex[:8]}"
        created = int(time.time())
        base = {"id": cid, "object": "chat.completion.chunk", "created": created, "model": req.get("model", "mock-chat")}

        with LOG.open("a", encoding="utf-8") as f:
            f.write(f"{now_ms()} turn={turn} stream={stream} tools={[ (t.get('function') or {}).get('name', t.get('name','?')) for t in tools ]} last_role={last.get('role')}\n")

        if turn == "tool_call" and tool:
            if not stream:
                self._reply_json({
                    "id": cid, "object": "chat.completion", "created": created,
                    "model": base["model"],
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": None,
                               "tool_calls": [{"id": "call_mock_1", "type": "function",
                                               "function": {"name": tool["name"], "arguments": tool["arguments"]}}]},
                                 "finish_reason": "tool_calls"}],
                    "usage": {"prompt_tokens": 100, "completion_tokens": 1, "total_tokens": 101},
                })
                return
            time.sleep(TTFT_S)
            self._sse_start()
            self._send_event({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "content": None}, "finish_reason": None}]})
            self._send_event({**base, "choices": [{"index": 0, "delta": {"tool_calls": [
                {"index": 0, "id": "call_mock_1", "type": "function",
                 "function": {"name": tool["name"], "arguments": ""}}]}, "finish_reason": None}]})
            for piece in (tool["arguments"][i:i + 40] for i in range(0, len(tool["arguments"]), 40)):
                time.sleep(TOKEN_GAP_S)
                self._send_event({**base, "choices": [{"index": 0, "delta": {"tool_calls": [
                    {"index": 0, "function": {"arguments": piece}}]}, "finish_reason": None}]})
            self._send_event({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]})
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return

        # ---------- answer turn ----------
        if not stream:
            time.sleep(TTFT_S)
            self._reply_json({
                "id": cid, "object": "chat.completion", "created": created,
                "model": base["model"],
                "choices": [{"index": 0, "message": {"role": "assistant", "content": ANSWER},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 800, "completion_tokens": N_CHUNKS, "total_tokens": 860},
            })
            return
        time.sleep(TTFT_S)
        self._sse_start()
        self._send_event({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": None}]})
        step = max(1, (len(ANSWER) + N_CHUNKS - 1) // N_CHUNKS)
        for i in range(0, len(ANSWER), step):
            time.sleep(TOKEN_GAP_S)
            self._send_event({**base, "choices": [{"index": 0, "delta": {"content": ANSWER[i:i + step]}, "finish_reason": None}]})
        self._send_event({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    srv.daemon_threads = True
    print(f"mock-llm listening on {HOST}:{PORT}", flush=True)
    srv.serve_forever()
