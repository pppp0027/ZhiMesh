#!/usr/bin/env python3
"""清理 scale_accounts.py 解析 bug 造成的重复压测角色。

规则：同名用户下若有多个「并发测试角色」，保留 accounts JSON 正在引用的那个
（其次保留 id 最小的原始角色），其余的在没有消息记录的前提下物理删除。
最后校验：每用户恰好 1 个角色，JSON 50 条全部有效。
"""
from __future__ import annotations

import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ACCOUNTS = Path(__file__).resolve().parent.parent / "configs" / "concurrency-accounts.server.json"
PG = ["docker", "exec", "pgvector", "psql", "-U", "root", "-d", "ai_rag_knowledge", "-t", "-A", "-c"]


def psql(sql: str) -> str:
    out = subprocess.run(PG + [sql], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def main() -> None:
    json_uuids = {a["character_uuid"] for a in json.loads(ACCOUNTS.read_text())["accounts"]}

    rows = [
        r.split("|")
        for r in psql(
            "SELECT c.id, c.uuid, u.email FROM adi_character c "
            "JOIN adi_user u ON u.id=c.user_id "
            "WHERE c.title LIKE '%并发测试角色%' ORDER BY c.id;"
        ).splitlines() if "|" in r
    ]
    by_user: dict[str, list[tuple[str, str]]] = defaultdict(list)  # email -> [(id, uuid)]
    for cid, cuuid, email in rows:
        by_user[email].append((cid, cuuid))

    deleted = fixed_json = 0
    for email, chars in by_user.items():
        if len(chars) <= 1:
            continue
        # 保留优先级：JSON 引用的 > id 最小的
        keep = next((c for c in chars if c[1] in json_uuids), chars[0])
        for cid, cuuid in chars:
            if (cid, cuuid) == keep:
                continue
            n_msg = psql(f"SELECT count(*) FROM adi_character_message WHERE character_id={cid};")
            if int(n_msg) > 0:
                print(f"SKIP char {cid} ({email}): has {n_msg} messages")
                continue
            psql(f"DELETE FROM adi_character WHERE id={cid};")
            deleted += 1
        # 若 JSON 引用的是被删角色（keep 不在 json_uuids 里），改指回保留角色
        if keep[1] not in json_uuids and email in {a["email"] for a in json.loads(ACCOUNTS.read_text())["accounts"]}:
            payload = json.loads(ACCOUNTS.read_text())
            for a in payload["accounts"]:
                if a["email"] == email:
                    a["character_uuid"] = keep[1]
                    fixed_json += 1
            ACCOUNTS.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    # 终态校验
    dup = psql(
        "SELECT count(*) FROM (SELECT user_id FROM adi_character "
        "WHERE title LIKE '%并发测试角色%' GROUP BY user_id HAVING count(*)>1) t;"
    )
    n = len(json.loads(ACCOUNTS.read_text())["accounts"])
    alive = {
        r for r in psql(
            "SELECT uuid FROM adi_character WHERE title LIKE '%并发测试角色%';"
        ).splitlines() if r
    }
    dangling = json_uuids - alive if False else {a["character_uuid"] for a in json.loads(ACCOUNTS.read_text())["accounts"]} - alive
    print(f"deleted={deleted} json_fixed={fixed_json} users_with_dup_now={dup} json_accounts={n} dangling_json_refs={len(dangling)}")
    if dup != "0" or dangling:
        sys.exit(1)


if __name__ == "__main__":
    main()
