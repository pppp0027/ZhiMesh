from __future__ import annotations

import argparse
import collections

from common import load_config, read_jsonl


REQUIRED = {
    "id", "question", "reference", "source_documents", "source_evidence",
    "question_type", "difficulty", "is_answerable",
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate the source evaluation JSONL")
    parser.add_argument("--config", default="config.json")
    args = parser.parse_args()
    rows = read_jsonl(load_config(args.config)["dataset"])
    errors: list[str] = []
    seen: set[str] = set()
    for number, row in enumerate(rows, 1):
        missing = REQUIRED.difference(row)
        if missing:
            errors.append(f"line {number}: missing {sorted(missing)}")
        row_id = str(row.get("id", ""))
        if not row_id:
            errors.append(f"line {number}: blank id")
        elif row_id in seen:
            errors.append(f"line {number}: duplicate id {row_id}")
        seen.add(row_id)
        if not isinstance(row.get("source_documents"), list):
            errors.append(f"line {number}: source_documents must be an array")
        if not isinstance(row.get("source_evidence"), list):
            errors.append(f"line {number}: source_evidence must be an array")
    if errors:
        print("\n".join(errors))
        raise SystemExit(f"Dataset validation failed with {len(errors)} error(s)")
    print(f"valid rows: {len(rows)}")
    print("question_type:", dict(collections.Counter(row["question_type"] for row in rows)))
    print("difficulty:", dict(collections.Counter(row["difficulty"] for row in rows)))
    print("answerable:", dict(collections.Counter(row["is_answerable"] for row in rows)))


if __name__ == "__main__":
    main()
