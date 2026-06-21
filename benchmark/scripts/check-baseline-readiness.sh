#!/usr/bin/env bash
set -euo pipefail

lab_baseline="benchmark/build/benchmark-baselines/latest"
impairment_baseline="benchmark/build/benchmark-baselines/latest-impairment"
out_dir=""
expected_impairment_profiles="perfect,near-loss,regional-loss,poor,severe"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/check-baseline-readiness.sh [options]

Checks whether promoted benchmark artifacts are ready to be used as the
baseline of record for performance engineering comparisons.

Options:
  --lab-baseline PATH              Promoted perfect-network baseline directory. Default: benchmark/build/benchmark-baselines/latest.
  --impairment-baseline PATH       Promoted impairment campaign baseline directory. Default: benchmark/build/benchmark-baselines/latest-impairment.
  --expected-impairment-profiles CSV Required impairment profiles. Default: perfect,near-loss,regional-loss,poor,severe.
  --out DIR                        Output directory. Default: directory containing the lab baseline, or benchmark/build/benchmark-results/baseline-readiness.
  --help                           Show this help.

Outputs:
  readiness.json                   Machine-readable readiness result.
  readiness.md                     Human-readable readiness report.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lab-baseline)
      lab_baseline="$2"
      shift 2
      ;;
    --impairment-baseline)
      impairment_baseline="$2"
      shift 2
      ;;
    --expected-impairment-profiles)
      expected_impairment_profiles="$2"
      shift 2
      ;;
    --out)
      out_dir="$2"
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

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to check baseline readiness" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

resolve_path() {
  local path="$1"
  if [[ "$path" == /* ]]; then
    printf '%s\n' "$path"
  else
    printf '%s\n' "$repo_root/$path"
  fi
}

lab_baseline="$(resolve_path "$lab_baseline")"
impairment_baseline="$(resolve_path "$impairment_baseline")"
if [[ -z "$out_dir" ]]; then
  if [[ -d "$lab_baseline" ]]; then
    out_dir="$lab_baseline/readiness"
  else
    out_dir="$repo_root/benchmark/build/benchmark-results/baseline-readiness"
  fi
elif [[ "$out_dir" != /* ]]; then
  out_dir="$repo_root/$out_dir"
fi
mkdir -p "$out_dir"

readiness_json="$out_dir/readiness.json"
readiness_md="$out_dir/readiness.md"
issues_jsonl="$(mktemp)"
trap 'rm -f "$issues_jsonl"' EXIT
: >"$issues_jsonl"

append_issue() {
  local code="$1"
  local component="$2"
  local message="$3"
  local extra="${4:-}"
  if [[ -z "$extra" ]]; then
    extra="{}"
  fi
  jq -n \
    --arg code "$code" \
    --arg component "$component" \
    --arg message "$message" \
    --argjson extra "$extra" \
    '{code:$code,component:$component,message:$message} + $extra' >>"$issues_jsonl"
}

csv_json_array() {
  printf '%s\n' "$1" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | jq -R -s 'split("\n") | map(select(length > 0))'
}

lab_manifest="$lab_baseline/baseline-manifest.json"
lab_validation="$lab_baseline/validation.json"
lab_aggregate="$lab_baseline/suite-aggregate.jsonl"
lab_capacity="$lab_baseline/bandwidth-capacity.jsonl"

if [[ ! -s "$lab_manifest" ]]; then
  append_issue "missing-lab-baseline-manifest" "lab-baseline" "promoted lab baseline manifest is missing" "{\"path\":\"$lab_manifest\"}"
fi
if [[ ! -s "$lab_validation" ]]; then
  append_issue "missing-lab-validation" "lab-baseline" "promoted lab baseline validation.json is missing" "{\"path\":\"$lab_validation\"}"
fi
if [[ ! -s "$lab_aggregate" ]]; then
  append_issue "missing-lab-aggregate" "lab-baseline" "promoted lab baseline suite-aggregate.jsonl is missing" "{\"path\":\"$lab_aggregate\"}"
fi
if [[ ! -s "$lab_capacity" ]]; then
  append_issue "missing-lab-capacity" "lab-baseline" "promoted lab baseline bandwidth-capacity.jsonl is missing" "{\"path\":\"$lab_capacity\"}"
fi

if [[ -s "$lab_manifest" ]]; then
  if ! jq -e '.baselineKind == "raknet-lab-baseline"' "$lab_manifest" >/dev/null; then
    append_issue "invalid-lab-baseline-kind" "lab-baseline" "promoted lab baseline has an unexpected baselineKind" "{\"path\":\"$lab_manifest\"}"
  fi
fi

if [[ -s "$lab_validation" ]]; then
  if ! jq -e '.passed == true' "$lab_validation" >/dev/null; then
    append_issue "failed-lab-validation" "lab-baseline" "promoted lab baseline validation did not pass" "{\"path\":\"$lab_validation\"}"
  fi
  if ! jq -e '.distinctHostnameCount >= 2' "$lab_validation" >/dev/null; then
    append_issue "lab-not-separate-hosts" "lab-baseline" "lab baseline does not prove at least two distinct hostnames" "{\"path\":\"$lab_validation\"}"
  fi
  if ! jq -e '.hostReportCount >= 2' "$lab_validation" >/dev/null; then
    append_issue "lab-missing-host-reports" "lab-baseline" "lab baseline does not include at least two host reports" "{\"path\":\"$lab_validation\"}"
  fi
  for scenario in curve multi-client-fanout fairness disappearing-clients; do
    if ! jq -e --arg scenario "$scenario" '(.scenarioCounts[$scenario] // 0) > 0' "$lab_validation" >/dev/null; then
      append_issue "lab-missing-scenario" "lab-baseline" "lab baseline is missing a required scenario family" "{\"scenario\":\"$scenario\"}"
    fi
  done
  if ! jq -e '.capacityRowCount > 0' "$lab_validation" >/dev/null; then
    append_issue "lab-missing-capacity-rows" "lab-baseline" "lab baseline has no capacity selector rows" "{\"path\":\"$lab_validation\"}"
  fi
fi

if [[ -s "$lab_capacity" ]] && ! jq -s 'all(.[]; (.selected // false) == true)' "$lab_capacity" >/dev/null; then
  append_issue "lab-unselected-capacity" "lab-baseline" "one or more lab capacity groups has no selected stable candidate" "{\"path\":\"$lab_capacity\"}"
fi

impairment_manifest="$impairment_baseline/impairment-baseline-manifest.json"
impairment_summary="$impairment_baseline/impairment-summary.json"

if [[ ! -s "$impairment_manifest" ]]; then
  append_issue "missing-impairment-baseline-manifest" "impairment-baseline" "promoted impairment baseline manifest is missing" "{\"path\":\"$impairment_manifest\"}"
fi
if [[ ! -s "$impairment_summary" ]]; then
  append_issue "missing-impairment-summary" "impairment-baseline" "promoted impairment baseline summary is missing" "{\"path\":\"$impairment_summary\"}"
fi

if [[ -s "$impairment_manifest" ]]; then
  if ! jq -e '.baselineKind == "raknet-lab-impairment-campaign"' "$impairment_manifest" >/dev/null; then
    append_issue "invalid-impairment-baseline-kind" "impairment-baseline" "promoted impairment baseline has an unexpected baselineKind" "{\"path\":\"$impairment_manifest\"}"
  fi
fi

if [[ -s "$impairment_summary" ]]; then
  if ! jq -e '.passed == true' "$impairment_summary" >/dev/null; then
    append_issue "failed-impairment-summary" "impairment-baseline" "impairment campaign summary did not pass" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.requireNetemEvidence == true' "$impairment_summary" >/dev/null; then
    append_issue "impairment-netem-evidence-not-required" "impairment-baseline" "impairment campaign summary was generated without required netem evidence" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.validationPassedCount == .profileCount and .profileCount > 0' "$impairment_summary" >/dev/null; then
    append_issue "impairment-profile-validation-incomplete" "impairment-baseline" "not every impairment profile has passing validation" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.netemStatusEvidenceCount >= .profileCount and .profileCount > 0' "$impairment_summary" >/dev/null; then
    append_issue "impairment-missing-netem-status" "impairment-baseline" "not every impairment profile has netem status evidence" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.aggregateRowCount > 0 and .capacityRowCount > 0' "$impairment_summary" >/dev/null; then
    append_issue "impairment-missing-comparable-rows" "impairment-baseline" "impairment campaign has no comparable aggregate or capacity rows" "{\"path\":\"$impairment_summary\"}"
  fi
  expected_profiles_json="$(csv_json_array "$expected_impairment_profiles")"
  while IFS= read -r missing_profile; do
    [[ -z "$missing_profile" ]] && continue
    append_issue "impairment-missing-profile" "impairment-baseline" "expected impairment profile is missing" "{\"profile\":\"$missing_profile\"}"
  done < <(jq -r --argjson expected "$expected_profiles_json" '
    ([.profiles[].profile] | unique) as $actual
    | $expected[] as $profile
    | select(($actual | index($profile)) == null)
    | $profile
  ' "$impairment_summary")
fi

issues_array="$(jq -s '.' "$issues_jsonl")"

jq -n \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg labBaseline "$lab_baseline" \
  --arg impairmentBaseline "$impairment_baseline" \
  --argjson expectedImpairmentProfiles "$(csv_json_array "$expected_impairment_profiles")" \
  --argjson issues "$issues_array" \
  --slurpfile labValidation "$([[ -s "$lab_validation" ]] && printf '%s' "$lab_validation" || printf '%s' /dev/null)" \
  --slurpfile impairmentSummary "$([[ -s "$impairment_summary" ]] && printf '%s' "$impairment_summary" || printf '%s' /dev/null)" \
  '{
    checkedAt: $checkedAt,
    ready: (($issues | length) == 0),
    labBaseline: {
      path: $labBaseline,
      validation: ($labValidation[0] // null)
    },
    impairmentBaseline: {
      path: $impairmentBaseline,
      summary: ($impairmentSummary[0] // null)
    },
    expectedImpairmentProfiles: $expectedImpairmentProfiles,
    issues: $issues
  }' >"$readiness_json"

{
  echo "# Benchmark Baseline Readiness"
  echo
  echo "- Checked: \`$(jq -r '.checkedAt' "$readiness_json")\`"
  echo "- Result: \`$(jq -r 'if .ready then "ready" else "not-ready" end' "$readiness_json")\`"
  echo "- Lab baseline: \`$lab_baseline\`"
  echo "- Impairment baseline: \`$impairment_baseline\`"
  echo
  echo "## Lab Baseline"
  echo
  if [[ -s "$lab_validation" ]]; then
    echo "- Validation: \`$(jq -r 'if .passed then "passed" else "failed" end' "$lab_validation")\`"
    echo "- Rows: \`$(jq -r '.rowCount // 0' "$lab_validation")\`"
    echo "- Capacity rows: \`$(jq -r '.capacityRowCount // 0' "$lab_validation")\`"
    echo "- Host reports: \`$(jq -r '.hostReportCount // 0' "$lab_validation")\`"
    echo "- Distinct hostnames: \`$(jq -r '.distinctHostnameCount // 0' "$lab_validation")\`"
  else
    echo "No lab validation file found."
  fi
  echo
  echo "## Impairment Baseline"
  echo
  if [[ -s "$impairment_summary" ]]; then
    echo "- Summary: \`$(jq -r 'if .passed then "passed" else "failed" end' "$impairment_summary")\`"
    echo "- Profiles: \`$(jq -r '.profileCount // 0' "$impairment_summary")\`"
    echo "- Validation passed: \`$(jq -r '.validationPassedCount // 0' "$impairment_summary")\`"
    echo "- Aggregate rows: \`$(jq -r '.aggregateRowCount // 0' "$impairment_summary")\`"
    echo "- Capacity rows: \`$(jq -r '.capacityRowCount // 0' "$impairment_summary")\`"
    echo "- Netem status evidence files: \`$(jq -r '.netemStatusEvidenceCount // 0' "$impairment_summary")\`"
  else
    echo "No impairment summary found."
  fi
  echo
  echo "## Issues"
  echo
  if jq -e '.issues | length == 0' "$readiness_json" >/dev/null; then
    echo "No issues found."
  else
    echo "| Code | Component | Message |"
    echo "| --- | --- | --- |"
    jq -r '.issues[] | "| `\(.code)` | `\(.component)` | \(.message) |"' "$readiness_json"
  fi
} >"$readiness_md"

echo "Readiness JSON: $readiness_json"
echo "Readiness report: $readiness_md"

if jq -e '.ready == true' "$readiness_json" >/dev/null; then
  exit 0
fi
exit 1
