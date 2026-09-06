from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_vector_graph_labels import build_rows
from build_three_route_labels import build_rows as build_three_route_rows
from split_routing_dataset import assign_splits
from train_routing_classifier import train_artifact
from validate_routing_dataset import validate


def run_row(row_id: str, question: str, expected_document: str,
            candidate_document: str | None, embedding: list[float]) -> dict:
    candidates = [] if candidate_document is None else [{
        "content": f"evidence for {candidate_document}",
        "sourceDocumentNames": [candidate_document],
        "selected": True,
    }]
    return {
        "id": row_id,
        "question": question,
        "source_documents": [expected_document],
        "source_evidence": [],
        "is_answerable": True,
        "queryEmbedding": embedding,
        "configSnapshot": {"embeddingModel": "local:test"},
        "candidates": candidates,
        "routes": [{"route": "vector", "durationMs": 10}],
        "timingMs": {"retrieval": 12},
        "collection_status": "success",
    }


class RoutingPipelineTest(unittest.TestCase):
    def test_builds_vector_safe_and_graph_required_suggestions(self):
        vector = [
            run_row("v", "vector question", "doc-v", "doc-v", [1.0, 0.0]),
            run_row("g", "graph question", "doc-g", None, [0.0, 1.0]),
        ]
        graph = [
            run_row("v", "vector question", "doc-v", None, [1.0, 0.0]),
            run_row("g", "graph question", "doc-g", "doc-g", [0.0, 1.0]),
        ]
        hybrid = [
            run_row("v", "vector question", "doc-v", "doc-v", [1.0, 0.0]),
            run_row("g", "graph question", "doc-g", "doc-g", [0.0, 1.0]),
        ]

        rows = build_rows(vector, graph, hybrid)

        self.assertEqual(True, rows[1]["labels"]["vectorSufficient"])
        self.assertEqual(False, rows[1]["labels"]["graphRequired"])
        self.assertEqual(False, rows[0]["labels"]["vectorSufficient"])
        self.assertEqual(True, rows[0]["labels"]["graphRequired"])
        self.assertIsNone(rows[0]["labels"]["bm25Required"])

    def test_builds_three_route_suggestions_from_minimum_acceptable_plan(self):
        vector = [run_row("b", "exact question", "doc-b", None, [0.0, 0.0, 1.0])]
        vector_graph = [run_row("b", "exact question", "doc-b", None, [0.0, 0.0, 1.0])]
        vector_bm25 = [run_row("b", "exact question", "doc-b", "doc-b", [0.0, 0.0, 1.0])]
        all_routes = [run_row("b", "exact question", "doc-b", "doc-b", [0.0, 0.0, 1.0])]

        rows = build_three_route_rows(vector, vector_graph, vector_bm25, all_routes)

        self.assertEqual("vector_bm25", rows[0]["selectedPlan"])
        self.assertEqual(False, rows[0]["labels"]["vectorSufficient"])
        self.assertEqual(False, rows[0]["labels"]["graphRequired"])
        self.assertEqual(True, rows[0]["labels"]["bm25Required"])

    def test_group_split_never_leaks_one_group_across_splits(self):
        rows = [
            {"id": "1", "groupId": "a"}, {"id": "2", "groupId": "a"},
            {"id": "3", "groupId": "b"}, {"id": "4", "groupId": "c"},
            {"id": "5", "groupId": "d"}, {"id": "6", "groupId": "e"},
        ]
        assigned = assign_splits(rows, 42, 0.6, 0.2)
        group_splits: dict[str, set[str]] = {}
        for row in assigned:
            group_splits.setdefault(row["groupId"], set()).add(row["split"])
        self.assertTrue(all(len(splits) == 1 for splits in group_splits.values()))

    def test_trains_three_route_heads(self):
        rows = []
        for split in ("train", "validation", "test"):
            for index in range(4):
                vector_safe = index in {0, 2}
                graph_required = index == 1
                bm25_required = index == 3
                rows.append({
                    "id": f"{split}-{index}",
                    "question": f"question {split} {index}",
                    "groupId": f"group-{split}-{index}",
                    "embeddingModel": "local:test",
                    "queryEmbedding": (
                        [1.0, 0.0, 0.0] if vector_safe
                        else [0.0, 1.0, 0.0] if graph_required
                        else [0.0, 0.0, 1.0]
                    ),
                    "labels": {
                        "vectorSufficient": vector_safe,
                        "graphRequired": graph_required,
                        "bm25Required": bm25_required,
                    },
                    "labelStatus": "REVIEWED",
                    "split": split,
                })
        self.assertEqual([], validate(rows, for_training=True))

        artifact, report = train_artifact(
            rows, "dataset-sha", "router-test", epochs=500,
            min_vector_precision=0.9, min_graph_recall=0.9,
        )

        self.assertTrue(artifact["outputs"]["vectorSufficient"]["enabled"])
        self.assertTrue(artifact["outputs"]["graphRequired"]["enabled"])
        self.assertTrue(artifact["outputs"]["bm25Required"]["enabled"])
        self.assertEqual(3, artifact["embeddingDimension"])
        self.assertIn("test", report["heads"]["vectorSufficient"])


if __name__ == "__main__":
    unittest.main()
