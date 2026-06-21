#!/usr/bin/env bash
set -euo pipefail

profile="smoke"
dry_run=false
continue_on_error=false
gradle="./gradlew"
output_root=""
common_args=""
only_pattern=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/run-baseline-matrix.sh [options]

Options:
  --profile smoke|local|lab     Baseline profile to run. Default: smoke.
  --out DIR                     Suite output directory. Default: benchmark/build/benchmark-results/baseline-<profile>-<timestamp>.
  --dry-run                     Print Gradle commands and write a manifest without running benchmarks.
  --only PATTERN                Run only cases whose name contains PATTERN.
  --common-args "..."           Extra benchmark args appended to every case.
  --gradle ./gradlew            Gradle executable to use.
  --continue-on-error           Keep running remaining cases after a benchmark failure.
  --help                        Show this help.

Profiles:
  smoke  Short local regression baseline. Not line-rate evidence.
  local  Longer single-host baseline for local comparisons. Not line-rate evidence.
  lab    Recommended remote/lab-oriented baseline command set. Use controlled hosts/NICs.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="$2"
      shift 2
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --only)
      only_pattern="$2"
      shift 2
      ;;
    --common-args)
      common_args="$2"
      shift 2
      ;;
    --gradle)
      gradle="$2"
      shift 2
      ;;
    --continue-on-error)
      continue_on_error=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

case "$profile" in
  smoke|local|lab)
    ;;
  *)
    echo "Unknown profile: $profile" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/baseline-${profile}-${timestamp}"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi

mkdir -p "$output_root"
manifest="$output_root/manifest.jsonl"
report="$output_root/README.md"
suite_summary_jsonl="$output_root/suite-summary.jsonl"
suite_summary_csv="$output_root/suite-summary.csv"
: >"$manifest"
: >"$suite_summary_jsonl"
cat >"$suite_summary_csv" <<'CSV'
case,benchmark_name,iteration,clients,payload_size,reliability,batched,target_mbps,target_client_mbps,elapsed_ms,offered_gbps,delivered_gbps,delivered_msg_s,delivered_logical_packets_s,p95_ms,p99_ms,fairness,healthy_fairness,affected_clients,disconnects,stale_datagrams,nack_in,nack_out,max_queued_bytes,artifact
CSV

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

command_line() {
  printf '%q ' "$@"
}

write_report_header() {
  {
    echo "# RakNet Baseline Suite"
    echo
    echo "- Profile: \`$profile\`"
    echo "- Started: \`$timestamp\`"
    echo "- Output root: \`$output_root\`"
    echo "- Dry run: \`$dry_run\`"
    echo
    echo "| Case | Status | Artifact |"
    echo "| --- | --- | --- |"
  } >"$report"
}

append_report_row() {
  local name="$1"
  local status="$2"
  local artifact="$3"
  echo "| \`$name\` | \`$status\` | \`$artifact\` |" >>"$report"
}

record_manifest() {
  local name="$1"
  local status="$2"
  local args="$3"
  local command="$4"
  local artifact="$5"
  local started="$6"
  local ended="$7"

  printf '{"name":"%s","profile":"%s","status":"%s","started":"%s","ended":"%s","args":"%s","command":"%s","artifact":"%s"}\n' \
    "$(json_escape "$name")" \
    "$(json_escape "$profile")" \
    "$(json_escape "$status")" \
    "$(json_escape "$started")" \
    "$(json_escape "$ended")" \
    "$(json_escape "$args")" \
    "$(json_escape "$command")" \
    "$(json_escape "$artifact")" >>"$manifest"
}

append_case_metrics() {
  local name="$1"
  local artifact="$2"
  local summary="$artifact/summary.json"

  if [[ ! -f "$summary" ]]; then
    echo "No summary.json found for $name at $summary" >&2
    return 0
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq not found; skipping suite summary extraction for $name" >&2
    return 0
  fi

  jq -c --arg case "$name" --arg artifact "$artifact" '
    .iterations[] | {
      case: $case,
      benchmarkName: .name,
      iteration: .iteration,
      clients: .clients,
      payloadSize: .payloadSize,
      reliability: .reliability,
      batched: (.batched // false),
      targetMbps: .targetMbps,
      targetClientMbps: (.targetClientMbps // 0),
      elapsedMillis: .elapsedMillis,
      offeredGbps: .offeredGbps,
      deliveredGbps: .deliveredGbps,
      deliveredMessagesPerSecond: .deliveredMessagesPerSecond,
      deliveredLogicalPacketsPerSecond: (.deliveredLogicalPacketsPerSecond // 0),
      probeRttP95Millis: .probeRttP95Millis,
      probeRttP99Millis: .probeRttP99Millis,
      fairnessIndex: .fairnessIndex,
      healthyFairnessIndex: (.healthyFairnessIndex // 1),
      affectedClients: (.affectedClients // 0),
      disconnects: (.disconnects // 0),
      staleDatagrams: .staleDatagrams,
      nackIn: .nackIn,
      nackOut: .nackOut,
      maxQueuedBytes: .maxQueuedBytes,
      artifact: $artifact
    }
  ' "$summary" >>"$suite_summary_jsonl"

  jq -r --arg case "$name" --arg artifact "$artifact" '
    .iterations[] | [
      $case,
      .name,
      .iteration,
      .clients,
      .payloadSize,
      .reliability,
      (.batched // false),
      .targetMbps,
      (.targetClientMbps // 0),
      .elapsedMillis,
      .offeredGbps,
      .deliveredGbps,
      .deliveredMessagesPerSecond,
      (.deliveredLogicalPacketsPerSecond // 0),
      .probeRttP95Millis,
      .probeRttP99Millis,
      .fairnessIndex,
      (.healthyFairnessIndex // 1),
      (.affectedClients // 0),
      (.disconnects // 0),
      .staleDatagrams,
      .nackIn,
      .nackOut,
      .maxQueuedBytes,
      $artifact
    ] | @csv
  ' "$summary" >>"$suite_summary_csv"
}

case_list_smoke() {
  cat <<'CASES'
bestcase-1c-medium|baseline-bandwidth --clients 1 --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --rate-mbps 50 --workers 1
curve-1c-mtu|bandwidth-latency-curve --clients 1 --warmup 0ms --duration 1s --iterations 1 --payload-size 1200 --rates-mbps 50,100 --workers 1
fanout-10x0_2|multi-client-fanout --clients 10 --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --per-client-mbps 0.2 --workers 1
disappear-10-stopread|disappearing-clients --clients 10 --disappearing-clients 1 --disappear-after 500ms --disappear-mode stop-reading --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --per-client-mbps 0.2 --workers 1
batch-10-20ms|batched-game-traffic --clients 10 --warmup 0ms --duration 1s --iterations 1 --batch-interval 20ms --logical-packets-per-batch 4 --batch-payload-sizes 64,256 --batch-groups 2 --per-client-mbps 0.2 --workers 1
CASES
}

case_list_local() {
  cat <<'CASES'
bestcase-1c-small|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 64 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-medium|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 512 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-mtu|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,1000,unlimited
fanout-20x5|multi-client-fanout --clients 20 --warmup 2s --duration 10s --iterations 3 --payload-size 512 --per-client-mbps 5
fanout-100x5|multi-client-fanout --clients 100 --warmup 2s --duration 10s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-close|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode close --warmup 2s --duration 15s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-stopread|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode stop-reading --warmup 2s --duration 15s --iterations 3 --payload-size 512 --per-client-mbps 5
batch-100-20ms|batched-game-traffic --clients 100 --warmup 2s --duration 10s --iterations 3 --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
CASES
}

case_list_lab() {
  cat <<'CASES'
bestcase-1c-small|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 64 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-medium|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 512 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-mtu|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
fanout-100x5|multi-client-fanout --clients 100 --warmup 5s --duration 30s --iterations 3 --payload-size 512 --per-client-mbps 5
fanout-500x5|multi-client-fanout --clients 500 --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
fairness-100-10poor|fairness --clients 100 --impaired-clients 10 --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-close|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode close --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-stopread|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode stop-reading --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
batch-100-10ms|batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 10ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
batch-100-20ms|batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
batch-100-50ms|batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 50ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
CASES
}

run_case() {
  local name="$1"
  local args="$2"
  local run_args="$args --out $output_root --run-id $name"
  if [[ -n "$common_args" ]]; then
    run_args="$run_args $common_args"
  fi

  local artifact="$output_root/$name"
  local started
  local ended
  local status="passed"
  local command_text
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  command_text="$(command_line "$gradle" ":benchmark:raknetBenchmark" "-PbenchmarkArgs=$run_args")"

  echo
  echo "==> $name"
  echo "$command_text"

  if "$dry_run"; then
    status="dry-run"
  else
    if ! "$gradle" ":benchmark:raknetBenchmark" "-PbenchmarkArgs=$run_args"; then
      status="failed"
    fi
  fi

  ended="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  record_manifest "$name" "$status" "$run_args" "$command_text" "$artifact" "$started" "$ended"
  append_report_row "$name" "$status" "$artifact"
  if [[ "$status" == "passed" ]]; then
    append_case_metrics "$name" "$artifact"
  fi

  if [[ "$status" == "failed" && "$continue_on_error" == "false" ]]; then
    echo "Benchmark case failed: $name" >&2
    exit 1
  fi
}

write_report_header
cd "$repo_root"

case "$profile" in
  smoke)
    cases="$(case_list_smoke)"
    ;;
  local)
    cases="$(case_list_local)"
    ;;
  lab)
    cases="$(case_list_lab)"
    ;;
esac

selected=0
while IFS='|' read -r name args; do
  [[ -z "$name" ]] && continue
  if [[ -n "$only_pattern" && "$name" != *"$only_pattern"* ]]; then
    continue
  fi
  selected=$((selected + 1))
  run_case "$name" "$args"
done <<<"$cases"

if [[ "$selected" -eq 0 ]]; then
  echo "No cases selected for profile '$profile' and filter '$only_pattern'" >&2
  exit 2
fi

echo
echo "Baseline suite artifacts: $output_root"
echo "Manifest: $manifest"
echo "Suite summary JSONL: $suite_summary_jsonl"
echo "Suite summary CSV: $suite_summary_csv"
echo "Report: $report"
