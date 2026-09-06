from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from routing_common import read_jsonl, write_jsonl


ZH_PATTERN = re.compile(r"[\u3400-\u9fff]")
QUESTION_END_PATTERN = re.compile(r"[?？。.]\s*$")
SUSPICIOUS_TRANSLATION_PATTERN = re.compile(
    r"津巴布韦|苏美尔|互联网档案馆|存档日期|萨斯达|语Name|ZXENT|ZXNUM|FTCXZ",
    re.I,
)


def normalize_question(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = re.sub(r"\s+", " ", text).strip()
    text = text.replace(" ,", ",").replace(" .", ".").replace(" ?", "?")
    return text


def normalized_key(value: Any) -> str:
    return re.sub(r"[\W_]+", "", normalize_question(value).casefold(), flags=re.UNICODE)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_csv(path: Path, fieldnames: list[str], rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def document_name_by_title(documents: list[dict[str, Any]]) -> dict[str, str]:
    result: dict[str, str] = {}
    for row in documents:
        title = str(row.get("titleEn") or "")
        name = str(row.get("documentName") or "")
        if title and name:
            result[title] = name
    return result


def translated_document_by_title(documents: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for row in documents:
        title = str(row.get("titleEn") or "")
        if title:
            if title in result:
                raise ValueError(f"duplicate translated document title: {title}")
            result[title] = row
    return result


def source_sentence(row: dict[str, Any], title: str, index: int) -> str:
    for context_title, sentences in row.get("context", []):
        if str(context_title) == title:
            values = [str(value) for value in sentences]
            if not 0 <= index < len(values):
                raise ValueError(f"supporting fact index out of range: {row.get('_id')} {title}[{index}]")
            return values[index]
    raise ValueError(f"supporting fact document missing: {row.get('_id')} {title}")


def machine_sentence(document: dict[str, Any], source: str) -> str:
    sources = [str(value) for value in document.get("sentencesEn", [])]
    targets = [str(value) for value in document.get("sentences", [])]
    for index, value in enumerate(sources):
        if value == source and index < len(targets):
            return targets[index]
    return ""


def question_issue_codes(question_en: str, question_zh: str, answer_en: str,
                         answer_zh: str, inherited: Iterable[str]) -> list[str]:
    issues = {str(value) for value in inherited if str(value)}
    if not question_zh:
        issues.add("EMPTY_QUESTION_ZH")
    if question_en.rstrip().endswith("?") and question_zh and not QUESTION_END_PATTERN.search(question_zh):
        issues.add("POSSIBLE_TRUNCATION")
    if question_zh.rstrip().endswith((",", "，", ":", "：")):
        issues.add("POSSIBLE_TRUNCATION")
    if len(question_en) >= 30 and len(question_zh) < max(8, len(question_en) * 0.18):
        issues.add("POSSIBLE_TRUNCATION")
    if question_zh and not ZH_PATTERN.search(question_zh):
        issues.add("NO_CHINESE_QUESTION")
    if answer_zh and SUSPICIOUS_TRANSLATION_PATTERN.search(answer_zh):
        issues.add("SUSPICIOUS_ANSWER_TEXT")
    if question_zh and SUSPICIOUS_TRANSLATION_PATTERN.search(question_zh):
        issues.add("SUSPICIOUS_QUESTION_TEXT")
    if answer_en and not answer_zh:
        issues.add("EMPTY_ANSWER_ZH")
    return sorted(issues)


def prepare_miracl(rows: list[dict[str, Any]], output_dir: Path) -> dict[str, Any]:
    ids: set[str] = set()
    duplicate_groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        duplicate_groups[normalized_key(row.get("question"))].append(row)
    representative_by_key: dict[str, str] = {}
    for key, group in duplicate_groups.items():
        representative = max(
            group,
            key=lambda row: (
                len(row.get("positive_passage_ids") or []),
                len(row.get("source_evidence") or []),
                len(row.get("source_documents") or []),
                str(row.get("id") or ""),
            ),
        )
        representative_by_key[key] = str(representative.get("id") or "")
    candidates: list[dict[str, Any]] = []
    review: list[dict[str, Any]] = []
    issue_counts: Counter[str] = Counter()
    for row in rows:
        row_id = str(row.get("id") or "")
        question = normalize_question(row.get("question"))
        issues: list[str] = []
        if not row_id or row_id in ids:
            issues.append("BLANK_OR_DUPLICATE_ID")
        ids.add(row_id)
        if not question:
            issues.append("EMPTY_QUESTION")
        if not row.get("source_documents") or not row.get("source_evidence"):
            issues.append("MISSING_GOLD_EVIDENCE")
        question_key = normalized_key(question)
        duplicate_group = duplicate_groups[question_key]
        if len(duplicate_group) > 1:
            issues.append("DUPLICATE_NORMALIZED_QUESTION")
        if not row.get("positive_passage_ids"):
            issues.append("MISSING_POSITIVE_QRELS")
        issue_counts.update(issues)
        prepared = {
            **row,
            "question": question,
            "contentReviewStatus": "STRUCTURAL_PASS" if not issues else "REVIEW_REQUIRED",
            "contentIssueCodes": sorted(set(issues)),
            "trainingEligible": False,
            "eligibilityReason": "RETRIEVAL_LABELS_NOT_BUILT",
        }
        candidates.append(prepared)
        if issues:
            representative_id = representative_by_key[question_key]
            review.append({
                "id": row_id,
                "question": question,
                "publicSplit": row.get("public_split"),
                "duplicateGroupId": hashlib.sha1(question_key.encode("utf-8")).hexdigest()[:12],
                "duplicateGroupSize": len(duplicate_group),
                "positivePassageCount": len(row.get("positive_passage_ids") or []),
                "suggestedDecision": "ACCEPT" if row_id == representative_id else "REJECT",
                "suggestedReason": (
                    "KEEP_RICHEST_GOLD_JUDGMENTS"
                    if row_id == representative_id else f"DUPLICATE_OF_{representative_id}"
                ),
                "issueCodes": "|".join(sorted(set(issues))),
                "decision": "PENDING",
                "reviewer": "",
                "notes": "",
            })
    write_jsonl(output_dir / "source-candidates.jsonl", candidates)
    write_csv(
        output_dir / "review-queue.csv",
        [
            "id", "question", "publicSplit", "duplicateGroupId", "duplicateGroupSize",
            "positivePassageCount", "suggestedDecision", "suggestedReason", "issueCodes",
            "decision", "reviewer", "notes",
        ],
        review,
    )
    return {
        "rows": len(rows),
        "structuralPassRows": len(rows) - len(review),
        "reviewRequiredRows": len(review),
        "issueCounts": dict(sorted(issue_counts.items())),
    }


def prepare_hotpot(source_rows: list[dict[str, Any]], translated_queries: list[dict[str, Any]],
                    translated_documents: list[dict[str, Any]], output_dir: Path) -> dict[str, Any]:
    query_by_public_id = {str(row.get("public_query_id")): row for row in translated_queries}
    documents_by_title = translated_document_by_title(translated_documents)
    names_by_title = document_name_by_title(translated_documents)
    question_rows: list[dict[str, Any]] = []
    evidence_rows: list[dict[str, Any]] = []
    title_usage: dict[str, set[str]] = defaultdict(set)
    title_machine: dict[str, str] = {}
    source_candidates: list[dict[str, Any]] = []
    issue_counts: Counter[str] = Counter()
    evidence_count = 0

    for line_number, source in enumerate(source_rows, 1):
        public_id = str(source.get("_id") or "")
        translated = query_by_public_id.get(public_id)
        if translated is None:
            raise ValueError(f"missing translated query for HotpotQA id {public_id}")
        question_en = str(source.get("question") or "")
        answer_en = str(source.get("answer") or "")
        question_zh = normalize_question(translated.get("question"))
        answer_zh = normalize_question(translated.get("reference"))
        issues = question_issue_codes(
            question_en, question_zh, answer_en, answer_zh,
            translated.get("translation_flags") or [],
        )
        issue_counts.update(issues)
        gold_titles = sorted({str(item[0]) for item in source.get("supporting_facts", [])})
        if len(gold_titles) != 2:
            issues.append("EXPECTED_TWO_GOLD_DOCUMENTS")
        for title in gold_titles:
            title_usage[title].add(public_id)
            document = documents_by_title.get(title)
            title_machine[title] = str((document or {}).get("title") or "")

        question_rows.append({
            "rowNumber": line_number,
            "id": f"hotpot_bridge_{public_id}",
            "publicQueryId": public_id,
            "questionEn": question_en,
            "machineQuestionZh": question_zh,
            "reviewedQuestionZh": "",
            "answerEn": answer_en,
            "machineAnswerZh": answer_zh,
            "reviewedAnswerZh": "",
            "goldTitle1En": gold_titles[0] if gold_titles else "",
            "goldTitle2En": gold_titles[1] if len(gold_titles) > 1 else "",
            "issueCodes": "|".join(sorted(set(issues))),
            "decision": "PENDING",
            "reviewer": "",
            "reviewedAt": "",
            "notes": "",
        })

        evidence_ids: list[str] = []
        for fact_number, (raw_title, raw_index) in enumerate(source.get("supporting_facts", []), 1):
            title = str(raw_title)
            sentence_index = int(raw_index)
            evidence_en = source_sentence(source, title, sentence_index)
            document = documents_by_title.get(title) or {}
            evidence_zh = machine_sentence(document, evidence_en)
            evidence_id = f"{public_id}:{fact_number}"
            evidence_ids.append(evidence_id)
            evidence_count += 1
            evidence_issues: list[str] = []
            if not evidence_zh:
                evidence_issues.append("MISSING_MACHINE_EVIDENCE")
            if evidence_zh and SUSPICIOUS_TRANSLATION_PATTERN.search(evidence_zh):
                evidence_issues.append("SUSPICIOUS_EVIDENCE_TEXT")
            evidence_rows.append({
                "evidenceId": evidence_id,
                "queryId": f"hotpot_bridge_{public_id}",
                "publicQueryId": public_id,
                "titleEn": title,
                "documentName": names_by_title.get(title, ""),
                "sentenceIndex": sentence_index,
                "evidenceEn": evidence_en,
                "machineEvidenceZh": normalize_question(evidence_zh),
                "reviewedEvidenceZh": "",
                "issueCodes": "|".join(evidence_issues),
                "decision": "PENDING",
                "reviewer": "",
                "reviewedAt": "",
                "notes": "",
            })

        source_candidates.append({
            "id": f"hotpot_bridge_{public_id}",
            "question": question_zh,
            "questionEn": question_en,
            "reference": answer_zh,
            "referenceEn": answer_en,
            "source_documents": sorted(names_by_title.get(title, "") for title in gold_titles),
            "goldTitleEn": gold_titles,
            "evidenceReviewIds": evidence_ids,
            "question_type": "public_graph_multihop",
            "difficulty": source.get("level"),
            "is_answerable": True,
            "public_dataset": "HotpotQA",
            "public_split": "dev-distractor",
            "public_query_id": public_id,
            "language": "zh-CN",
            "contentReviewStatus": "PENDING_TRANSLATION_REVIEW",
            "contentIssueCodes": sorted(set(issues)),
            "trainingEligible": False,
            "eligibilityReason": "QUESTION_ANSWER_EVIDENCE_AND_TITLES_NOT_REVIEWED",
        })

    title_rows = [{
        "titleEn": title,
        "machineTitleZh": title_machine.get(title, ""),
        "reviewedTitleZh": "",
        "goldQueryCount": len(query_ids),
        "goldQueryIds": "|".join(sorted(query_ids)),
        "decision": "PENDING",
        "reviewer": "",
        "reviewedAt": "",
        "notes": "",
    } for title, query_ids in sorted(title_usage.items())]

    write_csv(output_dir / "question-answer-review.csv", list(question_rows[0]), question_rows)
    write_csv(output_dir / "gold-evidence-review.csv", list(evidence_rows[0]), evidence_rows)
    write_csv(output_dir / "gold-title-review.csv", list(title_rows[0]), title_rows)
    write_jsonl(output_dir / "source-candidates.jsonl", source_candidates)
    return {
        "rows": len(source_rows),
        "questionAnswerReviewRows": len(question_rows),
        "goldEvidenceReviewRows": evidence_count,
        "goldTitleReviewRows": len(title_rows),
        "allRowsPendingHumanReview": True,
        "machineTranslationMayBeUsedAsReviewDraftOnly": True,
        "issueCounts": dict(sorted(issue_counts.items())),
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Prepare auditable MIRACL-zh and HotpotQA Bridge routing sources"
    )
    parser.add_argument("--miracl-queries", required=True)
    parser.add_argument("--hotpot-source", required=True)
    parser.add_argument("--hotpot-translated-queries", required=True)
    parser.add_argument("--hotpot-translated-documents", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    paths = {
        "miraclQueries": Path(args.miracl_queries).resolve(),
        "hotpotSource": Path(args.hotpot_source).resolve(),
        "hotpotTranslatedQueries": Path(args.hotpot_translated_queries).resolve(),
        "hotpotTranslatedDocuments": Path(args.hotpot_translated_documents).resolve(),
    }
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": 1,
        "purpose": "retrieval-routing-source-preparation",
        "inputs": {
            name: {"path": str(path), "sha256": sha256(path)} for name, path in paths.items()
        },
        "miracl": prepare_miracl(read_jsonl(paths["miraclQueries"]), output_dir / "miracl-zh"),
        "hotpotQaBridge": prepare_hotpot(
            read_jsonl(paths["hotpotSource"]),
            read_jsonl(paths["hotpotTranslatedQueries"]),
            read_jsonl(paths["hotpotTranslatedDocuments"]),
            output_dir / "hotpotqa-bridge",
        ),
        "readiness": {
            "contentReadyForRetrievalLabeling": False,
            "trainingReady": False,
            "nextGate": "COMPLETE_HOTPOT_HUMAN_REVIEW_AND_MIRACL_REVIEW_QUEUE",
        },
    }
    report_path = output_dir / "readiness-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
