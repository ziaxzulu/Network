#!/usr/bin/env bash
set -euo pipefail

input_path=""
out_dir=""
min_iterations="3"
required_scenarios="curve,multi-client-fanout,fairness,disappearing-clients"
allow_unstable=false
allow_disconnects=false
allow_missing_capacity=false
allow_unselected_capacity=false

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/validate-lab-baseline.sh --input DIR|suite-aggregate.jsonl [options]

Validates that a completed lab baseline has the aggregate rows and stability
signals needed to become a baseline of record.

Options:
  --input PATH                     Lab artifact root, combined dir, or suite-aggregate.jsonl.
  --out DIR                        Output directory. Default: directory containing the resolved suite aggregate.
  --min-iterations N               Minimum measured iterations per aggregate row. Default: 3.
  --required-scenarios CSV         Required scenario families. Default: curve,multi-client-fanout,fairness,disappearing-clients.
  --allow-unstable                 Do not fail rows marked unstable.
  --allow-disconnects              Do not fail disconnects in curve or fanout rows.
  --allow-missing-capacity         Do not require bandwidth-capacity.jsonl.
  --allow-unselected-capacity      Do not fail capacity rows without a selected stable candidate.
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
trap 'rm -f "$suite_array" "$capacity_array"' EXIT

jq -s '.' "$suite_aggregate" >"$suite_array"
if [[ -n "$capacity_jsonl" ]]; then
  jq -s '.' "$capacity_jsonl" >"$capacity_array"
else
  printf '[]\n' >"$capacity_array"
fi

validation_json="$out_dir/validation.json"
validation_md="$out_dir/validation.md"
allow_unstable_json=false
allow_disconnects_json=false
allow_missing_capacity_json=false
allow_unselected_capacity_json=false
"$allow_unstable" && allow_unstable_json=true
"$allow_disconnects" && allow_disconnects_json=true
"$allow_missing_capacity" && allow_missing_capacity_json=true
"$allow_unselected_capacity" && allow_unselected_capacity_json=true

jq -n \
  --slurpfile rows "$suite_array" \
  --slurpfile capacities "$capacity_array" \
  --arg input "$input_path" \
  --arg suiteAggregate "$suite_aggregate" \
  --arg capacityFile "$capacity_jsonl" \
  --arg requiredScenarios "$required_scenarios" \
  --argjson minIterations "$min_iterations" \
  --argjson allowUnstable "$allow_unstable_json" \
  --argjson allowDisconnects "$allow_disconnects_json" \
  --argjson allowMissingCapacity "$allow_missing_capacity_json" \
  --argjson allowUnselectedCapacity "$allow_unselected_capacity_json" \
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

  ($rows[0] // []) as $aggregateRows |
  ($capacities[0] // []) as $capacityRows |
  ($requiredScenarios | split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0))) as $required |
  (reduce $aggregateRows[] as $row ({}; .[scenario($row)] = ((.[scenario($row)] // 0) + 1))) as $scenarioCounts |
  (
    []
    + (if ($aggregateRows | length) == 0 then
        [issue("missing-suite-aggregate-rows"; "suite-aggregate.jsonl has no rows"; null; {})]
      else [] end)
    + ($required | map(select(($scenarioCounts[.] // 0) == 0) | issue("missing-required-scenario"; "required scenario family is missing"; null; {requiredScenario: .})))
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
        + (if (($allowDisconnects | not) and ((scenario($row) == "curve") or (scenario($row) == "multi-client-fanout")) and n($row.disconnects) > 0) then
            [issue("unexpected-disconnects"; "curve and fanout rows must not have disconnects"; $row; {disconnects: n($row.disconnects)})]
          else [] end)
        + (if (scenario($row) == "disappearing-clients" and n($row.affectedClients) > 0 and (retry_pressure($row) | not)) then
            [issue("missing-retry-pressure"; "disappearing-client row has no retry-pressure signal"; $row; {})]
          else [] end)
      ) | add
    )
    + (if (($allowMissingCapacity | not) and ($capacityRows | length) == 0) then
        [issue("missing-capacity"; "bandwidth-capacity.jsonl is required for lab baselines"; null; {})]
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
    capacityFile: (if $capacityFile == "" then null else $capacityFile end),
    minIterations: $minIterations,
    requiredScenarios: $required,
    allowUnstable: $allowUnstable,
    allowDisconnects: $allowDisconnects,
    allowMissingCapacity: $allowMissingCapacity,
    allowUnselectedCapacity: $allowUnselectedCapacity,
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
  if [[ -n "$capacity_jsonl" ]]; then
    echo "- Capacity file: \`$capacity_jsonl\`"
  else
    echo "- Capacity file: missing"
  fi
  echo "- Rows: \`$(jq -r '.rowCount' "$validation_json")\`"
  echo "- Capacity rows: \`$(jq -r '.capacityRowCount' "$validation_json")\`"
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
