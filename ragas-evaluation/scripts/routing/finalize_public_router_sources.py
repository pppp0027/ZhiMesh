from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from routing_common import read_jsonl, write_jsonl


ACCEPT = "ACCEPT"
REJECT = "REJECT"
PENDING = "PENDING"


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def decision(value: Any) -> str:
    return str(value or PENDING).strip().upper()


def require_reviewed_text(row: dict[str, str], field: str, identity: str) -> str:
    value = str(row.get(field) or "").strip()
    if not value:
        raise ValueError(f"accepted row {identity} must provide {field}")
    return value


def finalize_miracl(prepared_dir: Path, output_dir: Path, allow_partial: bool) -> dict[str, Any]:
    candidates = read_jsonl(prepared_dir / "source-candidates.jsonl")
    review_path = prepared_dir / "review-queue.csv"
    review = {str(row.get("id")): row for row in read_csv(review_path)} if review_path.exists() else {}
    accepted: list[dict[str, Any]] = []
    excluded: list[dict[str, Any]] = []
    pending = 0
    for row in candidates:
        row_id = str(row.get("id"))
        if row.get("contentReviewStatus") == "STRUCTURAL_PASS":
            accepted.append({
                **row,
                "contentReviewStatus": "SOURCE_ACCEPTED",
                "trainingEligible": False,
                "eligibilityReason": "RETRIEVAL_LABELS_NOT_BUILT",
            })
            continue
        review_row = review.get(row_id, {})
        current = decision(review_row.get("decision"))
        if current == ACCEPT:
            accepted.append({
                **row,
                "contentReviewStatus": "SOURCE_ACCEPTED_AFTER_REVIEW",
                "contentReviewNotes": review_row.get("notes", ""),
                "trainingEligible": False,
                "eligibilityReason": "RETRIEVAL_LABELS_NOT_BUILT",
            })
        elif current == REJECT:
            excluded.append({**row, "exclusionReason": review_row.get("notes") or "REVIEW_REJECTED"})
        else:
            pending += 1
    if pending and not allow_partial:
        raise ValueError(f"MIRACL review still has {pending} pending rows")
    write_jsonl(output_dir / "retrieval-labeling-source.jsonl", accepted)
    write_jsonl(output_dir / "excluded.jsonl", excluded)
    return {"acceptedRows": len(accepted), "excludedRows": len(excluded), "pendingRows": pending}


def finalize_hotpot(prepared_dir: Path, translated_documents_path: Path,
                    output_dir: Path, allow_partial: bool,
                    allow_machine_distractors: bool) -> dict[str, Any]:
    candidates = {str(row.get("id")): row for row in read_jsonl(prepared_dir / "source-candidates.jsonl")}
    questions = read_csv(prepared_dir / "question-answer-review.csv")
    evidence = read_csv(prepared_dir / "gold-evidence-review.csv")
    titles = read_csv(prepared_dir / "gold-title-review.csv")
    documents = read_jsonl(translated_documents_path)

    title_review = {str(row.get("titleEn")): row for row in titles}
    evidence_by_query: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in evidence:
        evidence_by_query[str(row.get("queryId"))].append(row)

    accepted: list[dict[str, Any]] = []
    excluded: list[dict[str, Any]] = []
    pending = 0
    accepted_evidence: dict[tuple[str, str], str] = {}
    accepted_title_zh: dict[str, str] = {}
    accepted_query_ids: set[str] = set()

    for review in questions:
        row_id = str(review.get("id"))
        source = candidates.get(row_id)
        if source is None:
            raise ValueError(f"question review references unknown id {row_id}")
        current = decision(review.get("decision"))
        if current == REJECT:
            excluded.append({
                **source,
                "exclusionReason": review.get("notes") or "QUESTION_REVIEW_REJECTED",
            })
            continue
        if current != ACCEPT:
            pending += 1
            continue

        question_zh = require_reviewed_text(review, "reviewedQuestionZh", row_id)
        answer_zh = require_reviewed_text(review, "reviewedAnswerZh", row_id)
        query_evidence = evidence_by_query.get(row_id, [])
        if not query_evidence:
            raise ValueError(f"accepted HotpotQA row {row_id} has no evidence review rows")
        evidence_output: list[dict[str, str]] = []
        for evidence_row in query_evidence:
            evidence_id = str(evidence_row.get("evidenceId"))
            if decision(evidence_row.get("decision")) != ACCEPT:
                raise ValueError(f"accepted HotpotQA row {row_id} needs accepted evidence {evidence_id}")
            evidence_zh = require_reviewed_text(evidence_row, "reviewedEvidenceZh", evidence_id)
            title_en = str(evidence_row.get("titleEn"))
            title_row = title_review.get(title_en)
            if title_row is None or decision(title_row.get("decision")) != ACCEPT:
                raise ValueError(f"accepted HotpotQA row {row_id} needs accepted title {title_en}")
            title_zh = require_reviewed_text(title_row, "reviewedTitleZh", title_en)
            previous_title = accepted_title_zh.setdefault(title_en, title_zh)
            if previous_title != title_zh:
                raise ValueError(f"conflicting reviewed title translation: {title_en}")
            key = (title_en, str(evidence_row.get("evidenceEn")))
            previous_evidence = accepted_evidence.setdefault(key, evidence_zh)
            if previous_evidence != evidence_zh:
                raise ValueError(f"conflicting reviewed evidence translation: {key}")
            evidence_output.append({
                "document": str(evidence_row.get("documentName")),
                "title": title_zh,
                "titleEn": title_en,
                "evidence": evidence_zh,
                "evidenceEn": str(evidence_row.get("evidenceEn")),
            })

        accepted_query_ids.add(row_id)
        accepted.append({
            **source,
            "question": question_zh,
            "reference": answer_zh,
            "source_evidence": evidence_output,
            "contentReviewStatus": "HUMAN_REVIEWED",
            "contentReviewer": review.get("reviewer", ""),
            "contentReviewedAt": review.get("reviewedAt", ""),
            "trainingEligible": False,
            "eligibilityReason": "RETRIEVAL_LABELS_NOT_BUILT",
        })

    if pending and not allow_partial:
        raise ValueError(f"HotpotQA question review still has {pending} pending rows")
    if accepted and not allow_machine_distractors:
        raise ValueError(
            "full Chinese distractor corpus is not human-reviewed; pass --allow-machine-distractors "
            "only for isolated retrieval-label experiments"
        )

    document_output: list[dict[str, Any]] = []
    gold_document_count = 0
    replaced_evidence_count = 0
    for document in documents:
        title_en = str(document.get("titleEn") or "")
        source_sentences = [str(value) for value in document.get("sentencesEn", [])]
        target_sentences = [str(value) for value in document.get("sentences", [])]
        if len(source_sentences) != len(target_sentences):
            raise ValueError(f"sentence alignment differs for document {title_en}")
        title_zh = accepted_title_zh.get(title_en, str(document.get("title") or title_en))
        is_reviewed_gold = title_en in accepted_title_zh
        if is_reviewed_gold:
            gold_document_count += 1
        replaced: list[str] = []
        for source_sentence, machine_sentence in zip(source_sentences, target_sentences, strict=True):
            reviewed = accepted_evidence.get((title_en, source_sentence))
            if reviewed is not None:
                replaced_evidence_count += 1
                replaced.append(reviewed)
            else:
                replaced.append(machine_sentence)
        bilingual_title = f"{title_zh}（{title_en}）" if title_zh and title_zh != title_en else title_en
        document_output.append({
            **document,
            "title": title_zh,
            "text": bilingual_title + "\n\n" + "".join(replaced),
            "sentences": replaced,
            "contentReviewStatus": (
                "GOLD_TITLE_AND_EVIDENCE_REVIEWED_MACHINE_CONTEXT"
                if is_reviewed_gold else "MACHINE_TRANSLATED_DISTRACTOR"
            ),
            "mayBeUsedAsGoldEvidence": is_reviewed_gold,
        })

    write_jsonl(output_dir / "retrieval-labeling-source.jsonl", accepted)
    write_jsonl(output_dir / "import-documents.reviewed-gold.jsonl", document_output)
    write_jsonl(output_dir / "excluded.jsonl", excluded)
    return {
        "acceptedRows": len(accepted),
        "excludedRows": len(excluded),
        "pendingRows": pending,
        "acceptedGoldDocuments": gold_document_count,
        "replacedGoldEvidenceSentences": replaced_evidence_count,
        "machineDistractorsExplicitlyAllowed": allow_machine_distractors,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Finalize reviewed public routing sources")
    parser.add_argument("--prepared-dir", required=True)
    parser.add_argument("--hotpot-translated-documents", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--allow-partial", action="store_true")
    parser.add_argument("--allow-machine-distractors", action="store_true")
    args = parser.parse_args()

    prepared_dir = Path(args.prepared_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": 1,
        "miracl": finalize_miracl(
            prepared_dir / "miracl-zh", output_dir / "miracl-zh", args.allow_partial
        ),
        "hotpotQaBridge": finalize_hotpot(
            prepared_dir / "hotpotqa-bridge",
            Path(args.hotpot_translated_documents).resolve(),
            output_dir / "hotpotqa-bridge",
            args.allow_partial,
            args.allow_machine_distractors,
        ),
    }
    content_ready = (
        report["miracl"]["pendingRows"] == 0
        and report["hotpotQaBridge"]["pendingRows"] == 0
        and report["hotpotQaBridge"]["acceptedRows"] > 0
    )
    report["readiness"] = {
        "contentReadyForRetrievalLabeling": content_ready,
        "trainingReady": False,
        "nextGate": (
            "RUN_RETRIEVAL_ABLATIONS_AND_REVIEW_FOUR_HEAD_LABELS"
            if content_ready else "COMPLETE_CONTENT_REVIEW"
        ),
    }
    (output_dir / "finalization-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
