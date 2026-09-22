import unittest

from pathlib import Path
from tempfile import TemporaryDirectory

from collect_predictions import experiment_options, load_ids_file, validate_applied_experiment


def intent_evaluation(intent_overrides=None, **overrides):
    """Build a fake backend evaluation echo that passes the intent-mode checks."""
    evaluation = {
        "configSnapshot": {
            "intentRouting": True,
            "retrievalRoutes": ["vector", "graph"],
            "useReranker": False,
        },
        "intent": {
            "routingEnabled": True,
            "scopePreflight": {
                "status": "related", "score": 0.83, "skip": False,
                "durationMs": 12, "reason": "kb scope matched",
            },
            "availableRoutes": ["vector", "graph"],
            "intent": "relationship",
            "recognizer": "route-prototype",
            "confidence": 0.81,
            "margin": 0.09,
            "proposedRoutes": ["vector", "graph"],
            "effectiveRoutes": ["vector", "graph"],
            "fallback": False,
            "reason": "recognized intent relationship with confidence 0.81",
            "recognitionReason": "top score 0.81",
        },
        "routes": [
            {"route": "vector", "status": "success"},
            {"route": "graph", "status": "success"},
        ],
        "rerank": {"configured": False, "successful": False},
    }
    if intent_overrides:
        evaluation["intent"].update(intent_overrides)
    evaluation.update(overrides)
    return evaluation


class ExperimentOptionsTest(unittest.TestCase):
    def test_accepts_supported_configuration(self):
        self.assertEqual(("graph", [], False, False), experiment_options({
            "retrieval_mode": "GRAPH", "use_reranker": False
        }))

    def test_accepts_explicit_routes_configuration(self):
        self.assertEqual((None, ["vector", "graph"], False, False), experiment_options({
            "retrieval_routes": ["Vector", "GRAPH"], "use_reranker": False
        }))

    def test_rejects_missing_or_invalid_mode(self):
        with self.assertRaisesRegex(ValueError, "retrieval_mode"):
            experiment_options({"use_reranker": False})

    def test_rejects_non_boolean_reranker_flag(self):
        with self.assertRaisesRegex(ValueError, "use_reranker"):
            experiment_options({"retrieval_mode": "hybrid", "use_reranker": "false"})

    def test_accepts_intent_routing_configuration(self):
        self.assertEqual((None, [], False, True), experiment_options({
            "retrieval_mode": None,
            "retrieval_routes": None,
            "intent_routing": True,
            "use_reranker": False,
        }))

    def test_rejects_intent_routing_with_retrieval_mode(self):
        with self.assertRaisesRegex(ValueError, "mutually exclusive"):
            experiment_options({
                "retrieval_mode": "vector",
                "retrieval_routes": None,
                "intent_routing": True,
                "use_reranker": False,
            })

    def test_rejects_intent_routing_with_retrieval_routes(self):
        with self.assertRaisesRegex(ValueError, "mutually exclusive"):
            experiment_options({
                "retrieval_mode": None,
                "retrieval_routes": ["vector"],
                "intent_routing": True,
                "use_reranker": False,
            })

    def test_rejects_non_boolean_intent_routing_flag(self):
        with self.assertRaisesRegex(ValueError, "intent_routing"):
            experiment_options({
                "retrieval_mode": None,
                "retrieval_routes": None,
                "intent_routing": "true",
                "use_reranker": False,
            })

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

    def test_accepts_intent_routing_decision_echo(self):
        validate_applied_experiment(intent_evaluation(), None, False, intent_routing=True)

    def test_accepts_intent_no_rag_with_empty_effective_routes(self):
        evaluation = intent_evaluation(
            intent_overrides={
                "intent": "no_rag",
                "proposedRoutes": [],
                "effectiveRoutes": [],
                "reason": "no knowledge required for this question",
            },
            configSnapshot={
                "intentRouting": True,
                "retrievalRoutes": [],
                "useReranker": False,
            },
            routes=[],
        )
        validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_rejects_intent_experiment_without_snapshot_intent_routing(self):
        evaluation = intent_evaluation(configSnapshot={"useReranker": False})
        with self.assertRaisesRegex(RuntimeError, "intentRouting"):
            validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_rejects_disabled_server_intent_routing(self):
        evaluation = intent_evaluation(
            intent_overrides={"reason": "intent routing is disabled"}
        )
        with self.assertRaisesRegex(RuntimeError, "ZHIMESH_INTENT_ROUTING_ENABLED"):
            validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_rejects_intent_echo_missing_required_fields(self):
        evaluation = intent_evaluation(intent={"intent": "relationship"})
        with self.assertRaisesRegex(RuntimeError, "missing required fields"):
            validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_rejects_empty_effective_routes_for_non_no_rag_intent(self):
        evaluation = intent_evaluation(
            intent_overrides={"effectiveRoutes": []},
            routes=[],
        )
        with self.assertRaisesRegex(RuntimeError, "no_rag"):
            validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_rejects_intent_routes_mismatch_with_effective_routes(self):
        evaluation = intent_evaluation(routes=[{"route": "vector", "status": "success"}])
        with self.assertRaisesRegex(RuntimeError, "effectiveRoutes"):
            validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_rejects_intent_route_timeout(self):
        evaluation = intent_evaluation(
            intent_overrides={
                "intent": "keyword",
                "proposedRoutes": ["vector", "bm25"],
                "effectiveRoutes": ["vector", "bm25"],
            },
            routes=[
                {"route": "vector", "status": "success"},
                {"route": "bm25", "status": "timeout"},
            ],
        )
        with self.assertRaisesRegex(RuntimeError, "did not finish cleanly"):
            validate_applied_experiment(evaluation, None, False, intent_routing=True)

    def test_loads_ids_file_and_ignores_comments(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "ids.txt"
            path.write_text("# group\neval_1\n\neval_2\n", encoding="utf-8")
            self.assertEqual({"eval_1", "eval_2"}, load_ids_file(path))


if __name__ == "__main__":
    unittest.main()
