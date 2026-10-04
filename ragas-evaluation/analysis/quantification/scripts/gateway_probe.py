#!/usr/bin/env python3
"""opencode zen/go/v1 网关并发探针（小体量，测网关自身并发曲线）。

从 DB 读平台密钥（不落日志）；阶梯 1/2/5/10 并发 × ROUNDS 轮，短 prompt、
max_tokens=16，记录每调用 TTFT/总时/错误。产出 gateway_probe_report.json。
"""
from __future__ import annotations

import asyncio
import json
import statistics
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

import httpx

LEVELS = [1, 2, 5, 10]
ROUNDS = 2
PER_LEVEL_REQUESTS = 4  # 每档总请求数（跨轮分摊）
PROMPT = "用一句话介绍你自己。"
MAX_TOKENS = 16
MODEL = "deepseek-v4.1-flash"
BASE_URL = "https://opencode.ai/zen/go/v1"
SESSION = "zhimesh-gateway-probe"
TIMEOUT = 120


def gateway_key() -> str:
    out = subprocess.run(
        ["docker", "exec", "pgvector", "psql", "-U", "root", "-d", "ai_rag_knowledge",
         "-t", "-A", "-c",
         "SELECT api_key FROM adi_model_platform WHERE name='opencode'"],
        capture_output=True, text=True, check=True)
    key = out.stdout.strip()
    if not key.startswith("oc_sk"):
        raise SystemExit("Unexpected gateway key from DB")
    return key


async def one_call(client: httpx.AsyncClient, sem: asyncio.Semaphore, idx: int):
    async with sem:
        t0 = time.perf_counter()
        ttft = None
        err = None
        try:
            async with client.stream("POST", BASE_URL + "/chat/completions",
                                     json={"model": MODEL, "stream": True,
                                           "max_tokens": MAX_TOKENS,
                                           "messages": [{"role": "user", "content": PROMPT}]}) as r:
                r.raise_for_status()
                got_data = False
                async for line in r.aiter_lines():
                    if line.startswith("data:") and "[DONE]" not in line:
                        now = time.perf_counter()
                        if ttft is None and line.strip() != "data:":
                            ttft = now - t0
                        got_data = True
                if not got_data:
                    err = "no-data"
        except Exception as exc:  # noqa: BLE001
            err = f"{type(exc).__name__}: {str(exc)[:120]}"
        return {"idx": idx, "ttft_s": ttft, "total_s": time.perf_counter() - t0, "error": err}


async def main():
    key = gateway_key()
    headers = {"Authorization": f"Bearer {key}", "x-opencode-session": SESSION}
    results = {}
    async with httpx.AsyncClient(timeout=TIMEOUT, headers=headers) as client:
        for level in LEVELS:
            rows = []
            for _round in range(ROUNDS):
                batch = max(level, PER_LEVEL_REQUESTS // ROUNDS)
                sem = asyncio.Semaphore(level)
                t0 = time.perf_counter()
                rows += await asyncio.gather(*(
                    one_call(client, sem, i) for i in range(batch)))
                wall = time.perf_counter() - t0
                ok = [r for r in rows if r["error"] is None]
                print(f"c{level}: batch={batch} wall={wall:.1f}s ok={len(ok)}/{len(rows)} "
                      f"ttft_p50={statistics.median([r['ttft_s'] for r in ok]) if ok else 'NA':.2f}"
                      if ok else f"c{level}: ALL FAILED")
                await asyncio.sleep(3)
            results[f"c{level}"] = rows
    report = {
        "generated_at": datetime.now().astimezone().isoformat(),
        "model": MODEL, "levels": LEVELS, "rounds": ROUNDS,
        "max_tokens": MAX_TOKENS, "results": results,
    }
    out = Path(__file__).resolve().parent.parent / "results" / "gateway-probe" / (
        f"gateway_probe_{datetime.now():%Y%m%d-%H%M%S}.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"report={out}")


if __name__ == "__main__":
    asyncio.run(main())
