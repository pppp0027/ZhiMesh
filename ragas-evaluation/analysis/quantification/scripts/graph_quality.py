from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
import statistics
from collections import Counter
from pathlib import Path
from typing import Any

from dotenv import load_dotenv


ROOT = Path(__file__).resolve().parents[3]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Read-only PostgreSQL/AGE graph quality audit")
    parser.add_argument("--kb-uuid", required=True)
    parser.add_argument("--graph-name", default="adi_knowledge_base_graph")
    parser.add_argument("--output-dir", default="analysis/quantification/results/runs/graph-quality")
    return parser.parse_args()


def normalize_name(value: str) -> str:
    return re.sub(r"\s+", "", value.strip().lower())


def ag_scalar(value: Any) -> Any:
    if value is None:
        return None
    text = str(value)
    try:
        return json.loads(text)
    except (TypeError, ValueError):
        return text.strip('"')


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    args = parse_args()
    if not re.fullmatch(r"[0-9a-fA-F]{32}", args.kb_uuid):
        raise SystemExit("--kb-uuid must be a 32-character hexadecimal UUID")
    if not re.fullmatch(r"[A-Za-z0-9_]+", args.graph_name):
        raise SystemExit("--graph-name may contain only letters, numbers and underscores")
    load_dotenv(ROOT / ".env")
    dsn = os.getenv("ZHIMESH_DB_DSN", "").strip()
    if not dsn:
        raise SystemExit("ZHIMESH_DB_DSN is missing in .env")
    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit(
            "psycopg is missing; run .\\.venv\\Scripts\\python.exe -m pip install \"psycopg[binary]>=3.1,<4\""
        ) from exc

    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = ROOT / output_dir

    with psycopg.connect(dsn, autocommit=True) as connection:
        with connection.cursor() as cursor:
            cursor.execute("LOAD 'age'")
            cursor.execute('SET search_path = ag_catalog, "$user", public')

            kb = args.kb_uuid
            graph = args.graph_name
            cursor.execute(
                f"""
                SELECT * FROM cypher('{graph}', $$
                  MATCH (n)
                  WHERE n.metadata.kb_uuid = '{kb}'
                  OPTIONAL MATCH (n)-[r]-()
                  RETURN id(n), n.name, count(r)
                $$) AS (element_id agtype, name agtype, degree agtype)
                """
            )
            nodes = [
                {
                    "element_id": str(ag_scalar(row[0])),
                    "name": str(ag_scalar(row[1]) or ""),
                    "degree": int(ag_scalar(row[2]) or 0),
                }
                for row in cursor.fetchall()
            ]
            cursor.execute(
                f"""
                SELECT * FROM cypher('{graph}', $$
                  MATCH ()-[r]->()
                  WHERE r.metadata.kb_uuid = '{kb}'
                  RETURN id(r)
                $$) AS (element_id agtype)
                """
            )
            edges = [{"element_id": str(ag_scalar(row[0]))} for row in cursor.fetchall()]

            cursor.execute(
                """
                SELECT count(*),
                       count(*) FILTER (WHERE embedding_status = 3),
                       count(*) FILTER (WHERE graphical_status = 3),
                       count(*) FILTER (WHERE graphical_status = 4)
                FROM adi_knowledge_base_item
                WHERE kb_uuid = %s AND is_deleted = false
                """,
                (kb,),
            )
            document_count, embedded_docs, graphed_docs, failed_graph_docs = cursor.fetchone()

            cursor.execute(
                """
                SELECT count(*), min(token_count), avg(token_count),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY token_count),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY token_count),
                       max(token_count)
                FROM adi_knowledge_base_chunk
                WHERE kb_uuid = %s AND is_deleted = false
                """,
                (kb,),
            )
            canonical_chunk_stats = cursor.fetchone()
            cursor.execute(
                """
                SELECT count(*)
                FROM adi_knowledge_base_graph_segment
                WHERE kb_uuid = %s AND is_deleted = false
                """,
                (kb,),
            )
            graph_segment_count = cursor.fetchone()[0]

            cursor.execute(
                """
                SELECT element_type, element_id, graph_segment_uuid
                FROM adi_knowledge_base_graph_element_source
                WHERE kb_uuid = %s AND is_deleted = false
                """,
                (kb,),
            )
            sources = [
                {
                    "element_type": row[0],
                    "element_id": str(row[1]),
                    "graph_segment_uuid": row[2],
                }
                for row in cursor.fetchall()
            ]
            cursor.execute(
                """
                SELECT count(*)
                FROM adi_knowledge_base_graph_element_source s
                LEFT JOIN adi_knowledge_base_graph_segment g
                  ON g.uuid = s.graph_segment_uuid
                 AND g.is_deleted = false
                 AND g.kb_uuid = s.kb_uuid
                LEFT JOIN adi_knowledge_base_item i
                  ON i.uuid = s.kb_item_uuid
                 AND i.is_deleted = false
                 AND i.kb_uuid = s.kb_uuid
                WHERE s.kb_uuid = %s
                  AND s.is_deleted = false
                  AND (g.uuid IS NULL OR i.uuid IS NULL)
                """,
                (kb,),
            )
            invalid_source_count = cursor.fetchone()[0]

    vertex_sources = {
        row["element_id"] for row in sources if row["element_type"] == "vertex"
    }
    edge_sources = {row["element_id"] for row in sources if row["element_type"] == "edge"}
    node_ids = {row["element_id"] for row in nodes}
    edge_ids = {row["element_id"] for row in edges}
    isolated = [row for row in nodes if row["degree"] == 0]
    normalized = Counter(
        normalize_name(row["name"]) for row in nodes if normalize_name(row["name"])
    )
    duplicates = [
        {"normalized_name": name, "count": count}
        for name, count in normalized.most_common()
        if count > 1
    ]
    duplicate_rows = sum(count - 1 for count in normalized.values() if count > 1)
    total_sources = len(sources)

    summary = {
        "kb_uuid": args.kb_uuid,
        "graph_name": args.graph_name,
        "document_count": document_count,
        "embedded_document_count": embedded_docs,
        "graphed_document_count": graphed_docs,
        "failed_graph_document_count": failed_graph_docs,
        "canonical_chunk_count": canonical_chunk_stats[0],
        "canonical_chunk_token_min": canonical_chunk_stats[1],
        "canonical_chunk_token_mean": float(canonical_chunk_stats[2])
        if canonical_chunk_stats[2] is not None
        else None,
        "canonical_chunk_token_p50": float(canonical_chunk_stats[3])
        if canonical_chunk_stats[3] is not None
        else None,
        "canonical_chunk_token_p95": float(canonical_chunk_stats[4])
        if canonical_chunk_stats[4] is not None
        else None,
        "canonical_chunk_token_max": canonical_chunk_stats[5],
        "graph_segment_count": graph_segment_count,
        "entity_count": len(nodes),
        "relation_count": len(edges),
        "entities_per_graphed_document": len(nodes) / graphed_docs if graphed_docs else None,
        "relations_per_graphed_document": len(edges) / graphed_docs if graphed_docs else None,
        "average_node_degree": statistics.fmean(row["degree"] for row in nodes)
        if nodes
        else None,
        "duplicate_entity_rows": duplicate_rows,
        "duplicate_entity_rate": duplicate_rows / len(nodes) if nodes else None,
        "isolated_entity_count": len(isolated),
        "isolated_entity_rate": len(isolated) / len(nodes) if nodes else None,
        "vertex_source_coverage_rate": len(node_ids & vertex_sources) / len(node_ids)
        if node_ids
        else None,
        "edge_source_coverage_rate": len(edge_ids & edge_sources) / len(edge_ids)
        if edge_ids
        else None,
        "source_record_count": total_sources,
        "invalid_source_count": invalid_source_count,
        "invalid_source_rate": invalid_source_count / total_sources
        if total_sources
        else None,
        "vertices_without_source": len(node_ids - vertex_sources),
        "edges_without_source": len(edge_ids - edge_sources),
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "graph_quality_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    write_csv(
        output_dir / "graph_duplicate_entities.csv",
        ["normalized_name", "count"],
        duplicates,
    )
    write_csv(
        output_dir / "graph_isolated_entities.csv",
        ["element_id", "name", "degree"],
        isolated,
    )
    write_csv(
        output_dir / "graph_elements_without_source.csv",
        ["element_type", "element_id"],
        [
            *[
                {"element_type": "vertex", "element_id": value}
                for value in sorted(node_ids - vertex_sources)
            ],
            *[
                {"element_type": "edge", "element_id": value}
                for value in sorted(edge_ids - edge_sources)
            ],
        ],
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"output={output_dir}")


if __name__ == "__main__":
    main()
