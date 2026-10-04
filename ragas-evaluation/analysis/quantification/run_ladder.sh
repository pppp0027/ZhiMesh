#!/usr/bin/env bash
# ZhiMesh SSE 并发阶梯压测编排器（L0 预检 → L1 金丝雀 → L3 交错阶梯 → 验证归档）
#
# 与旧 matrix 模式的区别：
#   1. 金丝雀 + DB 三路引用断言（防"静默无 RAG"假数据）
#   2. kbCount:0 日志绊线（filteredKb 被权限剔除即报警）
#   3. 实际服务模型分布验证（防健康回退悄悄换模型）
#   4. 交错多轮（c1→c5→c10→c1→... 均摊时段负载漂移）
#   5. docker stats 联动采样 + manifest 产物溯源
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PY="${RAGAS_VENV_PYTHON:-${PROJECT_DIR}/.venv/bin/python}"
CONFIG="${LADDER_CONFIG:-analysis/quantification/configs/quantification-config.server.example.json}"
ACCOUNTS="${LADDER_ACCOUNTS:-analysis/quantification/configs/concurrency-accounts.server.json}"
DATASET="${LADDER_DATASET:-datasets/ragas_eval_dataset_merged_v1.jsonl}"
BASE_URL="${LADDER_BASE_URL:-http://127.0.0.1:9999}"
MODEL_ARGS=()
[[ -n "${LADDER_MODEL_PLATFORM:-}" ]] && MODEL_ARGS+=(--model-platform "${LADDER_MODEL_PLATFORM}")
[[ -n "${LADDER_MODEL_NAME:-}" ]] && MODEL_ARGS+=(--model-name "${LADDER_MODEL_NAME}")
LEVELS=(${LADDER_LEVELS:-1 5 10 20 50})
ROUNDS="${LADDER_ROUNDS:-3}"
REQUESTS="${LADDER_REQUESTS:-100}"
SAMPLES="${LADDER_SAMPLES:-100}"
COOLDOWN="${LADDER_COOLDOWN:-60}"
CANARY_N="${LADDER_CANARY_N:-3}"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_ROOT="${LADDER_OUTPUT_ROOT:-analysis/quantification/results/runs/ladder-${RUN_ID}}"
PG="docker exec pgvector psql -U root -d ai_rag_knowledge -t -A"
API_CONTAINER="${LADDER_API_CONTAINER:-$(docker ps --format '{{.Names}}' | grep -E 'zhimesh-api' | head -1)}"
[[ -n "${API_CONTAINER}" ]] || { echo "zhimesh-api container not found" >&2; exit 1; }

cd "${PROJECT_DIR}"
mkdir -p "${OUT_ROOT}"

log() { printf '\033[1;36m[ladder]\033[0m %s\n' "$*" | tee -a "${OUT_ROOT}/ladder.log"; }
fail() { printf '\033[1;31m[ladder][FAIL]\033[0m %s\n' "$*" | tee -a "${OUT_ROOT}/ladder.log" >&2; exit 1; }

# ---------- L0 环境预检 ----------
log "L0 preflight: base_url=${BASE_URL} dataset=${DATASET} levels=[${LEVELS[*]}] rounds=${ROUNDS}"
[[ -f "${DATASET}" ]] || fail "dataset not found: ${DATASET}"
N_ACCOUNTS=$(${PY} -c "import json;print(len(json.load(open('${ACCOUNTS}'))['accounts']))")
MAX_LEVEL=$(printf '%s\n' "${LEVELS[@]}" | sort -n | tail -1)
[[ "${N_ACCOUNTS}" -ge "${MAX_LEVEL}" ]] || fail "need ${MAX_LEVEL} accounts, have ${N_ACCOUNTS} (run scale_accounts.py)"
BM25=$(${PG} -c "SELECT count(*) FROM adi_knowledge_base_index_build WHERE kb_id=3 AND index_type='FULLTEXT' AND status='ACTIVE' AND is_active=true;")
KB3=$(${PG} -c "SELECT owner_type FROM adi_knowledge_base WHERE id=3;")
log "L0: accounts=${N_ACCOUNTS} kb3_owner_type=${KB3} bm25_active_builds=${BM25}"
[[ "${KB3}" != "PERSONAL" ]] || fail "KB3 is PERSONAL — benchmark accounts cannot read it; flip to COMPANY/STAFF first"

# ---------- 资源采样（贯穿全程） ----------
( while true; do
    printf '%s,' "$(date +%s)"
    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' 2>/dev/null | grep -E 'zhimesh-api|pgvector|redis' | tr '\n' ';'
    echo
    sleep 2
  done ) > "${OUT_ROOT}/stats.log" &
STATS_PID=$!
trap 'kill ${STATS_PID} 2>/dev/null || true' EXIT
LADDER_START=$(${PG} -c "SELECT to_char(now(),'YYYY-MM-DD\"T\"HH24:MI:SS');")  # DB 跑 UTC，窗口一律用 DB 时钟
log "stats sampler pid=${STATS_PID} run_start=${LADDER_START}"

# ---------- L1 金丝雀：固定题 + DB 三路引用断言 ----------
# ref 表无时间列且消息会被清理 → 用三表 max(id) 快照 + app.log 行号快照划窗口
REF_EMB_MAX=$(${PG} -c "SELECT coalesce(max(id),0) FROM adi_character_message_ref_embedding;")
REF_BM25_MAX=$(${PG} -c "SELECT coalesce(max(id),0) FROM adi_character_message_ref_bm25;")
REF_GRAPH_MAX=$(${PG} -c "SELECT coalesce(max(id),0) FROM adi_character_message_ref_graph;")
APP_LOG="/opt/zhimesh/logs/app.log"   # 应用日志在挂载卷，docker logs 只有启动噪声
APP_LOG_T0=$(wc -l < "${APP_LOG}" 2>/dev/null || echo 0)
CANARY_FILE="${OUT_ROOT}/canary_ids.txt"
${PY} - "$DATASET" "$CANARY_FILE" "$CANARY_N" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
facts = [r for r in rows if r.get("question_type") == "single_document_fact"]
ids = [str(r["id"]) for r in facts[: int(sys.argv[3])]]
assert len(ids) == int(sys.argv[3]), f"not enough single_document_fact rows: {len(ids)}"
open(sys.argv[2], "w").write("\n".join(ids) + "\n")
EOF
log "L1 canary ids: $(tr '\n' ' ' < "${CANARY_FILE}")"
CANARY_T0=$(${PG} -c "SELECT to_char(now(),'YYYY-MM-DD\"T\"HH24:MI:SS');")
"${PY}" analysis/quantification/scripts/sse_benchmark.py \
    --config "${CONFIG}" --accounts-file "${ACCOUNTS}" --mode serial \
    --base-url "${BASE_URL}" --ids-file "${CANARY_FILE}" --rounds 1 --keep-conversations \
    "${MODEL_ARGS[@]}" --output-dir "${OUT_ROOT}/canary" >> "${OUT_ROOT}/ladder.log" 2>&1 \
    || fail "canary requests failed"
sleep 3
REF_EMB=$(${PG} -c "SELECT count(*) FROM adi_character_message_ref_embedding WHERE id > ${REF_EMB_MAX};")
REF_BM25=$(${PG} -c "SELECT count(*) FROM adi_character_message_ref_bm25 WHERE id > ${REF_BM25_MAX};")
REF_GRAPH=$(${PG} -c "SELECT count(*) FROM adi_character_message_ref_graph WHERE id > ${REF_GRAPH_MAX};")
log "L1 refs since canary (id snapshot): embedding=${REF_EMB} bm25=${REF_BM25} graph=${REF_GRAPH}"
[[ "${REF_EMB}" -ge "${CANARY_N}" ]] || fail "vector route left no traces — RAG silently off?"
[[ "${REF_BM25}" -ge "${CANARY_N}" ]] || fail "bm25 route left no traces — route silently dropped?"
[[ "${REF_GRAPH}" -ge 1 ]] || log "WARN: graph route left no traces (49% healthy-empty is known; check route config)"
KBC0=$(tail -n +$((APP_LOG_T0 + 1)) "${APP_LOG}" 2>/dev/null | grep -c "kbCount:0" || true)
[[ "${KBC0}" -eq 0 ]] || fail "kbCount:0 appeared ${KBC0} times — filteredKb empty, RAG off for some requests"
log "L1 canary PASSED"

# ---------- L3 交错阶梯 ----------
run_case() {
    local level="$1" round="$2"
    local dir="${OUT_ROOT}/c${level}-r${round}"
    log "RUN c${level} r${round} -> ${dir}"
    if [[ "${level}" -eq 1 ]]; then
        "${PY}" analysis/quantification/scripts/sse_benchmark.py \
            --config "${CONFIG}" --accounts-file "${ACCOUNTS}" --mode serial \
            --base-url "${BASE_URL}" --sample-count "${SAMPLES}" --rounds 1 --keep-conversations \
            "${MODEL_ARGS[@]}" --output-dir "${dir}" >> "${OUT_ROOT}/ladder.log" 2>&1 \
            || fail "case c${level}-r${round} (serial) failed — see ${OUT_ROOT}/ladder.log"
    else
        "${PY}" analysis/quantification/scripts/sse_benchmark.py \
            --config "${CONFIG}" --accounts-file "${ACCOUNTS}" --mode concurrency \
            --base-url "${BASE_URL}" --concurrency "${level}" --requests "${REQUESTS}" \
            --sample-count "${SAMPLES}" --keep-conversations \
            "${MODEL_ARGS[@]}" --output-dir "${dir}" >> "${OUT_ROOT}/ladder.log" 2>&1 \
            || fail "case c${level}-r${round} (concurrency) failed — see ${OUT_ROOT}/ladder.log"
    fi
}
for round in $(seq 1 "${ROUNDS}"); do
    for level in "${LEVELS[@]}"; do
        run_case "${level}" "${round}"
        sleep "${COOLDOWN}"
    done
done

# ---------- 事后验证 ----------
LADDER_END=$(${PG} -c "SELECT to_char(now(),'YYYY-MM-DD\"T\"HH24:MI:SS');")
MODEL_DIST=$(${PG} -c "SELECT m.ai_model_id, coalesce(am.name,'?'), count(*) FROM adi_character_message m LEFT JOIN adi_ai_model am ON am.id=m.ai_model_id WHERE m.create_time > '${LADDER_START}' GROUP BY 1,2 ORDER BY 3 DESC;")
log "served model distribution since ${LADDER_START}:"$'\n'"${MODEL_DIST}"
KBC_ALL=$(tail -n +$((APP_LOG_T0 + 1)) "${APP_LOG}" 2>/dev/null | grep -c "kbCount:0" || true)
[[ "${KBC_ALL}" -eq 0 ]] || log "WARN: kbCount:0 total=${KBC_ALL} during ladder — inspect ${OUT_ROOT}/api_during_run.log"
tail -n +$((APP_LOG_T0 + 1)) "${APP_LOG}" > "${OUT_ROOT}/api_during_run.log" 2>/dev/null || true
kill "${STATS_PID}" 2>/dev/null || true

# ---------- manifest ----------
cat > "${OUT_ROOT}/manifest.json" <<EOF
{
  "run_id": "${RUN_ID}",
  "started": "${LADDER_START}",
  "ended": "${LADDER_END}",
  "base_url": "${BASE_URL}",
  "dataset": "${DATASET}",
  "dataset_md5": "$(md5sum "${DATASET}" | cut -d' ' -f1)",
  "levels": "${LEVELS[*]}",
  "rounds": ${ROUNDS},
  "requests_per_level": ${REQUESTS},
  "model_platform": "${LADDER_MODEL_PLATFORM:-default}",
  "model_name": "${LADDER_MODEL_NAME:-default}",
  "canary_refs": {"embedding": ${REF_EMB}, "bm25": ${REF_BM25}, "graph": ${REF_GRAPH}},
  "served_model_distribution": $(echo "${MODEL_DIST}" | ${PY} -c "import sys;print(repr(sys.stdin.read()))"),
  "kb_count_zero_hits": ${KBC_ALL:-0},
  "sse_benchmark_md5": "$(md5sum analysis/quantification/scripts/sse_benchmark.py | cut -d' ' -f1)"
}
EOF
log "DONE. artifacts: ${OUT_ROOT}"
