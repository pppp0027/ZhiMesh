import unittest

from pathlib import Path
from tempfile import TemporaryDirectory

from collect_predictions import experiment_options, load_ids_file, validate_applied_experiment


class ExperimentOptionsTest(unittest.TestCase):
    def test_accepts_supported_configuration(self):
        self.assertEqual(("graph", False), experiment_options({
            "retrieval_mode": "GRAPH", "use_reranker": False
        }))

    def test_rejects_missing_or_invalid_mode(self):
        with self.assertRaisesRegex(ValueError, "retrieval_mode"):
            experiment_options({"use_reranker": False})

    def test_rejects_non_boolean_reranker_flag(self):
        with self.assertRaisesRegex(ValueError, "use_reranker"):
            experiment_options({"retrieval_mode": "hybrid", "use_reranker": "false"})

    def test_accepts_routes_and_reranker_matching_experiment(self):
        validate_applied_experiment({
            "configSnapshot": {"retrievalMode": "hybrid", "useReranker": True},
            "routes": [{"route": "vector"}, {"route": "graph"}],
            "rerank": {"configured": True, "successful": True},
        }, "hybrid", True)

    def test_rejects_route_mismatch(self):
        with self.assertRaisesRegex(RuntimeError, "routes mismatch"):
            validate_applied_experiment({
                "configSnapshot": {"retrievalMode": "vector", "useReranker": False},
                "routes": [{"route": "vector"}, {"route": "graph"}],
                "rerank": {"configured": False, "successful": False},
            }, "vector", False)

    def test_accepts_retrieval_only_graph_trace(self):
        validate_applied_experiment({
            "configSnapshot": {
                "retrievalMode": "graph", "useReranker": False, "retrievalOnly": True
            },
            "routes": [{"route": "graph"}],
            "rerank": {"configured": False, "successful": False},
            "graphTrace": {"status": "success"},
        }, "graph", False, True)

    def test_accepts_explicit_query_embedding_for_router_training(self):
        validate_applied_experiment({
            "configSnapshot": {
                "retrievalMode": "vector", "useReranker": False,
                "retrievalOnly": True, "queryEmbeddingIncluded": True,
            },
            "routes": [{"route": "vector"}],
            "rerank": {"configured": False, "successful": False},
            "queryEmbedding": [0.1, 0.2],
        }, "vector", False, True, True)

    def test_loads_ids_file_and_ignores_comments(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "ids.txt"
            path.write_text("# group\neval_1\n\neval_2\n", encoding="utf-8")
            self.assertEqual({"eval_1", "eval_2"}, load_ids_file(path))


if __name__ == "__main__":
    unittest.main()
