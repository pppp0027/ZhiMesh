from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
from typing import Any

from dotenv import load_dotenv
from langchain_openai import OpenAIEmbeddings as LangchainOpenAIEmbeddings
from openai import AsyncOpenAI
from ragas import EvaluationDataset, evaluate
from ragas.cache import DiskCacheBackend
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import llm_factory
from ragas.metrics import (
    Faithfulness,
    LLMContextPrecisionWithReference,
    LLMContextRecall,
    ResponseRelevancy,
)
from ragas.metrics.collections import AnswerAccuracy
from ragas.metrics.result import MetricResult
from ragas.run_config import RunConfig

from common import append_jsonl, load_config, read_jsonl, resolve_path


class ReferenceCoverageAccuracy(AnswerAccuracy):
    """Question-aware accuracy that scores coverage of reference facts only."""

    RUBRIC = """You are evaluating whether a RAG answer correctly covers the reference answer for the given question.
Return rating 4 when the user answer covers every key fact in the reference answer and contains no contradiction with it.
Return rating 2 when the user answer correctly covers only part of the reference answer, or has a minor factual omission or imprecision while preserving the core answer.
Return rating 0 when the user answer misses the core answer, contradicts the reference answer, or gives an incorrect answer.
Do not lower the rating merely because the user answer contains additional details. Other metrics evaluate whether additional details are supported and relevant.
Judge factual coverage, not wording similarity or answer length.
Do not explain or justify the rating. Return only JSON in this exact format: {\"rating\": X}, where X is 0, 2, or 4."""

    def __init__(self, llm: Any):
        super().__init__(llm=llm, name="answer_accuracy")
        self.judge1_prompt.instruction = self.RUBRIC
        self.judge1_prompt.examples = []

    async def ascore(
        self, user_input: str, response: str, reference: str
    ) -> MetricResult:
        if not user_input or not response or not reference:
            raise ValueError("user_input, response and reference are required")
        rating = await self._get_judge_rating(
            self.judge1_prompt, user_input, response, reference
        )
        return MetricResult(value=float(rating / 4.0))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Score collected RAG answers with RAGAS")
    parser.add_argument("--config", default="config.json")
    parser.add_argument("--limit", type=int, help="Score only the first N pending samples")
    parser.add_argument("--batch-size", type=int, default=5, help="Persist results after each batch")
    return parser.parse_args()


def clean_value(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    if hasattr(value, "item"):
        return clean_value(value.item())
    return value


async def score_answer_accuracy(
    metric: AnswerAccuracy,
    rows: list[dict[str, Any]],
    max_workers: int,
) -> list[tuple[Any, str | None]]:
    """Score short references with question-aware reference coverage."""
    semaphore = asyncio.Semaphore(max(1, max_workers))

    async def score(row: dict[str, Any]) -> tuple[Any, str | None]:
        async with semaphore:
            try:
                result = await metric.ascore(
                    user_input=str(row.get("question", "")),
                    response=str(row.get("answer", "")),
                    reference=str(row.get("reference", "")),
                )
                value = clean_value(result.value)
                error = None if value is not None else "AnswerAccuracy returned no finite score"
                return value, error
            except Exception as exc:
                return None, f"{type(exc).__name__}: {exc}"

    return list(await asyncio.gather(*(score(row) for row in rows)))


def main() -> None:
    args = parse_args()
    load_dotenv(resolve_path(".env"))
    config = load_config(args.config)
    api_key = os.getenv("RAGAS_JUDGE_API_KEY", "").strip()
    judge_model = os.getenv("RAGAS_JUDGE_MODEL", "").strip()
    if not api_key or not judge_model:
        raise SystemExit("RAGAS_JUDGE_API_KEY and RAGAS_JUDGE_MODEL must be set in .env")

    judge_base_url = os.getenv("RAGAS_JUDGE_BASE_URL", "https://api.openai.com/v1").strip()
    judge_max_tokens = int(os.getenv("RAGAS_JUDGE_MAX_TOKENS", "4096"))
    if judge_max_tokens < 1024:
        raise SystemExit("RAGAS_JUDGE_MAX_TOKENS must be at least 1024")

    embedding_key = os.getenv("RAGAS_EMBEDDING_API_KEY", "").strip() or api_key
    embedding_base_url = os.getenv("RAGAS_EMBEDDING_BASE_URL", "").strip() or judge_base_url
    embedding_model = os.getenv("RAGAS_EMBEDDING_MODEL", "text-embedding-3-small").strip()
    judge_client = AsyncOpenAI(api_key=api_key, base_url=judge_base_url)
    cache = DiskCacheBackend(str(resolve_path(
        config.get("ragas_cache", "output/.ragas-cache")
    )))
    judge = llm_factory(
        judge_model,
        provider="openai",
        client=judge_client,
        cache=cache,
        temperature=0,
        max_tokens=judge_max_tokens,
    )
    embedding_backend = LangchainOpenAIEmbeddings(
        api_key=embedding_key,
        base_url=embedding_base_url,
        model=embedding_model,
        max_retries=int(config.get("ragas_retries", 2)),
        timeout=float(config.get("ragas_timeout_seconds", 180)),
        check_embedding_ctx_length=False,
    )
    embeddings = LangchainEmbeddingsWrapper(embedding_backend, cache=cache)
    metrics = [
        LLMContextPrecisionWithReference(llm=judge),
        LLMContextRecall(llm=judge),
        Faithfulness(llm=judge),
        ResponseRelevancy(llm=judge, embeddings=embeddings, strictness=1),
    ]
    answer_accuracy = ReferenceCoverageAccuracy(llm=judge)

    prediction_rows = read_jsonl(config.get("predictions", "output/ragas_predictions.jsonl"))
    latest_predictions: dict[str, dict[str, Any]] = {}
    for row in prediction_rows:
        if row.get("collection_status") == "success":
            latest_predictions[str(row["id"])] = row
    score_path = resolve_path(config.get("ragas_scores", "output/ragas_scores.jsonl"))
    completed: set[str] = set()
    if score_path.exists():
        completed = {
            str(row["id"]) for row in read_jsonl(score_path)
            if row.get("ragas_status") == "success"
        }
    pending = [row for key, row in latest_predictions.items() if key not in completed]
    if args.limit is not None:
        pending = pending[:args.limit]
    print(f"predictions={len(latest_predictions)}, scored={len(completed)}, pending={len(pending)}")
    print(f"judge_model={judge_model}, judge_max_tokens={judge_max_tokens}")
    if not pending:
        return

    run_config = RunConfig(
        timeout=int(config.get("ragas_timeout_seconds", 180)),
        max_retries=int(config.get("ragas_retries", 2)),
        max_wait=30,
        max_workers=max(1, min(int(config.get("ragas_workers", 2)), 4)),
    )
    ragas_workers = max(1, min(int(config.get("ragas_workers", 2)), 4))
    batch_size = max(1, args.batch_size)
    for start in range(0, len(pending), batch_size):
        source_batch = pending[start:start + batch_size]
        samples = [
            {
                "user_input": row["question"],
                "response": row.get("answer", ""),
                "retrieved_contexts": row.get("contexts", []),
                "reference": row.get("reference", ""),
            }
            for row in source_batch
        ]
        try:
            result = evaluate(
                dataset=EvaluationDataset.from_list(samples),
                metrics=metrics,
                llm=judge,
                embeddings=embeddings,
                run_config=run_config,
                raise_exceptions=False,
                show_progress=True,
                batch_size=batch_size,
            )
            frame = result.to_pandas()
            print(f"[ANSWER_ACCURACY] scoring {len(source_batch)} sample(s) for reference coverage")
            accuracy_results = asyncio.run(
                score_answer_accuracy(answer_accuracy, source_batch, ragas_workers)
            )
            print("[ANSWER_ACCURACY] completed")
            for index, source in enumerate(source_batch):
                scores = {
                    metric.name: clean_value(frame.iloc[index].get(metric.name))
                    for metric in metrics
                }
                accuracy_value, accuracy_error = accuracy_results[index]
                scores[answer_accuracy.name] = accuracy_value
                missing_metrics = [name for name, value in scores.items() if value is None]
                status = "success" if not missing_metrics else "partial"
                append_jsonl(score_path, {
                    **source,
                    "ragas": scores,
                    "ragas_status": status,
                    "ragas_missing_metrics": missing_metrics,
                    "answer_accuracy_error": accuracy_error,
                    "judge_model": judge_model,
                    "judge_max_tokens": judge_max_tokens,
                    "embedding_model": embedding_model,
                })
                print(f"[SCORED:{status.upper()}] {source['id']} {scores}")
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
            for source in source_batch:
                append_jsonl(score_path, {
                    "id": source["id"],
                    "question": source.get("question"),
                    "question_type": source.get("question_type"),
                    "difficulty": source.get("difficulty"),
                    "ragas_status": "error",
                    "ragas_error": error,
                })
            print(f"[BATCH ERROR] {error}")


if __name__ == "__main__":
    main()
