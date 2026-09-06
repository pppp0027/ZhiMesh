#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
VENV_DIR="${RAGAS_VENV_DIR:-${PROJECT_DIR}/.venv}"
CONFIG="${RAGAS_BENCHMARK_CONFIG:-analysis/quantification/configs/quantification-config.server.example.json}"
ACCOUNTS_FILE="${ZHIMESH_BENCHMARK_ACCOUNTS_FILE:-analysis/quantification/configs/concurrency-accounts.server.json}"
MODE="${1:-check}"
MATRIX_OUTPUT_ROOT="${SSE_MATRIX_OUTPUT_ROOT:-analysis/quantification/results/runs/performance-matrix-$(date +%Y%m%d-%H%M%S)}"
ROUND_COOLDOWN_SECONDS="${SSE_ROUND_COOLDOWN_SECONDS:-60}"

if [[ $# -gt 0 ]]; then
  shift
fi

cd "${PROJECT_DIR}"

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  "${PYTHON_BIN}" -m venv "${VENV_DIR}"
fi

if ! "${VENV_DIR}/bin/python" -c "import httpx, dotenv" >/dev/null 2>&1; then
  "${VENV_DIR}/bin/python" -m pip install -r requirements-benchmark.txt
fi

case "${MODE}" in
  check)
    if [[ -f "${ACCOUNTS_FILE}" ]]; then
      exec "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
        --config "${CONFIG}" --accounts-file "${ACCOUNTS_FILE}" --check-only "$@"
    fi
    exec "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
      --config "${CONFIG}" --check-only "$@"
    ;;
  serial)
    exec "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
      --config "${CONFIG}" --mode serial \
      --sample-count "${SSE_SAMPLE_COUNT:-100}" --rounds "${SSE_ROUNDS:-1}" "$@"
    ;;
  concurrency)
    exec "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
      --config "${CONFIG}" --mode concurrency --accounts-file "${ACCOUNTS_FILE}" \
      --concurrency "${SSE_CONCURRENCY:-10}" --requests "${SSE_REQUESTS:-100}" "$@"
    ;;
  matrix)
    if [[ ! -f "${ACCOUNTS_FILE}" ]]; then
      echo "Accounts file not found: ${ACCOUNTS_FILE}" >&2
      exit 2
    fi

    run_case() {
      local concurrency="$1"
      local round="$2"
      shift 2
      local output_dir="${MATRIX_OUTPUT_ROOT}/c${concurrency}-r${round}"
      if [[ "${concurrency}" == "1" ]]; then
        "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
          --config "${CONFIG}" --accounts-file "${ACCOUNTS_FILE}" --mode serial \
          --sample-count 100 --rounds 1 --output-dir "${output_dir}" "$@"
      else
        "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
          --config "${CONFIG}" --accounts-file "${ACCOUNTS_FILE}" --mode concurrency \
          --sample-count 100 --concurrency "${concurrency}" --requests 100 \
          --output-dir "${output_dir}" "$@"
      fi
    }

    run_case 1 1 "$@"
    sleep "${ROUND_COOLDOWN_SECONDS}"
    run_case 5 1 "$@"
    sleep "${ROUND_COOLDOWN_SECONDS}"
    run_case 10 1 "$@"
    echo "matrix_output=${MATRIX_OUTPUT_ROOT}"
    ;;
  interrupt)
    exec "${VENV_DIR}/bin/python" analysis/quantification/scripts/sse_benchmark.py \
      --config "${CONFIG}" --mode interrupt --requests "${SSE_REQUESTS:-10}" "$@"
    ;;
  *)
    echo "Usage: $0 {check|serial|concurrency|matrix|interrupt} [extra sse_benchmark.py arguments]" >&2
    exit 2
    ;;
esac
