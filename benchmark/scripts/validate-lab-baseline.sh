#!/usr/bin/env bash
set -euo pipefail

input_path=""
out_dir=""
manifest_paths=()
min_iterations="3"
required_scenarios="curve,multi-client-fanout,fairness,disappearing-clients,resource-pack-transfer"
allow_unstable=false
allow_disconnects=false
allow_missing_capacity=false
allow_unselected_capacity=false
allow_missing_host_context=false
allow_missing_prereq_context=false
allow_loose_prereq_gates=false
min_host_reports="2"
min_distinct_hostnames="2"
min_prereq_reports="2"
min_prereq_distinct_hostnames="2"
min_healthy_fairness="0"
max_healthy_send_deliver_ratio="0"
max_affected_send_deliver_ratio="0"
max_contention_p99_ms="0"
min_contention_clients="0"
min_contention_target_client_mbps="0"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/validate-lab-baseline.sh --input DIR|suite-aggregate.jsonl [options]

Validates that a completed lab baseline has the aggregate rows and stability
signals needed to become a baseline of record.

Options:
  --input PATH                     Lab artifact root, combined dir, or suite-aggregate.jsonl.
  --out DIR                        Output directory. Default: directory containing the resolved suite aggregate.
  --manifest PATH                  Planned manifest.jsonl. May be repeated.
  --curve-manifest PATH            Alias for --manifest.
  --contention-manifest PATH       Alias for --manifest.
  --min-iterations N               Minimum measured iterations per aggregate row. Default: 3.
  --required-scenarios CSV         Required scenario families. Default: curve,multi-client-fanout,fairness,disappearing-clients,resource-pack-transfer.
  --allow-unstable                 Do not fail rows marked unstable.
  --allow-disconnects              Do not fail disconnects in curve or fanout rows.
  --allow-missing-capacity         Do not require bandwidth-capacity.jsonl.
  --allow-unselected-capacity      Do not fail capacity rows without a selected stable candidate.
  --allow-missing-host-context     Do not require topology.md and host reports.
  --allow-missing-prereq-context   Do not require ready host prereq reports.
  --allow-loose-prereq-gates       Do not require strict clock/MTU/CPU/no-netem prereq evidence. Smoke only.
  --min-host-reports N             Minimum host-report.md files when host context is required. Default: 2.
  --min-distinct-hostnames N        Minimum distinct captured hostnames when host context is required. Default: 2.
  --min-prereq-reports N           Minimum prereq.json files when prereq context is required. Default: 2.
  --min-prereq-distinct-hostnames N Minimum distinct prereq hostnames when prereq context is required. Default: 2.
  --min-healthy-fairness N         Fail fairness/disappearance rows below this healthy-client Jain fairness. Default: 0, disabled.
  --max-healthy-send-deliver-ratio N Fail fairness/disappearance rows above this healthy-client send/deliver ratio. Default: 0, disabled.
  --max-affected-send-deliver-ratio N Fail affected-client rows above this send/deliver ratio. Default: 0, disabled.
  --max-contention-p99-ms N        Fail fanout/fairness/disappearance/resource-pack rows above this p99 probe RTT. Default: 0, disabled.
  --min-contention-clients N       Fail fanout/fairness/disappearance/resource-pack rows below this client count. Default: 0, disabled.
  --min-contention-target-client-mbps N Fail fanout/fairness/disappearance rows below this per-client offered rate. Default: 0, disabled.
  --help                           Show this help.

Outputs:
  validation.json                  Machine-readable validation result.
  validation.md                    Human-readable validation report.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      input_path="$2"
      shift 2
      ;;
    --out)
      out_dir="$2"
      shift 2
      ;;
    --manifest|--curve-manifest|--contention-manifest)
      manifest_paths+=("$2")
      shift 2
      ;;
    --min-iterations)
      min_iterations="$2"
      shift 2
      ;;
    --required-scenarios)
      required_scenarios="$2"
      shift 2
      ;;
    --allow-unstable)
      allow_unstable=true
      shift
      ;;
    --allow-disconnects)
      allow_disconnects=true
      shift
      ;;
    --allow-missing-capacity)
      allow_missing_capacity=true
      shift
      ;;
    --allow-unselected-capacity)
      allow_unselected_capacity=true
      shift
      ;;
    --allow-missing-host-context)
      allow_missing_host_context=true
      shift
      ;;
    --allow-missing-prereq-context)
      allow_missing_prereq_context=true
      shift
      ;;
    --allow-loose-prereq-gates)
      allow_loose_prereq_gates=true
      shift
      ;;
    --min-host-reports)
      min_host_reports="$2"
      shift 2
      ;;
    --min-distinct-hostnames)
      min_distinct_hostnames="$2"
      shift 2
      ;;
    --min-prereq-reports)
      min_prereq_reports="$2"
      shift 2
      ;;
    --min-prereq-distinct-hostnames)
      min_prereq_distinct_hostnames="$2"
      shift 2
      ;;
    --min-healthy-fairness)
      min_healthy_fairness="$2"
      shift 2
      ;;
    --max-healthy-send-deliver-ratio)
      max_healthy_send_deliver_ratio="$2"
      shift 2
      ;;
    --max-affected-send-deliver-ratio)
      max_affected_send_deliver_ratio="$2"
      shift 2
      ;;
    --max-contention-p99-ms)
      max_contention_p99_ms="$2"
      shift 2
      ;;
    --min-contention-clients)
      min_contention_clients="$2"
      shift 2
      ;;
    --min-contention-target-client-mbps)
      min_contention_target_client_mbps="$2"
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

if [[ -z "$input_path" ]]; then
  usage >&2
  exit 2
fi
if [[ ! "$min_iterations" =~ ^[0-9]+$ || "$min_iterations" -le 0 ]]; then
  echo "--min-iterations must be a positive integer" >&2
  exit 2
fi
if [[ ! "$min_host_reports" =~ ^[0-9]+$ ]]; then
  echo "--min-host-reports must be a non-negative integer" >&2
  exit 2
fi
if [[ ! "$min_distinct_hostnames" =~ ^[0-9]+$ ]]; then
  echo "--min-distinct-hostnames must be a non-negative integer" >&2
  exit 2
fi
if [[ ! "$min_prereq_reports" =~ ^[0-9]+$ ]]; then
  echo "--min-prereq-reports must be a non-negative integer" >&2
  exit 2
fi
if [[ ! "$min_prereq_distinct_hostnames" =~ ^[0-9]+$ ]]; then
  echo "--min-prereq-distinct-hostnames must be a non-negative integer" >&2
  exit 2
fi
if [[ ! "$min_contention_clients" =~ ^[0-9]+$ ]]; then
  echo "--min-contention-clients must be a non-negative integer" >&2
  exit 2
fi
is_non_negative_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}
for value_name in min_healthy_fairness max_healthy_send_deliver_ratio max_affected_send_deliver_ratio max_contention_p99_ms min_contention_target_client_mbps; do
  if ! is_non_negative_number "${!value_name}"; then
    echo "--${value_name//_/-} must be a non-negative number: ${!value_name}" >&2
    exit 2
  fi
done
if [[ -z "$required_scenarios" || "$required_scenarios" == *, || "$required_scenarios" == ,* ]]; then
  echo "--required-scenarios must be a non-empty CSV value" >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required for lab baseline validation" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
if [[ "$input_path" != /* ]]; then
  input_path="$repo_root/$input_path"
fi
for i in "${!manifest_paths[@]}"; do
  if [[ "${manifest_paths[$i]}" != /* ]]; then
    manifest_paths[$i]="$repo_root/${manifest_paths[$i]}"
  fi
  if [[ ! -s "${manifest_paths[$i]}" ]]; then
    echo "manifest not found or empty: ${manifest_paths[$i]}" >&2
    exit 2
  fi
done

suite_aggregate=""
if [[ -f "$input_path" ]]; then
  suite_aggregate="$input_path"
elif [[ -s "$input_path/combined/suite-aggregate.jsonl" ]]; then
  suite_aggregate="$input_path/combined/suite-aggregate.jsonl"
elif [[ -s "$input_path/suite-aggregate.jsonl" ]]; then
  suite_aggregate="$input_path/suite-aggregate.jsonl"
else
  echo "suite-aggregate.jsonl not found in: $input_path" >&2
  exit 2
fi

suite_dir="$(cd "$(dirname "$suite_aggregate")" && pwd)"
artifact_root="$suite_dir"
if [[ "$(basename "$suite_dir")" == "combined" ]]; then
  artifact_root="$(cd "$suite_dir/.." && pwd)"
elif [[ -d "$input_path/combined" ]]; then
  artifact_root="$input_path"
fi

topology_file=""
if [[ -s "$artifact_root/topology.md" ]]; then
  topology_file="$artifact_root/topology.md"
fi
host_report_count="0"
host_report_paths=()
host_report_hostnames=()
if [[ -d "$artifact_root" ]]; then
  while IFS= read -r -d '' host_report; do
    host_report_paths+=("$host_report")
    hostname="$(sed -n 's/^- Hostname: `\(.*\)`/\1/p' "$host_report" | head -n 1)"
    if [[ -n "$hostname" ]]; then
      host_report_hostnames+=("$hostname")
    fi
  done < <(find "$artifact_root" -maxdepth 2 -type f -name host-report.md -print0 2>/dev/null | sort -z)
  host_report_count="${#host_report_paths[@]}"
fi
distinct_hostname_count="0"
hostnames_json="[]"
if [[ "${#host_report_hostnames[@]}" -gt 0 ]]; then
  distinct_hostname_count="$(printf '%s\n' "${host_report_hostnames[@]}" | sort -u | wc -l | tr -d ' ')"
  hostnames_json="$(printf '%s\n' "${host_report_hostnames[@]}" | sort -u | jq -R -s 'split("\n") | map(select(length > 0))')"
fi
prereq_report_count="0"
ready_prereq_report_count="0"
not_ready_prereq_report_count="0"
strict_prereq_report_count="0"
prereq_report_paths=()
prereq_report_hostnames=()
strict_prereq_report_hostnames=()
if [[ -d "$artifact_root" ]]; then
  while IFS= read -r -d '' prereq_report; do
    prereq_report_paths+=("$prereq_report")
    hostname="$(jq -r '.hostname // empty' "$prereq_report" 2>/dev/null || true)"
    if [[ -n "$hostname" ]]; then
      prereq_report_hostnames+=("$hostname")
    fi
    if jq -e '.ready == true' "$prereq_report" >/dev/null 2>&1; then
      ready_prereq_report_count=$((ready_prereq_report_count + 1))
    fi
    if jq -e '
      .ready == true
      and .requireClockSync == true
      and .requireNoNetem == true
      and ((.expectedMtu // null) | type == "number")
      and ((.interfaceMtu // null) | type == "number")
      and (.interfaceMtu == .expectedMtu)
      and ((.expectedMinCpus // null) | type == "number")
      and ((.cpuCount // null) | type == "number")
      and (.cpuCount >= .expectedMinCpus)
    ' "$prereq_report" >/dev/null 2>&1; then
      strict_prereq_report_count=$((strict_prereq_report_count + 1))
      if [[ -n "$hostname" ]]; then
        strict_prereq_report_hostnames+=("$hostname")
      fi
    fi
  done < <(find "$artifact_root" -maxdepth 2 -type f -name prereq.json -print0 2>/dev/null | sort -z)
  prereq_report_count="${#prereq_report_paths[@]}"
  not_ready_prereq_report_count=$((prereq_report_count - ready_prereq_report_count))
fi
prereq_distinct_hostname_count="0"
prereq_hostnames_json="[]"
strict_prereq_distinct_hostname_count="0"
strict_prereq_hostnames_json="[]"
if [[ "${#prereq_report_hostnames[@]}" -gt 0 ]]; then
  prereq_distinct_hostname_count="$(printf '%s\n' "${prereq_report_hostnames[@]}" | sort -u | wc -l | tr -d ' ')"
  prereq_hostnames_json="$(printf '%s\n' "${prereq_report_hostnames[@]}" | sort -u | jq -R -s 'split("\n") | map(select(length > 0))')"
fi
if [[ "${#strict_prereq_report_hostnames[@]}" -gt 0 ]]; then
  strict_prereq_distinct_hostname_count="$(printf '%s\n' "${strict_prereq_report_hostnames[@]}" | sort -u | wc -l | tr -d ' ')"
  strict_prereq_hostnames_json="$(printf '%s\n' "${strict_prereq_report_hostnames[@]}" | sort -u | jq -R -s 'split("\n") | map(select(length > 0))')"
fi
if [[ -z "$out_dir" ]]; then
  out_dir="$suite_dir"
elif [[ "$out_dir" != /* ]]; then
  out_dir="$repo_root/$out_dir"
fi
mkdir -p "$out_dir"

capacity_jsonl=""
for candidate in \
  "$suite_dir/bandwidth-capacity.jsonl" \
  "$(dirname "$suite_dir")/curve/merged/bandwidth-capacity.jsonl" \
  "$input_path/curve/merged/bandwidth-capacity.jsonl"; do
  if [[ -s "$candidate" ]]; then
    capacity_jsonl="$candidate"
    break
  fi
done

suite_array="$(mktemp)"
capacity_array="$(mktemp)"
manifest_array="$(mktemp)"
artifact_issues_jsonl="$(mktemp)"
artifact_issues_array="$(mktemp)"
trap 'rm -f "$suite_array" "$capacity_array" "$manifest_array" "$artifact_issues_jsonl" "$artifact_issues_array"' EXIT

jq -s '.' "$suite_aggregate" >"$suite_array"
if [[ -n "$capacity_jsonl" ]]; then
  jq -s '.' "$capacity_jsonl" >"$capacity_array"
else
  printf '[]\n' >"$capacity_array"
fi
if [[ "${#manifest_paths[@]}" -gt 0 ]]; then
  jq -s '.' "${manifest_paths[@]}" >"$manifest_array"
else
  printf '[]\n' >"$manifest_array"
fi

: >"$artifact_issues_jsonl"
append_artifact_issue() {
  local code="$1"
  local message="$2"
  local case_name="$3"
  local benchmark_name="$4"
  local artifact="$5"
  local extra="${6:-}"
  if [[ -z "$extra" ]]; then
    extra="{}"
  fi
  jq -n \
    --arg code "$code" \
    --arg message "$message" \
    --arg case "$case_name" \
    --arg benchmarkName "$benchmark_name" \
    --arg artifact "$artifact" \
    --argjson extra "$extra" \
    '{code:$code,message:$message,case:$case,benchmarkName:$benchmarkName,scenario:null,artifact:$artifact} + $extra' >>"$artifact_issues_jsonl"
}

while IFS=$'\t' read -r case_name benchmark_name artifact; do
  if [[ -z "$artifact" || "$artifact" == "null" ]]; then
    append_artifact_issue "missing-artifact-path" "aggregate row does not include an artifact path" "$case_name" "$benchmark_name" ""
    continue
  fi
  artifact_path="$artifact"
  if [[ "$artifact_path" != /* ]]; then
    artifact_path="$repo_root/$artifact_path"
  fi
  if [[ ! -d "$artifact_path" ]]; then
    append_artifact_issue "missing-artifact" "aggregate artifact directory is missing" "$case_name" "$benchmark_name" "$artifact_path"
    continue
  fi
  if [[ -s "$artifact_path/lab-summary.json" ]]; then
    missing_files=()
    for file in lab-summary.csv README.md suite-aggregate.jsonl; do
      [[ -s "$artifact_path/$file" ]] || missing_files+=("$file")
    done
    if [[ "${#missing_files[@]}" -gt 0 ]]; then
      missing_json="$(printf '%s\n' "${missing_files[@]}" | jq -R -s -c 'split("\n") | map(select(length > 0))')"
      append_artifact_issue "missing-merged-artifact-files" "merged artifact directory is incomplete" "$case_name" "$benchmark_name" "$artifact_path" "{\"missingFiles\":$missing_json}"
    fi
    warnings_json="$(jq -c '.warnings // []' "$artifact_path/lab-summary.json")"
    warning_count="$(jq 'length' <<<"$warnings_json")"
    if [[ "$warning_count" -gt 0 ]]; then
      append_artifact_issue "merge-warnings" "remote worker merge produced warnings" "$case_name" "$benchmark_name" "$artifact_path" "{\"warnings\":$warnings_json}"
    fi
    while IFS= read -r summary_path; do
      [[ -z "$summary_path" || "$summary_path" == "null" ]] && continue
      if [[ "$summary_path" != /* ]]; then
        summary_path="$repo_root/$summary_path"
      fi
      summary_dir="$(dirname "$summary_path")"
      raw_missing=()
      for file in summary.json timeseries.csv latency.hdr report.md; do
        [[ -s "$summary_dir/$file" ]] || raw_missing+=("$file")
      done
      if [[ "${#raw_missing[@]}" -gt 0 ]]; then
        raw_missing_json="$(printf '%s\n' "${raw_missing[@]}" | jq -R -s -c 'split("\n") | map(select(length > 0))')"
        append_artifact_issue "missing-raw-worker-artifact-files" "raw worker artifact directory is incomplete" "$case_name" "$benchmark_name" "$summary_dir" "{\"missingFiles\":$raw_missing_json}"
      fi
    done < <(jq -r '([.serverSummary] + (.receiverSummaries // []))[]?' "$artifact_path/lab-summary.json")
  elif [[ -s "$artifact_path/summary.json" ]]; then
    missing_files=()
    for file in timeseries.csv latency.hdr report.md; do
      [[ -s "$artifact_path/$file" ]] || missing_files+=("$file")
    done
    if [[ "${#missing_files[@]}" -gt 0 ]]; then
      missing_json="$(printf '%s\n' "${missing_files[@]}" | jq -R -s -c 'split("\n") | map(select(length > 0))')"
      append_artifact_issue "missing-raw-artifact-files" "benchmark artifact directory is incomplete" "$case_name" "$benchmark_name" "$artifact_path" "{\"missingFiles\":$missing_json}"
    fi
  else
    append_artifact_issue "missing-artifact-summary" "artifact directory has neither lab-summary.json nor summary.json" "$case_name" "$benchmark_name" "$artifact_path"
  fi
done < <(jq -r '.[] | [(.case // ""), (.benchmarkName // ""), (.artifact // "")] | @tsv' "$suite_array")

if [[ -s "$artifact_issues_jsonl" ]]; then
  jq -s '.' "$artifact_issues_jsonl" >"$artifact_issues_array"
else
  printf '[]\n' >"$artifact_issues_array"
fi

validation_json="$out_dir/validation.json"
validation_md="$out_dir/validation.md"
allow_unstable_json=false
allow_disconnects_json=false
allow_missing_capacity_json=false
allow_unselected_capacity_json=false
allow_missing_host_context_json=false
allow_missing_prereq_context_json=false
allow_loose_prereq_gates_json=false
"$allow_unstable" && allow_unstable_json=true
"$allow_disconnects" && allow_disconnects_json=true
"$allow_missing_capacity" && allow_missing_capacity_json=true
"$allow_unselected_capacity" && allow_unselected_capacity_json=true
"$allow_missing_host_context" && allow_missing_host_context_json=true
"$allow_missing_prereq_context" && allow_missing_prereq_context_json=true
"$allow_loose_prereq_gates" && allow_loose_prereq_gates_json=true

jq -n \
  --slurpfile rows "$suite_array" \
  --slurpfile capacities "$capacity_array" \
  --slurpfile manifests "$manifest_array" \
  --slurpfile artifactIssues "$artifact_issues_array" \
  --arg input "$input_path" \
  --arg suiteAggregate "$suite_aggregate" \
  --arg artifactRoot "$artifact_root" \
  --arg capacityFile "$capacity_jsonl" \
  --arg topologyFile "$topology_file" \
  --argjson manifestPaths "$(printf '%s\n' "${manifest_paths[@]}" | jq -R -s 'split("\n") | map(select(length > 0))')" \
  --arg requiredScenarios "$required_scenarios" \
  --argjson minIterations "$min_iterations" \
  --argjson minHostReports "$min_host_reports" \
  --argjson minDistinctHostnames "$min_distinct_hostnames" \
  --argjson hostReportCount "$host_report_count" \
  --argjson distinctHostnameCount "$distinct_hostname_count" \
  --argjson hostnames "$hostnames_json" \
  --argjson minPrereqReports "$min_prereq_reports" \
  --argjson minPrereqDistinctHostnames "$min_prereq_distinct_hostnames" \
  --argjson prereqReportCount "$prereq_report_count" \
  --argjson readyPrereqReportCount "$ready_prereq_report_count" \
  --argjson notReadyPrereqReportCount "$not_ready_prereq_report_count" \
  --argjson strictPrereqReportCount "$strict_prereq_report_count" \
  --argjson prereqDistinctHostnameCount "$prereq_distinct_hostname_count" \
  --argjson prereqHostnames "$prereq_hostnames_json" \
  --argjson strictPrereqDistinctHostnameCount "$strict_prereq_distinct_hostname_count" \
  --argjson strictPrereqHostnames "$strict_prereq_hostnames_json" \
  --argjson minHealthyFairness "$min_healthy_fairness" \
  --argjson maxHealthySendDeliverRatio "$max_healthy_send_deliver_ratio" \
  --argjson maxAffectedSendDeliverRatio "$max_affected_send_deliver_ratio" \
  --argjson maxContentionP99Millis "$max_contention_p99_ms" \
  --argjson minContentionClients "$min_contention_clients" \
  --argjson minContentionTargetClientMbps "$min_contention_target_client_mbps" \
  --argjson allowUnstable "$allow_unstable_json" \
  --argjson allowDisconnects "$allow_disconnects_json" \
  --argjson allowMissingCapacity "$allow_missing_capacity_json" \
  --argjson allowUnselectedCapacity "$allow_unselected_capacity_json" \
  --argjson allowMissingHostContext "$allow_missing_host_context_json" \
  --argjson allowMissingPrereqContext "$allow_missing_prereq_context_json" \
  --argjson allowLoosePrereqGates "$allow_loose_prereq_gates_json" \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
  def n($value): ($value // 0) | tonumber;
  def scenario($row):
    if (($row.benchmarkName // "") | startswith("curve-")) then "curve"
    else ($row.benchmarkName // "unknown")
    end;
  def issue($code; $message; $row; $extra):
    {
      code: $code,
      message: $message,
      case: (if $row == null then null else ($row.case // null) end),
      benchmarkName: (if $row == null then null else ($row.benchmarkName // null) end),
      scenario: (if $row == null then null else scenario($row) end)
    } + $extra;
  def required_fields:
    [
      "case",
      "benchmarkName",
      "measuredIterations",
      "clients",
      "payloadSize",
      "reliability",
      "targetClientMbps",
      "deliveredGbps",
      "probeRttP99Millis",
      "deliveredGbpsSpreadPct",
      "probeRttP99MillisSpreadPct",
      "maxQueuedBytes",
      "sentToDeliveredBytesRatio",
      "serverDatagramsOutPerSecond",
      "staleDatagramsPerSecond",
      "nackOutPerSecond",
      "fairnessIndex",
      "healthyFairnessIndex",
      "affectedFairnessIndex",
      "disconnects",
      "unstable",
      "unstableReasons",
      "artifact"
    ];
  def retry_pressure($row):
    (
      n($row.staleDatagrams) +
      n($row.nackIn) +
      n($row.nackOut) +
      n($row.blackholedDatagramsIn) +
      n($row.blackholedDatagramsOut)
    ) > 0
    or n($row.staleDatagramsPerSecond) > 0
    or n($row.nackOutPerSecond) > 0
    or n($row.maxQueuedBytes) > 0
    or (n($row.affectedSentToDeliveredBytesRatio) > (n($row.healthySentToDeliveredBytesRatio) * 1.05));
  def contention_scenario($row):
    (scenario($row) == "multi-client-fanout")
    or (scenario($row) == "fairness")
    or (scenario($row) == "disappearing-clients")
    or (scenario($row) == "resource-pack-transfer");
  def rate_limited_contention_scenario($row):
    (scenario($row) == "multi-client-fanout")
    or (scenario($row) == "fairness")
    or (scenario($row) == "disappearing-clients");
  def affected_scenario($row):
    (scenario($row) == "fairness")
    or (scenario($row) == "disappearing-clients");

  ($rows[0] // []) as $aggregateRows |
  ($capacities[0] // []) as $capacityRows |
  ($manifests[0] // []) as $manifestRows |
  ($artifactIssues[0] // []) as $artifactIssueRows |
  ($requiredScenarios | split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0))) as $required |
  (reduce $aggregateRows[] as $row ({}; .[scenario($row)] = ((.[scenario($row)] // 0) + 1))) as $scenarioCounts |
  ($manifestRows | map(select((.benchmarkName // "") | startswith("curve-")) | .case) | unique) as $plannedCurveCases |
  (
    []
    + $artifactIssueRows
    + (if (($allowMissingHostContext | not) and ($topologyFile == "")) then
        [issue("missing-topology"; "topology.md is required for lab baselines"; null; {artifactRoot: $artifactRoot})]
      else [] end)
    + (if (($allowMissingHostContext | not) and ($hostReportCount < $minHostReports)) then
        [issue("missing-host-reports"; "not enough host reports were captured"; null; {artifactRoot: $artifactRoot, hostReportCount: $hostReportCount, minHostReports: $minHostReports})]
      else [] end)
    + (if (($allowMissingHostContext | not) and ($distinctHostnameCount < $minDistinctHostnames)) then
        [issue("missing-distinct-hosts"; "not enough distinct hostnames were captured"; null; {artifactRoot: $artifactRoot, hostnames: $hostnames, distinctHostnameCount: $distinctHostnameCount, minDistinctHostnames: $minDistinctHostnames})]
      else [] end)
    + (if (($allowMissingPrereqContext | not) and ($prereqReportCount < $minPrereqReports)) then
        [issue("missing-prereq-reports"; "not enough host prerequisite reports were captured"; null; {artifactRoot: $artifactRoot, prereqReportCount: $prereqReportCount, minPrereqReports: $minPrereqReports})]
      else [] end)
    + (if (($allowMissingPrereqContext | not) and ($readyPrereqReportCount < $minPrereqReports)) then
        [issue("not-enough-ready-prereq-reports"; "not enough host prerequisite reports were ready"; null; {artifactRoot: $artifactRoot, readyPrereqReportCount: $readyPrereqReportCount, minPrereqReports: $minPrereqReports})]
      else [] end)
    + (if (($allowMissingPrereqContext | not) and ($notReadyPrereqReportCount > 0)) then
        [issue("not-ready-prereq-reports"; "one or more host prerequisite reports were not ready"; null; {artifactRoot: $artifactRoot, notReadyPrereqReportCount: $notReadyPrereqReportCount})]
      else [] end)
    + (if (($allowMissingPrereqContext | not) and ($prereqDistinctHostnameCount < $minPrereqDistinctHostnames)) then
        [issue("missing-prereq-distinct-hosts"; "not enough distinct prerequisite hostnames were captured"; null; {artifactRoot: $artifactRoot, prereqHostnames: $prereqHostnames, prereqDistinctHostnameCount: $prereqDistinctHostnameCount, minPrereqDistinctHostnames: $minPrereqDistinctHostnames})]
      else [] end)
    + (if (($allowMissingPrereqContext | not) and ($allowLoosePrereqGates | not) and ($strictPrereqReportCount < $minPrereqReports)) then
        [issue("missing-strict-prereq-gates"; "not enough prerequisite reports prove strict clock, MTU, CPU-count, and no-netem gates"; null; {artifactRoot: $artifactRoot, strictPrereqReportCount: $strictPrereqReportCount, minPrereqReports: $minPrereqReports})]
      else [] end)
    + (if (($allowMissingPrereqContext | not) and ($allowLoosePrereqGates | not) and ($strictPrereqDistinctHostnameCount < $minPrereqDistinctHostnames)) then
        [issue("missing-strict-prereq-distinct-hosts"; "not enough distinct prerequisite hostnames prove strict lab gates"; null; {artifactRoot: $artifactRoot, strictPrereqHostnames: $strictPrereqHostnames, strictPrereqDistinctHostnameCount: $strictPrereqDistinctHostnameCount, minPrereqDistinctHostnames: $minPrereqDistinctHostnames})]
      else [] end)
    + (if ($aggregateRows | length) == 0 then
        [issue("missing-suite-aggregate-rows"; "suite-aggregate.jsonl has no rows"; null; {})]
      else [] end)
    + ($required | map(select(($scenarioCounts[.] // 0) == 0) | issue("missing-required-scenario"; "required scenario family is missing"; null; {requiredScenario: .})))
    + (
      $manifestRows |
      map(. as $planned |
        if any($aggregateRows[]; (.case == ($planned.case // "") and .benchmarkName == ($planned.benchmarkName // ""))) then empty
        else issue("missing-planned-row"; "planned manifest row is missing from suite aggregate"; null; {
          plannedCase: ($planned.case // null),
          plannedBenchmarkName: ($planned.benchmarkName // null),
          plannedRunId: ($planned.runId // null)
        })
        end
      )
    )
    + (
      $manifestRows |
      map(. as $planned |
        ([$aggregateRows[] | select(.case == ($planned.case // "") and .benchmarkName == ($planned.benchmarkName // ""))] | .[0] // null) as $matched |
        if $matched == null then []
        else
          []
          + (if (($planned | has("clients")) and n($matched.clients) != n($planned.clients)) then
              [issue("planned-client-count-mismatch"; "aggregate row client count differs from planned manifest"; $matched; {plannedClients: n($planned.clients), actualClients: n($matched.clients)})]
            else [] end)
          + (if (($planned | has("payloadSize")) and n($matched.payloadSize) != n($planned.payloadSize)) then
              [issue("planned-payload-size-mismatch"; "aggregate row payload size differs from planned manifest"; $matched; {plannedPayloadSize: n($planned.payloadSize), actualPayloadSize: n($matched.payloadSize)})]
            else [] end)
          + (if (($planned | has("perClientMbps")) and ((n($matched.targetClientMbps) - n($planned.perClientMbps)) | fabs) > 0.000001) then
              [issue("planned-per-client-mbps-mismatch"; "aggregate row per-client target Mbps differs from planned manifest"; $matched; {plannedPerClientMbps: n($planned.perClientMbps), actualTargetClientMbps: n($matched.targetClientMbps)})]
            else [] end)
          + (if (($planned | has("configuredMaxQueuedBytes")) and n($matched.configuredMaxQueuedBytes) != n($planned.configuredMaxQueuedBytes)) then
              [issue("planned-max-queued-bytes-mismatch"; "aggregate row configured max queued bytes differs from planned manifest"; $matched; {plannedMaxQueuedBytes: n($planned.configuredMaxQueuedBytes), actualConfiguredMaxQueuedBytes: n($matched.configuredMaxQueuedBytes)})]
            else [] end)
        end
      ) | add // []
    )
    + (
      $aggregateRows |
      map(. as $row |
        (required_fields | map(. as $field | select((($row | has($field)) | not) or ($row[$field] == null)))) as $missing |
        []
        + (if ($missing | length) > 0 then
            [issue("missing-row-fields"; "aggregate row is missing required fields"; $row; {missingFields: $missing})]
          else [] end)
        + (if n($row.measuredIterations) < $minIterations then
            [issue("insufficient-iterations"; "aggregate row has fewer measured iterations than required"; $row; {measuredIterations: n($row.measuredIterations), minIterations: $minIterations})]
          else [] end)
        + (if (($allowUnstable | not) and (($row.unstable // false) == true)) then
            [issue("unstable-row"; "aggregate row is marked unstable"; $row; {unstableReasons: ($row.unstableReasons // [])})]
          else [] end)
        + (if n($row.deliveredGbps) <= 0 then
            [issue("zero-delivery"; "aggregate row has zero delivered throughput"; $row; {deliveredGbps: n($row.deliveredGbps)})]
          else [] end)
        + (if (($allowDisconnects | not) and ((scenario($row) == "curve") or (scenario($row) == "multi-client-fanout")) and n($row.disconnects) > 0) then
            [issue("unexpected-disconnects"; "curve and fanout rows must not have disconnects"; $row; {disconnects: n($row.disconnects)})]
          else [] end)
        + (if (scenario($row) == "disappearing-clients" and n($row.affectedClients) > 0 and (retry_pressure($row) | not)) then
            [issue("missing-retry-pressure"; "disappearing-client row has no retry-pressure signal"; $row; {})]
          else [] end)
        + (if ($minHealthyFairness > 0 and affected_scenario($row) and n($row.healthyFairnessIndex) < $minHealthyFairness) then
            [issue("healthy-fairness-below-threshold"; "healthy-client Jain fairness is below the configured threshold"; $row; {healthyFairnessIndex: n($row.healthyFairnessIndex), minHealthyFairness: $minHealthyFairness})]
          else [] end)
        + (if ($maxHealthySendDeliverRatio > 0 and affected_scenario($row) and n($row.healthySentToDeliveredBytesRatio) > $maxHealthySendDeliverRatio) then
            [issue("healthy-send-deliver-ratio-above-threshold"; "healthy-client send/deliver byte ratio is above the configured threshold"; $row; {healthySentToDeliveredBytesRatio: n($row.healthySentToDeliveredBytesRatio), maxHealthySendDeliverRatio: $maxHealthySendDeliverRatio})]
          else [] end)
        + (if ($maxAffectedSendDeliverRatio > 0 and affected_scenario($row) and n($row.affectedClients) > 0 and n($row.affectedSentToDeliveredBytesRatio) > $maxAffectedSendDeliverRatio) then
            [issue("affected-send-deliver-ratio-above-threshold"; "affected-client send/deliver byte ratio is above the configured threshold"; $row; {affectedSentToDeliveredBytesRatio: n($row.affectedSentToDeliveredBytesRatio), maxAffectedSendDeliverRatio: $maxAffectedSendDeliverRatio})]
          else [] end)
        + (if ($maxContentionP99Millis > 0 and contention_scenario($row) and n($row.probeRttP99Millis) > $maxContentionP99Millis) then
            [issue("contention-p99-above-threshold"; "contention p99 probe RTT is above the configured threshold"; $row; {probeRttP99Millis: n($row.probeRttP99Millis), maxContentionP99Millis: $maxContentionP99Millis})]
          else [] end)
        + (if ($minContentionClients > 0 and contention_scenario($row) and n($row.clients) < $minContentionClients) then
            [issue("contention-clients-below-threshold"; "contention row has fewer clients than the configured threshold"; $row; {clients: n($row.clients), minContentionClients: $minContentionClients})]
          else [] end)
        + (if ($minContentionTargetClientMbps > 0 and rate_limited_contention_scenario($row) and n($row.targetClientMbps) < $minContentionTargetClientMbps) then
            [issue("contention-target-client-mbps-below-threshold"; "contention row has a lower per-client target Mbps than the configured threshold"; $row; {targetClientMbps: n($row.targetClientMbps), minContentionTargetClientMbps: $minContentionTargetClientMbps})]
          else [] end)
      ) | add
    )
    + (if (($allowMissingCapacity | not) and ($capacityRows | length) == 0) then
        [issue("missing-capacity"; "bandwidth-capacity.jsonl is required for lab baselines"; null; {})]
      else [] end)
    + (if (($allowMissingCapacity | not) and ($plannedCurveCases | length) > 0) then
        ($plannedCurveCases | map(. as $plannedCase |
          if any($capacityRows[]; (.case // "") == $plannedCase) then empty
          else issue("missing-capacity-case"; "planned curve case has no capacity selection row"; null; {capacityCase: $plannedCase})
          end
        ))
      else [] end)
    + (if (($allowUnselectedCapacity | not) and ($capacityRows | length) > 0) then
        ($capacityRows | map(select((.selected // false) != true) | issue("unselected-capacity"; "capacity group has no selected stable candidate"; null; {
          capacityCase: (.case // null),
          eligibleCandidateCount: (.eligibleCandidateCount // null),
          bestObservedCandidate: (.bestObservedCandidate // null)
        })))
      else [] end)
  ) as $issues |
  {
    checkedAt: $checkedAt,
    input: $input,
    suiteAggregate: $suiteAggregate,
    artifactRoot: $artifactRoot,
    capacityFile: (if $capacityFile == "" then null else $capacityFile end),
    topologyFile: (if $topologyFile == "" then null else $topologyFile end),
    manifests: $manifestPaths,
    manifestRowCount: ($manifestRows | length),
    hostReportCount: $hostReportCount,
    minHostReports: $minHostReports,
    distinctHostnameCount: $distinctHostnameCount,
    minDistinctHostnames: $minDistinctHostnames,
    hostnames: $hostnames,
    prereqReportCount: $prereqReportCount,
    readyPrereqReportCount: $readyPrereqReportCount,
    notReadyPrereqReportCount: $notReadyPrereqReportCount,
    strictPrereqReportCount: $strictPrereqReportCount,
    minPrereqReports: $minPrereqReports,
    prereqDistinctHostnameCount: $prereqDistinctHostnameCount,
    strictPrereqDistinctHostnameCount: $strictPrereqDistinctHostnameCount,
    minPrereqDistinctHostnames: $minPrereqDistinctHostnames,
    prereqHostnames: $prereqHostnames,
    strictPrereqHostnames: $strictPrereqHostnames,
    minIterations: $minIterations,
    minHealthyFairness: $minHealthyFairness,
    maxHealthySendDeliverRatio: $maxHealthySendDeliverRatio,
    maxAffectedSendDeliverRatio: $maxAffectedSendDeliverRatio,
    maxContentionP99Millis: $maxContentionP99Millis,
    minContentionClients: $minContentionClients,
    minContentionTargetClientMbps: $minContentionTargetClientMbps,
    requiredScenarios: $required,
    allowUnstable: $allowUnstable,
    allowDisconnects: $allowDisconnects,
    allowMissingCapacity: $allowMissingCapacity,
    allowUnselectedCapacity: $allowUnselectedCapacity,
    allowMissingHostContext: $allowMissingHostContext,
    allowMissingPrereqContext: $allowMissingPrereqContext,
    allowLoosePrereqGates: $allowLoosePrereqGates,
    rowCount: ($aggregateRows | length),
    capacityRowCount: ($capacityRows | length),
    scenarioCounts: $scenarioCounts,
    passed: (($issues | length) == 0),
    issues: $issues
  }
  ' >"$validation_json"

{
  echo "# Lab Baseline Validation"
  echo
  echo "- Checked: \`$(jq -r '.checkedAt' "$validation_json")\`"
  echo "- Suite aggregate: \`$suite_aggregate\`"
  echo "- Artifact root: \`$artifact_root\`"
  if [[ -n "$topology_file" ]]; then
    echo "- Topology: \`$topology_file\`"
  else
    echo "- Topology: missing"
  fi
  echo "- Host reports: \`$host_report_count\`"
  echo "- Distinct hostnames: \`$(jq -r '.distinctHostnameCount' "$validation_json")\`"
  echo "- Prereq reports: \`$(jq -r '.prereqReportCount' "$validation_json")\`"
  echo "- Ready prereq reports: \`$(jq -r '.readyPrereqReportCount' "$validation_json")\`"
  echo "- Strict prereq reports: \`$(jq -r '.strictPrereqReportCount' "$validation_json")\`"
  echo "- Distinct prereq hostnames: \`$(jq -r '.prereqDistinctHostnameCount' "$validation_json")\`"
  echo "- Strict prereq hostnames: \`$(jq -r '.strictPrereqDistinctHostnameCount' "$validation_json")\`"
  echo "- Manifest rows: \`$(jq -r '.manifestRowCount' "$validation_json")\`"
  if [[ -n "$capacity_jsonl" ]]; then
    echo "- Capacity file: \`$capacity_jsonl\`"
  else
    echo "- Capacity file: missing"
  fi
  echo "- Rows: \`$(jq -r '.rowCount' "$validation_json")\`"
  echo "- Capacity rows: \`$(jq -r '.capacityRowCount' "$validation_json")\`"
  echo "- Minimum contention clients: \`$(jq -r '.minContentionClients' "$validation_json")\`"
  echo "- Minimum contention target/client Mbps: \`$(jq -r '.minContentionTargetClientMbps' "$validation_json")\`"
  echo "- Result: \`$(jq -r 'if .passed then "passed" else "failed" end' "$validation_json")\`"
  echo
  echo "## Scenario Counts"
  echo
  echo "| Scenario | Rows |"
  echo "| --- | ---: |"
  jq -r '.scenarioCounts | to_entries | sort_by(.key)[] | "| \(.key) | \(.value) |"' "$validation_json"
  echo
  echo "## Issues"
  echo
  if jq -e '.issues | length == 0' "$validation_json" >/dev/null; then
    echo "No issues found."
  else
    echo "| Code | Scenario | Case | Benchmark | Message |"
    echo "| --- | --- | --- | --- | --- |"
    jq -r '.issues[] | "| \(.code) | \(.scenario // .requiredScenario // "-") | \(.case // .capacityCase // "-") | \(.benchmarkName // "-") | \(.message) |"' "$validation_json"
  fi
} >"$validation_md"

echo "Validation JSON: $validation_json"
echo "Validation report: $validation_md"

if jq -e '.passed' "$validation_json" >/dev/null; then
  exit 0
fi
exit 1
