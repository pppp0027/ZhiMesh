from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import random
import re
import shutil
import time
import urllib.request
import urllib.parse
import urllib.error
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


MIRACL_ANNOTATION_BASE = (
    "https://huggingface.co/datasets/miracl/miracl/resolve/main/miracl-v1.0-zh"
)
MIRACL_ANNOTATIONS = {
    "topics.train.tsv": f"{MIRACL_ANNOTATION_BASE}/topics/topics.miracl-v1.0-zh-train.tsv",
    "qrels.train.tsv": f"{MIRACL_ANNOTATION_BASE}/qrels/qrels.miracl-v1.0-zh-train.tsv",
    "topics.dev.tsv": f"{MIRACL_ANNOTATION_BASE}/topics/topics.miracl-v1.0-zh-dev.tsv",
    "qrels.dev.tsv": f"{MIRACL_ANNOTATION_BASE}/qrels/qrels.miracl-v1.0-zh-dev.tsv",
}
MIRACL_CORPUS_URLS = [
    "https://huggingface.co/datasets/miracl/miracl-corpus/resolve/main/"
    f"miracl-corpus-v1.0-zh/docs-{index}.jsonl.gz"
    for index in range(10)
]
HOTPOT_DEV_URL = (
    "http://curtis.ml.cmu.edu/datasets/hotpot/hotpot_dev_distractor_v1.json"
)
HOTPOT_HF_ROWS_URL = "https://datasets-server.huggingface.co/rows"


def json_dump(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path, retries: int = 4) -> dict[str, Any]:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 0:
        print(f"[reuse] {target.name} ({target.stat().st_size:,} bytes)", flush=True)
        return {"url": url, "bytes": target.stat().st_size, "sha256": sha256(target)}
    partial = target.with_suffix(target.suffix + ".part")
    for attempt in range(1, retries + 1):
        try:
            if partial.exists():
                partial.unlink()
            request = urllib.request.Request(url, headers={"User-Agent": "ZhiMesh-Router-Dataset/1.0"})
            print(f"[download {attempt}/{retries}] {url}", flush=True)
            with urllib.request.urlopen(request, timeout=180) as response, partial.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
            partial.replace(target)
            print(f"[saved] {target} ({target.stat().st_size:,} bytes)", flush=True)
            return {"url": url, "bytes": target.stat().st_size, "sha256": sha256(target)}
        except Exception as exc:
            print(f"[retry] {type(exc).__name__}: {exc}", flush=True)
            if attempt == retries:
                raise
            time.sleep(attempt * 2)
    raise AssertionError("unreachable")


def load_topics(path: Path) -> dict[str, str]:
    topics: dict[str, str] = {}
    with path.open(encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            fields = line.rstrip("\n").split("\t", 1)
            if len(fields) != 2:
                raise ValueError(f"Invalid topic line {path}:{line_number}")
            topics[fields[0]] = fields[1]
    return topics


def load_qrels(path: Path) -> dict[str, dict[str, int]]:
    qrels: dict[str, dict[str, int]] = defaultdict(dict)
    with path.open(encoding="utf-8-sig") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            fields = line.split()
            if len(fields) != 4:
                raise ValueError(f"Invalid qrel line {path}:{line_number}")
            qid, _, docid, relevance = fields
            qrels[qid][docid] = int(relevance)
    return dict(qrels)


def article_id(docid: str) -> str:
    return docid.rsplit("#", 1)[0]


def safe_filename(value: str, prefix: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value).strip(" ._")
    cleaned = re.sub(r"\s+", "_", cleaned)[:60] or "document"
    suffix = hashlib.sha1(value.encode("utf-8")).hexdigest()[:12]
    return f"{prefix}_{cleaned}_{suffix}.txt"


def prepare_miracl(destination: Path, working: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    official = destination / "miracl-zh" / "official"
    prepared = destination / "miracl-zh" / "prepared"
    annotation_meta: dict[str, Any] = {}
    for name, url in MIRACL_ANNOTATIONS.items():
        annotation_meta[name] = download(url, official / name)

    topics_by_split = {
        split: load_topics(official / f"topics.{split}.tsv") for split in ("train", "dev")
    }
    qrels_by_split = {
        split: load_qrels(official / f"qrels.{split}.tsv") for split in ("train", "dev")
    }
    wanted_docids = {
        docid
        for split_qrels in qrels_by_split.values()
        for judgments in split_qrels.values()
        for docid in judgments
    }
    print(f"[miracl] wanted judged passages={len(wanted_docids):,}", flush=True)

    corpus: dict[str, dict[str, Any]] = {}
    shard_meta: list[dict[str, Any]] = []
    for index, url in enumerate(MIRACL_CORPUS_URLS):
        shard = working / "miracl-zh" / f"docs-{index}.jsonl.gz"
        metadata = download(url, shard)
        matched = 0
        with gzip.open(shard, "rt", encoding="utf-8") as handle:
            for line in handle:
                row = json.loads(line)
                docid = str(row.get("docid", ""))
                if docid in wanted_docids:
                    corpus[docid] = {
                        "docid": docid,
                        "title": row.get("title", ""),
                        "text": row.get("text", ""),
                    }
                    matched += 1
        metadata.update({"name": shard.name, "matchedJudgedPassages": matched})
        shard_meta.append(metadata)
        shard.unlink()
        print(
            f"[miracl] shard={index} matched={matched:,}; total={len(corpus):,}/{len(wanted_docids):,}",
            flush=True,
        )
    missing = sorted(wanted_docids - set(corpus))
    if missing:
        raise RuntimeError(f"MIRACL corpus is missing {len(missing)} judged passages")

    write_jsonl(prepared / "corpus-judged.jsonl", (corpus[key] for key in sorted(corpus)))
    passages_by_article: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in corpus.values():
        passages_by_article[article_id(row["docid"])].append(row)
    document_names: dict[str, str] = {}
    documents: list[dict[str, Any]] = []
    for current_article_id, passages in sorted(passages_by_article.items()):
        passages.sort(key=lambda row: row["docid"])
        title = next((str(row["title"]) for row in passages if row.get("title")), current_article_id)
        document_name = safe_filename(title, "miracl_zh")
        document_names[current_article_id] = document_name
        documents.append({
            "documentName": document_name,
            "articleId": current_article_id,
            "title": title,
            "text": "\n\n".join(str(row["text"]) for row in passages if row.get("text")),
            "sourcePassageIds": [row["docid"] for row in passages],
        })
    write_jsonl(prepared / "import-documents.jsonl", documents)

    query_rows: list[dict[str, Any]] = []
    positive_judgments = 0
    negative_judgments = 0
    for split in ("train", "dev"):
        for qid, question in sorted(topics_by_split[split].items()):
            judgments = qrels_by_split[split].get(qid, {})
            positive_docids = sorted(docid for docid, rel in judgments.items() if rel > 0)
            negative_docids = sorted(docid for docid, rel in judgments.items() if rel <= 0)
            positive_judgments += len(positive_docids)
            negative_judgments += len(negative_docids)
            source_documents = sorted({document_names[article_id(docid)] for docid in positive_docids})
            evidence = [
                {"document": document_names[article_id(docid)], "evidence": corpus[docid]["text"]}
                for docid in positive_docids
            ]
            query_rows.append({
                "id": f"miracl_zh_{qid}",
                "question": question,
                "source_documents": source_documents,
                "source_evidence": evidence,
                "question_type": "public_retrieval",
                "difficulty": "medium",
                "is_answerable": bool(positive_docids),
                "public_dataset": "MIRACL",
                "public_split": split,
                "public_query_id": qid,
                "positive_passage_ids": positive_docids,
                "judged_negative_passage_ids": negative_docids,
            })
    write_jsonl(prepared / "router-queries.jsonl", query_rows)
    stats = {
        "trainQueries": len(topics_by_split["train"]),
        "devQueries": len(topics_by_split["dev"]),
        "routerQueries": len(query_rows),
        "judgedPassages": len(corpus),
        "aggregatedImportDocuments": len(documents),
        "positiveJudgments": positive_judgments,
        "negativeJudgments": negative_judgments,
    }
    json_dump(prepared / "stats.json", stats)
    manifest["downloads"]["miraclAnnotations"] = annotation_meta
    manifest["downloads"]["miraclCorpusShards"] = shard_meta
    manifest["prepared"]["miraclZh"] = stats
    return stats


def hotpot_document_name(title: str) -> str:
    return safe_filename(title, "hotpot_en")


def prepare_hotpot(destination: Path, working: Path, manifest: dict[str, Any], sample_size: int) -> dict[str, Any]:
    official = destination / "hotpotqa-bridge" / "official"
    prepared = destination / "hotpotqa-bridge" / "prepared"
    raw = working / "hotpotqa" / "hotpot_dev_distractor_v1.json"
    print("[hotpot] using official Hugging Face mirror after CMU source returned HTTP 502", flush=True)
    source = load_hotpot_huggingface_rows(required_bridge=max(sample_size, sample_size * 2))
    download_meta = {
        "url": HOTPOT_HF_ROWS_URL,
        "canonicalCmuUrl": HOTPOT_DEV_URL,
        "repository": "hotpotqa/hotpot_qa",
        "config": "distractor",
        "split": "validation",
        "rows": len(source),
    }
    source_name = "official-huggingface-mirror"
    bridge = [row for row in source if str(row.get("type", "")).lower() == "bridge"]
    random.Random(42).shuffle(bridge)
    selected = bridge[:sample_size]
    if len(selected) < sample_size:
        raise RuntimeError(f"HotpotQA has only {len(selected)} bridge rows, requested {sample_size}")
    write_jsonl(official / f"bridge-{sample_size}-source.jsonl", selected)

    title_to_sentences: dict[str, list[str]] = {}
    for item in selected:
        for title, sentences in item.get("context", []):
            values = [str(sentence) for sentence in sentences]
            existing = title_to_sentences.get(str(title))
            if existing is None or sum(map(len, values)) > sum(map(len, existing)):
                title_to_sentences[str(title)] = values
    documents = [
        {
            "documentName": hotpot_document_name(title),
            "title": title,
            "text": "".join(sentences),
            "sentences": sentences,
            "language": "en",
            "translationStatus": "PENDING",
        }
        for title, sentences in sorted(title_to_sentences.items())
    ]
    write_jsonl(prepared / "import-documents.en.jsonl", documents)

    query_rows: list[dict[str, Any]] = []
    for item in selected:
        supporting_by_title: dict[str, list[int]] = defaultdict(list)
        for title, sentence_index in item.get("supporting_facts", []):
            supporting_by_title[str(title)].append(int(sentence_index))
        evidence: list[dict[str, str]] = []
        for title, indexes in supporting_by_title.items():
            sentences = title_to_sentences.get(title, [])
            selected_sentences = [sentences[index] for index in sorted(set(indexes)) if index < len(sentences)]
            evidence.append({
                "document": hotpot_document_name(title),
                "evidence": "".join(selected_sentences),
            })
        query_rows.append({
            "id": f"hotpot_bridge_{item['_id']}",
            "question": item.get("question"),
            "reference": item.get("answer"),
            "source_documents": sorted(hotpot_document_name(title) for title in supporting_by_title),
            "source_evidence": evidence,
            "question_type": "public_graph_multihop",
            "difficulty": "hard",
            "is_answerable": True,
            "public_dataset": "HotpotQA",
            "public_split": "dev-distractor",
            "public_query_id": item.get("_id"),
            "language": "en",
            "translation_status": "PENDING",
        })
    write_jsonl(prepared / "router-queries.en.jsonl", query_rows)
    stats = {
        "sourceRows": len(source),
        "sourceBridgeRows": len(bridge),
        "selectedBridgeRows": len(selected),
        "uniqueImportDocuments": len(documents),
        "language": "en",
        "translationStatus": "PENDING",
        "selectionSeed": 42,
        "source": source_name,
    }
    json_dump(prepared / "stats.json", stats)
    manifest["downloads"]["hotpotDevDistractor"] = download_meta
    manifest["prepared"]["hotpotQaBridge"] = stats
    if raw.exists():
        raw.unlink()
    return stats


def load_hotpot_huggingface_rows(required_bridge: int) -> list[dict[str, Any]]:
    source: list[dict[str, Any]] = []
    offset = 0
    page_size = 100
    total: int | None = None
    while total is None or offset < total:
        query = urllib.parse.urlencode({
            "dataset": "hotpotqa/hotpot_qa",
            "config": "distractor",
            "split": "validation",
            "offset": offset,
            "length": page_size,
        })
        request = urllib.request.Request(
            f"{HOTPOT_HF_ROWS_URL}?{query}",
            headers={"User-Agent": "ZhiMesh-Router-Dataset/1.0"},
        )
        payload = None
        for attempt in range(1, 7):
            try:
                with urllib.request.urlopen(request, timeout=180) as response:
                    payload = json.load(response)
                break
            except urllib.error.HTTPError as exc:
                if exc.code != 429 or attempt == 6:
                    raise
                delay = attempt * 10
                print(f"[hotpot] rate limited at offset={offset}; retrying in {delay}s", flush=True)
                time.sleep(delay)
        if payload is None:
            raise RuntimeError("HotpotQA mirror returned no payload")
        page = payload.get("rows") or []
        if total is None:
            total = int(payload.get("num_rows_total") or 0)
            if total <= 0:
                raise RuntimeError("HotpotQA Hugging Face mirror returned no total row count")
            print(f"[hotpot] official HF validation rows={total:,}", flush=True)
        if not page:
            break
        for wrapper in page:
            row = wrapper.get("row") or {}
            supporting = row.get("supporting_facts") or {}
            if isinstance(supporting, dict):
                supporting = list(zip(supporting.get("title", []), supporting.get("sent_id", [])))
            context = row.get("context") or {}
            if isinstance(context, dict):
                context = list(zip(context.get("title", []), context.get("sentences", [])))
            source.append({
                "_id": row.get("id") or row.get("_id"),
                "question": row.get("question"),
                "answer": row.get("answer"),
                "type": row.get("type"),
                "level": row.get("level"),
                "supporting_facts": supporting,
                "context": context,
            })
        offset += len(page)
        bridge_count = sum(1 for row in source if str(row.get("type", "")).lower() == "bridge")
        if offset % 500 == 0 or offset >= total:
            print(f"[hotpot] fetched={offset:,}/{total:,}", flush=True)
        if bridge_count >= required_bridge:
            print(f"[hotpot] collected bridge candidates={bridge_count:,}; stopping compact fetch", flush=True)
            break
        time.sleep(0.35)
    return source


def write_readme(destination: Path, miracl_stats: dict[str, Any], hotpot_stats: dict[str, Any]) -> None:
    content = f"""# 意图路由公共训练候选数据

生成时间：{dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec='seconds')}

## MIRACL 中文紧凑集

- 官方 train/dev topics 与 qrels 完整保留在 `miracl-zh/official`。
- 扫描了官方中文语料的 10 个分片，只保留 qrels 实际涉及的段落。
- 路由候选问题：{miracl_stats['routerQueries']} 条。
- qrels 涉及段落：{miracl_stats['judgedPassages']} 条。
- 按 Wikipedia 文章聚合后的待导入文档：{miracl_stats['aggregatedImportDocuments']} 篇。
- `miracl-zh/prepared/router-queries.jsonl` 可作为后续 RAG retrieval-only 数据集。
- `miracl-zh/prepared/import-documents.jsonl` 是待导入独立训练知识库的文档。

## HotpotQA Bridge 子集

- 来源为官方 `hotpot_dev_distractor_v1.json`。
- 按固定随机种子 42 选取 Bridge 问题：{hotpot_stats['selectedBridgeRows']} 条。
- 上下文去重后的待导入文档：{hotpot_stats['uniqueImportDocuments']} 篇。
- 当前仍为英文，`translationStatus=PENDING`；使用中文 Embedding 前必须翻译问题、标题、文档和证据。
- `hotpotqa-bridge/official/bridge-{hotpot_stats['selectedBridgeRows']}-source.jsonl` 保留筛选后的官方原始结构。
- `hotpotqa-bridge/prepared/router-queries.en.jsonl` 和 `import-documents.en.jsonl` 是英文转换结果。

## 完整性

- `manifest.json` 记录官方 URL、可保留下载文件的元数据和筛选统计；大型 MIRACL 分片采用流式抽取后删除。
- `checksums.sha256` 记录最终交付文件的 SHA-256。
- MIRACL 使用 Apache-2.0；HotpotQA 官方说明为 CC BY-SA 4.0。对翻译或再分发数据时需要继续遵守对应许可。
"""
    (destination / "README.zh-CN.md").write_text(content, encoding="utf-8")


def write_checksums(destination: Path) -> None:
    files = sorted(
        path for path in destination.rglob("*")
        if path.is_file() and path.name != "checksums.sha256" and "_working" not in path.parts
    )
    lines = [f"{sha256(path)}  {path.relative_to(destination).as_posix()}" for path in files]
    (destination / "checksums.sha256").write_text("\n".join(lines) + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description="Download compact MIRACL-zh and HotpotQA Bridge router datasets")
    parser.add_argument("--destination", required=True)
    parser.add_argument("--hotpot-bridge-size", type=int, default=500)
    parser.add_argument("--finalize-only", action="store_true",
                        help="Regenerate README and checksums from an already prepared dataset")
    args = parser.parse_args()
    if args.hotpot_bridge_size <= 0:
        raise SystemExit("--hotpot-bridge-size must be positive")
    destination = Path(args.destination).expanduser().resolve()
    destination.mkdir(parents=True, exist_ok=True)
    if args.finalize_only:
        miracl_stats = json.loads(
            (destination / "miracl-zh" / "prepared" / "stats.json").read_text(encoding="utf-8")
        )
        hotpot_stats = json.loads(
            (destination / "hotpotqa-bridge" / "prepared" / "stats.json").read_text(encoding="utf-8")
        )
        write_readme(destination, miracl_stats, hotpot_stats)
        write_checksums(destination)
        print(f"[finalized] {destination}", flush=True)
        return
    working = destination / "_working"
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "selection": {"hotpotBridgeSize": args.hotpot_bridge_size, "seed": 42},
        "downloads": {},
        "prepared": {},
    }
    existing_miracl_stats = destination / "miracl-zh" / "prepared" / "stats.json"
    if existing_miracl_stats.exists():
        miracl_stats = json.loads(existing_miracl_stats.read_text(encoding="utf-8"))
        manifest["prepared"]["miraclZh"] = miracl_stats
        manifest["downloads"]["miraclAnnotations"] = {
            name: {
                "url": url,
                "bytes": (destination / "miracl-zh" / "official" / name).stat().st_size,
                "sha256": sha256(destination / "miracl-zh" / "official" / name),
            }
            for name, url in MIRACL_ANNOTATIONS.items()
        }
        manifest["downloads"]["miraclCorpusShards"] = [
            {"url": url, "status": "streamed-filter-complete"} for url in MIRACL_CORPUS_URLS
        ]
        print("[reuse] completed MIRACL prepared dataset", flush=True)
    else:
        miracl_stats = prepare_miracl(destination, working, manifest)
    hotpot_stats = prepare_hotpot(destination, working, manifest, args.hotpot_bridge_size)
    json_dump(destination / "manifest.json", manifest)
    write_readme(destination, miracl_stats, hotpot_stats)
    write_checksums(destination)
    try:
        working.rmdir()
    except OSError:
        pass
    print(json.dumps(manifest["prepared"], ensure_ascii=False, indent=2), flush=True)
    print(f"[complete] {destination}", flush=True)


if __name__ == "__main__":
    main()
