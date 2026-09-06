from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from routing_common import read_jsonl, write_jsonl


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build a conservative clean source without inventing routing labels"
    )
    parser.add_argument("--prepared-dir", required=True)
    parser.add_argument("--hotpot-final-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    prepared = Path(args.prepared_dir).resolve()
    hotpot_final = Path(args.hotpot_final_dir).resolve()
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)

    miracl_candidates = read_jsonl(prepared / "miracl-zh" / "source-candidates.jsonl")
    miracl_rows = [
        {
            **row,
            "contentReviewStatus": "SOURCE_VALIDATED",
            "trainingEligible": False,
            "labels": {
                "vectorSufficient": None,
                "graphRequired": None,
                "bm25Required": None,
            },
            "labelStatus": "PENDING_RETRIEVAL_ABLATION",
        }
        for row in miracl_candidates
        if row.get("contentReviewStatus") == "STRUCTURAL_PASS"
    ]
    miracl_excluded = [
        {
            **row,
            "exclusionReason": "DUPLICATE_OR_STRUCTURAL_REVIEW_PENDING",
        }
        for row in miracl_candidates
        if row.get("contentReviewStatus") != "STRUCTURAL_PASS"
    ]

    hotpot_path = hotpot_final / "retrieval-labeling-source.jsonl"
    hotpot_rows = []
    if hotpot_path.exists():
        hotpot_rows = [
            {
                **row,
                "labels": {
                    "vectorSufficient": None,
                    "graphRequired": None,
                    "bm25Required": None,
                },
                "labelStatus": "PENDING_RETRIEVAL_ABLATION",
                "trainingEligible": False,
            }
            for row in read_jsonl(hotpot_path)
            if row.get("contentReviewStatus") == "HUMAN_REVIEWED"
        ]
    hotpot_candidates = read_jsonl(prepared / "hotpotqa-bridge" / "source-candidates.jsonl")
    hotpot_excluded = [
        {
            **row,
            "exclusionReason": "MACHINE_TRANSLATION_NOT_HUMAN_REVIEWED",
        }
        for row in hotpot_candidates
        if row.get("id") not in {str(item.get("id")) for item in hotpot_rows}
    ]

    combined = miracl_rows + hotpot_rows
    write_jsonl(output / "retrieval-labeling-source.jsonl", combined)
    write_jsonl(output / "excluded-miracl.jsonl", miracl_excluded)
    write_jsonl(output / "excluded-hotpotqa-bridge.jsonl", hotpot_excluded)
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "policy": "conservative-no-invented-routing-labels",
        "miracl": {
            "acceptedRows": len(miracl_rows),
            "excludedRows": len(miracl_excluded),
        },
        "hotpotQaBridge": {
            "acceptedHumanReviewedRows": len(hotpot_rows),
            "excludedMachineTranslationRows": len(hotpot_excluded),
        },
        "combinedRows": len(combined),
        "trainingReady": False,
        "reason": "routing labels require real Vector/Graph/BM25 ablation and human label review",
        "requiredNextStep": "RUN_RETRIEVAL_ABLATIONS_AND_MARK_LABELS_REVIEWED",
    }
    (output / "quality-source-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
