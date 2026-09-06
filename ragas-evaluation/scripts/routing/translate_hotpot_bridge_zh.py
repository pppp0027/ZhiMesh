from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

import ctranslate2
import sentencepiece as spm


NUMBER_PATTERN = re.compile(r"(?<![A-Za-z])(?:\d{1,4}(?:[,.]\d+)*(?:st|nd|rd|th)?%?)(?![A-Za-z])", re.I)
CHINESE_PATTERN = re.compile(r"[\u3400-\u9fff]")
LATIN_PATTERN = re.compile(r"[A-Za-z]")
NEGATIVE_PATTERN = re.compile(r"\b(?:no|not|never|neither|without|cannot|can't|didn't|doesn't|isn't|wasn't|weren't)\b", re.I)
ZH_NEGATIVE_PATTERN = re.compile(r"不|未|无|没有|并非|不能|无法|从未|既不|也不")
COMPARISON_PATTERN = re.compile(r"\b(?:before|after|earlier|later|older|younger|longer|shorter|more|less)\b", re.I)
ZH_COMPARISON_PATTERN = re.compile(r"之前|之后|以前|后来|更早|较早|更晚|较晚|年长|年轻|更长|更短|更多|更少|大于|小于")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSONL at {path}:{line_number}: {exc}") from exc
    return rows


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def safe_filename(title: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", title).strip(" ._")
    cleaned = re.sub(r"\s+", "_", cleaned)[:60] or "document"
    suffix = hashlib.sha1(title.encode("utf-8")).hexdigest()[:12]
    return f"hotpot_en_{cleaned}_{suffix}.txt"


class BatchTranslator:
    def __init__(self, model_dir: Path, cache_path: Path, batch_size: int = 64) -> None:
        self.model_dir = model_dir
        self.cache_path = cache_path
        self.batch_size = batch_size
        self.processor = spm.SentencePieceProcessor(model_file=str(model_dir / "sentencepiece.model"))
        self.translator = ctranslate2.Translator(
            str(model_dir / "model"), device="cpu", inter_threads=1,
            intra_threads=max(1, min(os.cpu_count() or 1, 8)),
        )
        self.cache: dict[str, str] = {}
        if cache_path.exists():
            for row in read_jsonl(cache_path):
                source = str(row.get("source", ""))
                target = str(row.get("target", ""))
                if source and target:
                    self.cache[source] = target.replace("▁", " ").replace("_", " ").strip()

    def translate_many(self, values: Iterable[str], progress_label: str) -> dict[str, str]:
        ordered = list(dict.fromkeys(str(value) for value in values if str(value).strip()))
        pending = [value for value in ordered if value not in self.cache]
        total = len(pending)
        if total:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            started = time.perf_counter()
            with self.cache_path.open("a", encoding="utf-8", newline="\n") as cache_file:
                for start in range(0, total, self.batch_size):
                    batch = pending[start:start + self.batch_size]
                    tokenized = [self.processor.encode(value, out_type=str) for value in batch]
                    translated = self.translator.translate_batch(
                        tokenized,
                        replace_unknowns=True,
                        max_batch_size=4096,
                        batch_type="tokens",
                        beam_size=2,
                        num_hypotheses=1,
                        length_penalty=0.2,
                    )
                    for source, result in zip(batch, translated, strict=True):
                        target = (self.processor.decode_pieces(result.hypotheses[0])
                                  .replace("▁", " ").replace("_", " ").strip())
                        self.cache[source] = target
                        cache_file.write(json.dumps(
                            {"source": source, "target": target},
                            ensure_ascii=False, separators=(",", ":"),
                        ) + "\n")
                    cache_file.flush()
                    done = min(start + len(batch), total)
                    if done == total or done % 320 == 0:
                        elapsed = time.perf_counter() - started
                        rate = done / elapsed if elapsed else 0.0
                        print(f"[{progress_label}] {done:,}/{total:,} ({rate:.1f} texts/s)", flush=True)
        return {value: self.cache[value] for value in ordered}


class EntityProtector:
    def __init__(self, title_map: dict[str, str]) -> None:
        self.title_map = title_map
        self.titles = sorted(title_map, key=len, reverse=True)
        self.casefold_titles = {title.casefold(): title for title in self.titles}
        self.codes = {title: f"ZXENT{alpha_code(index, 5)}XZ" for index, title in enumerate(self.titles)}
        self.code_to_target = {self.codes[title]: title_map[title] for title in self.titles}
        self.pattern = re.compile("|".join(re.escape(title) for title in self.titles), re.I)

    def protect(self, text: str) -> tuple[str, list[tuple[str, str]]]:
        occurrences: list[tuple[str, str]] = []

        def replace_entity(match: re.Match[str]) -> str:
            actual = self.casefold_titles.get(match.group(0).casefold())
            if actual is None:
                return match.group(0)
            code = self.codes[actual]
            occurrences.append(("entity", self.title_map[actual]))
            return code

        protected = self.pattern.sub(replace_entity, text)
        number_index = 0

        def replace_number(match: re.Match[str]) -> str:
            nonlocal number_index
            code = f"ZXNUM{alpha_code(number_index, 4)}XZ"
            number_index += 1
            occurrences.append(("number", match.group(0)))
            return code

        protected = NUMBER_PATTERN.sub(replace_number, protected)
        return protected, occurrences

    @staticmethod
    def restore(translated: str, occurrences: list[tuple[str, str]]) -> str:
        result = translated.replace("▁", " ").replace("_", " ")
        for kind, marker in (("entity", r"ZXENT[A-Z]+"), ("number", r"ZXNUM[A-Z]+")):
            values = [value for current_kind, value in occurrences if current_kind == kind]
            index = 0

            def replace_in_order(match: re.Match[str]) -> str:
                nonlocal index
                if index >= len(values):
                    return match.group(0)
                value = values[index]
                index += 1
                return value

            result = re.sub(marker, replace_in_order, result, flags=re.I)
        return result.strip()


def translate_protected(
    translator: BatchTranslator,
    protector: EntityProtector,
    values: Iterable[str],
    progress_label: str,
) -> tuple[dict[str, str], set[str]]:
    originals = list(dict.fromkeys(str(value) for value in values if str(value).strip()))
    protected: dict[str, tuple[str, list[tuple[str, str]]]] = {
        value: protector.protect(value) for value in originals
    }
    raw = translator.translate_many(
        (value[0] for value in protected.values()), progress_label,
    )
    result: dict[str, str] = {}
    fallback: set[str] = set()
    for original, (protected_text, occurrences) in protected.items():
        if original in protector.title_map:
            result[original] = protector.title_map[original]
            continue
        translated = raw[protected_text]
        source_entity_count = sum(1 for kind, _ in occurrences if kind == "entity")
        source_number_count = sum(1 for kind, _ in occurrences if kind == "number")
        target_entity_count = len(re.findall(r"ZXENT[A-Z]+", translated, flags=re.I))
        target_number_count = len(re.findall(r"ZXNUM[A-Z]+", translated, flags=re.I))
        if source_entity_count != target_entity_count or source_number_count != target_number_count:
            fallback.add(original)
            continue
        result[original] = protector.restore(translated, occurrences)
    if fallback:
        raw_fallback = translator.translate_many(fallback, progress_label + "-fallback")
        for original in fallback:
            result[original] = raw_fallback[original]
    return result, fallback


def canonical_documents(rows: list[dict[str, Any]]) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for row in rows:
        for title, sentences in row.get("context", []):
            title = str(title)
            values = [str(sentence) for sentence in sentences]
            existing = result.setdefault(title, [])
            seen = set(existing)
            for sentence in values:
                if sentence not in seen:
                    existing.append(sentence)
                    seen.add(sentence)
    return result


def alpha_code(value: int, width: int) -> str:
    if value < 0:
        raise ValueError("alpha code value must be non-negative")
    characters = ["A"] * width
    remaining = value
    for index in range(width - 1, -1, -1):
        characters[index] = chr(ord("A") + remaining % 26)
        remaining //= 26
    if remaining:
        raise ValueError("alpha code width is too small")
    return "".join(characters)


def sentence_lookup(row: dict[str, Any]) -> dict[str, list[str]]:
    return {str(title): [str(sentence) for sentence in sentences] for title, sentences in row.get("context", [])}


def quality_flags(source: str, target: str) -> list[str]:
    flags: list[str] = []
    if not target.strip():
        flags.append("EMPTY_TRANSLATION")
    if source.strip() == target.strip():
        flags.append("UNCHANGED_TRANSLATION")
    target_non_space = len(re.sub(r"\s+", "", target))
    chinese_ratio = len(CHINESE_PATTERN.findall(target)) / max(1, target_non_space)
    if len(source) >= 20 and chinese_ratio < 0.12:
        flags.append("LOW_CHINESE_RATIO")
    if NEGATIVE_PATTERN.search(source) and not ZH_NEGATIVE_PATTERN.search(target):
        flags.append("NEGATION_REVIEW")
    if COMPARISON_PATTERN.search(source) and not ZH_COMPARISON_PATTERN.search(target):
        flags.append("COMPARISON_REVIEW")
    source_numbers = [re.sub(r"[,\s]", "", value) for value in NUMBER_PATTERN.findall(source)]
    target_numbers = {re.sub(r"[,\s]", "", value) for value in NUMBER_PATTERN.findall(target)}
    if any(value not in target_numbers for value in source_numbers):
        flags.append("NUMBER_REVIEW")
    if "ZXENT" in target or "ZXNUM" in target:
        flags.append("UNRESTORED_PLACEHOLDER")
    return flags


def main() -> None:
    parser = argparse.ArgumentParser(description="Translate the prepared HotpotQA Bridge subset to Chinese")
    parser.add_argument("--source", required=True, help="bridge-500-source.jsonl")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--sample-size", type=int, default=0,
                        help="Translate only the first N rows for quality inspection")
    args = parser.parse_args()
    source_path = Path(args.source).resolve()
    output_dir = Path(args.output_dir).resolve()
    model_dir = Path(args.model_dir).resolve()
    rows = read_jsonl(source_path)
    if args.sample_size:
        rows = rows[:args.sample_size]
    if not rows:
        raise SystemExit("No HotpotQA rows to translate")

    documents_en = canonical_documents(rows)
    cache_path = output_dir / "translation-cache.jsonl"
    translator = BatchTranslator(model_dir, cache_path, args.batch_size)

    # Titles are translated once, then protected everywhere else to keep entity naming stable.
    title_map = translator.translate_many(documents_en.keys(), "titles")
    protector = EntityProtector(title_map)
    all_sentences = [sentence for sentences in documents_en.values() for sentence in sentences]
    sentence_map, sentence_fallback = translate_protected(
        translator, protector, all_sentences, "sentences"
    )
    sentence_map[""] = ""
    questions = [str(row.get("question", "")) for row in rows]
    answers = [str(row.get("answer", "")) for row in rows]
    question_map, question_fallback = translate_protected(
        translator, protector, questions, "questions"
    )
    answer_map, answer_fallback = translate_protected(
        translator, protector, answers, "answers"
    )
    for answer in answers:
        if NUMBER_PATTERN.fullmatch(answer.strip()):
            answer_map[answer] = answer.strip()
            answer_fallback.discard(answer)

    document_rows: list[dict[str, Any]] = []
    document_text_by_name: dict[str, str] = {}
    for title, sentences in sorted(documents_en.items()):
        document_name = safe_filename(title)
        translated_sentences = [sentence_map[sentence] for sentence in sentences]
        text = title_map[title] + "\n\n" + "".join(translated_sentences)
        document_text_by_name[document_name] = text
        document_rows.append({
            "documentName": document_name,
            "title": title_map[title],
            "titleEn": title,
            "text": text,
            "sentences": translated_sentences,
            "sentencesEn": sentences,
            "language": "zh-CN",
            "translationStatus": "MACHINE_TRANSLATED",
            "translationModel": "argos-opus-mt-en-zh-1.9",
        })

    query_rows: list[dict[str, Any]] = []
    review_rows: list[dict[str, Any]] = []
    flag_counts: dict[str, int] = defaultdict(int)
    for row in rows:
        contexts = sentence_lookup(row)
        supporting: dict[str, list[int]] = defaultdict(list)
        for title, sentence_index in row.get("supporting_facts", []):
            supporting[str(title)].append(int(sentence_index))
        evidence: list[dict[str, str]] = []
        evidence_missing = False
        for title, indexes in supporting.items():
            source_sentences = contexts.get(title, [])
            selected = [source_sentences[index] for index in sorted(set(indexes)) if index < len(source_sentences)]
            document_name = safe_filename(title)
            for source_sentence in selected:
                translated_evidence = sentence_map[source_sentence]
                if translated_evidence not in document_text_by_name.get(document_name, ""):
                    evidence_missing = True
                evidence.append({
                    "document": document_name,
                    "evidence": translated_evidence,
                    "evidenceEn": source_sentence,
                })
        question_en = str(row.get("question", ""))
        answer_en = str(row.get("answer", ""))
        question_zh = question_map[question_en]
        answer_zh = answer_map[answer_en]
        flags = quality_flags(question_en, question_zh) + quality_flags(answer_en, answer_zh)
        if question_en in question_fallback or answer_en in answer_fallback:
            flags.append("GLOSSARY_FALLBACK")
        if any(item["evidenceEn"] in sentence_fallback for item in evidence):
            flags.append("EVIDENCE_GLOSSARY_FALLBACK")
        if evidence_missing:
            flags.append("EVIDENCE_NOT_IN_DOCUMENT")
        flags = sorted(set(flags))
        for flag in flags:
            flag_counts[flag] += 1
        translated_row = {
            "id": f"hotpot_bridge_{row['_id']}",
            "question": question_zh,
            "questionEn": question_en,
            "reference": answer_zh,
            "referenceEn": answer_en,
            "source_documents": sorted(safe_filename(title) for title in supporting),
            "source_evidence": evidence,
            "question_type": "public_graph_multihop",
            "difficulty": "hard",
            "is_answerable": True,
            "public_dataset": "HotpotQA",
            "public_split": "dev-distractor",
            "public_query_id": row.get("_id"),
            "language": "zh-CN",
            "translation_status": "REVIEW_REQUIRED" if flags else "MACHINE_TRANSLATED",
            "translation_flags": flags,
        }
        query_rows.append(translated_row)
        if flags:
            review_rows.append(translated_row)

    output_dir.mkdir(parents=True, exist_ok=True)
    suffix = f".sample-{len(rows)}" if args.sample_size else ""
    documents_path = output_dir / f"import-documents.zh-CN{suffix}.jsonl"
    queries_path = output_dir / f"router-queries.zh-CN{suffix}.jsonl"
    write_jsonl(documents_path, document_rows)
    write_jsonl(queries_path, query_rows)
    write_jsonl(output_dir / f"review-required.zh-CN{suffix}.jsonl", review_rows)
    report = {
        "schemaVersion": 1,
        "translationModel": "argos-opus-mt-en-zh-1.9",
        "source": str(source_path),
        "sample": bool(args.sample_size),
        "queries": len(query_rows),
        "documents": len(document_rows),
        "uniqueEnglishSentences": len(set(all_sentences)),
        "sentenceGlossaryFallbacks": len(sentence_fallback),
        "questionGlossaryFallbacks": len(question_fallback),
        "answerGlossaryFallbacks": len(answer_fallback),
        "reviewRequiredRows": len(review_rows),
        "flagCounts": dict(sorted(flag_counts.items())),
        "invariants": {
            "missingDocumentReferences": sum(
                1 for query in query_rows for name in query["source_documents"]
                if name not in document_text_by_name
            ),
            "emptyEvidence": sum(
                1 for query in query_rows for item in query["source_evidence"] if not item["evidence"]
            ),
            "evidenceNotInDocument": flag_counts.get("EVIDENCE_NOT_IN_DOCUMENT", 0),
        },
    }
    report_path = output_dir / f"quality-report.zh-CN{suffix}.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
