from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Any, Iterable


CAPABILITIES = (
    "vectorSufficient",
    "graphRequired",
    "bm25Required",
)


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with Path(path).open(encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSONL at {path}:{line_number}: {exc}") from exc
    return rows


def write_jsonl(path: str | Path, rows: Iterable[dict[str, Any]]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def normalized_text(value: Any) -> str:
    return re.sub(r"[\W_]+", "", str(value or "").lower(), flags=re.UNICODE)


def finite_vector(value: Any) -> bool:
    return isinstance(value, list) and bool(value) and all(
        isinstance(item, (int, float)) and math.isfinite(float(item)) for item in value
    )


def successful_by_id(rows: Iterable[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        row_id = str(row.get("id", ""))
        if not row_id or row.get("collection_status") not in {None, "success"}:
            continue
        if row_id in result:
            raise ValueError(f"Duplicate successful row id: {row_id}")
        result[row_id] = row
    return result
