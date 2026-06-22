#!/usr/bin/env bash
set -euo pipefail

baseline_path=""
candidate_path=""
report_path=""
jsonl_path=""
throughput_regression_pct="10"
latency_regression_pct="10"
queue_regression_pct="50"
allow_failed_validation=false
require_validation=false
allow_validation_bypasses=false

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
  --require-validation            Fail when either input does not have validation.json.
  --allow-failed-validation       Do not fail when baseline or candidate validation.json exists and is failed.
  --allow-validation-bypasses     Allow validation files that used baseline bypass flags. Smoke only.
  --help                          Show this help.

Suite directories prefer suite-aggregate.jsonl when present, falling back to
suite-summary.jsonl. Aggregate rows are matched by case name and benchmark
scenario. Per-iteration rows are matched by case name, scenario, and iteration
number. The script exits non-zero when a candidate row is missing, a matched
row changes matrix shape, or a matched row breaches one of the configured
regression thresholds. Extra candidate rows are reported as informational rows
and do not fail the comparison.
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
    --allow-failed-validation)
      allow_failed_validation=true
      shift
      ;;
    --allow-validation-bypasses)
      allow_validation_bypasses=true
      shift
      ;;
    --require-validation)
      require_validation=true
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

resolve_validation() {
  local original_path="$1"
  local summary_path="$2"
  if [[ -d "$original_path" && -s "$original_path/validation.json" ]]; then
    printf '%s\n' "$original_path/validation.json"
    return
  fi
  local summary_dir
  summary_dir="$(dirname "$summary_path")"
  if [[ -s "$summary_dir/validation.json" ]]; then
    printf '%s\n' "$summary_dir/validation.json"
    return
  fi
  printf '\n'
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
baseline_validation="$(resolve_validation "$baseline_path" "$baseline_summary")"
candidate_validation="$(resolve_validation "$candidate_path" "$candidate_summary")"

validation_failures=()
validation_missing=()
validation_bypasses=()
for label_and_path in "baseline:$baseline_validation" "candidate:$candidate_validation"; do
  label="${label_and_path%%:*}"
  validation_path="${label_and_path#*:}"
  if [[ -z "$validation_path" ]]; then
    if "$require_validation"; then
      validation_missing+=("$label")
    fi
    continue
  fi
  if ! jq -e 'has("passed") and (.passed | type == "boolean")' "$validation_path" >/dev/null; then
    echo "$label validation file is not a validate-lab-baseline result: $validation_path" >&2
    exit 2
  fi
  if ! jq -e '.passed == true' "$validation_path" >/dev/null; then
    validation_failures+=("$label:$validation_path")
  fi
  validation_bypass_flags="$(jq -r '
    [
      ["allowUnstable", (.allowUnstable // false)],
      ["allowDisconnects", (.allowDisconnects // false)],
      ["allowMissingCapacity", (.allowMissingCapacity // false)],
      ["allowUnselectedCapacity", (.allowUnselectedCapacity // false)],
      ["allowMissingHostContext", (.allowMissingHostContext // false)],
      ["allowMissingPrereqContext", (.allowMissingPrereqContext // false)],
      ["allowLoosePrereqGates", (.allowLoosePrereqGates // false)]
    ]
    | map(select(.[1] == true) | .[0])
    | join(",")
  ' "$validation_path")"
  if [[ -n "$validation_bypass_flags" ]]; then
    validation_bypasses+=("$label:$validation_path:$validation_bypass_flags")
  fi
done

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

  def numeric_mismatch($base; $candidate):
    if $base == null and $candidate == null then
      false
    elif $base == null or $candidate == null then
      true
    else
      (((($candidate | tonumber) - ($base | tonumber)) | fabs) > 0.000001)
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
        packetLimit: ($row.packetLimit // null),
        globalPacketLimit: ($row.globalPacketLimit // null),
        configuredMaxQueuedBytes: ($row.configuredMaxQueuedBytes // null),
        impairmentProfile: ($row.impairmentProfile // "0ms/0ms/0%"),
        impairmentLatencyMillis: ($row.impairmentLatencyMillis // 0),
        impairmentJitterMillis: ($row.impairmentJitterMillis // 0),
        impairmentLossPercent: ($row.impairmentLossPercent // 0),
        deliveredGbps: $row.deliveredGbps,
        healthyDeliveredGbps: ($row.healthyDeliveredGbps // $row.deliveredGbps),
        affectedDeliveredGbps: ($row.affectedDeliveredGbps // 0),
        serverDatagramsOutPerSecond: ($row.serverDatagramsOutPerSecond // 0),
        sentToDeliveredBytesRatio: ($row.sentToDeliveredBytesRatio // 0),
        healthySentToDeliveredBytesRatio: ($row.healthySentToDeliveredBytesRatio // 0),
        affectedSentToDeliveredBytesRatio: ($row.affectedSentToDeliveredBytesRatio // 0),
        clientMbpsP50: ($row.clientMbpsP50 // 0),
        clientMbpsP99: ($row.clientMbpsP99 // 0),
        healthyClientMbpsP50: ($row.healthyClientMbpsP50 // 0),
        healthyClientMbpsP99: ($row.healthyClientMbpsP99 // 0),
        affectedClientMbpsP50: ($row.affectedClientMbpsP50 // 0),
        affectedClientMbpsP99: ($row.affectedClientMbpsP99 // 0),
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
        staleDatagramsPerSecond: ($row.staleDatagramsPerSecond // 0),
        nackIn: $row.nackIn,
        nackInPerSecond: ($row.nackInPerSecond // 0),
        nackOut: $row.nackOut,
        nackOutPerSecond: ($row.nackOutPerSecond // 0),
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
    (($base.clients // null) != ($cand.clients // null)) as $clientCountMismatch |
    (($base.payloadSize // null) != ($cand.payloadSize // null)) as $payloadSizeMismatch |
    (($base.reliability // null) != ($cand.reliability // null)) as $reliabilityMismatch |
    (($base.batched // null) != ($cand.batched // null)) as $batchedMismatch |
    (numeric_mismatch($base.targetMbps; $cand.targetMbps)) as $targetMbpsMismatch |
    (numeric_mismatch($base.targetClientMbps; $cand.targetClientMbps)) as $targetClientMbpsMismatch |
    (($base.impairmentProfile // "0ms/0ms/0%") != ($cand.impairmentProfile // "0ms/0ms/0%")) as $impairmentMismatch |
    (($base.packetLimit // null) != ($cand.packetLimit // null)) as $packetLimitMismatch |
    (($base.globalPacketLimit // null) != ($cand.globalPacketLimit // null)) as $globalPacketLimitMismatch |
    (($base.configuredMaxQueuedBytes // null) != ($cand.configuredMaxQueuedBytes // null)) as $configuredMaxQueuedBytesMismatch |
    (
      []
      + (if $clientCountMismatch then ["client-count-mismatch"] else [] end)
      + (if $payloadSizeMismatch then ["payload-size-mismatch"] else [] end)
      + (if $reliabilityMismatch then ["reliability-mismatch"] else [] end)
      + (if $batchedMismatch then ["batched-mode-mismatch"] else [] end)
      + (if $targetMbpsMismatch then ["target-mbps-mismatch"] else [] end)
      + (if $targetClientMbpsMismatch then ["target-client-mbps-mismatch"] else [] end)
      + (if $impairmentMismatch then ["impairment-profile-mismatch"] else [] end)
      + (if $packetLimitMismatch then ["packet-limit-mismatch"] else [] end)
      + (if $globalPacketLimitMismatch then ["global-packet-limit-mismatch"] else [] end)
      + (if $configuredMaxQueuedBytesMismatch then ["configured-max-queued-bytes-mismatch"] else [] end)
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
        clientMbpsP50Pct: pct_delta(n($base.clientMbpsP50); n($cand.clientMbpsP50)),
        clientMbpsP99Pct: pct_delta(n($base.clientMbpsP99); n($cand.clientMbpsP99)),
        healthyClientMbpsP50Pct: pct_delta(n($base.healthyClientMbpsP50); n($cand.healthyClientMbpsP50)),
        affectedClientMbpsP50Pct: pct_delta(n($base.affectedClientMbpsP50); n($cand.affectedClientMbpsP50)),
        serverDatagramsOutPerSecondPct: pct_delta(n($base.serverDatagramsOutPerSecond); n($cand.serverDatagramsOutPerSecond)),
        sentToDeliveredBytesRatioPct: pct_delta(n($base.sentToDeliveredBytesRatio); n($cand.sentToDeliveredBytesRatio)),
        affectedSentToDeliveredBytesRatioPct: pct_delta(n($base.affectedSentToDeliveredBytesRatio); n($cand.affectedSentToDeliveredBytesRatio)),
        staleDatagramsPerSecondPct: pct_delta(n($base.staleDatagramsPerSecond); n($cand.staleDatagramsPerSecond)),
        nackOutPerSecondPct: pct_delta(n($base.nackOutPerSecond); n($cand.nackOutPerSecond)),
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
validation_failure_rows="${#validation_failures[@]}"
if [[ "$allow_failed_validation" == "true" ]]; then
  validation_failure_rows=0
fi
validation_missing_rows="${#validation_missing[@]}"
validation_bypass_rows="${#validation_bypasses[@]}"
if [[ "$allow_validation_bypasses" == "true" ]]; then
  validation_bypass_rows=0
fi

write_report() {
  {
    echo "# RakNet Baseline Comparison"
    echo
    echo "- Baseline summary: \`$baseline_summary\`"
    echo "- Candidate summary: \`$candidate_summary\`"
    if [[ -n "$baseline_validation" ]]; then
      baseline_validation_status="$(jq -r 'if .passed then "passed" else "failed" end' "$baseline_validation")"
      echo "- Baseline validation: \`$baseline_validation\` (\`$baseline_validation_status\`)"
    else
      echo "- Baseline validation: not found"
    fi
    if [[ -n "$candidate_validation" ]]; then
      candidate_validation_status="$(jq -r 'if .passed then "passed" else "failed" end' "$candidate_validation")"
      echo "- Candidate validation: \`$candidate_validation\` (\`$candidate_validation_status\`)"
    else
      echo "- Candidate validation: not found"
    fi
    echo "- Raw comparison JSONL: \`$jsonl_path\` ($jsonl_retention_note)"
    echo "- Throughput regression threshold: \`$throughput_regression_pct%\`"
    echo "- p99 latency regression threshold: \`$latency_regression_pct%\`"
    echo "- Queue regression threshold: \`$queue_regression_pct%\`"
    echo "- Require validation: \`$require_validation\`"
    echo "- Allow failed validation: \`$allow_failed_validation\`"
    echo "- Allow validation bypasses: \`$allow_validation_bypasses\`"
    echo
    echo "| Result | Count |"
    echo "| --- | ---: |"
    echo "| Total rows | $total_rows |"
    echo "| OK | $ok_rows |"
    echo "| Regressions | $regression_rows |"
    echo "| Missing candidate rows | $missing_rows |"
    echo "| Extra candidate rows | $extra_rows |"
    echo "| Failed validation inputs | ${#validation_failures[@]} |"
    echo "| Missing validation inputs | ${#validation_missing[@]} |"
    echo "| Validation bypass inputs | ${#validation_bypasses[@]} |"
    echo
    if [[ "${#validation_missing[@]}" -gt 0 ]]; then
      echo "## Missing Validation"
      echo
      echo "| Input | Required file |"
      echo "| --- | --- |"
      for missing in "${validation_missing[@]}"; do
        echo "| $missing | \`validation.json\` |"
      done
      echo
    fi
    if [[ "${#validation_failures[@]}" -gt 0 ]]; then
      echo "## Validation Failures"
      echo
      echo "| Input | Validation | Issues |"
      echo "| --- | --- | ---: |"
      for failure in "${validation_failures[@]}"; do
        failure_label="${failure%%:*}"
        failure_path="${failure#*:}"
        echo "| $failure_label | \`$failure_path\` | $(jq -r '.issues | length' "$failure_path") |"
      done
      echo
    fi
    if [[ "${#validation_bypasses[@]}" -gt 0 ]]; then
      echo "## Validation Bypasses"
      echo
      echo "| Input | Validation | Bypass flags |"
      echo "| --- | --- | --- |"
      for bypass in "${validation_bypasses[@]}"; do
        bypass_label="${bypass%%:*}"
        bypass_rest="${bypass#*:}"
        bypass_path="${bypass_rest%:*}"
        bypass_flags="${bypass_rest##*:}"
        echo "| $bypass_label | \`$bypass_path\` | \`$bypass_flags\` |"
      done
      echo
    fi
    echo "| Status | Case | Scenario | Impairment | Iteration | Iterations | Delivered Gbps | Delta | Healthy Gbps Delta | Affected Gbps Delta | Client Mbps p50 | Delta | Client Mbps p99 | Delta | Send/Deliver | Delta | Affected Send/Deliver Delta | Datagram Out/s | Delta | Stale/s Delta | NACK Out/s Delta | p99 RTT ms | Delta | Throughput Spread | p99 Spread | Max queue bytes | Delta | Fairness delta | Healthy fairness delta | Affected fairness delta | Candidate unstable | Blackhole in delta | Blackhole out delta | NACK out delta | Stale datagram delta | Reasons |"
    echo "| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- |"
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
        (metric(.candidate; "clientMbpsP50") + " / " + metric(.baseline; "clientMbpsP50")),
        pct(.deltas.clientMbpsP50Pct),
        (metric(.candidate; "clientMbpsP99") + " / " + metric(.baseline; "clientMbpsP99")),
        pct(.deltas.clientMbpsP99Pct),
        (metric(.candidate; "sentToDeliveredBytesRatio") + " / " + metric(.baseline; "sentToDeliveredBytesRatio")),
        pct(.deltas.sentToDeliveredBytesRatioPct),
        pct(.deltas.affectedSentToDeliveredBytesRatioPct),
        (metric(.candidate; "serverDatagramsOutPerSecond") + " / " + metric(.baseline; "serverDatagramsOutPerSecond")),
        pct(.deltas.serverDatagramsOutPerSecondPct),
        pct(.deltas.staleDatagramsPerSecondPct),
        pct(.deltas.nackOutPerSecondPct),
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
    ' "$jsonl_path" | while IFS=$'\t' read -r status case_name scenario impairment iteration iterations delivered delivered_delta healthy_delta affected_delta client_p50 client_p50_delta client_p99 client_p99_delta send_ratio send_ratio_delta affected_send_ratio_delta datagram_out_s datagram_out_s_delta stale_s_delta nack_out_s_delta p99 p99_delta throughput_spread p99_spread queue queue_delta fairness_delta healthy_fairness_delta affected_fairness_delta candidate_unstable blackhole_in_delta blackhole_out_delta nack_delta stale_delta reasons; do
      echo "| $status | $case_name | $scenario | $impairment | $iteration | $iterations | $delivered | $delivered_delta | $healthy_delta | $affected_delta | $client_p50 | $client_p50_delta | $client_p99 | $client_p99_delta | $send_ratio | $send_ratio_delta | $affected_send_ratio_delta | $datagram_out_s | $datagram_out_s_delta | $stale_s_delta | $nack_out_s_delta | $p99 | $p99_delta | $throughput_spread | $p99_spread | $queue | $queue_delta | $fairness_delta | $healthy_fairness_delta | $affected_fairness_delta | $candidate_unstable | $blackhole_in_delta | $blackhole_out_delta | $nack_delta | $stale_delta | $reasons |"
    done
    echo
    if [[ "$failure_rows" -gt 0 || "$validation_failure_rows" -gt 0 || "$validation_missing_rows" -gt 0 || "$validation_bypass_rows" -gt 0 ]]; then
      echo "Comparison failed: $regression_rows regression row(s), $missing_rows missing candidate row(s), $validation_failure_rows failed validation input(s), $validation_missing_rows missing validation input(s), $validation_bypass_rows validation bypass input(s)."
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

if [[ "$failure_rows" -gt 0 || "$validation_failure_rows" -gt 0 || "$validation_missing_rows" -gt 0 || "$validation_bypass_rows" -gt 0 ]]; then
  exit 1
fi
