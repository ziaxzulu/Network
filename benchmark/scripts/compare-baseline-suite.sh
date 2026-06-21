#!/usr/bin/env bash
set -euo pipefail

baseline_path=""
candidate_path=""
report_path=""
jsonl_path=""
throughput_regression_pct="10"
latency_regression_pct="10"
queue_regression_pct="50"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/compare-baseline-suite.sh --baseline DIR|suite-aggregate.jsonl|suite-summary.jsonl --candidate DIR|suite-aggregate.jsonl|suite-summary.jsonl [options]

Options:
  --baseline PATH                 Baseline suite directory, aggregate JSONL, or per-iteration summary JSONL.
  --candidate PATH                Candidate suite directory, aggregate JSONL, or per-iteration summary JSONL.
  --out FILE                      Markdown report path. Default: stdout.
  --jsonl FILE                    Raw comparison JSONL path. Default: next to --out, or temporary for stdout.
  --throughput-regression-pct N   Fail when delivered Gbps falls by more than N percent. Default: 10.
  --latency-regression-pct N      Fail when p99 probe RTT rises by more than N percent. Default: 10.
  --queue-regression-pct N        Fail when max queued bytes rises by more than N percent. Default: 50.
  --help                          Show this help.

Suite directories prefer suite-aggregate.jsonl when present, falling back to
suite-summary.jsonl. Aggregate rows are matched by case name and benchmark
scenario. Per-iteration rows are matched by case name, scenario, and iteration
number. The script exits non-zero when a candidate row is missing or a matched
row breaches one of the configured regression thresholds. Extra candidate rows
are reported as informational rows and do not fail the comparison.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline)
      baseline_path="$2"
      shift 2
      ;;
    --candidate)
      candidate_path="$2"
      shift 2
      ;;
    --out)
      report_path="$2"
      shift 2
      ;;
    --jsonl)
      jsonl_path="$2"
      shift 2
      ;;
    --throughput-regression-pct)
      throughput_regression_pct="$2"
      shift 2
      ;;
    --latency-regression-pct)
      latency_regression_pct="$2"
      shift 2
      ;;
    --queue-regression-pct)
      queue_regression_pct="$2"
      shift 2
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

if [[ -z "$baseline_path" || -z "$candidate_path" ]]; then
  usage >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to compare baseline suite summaries" >&2
  exit 2
fi

resolve_summary() {
  local path="$1"
  if [[ -d "$path" ]]; then
    if [[ -s "$path/suite-aggregate.jsonl" ]]; then
      path="$path/suite-aggregate.jsonl"
    else
      path="$path/suite-summary.jsonl"
    fi
  fi
  if [[ ! -f "$path" ]]; then
    echo "suite summary not found: $path" >&2
    exit 2
  fi
  if [[ ! -s "$path" ]]; then
    echo "suite summary is empty: $path" >&2
    exit 2
  fi
  printf '%s\n' "$path"
}

is_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

for value in "$throughput_regression_pct" "$latency_regression_pct" "$queue_regression_pct"; do
  if ! is_number "$value"; then
    echo "Threshold values must be non-negative numbers: $value" >&2
    exit 2
  fi
done

baseline_summary="$(resolve_summary "$baseline_path")"
candidate_summary="$(resolve_summary "$candidate_path")"

tmp_jsonl=""
jsonl_retention_note=""
if [[ -z "$jsonl_path" ]]; then
  if [[ -n "$report_path" ]]; then
    jsonl_path="${report_path%.*}.jsonl"
    jsonl_retention_note="retained beside the Markdown report"
  else
    tmp_jsonl="$(mktemp)"
    jsonl_path="$tmp_jsonl"
    jsonl_retention_note="temporary; pass --jsonl to retain it"
  fi
else
  jsonl_retention_note="retained at the configured path"
fi
trap '[[ -n "$tmp_jsonl" ]] && rm -f "$tmp_jsonl"' EXIT

mkdir -p "$(dirname "$jsonl_path")"
jq -c -n \
  --slurpfile baseline "$baseline_summary" \
  --slurpfile candidate "$candidate_summary" \
  --argjson throughputThreshold "$throughput_regression_pct" \
  --argjson latencyThreshold "$latency_regression_pct" \
  --argjson queueThreshold "$queue_regression_pct" '
  def suite_key($row):
    if ($row.summaryKind // "") == "aggregate" or (($row.iteration // "") | tostring) == "aggregate" then
      [$row.case, $row.benchmarkName] | join("|")
    else
      [$row.case, $row.benchmarkName, ($row.iteration | tostring)] | join("|")
    end;

  def n($value):
    if $value == null then null else ($value | tonumber) end;

  def pct_delta($base; $candidate):
    if $base == null or $candidate == null or ($base | tonumber) == 0 then
      null
    else
      (((($candidate | tonumber) - ($base | tonumber)) / ($base | tonumber)) * 100)
    end;

  def metric_row($row):
    if $row == null then
      null
    else
      {
        case: $row.case,
        benchmarkName: $row.benchmarkName,
        iteration: ($row.iteration // "aggregate"),
        measuredIterations: ($row.measuredIterations // 1),
        clients: $row.clients,
        payloadSize: $row.payloadSize,
        reliability: $row.reliability,
        batched: $row.batched,
        targetMbps: $row.targetMbps,
        targetClientMbps: $row.targetClientMbps,
        impairmentProfile: ($row.impairmentProfile // "0ms/0ms/0%"),
        impairmentLatencyMillis: ($row.impairmentLatencyMillis // 0),
        impairmentJitterMillis: ($row.impairmentJitterMillis // 0),
        impairmentLossPercent: ($row.impairmentLossPercent // 0),
        deliveredGbps: $row.deliveredGbps,
        healthyDeliveredGbps: ($row.healthyDeliveredGbps // $row.deliveredGbps),
        affectedDeliveredGbps: ($row.affectedDeliveredGbps // 0),
        deliveredGbpsSpreadPct: ($row.deliveredGbpsSpreadPct // null),
        deliveredMessagesPerSecond: $row.deliveredMessagesPerSecond,
        deliveredLogicalPacketsPerSecond: $row.deliveredLogicalPacketsPerSecond,
        probeRttP99Millis: $row.probeRttP99Millis,
        probeRttP99MillisSpreadPct: ($row.probeRttP99MillisSpreadPct // null),
        fairnessIndex: $row.fairnessIndex,
        healthyFairnessIndex: $row.healthyFairnessIndex,
        affectedFairnessIndex: ($row.affectedFairnessIndex // 1),
        disconnects: $row.disconnects,
        blackholedDatagramsIn: ($row.blackholedDatagramsIn // 0),
        blackholedDatagramsOut: ($row.blackholedDatagramsOut // 0),
        staleDatagrams: $row.staleDatagrams,
        nackIn: $row.nackIn,
        nackOut: $row.nackOut,
        maxQueuedBytes: $row.maxQueuedBytes,
        unstable: ($row.unstable // false),
        unstableReasons: ($row.unstableReasons // []),
        artifact: $row.artifact
      }
    end;

  def compare_rows($base; $cand):
    (pct_delta(n($base.deliveredGbps); n($cand.deliveredGbps))) as $throughputDeltaPct |
    (pct_delta(n($base.probeRttP99Millis); n($cand.probeRttP99Millis))) as $latencyDeltaPct |
    (pct_delta(n($base.maxQueuedBytes); n($cand.maxQueuedBytes))) as $queueDeltaPct |
    (($base.impairmentProfile // "0ms/0ms/0%") != ($cand.impairmentProfile // "0ms/0ms/0%")) as $impairmentMismatch |
    (
      []
      + (if $impairmentMismatch then ["impairment-profile-mismatch"] else [] end)
      + (if $throughputDeltaPct != null and $throughputDeltaPct < (-1 * $throughputThreshold) then ["throughput-regression"] else [] end)
      + (if $latencyDeltaPct != null and $latencyDeltaPct > $latencyThreshold then ["p99-latency-regression"] else [] end)
      + (if $queueDeltaPct != null and $queueDeltaPct > $queueThreshold then ["queue-regression"] else [] end)
    ) as $reasons |
    {
      status: (if ($reasons | length) == 0 then "ok" else "regression" end),
      statusReasons: $reasons,
      case: $cand.case,
      benchmarkName: $cand.benchmarkName,
      iteration: ($cand.iteration // "aggregate"),
      baseline: metric_row($base),
      candidate: metric_row($cand),
      deltas: {
        deliveredGbpsPct: $throughputDeltaPct,
        probeRttP99MillisPct: $latencyDeltaPct,
        maxQueuedBytesPct: $queueDeltaPct,
        fairnessIndex: ((n($cand.fairnessIndex) // 0) - (n($base.fairnessIndex) // 0)),
        healthyFairnessIndex: ((n($cand.healthyFairnessIndex) // 0) - (n($base.healthyFairnessIndex) // 0)),
        affectedFairnessIndex: ((n($cand.affectedFairnessIndex) // 0) - (n($base.affectedFairnessIndex) // 0)),
        healthyDeliveredGbpsPct: pct_delta(n($base.healthyDeliveredGbps); n($cand.healthyDeliveredGbps)),
        affectedDeliveredGbpsPct: pct_delta(n($base.affectedDeliveredGbps); n($cand.affectedDeliveredGbps)),
        disconnects: ((n($cand.disconnects) // 0) - (n($base.disconnects) // 0)),
        blackholedDatagramsIn: ((n($cand.blackholedDatagramsIn) // 0) - (n($base.blackholedDatagramsIn) // 0)),
        blackholedDatagramsOut: ((n($cand.blackholedDatagramsOut) // 0) - (n($base.blackholedDatagramsOut) // 0)),
        staleDatagrams: ((n($cand.staleDatagrams) // 0) - (n($base.staleDatagrams) // 0)),
        nackIn: ((n($cand.nackIn) // 0) - (n($base.nackIn) // 0)),
        nackOut: ((n($cand.nackOut) // 0) - (n($base.nackOut) // 0))
      }
    };

  ($baseline | map({key: suite_key(.), value: .}) | from_entries) as $baselineByKey |
  ($candidate | map({key: suite_key(.), value: .}) | from_entries) as $candidateByKey |
  (($baselineByKey | keys_unsorted) + ($candidateByKey | keys_unsorted) | unique | sort[]) as $key |
  ($baselineByKey[$key] // null) as $base |
  ($candidateByKey[$key] // null) as $cand |
  if $base == null then
    {
      status: "extra-candidate",
      statusReasons: ["extra-candidate"],
      case: $cand.case,
      benchmarkName: $cand.benchmarkName,
      iteration: ($cand.iteration // "aggregate"),
      baseline: null,
      candidate: metric_row($cand),
      deltas: {}
    }
  elif $cand == null then
    {
      status: "missing-candidate",
      statusReasons: ["missing-candidate"],
      case: $base.case,
      benchmarkName: $base.benchmarkName,
      iteration: ($base.iteration // "aggregate"),
      baseline: metric_row($base),
      candidate: null,
      deltas: {}
    }
  else
    compare_rows($base; $cand)
  end
' >"$jsonl_path"

total_rows="$(jq -s 'length' "$jsonl_path")"
regression_rows="$(jq -s '[.[] | select(.status == "regression")] | length' "$jsonl_path")"
missing_rows="$(jq -s '[.[] | select(.status == "missing-candidate")] | length' "$jsonl_path")"
extra_rows="$(jq -s '[.[] | select(.status == "extra-candidate")] | length' "$jsonl_path")"
ok_rows="$(jq -s '[.[] | select(.status == "ok")] | length' "$jsonl_path")"
failure_rows=$((regression_rows + missing_rows))

write_report() {
  {
    echo "# RakNet Baseline Comparison"
    echo
    echo "- Baseline summary: \`$baseline_summary\`"
    echo "- Candidate summary: \`$candidate_summary\`"
    echo "- Raw comparison JSONL: \`$jsonl_path\` ($jsonl_retention_note)"
    echo "- Throughput regression threshold: \`$throughput_regression_pct%\`"
    echo "- p99 latency regression threshold: \`$latency_regression_pct%\`"
    echo "- Queue regression threshold: \`$queue_regression_pct%\`"
    echo
    echo "| Result | Count |"
    echo "| --- | ---: |"
    echo "| Total rows | $total_rows |"
    echo "| OK | $ok_rows |"
    echo "| Regressions | $regression_rows |"
    echo "| Missing candidate rows | $missing_rows |"
    echo "| Extra candidate rows | $extra_rows |"
    echo
    echo "| Status | Case | Scenario | Impairment | Iteration | Iterations | Delivered Gbps | Delta | Healthy Gbps Delta | Affected Gbps Delta | p99 RTT ms | Delta | Throughput Spread | p99 Spread | Max queue bytes | Delta | Fairness delta | Healthy fairness delta | Affected fairness delta | Candidate unstable | Blackhole in delta | Blackhole out delta | NACK out delta | Stale datagram delta | Reasons |"
    echo "| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- |"
    jq -r '
      def fmt($value):
        if $value == null then "n/a"
        elif ($value | type) == "number" and $value != 0 and (($value | fabs) < 1) then (($value * 1000000 | round) / 1000000 | tostring)
        elif ($value | type) == "number" then (($value * 1000 | round) / 1000 | tostring)
        else ($value | tostring)
        end;
      def metric($side; $name):
        if $side == null then "n/a" else fmt($side[$name]) end;
      def pct($value):
        if $value == null then "n/a" else (((($value * 100) | round) / 100) | tostring) + "%" end;
      def unstable($side):
        if $side == null then "n/a"
        elif ($side.unstable // false) then "true:" + (($side.unstableReasons // []) | join(","))
        else "false"
        end;
      [
        "`" + .status + "`",
        "`" + .case + "`",
        "`" + .benchmarkName + "`",
        (metric(.candidate; "impairmentProfile") + " / " + metric(.baseline; "impairmentProfile")),
        (.iteration | tostring),
        (metric(.candidate; "measuredIterations") + " / " + metric(.baseline; "measuredIterations")),
        (metric(.candidate; "deliveredGbps") + " / " + metric(.baseline; "deliveredGbps")),
        pct(.deltas.deliveredGbpsPct),
        pct(.deltas.healthyDeliveredGbpsPct),
        pct(.deltas.affectedDeliveredGbpsPct),
        (metric(.candidate; "probeRttP99Millis") + " / " + metric(.baseline; "probeRttP99Millis")),
        pct(.deltas.probeRttP99MillisPct),
        (pct(.candidate.deliveredGbpsSpreadPct) + " / " + pct(.baseline.deliveredGbpsSpreadPct)),
        (pct(.candidate.probeRttP99MillisSpreadPct) + " / " + pct(.baseline.probeRttP99MillisSpreadPct)),
        (metric(.candidate; "maxQueuedBytes") + " / " + metric(.baseline; "maxQueuedBytes")),
        pct(.deltas.maxQueuedBytesPct),
        fmt(.deltas.fairnessIndex),
        fmt(.deltas.healthyFairnessIndex),
        fmt(.deltas.affectedFairnessIndex),
        unstable(.candidate),
        fmt(.deltas.blackholedDatagramsIn),
        fmt(.deltas.blackholedDatagramsOut),
        fmt(.deltas.nackOut),
        fmt(.deltas.staleDatagrams),
        "`" + ((.statusReasons // []) | join(",")) + "`"
      ] | @tsv
    ' "$jsonl_path" | while IFS=$'\t' read -r status case_name scenario impairment iteration iterations delivered delivered_delta healthy_delta affected_delta p99 p99_delta throughput_spread p99_spread queue queue_delta fairness_delta healthy_fairness_delta affected_fairness_delta candidate_unstable blackhole_in_delta blackhole_out_delta nack_delta stale_delta reasons; do
      echo "| $status | $case_name | $scenario | $impairment | $iteration | $iterations | $delivered | $delivered_delta | $healthy_delta | $affected_delta | $p99 | $p99_delta | $throughput_spread | $p99_spread | $queue | $queue_delta | $fairness_delta | $healthy_fairness_delta | $affected_fairness_delta | $candidate_unstable | $blackhole_in_delta | $blackhole_out_delta | $nack_delta | $stale_delta | $reasons |"
    done
    echo
    if [[ "$failure_rows" -gt 0 ]]; then
      echo "Comparison failed: $regression_rows regression row(s), $missing_rows missing candidate row(s)."
    else
      echo "Comparison passed."
    fi
  }
}

if [[ -n "$report_path" ]]; then
  mkdir -p "$(dirname "$report_path")"
  write_report >"$report_path"
  echo "Comparison report: $report_path"
  echo "Comparison JSONL: $jsonl_path"
else
  write_report
fi

if [[ "$failure_rows" -gt 0 ]]; then
  exit 1
fi
