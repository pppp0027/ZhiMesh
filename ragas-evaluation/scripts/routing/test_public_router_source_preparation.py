from __future__ import annotations

import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from finalize_public_router_sources import finalize_hotpot
from prepare_public_router_sources import prepare_hotpot, prepare_miracl
from routing_common import read_jsonl, write_jsonl


def update_csv(path: Path, updates: dict[str, str]) -> None:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
        fieldnames = list(rows[0])
    for row in rows:
        row.update(updates)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


class PublicRouterSourcePreparationTest(unittest.TestCase):
    def test_miracl_flags_duplicate_normalized_questions(self):
        rows = [
            {
                "id": "m1", "question": "Redis 怎么配置？", "source_documents": ["a"],
                "source_evidence": [{"document": "a", "evidence": "x"}],
                "positive_passage_ids": ["p1"],
            },
            {
                "id": "m2", "question": "Redis怎么配置?", "source_documents": ["b"],
                "source_evidence": [{"document": "b", "evidence": "y"}],
                "positive_passage_ids": ["p2"],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            report = prepare_miracl(rows, Path(directory))
            self.assertEqual(2, report["reviewRequiredRows"])
            candidates = read_jsonl(Path(directory) / "source-candidates.jsonl")
            self.assertIn("DUPLICATE_NORMALIZED_QUESTION", candidates[0]["contentIssueCodes"])
            self.assertFalse(candidates[0]["trainingEligible"])

    def test_hotpot_creates_question_evidence_and_title_review_queues(self):
        source = [{
            "_id": "h1", "question": "Where did Alpha play?", "answer": "Beta",
            "type": "bridge", "level": "hard",
            "supporting_facts": [["Alpha", 0], ["Beta", 0]],
            "context": [["Alpha", ["Alpha played at Beta."]], ["Beta", ["Beta is in City."]]],
        }]
        translated_queries = [{
            "public_query_id": "h1", "question": "阿尔法在哪里比赛？", "reference": "贝塔",
            "translation_flags": [],
        }]
        translated_documents = [
            {
                "documentName": "alpha.txt", "titleEn": "Alpha", "title": "阿尔法",
                "sentencesEn": ["Alpha played at Beta."], "sentences": ["阿尔法在贝塔比赛。"],
            },
            {
                "documentName": "beta.txt", "titleEn": "Beta", "title": "贝塔",
                "sentencesEn": ["Beta is in City."], "sentences": ["贝塔位于城市。"],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = prepare_hotpot(source, translated_queries, translated_documents, output)
            self.assertEqual(1, report["questionAnswerReviewRows"])
            self.assertEqual(2, report["goldEvidenceReviewRows"])
            self.assertEqual(2, report["goldTitleReviewRows"])
            self.assertTrue((output / "question-answer-review.csv").exists())
            self.assertFalse(read_jsonl(output / "source-candidates.jsonl")[0]["trainingEligible"])

    def test_finalizer_requires_reviewed_text_and_replaces_only_gold_evidence(self):
        source = [{
            "_id": "h1", "question": "Where did Alpha play?", "answer": "Beta",
            "type": "bridge", "level": "hard",
            "supporting_facts": [["Alpha", 0], ["Beta", 0]],
            "context": [["Alpha", ["Alpha played at Beta.", "Noise."]],
                        ["Beta", ["Beta is in City."]]],
        }]
        translated_queries = [{
            "public_query_id": "h1", "question": "坏翻译", "reference": "坏答案",
            "translation_flags": [],
        }]
        translated_documents = [
            {
                "documentName": "alpha.txt", "titleEn": "Alpha", "title": "坏标题A",
                "sentencesEn": ["Alpha played at Beta.", "Noise."],
                "sentences": ["坏证据A", "机器干扰句"],
            },
            {
                "documentName": "beta.txt", "titleEn": "Beta", "title": "坏标题B",
                "sentencesEn": ["Beta is in City."], "sentences": ["坏证据B"],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prepared = root / "prepared"
            prepare_hotpot(source, translated_queries, translated_documents, prepared)
            update_csv(prepared / "question-answer-review.csv", {
                "reviewedQuestionZh": "阿尔法在哪里比赛？", "reviewedAnswerZh": "贝塔",
                "decision": "ACCEPT", "reviewer": "tester",
            })
            update_csv(prepared / "gold-evidence-review.csv", {
                "reviewedEvidenceZh": "已复核证据", "decision": "ACCEPT", "reviewer": "tester",
            })
            update_csv(prepared / "gold-title-review.csv", {
                "reviewedTitleZh": "已复核标题", "decision": "ACCEPT", "reviewer": "tester",
            })
            documents_path = root / "documents.jsonl"
            write_jsonl(documents_path, translated_documents)
            report = finalize_hotpot(
                prepared, documents_path, root / "final", allow_partial=False,
                allow_machine_distractors=True,
            )
            self.assertEqual(1, report["acceptedRows"])
            cleaned = read_jsonl(root / "final" / "retrieval-labeling-source.jsonl")[0]
            self.assertEqual("HUMAN_REVIEWED", cleaned["contentReviewStatus"])
            self.assertFalse(cleaned["trainingEligible"])
            documents = read_jsonl(root / "final" / "import-documents.reviewed-gold.jsonl")
            self.assertEqual("已复核证据", documents[0]["sentences"][0])
            self.assertEqual("机器干扰句", documents[0]["sentences"][1])


if __name__ == "__main__":
    unittest.main()
