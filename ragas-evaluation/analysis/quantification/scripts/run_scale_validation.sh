#!/usr/bin/env bash
# 扩容验证跑：chatExecutor 28→76 后，验证 c50 是否全接纳、下一个瓶颈在哪。
# S1 = mock 轨 c20/c50 × 3 轮（不烧网关配额，隔离平台）
# S2 = 真实网关 c50 × 1 轮（验证真实链路在满接纳下的表现）
# 前置：compose 已注入 ZHIMESH_CHAT_CORE=8/MAX=16/QUEUE=60 并重启生效；KB3 已翻 COMPANY。
set -uo pipefail
cd /opt/zhimesh/ragas-evaluation

BASE=analysis/quantification/results/runs
PG="docker exec pgvector psql -U root -d ai_rag_knowledge -t -A"
APP_LOG="/opt/zhimesh/logs/app.log"

log() { printf '\033[1;36m[scale-val]\033[0m %s\n' "$*" | tee -a "${BASE}/scale_validation.log"; }
fail() { printf '\033[1;31m[scale-val][FAIL]\033[0m %s\n' "$*" | tee -a "${BASE}/scale_validation.log" >&2; exit 1; }

# ---------- L0：确认扩容已生效 ----------
EXEC_LINE=$(grep -o "Configuring executor chat- core:[0-9]*, max:[0-9]*, queue:[0-9]*" "${APP_LOG}" | tail -1)
log "effective chat executor: ${EXEC_LINE}"
echo "${EXEC_LINE}" | grep -q "core:8, max:16, queue:60" || fail "chatExecutor 未生效为 8/16/60 — 先改 compose 再跑"
KB3=$(${PG} -c "SELECT owner_type FROM adi_knowledge_base WHERE id=3;")
[[ "${KB3}" != "PERSONAL" ]] || fail "KB3 是 PERSONAL — 压测账号读不到，先翻 COMPANY"

# ---------- S1：mock 轨 ----------
S1_START=$(date '+%F %T')
log "S1 mock c20/c50 x3 start ${S1_START}"
LADDER_LEVELS="20 50" LADDER_ROUNDS=3 LADDER_REQUESTS=100 \
LADDER_OUTPUT_ROOT="${BASE}/ladder-scale-mock" \
LADDER_MODEL_PLATFORM=mock LADDER_MODEL_NAME=mock-chat \
bash analysis/quantification/run_ladder.sh >> "${BASE}/scale_validation.log" 2>&1 \
  || fail "S1 mock stage failed"
log "S1 mock done $(date '+%F %T')"

sleep 120

# ---------- S2：真实网关 c50 x1 ----------
S2_START=$(date '+%F %T')
log "S2 real c50 x1 start ${S2_START}"
LADDER_LEVELS="50" LADDER_ROUNDS=1 LADDER_REQUESTS=100 \
LADDER_OUTPUT_ROOT="${BASE}/ladder-scale-real" \
LADDER_MODEL_PLATFORM=opencode LADDER_MODEL_NAME=deepseek-v4.1-flash \
bash analysis/quantification/run_ladder.sh >> "${BASE}/scale_validation.log" 2>&1 \
  || fail "S2 real stage failed"
log "S2 real done $(date '+%F %T')"

# ---------- 绊线汇总：下一个瓶颈证据 ----------
summarize() {
    local root="$1" label="$2"
    local api_log="${root}/api_during_run.log"
    [[ -f "${api_log}" ]] || { log "${label}: no api log"; return; }
    {
        echo "== ${label} =="
        echo "Chat task rejected (硬拒，验证应为0): $(grep -c 'Chat task rejected' "${api_log}" || true)"
        echo "Retrieve content error (检索全空软降级): $(grep -c 'Retrieve content error' "${api_log}" || true)"
        echo "retrieval executor saturated (路由槽饱和): $(grep -c 'bounded executor is saturated' "${api_log}" || true)"
        echo "Hikari Connection is not available (DB池耗尽): $(grep -c 'Connection is not available' "${api_log}" || true)"
        echo "connection refused/reset: $(grep -ci 'connection refused\|connection reset' "${api_log}" || true)"
        echo "kbCount:0: $(grep -c 'kbCount:0' "${api_log}" || true)"
    } | tee -a "${BASE}/scale_validation.log"
}
summarize "${BASE}/ladder-scale-mock" "S1 mock"
summarize "${BASE}/ladder-scale-real" "S2 real"

# 真实窗口模型分布（app 北京墙钟）
for w in "S1 mock:${S1_START}" ; do :; done
MODEL_DIST_S1=$(${PG} -c "SELECT m.ai_model_id, coalesce(am.name,'?'), count(*) FROM adi_character_message m LEFT JOIN adi_ai_model am ON am.id=m.ai_model_id WHERE m.create_time >= '${S1_START}' AND m.create_time < '${S2_START}' GROUP BY 1,2 ORDER BY 3 DESC;")
log "S1 window model dist:"$'\n'"${MODEL_DIST_S1}"
MODEL_DIST_S2=$(${PG} -c "SELECT m.ai_model_id, coalesce(am.name,'?'), count(*) FROM adi_character_message m LEFT JOIN adi_ai_model am ON am.id=m.ai_model_id WHERE m.create_time >= '${S2_START}' GROUP BY 1,2 ORDER BY 3 DESC;")
log "S2 window model dist (含跑后至今，人工核对窗口):"$'\n'"${MODEL_DIST_S2}"
log "ALL DONE"
