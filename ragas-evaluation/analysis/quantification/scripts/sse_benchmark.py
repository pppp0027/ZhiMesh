from __future__ import annotations

import argparse
import asyncio
import csv
import json
import math
import os
import statistics
import sys
import time
from collections import defaultdict, deque
from contextlib import AsyncExitStack
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, AsyncIterator

PROJECT_ROOT = Path(__file__).resolve().parents[3]
CORE_SCRIPTS = PROJECT_ROOT / "scripts" / "core"
if str(CORE_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(CORE_SCRIPTS))

import httpx
from dotenv import load_dotenv

from common import load_config, read_jsonl, resolve_path


SPECIAL_EVENTS = {
    "[START]",
    "[DONE]",
    "[ERROR]",
    "[THINKING]",
    "[STATE_CHANGED]",
    "[TOOL_CALL]",
    "[AUDIO]",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Measure real /chat/process SSE latency")
    parser.add_argument("--config", default="analysis/quantification/configs/quantification-config.json")
    parser.add_argument("--mode", choices=["serial", "concurrency", "interrupt"], default="serial")
    parser.add_argument("--sample-count", type=int)
    parser.add_argument("--rounds", type=int)
    parser.add_argument("--concurrency", type=int)
    parser.add_argument("--requests", type=int)
    parser.add_argument("--ids-file")
    parser.add_argument("--output-dir")
    parser.add_argument("--base-url", help="API base URL, e.g. http://127.0.0.1/api")
    parser.add_argument("--dataset", help="JSONL dataset path on this machine")
    parser.add_argument("--character-uuid", help="Benchmark user's Character UUID")
    parser.add_argument(
        "--accounts-file",
        help="JSON file containing dedicated benchmark accounts and Character UUIDs",
    )
    parser.add_argument("--warmups", type=int, help="Override warm-up request count")
    parser.add_argument("--insecure", action="store_true", help="Disable TLS certificate verification")
    parser.add_argument("--check-only", action="store_true", help="Validate API, auth and configuration without sending chat requests")
    return parser.parse_args()


def finite(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def nearest_rank(values: list[float], probability: float) -> float | None:
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[max(0, math.ceil(len(ordered) * probability) - 1)]


def unwrap(payload: Any) -> Any:
    if isinstance(payload, dict) and "success" in payload:
        if not payload.get("success"):
            raise RuntimeError(f"API error {payload.get('code')}: {payload.get('message')}")
        return payload.get("data")
    return payload


def validate_character_uuid(value: Any, source: str) -> str:
    character_uuid = str(value or "").strip()
    if not character_uuid or character_uuid.startswith("REPLACE_"):
        raise SystemExit(f"Character UUID is missing in {source}")
    if len(character_uuid) != 32:
        raise SystemExit(f"Character UUID in {source} must be 32 characters without hyphens")
    return character_uuid


def load_account_specs(path: str | Path) -> list[dict[str, str]]:
    account_path = resolve_path(path)
    with account_path.open("r", encoding="utf-8-sig") as handle:
        payload = json.load(handle)
    accounts = payload.get("accounts") if isinstance(payload, dict) else payload
    if not isinstance(accounts, list) or not accounts:
        raise SystemExit(f"No accounts found in {account_path}")

    specs: list[dict[str, str]] = []
    seen_emails: set[str] = set()
    for index, item in enumerate(accounts, 1):
        if not isinstance(item, dict):
            raise SystemExit(f"Account #{index} in {account_path} must be an object")
        email = str(item.get("email", "")).strip().lower()
        password = str(item.get("password", ""))
        token = str(item.get("token", "")).strip()
        if not email:
            raise SystemExit(f"Account #{index} in {account_path} has no email")
        if email in seen_emails:
            raise SystemExit(f"Duplicate account email in {account_path}: {email}")
        if not token and not password:
            raise SystemExit(f"Account {email} needs password or token")
        seen_emails.add(email)
        specs.append(
            {
                "email": email,
                "password": password,
                "token": token,
                "character_uuid": validate_character_uuid(
                    item.get("character_uuid"), f"account {email}"
                ),
            }
        )
    return specs


async def login_token(
    base_url: str,
    email: str,
    password: str,
    verify_tls: bool,
) -> str:
    async with httpx.AsyncClient(timeout=30, verify=verify_tls) as login_client:
        response = await login_client.post(
            base_url + "/auth/login",
            json={"email": email, "password": password},
        )
        response.raise_for_status()
        login_data = unwrap(response.json())
        if not isinstance(login_data, dict) or not login_data.get("token"):
            raise SystemExit(f"Login succeeded but API returned no token for {email}")
        return str(login_data["token"])


def select_stratified(dataset: list[dict[str, Any]], count: int) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str], deque[dict[str, Any]]] = defaultdict(deque)
    for row in dataset:
        groups[(str(row.get("question_type")), str(row.get("difficulty")))].append(row)
    selected: list[dict[str, Any]] = []
    keys = sorted(groups)
    while len(selected) < count and keys:
        next_keys: list[tuple[str, str]] = []
        for key in keys:
            if groups[key] and len(selected) < count:
                selected.append(groups[key].popleft())
            if groups[key]:
                next_keys.append(key)
        keys = next_keys
    return selected


async def sse_events(response: httpx.Response) -> AsyncIterator[tuple[str, str]]:
    event_name = ""
    data_lines: list[str] = []
    async for line in response.aiter_lines():
        if line == "":
            if event_name or data_lines:
                yield event_name, "\n".join(data_lines)
            event_name = ""
            data_lines = []
        elif line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    if event_name or data_lines:
        yield event_name, "\n".join(data_lines)


class Benchmark:
    def __init__(self, config: dict[str, Any], token: str):
        self.config = config
        self.base_url = str(config.get("base_url", "http://127.0.0.1/api")).rstrip("/")
        self.endpoint = self.base_url + str(config.get("sse_endpoint", "/chat/process"))
        self.token = token
        self.character_uuid = str(config["character_uuid"])
        self.timeout = float(config.get("sse_timeout_seconds", 180))
        self.manage_conversations = bool(config.get("manage_conversations", True))

    async def preflight(self, client: httpx.AsyncClient) -> None:
        public_response = await client.get(self.base_url + "/auth/search-engine/list")
        public_response.raise_for_status()
        try:
            unwrap(public_response.json())
        except (ValueError, TypeError, RuntimeError) as exc:
            raise RuntimeError(
                "API preflight did not return valid JSON; check that base_url includes /api when using the Compose gateway"
            ) from exc
        if self.manage_conversations:
            auth_response = await client.get(
                self.base_url + "/conversation/list",
                params={"currentPage": 1, "pageSize": 1},
            )
            auth_response.raise_for_status()
            try:
                unwrap(auth_response.json())
            except (ValueError, TypeError, RuntimeError) as exc:
                raise RuntimeError("Authenticated Conversation preflight returned invalid JSON") from exc

    async def create_conversation(self, client: httpx.AsyncClient, title: str) -> str | None:
        if not self.manage_conversations:
            return None
        response = await client.post(
            self.base_url + "/conversation/add",
            json={"characterUuid": self.character_uuid, "title": title[:100]},
        )
        response.raise_for_status()
        data = unwrap(response.json())
        if not isinstance(data, dict) or not data.get("uuid"):
            raise RuntimeError("Conversation API returned no uuid")
        return str(data["uuid"])

    async def delete_conversation(
        self, client: httpx.AsyncClient, conversation_uuid: str | None
    ) -> None:
        if not conversation_uuid:
            return
        response = await client.post(
            self.base_url + f"/conversation/del/{conversation_uuid}"
        )
        response.raise_for_status()

    async def request(
        self,
        client: httpx.AsyncClient,
        row: dict[str, Any],
        run_id: str,
        abort_after_first_token: bool = False,
    ) -> dict[str, Any]:
        conversation_uuid: str | None = None
        started = time.perf_counter()
        first_token_at: float | None = None
        done_at: float | None = None
        error_event = False
        token_events = 0
        http_status: int | None = None
        error_type: str | None = None
        try:
            conversation_uuid = await self.create_conversation(
                client, f"quant-{run_id}-{row['id']}"
            )
            request_started = time.perf_counter()
            body = {
                "prompt": row["question"],
                "characterUuid": self.character_uuid,
            }
            if conversation_uuid:
                body["conversationUuid"] = conversation_uuid
            async with client.stream("POST", self.endpoint, json=body) as response:
                http_status = response.status_code
                response.raise_for_status()
                content_type = response.headers.get("content-type", "").lower()
                if "text/event-stream" not in content_type:
                    raise RuntimeError(
                        f"Expected text/event-stream, got {content_type or 'missing Content-Type'}"
                    )
                async for event, _data in sse_events(response):
                    now = time.perf_counter()
                    if event == "[ERROR]":
                        error_event = True
                    elif event == "[DONE]":
                        done_at = now
                        break
                    elif event not in SPECIAL_EVENTS:
                        token_events += 1
                        if first_token_at is None:
                            first_token_at = now
                            if abort_after_first_token:
                                break
            finished = time.perf_counter()
        except Exception as exc:
            request_started = locals().get("request_started", started)
            finished = time.perf_counter()
            error_type = type(exc).__name__
            error_message = str(exc)[:500]
        finally:
            try:
                await self.delete_conversation(client, conversation_uuid)
            except Exception:
                if error_type is None:
                    error_type = "ConversationCleanupError"
        return {
            "id": str(row["id"]),
            "run_id": run_id,
            "question_type": row.get("question_type"),
            "difficulty": row.get("difficulty"),
            "http_status": http_status,
            "ttft_ms": round((first_token_at - request_started) * 1000)
            if first_token_at is not None
            else None,
            "full_latency_ms": round((done_at - request_started) * 1000)
            if done_at is not None
            else None,
            "client_elapsed_ms": round((finished - request_started) * 1000),
            "token_event_count": token_events,
            "received_done": done_at is not None,
            "received_error_event": error_event,
            "aborted_after_first_token": abort_after_first_token and first_token_at is not None,
            "success": (
                first_token_at is not None
                and (abort_after_first_token or done_at is not None)
                and not error_event
                and error_type is None
            ),
            "error_type": error_type,
            "error_message": locals().get("error_message"),
        }


@dataclass
class AccountRuntime:
    index: int
    email: str
    benchmark: Benchmark
    client: httpx.AsyncClient
    lock: asyncio.Lock


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    ttft = [float(row["ttft_ms"]) for row in rows if finite(row.get("ttft_ms")) is not None]
    full = [
        float(row["full_latency_ms"])
        for row in rows
        if finite(row.get("full_latency_ms")) is not None
    ]
    elapsed = [
        float(row["client_elapsed_ms"])
        for row in rows
        if finite(row.get("client_elapsed_ms")) is not None
    ]
    wall_seconds = sum(elapsed) / 1000 if elapsed else 0
    return {
        "requests": len(rows),
        "successes": sum(row.get("success") is True for row in rows),
        "success_rate": (
            sum(row.get("success") is True for row in rows) / len(rows) if rows else None
        ),
        "complete_rate": (
            sum(row.get("received_done") is True for row in rows) / len(rows)
            if rows
            else None
        ),
        "ttft_ms": {
            "count": len(ttft),
            "mean": statistics.fmean(ttft) if ttft else None,
            "p50": nearest_rank(ttft, 0.50),
            "p95": nearest_rank(ttft, 0.95),
            "max": max(ttft) if ttft else None,
        },
        "full_latency_ms": {
            "count": len(full),
            "mean": statistics.fmean(full) if full else None,
            "p50": nearest_rank(full, 0.50),
            "p95": nearest_rank(full, 0.95),
            "max": max(full) if full else None,
        },
        "serial_equivalent_qps": len(rows) / wall_seconds if wall_seconds else None,
        "error_types": {
            error_type: sum(row.get("error_type") == error_type for row in rows)
            for error_type in sorted(
                {str(row.get("error_type")) for row in rows if row.get("error_type")}
            )
        },
    }


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]) if rows else [])
        if rows:
            writer.writeheader()
            writer.writerows(rows)


async def main() -> None:
    args = parse_args()
    load_dotenv(resolve_path(".env"))
    config = load_config(args.config)
    config["base_url"] = (
        args.base_url
        or os.getenv("ZHIMESH_API_BASE_URL", "").strip()
        or config.get("base_url")
        or "http://127.0.0.1/api"
    )
    config["dataset"] = (
        args.dataset
        or os.getenv("RAGAS_DATASET", "").strip()
        or config.get("dataset")
    )
    config["character_uuid"] = (
        args.character_uuid
        or os.getenv("ZHIMESH_BENCHMARK_CHARACTER_UUID", "").strip()
        or config.get("character_uuid")
    )
    accounts_file = (
        args.accounts_file
        or os.getenv("ZHIMESH_BENCHMARK_ACCOUNTS_FILE", "").strip()
        or config.get("accounts_file")
    )
    if not config.get("dataset"):
        raise SystemExit("Dataset is missing; use --dataset or RAGAS_DATASET")

    base_url = str(config["base_url"]).rstrip("/")
    verify_tls = not args.insecure
    if accounts_file:
        account_specs = load_account_specs(accounts_file)
    else:
        character_uuid = validate_character_uuid(
            config.get("character_uuid"),
            "--character-uuid or ZHIMESH_BENCHMARK_CHARACTER_UUID",
        )
        token = os.getenv("ZHIMESH_USER_TOKEN", "").strip()
        email = os.getenv("ZHIMESH_USER_EMAIL", "").strip()
        password = os.getenv("ZHIMESH_USER_PASSWORD", "")
        if not token and (not email or not password):
            raise SystemExit(
                "Authentication is missing; set ZHIMESH_USER_TOKEN or both ZHIMESH_USER_EMAIL and ZHIMESH_USER_PASSWORD"
            )
        account_specs = [
            {
                "email": email.lower() or "token-account",
                "password": password,
                "token": token,
                "character_uuid": character_uuid,
            }
        ]

    concurrency = args.concurrency or int(config.get("concurrency", 5))
    if args.mode == "concurrency" and concurrency > len(account_specs):
        raise SystemExit(
            f"Concurrency {concurrency} needs at least {concurrency} accounts because "
            f"one user may run only one SSE request; configured accounts: {len(account_specs)}"
        )

    dataset = read_jsonl(config["dataset"])
    if args.ids_file:
        ids = {
            line.strip()
            for line in resolve_path(args.ids_file).read_text(encoding="utf-8-sig").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        dataset = [row for row in dataset if str(row.get("id")) in ids]
    else:
        dataset = select_stratified(
            dataset, args.sample_count or int(config.get("sse_sample_count", 30))
        )
    if not dataset:
        raise SystemExit("No benchmark samples selected")

    output_dir = resolve_path(
        args.output_dir
        or f"analysis/quantification/results/runs/sse-{args.mode}-{datetime.now():%Y%m%d-%H%M%S}"
    )
    rows: list[dict[str, Any]] = []

    async with AsyncExitStack() as stack:
        if args.check_only:
            selected_specs = account_specs
        elif args.mode == "concurrency":
            selected_specs = account_specs[:concurrency]
        else:
            selected_specs = account_specs[:1]
        runtimes: list[AccountRuntime] = []
        for index, spec in enumerate(selected_specs, 1):
            token = spec["token"] or await login_token(
                base_url, spec["email"], spec["password"], verify_tls
            )
            account_config = dict(config)
            account_config["character_uuid"] = spec["character_uuid"]
            benchmark = Benchmark(account_config, token)
            client = await stack.enter_async_context(
                httpx.AsyncClient(
                    headers={"Authorization": token, "Content-Type": "application/json"},
                    timeout=httpx.Timeout(benchmark.timeout),
                    verify=verify_tls,
                )
            )
            runtimes.append(
                AccountRuntime(index, spec["email"], benchmark, client, asyncio.Lock())
            )

        for runtime in runtimes:
            await runtime.benchmark.preflight(runtime.client)
            print(
                f"preflight=OK account={runtime.index}/{len(runtimes)} "
                f"email={runtime.email} character_uuid={runtime.benchmark.character_uuid}"
            )
        print(
            f"base_url={base_url} dataset={config['dataset']} "
            f"samples={len(dataset)} accounts={len(runtimes)}"
        )
        if args.check_only:
            return

        if args.warmups is not None:
            warmups = args.warmups
        else:
            warmups = int(config.get("sse_warmup_requests", 5))
            if args.mode == "concurrency":
                warmups = max(warmups, concurrency)
        warmup_runtimes = runtimes if args.mode == "concurrency" else runtimes[:1]
        for index in range(warmups):
            runtime = warmup_runtimes[index % len(warmup_runtimes)]
            warmup = await runtime.benchmark.request(
                runtime.client, dataset[index % len(dataset)], f"warmup-{index + 1}"
            )
            if not warmup["success"]:
                raise RuntimeError(
                    f"Warm-up {index + 1} failed for {runtime.email}: "
                    f"{warmup.get('error_type')} {warmup.get('error_message') or ''}".strip()
                )

        primary = runtimes[0]
        if args.mode == "serial":
            rounds = args.rounds or int(config.get("sse_rounds", 2))
            for round_number in range(1, rounds + 1):
                for row in dataset:
                    result = await primary.benchmark.request(
                        primary.client, row, f"serial-r{round_number}-{row['id']}"
                    )
                    result["account_index"] = primary.index
                    result["account_email"] = primary.email
                    rows.append(result)
                    print(f"[{'OK' if result['success'] else 'ERROR'}] {result['run_id']}")
        elif args.mode == "concurrency":
            request_count = args.requests or int(config.get("concurrency_requests", 20))
            eligible = runtimes[:concurrency]
            semaphore = asyncio.Semaphore(max(1, concurrency))
            wall_started = time.perf_counter()

            async def one(index: int) -> dict[str, Any]:
                runtime = eligible[index % len(eligible)]
                async with runtime.lock:
                    async with semaphore:
                        row = dataset[index % len(dataset)]
                        result = await runtime.benchmark.request(
                            runtime.client, row, f"c{concurrency}-{index + 1}"
                        )
                        result["account_index"] = runtime.index
                        result["account_email"] = runtime.email
                        return result

            rows = list(await asyncio.gather(*(one(index) for index in range(request_count))))
            wall_seconds = time.perf_counter() - wall_started
            successful = sum(item["success"] for item in rows)
            for row in rows:
                row["concurrency"] = concurrency
                row["wall_seconds"] = round(wall_seconds, 6)
                row["qps"] = successful / wall_seconds
        else:
            request_count = args.requests or int(config.get("interrupt_requests", 10))
            for index in range(request_count):
                interrupted = await primary.benchmark.request(
                    primary.client,
                    dataset[index % len(dataset)],
                    f"interrupt-{index + 1}",
                    abort_after_first_token=True,
                )
                recovery = await primary.benchmark.request(
                    primary.client,
                    dataset[(index + 1) % len(dataset)],
                    f"recovery-{index + 1}",
                )
                for item in (interrupted, recovery):
                    item["account_index"] = primary.index
                    item["account_email"] = primary.email
                interrupted["pair_role"] = "interrupted"
                recovery["pair_role"] = "recovery"
                interrupted["recovery_success"] = recovery["success"]
                recovery["recovery_success"] = recovery["success"]
                rows.extend([interrupted, recovery])

    summary = summarize(rows)
    summary["benchmark"] = {
        "generated_at": datetime.now().astimezone().isoformat(),
        "mode": args.mode,
        "base_url": base_url,
        "sse_endpoint": primary.benchmark.endpoint,
        "dataset": str(config["dataset"]),
        "accounts_file": str(accounts_file) if accounts_file else None,
        "account_count": len(runtimes),
        "account_emails": [runtime.email for runtime in runtimes],
        "character_uuids": [runtime.benchmark.character_uuid for runtime in runtimes],
        "selected_samples": len(dataset),
        "warmup_requests": warmups,
        "tls_verification": verify_tls,
    }
    if args.mode == "concurrency" and rows:
        summary["wall_seconds"] = rows[0].get("wall_seconds")
        summary["qps"] = rows[0].get("qps")
    if args.mode == "interrupt":
        interrupted_rows = [row for row in rows if row.get("pair_role") == "interrupted"]
        summary["interruption_first_token_rate"] = (
            sum(row.get("aborted_after_first_token") is True for row in interrupted_rows)
            / len(interrupted_rows)
            if interrupted_rows
            else None
        )
        summary["recovery_rate"] = (
            sum(row.get("recovery_success") is True for row in interrupted_rows)
            / len(interrupted_rows)
            if interrupted_rows
            else None
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    write_csv(output_dir / "sse_latency_per_sample.csv", rows)
    (output_dir / "sse_latency_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"output={output_dir}")


if __name__ == "__main__":
    asyncio.run(main())
