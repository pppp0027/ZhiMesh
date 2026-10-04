#!/usr/bin/env bash
# 补完 C3 缺失的 r3 轮（c10/c20/c50）+ 事后验证 + manifest。
# 背景：原 C3 因 c10-r3 预热撞上网关瞬时抖动中止，前 12 个 case 数据完好。
set -uo pipefail
cd /opt/zhimesh/ragas-evaluation

OUT_ROOT=analysis/quantification/results/runs/ladder-c3-e2e
PY=.venv/bin/python
CONFIG=analysis/quantification/configs/quantification-config.server.example.json
ACCOUNTS=analysis/quantification/configs/concurrency-accounts.server.json
MODEL_ARGS=(--model-platform opencode --model-name deepseek-v4.1-flash)
PG="docker exec pgvector psql -U root -d ai_rag_knowledge -t -A"

log() { printf '\033[1;36m[c3-fix]\033[0m %s\n' "$*" | tee -a "${OUT_ROOT}/ladder.log"; }
fail() { printf '\033[1;31m[c3-fix][FAIL]\033[0m %s\n' "$*" | tee -a "${OUT_ROOT}/ladder.log" >&2; exit 1; }

# 资源采样（仅补跑期间）
( while true; do
    printf '%s,' "$(date +%s)"
    docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' 2>/dev/null | grep -E 'zhimesh-api|pgvector|redis' | tr '\n' ';'
    echo
    sleep 2
  done ) > "${OUT_ROOT}/stats-fix.log" &
STATS_PID=$!
trap 'kill ${STATS_PID} 2>/dev/null || true' EXIT

for level in 10 20 50; do
    dir="${OUT_ROOT}/c${level}-r3"
    log "FIX-RUN c${level} r3 -> ${dir}"
    ${PY} analysis/quantification/scripts/sse_benchmark.py \
        --config "${CONFIG}" --accounts-file "${ACCOUNTS}" --mode concurrency \
        --base-url http://127.0.0.1:9999 --concurrency "${level}" --requests 100 \
        --sample-count 100 --keep-conversations \
        "${MODEL_ARGS[@]}" --output-dir "${dir}" >> "${OUT_ROOT}/ladder.log" 2>&1 \
        || fail "fix case c${level}-r3 failed"
    sleep 60
done

# ---------- 事后验证（覆盖原 C3 全窗口，含补跑） ----------
LADDER_START=$(grep -o "run_start=[0-9T:-]*" "${OUT_ROOT}/ladder.log" | head -1 | cut -d= -f2)
MODEL_DIST=$(${PG} -c "SELECT m.ai_model_id, coalesce(am.name,'?'), count(*) FROM adi_character_message m LEFT JOIN adi_ai_model am ON am.id=m.ai_model_id WHERE m.create_time > '${LADDER_START}' GROUP BY 1,2 ORDER BY 3 DESC;")
log "served model distribution since ${LADDER_START}:"$'\n'"${MODEL_DIST}"
KBC_ALL=$(grep -c "kbCount:0" /opt/zhimesh/logs/app.log || true)
[[ "${KBC_ALL}" -eq 0 ]] || log "WARN: kbCount:0 in whole app.log = ${KBC_ALL} (含历史，人工核对时间窗)"

cat > "${OUT_ROOT}/manifest.json" <<EOF
{
  "run_id": "c3-e2e-plus-fix",
  "started": "${LADDER_START}",
  "ended": "$(${PG} -c "SELECT to_char(now(),'YYYY-MM-DD\"T\"HH24:MI:SS');")",
  "base_url": "http://127.0.0.1:9999",
  "model_platform": "opencode",
  "model_name": "deepseek-v4.1-flash",
  "levels": "1 5 10 20 50",
  "rounds": 3,
  "requests_per_level": 100,
  "note": "r3 轮 c10/c20/c50 为 21:58 后补跑（原跑 21:27 因预热瞬时网关抖动中止）；其余 12 case 为 20:04-21:27 原始数据",
  "served_model_distribution": $(echo "${MODEL_DIST}" | ${PY} -c "import sys;print(repr(sys.stdin.read()))"),
  "kb_count_zero_hits_wholeday": ${KBC_ALL:-0},
  "sse_benchmark_md5": "$(md5sum analysis/quantification/scripts/sse_benchmark.py | cut -d' ' -f1)"
}
EOF
log "C3 COMPLETE (with fix). artifacts: ${OUT_ROOT}"
