from __future__ import annotations

import argparse
from collections import Counter

from routing_common import CAPABILITIES, finite_vector, read_jsonl


def validate(rows: list[dict], for_training: bool = False,
             require_reviewed: bool = True) -> list[str]:
    errors: list[str] = []
    seen_ids: set[str] = set()
    dimensions: set[int] = set()
    embedding_models: set[str] = set()
    for number, row in enumerate(rows, 1):
        prefix = f"line {number}"
        row_id = str(row.get("id", ""))
        if not row_id or row_id in seen_ids:
            errors.append(f"{prefix}: id is blank or duplicated: {row_id}")
        seen_ids.add(row_id)
        if not str(row.get("question", "")).strip():
            errors.append(f"{prefix}: question is blank")
        labels = row.get("labels")
        if not isinstance(labels, dict):
            errors.append(f"{prefix}: labels must be an object")
            continue
        for capability in CAPABILITIES:
            if capability not in labels or labels[capability] not in {True, False, None}:
                errors.append(f"{prefix}: invalid tri-state label {capability}")
        if labels.get("vectorSufficient") is True and any(
            labels.get(capability) is True for capability in CAPABILITIES[1:]
        ):
            errors.append(f"{prefix}: vectorSufficient conflicts with a required auxiliary route")
        if for_training and require_reviewed and row.get("labelStatus") != "REVIEWED":
            errors.append(f"{prefix}: training input must be REVIEWED")
        if for_training and row.get("split") not in {"train", "validation", "test"}:
            errors.append(f"{prefix}: split must be train, validation or test")
        vector = row.get("queryEmbedding")
        if for_training and not finite_vector(vector):
            errors.append(f"{prefix}: queryEmbedding is missing or invalid")
        elif finite_vector(vector):
            dimensions.add(len(vector))
        model = str(row.get("embeddingModel") or "")
        if for_training and not model:
            errors.append(f"{prefix}: embeddingModel is missing")
        elif model:
            embedding_models.add(model)
    if len(dimensions) > 1:
        errors.append(f"embedding dimensions differ: {sorted(dimensions)}")
    if len(embedding_models) > 1:
        errors.append(f"embedding models differ: {sorted(embedding_models)}")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate reviewed routing dataset JSONL")
    parser.add_argument("--input", required=True)
    parser.add_argument("--for-training", action="store_true")
    args = parser.parse_args()
    rows = read_jsonl(args.input)
    errors = validate(rows, args.for_training)
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"valid rows: {len(rows)}")
    print("status:", dict(Counter(row.get("labelStatus") for row in rows)))
    for capability in CAPABILITIES:
        print(capability, dict(Counter(row["labels"].get(capability) for row in rows)))


if __name__ == "__main__":
    main()
