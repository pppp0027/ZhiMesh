#!/usr/bin/env python3
"""把并发压测账号扩容到 TARGET 个（默认 50）。

做法：以 ceshi01 为模板复制 adi_user 行（同密码哈希、同配额），再为其复制
ceshi01 的压测角色（adi_character，kb_ids=3 同配置、新 uuid），最后重写
concurrency-accounts.server.json（密码沿用 ceshi01 在文件里的密码）。

幂等：已存在的 email 跳过。仅在压测服务器上运行（依赖 docker exec psql）。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 50
ACCOUNTS = Path(__file__).resolve().parent.parent / "configs" / "concurrency-accounts.server.json"
PG = ["docker", "exec", "pgvector", "psql", "-U", "root", "-d", "ai_rag_knowledge", "-t", "-A", "-c"]


def psql(sql: str) -> str:
    out = subprocess.run(PG + [sql], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def main() -> None:
    payload = json.loads(ACCOUNTS.read_text(encoding="utf-8"))
    accounts = payload["accounts"]
    template_email = accounts[0]["email"]
    template_password = accounts[0]["password"]
    have = {a["email"] for a in accounts}

    existing_emails = {
        row for row in psql(
            "SELECT email FROM adi_user WHERE email LIKE 'ceshi%@zhimesh.com';"
        ).splitlines() if row
    }
    # 现有可用账号（有用户行 + 有绑 kb_ids=3 的角色）—— 单列结果，直接取整行
    usable = {
        row
        for row in psql(
            "SELECT u.email FROM adi_user u JOIN adi_character c ON c.user_id=u.id "
            "WHERE c.kb_ids='3' AND c.title LIKE '%并发测试角色%';"
        ).splitlines() if row
    }
    print(f"target={TARGET} json_accounts={len(accounts)} usable_in_db={len(usable)}")

    n = 1
    created_users = created_chars = 0
    while len(usable) < TARGET:
        email = f"ceshi{n:02d}@zhimesh.com"
        n += 1
        if n > 999:
            raise SystemExit("ran out of email slots")
        if email in usable or (email in existing_emails and email not in have):
            # 用户行存在但没角色的，补角色即可
            if email in existing_emails and email not in usable:
                uid = psql(f"SELECT id FROM adi_user WHERE email='{email}';")
                if not uid:
                    continue
                psql(_copy_char_sql(uid))
                usable.add(email)
                created_chars += 1
            continue
        if email in existing_emails:
            uid = psql(f"SELECT id FROM adi_user WHERE email='{email}';")
            psql(_copy_char_sql(uid))
            usable.add(email)
            created_chars += 1
            continue
        uuid = psql(f"SELECT md5(random()::text || '{email}');")
        psql(
            "INSERT INTO adi_user (name, password, uuid, email, user_status, is_admin,"
            " quota_by_token_daily, quota_by_token_monthly, quota_by_request_daily,"
            " quota_by_request_monthly, quota_by_image_daily, quota_by_image_monthly, locale)"
            f" SELECT name, password, '{uuid}', '{email}', user_status, false,"
            " quota_by_token_daily, quota_by_token_monthly, quota_by_request_daily,"
            " quota_by_request_monthly, quota_by_image_daily, quota_by_image_monthly, locale"
            f" FROM adi_user WHERE email='{template_email}';"
        )
        uid = psql(f"SELECT id FROM adi_user WHERE email='{email}';")
        psql(_copy_char_sql(uid))
        usable.add(email)
        created_users += 1
        created_chars += 1
    print(f"created_users={created_users} created_chars={created_chars}")

    # 重写 accounts JSON：一个可用账号一行（email, 模板密码, 其最新角色 uuid）
    rows = psql(
        "SELECT u.email, c.uuid FROM adi_user u JOIN adi_character c ON c.user_id=u.id"
        " WHERE c.kb_ids='3' AND c.title LIKE '%并发测试角色%' ORDER BY u.id;"
    ).splitlines()
    email_to_char = dict(r.split("|") for r in rows if "|" in r)
    new_accounts = []
    for email in sorted(email_to_char, key=lambda e: int(re.match(r"ceshi(\d+)@", e).group(1)) if re.match(r"ceshi(\d+)@", e) else 999):
        new_accounts.append({
            "email": email,
            "password": template_password,
            "character_uuid": email_to_char[email],
        })
        if len(new_accounts) >= TARGET:
            break
    ACCOUNTS.write_text(json.dumps({"accounts": new_accounts}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"accounts json rewritten: {len(new_accounts)} entries -> {ACCOUNTS}")


def _copy_char_sql(uid: str) -> str:
    return (
        "INSERT INTO adi_character (user_id, uuid, title, remark, ai_system_message,"
        " understand_context_enable, llm_temperature, mcp_ids, kb_ids, answer_content_type,"
        " is_autoplay_answer, is_enable_thinking, is_enable_web_search, audio_config,"
        " api_key, is_agentic, tool_policy)"
        " SELECT " + uid + ", replace(gen_random_uuid()::text, '-', ''), title, remark, ai_system_message,"
        " understand_context_enable, llm_temperature, mcp_ids, kb_ids, answer_content_type,"
        " is_autoplay_answer, is_enable_thinking, is_enable_web_search, audio_config,"
        " api_key, is_agentic, tool_policy FROM adi_character c"
        " WHERE c.title LIKE '%并发测试角色%' ORDER BY c.id LIMIT 1;"
    )


if __name__ == "__main__":
    main()
