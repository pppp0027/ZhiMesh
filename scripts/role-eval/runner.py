#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
T6 角色级 eval 跑分器（stdlib-only，无第三方依赖）。

流程：登录 →（幂等）addByPreset 实例化三领域角色 →（幂等）saveOrUpdate 启用
MCP 15/16/17 → 逐场景：显式建会话 → 按 turns 剧本驱动聊天与挂起恢复
（ask_user 追问 / 审批卡批准与 [APPROVAL_REJECTED] 拒绝）→ 聚合全量 SSE 事件、
答案文本与 [DONE] META → 断言（工具调用序列、政策引用、数据数字、审批路径、
越权拒绝）→ 删会话 → 汇总输出 results.json 与 report.md。

断言失败不中断后续场景。imageGated 场景默认 SKIPPED_IMAGE_GATED，
设 ZHIMESH_EVAL_STRICT_IMAGE=1 才真跑（详见 README.md）。

凭据全部走环境变量（见 .env.example）；本仓库公开，任何输出文件不得包含
真实凭据或内网地址。评估账号（李四）必须已存在且可登录，runner 不创建账号。

用法示例：
    python3 runner.py --list
    python3 runner.py --smoke
    python3 runner.py --domain hr --only hr-full-leave-chain
    ZHIMESH_EVAL_STRICT_IMAGE=1 python3 runner.py
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCENARIO_DIR = SCRIPT_DIR / "scenarios"
DEFAULT_REPORT_ROOT = REPO_ROOT / "docs" / "reports" / "role-eval"

DOMAINS = ("hr", "it", "finance")
CATEGORIES = ("policy_ref", "data_query", "full_chain", "unauthorized", "approval_path")
# 剧本结构白名单（加载期校验，防手写漂移到运行期才炸）
EXPECT_KEYS = {
    "toolCalls", "toolCallsIncludes", "toolCallsExcludes", "toolArgs",
    "toolResultStartsWith", "successfulCall", "noSuccessfulCall",
    "answerContains", "answerNotContains", "kbSource", "isRefEmbedding", "suspensions",
}
SCENARIO_KEYS = {"id", "category", "prompt", "turns", "expect", "imageGated"}
TURN_ON_KINDS = {"ASK_USER", "APPROVAL", "MCP_APPROVAL", "ANY"}
STATUS_PASS = "PASS"
STATUS_FAIL = "FAIL"
STATUS_SKIP_IMAGE = "SKIPPED_IMAGE_GATED"
STATUS_SKIP_PREREQ = "SKIPPED_PREREQ"

# SSE 约定（与 SseManager 逐字对齐）：换行标记字面量、META 前缀
WRAP_MARKER = "-_wrap_-"
META_PREFIX = "[META]"

DEFAULT_BASE_URL = "http://127.0.0.1:9999"


class ApiError(RuntimeError):
    """HTTP / 业务包装层错误（含登录失败、预设实例化失败等）。"""


# ----------------------------------------------------------------------------
# 环境与凭据
# ----------------------------------------------------------------------------

def load_dotenv(path: Path) -> None:
    """极简 .env 加载器（仅本目录 .env，已存在同名环境变量时不覆盖）。"""
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def env_str(name: str, default: str = "") -> str:
    value = os.environ.get(name, default)
    return value.strip() if isinstance(value, str) else default


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "")
    try:
        return int(raw) if raw.strip() else default
    except ValueError:
        print(f"[警告] 环境变量 {name}={raw!r} 不是整数，使用默认值 {default}")
        return default


# ----------------------------------------------------------------------------
# HTTP 基础设施（urllib 封装，响应统一 unwrap {success,code,message,data}）
# ----------------------------------------------------------------------------

def unwrap(payload):
    """剥掉统一响应包装取 .data；业务失败抛 ApiError。

    口径与后端 BaseResponse（success/code/message/data）对齐；
    兼容旧形态 {code,data,msg}（sse_benchmark.py 的 unwrap 同族逻辑）。
    """
    if isinstance(payload, dict) and "success" in payload:
        if not payload.get("success"):
            raise ApiError(
                f"API 业务失败 code={payload.get('code')} "
                f"msg={payload.get('message') or payload.get('msg')}"
            )
        return payload.get("data")
    if isinstance(payload, dict) and "data" in payload and "code" in payload:
        code = str(payload.get("code"))
        if code not in ("200", "0", "None", ""):
            raise ApiError(f"API 业务失败 code={code} msg={payload.get('message') or payload.get('msg')}")
        return payload.get("data")
    return payload


class Client:
    """后端 HTTP+SSE 客户端。Authorization 头携带裸 token（无 Bearer）。"""

    def __init__(self, base_url: str, token: str, http_timeout: int, sse_timeout: int):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.http_timeout = http_timeout
        self.sse_timeout = sse_timeout
        # 强制直连：评估目标是内网/环回后端，绝不能被 http_proxy 环境变量劫持
        # （本机 clash 的 no_proxy 不含 Tailscale 100.* 段，走代理必得 502）
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    # ---- 通用 JSON 请求 -----------------------------------------------------

    def request_json(self, method: str, path: str, body=None, timeout=None):
        url = self.base_url + path
        data = None
        headers = {"Authorization": self.token}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif method == "POST":
            data = b""  # POST 无体也带 Content-Length，避免网关 411
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        timeout = timeout or self.http_timeout
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as exc:
            detail = ""
            try:
                detail = exc.read().decode("utf-8", errors="replace")[:300]
            except Exception:
                pass
            raise ApiError(f"HTTP {exc.code} {method} {path} {detail}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"连接失败 {method} {path}: {exc.reason}") from exc
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise ApiError(f"{method} {path} 返回非 JSON：{raw[:200]!r}") from exc
        return unwrap(payload)

    # ---- 业务端点 -----------------------------------------------------------

    def login(self, email: str, password: str) -> str:
        """POST /auth/login（captcha 可空）→ .data.token。"""
        url = self.base_url + "/auth/login"
        body = json.dumps({"email": email, "password": password}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            url, data=body, method="POST",
            headers={"Content-Type": "application/json"},
        )
        try:
            with self.opener.open(req, timeout=self.http_timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            try:
                detail = exc.read().decode("utf-8", errors="replace")[:300]
            except Exception:
                detail = ""
            raise ApiError(f"登录失败 HTTP {exc.code}：{detail}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"登录连接失败：{exc.reason}") from exc
        data = unwrap(payload)
        token = (data or {}).get("token") if isinstance(data, dict) else None
        if not token:
            raise ApiError("登录返回无 token（检查账号密码与账号激活状态）")
        return str(token)

    def add_by_preset(self, preset_uuid: str) -> dict:
        """POST /character/addByPreset（幂等）→ CharacterDto（.uuid/.id）。"""
        data = self.request_json("POST", f"/character/addByPreset?presetUuid={preset_uuid}")
        if not isinstance(data, dict) or not data.get("uuid"):
            raise ApiError(f"addByPreset({preset_uuid}) 返回无 uuid")
        return data

    def enable_mcp(self, mcp_id: int) -> None:
        """POST /user/mcp/saveOrUpdate（用户级 MCP 闸门，幂等）。"""
        self.request_json("POST", "/user/mcp/saveOrUpdate", body={"mcpId": mcp_id, "isEnable": True})

    def create_conversation(self, character_uuid: str, title: str) -> str:
        """POST /conversation/add → .data.uuid（评估必须显式建会话便于清理）。"""
        data = self.request_json(
            "POST", "/conversation/add",
            body={"characterUuid": character_uuid, "title": title[:100]},
        )
        if not isinstance(data, dict) or not data.get("uuid"):
            raise ApiError("conversation/add 返回无 uuid")
        return str(data["uuid"])

    def delete_conversation(self, conversation_uuid: str) -> None:
        """POST /conversation/del/{uuid}（best-effort，失败仅告警）。"""
        try:
            self.request_json("POST", f"/conversation/del/{conversation_uuid}")
        except ApiError as exc:
            print(f"[警告] 会话清理失败 {conversation_uuid}: {exc}")

    # ---- SSE 聊天 ------------------------------------------------------------

    def chat(self, character_uuid: str, conversation_uuid: str, prompt: str) -> dict:
        """POST /chat/process → 解析一轮 SSE，返回该轮聚合结果。

        返回键：answer / meta / tool_calls / suspension / suspension_events /
        error / saw_done。事件口径见 ZhiMeshConstant.SSEEventName 与 SseManager。
        """
        body = json.dumps(
            {"characterUuid": character_uuid, "conversationUuid": conversation_uuid, "prompt": prompt},
            ensure_ascii=False,
        ).encode("utf-8")
        req = urllib.request.Request(
            self.base_url + "/chat/process", data=body, method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                "Authorization": self.token,
            },
        )
        fragments: list[str] = []
        tool_events: list[dict] = []
        suspension_events: list[str] = []
        meta = None
        error_text = None
        saw_done = False

        def handle_event(name: str, data: str) -> None:
            """单条完整 SSE 事件的分发（闭包写入上方的收集容器）。"""
            nonlocal meta, error_text, saw_done
            if name == "[DONE]":
                saw_done = True
                text = data.lstrip()
                if text.startswith(META_PREFIX):
                    try:
                        meta = json.loads(text[len(META_PREFIX):])
                    except ValueError:
                        error_text = error_text or f"[META] 解析失败：{text[:200]}"
            elif name == "[ERROR]":
                error_text = error_text or data
            elif name == "[TOOL_CALL]":
                tool_events.append(_try_json(data))
            elif name == "[AGENT_QUESTION]":
                suspension_events.append("ASK_USER")
            elif name == "[APPROVAL_REQUEST]":
                payload = _try_json(data)
                suspension_events.append(str(payload.get("kind") or "APPROVAL"))
            elif name == "":  # 无名事件 = 增量 token
                fragments.append(data)
            # 其余（[THINKING]/[STATE_CHANGED]/…）对评估无断言价值，忽略

        parser = _SseParser()
        try:
            with self.opener.open(req, timeout=self.sse_timeout) as resp:
                for raw_line in resp:  # 按行读 SSE（与服务端 \n 分帧对齐）
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    event = parser.feed(line)
                    if event is not None:
                        handle_event(*event)
                        if saw_done or error_text:
                            break
                if not saw_done and error_text is None:
                    trailing = parser.flush()  # 流尾兜底：无结尾空行的事件
                    if trailing is not None:
                        handle_event(*trailing)
        except urllib.error.HTTPError as exc:
            error_text = error_text or f"HTTP {exc.code} /chat/process"
        except urllib.error.URLError as exc:
            error_text = error_text or f"/chat/process 连接失败: {exc.reason}"
        except TimeoutError as exc:
            error_text = error_text or f"/chat/process 读取超时（{self.sse_timeout}s）: {exc}"
        except OSError as exc:
            error_text = error_text or f"/chat/process 流读取异常: {exc}"
        except Exception as exc:  # http.client.IncompleteRead 等未预期异常：该轮收敛为错误，不炸整场
            error_text = error_text or f"/chat/process 未预期异常 {type(exc).__name__}: {exc}"

        answer = "".join(fragments).replace(WRAP_MARKER, "\n").strip()
        if not saw_done and error_text is None:
            error_text = "SSE 流结束但未收到 [DONE]"

        # 工具轨迹优先取 META（含 seq / 成功标记），缺 meta 时退回 [TOOL_CALL] 事件
        answer_meta = (meta or {}).get("answer") or {}
        tool_calls = answer_meta.get("toolCalls")
        if not isinstance(tool_calls, list):
            tool_calls = tool_events

        # 挂起类型：优先 META.answer.suspension.type，退回事件流推断
        suspension = None
        meta_suspension = answer_meta.get("suspension")
        if isinstance(meta_suspension, dict) and meta_suspension.get("type"):
            suspension = str(meta_suspension["type"])
        elif suspension_events:
            suspension = suspension_events[-1]

        return {
            "answer": answer,
            "meta": meta,
            "tool_calls": [tc for tc in tool_calls if isinstance(tc, dict)],
            "suspension": suspension,
            "suspension_events": suspension_events,
            "error": error_text,
            "saw_done": saw_done,
        }


# ---- SSE 解析（手写状态机，sse_benchmark.py:190-204 同款思路） ---------------

class _SseParser:
    """逐行喂入、按需取整事件的 SSE 解析器（Spring \n 分帧、data: 前缀）。"""

    def __init__(self):
        self.event_name = ""
        self.data_lines: list[str] = []

    def feed(self, line: str):
        """喂一行；空行时返回 (event_name, joined_data)，其余返回 None。"""
        if line == "":
            if self.event_name or self.data_lines:
                event = (self.event_name, "\n".join(self.data_lines))
            else:
                event = None
            self.event_name = ""
            self.data_lines = []
            return event
        if line.startswith("event:"):
            self.event_name = line[6:].strip()
        elif line.startswith("data:"):
            value = line[5:]
            if value.startswith(" "):  # SSE 规范：冒号后单个空格剥离
                value = value[1:]
            self.data_lines.append(value)
        # 注释行（:）与其他字段忽略
        return None

    def flush(self):
        if self.event_name or self.data_lines:
            event = (self.event_name, "\n".join(self.data_lines))
            self.event_name = ""
            self.data_lines = []
            return event
        return None


def _try_json(text: str) -> dict:
    try:
        payload = json.loads(text)
        return payload if isinstance(payload, dict) else {"raw": text}
    except ValueError:
        return {"raw": text}


# ----------------------------------------------------------------------------
# 剧本加载与校验
# ----------------------------------------------------------------------------

def _expect_type_problems(sid: str, expect: dict) -> list:
    """expect 值形态校验（键白名单之外的类型坑，加载期拦下）。"""
    problems = []
    for key in ("toolCalls", "toolCallsIncludes", "toolCallsExcludes", "successfulCall",
                "noSuccessfulCall", "answerContains", "answerNotContains", "suspensions"):
        if key in expect and not isinstance(expect[key], list):
            problems.append(f"{sid}: expect.{key} 应为数组")
    if "toolArgs" in expect:
        value = expect["toolArgs"]
        if not isinstance(value, dict) or not all(isinstance(v, list) for v in value.values()):
            problems.append(f"{sid}: expect.toolArgs 应为 {{工具名: [子串…]}}")
    if "toolResultStartsWith" in expect:
        value = expect["toolResultStartsWith"]
        if not isinstance(value, dict) or not all(isinstance(v, str) and v for v in value.values()):
            problems.append(f"{sid}: expect.toolResultStartsWith 应为 {{工具名: 前缀字符串}}")
    if "kbSource" in expect and not isinstance(expect["kbSource"], str):
        problems.append(f"{sid}: expect.kbSource 应为字符串")
    if "isRefEmbedding" in expect and not isinstance(expect["isRefEmbedding"], bool):
        problems.append(f"{sid}: expect.isRefEmbedding 应为布尔值")
    return problems


def load_domain_scenarios(domain: str) -> dict:
    path = SCENARIO_DIR / f"{domain}.json"
    if not path.is_file():
        raise SystemExit(f"剧本文件缺失：{path}")
    try:
        spec = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise SystemExit(f"剧本 JSON 解析失败 {path}: {exc}")
    problems = []
    if spec.get("domain") != domain:
        problems.append(f"domain 字段应为 {domain}")
    if not spec.get("presetUuid"):
        problems.append("缺 presetUuid")
    if not spec.get("mcpId"):
        problems.append("缺 mcpId")
    scenarios = spec.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        problems.append("scenarios 为空")
    else:
        seen_ids = set()
        for idx, sc in enumerate(scenarios):
            if not isinstance(sc, dict):
                problems.append(f"场景 #{idx} 应为对象")
                continue
            sid = sc.get("id") or f"#{idx}"
            if not isinstance(sc.get("id"), str) or not sc.get("id").strip():
                problems.append(f"场景 #{idx}: id 应为非空字符串")
            elif sc.get("id") in seen_ids:
                problems.append(f"场景 id 重复：{sc.get('id')}")
            if isinstance(sc.get("id"), str):
                seen_ids.add(sc.get("id"))
            unknown_keys = set(sc) - SCENARIO_KEYS
            if unknown_keys:
                problems.append(f"{sid}: 未知字段 {sorted(unknown_keys)}")
            if sc.get("category") not in CATEGORIES:
                problems.append(f"{sid}: category 非法 {sc.get('category')!r}")
            if not isinstance(sc.get("prompt"), str) or not sc.get("prompt").strip():
                problems.append(f"{sid}: 缺 prompt")
            turns = sc.get("turns", [])
            if not isinstance(turns, list):
                problems.append(f"{sid}: turns 应为数组")
            else:
                for t_idx, turn in enumerate(turns):
                    if (not isinstance(turn, dict)
                            or not isinstance(turn.get("text"), str)
                            or not turn.get("text", "").strip()):
                        problems.append(f"{sid}: turns[{t_idx}] 应为含非空 text 的对象")
                        continue
                    on = turn.get("on", "ANY")
                    on_ok = (isinstance(on, str) and on in TURN_ON_KINDS) or (
                        isinstance(on, list) and on
                        and all(isinstance(k, str) and k in TURN_ON_KINDS for k in on))
                    if not on_ok:
                        problems.append(
                            f"{sid}: turns[{t_idx}].on 非法 {on!r}"
                            f"（可选 {'/'.join(sorted(TURN_ON_KINDS))} 或其数组）")
            expect = sc.get("expect", {})
            if not isinstance(expect, dict):
                problems.append(f"{sid}: expect 应为对象")
            else:
                unknown_expect = set(expect) - EXPECT_KEYS
                if unknown_expect:
                    problems.append(f"{sid}: 未知 expect 键 {sorted(unknown_expect)}")
                problems.extend(_expect_type_problems(sid, expect))
            gate = sc.get("imageGated")
            if gate is not None and not (
                    isinstance(gate, dict) and isinstance(gate.get("reason", ""), str)):
                problems.append(f"{sid}: imageGated 应为 null 或 {{reason: str}}（不接受布尔值）")
    if problems:
        raise SystemExit(f"剧本校验失败 {path}:\n  " + "\n  ".join(problems))
    return spec


# ----------------------------------------------------------------------------
# 断言引擎
# ----------------------------------------------------------------------------

def _norm(text) -> str:
    """空白归一化：答案匹配对全角/半角空格、换行不敏感（「7 天」==「7天」）。"""
    return re.sub(r"\s+", "", str(text))


def _match_needle(haystack_norm: str, needle) -> bool:
    """字符串=字面包含；数组=任一命中（any-of）。needle 先做同样的空白归一化。"""
    if isinstance(needle, list):
        return any(_match_needle(haystack_norm, n) for n in needle)
    return _norm(needle) in haystack_norm


def _match_tool_name(present_names: list, want) -> bool:
    """工具名匹配：字符串=精确存在；数组=任一存在（兼容 search_tickets/search_ticket 命名差）。"""
    if isinstance(want, list):
        return any(name in present_names for name in want)
    return want in present_names


def evaluate_expectations(expect: dict, agg: dict) -> list:
    """对聚合上下文执行全部断言，返回 [{name, pass, detail}]。"""
    results = []

    def record(name: str, ok: bool, detail: str):
        results.append({"name": name, "pass": bool(ok), "detail": detail[:500]})

    answer_norm = _norm(agg["answer"])
    tool_calls = agg["tool_calls"]
    tool_names = [str(tc.get("toolName") or "") for tc in tool_calls]

    def calls_of(name) -> list:
        return [tc for tc in tool_calls if str(tc.get("toolName") or "") == name]

    # ---- 工具调用序列 ----
    if "toolCalls" in expect:  # 精确有序序列（逐位 any-of）
        wanted = expect["toolCalls"] or []
        ok = len(tool_names) == len(wanted) and all(
            _match_tool_name([tool_names[i]], want) for i, want in enumerate(wanted)
        )
        record("toolCalls", ok, f"期望序列 {wanted}，实际 {tool_names}")

    if "toolCallsIncludes" in expect:  # 每项至少出现一次
        for want in expect["toolCallsIncludes"] or []:
            ok = _match_tool_name(tool_names, want)
            display = want if isinstance(want, str) else "/".join(want)
            record(f"toolCallsIncludes({display})", ok,
                   f"期望出现 {display}，实际 {tool_names or '（无工具调用）'}")

    if "toolCallsExcludes" in expect:  # 每项都不得出现（数组=任一出现即违规）
        for want in expect["toolCallsExcludes"] or []:
            display = want if isinstance(want, str) else "/".join(want)
            ok = not _match_tool_name(tool_names, want)
            record(f"toolCallsExcludes({display})", ok,
                   f"期望不出现 {display}，实际 {tool_names or '（无工具调用）'}")

    if "toolArgs" in expect:  # {工具名: [子串…]}：该工具某次调用的 args 含全部子串
        for tool_name, needles in (expect["toolArgs"] or {}).items():
            ok = any(
                all(_norm(n) in _norm(tc.get("args") or "") for n in needles)
                for tc in calls_of(tool_name)
            )
            sample = next((tc.get("args") for tc in calls_of(tool_name)), None)
            record(f"toolArgs({tool_name})", ok,
                   f"期望 args 含 {needles}，采样 args={sample!r}")

    if "toolResultStartsWith" in expect:  # {工具名: 前缀}：某次调用 resultSummary 以此前缀开头
        for tool_name, prefix in (expect["toolResultStartsWith"] or {}).items():
            ok = any(
                _norm(tc.get("resultSummary") or "").startswith(_norm(prefix))
                for tc in calls_of(tool_name)
            )
            sample = next((tc.get("resultSummary") for tc in calls_of(tool_name)), None)
            record(f"toolResultStartsWith({tool_name})", ok,
                   f"期望 resultSummary 以 {prefix!r} 开头，采样={sample!r}")

    if "successfulCall" in expect:  # 该工具至少一次 success=true（审批放行后真正执行）
        for tool_name in expect["successfulCall"] or []:
            ok = any(tc.get("success") is True for tc in calls_of(tool_name))
            record(f"successfulCall({tool_name})", ok,
                   f"期望存在 success=true 调用，实际 {[tc.get('success') for tc in calls_of(tool_name)]}")

    if "noSuccessfulCall" in expect:  # 该工具不得有 success=true（拒绝/越权未放行）
        for tool_name in expect["noSuccessfulCall"] or []:
            ok = not any(tc.get("success") is True for tc in calls_of(tool_name))
            record(f"noSuccessfulCall({tool_name})", ok,
                   f"期望无 success=true 调用，实际 {[tc.get('success') for tc in calls_of(tool_name)]}")

    # ---- 答案文本 ----
    for needle in expect.get("answerContains") or []:
        ok = _match_needle(answer_norm, needle)
        display = needle if isinstance(needle, str) else "/".join(needle)
        record(f"answerContains({display})", ok,
               f"答案缺少 {display}；节选：{agg['answer'][:180]!r}")

    for needle in expect.get("answerNotContains") or []:
        ok = not _match_needle(answer_norm, needle)
        display = needle if isinstance(needle, str) else "/".join(needle)
        record(f"answerNotContains({display})", ok,
               f"答案不应出现 {display}；节选：{agg['answer'][:180]!r}")

    # ---- 知识库引用 ----
    if "kbSource" in expect:
        title = expect["kbSource"]
        marker = _norm(f"[来源: {title}]")
        summary_hit = any(marker in _norm(tc.get("resultSummary") or "") for tc in tool_calls)
        ok = agg["ref_embedding"] or summary_hit
        record(f"kbSource({title})", ok,
               f"isRefEmbedding={agg['ref_embedding']}，resultSummary 含来源标记={summary_hit}"
               f"（注意截断风险，优先看 isRefEmbedding）")

    if "isRefEmbedding" in expect:
        ok = agg["ref_embedding"] == bool(expect["isRefEmbedding"])
        record("isRefEmbedding", ok, f"期望 {expect['isRefEmbedding']}，实际 {agg['ref_embedding']}")

    # ---- 挂起（ask_user / 审批卡） ----
    for want in expect.get("suspensions") or []:
        ok = _match_tool_name(agg["suspensions"], want)
        display = want if isinstance(want, str) else "/".join(want)
        record(f"suspensions({display})", ok,
               f"期望出现挂起 {display}，实际 {agg['suspensions'] or '（无挂起）'}")

    return results


# ----------------------------------------------------------------------------
# 场景执行
# ----------------------------------------------------------------------------

def _turn_matches(on, suspension_type: str) -> bool:
    if on in (None, "", "ANY"):
        return True
    if isinstance(on, list):
        return suspension_type in on
    return suspension_type == on


def run_scenario(client: Client, scenario: dict, character_uuid: str) -> dict:
    """驱动一个场景的多轮聊天并断言。任何异常都收敛为 FAIL，不向外抛。"""
    rounds: list[dict] = []
    runtime_failures: list[dict] = []
    conversation_uuid = None
    try:
        conversation_uuid = client.create_conversation(
            character_uuid, f"role-eval {scenario['id']}")
        turns = scenario.get("turns") or []
        prompt = scenario["prompt"]
        # 上限保护：轮数 = 剧本 turns + 1 轮起始消息（服务端每次聊天挂起上限 3）
        for index in range(len(turns) + 1):
            try:
                rnd = client.chat(character_uuid, conversation_uuid, prompt)
            except ApiError as exc:
                runtime_failures.append({"name": "chat_request", "pass": False, "detail": str(exc)})
                break
            rounds.append(rnd)
            if rnd["error"]:
                runtime_failures.append({"name": "sse_error", "pass": False, "detail": rnd["error"]})
                break
            suspension = rnd["suspension"]
            if suspension is None:
                break  # 本轮自然结束，剧本驱动完成
            if index >= len(turns):
                break  # 出现剧本未覆盖的挂起：停止驱动，交给断言判 FAIL
            turn = turns[index]
            if not _turn_matches(turn.get("on", "ANY"), suspension):
                runtime_failures.append({
                    "name": "turn_mismatch",
                    "pass": False,
                    "detail": f"第 {index + 1} 轮挂起类型为 {suspension}，"
                              f"剧本期望 on={turn.get('on')!r}，已停止后续驱动",
                })
                break
            prompt = turn["text"]
    except ApiError as exc:
        runtime_failures.append({"name": "setup_conversation", "pass": False, "detail": str(exc)})
    except Exception as exc:  # 剧本缺键等未预期异常也收敛为 FAIL（docstring 契约），报告仍产出
        runtime_failures.append({"name": "unexpected", "pass": False,
                                 "detail": f"{type(exc).__name__}: {exc}"})
    finally:
        if conversation_uuid:
            client.delete_conversation(conversation_uuid)

    # 聚合全轮结果
    tool_calls: list[dict] = []
    suspensions: list[str] = []
    ref_embedding = False
    answer_parts: list[str] = []
    for rnd in rounds:
        tool_calls.extend(rnd["tool_calls"])
        if rnd["suspension"]:
            suspensions.append(rnd["suspension"])
        answer_parts.append(rnd["answer"])
        meta_answer = (rnd["meta"] or {}).get("answer") or {}
        if meta_answer.get("isRefEmbedding"):
            ref_embedding = True
    agg = {
        "answer": "\n".join(part for part in answer_parts if part),
        "tool_calls": tool_calls,
        "suspensions": suspensions,
        "ref_embedding": ref_embedding,
    }

    assertion_results = evaluate_expectations(scenario.get("expect") or {}, agg)
    failures = runtime_failures + [r for r in assertion_results if not r["pass"]]
    status = STATUS_PASS if not failures else STATUS_FAIL

    evidence = {
        "rounds": len(rounds),
        "toolCalls": [
            {
                "toolName": tc.get("toolName"),
                "success": tc.get("success"),
                "args": _excerpt(tc.get("args"), 120),
                "resultSummary": _excerpt(tc.get("resultSummary"), 160),
            }
            for tc in tool_calls[:16]
        ],
        "suspensions": suspensions,
        "answerExcerpt": agg["answer"][:600],
        "error": next((r["detail"] for r in runtime_failures), None),
        "conversationUuid": conversation_uuid,
    }
    return {
        "id": scenario["id"],
        "category": scenario["category"],
        "status": status,
        "imageGated": bool(scenario.get("imageGated")),
        "failures": [{"name": f["name"], "detail": f["detail"]} for f in failures],
        "evidence": evidence,
    }


def _excerpt(text, limit: int):
    value = "" if text is None else str(text)
    return value if len(value) <= limit else value[:limit] + "…"


# ----------------------------------------------------------------------------
# 报告输出
# ----------------------------------------------------------------------------

def build_report(run_id: str, started_at: str, finished_at: str, strict_image: bool,
                 filters: dict, records: list) -> tuple:
    """返回 (results_dict, report_md_text)。"""
    by_category = {}
    for cat in CATEGORIES:
        rows = [r for r in records if r["category"] == cat]
        by_category[cat] = {
            "total": len(rows),
            STATUS_PASS: sum(1 for r in rows if r["status"] == STATUS_PASS),
            STATUS_FAIL: sum(1 for r in rows if r["status"] == STATUS_FAIL),
            STATUS_SKIP_IMAGE: sum(1 for r in rows if r["status"] == STATUS_SKIP_IMAGE),
            STATUS_SKIP_PREREQ: sum(1 for r in rows if r["status"] == STATUS_SKIP_PREREQ),
        }
    counter = {}
    for r in records:
        counter[r["status"]] = counter.get(r["status"], 0) + 1
    graded = counter.get(STATUS_PASS, 0) + counter.get(STATUS_FAIL, 0)
    pass_rate = round(counter.get(STATUS_PASS, 0) / graded, 4) if graded else None

    results = {
        "runId": run_id,
        "startedAt": started_at,
        "finishedAt": finished_at,
        "strictImage": strict_image,
        "filters": filters,
        "summary": {
            "total": len(records),
            STATUS_PASS: counter.get(STATUS_PASS, 0),
            STATUS_FAIL: counter.get(STATUS_FAIL, 0),
            STATUS_SKIP_IMAGE: counter.get(STATUS_SKIP_IMAGE, 0),
            STATUS_SKIP_PREREQ: counter.get(STATUS_SKIP_PREREQ, 0),
            "passRate": pass_rate,
            "byCategory": by_category,
        },
        "scenarios": records,
    }

    status_label = {
        STATUS_PASS: "PASS", STATUS_FAIL: "FAIL",
        STATUS_SKIP_IMAGE: "SKIP(镜像门控)", STATUS_SKIP_PREREQ: "SKIP(前置失败)",
    }
    lines = []
    lines.append(f"# 角色级 eval 跑分报告（run-id: {run_id}）")
    lines.append("")
    lines.append(f"- 运行区间：{started_at} ~ {finished_at}")
    lines.append(f"- 镜像口径：{'STRICT（ZHIMESH_EVAL_STRICT_IMAGE=1，imageGated 场景真跑）' if strict_image else '基线现镜像（imageGated 场景默认跳过）'}")
    if filters:
        lines.append(f"- 过滤条件：{json.dumps(filters, ensure_ascii=False)}")
    lines.append("")
    lines.append("## 总览")
    lines.append("")
    lines.append(f"- 场景总数：{len(records)}")
    lines.append(f"- PASS：{counter.get(STATUS_PASS, 0)}；FAIL：{counter.get(STATUS_FAIL, 0)}；"
                 f"镜像门控跳过：{counter.get(STATUS_SKIP_IMAGE, 0)}；前置失败跳过：{counter.get(STATUS_SKIP_PREREQ, 0)}")
    rate_text = f"{pass_rate:.1%}" if pass_rate is not None else "无生效场景"
    lines.append(f"- 通过率（PASS / (PASS+FAIL)）：{rate_text}")
    lines.append("")
    lines.append("## 按 category 分组")
    lines.append("")
    lines.append("| category | 总数 | PASS | FAIL | 镜像门控跳过 | 前置失败 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for cat in CATEGORIES:
        row = by_category[cat]
        lines.append(
            f"| {cat} | {row['total']} | {row[STATUS_PASS]} | {row[STATUS_FAIL]} "
            f"| {row[STATUS_SKIP_IMAGE]} | {row[STATUS_SKIP_PREREQ]} |"
        )
    lines.append("")
    gated = [r for r in records if r["imageGated"]]
    lines.append("## imageGated 场景清单")
    lines.append("")
    if gated:
        lines.append("| 场景 | 域 | category | 本次状态 |")
        lines.append("| --- | --- | --- | --- |")
        for r in gated:
            domain = r.get("domain", "")
            lines.append(f"| {r['id']} | {domain} | {r['category']} | {status_label[r['status']]} |")
    else:
        lines.append("（本次运行未包含 imageGated 场景）")
    lines.append("")
    lines.append("## 逐场景结果")
    lines.append("")
    lines.append("| 场景 | 域 | category | 状态 | 失败摘要 |")
    lines.append("| --- | --- | --- | --- | --- |")
    for r in records:
        if r["failures"]:
            first = r["failures"][0]
            brief = f"{first['name']}: {first['detail'][:120]}"
            extra = f"（共 {len(r['failures'])} 项）" if len(r["failures"]) > 1 else ""
            summary = brief + extra
        else:
            summary = ""
        summary = summary.replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {r['id']} | {r.get('domain', '')} | {r['category']} "
            f"| {status_label[r['status']]} | {summary} |"
        )
    lines.append("")
    lines.append("> 失败详情与关键证据（工具轨迹 / 挂起类型 / 答案节选）见同目录 results.json。")
    lines.append("")
    return results, "\n".join(lines)


# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="T6 角色级 eval 跑分器：固定剧本驱动真实后端聊天并断言（stdlib-only）")
    parser.add_argument("--smoke", action="store_true",
                        help="只跑每域第 1 条场景（连通性冒烟）")
    parser.add_argument("--domain", default=",".join(DOMAINS),
                        help="逗号分隔的域：hr,it,finance（默认全部）")
    parser.add_argument("--only", default="",
                        help="逗号分隔的场景 id 白名单（跨域查打）")
    parser.add_argument("--list", action="store_true", help="列出场景清单后退出（不连后端）")
    parser.add_argument("--run-id", default="", help="覆盖自动生成的 run-id（目录名）")
    parser.add_argument("--pause", type=float, default=1.0,
                        help="场景之间的间隔秒数（默认 1.0，给后端喘息）")
    parser.add_argument("--output-root", default=str(DEFAULT_REPORT_ROOT),
                        help=f"报告输出根目录（默认 {DEFAULT_REPORT_ROOT}）")
    return parser.parse_args()


def select_scenarios(args) -> list:
    """按 --domain/--smoke/--only 过滤，返回 [(domain, spec, scenario)]。"""
    domains = [d.strip() for d in args.domain.split(",") if d.strip()]
    for domain in domains:
        if domain not in DOMAINS:
            raise SystemExit(f"未知域 {domain!r}（可选：{','.join(DOMAINS)}）")
    only = {o.strip() for o in args.only.split(",") if o.strip()}
    selected = []
    for domain in domains:
        spec = load_domain_scenarios(domain)
        scenarios = spec["scenarios"]
        if only:
            scenarios = [sc for sc in scenarios if sc["id"] in only]
        elif args.smoke:
            scenarios = scenarios[:1]
        for scenario in scenarios:
            selected.append((domain, spec, scenario))
    if only:
        found = {sc["id"] for _, _, sc in selected}
        missing = sorted(only - found)
        if missing:
            raise SystemExit(f"--only 中的场景不存在：{', '.join(missing)}")
    if not selected:
        raise SystemExit("过滤后没有可运行的场景（检查 --domain/--only/--smoke）")
    return selected


def main() -> int:
    args = parse_args()
    load_dotenv(SCRIPT_DIR / ".env")

    selected = select_scenarios(args)
    domains_in_run = sorted({domain for domain, _, _ in selected})

    if args.list:
        print(f"{'场景 id':<38} {'域':<9} {'category':<14} imageGated")
        for domain, _, scenario in selected:
            gated = "是" if scenario.get("imageGated") else "-"
            print(f"{scenario['id']:<38} {domain:<9} {scenario['category']:<14} {gated}")
        print(f"共 {len(selected)} 条场景")
        return 0

    base_url = env_str("ZHIMESH_EVAL_BASE_URL", DEFAULT_BASE_URL)
    email = env_str("ZHIMESH_EVAL_USER_EMAIL")
    password = env_str("ZHIMESH_EVAL_USER_PASSWORD")
    strict_image = env_str("ZHIMESH_EVAL_STRICT_IMAGE") == "1"
    http_timeout = env_int("ZHIMESH_EVAL_HTTP_TIMEOUT", 30)
    sse_timeout = env_int("ZHIMESH_EVAL_SSE_TIMEOUT", 320)

    filters = {"domains": domains_in_run, "smoke": args.smoke}
    if args.only:
        filters["only"] = sorted({o.strip() for o in args.only.split(",") if o.strip()})

    # 前置裁剪：imageGated 场景在基线模式下直接跳过（不连后端）
    records = []
    runnable = []
    for domain, spec, scenario in selected:
        record = {
            "id": scenario["id"],
            "domain": domain,
            "category": scenario["category"],
            "status": STATUS_SKIP_PREREQ,  # 初始化为"未执行"；门控/前置失败/真跑结果各自覆盖
            "imageGated": bool(scenario.get("imageGated")),
            "failures": [],
            "evidence": {"reason": "场景未执行（运行中断或前置失败）"},
        }
        if scenario.get("imageGated") and not strict_image:
            record["status"] = STATUS_SKIP_IMAGE
            record["evidence"] = {"reason": scenario["imageGated"].get("reason", "")}
            records.append(record)
        else:
            runnable.append((domain, spec, scenario, record))
            records.append(record)

    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    # 执行主体放 try/finally：任何退出路径（含登录失败 SystemExit/未预期异常）都保证报告落盘
    try:
        if runnable:
            if not email or not password:
                for _, _, _, record in runnable:
                    record["evidence"] = {"reason": "缺少凭据（ZHIMESH_EVAL_USER_EMAIL/PASSWORD）"}
                raise SystemExit(
                    "缺少凭据：请设置 ZHIMESH_EVAL_USER_EMAIL / ZHIMESH_EVAL_USER_PASSWORD"
                    "（可复制 .env.example 为 .env；评估账号须已存在，runner 不创建账号）")
            print(f"[setup] 登录 {email} @ {base_url}")
            client = Client(base_url, "", http_timeout, sse_timeout)
            try:
                client.token = client.login(email, password)
            except ApiError as exc:
                for _, _, _, record in runnable:
                    record["evidence"] = {"reason": f"登录失败：{exc}"}
                raise SystemExit(
                    f"登录失败：{exc}\n前置条件：评估账号（李四）须已存在且可登录，"
                    "runner 不创建账号（见 README.md）")
            # 逐域幂等实例化角色 + 启用 MCP；失败整域 SKIPPED_PREREQ
            character_uuids = {}
            domain_specs = {}
            failed_domains = set()
            for domain, spec, _, _ in runnable:
                domain_specs.setdefault(domain, spec)
            for domain in sorted(domain_specs):
                spec = domain_specs[domain]
                try:
                    dto = client.add_by_preset(spec["presetUuid"])
                    client.enable_mcp(int(spec["mcpId"]))
                    character_uuids[domain] = str(dto["uuid"])
                    print(f"[setup] {domain} 角色就绪 uuid={dto['uuid']} mcp={spec['mcpId']} 已启用")
                except ApiError as exc:
                    failed_domains.add(domain)
                    print(f"[setup] {domain} 前置失败：{exc}")
            for domain, spec, scenario, record in runnable:
                if domain in failed_domains:
                    record["status"] = STATUS_SKIP_PREREQ
                    record["evidence"] = {"reason": f"域 {domain} 前置失败（实例化/启用 MCP），场景未运行"}
            # 逐场景执行（断言失败不中断；run_scenario 内部已收敛一切异常）
            pending = [item for item in runnable if item[0] not in failed_domains]
            for position, (domain, spec, scenario, record) in enumerate(pending):
                print(f"[run] ({position + 1}/{len(pending)}) {scenario['id']} ({domain}/{scenario['category']})")
                outcome = run_scenario(client, scenario, character_uuids[domain])
                record["status"] = outcome["status"]
                record["failures"] = outcome["failures"]
                record["evidence"] = outcome["evidence"]
                mark = {"PASS": "[PASS]", "FAIL": "[FAIL]"}.get(outcome["status"], "[STOP]")
                detail = ""
                if outcome["failures"]:
                    detail = " — " + "; ".join(f["name"] for f in outcome["failures"][:3])
                print(f"{mark} {scenario['id']}{detail}")
                if position < len(pending) - 1 and args.pause > 0:
                    time.sleep(args.pause)
    finally:
        finished_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        run_id = args.run_id or datetime.now().strftime("%Y%m%d-%H%M%S")
        output_root = Path(args.output_root)
        output_dir = output_root / run_id
        output_dir.mkdir(parents=True, exist_ok=True)

        results, report_md = build_report(
            run_id, started_at, finished_at, strict_image, filters, records)
        results_path = output_dir / "results.json"
        report_path = output_dir / "report.md"
        results_path.write_text(
            json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report_path.write_text(report_md, encoding="utf-8")
        report_written = (results_path, report_path)

    results_path, report_path = report_written
    summary = results["summary"]
    pass_rate = summary["passRate"]
    rate_text = "无生效场景" if pass_rate is None else f"{pass_rate:.1%}"
    print("")
    print(f"[report] 通过率 {rate_text}"
          f"（PASS {summary[STATUS_PASS]} / FAIL {summary[STATUS_FAIL]}，"
          f"门控跳过 {summary[STATUS_SKIP_IMAGE]}，前置跳过 {summary[STATUS_SKIP_PREREQ]}）")
    print(f"[report] {results_path}")
    print(f"[report] {report_path}")
    # FAIL 或前置跳过都非零：前置失败说明本次结果不完整，不能当绿
    return 1 if (summary[STATUS_FAIL] or summary[STATUS_SKIP_PREREQ]) else 0


if __name__ == "__main__":
    sys.exit(main())
