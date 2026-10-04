#!/usr/bin/env bash
# C2(mock 平台容量) → C3(真实网关端到端) 串联驱动。日志: /tmp/c2c3_driver.log
set -uo pipefail
cd /opt/zhimesh/ragas-evaluation

COMMON_ENV=(LADDER_BASE_URL=http://127.0.0.1:9999 LADDER_LEVELS="1 5 10 20 50" LADDER_ROUNDS=3 LADDER_REQUESTS=100)

echo "[driver] C2 (mock) start $(date '+%F %T')"
env "${COMMON_ENV[@]}" LADDER_MODEL_PLATFORM=mock LADDER_MODEL_NAME=mock-chat \
    LADDER_OUTPUT_ROOT=analysis/quantification/results/runs/ladder-c2-mock \
    bash analysis/quantification/run_ladder.sh
echo "[driver] C2 done rc=$? $(date '+%F %T')"

sleep 120
echo "[driver] C3 (opencode) start $(date '+%F %T')"
env "${COMMON_ENV[@]}" LADDER_MODEL_PLATFORM=opencode LADDER_MODEL_NAME=deepseek-v4.1-flash \
    LADDER_OUTPUT_ROOT=analysis/quantification/results/runs/ladder-c3-e2e \
    bash analysis/quantification/run_ladder.sh
echo "[driver] C3 done rc=$? $(date '+%F %T')"
