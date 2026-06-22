#!/usr/bin/env bash
set -euo pipefail

lab_baseline="benchmark/build/benchmark-baselines/latest"
impairment_baseline="benchmark/build/benchmark-baselines/latest-impairment"
out_dir=""
expected_impairment_profiles="perfect,near-loss,regional-loss,poor,severe"
required_curve_payload_sizes="64,256,512,1200,1340,1400,262144"
required_impairment_contention_scenarios="multi-client-fanout,fairness,disappearing-clients,batched-game-traffic,resource-pack-transfer"
required_min_contention_clients="500"
required_min_contention_target_client_mbps="5"
required_min_prereq_reports="2"
required_min_ready_prereq_reports="2"
required_min_prereq_distinct_hostnames="2"

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
  --required-curve-payload-sizes CSV Required perfect-network curve payload sizes. Default: 64,256,512,1200,1340,1400,262144.
  --required-impairment-contention-scenarios CSV Required contention scenarios per impairment profile. Default: multi-client-fanout,fairness,disappearing-clients,batched-game-traffic,resource-pack-transfer.
  --required-min-contention-clients N Required lab validation contention-client gate. Default: 500.
  --required-min-contention-target-client-mbps N Required lab validation per-client Mbps gate. Default: 5.
  --required-min-prereq-reports N Required lab prereq reports. Default: 2.
  --required-min-ready-prereq-reports N Required ready lab prereq reports. Default: 2.
  --required-min-prereq-distinct-hostnames N Required distinct lab prereq hostnames. Default: 2.
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
    --required-curve-payload-sizes)
      required_curve_payload_sizes="$2"
      shift 2
      ;;
    --required-impairment-contention-scenarios)
      required_impairment_contention_scenarios="$2"
      shift 2
      ;;
    --required-min-contention-clients)
      required_min_contention_clients="$2"
      shift 2
      ;;
    --required-min-contention-target-client-mbps)
      required_min_contention_target_client_mbps="$2"
      shift 2
      ;;
    --required-min-prereq-reports)
      required_min_prereq_reports="$2"
      shift 2
      ;;
    --required-min-ready-prereq-reports)
      required_min_ready_prereq_reports="$2"
      shift 2
      ;;
    --required-min-prereq-distinct-hostnames)
      required_min_prereq_distinct_hostnames="$2"
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
if ! [[ "$required_min_contention_clients" =~ ^[0-9]+$ ]]; then
  echo "--required-min-contention-clients must be a non-negative integer" >&2
  exit 2
fi
if ! [[ "$required_min_contention_target_client_mbps" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "--required-min-contention-target-client-mbps must be a non-negative number" >&2
  exit 2
fi
if ! [[ "$required_min_prereq_reports" =~ ^[0-9]+$ ]]; then
  echo "--required-min-prereq-reports must be a non-negative integer" >&2
  exit 2
fi
if ! [[ "$required_min_ready_prereq_reports" =~ ^[0-9]+$ ]]; then
  echo "--required-min-ready-prereq-reports must be a non-negative integer" >&2
  exit 2
fi
if ! [[ "$required_min_prereq_distinct_hostnames" =~ ^[0-9]+$ ]]; then
  echo "--required-min-prereq-distinct-hostnames must be a non-negative integer" >&2
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

csv_json_number_array() {
  printf '%s\n' "$1" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | jq -R -s 'split("\n") | map(select(length > 0) | tonumber)'
}

lab_manifest="$lab_baseline/baseline-manifest.json"
lab_validation="$lab_baseline/validation.json"
lab_aggregate="$lab_baseline/suite-aggregate.jsonl"
lab_capacity="$lab_baseline/bandwidth-capacity.jsonl"
required_curve_payloads_json="$(csv_json_number_array "$required_curve_payload_sizes")"
required_impairment_contention_json="$(csv_json_array "$required_impairment_contention_scenarios")"

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
  if jq -e '.allowValidationBypasses == true' "$lab_manifest" >/dev/null; then
    append_issue "lab-validation-bypasses-allowed" "lab-baseline" "promoted lab baseline allowed validation bypasses" "{\"path\":\"$lab_manifest\"}"
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
  if ! jq -e --argjson required "$required_min_prereq_reports" '(.prereqReportCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_reports" --argjson actual "$(jq -r '.prereqReportCount // 0' "$lab_validation")" '{requiredMinPrereqReports:$required,actualPrereqReportCount:$actual}')"
    append_issue "lab-missing-prereq-reports" "lab-baseline" "lab baseline does not include enough host prerequisite reports" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_ready_prereq_reports" '(.readyPrereqReportCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_ready_prereq_reports" --argjson actual "$(jq -r '.readyPrereqReportCount // 0' "$lab_validation")" '{requiredMinReadyPrereqReports:$required,actualReadyPrereqReportCount:$actual}')"
    append_issue "lab-prereq-not-ready" "lab-baseline" "lab baseline does not prove enough ready host prerequisite reports" "$extra"
  fi
  if ! jq -e '(.notReadyPrereqReportCount // 0) == 0' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson actual "$(jq -r '.notReadyPrereqReportCount // 0' "$lab_validation")" '{notReadyPrereqReportCount:$actual}')"
    append_issue "lab-prereq-report-failed" "lab-baseline" "one or more lab host prerequisite reports was not ready" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_distinct_hostnames" '(.prereqDistinctHostnameCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_distinct_hostnames" --argjson actual "$(jq -r '.prereqDistinctHostnameCount // 0' "$lab_validation")" '{requiredMinPrereqDistinctHostnames:$required,actualPrereqDistinctHostnameCount:$actual}')"
    append_issue "lab-prereq-not-separate-hosts" "lab-baseline" "lab baseline does not prove prerequisite checks from enough distinct hostnames" "$extra"
  fi
  if jq -e '.allowLoosePrereqGates == true' "$lab_validation" >/dev/null; then
    append_issue "lab-prereq-strict-gates-bypassed" "lab-baseline" "lab validation allowed loose prerequisite gates" "{\"path\":\"$lab_validation\"}"
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
  ' "$lab_validation")"
  if [[ -n "$validation_bypass_flags" ]]; then
    extra="$(jq -n --arg path "$lab_validation" --arg flags "$validation_bypass_flags" '{path:$path,bypassFlags:($flags | split(","))}')"
    append_issue "lab-validation-bypass-flags" "lab-baseline" "lab validation used baseline bypass flags" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_ready_prereq_reports" '(.strictPrereqReportCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_ready_prereq_reports" --argjson actual "$(jq -r '.strictPrereqReportCount // 0' "$lab_validation")" '{requiredMinStrictPrereqReports:$required,actualStrictPrereqReportCount:$actual}')"
    append_issue "lab-prereq-strict-gates-missing" "lab-baseline" "lab baseline does not prove enough strict clock, MTU, CPU-count, and no-netem prerequisite gates" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_distinct_hostnames" '(.strictPrereqDistinctHostnameCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_distinct_hostnames" --argjson actual "$(jq -r '.strictPrereqDistinctHostnameCount // 0' "$lab_validation")" '{requiredMinStrictPrereqDistinctHostnames:$required,actualStrictPrereqDistinctHostnameCount:$actual}')"
    append_issue "lab-prereq-strict-gates-not-separate-hosts" "lab-baseline" "lab baseline does not prove strict prerequisite gates from enough distinct hostnames" "$extra"
  fi
  for scenario in curve multi-client-fanout fairness disappearing-clients batched-game-traffic resource-pack-transfer; do
    if ! jq -e --arg scenario "$scenario" '(.scenarioCounts[$scenario] // 0) > 0' "$lab_validation" >/dev/null; then
      append_issue "lab-missing-scenario" "lab-baseline" "lab baseline is missing a required scenario family" "{\"scenario\":\"$scenario\"}"
    fi
  done
  if ! jq -e '.capacityRowCount > 0' "$lab_validation" >/dev/null; then
    append_issue "lab-missing-capacity-rows" "lab-baseline" "lab baseline has no capacity selector rows" "{\"path\":\"$lab_validation\"}"
  fi
  if ! jq -e --argjson required "$required_min_contention_clients" '(.minContentionClients // -1) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_contention_clients" --argjson actual "$(jq -r '.minContentionClients // -1' "$lab_validation")" '{requiredMinContentionClients:$required,actualMinContentionClients:$actual}')"
    append_issue "lab-contention-client-gate-too-low" "lab-baseline" "lab validation did not enforce the required contention client count" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_contention_target_client_mbps" '(.minContentionTargetClientMbps // -1) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_contention_target_client_mbps" --argjson actual "$(jq -r '.minContentionTargetClientMbps // -1' "$lab_validation")" '{requiredMinContentionTargetClientMbps:$required,actualMinContentionTargetClientMbps:$actual}')"
    append_issue "lab-contention-target-client-mbps-gate-too-low" "lab-baseline" "lab validation did not enforce the required contention per-client Mbps target" "$extra"
  fi
fi

if [[ -s "$lab_capacity" ]] && ! jq -s 'all(.[]; (.selected // false) == true)' "$lab_capacity" >/dev/null; then
  append_issue "lab-unselected-capacity" "lab-baseline" "one or more lab capacity groups has no selected stable candidate" "{\"path\":\"$lab_capacity\"}"
fi

if [[ -s "$lab_aggregate" ]]; then
  missing_curve_payloads="$(jq -r -s --argjson expected "$required_curve_payloads_json" '
    def is_curve:
      ((.scenario // "") == "curve")
      or ((.scenario // "") == "bandwidth-latency-curve")
      or ((.benchmarkName // "") == "bandwidth-latency-curve")
      or (((.benchmarkName // "") | startswith("curve-")))
      or (((.case // "") | contains("-curve")))
      or (((.case // "") | startswith("curve-")));
    ([.[] | select(is_curve) | (.payloadSize // empty | tonumber)] | unique) as $actual
    | $expected[] as $payload
    | select(($actual | index($payload)) == null)
    | $payload
  ' "$lab_aggregate")"
  while IFS= read -r missing_payload; do
    [[ -z "$missing_payload" ]] && continue
    append_issue "lab-missing-curve-payload" "lab-baseline" "lab baseline is missing a required bandwidth-curve payload size" "{\"payloadSize\":$missing_payload}"
  done <<<"$missing_curve_payloads"
fi

if [[ -s "$lab_capacity" ]]; then
  missing_capacity_payloads="$(jq -r -s --argjson expected "$required_curve_payloads_json" '
    ([.[] | select((.summaryKind // "") == "bandwidth-capacity") | (.payloadSize // empty | tonumber)] | unique) as $actual
    | $expected[] as $payload
    | select(($actual | index($payload)) == null)
    | $payload
  ' "$lab_capacity")"
  while IFS= read -r missing_payload; do
    [[ -z "$missing_payload" ]] && continue
    append_issue "lab-missing-capacity-payload" "lab-baseline" "lab baseline capacity selector is missing a required payload size" "{\"payloadSize\":$missing_payload}"
  done <<<"$missing_capacity_payloads"

  invalid_lab_capacity="$(jq -r -s '
    def n($value): ($value // 0) | tonumber;
    .[]
    | select((.summaryKind // "") == "bandwidth-capacity")
    | select((.selected // false) == true and (((.selectedCandidate.benchmarkName // "") == "") or n(.selectedCandidate.deliveredGbps) <= 0))
    | [(.case // ""), (.payloadSize // 0)] | @tsv
  ' "$lab_capacity")"
  while IFS=$'\t' read -r case_name payload_size; do
    [[ -z "$case_name" && -z "$payload_size" ]] && continue
    extra="$(jq -n --arg case "$case_name" --argjson payloadSize "${payload_size:-0}" '{case:$case,payloadSize:$payloadSize}')"
    append_issue "lab-invalid-selected-capacity" "lab-baseline" "lab baseline capacity selector has a selected row without a concrete positive-throughput selected candidate" "$extra"
  done <<<"$invalid_lab_capacity"
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
  if jq -e '.allowValidationBypasses == true' "$impairment_manifest" >/dev/null; then
    append_issue "impairment-validation-bypasses-allowed" "impairment-baseline" "promoted impairment baseline allowed validation bypasses" "{\"path\":\"$impairment_manifest\"}"
  fi
fi

if [[ -s "$impairment_summary" ]]; then
  if ! jq -e '.passed == true' "$impairment_summary" >/dev/null; then
    append_issue "failed-impairment-summary" "impairment-baseline" "impairment campaign summary did not pass" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.requireNetemEvidence == true' "$impairment_summary" >/dev/null; then
    append_issue "impairment-netem-evidence-not-required" "impairment-baseline" "impairment campaign summary was generated without required netem evidence" "{\"path\":\"$impairment_summary\"}"
  fi
  if jq -e '.allowValidationBypasses == true' "$impairment_summary" >/dev/null; then
    append_issue "impairment-validation-bypasses-allowed" "impairment-baseline" "impairment campaign summary allowed profile validation bypasses" "{\"path\":\"$impairment_summary\"}"
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
  missing_impairment_profiles="$(jq -r --argjson expected "$expected_profiles_json" '
    ([.profiles[].profile] | unique) as $actual
    | $expected[] as $profile
    | select(($actual | index($profile)) == null)
    | $profile
  ' "$impairment_summary")"
  while IFS= read -r missing_profile; do
    [[ -z "$missing_profile" ]] && continue
    append_issue "impairment-missing-profile" "impairment-baseline" "expected impairment profile is missing" "{\"profile\":\"$missing_profile\"}"
  done <<<"$missing_impairment_profiles"

  missing_impairment_payloads="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedPayloads "$required_curve_payloads_json" '
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | ([.capacity.rows[]? | (.payloadSize // empty | tonumber)] | unique) as $actual
    | $expectedPayloads[] as $payload
    | select(($actual | index($payload)) == null)
    | [$profile, $payload] | @tsv
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile payload_size; do
    [[ -z "$profile" || -z "$payload_size" ]] && continue
    extra="$(jq -n --arg profile "$profile" --argjson payloadSize "$payload_size" '{profile:$profile,payloadSize:$payloadSize}')"
    append_issue "impairment-missing-capacity-payload" "impairment-baseline" "impairment profile capacity selector is missing a required payload size" "$extra"
  done <<<"$missing_impairment_payloads"

  unselected_impairment_capacity="$(jq -r --argjson expectedProfiles "$expected_profiles_json" '
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | .capacity.rows[]?
    | select((.selected // false) != true)
    | [$profile, (.case // ""), (.payloadSize // 0)] | @tsv
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile case_name payload_size; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg case "$case_name" --argjson payloadSize "${payload_size:-0}" '{profile:$profile,case:$case,payloadSize:$payloadSize}')"
    append_issue "impairment-unselected-capacity" "impairment-baseline" "impairment profile has an unselected capacity row" "$extra"
  done <<<"$unselected_impairment_capacity"

  invalid_impairment_capacity="$(jq -r --argjson expectedProfiles "$expected_profiles_json" '
    def n($value): ($value // 0) | tonumber;
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | .capacity.rows[]?
    | select((.selected // false) == true and (((.selectedBenchmarkName // "") == "") or n(.selectedDeliveredGbps) <= 0))
    | [$profile, (.case // ""), (.payloadSize // 0)] | @tsv
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile case_name payload_size; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg case "$case_name" --argjson payloadSize "${payload_size:-0}" '{profile:$profile,case:$case,payloadSize:$payloadSize}')"
    append_issue "impairment-invalid-selected-capacity" "impairment-baseline" "impairment profile capacity selector has a selected row without a concrete positive-throughput selected candidate" "$extra"
  done <<<"$invalid_impairment_capacity"

  missing_impairment_contention="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedScenarios "$required_impairment_contention_json" '
    def scenario($row):
      if (($row.benchmarkName // "") | startswith("curve-")) then "curve"
      else ($row.benchmarkName // "unknown")
      end;
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | ([.aggregate.contentionRows[]? | scenario(.)] | unique) as $actual
    | $expectedScenarios[] as $scenario
    | select(($actual | index($scenario)) == null)
    | [$profile, $scenario] | @tsv
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile scenario; do
    [[ -z "$profile" || -z "$scenario" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg scenario "$scenario" '{profile:$profile,scenario:$scenario}')"
    append_issue "impairment-missing-contention-scenario" "impairment-baseline" "impairment profile is missing a required contention scenario" "$extra"
  done <<<"$missing_impairment_contention"
fi

issues_array="$(jq -s '.' "$issues_jsonl")"
next_actions_json="$(jq -s '
  def has_code($code): any(.[]; .code == $code);
  def has_any_code($codes): any(.[]; (.code as $code | ($codes | index($code)) != null));
  def has_prefix($prefix): any(.[]; (.code | startswith($prefix)));
  [
    if has_any_code([
      "missing-lab-baseline-manifest",
      "missing-lab-validation",
      "missing-lab-aggregate",
      "missing-lab-capacity",
      "invalid-lab-baseline-kind"
    ]) then {
      code: "promote-lab-baseline",
      title: "Promote the perfect-network lab baseline",
      detail: "Create a promoted lab baseline package after merged perfect-network artifacts exist.",
      command: "benchmark/scripts/promote-lab-baseline.sh --input <perfect-artifacts>/combined --manifest <curve-manifest.jsonl> --manifest <raised-curve-manifest.jsonl> --manifest <contention-manifest.jsonl>"
    } else empty end,
    if has_any_code([
      "failed-lab-validation",
      "lab-not-separate-hosts",
      "lab-missing-host-reports",
      "lab-missing-prereq-reports",
      "lab-prereq-not-ready",
      "lab-prereq-report-failed",
      "lab-prereq-not-separate-hosts",
      "lab-validation-bypasses-allowed",
      "lab-validation-bypass-flags",
      "lab-prereq-strict-gates-bypassed",
      "lab-prereq-strict-gates-missing",
      "lab-prereq-strict-gates-not-separate-hosts"
    ]) then {
      code: "fix-lab-evidence",
      title: "Fix perfect-network validation evidence",
      detail: "Capture topology, host reports, and strict ready prereq reports on the lab hosts, then rerun validation.",
      command: "benchmark/scripts/validate-lab-baseline.sh --input <perfect-artifacts>/combined --manifest <curve-manifest.jsonl> --manifest <raised-curve-manifest.jsonl> --manifest <contention-manifest.jsonl>"
    } else empty end,
    if has_prefix("lab-missing-") or has_any_code([
      "lab-contention-client-gate-too-low",
      "lab-contention-target-client-mbps-gate-too-low",
      "lab-unselected-capacity"
    ]) then {
      code: "rerun-perfect-baseline",
      title: "Rerun or repromote the perfect-network baseline",
      detail: "The promoted baseline does not match the required matrix, capacity selection, or contention gate.",
      command: "benchmark/scripts/prepare-lab-baseline-handoff.sh --server-host <server-ip> --interface <nic> --expect-mtu <mtu> --expect-min-cpus <min-cpus>"
    } else empty end,
    if has_any_code([
      "missing-impairment-baseline-manifest",
      "missing-impairment-summary",
      "invalid-impairment-baseline-kind"
    ]) then {
      code: "promote-impairment-baseline",
      title: "Promote the adverse-network impairment campaign",
      detail: "Create a promoted impairment package after campaign summary artifacts exist.",
      command: "benchmark/scripts/promote-lab-impairment.sh --input <impairment-artifacts>/campaign-summary"
    } else empty end,
    if has_prefix("impairment-") or has_any_code(["failed-impairment-summary"]) then {
      code: "rerun-impairment-campaign",
      title: "Fix or rerun the impairment campaign",
      detail: "The impairment package is missing required profiles, netem status evidence, validation, capacity, or contention rows.",
      command: "benchmark/scripts/plan-lab-impairment.sh --interface <nic> --target-host-role <receiver-role> --server-host <server-ip>"
    } else empty end
  ] | reduce .[] as $action ([]; if any(.[]; .code == $action.code) then . else . + [$action] end)
' "$issues_jsonl")"

jq -n \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg labBaseline "$lab_baseline" \
  --arg impairmentBaseline "$impairment_baseline" \
  --argjson expectedImpairmentProfiles "$(csv_json_array "$expected_impairment_profiles")" \
  --argjson requiredCurvePayloadSizes "$required_curve_payloads_json" \
  --argjson requiredImpairmentContentionScenarios "$required_impairment_contention_json" \
  --argjson requiredMinContentionClients "$required_min_contention_clients" \
  --argjson requiredMinContentionTargetClientMbps "$required_min_contention_target_client_mbps" \
  --argjson requiredMinPrereqReports "$required_min_prereq_reports" \
  --argjson requiredMinReadyPrereqReports "$required_min_ready_prereq_reports" \
  --argjson requiredMinPrereqDistinctHostnames "$required_min_prereq_distinct_hostnames" \
  --argjson issues "$issues_array" \
  --argjson nextActions "$next_actions_json" \
  --slurpfile labValidation "$([[ -s "$lab_validation" ]] && printf '%s' "$lab_validation" || printf '%s' /dev/null)" \
  --slurpfile impairmentSummary "$([[ -s "$impairment_summary" ]] && printf '%s' "$impairment_summary" || printf '%s' /dev/null)" \
  '{
    checkedAt: $checkedAt,
    ready: (($issues | length) == 0),
    issueCount: ($issues | length),
    labBaseline: {
      path: $labBaseline,
      validation: ($labValidation[0] // null)
    },
    impairmentBaseline: {
      path: $impairmentBaseline,
      summary: ($impairmentSummary[0] // null)
    },
    expectedImpairmentProfiles: $expectedImpairmentProfiles,
    requiredCurvePayloadSizes: $requiredCurvePayloadSizes,
    requiredImpairmentContentionScenarios: $requiredImpairmentContentionScenarios,
    requiredMinContentionClients: $requiredMinContentionClients,
    requiredMinContentionTargetClientMbps: $requiredMinContentionTargetClientMbps,
    requiredMinPrereqReports: $requiredMinPrereqReports,
    requiredMinReadyPrereqReports: $requiredMinReadyPrereqReports,
    requiredMinPrereqDistinctHostnames: $requiredMinPrereqDistinctHostnames,
    nextActions: $nextActions,
    issues: $issues
  }' >"$readiness_json"

{
  echo "# Benchmark Baseline Readiness"
  echo
  echo "- Checked: \`$(jq -r '.checkedAt' "$readiness_json")\`"
  echo "- Result: \`$(jq -r 'if .ready then "ready" else "not-ready" end' "$readiness_json")\`"
  echo "- Issues: \`$(jq -r '.issueCount' "$readiness_json")\`"
  echo "- Lab baseline: \`$lab_baseline\`"
  echo "- Impairment baseline: \`$impairment_baseline\`"
  echo "- Required curve payload sizes: \`$required_curve_payload_sizes\`"
  echo "- Required impairment contention scenarios: \`$required_impairment_contention_scenarios\`"
  echo "- Required minimum contention clients: \`$required_min_contention_clients\`"
  echo "- Required minimum contention target/client Mbps: \`$required_min_contention_target_client_mbps\`"
  echo "- Required prereq reports: \`$required_min_prereq_reports\`"
  echo "- Required ready prereq reports: \`$required_min_ready_prereq_reports\`"
  echo "- Required distinct prereq hostnames: \`$required_min_prereq_distinct_hostnames\`"
  echo
  echo "## Lab Baseline"
  echo
  if [[ -s "$lab_validation" ]]; then
    echo "- Validation: \`$(jq -r 'if .passed then "passed" else "failed" end' "$lab_validation")\`"
    echo "- Rows: \`$(jq -r '.rowCount // 0' "$lab_validation")\`"
    echo "- Capacity rows: \`$(jq -r '.capacityRowCount // 0' "$lab_validation")\`"
    echo "- Host reports: \`$(jq -r '.hostReportCount // 0' "$lab_validation")\`"
    echo "- Distinct hostnames: \`$(jq -r '.distinctHostnameCount // 0' "$lab_validation")\`"
    echo "- Prereq reports: \`$(jq -r '.prereqReportCount // 0' "$lab_validation")\`"
    echo "- Ready prereq reports: \`$(jq -r '.readyPrereqReportCount // 0' "$lab_validation")\`"
    echo "- Strict prereq reports: \`$(jq -r '.strictPrereqReportCount // 0' "$lab_validation")\`"
    echo "- Distinct prereq hostnames: \`$(jq -r '.prereqDistinctHostnameCount // 0' "$lab_validation")\`"
    echo "- Strict prereq hostnames: \`$(jq -r '.strictPrereqDistinctHostnameCount // 0' "$lab_validation")\`"
    echo "- Validated minimum contention clients: \`$(jq -r '.minContentionClients // "missing"' "$lab_validation")\`"
    echo "- Validated minimum contention target/client Mbps: \`$(jq -r '.minContentionTargetClientMbps // "missing"' "$lab_validation")\`"
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
  echo "## Next Actions"
  echo
  if jq -e '.nextActions | length == 0' "$readiness_json" >/dev/null; then
    echo "No follow-up actions required."
  else
    echo "| Code | Action | Command |"
    echo "| --- | --- | --- |"
    jq -r '.nextActions[] | "| `\(.code)` | \(.title): \(.detail) | `\(.command)` |"' "$readiness_json"
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
