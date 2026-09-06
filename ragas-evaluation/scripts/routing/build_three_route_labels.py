from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

from build_vector_graph_labels import assess_retrieval
from routing_common import read_jsonl, successful_by_id, write_jsonl


PLAN_ROUTES = {
    "vector": ("vector",),
    "vector_graph": ("vector", "graph"),
    "vector_bm25": ("vector", "bm25"),
    "vector_graph_bm25": ("vector", "graph", "bm25"),
}


def choose_plan(observations: dict[str, dict[str, Any]], min_gold_recall: float) -> str | None:
    acceptable = [
        name for name, observation in observations.items()
        if float(observation["goldRecall"]) >= min_gold_recall
    ]
    if not acceptable:
        return None
    return min(
        acceptable,
        key=lambda name: (
            len(PLAN_ROUTES[name]),
            int(observations[name]["retrievalDurationMs"]),
            int(observations[name]["routeDurationMs"]),
            -float(observations[name]["goldRecall"]),
            name,
        ),
    )


def labels_for_plan(plan: str | None) -> dict[str, bool | None]:
    if plan is None:
        return {
            "vectorSufficient": None,
            "graphRequired": None,
            "bm25Required": None,
        }
    routes = set(PLAN_ROUTES[plan])
    return {
        "vectorSufficient": routes == {"vector"},
        "graphRequired": "graph" in routes,
        "bm25Required": "bm25" in routes,
    }


def build_rows(vector_rows: list[dict[str, Any]], vector_graph_rows: list[dict[str, Any]],
               vector_bm25_rows: list[dict[str, Any]],
               vector_graph_bm25_rows: list[dict[str, Any]],
               min_gold_recall: float = 1.0) -> list[dict[str, Any]]:
    runs = {
        "vector": successful_by_id(vector_rows),
        "vector_graph": successful_by_id(vector_graph_rows),
        "vector_bm25": successful_by_id(vector_bm25_rows),
        "vector_graph_bm25": successful_by_id(vector_graph_bm25_rows),
    }
    common_ids = sorted(set.intersection(*(set(rows) for rows in runs.values())))
    if not common_ids:
        raise ValueError("three-route ablation runs have no common successful ids")

    output: list[dict[str, Any]] = []
    for row_id in common_ids:
        source = runs["vector"][row_id]
        questions = {str(rows[row_id].get("question", "")) for rows in runs.values()}
        if len(questions) != 1:
            raise ValueError(f"question text differs across runs for id {row_id}")
        observations = {
            name: assess_retrieval(rows[row_id], source) for name, rows in runs.items()
        }
        selected_plan = choose_plan(observations, min_gold_recall)
        labels = labels_for_plan(selected_plan)
        source_documents = sorted({
            str(value) for value in source.get("source_documents", []) if value
        })
        if not bool(source.get("is_answerable", True)):
            status = "EXCLUDED_UNANSWERABLE"
            reasons = ["source dataset marks the question unanswerable"]
            labels = labels_for_plan(None)
        elif not source_documents and not source.get("source_evidence"):
            status = "UNRESOLVED"
            reasons = ["no gold document or evidence is available"]
            labels = labels_for_plan(None)
        elif selected_plan is None:
            status = "UNRESOLVED"
            reasons = [f"no Vector/Graph/BM25 plan reached gold recall {min_gold_recall}"]
        else:
            status = "AUTO_SUGGESTED"
            reasons = [
                f"selected minimum-cost acceptable plan {selected_plan}",
                f"gold recall={observations[selected_plan]['goldRecall']}",
            ]
        embedding = source.get("queryEmbedding")
        embedding_model = (source.get("configSnapshot") or {}).get("embeddingModel")
        output.append({
            "id": row_id,
            "question": source.get("question"),
            "questionType": source.get("question_type"),
            "difficulty": source.get("difficulty"),
            "isAnswerable": bool(source.get("is_answerable", True)),
            "groupId": "documents:" + "|".join(source_documents)
            if source_documents else "unanswerable",
            "embeddingModel": embedding_model,
            "queryEmbedding": embedding,
            "goldEvidence": {
                "sourceDocuments": source_documents,
                "sourceEvidence": source.get("source_evidence", []),
            },
            "observations": observations,
            "selectedPlan": selected_plan,
            "labels": labels,
            "labelStatus": status,
            "labelReasons": reasons,
            "labelSource": "three_route_retrieval_ablation_v1",
            "split": None,
        })
    return output


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build reviewable Vector/Graph/BM25 routing labels"
    )
    parser.add_argument("--vector", required=True)
    parser.add_argument("--vector-graph", required=True)
    parser.add_argument("--vector-bm25", required=True)
    parser.add_argument("--vector-graph-bm25", required=True)
    parser.add_argument("--min-gold-recall", type=float, default=1.0)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not 0.0 < args.min_gold_recall <= 1.0:
        raise SystemExit("--min-gold-recall must be in (0, 1]")
    rows = build_rows(
        read_jsonl(args.vector), read_jsonl(args.vector_graph),
        read_jsonl(args.vector_bm25), read_jsonl(args.vector_graph_bm25),
        args.min_gold_recall,
    )
    write_jsonl(args.output, rows)
    counts: dict[str, int] = {}
    for row in rows:
        counts[row["labelStatus"]] = counts.get(row["labelStatus"], 0) + 1
    print(f"wrote {len(rows)} rows to {Path(args.output)}; status={counts}")


if __name__ == "__main__":
    main()
