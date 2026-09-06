from __future__ import annotations

import argparse
import random

from routing_common import read_jsonl, write_jsonl


def assign_splits(rows: list[dict], seed: int, train_ratio: float,
                  validation_ratio: float) -> list[dict]:
    if (not 0 < train_ratio < 1 or not 0 < validation_ratio < 1
            or train_ratio + validation_ratio >= 1):
        raise ValueError("ratios must be positive and leave room for a test split")
    groups = sorted({str(row.get("groupId") or "") for row in rows})
    if "" in groups:
        raise ValueError("every row needs groupId")
    random.Random(seed).shuffle(groups)
    train_end = max(1, round(len(groups) * train_ratio))
    validation_end = min(len(groups) - 1,
                         train_end + max(1, round(len(groups) * validation_ratio)))
    assignments = {
        group: "train" if index < train_end
        else "validation" if index < validation_end else "test"
        for index, group in enumerate(groups)
    }
    return [{**row, "split": assignments[str(row["groupId"])]} for row in rows]


def main() -> None:
    parser = argparse.ArgumentParser(description="Group-split a reviewed routing dataset")
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--train-ratio", type=float, default=0.7)
    parser.add_argument("--validation-ratio", type=float, default=0.15)
    args = parser.parse_args()
    rows = assign_splits(read_jsonl(args.input), args.seed,
                         args.train_ratio, args.validation_ratio)
    write_jsonl(args.output, rows)
    print(f"wrote {len(rows)} rows to {args.output}")


if __name__ == "__main__":
    main()
