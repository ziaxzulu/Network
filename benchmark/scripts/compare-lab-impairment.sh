#!/usr/bin/env bash
set -euo pipefail

baseline_path=""
candidate_path=""
report_path=""
jsonl_path=""
throughput_regression_pct="10"
latency_regression_pct="10"
queue_regression_pct="50"
allow_failed_summary=false
allow_missing_netem_evidence=false
allow_validation_bypasses=false

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/compare-lab-impairment.sh --baseline DIR|impairment-summary.json --candidate DIR|impairment-summary.json [options]

Compares two multi-profile lab impairment campaign summaries produced by
summarize-lab-impairment.sh.

Options:
  --baseline PATH                 Baseline campaign-summary dir or impairment-summary.json.
  --candidate PATH                Candidate campaign-summary dir or impairment-summary.json.
  --out FILE                      Markdown report path. Default: stdout.
  --jsonl FILE                    Raw comparison JSONL path. Default: next to --out, or temporary for stdout.
  --throughput-regression-pct N   Fail when delivered Gbps falls by more than N percent. Default: 10.
  --latency-regression-pct N      Fail when p99 probe RTT rises by more than N percent. Default: 10.
  --queue-regression-pct N        Fail when max queued bytes rises by more than N percent. Default: 50.
  --allow-failed-summary          Do not fail when either campaign summary is failed.
  --allow-missing-netem-evidence  Do not fail when a summary was generated without required netem evidence.
  --allow-validation-bypasses     Allow summaries that used profile validation bypass flags. Smoke only.
  --help                          Show this help.
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
    --allow-failed-summary)
      allow_failed_summary=true
      shift
      ;;
    --allow-missing-netem-evidence)
      allow_missing_netem_evidence=true
      shift
      ;;
    --allow-validation-bypasses)
      allow_validation_bypasses=true
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
  echo "jq is required to compare lab impairment campaigns" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

is_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

for value in "$throughput_regression_pct" "$latency_regression_pct" "$queue_regression_pct"; do
  if ! is_number "$value"; then
    echo "Threshold values must be non-negative numbers: $value" >&2
    exit 2
  fi
done

resolve_summary() {
  local path="$1"
  if [[ "$path" != /* ]]; then
    path="$repo_root/$path"
  fi
  if [[ -d "$path" ]]; then
    if [[ -s "$path/impairment-summary.json" ]]; then
      path="$path/impairment-summary.json"
    elif [[ -s "$path/campaign-summary/impairment-summary.json" ]]; then
      path="$path/campaign-summary/impairment-summary.json"
    else
      echo "impairment-summary.json not found in: $path" >&2
      exit 2
    fi
  fi
  if [[ ! -s "$path" ]]; then
    echo "impairment summary not found or empty: $path" >&2
    exit 2
  fi
  if ! jq -e '.summaryKind == "raknet-lab-impairment-campaign"' "$path" >/dev/null; then
    echo "not a lab impairment campaign summary: $path" >&2
    exit 2
  fi
  printf '%s\n' "$path"
}

baseline_summary="$(resolve_summary "$baseline_path")"
candidate_summary="$(resolve_summary "$candidate_path")"

summary_failures=()
for label_and_path in "baseline:$baseline_summary" "candidate:$candidate_summary"; do
  label="${label_and_path%%:*}"
  path="${label_and_path#*:}"
  if ! jq -e '.passed == true' "$path" >/dev/null; then
    summary_failures+=("$label:$path")
  fi
done

netem_evidence_failures=()
if [[ "$allow_missing_netem_evidence" != "true" ]]; then
  for label_and_path in "baseline:$baseline_summary" "candidate:$candidate_summary"; do
    label="${label_and_path%%:*}"
    path="${label_and_path#*:}"
    if ! jq -e '.requireNetemEvidence == true' "$path" >/dev/null; then
      netem_evidence_failures+=("$label:$path")
    fi
  done
fi

validation_bypass_failures=()
if [[ "$allow_validation_bypasses" != "true" ]]; then
  for label_and_path in "baseline:$baseline_summary" "candidate:$candidate_summary"; do
    label="${label_and_path%%:*}"
    path="${label_and_path#*:}"
    if jq -e '
      (.allowValidationBypasses == true)
      or any((.profiles // [])[]; ((.validation.bypassFlags // []) | length) > 0)
    ' "$path" >/dev/null; then
      validation_bypass_failures+=("$label:$path")
    fi
  done
fi

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
  if [[ "$jsonl_path" != /* ]]; then
    jsonl_path="$repo_root/$jsonl_path"
  fi
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
  def n($value): if $value == null then null else ($value | tonumber) end;
  def pct_delta($base; $candidate):
    if $base == null or $candidate == null or ($base | tonumber) == 0 then null
    else (((($candidate | tonumber) - ($base | tonumber)) / ($base | tonumber)) * 100)
    end;
  def network($profile): "\($profile.latency)/\($profile.jitter)/\($profile.loss)";
  def profile_key($profile): $profile.profile;
  def capacity_key($profile; $row):
    [
      "capacity",
      $profile.profile,
      ($row.payloadSize // "null" | tostring),
      ($row.reliability // "null" | tostring),
      ($row.packetLimit // "null" | tostring),
      ($row.globalPacketLimit // "null" | tostring)
    ] | join("|");
  def contention_key($profile; $row):
    [
      "contention",
      $profile.profile,
      ($row.benchmarkName // "null" | tostring),
      ($row.clients // "null" | tostring),
      ($row.payloadSize // "null" | tostring),
      ($row.reliability // "null" | tostring),
      ($row.targetClientMbps // "null" | tostring),
      ($row.batchIntervalMillis // "0" | tostring),
      ($row.logicalPacketsPerBatch // "1" | tostring),
      ($row.batchGroups // "1" | tostring),
      ($row.packetLimit // "null" | tostring),
      ($row.globalPacketLimit // "null" | tostring),
      ($row.configuredMaxQueuedBytes // "null" | tostring)
    ] | join("|");
  def profile_row($profile):
    {
      kind: "profile",
      key: ("profile|" + $profile.profile),
      profile: $profile.profile,
      network: network($profile),
      validationPassed: ($profile.validation.passed // false),
      aggregateRows: ($profile.aggregate.rowCount // 0),
      capacityRows: ($profile.capacity.rowCount // 0),
      capacitySelected: ($profile.capacity.selectedCount // 0),
      netemStatusEvidenceCount: ($profile.netem.statusEvidenceCount // 0),
      metric: null
    };
  def capacity_row($profile; $row):
    {
      kind: "capacity",
      key: capacity_key($profile; $row),
      profile: $profile.profile,
      network: network($profile),
      case: ($row.case // null),
      benchmarkName: ($row.selectedBenchmarkName // null),
      payloadSize: ($row.payloadSize // null),
      reliability: ($row.reliability // null),
      packetLimit: ($row.packetLimit // null),
      globalPacketLimit: ($row.globalPacketLimit // null),
      selected: ($row.selected // false),
      deliveredGbps: ($row.selectedDeliveredGbps // null),
      probeRttP99Millis: ($row.selectedProbeRttP99Millis // null),
      bestObservedDeliveredGbps: ($row.bestObservedDeliveredGbps // null),
      metric: "selected-capacity"
    };
  def contention_row($profile; $row):
    {
      kind: "contention",
      key: contention_key($profile; $row),
      profile: $profile.profile,
      network: network($profile),
      case: ($row.case // null),
      benchmarkName: ($row.benchmarkName // null),
      clients: ($row.clients // null),
      payloadSize: ($row.payloadSize // null),
      reliability: ($row.reliability // null),
      packetLimit: ($row.packetLimit // null),
      globalPacketLimit: ($row.globalPacketLimit // null),
      configuredMaxQueuedBytes: ($row.configuredMaxQueuedBytes // null),
      batchIntervalMillis: ($row.batchIntervalMillis // 0),
      logicalPacketsPerBatch: ($row.logicalPacketsPerBatch // 1),
      batchGroups: ($row.batchGroups // 1),
      targetClientMbps: ($row.targetClientMbps // null),
      deliveredGbps: ($row.deliveredGbps // null),
      probeRttP99Millis: ($row.probeRttP99Millis // null),
      maxQueuedBytes: ($row.maxQueuedBytes // null),
      healthyFairnessIndex: ($row.healthyFairnessIndex // null),
      healthySentToDeliveredBytesRatio: ($row.healthySentToDeliveredBytesRatio // null),
      affectedSentToDeliveredBytesRatio: ($row.affectedSentToDeliveredBytesRatio // null),
      unstable: ($row.unstable // false),
      unstableReasons: ($row.unstableReasons // []),
      metric: "contention"
    };
  def rows($summary):
    ($summary.profiles // [])[] as $profile
    | profile_row($profile),
      (($profile.capacity.rows // [])[] | capacity_row($profile; .)),
      (($profile.aggregate.contentionRows // [])[] | contention_row($profile; .));
  def row_map($summary): [rows($summary)] | map({key:.key, value:.}) | from_entries;
  def compare_row($base; $cand):
    (pct_delta(n($base.deliveredGbps); n($cand.deliveredGbps))) as $throughputDelta |
    (pct_delta(n($base.probeRttP99Millis); n($cand.probeRttP99Millis))) as $latencyDelta |
    (pct_delta(n($base.maxQueuedBytes); n($cand.maxQueuedBytes))) as $queueDelta |
    (
      []
      + (if (($base.network // null) != ($cand.network // null)) then ["network-profile-mismatch"] else [] end)
      + (if ($cand.kind == "profile" and (($cand.validationPassed // false) | not)) then ["candidate-validation-failed"] else [] end)
      + (if ($cand.kind == "capacity" and (($cand.selected // false) | not)) then ["candidate-capacity-unselected"] else [] end)
      + (if ($throughputDelta != null and $throughputDelta < (-1 * $throughputThreshold)) then ["throughput-regression"] else [] end)
      + (if ($latencyDelta != null and $latencyDelta > $latencyThreshold) then ["p99-latency-regression"] else [] end)
      + (if ($queueDelta != null and $queueDelta > $queueThreshold) then ["queue-regression"] else [] end)
    ) as $reasons |
    {
      status: (if ($reasons | length) == 0 then "ok" else "regression" end),
      statusReasons: $reasons,
      kind: $cand.kind,
      key: $cand.key,
      profile: $cand.profile,
      network: $cand.network,
      case: ($cand.case // null),
      benchmarkName: ($cand.benchmarkName // null),
      baseline: $base,
      candidate: $cand,
      deltas: {
        deliveredGbpsPct: $throughputDelta,
        probeRttP99MillisPct: $latencyDelta,
        maxQueuedBytesPct: $queueDelta,
        healthyFairnessIndex: ((n($cand.healthyFairnessIndex) // 0) - (n($base.healthyFairnessIndex) // 0)),
        healthySentToDeliveredBytesRatioPct: pct_delta(n($base.healthySentToDeliveredBytesRatio); n($cand.healthySentToDeliveredBytesRatio)),
        affectedSentToDeliveredBytesRatioPct: pct_delta(n($base.affectedSentToDeliveredBytesRatio); n($cand.affectedSentToDeliveredBytesRatio))
      }
    };

  ($baseline[0]) as $baseSummary |
  ($candidate[0]) as $candidateSummary |
  (row_map($baseSummary)) as $baseByKey |
  (row_map($candidateSummary)) as $candidateByKey |
  (($baseByKey | keys_unsorted) + ($candidateByKey | keys_unsorted) | unique | sort[]) as $key |
  ($baseByKey[$key] // null) as $base |
  ($candidateByKey[$key] // null) as $cand |
  if $base == null then
    {
      status: "extra-candidate",
      statusReasons: ["extra-candidate"],
      kind: $cand.kind,
      key: $cand.key,
      profile: $cand.profile,
      network: $cand.network,
      case: ($cand.case // null),
      benchmarkName: ($cand.benchmarkName // null),
      baseline: null,
      candidate: $cand,
      deltas: {}
    }
  elif $cand == null then
    {
      status: "missing-candidate",
      statusReasons: ["missing-candidate"],
      kind: $base.kind,
      key: $base.key,
      profile: $base.profile,
      network: $base.network,
      case: ($base.case // null),
      benchmarkName: ($base.benchmarkName // null),
      baseline: $base,
      candidate: null,
      deltas: {}
    }
  else
    compare_row($base; $cand)
  end
' >"$jsonl_path"

total_rows="$(jq -s 'length' "$jsonl_path")"
regression_rows="$(jq -s '[.[] | select(.status == "regression")] | length' "$jsonl_path")"
missing_rows="$(jq -s '[.[] | select(.status == "missing-candidate")] | length' "$jsonl_path")"
extra_rows="$(jq -s '[.[] | select(.status == "extra-candidate")] | length' "$jsonl_path")"
ok_rows="$(jq -s '[.[] | select(.status == "ok")] | length' "$jsonl_path")"
failure_rows=$((regression_rows + missing_rows))
summary_failure_rows="${#summary_failures[@]}"
if [[ "$allow_failed_summary" == "true" ]]; then
  summary_failure_rows=0
fi
netem_failure_rows="${#netem_evidence_failures[@]}"
validation_bypass_rows="${#validation_bypass_failures[@]}"

write_report() {
  {
    echo "# RakNet Impairment Campaign Comparison"
    echo
    echo "- Baseline summary: \`$baseline_summary\`"
    echo "- Candidate summary: \`$candidate_summary\`"
    echo "- Raw comparison JSONL: \`$jsonl_path\` ($jsonl_retention_note)"
    echo "- Throughput regression threshold: \`$throughput_regression_pct%\`"
    echo "- p99 latency regression threshold: \`$latency_regression_pct%\`"
    echo "- Queue regression threshold: \`$queue_regression_pct%\`"
    echo "- Allow failed summary: \`$allow_failed_summary\`"
    echo "- Allow missing netem evidence: \`$allow_missing_netem_evidence\`"
    echo "- Allow validation bypasses: \`$allow_validation_bypasses\`"
    echo
    echo "| Result | Count |"
    echo "| --- | ---: |"
    echo "| Total rows | $total_rows |"
    echo "| OK | $ok_rows |"
    echo "| Regressions | $regression_rows |"
    echo "| Missing candidate rows | $missing_rows |"
    echo "| Extra candidate rows | $extra_rows |"
    echo "| Failed campaign summaries | ${#summary_failures[@]} |"
    echo "| Missing netem-evidence policy | ${#netem_evidence_failures[@]} |"
    echo "| Validation bypass summaries | ${#validation_bypass_failures[@]} |"
    echo
    if [[ "${#summary_failures[@]}" -gt 0 ]]; then
      echo "## Summary Failures"
      echo
      echo "| Input | Summary | Issues |"
      echo "| --- | --- | ---: |"
      for failure in "${summary_failures[@]}"; do
        failure_label="${failure%%:*}"
        failure_path="${failure#*:}"
        echo "| $failure_label | \`$failure_path\` | $(jq -r '.issues | length' "$failure_path") |"
      done
      echo
    fi
    if [[ "${#netem_evidence_failures[@]}" -gt 0 ]]; then
      echo "## Netem Evidence Policy"
      echo
      echo "| Input | Summary |"
      echo "| --- | --- |"
      for failure in "${netem_evidence_failures[@]}"; do
        failure_label="${failure%%:*}"
        failure_path="${failure#*:}"
        echo "| $failure_label | \`$failure_path\` |"
      done
      echo
    fi
    if [[ "${#validation_bypass_failures[@]}" -gt 0 ]]; then
      echo "## Validation Bypasses"
      echo
      echo "| Input | Summary | Bypass flags |"
      echo "| --- | --- | --- |"
      for failure in "${validation_bypass_failures[@]}"; do
        failure_label="${failure%%:*}"
        failure_path="${failure#*:}"
        flags="$(jq -r '
          (
            [if .allowValidationBypasses == true then "allowValidationBypasses" else empty end]
            + [(.profiles // [])[] | (.validation.bypassFlags // [])[]]
          )
          | unique
          | join(",")
        ' "$failure_path")"
        echo "| $failure_label | \`$failure_path\` | \`$flags\` |"
      done
      echo
    fi
    echo "## Rows"
    echo
    echo "| Status | Kind | Profile | Network | Case | Benchmark | Batch shape | Delivered Gbps | Delta | p99 RTT ms | Delta | Max queue | Delta | Healthy fairness delta | Reasons |"
    echo "| --- | --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
    jq -r '
      def fmt($value):
        if $value == null then "n/a"
        elif ($value | type) == "number" and $value != 0 and (($value | fabs) < 1) then (($value * 1000000 | round) / 1000000 | tostring)
        elif ($value | type) == "number" then (($value * 1000 | round) / 1000 | tostring)
        else ($value | tostring)
        end;
      def pct($value): if $value == null then "n/a" else (((($value * 100) | round) / 100) | tostring) + "%" end;
      def side($row; $field): if $row == null then "n/a" else fmt($row[$field]) end;
      def batch_shape($row):
        if $row == null or ($row.kind // "") != "contention" then "n/a"
        else ((($row.batchIntervalMillis // 0) | tostring) + "ms/" + (($row.logicalPacketsPerBatch // 1) | tostring) + "lp/" + (($row.batchGroups // 1) | tostring) + "g")
        end;
      [
        "`" + .status + "`",
        "`" + .kind + "`",
        "`" + .profile + "`",
        "`" + (.network // "-") + "`",
        "`" + (.case // "-") + "`",
        "`" + (.benchmarkName // "-") + "`",
        (batch_shape(.candidate) + " / " + batch_shape(.baseline)),
        (side(.candidate; "deliveredGbps") + " / " + side(.baseline; "deliveredGbps")),
        pct(.deltas.deliveredGbpsPct),
        (side(.candidate; "probeRttP99Millis") + " / " + side(.baseline; "probeRttP99Millis")),
        pct(.deltas.probeRttP99MillisPct),
        (side(.candidate; "maxQueuedBytes") + " / " + side(.baseline; "maxQueuedBytes")),
        pct(.deltas.maxQueuedBytesPct),
        fmt(.deltas.healthyFairnessIndex),
        "`" + ((.statusReasons // []) | join(",")) + "`"
      ] | @tsv
    ' "$jsonl_path" | while IFS=$'\t' read -r status kind profile network case_name benchmark batch_shape delivered delivered_delta p99 p99_delta queue queue_delta fairness_delta reasons; do
      echo "| $status | $kind | $profile | $network | $case_name | $benchmark | $batch_shape | $delivered | $delivered_delta | $p99 | $p99_delta | $queue | $queue_delta | $fairness_delta | $reasons |"
    done
    echo
    if [[ "$failure_rows" -gt 0 || "$summary_failure_rows" -gt 0 || "$netem_failure_rows" -gt 0 || "$validation_bypass_rows" -gt 0 ]]; then
      echo "Comparison failed: $regression_rows regression row(s), $missing_rows missing candidate row(s), $summary_failure_rows failed campaign summary input(s), $netem_failure_rows netem evidence policy issue(s), $validation_bypass_rows validation bypass summary input(s)."
    else
      echo "Comparison passed."
    fi
  }
}

if [[ -n "$report_path" ]]; then
  if [[ "$report_path" != /* ]]; then
    report_path="$repo_root/$report_path"
  fi
  mkdir -p "$(dirname "$report_path")"
  write_report >"$report_path"
  echo "Impairment comparison report: $report_path"
  echo "Impairment comparison JSONL: $jsonl_path"
else
  write_report
fi

if [[ "$failure_rows" -gt 0 || "$summary_failure_rows" -gt 0 || "$netem_failure_rows" -gt 0 || "$validation_bypass_rows" -gt 0 ]]; then
  exit 1
fi
