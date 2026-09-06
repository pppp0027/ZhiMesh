from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

from routing_common import normalized_text, read_jsonl, successful_by_id, write_jsonl


def selected_candidates(row: dict[str, Any]) -> list[dict[str, Any]]:
    return [candidate for candidate in row.get("candidates", []) if candidate.get("selected")]


def assess_retrieval(row: dict[str, Any], source_row: dict[str, Any]) -> dict[str, Any]:
    expected_documents = {str(value) for value in source_row.get("source_documents", []) if value}
    expected_evidence = [
        normalized_text(item.get("evidence"))
        for item in source_row.get("source_evidence", [])
        if normalized_text(item.get("evidence"))
    ]
    candidates = selected_candidates(row)
    selected_documents = {
        str(name)
        for candidate in candidates
        for name in candidate.get("sourceDocumentNames", [])
        if name
    }
    candidate_texts = [normalized_text(candidate.get("content")) for candidate in candidates]
    matched_documents = expected_documents.intersection(selected_documents)
    matched_evidence = [
        evidence for evidence in expected_evidence
        if any(evidence in content or content in evidence for content in candidate_texts if content)
    ]
    if expected_evidence:
        recall = len(matched_evidence) / len(expected_evidence)
    elif expected_documents:
        recall = len(matched_documents) / len(expected_documents)
    else:
        recall = 0.0
    routes = row.get("routes") or []
    duration_ms = sum(int(route.get("durationMs") or 0) for route in routes)
    return {
        "goldRecall": round(recall, 6),
        "matchedDocuments": sorted(matched_documents),
        "matchedEvidenceCount": len(matched_evidence),
        "expectedEvidenceCount": len(expected_evidence),
        "selectedCandidateCount": len(candidates),
        "routeDurationMs": duration_ms,
        "retrievalDurationMs": int((row.get("timingMs") or {}).get("retrieval") or 0),
    }


def suggest_labels(source: dict[str, Any], vector: dict[str, Any], graph: dict[str, Any],
                   hybrid: dict[str, Any]) -> tuple[dict[str, bool | None], str, list[str]]:
    labels: dict[str, bool | None] = {
        "vectorSufficient": None,
        "graphRequired": None,
        "bm25Required": None,
    }
    reasons: list[str] = []
    if not bool(source.get("is_answerable", True)):
        return labels, "EXCLUDED_UNANSWERABLE", ["source dataset marks the question unanswerable"]
    if not source.get("source_documents") and not source.get("source_evidence"):
        return labels, "UNRESOLVED", ["no gold document or evidence is available"]

    vector_recall = float(vector["goldRecall"])
    graph_recall = float(graph["goldRecall"])
    hybrid_recall = float(hybrid["goldRecall"])
    if vector_recall >= 1.0:
        labels["vectorSufficient"] = True
        labels["graphRequired"] = False
        reasons.append("vector selected all gold evidence")
    elif graph_recall > vector_recall and max(graph_recall, hybrid_recall) > 0.0:
        labels["vectorSufficient"] = False
        labels["graphRequired"] = True
        reasons.append("graph recovered gold evidence missed by vector")
    elif hybrid_recall > vector_recall and graph_recall > 0.0:
        labels["vectorSufficient"] = False
        labels["graphRequired"] = True
        reasons.append("hybrid improved gold recall with graph evidence")
    elif max(vector_recall, graph_recall, hybrid_recall) == 0.0:
        return labels, "UNRESOLVED", ["no evaluated route selected gold evidence"]
    else:
        labels["vectorSufficient"] = False
        labels["graphRequired"] = False
        reasons.append("vector is incomplete but graph necessity is not proven")
    return labels, "AUTO_SUGGESTED", reasons


def build_rows(vector_rows: list[dict[str, Any]], graph_rows: list[dict[str, Any]],
               hybrid_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    vector_by_id = successful_by_id(vector_rows)
    graph_by_id = successful_by_id(graph_rows)
    hybrid_by_id = successful_by_id(hybrid_rows)
    common_ids = sorted(set(vector_by_id) & set(graph_by_id) & set(hybrid_by_id))
    if not common_ids:
        raise ValueError("Vector, graph and hybrid runs have no common successful ids")
    output: list[dict[str, Any]] = []
    for row_id in common_ids:
        source = vector_by_id[row_id]
        graph_source = graph_by_id[row_id]
        hybrid_source = hybrid_by_id[row_id]
        questions = {str(item.get("question", "")) for item in (source, graph_source, hybrid_source)}
        if len(questions) != 1:
            raise ValueError(f"Question text differs across runs for id {row_id}")
        observations = {
            "vector": assess_retrieval(source, source),
            "graph": assess_retrieval(graph_source, source),
            "hybrid": assess_retrieval(hybrid_source, source),
        }
        labels, status, reasons = suggest_labels(
            source, observations["vector"], observations["graph"], observations["hybrid"]
        )
        source_documents = sorted({str(value) for value in source.get("source_documents", []) if value})
        embedding = source.get("queryEmbedding") or hybrid_source.get("queryEmbedding")
        embedding_model = (source.get("configSnapshot") or {}).get("embeddingModel")
        output.append({
            "id": row_id,
            "question": source.get("question"),
            "questionType": source.get("question_type"),
            "difficulty": source.get("difficulty"),
            "isAnswerable": bool(source.get("is_answerable", True)),
            "groupId": "documents:" + "|".join(source_documents) if source_documents else "unanswerable",
            "embeddingModel": embedding_model,
            "queryEmbedding": embedding,
            "goldEvidence": {
                "sourceDocuments": source_documents,
                "sourceEvidence": source.get("source_evidence", []),
            },
            "observations": observations,
            "labels": labels,
            "labelStatus": status,
            "labelReasons": reasons,
            "labelSource": "retrieval_ablation_v1",
            "split": None,
        })
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="Build reviewable Vector/Graph routing labels")
    parser.add_argument("--vector", required=True)
    parser.add_argument("--graph", required=True)
    parser.add_argument("--hybrid", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    rows = build_rows(read_jsonl(args.vector), read_jsonl(args.graph), read_jsonl(args.hybrid))
    write_jsonl(args.output, rows)
    counts: dict[str, int] = {}
    for row in rows:
        counts[row["labelStatus"]] = counts.get(row["labelStatus"], 0) + 1
    print(f"wrote {len(rows)} rows to {Path(args.output)}; status={counts}")


if __name__ == "__main__":
    main()
