#!/usr/bin/env python3
"""二次清理：JSON 改指回各用户 id 最小的原始角色，删除其余无消息的重复角色。"""
from __future__ import annotations

import json
import subprocess
from collections import defaultdict
from pathlib import Path

ACCOUNTS = Path(__file__).resolve().parent.parent / "configs" / "concurrency-accounts.server.json"
PG = ["docker", "exec", "pgvector", "psql", "-U", "root", "-d", "ai_rag_knowledge", "-t", "-A", "-c"]


def psql(sql: str) -> str:
    return subprocess.run(PG + [sql], capture_output=True, text=True, check=True).stdout.strip()


def main() -> None:
    payload = json.loads(ACCOUNTS.read_text())
    email_to_uuid = {a["email"]: a["character_uuid"] for a in payload["accounts"]}

    rows = [
        r.split("|")
        for r in psql(
            "SELECT c.id, c.uuid, u.email FROM adi_character c "
            "JOIN adi_user u ON u.id=c.user_id "
            "WHERE c.title LIKE '%并发测试角色%' ORDER BY c.id;"
        ).splitlines() if "|" in r
    ]
    by_user: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for cid, cuuid, email in rows:
        by_user[email].append((cid, cuuid))

    deleted = fixed = 0
    for email, chars in by_user.items():
        if len(chars) <= 1:
            continue
        keep = min(chars, key=lambda c: int(c[0]))  # 原始角色 = id 最小
        for cid, cuuid in chars:
            if (cid, cuuid) == keep:
                continue
            n = int(psql(f"SELECT count(*) FROM adi_character_message WHERE character_id={cid};"))
            if n > 0:
                print(f"SKIP {cid} ({email}): {n} messages")
                continue
            psql(f"DELETE FROM adi_character WHERE id={cid};")
            deleted += 1
        if email in email_to_uuid and email_to_uuid[email] != keep[1]:
            for a in payload["accounts"]:
                if a["email"] == email:
                    a["character_uuid"] = keep[1]
                    fixed += 1

    ACCOUNTS.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    dup = psql(
        "SELECT count(*) FROM (SELECT user_id FROM adi_character "
        "WHERE title LIKE '%并发测试角色%' GROUP BY user_id HAVING count(*)>1) t;"
    )
    alive = {r for r in psql("SELECT uuid FROM adi_character WHERE title LIKE '%并发测试角色%';").splitlines() if r}
    dangling = {a["character_uuid"] for a in payload["accounts"]} - alive
    n_chars = psql("SELECT count(*) FROM adi_character WHERE title LIKE '%并发测试角色%';")
    print(
        f"deleted={deleted} json_fixed={fixed} users_with_dup={dup} "
        f"chars={n_chars} json_accounts={len(payload['accounts'])} dangling={len(dangling)}"
    )
    assert dup == "0" and not dangling and n_chars == "50", "cleanup incomplete"


if __name__ == "__main__":
    main()
