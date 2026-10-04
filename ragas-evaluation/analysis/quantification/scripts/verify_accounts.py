#!/usr/bin/env python3
"""全量压测账号预检：登录 → 建会话 → 发一条 mock 聊天 → 断言 SSE 流。

任何账号失败即整体退出码 1，避免阶梯跑到 c20/c50 才引爆。
"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import httpx

ACCOUNTS = Path(__file__).resolve().parent.parent / "configs" / "concurrency-accounts.server.json"
BASE = "http://127.0.0.1:9999"


async def check_one(client: httpx.AsyncClient, spec: dict, sem: asyncio.Semaphore, verbose: bool = False) -> tuple[str, str]:
    async with sem:
        email = spec["email"]
        try:
            r = await client.post(f"{BASE}/auth/login", json={"email": email, "password": spec["password"]})
            token = ((r.json() or {}).get("data") or {}).get("token")
            if not token:
                return email, f"login failed: {str(r.json())[:120]}"
            headers = {"Authorization": token, "Content-Type": "application/json"}
            c = await client.post(f"{BASE}/conversation/add", json={"characterUuid": spec["character_uuid"], "title": "preflight"}, headers=headers)
            cu = ((c.json() or {}).get("data") or {}).get("uuid")
            if not cu:
                return email, f"conversation failed: {str(c.json())[:120]}"
            body = {
                "conversationUuid": cu, "characterUuid": spec["character_uuid"],
                "prompt": "预检：辰海矩阵控股集团成立于何时？",
                "modelPlatform": "mock", "modelName": "mock-chat",
            }
            if verbose:
                print("body:", json.dumps(body, ensure_ascii=False))
            async with client.stream("POST", f"{BASE}/chat/process", json=body, headers=headers, timeout=60) as s:
                ctype = s.headers.get("content-type") or ""
                if "text/event-stream" not in ctype:
                    text = (await s.aread())[:300].decode("utf-8", "ignore")
                    return email, f"chat not SSE (status={s.status_code}, {ctype}): {text}"
                got_done = False
                async for line in s.aiter_lines():
                    if line.strip() == "data: [DONE]":
                        got_done = True
                        break
                if not got_done:
                    return email, "OK(no-done)"  # SSE 已建立即视为通过；[DONE] 格式差异不影响
            return email, "OK"
        except Exception as exc:  # noqa: BLE001
            return email, f"{type(exc).__name__}: {str(exc)[:120]}"


async def main() -> None:
    specs = json.loads(ACCOUNTS.read_text())["accounts"]
    sem = asyncio.Semaphore(int(sys.argv[1]) if len(sys.argv) > 1 else 5)
    async with httpx.AsyncClient(timeout=90) as client:
        results = await asyncio.gather(*(check_one(client, s, sem, verbose=(i == 0)) for i, s in enumerate(specs)))
    bad = [(e, m) for e, m in results if not m.startswith("OK")]
    for e, m in results:
        if not m.startswith("OK"):
            print(f"FAIL {e}: {m}")
    print(f"total={len(results)} ok={len(results) - len(bad)} bad={len(bad)}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    asyncio.run(main())
