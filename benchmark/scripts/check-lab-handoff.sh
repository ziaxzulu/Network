#!/usr/bin/env bash
set -euo pipefail

handoff_root=""
out_dir=""
required_min_contention_clients="500"
required_min_contention_target_client_mbps="5"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/check-lab-handoff.sh --handoff DIR [options]

Checks a generated prepare-lab-baseline-handoff.sh directory before lab
operators distribute commands to server and receiver hosts. This is a structural
preflight only; it does not run benchmarks and does not replace plan freshness,
lab validation, promotion, or final baseline readiness checks.

Options:
  --handoff DIR                    Handoff directory containing handoff-manifest.json. Required.
  --out DIR                        Output directory. Default: <handoff>/preflight.
  --required-min-contention-clients N Required handoff contention client count. Default: 500.
  --required-min-contention-target-client-mbps N Required handoff per-client Mbps target. Default: 5.
  --help                           Show this help.

Outputs:
  handoff-check.json               Machine-readable preflight result.
  handoff-check.md                 Human-readable preflight report.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --handoff)
      handoff_root="$2"
      shift 2
      ;;
    --out)
      out_dir="$2"
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

if [[ -z "$handoff_root" ]]; then
  usage >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to check lab handoff artifacts" >&2
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

handoff_root="$(resolve_path "$handoff_root")"
if [[ -z "$out_dir" ]]; then
  out_dir="$handoff_root/preflight"
else
  out_dir="$(resolve_path "$out_dir")"
fi
mkdir -p "$out_dir"

check_json="$out_dir/handoff-check.json"
check_md="$out_dir/handoff-check.md"
issues_jsonl="$(mktemp)"
trap 'rm -f "$issues_jsonl"' EXIT
: >"$issues_jsonl"

append_issue() {
  local code="$1"
  local component="$2"
  local message="$3"
  local extra="${4-}"
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

check_path() {
  local path="$1"
  local component="$2"
  local executable="${3:-false}"
  if [[ ! -e "$path" ]]; then
    append_issue "missing-path" "$component" "required handoff path is missing" "$(jq -n --arg path "$path" '{path:$path}')"
    return
  fi
  if [[ "$executable" == "true" && ! -x "$path" ]]; then
    append_issue "non-executable-path" "$component" "required handoff script is not executable" "$(jq -n --arg path "$path" '{path:$path}')"
  fi
}

check_readme_contains() {
  local pattern="$1"
  local message="$2"
  local readme="$handoff_root/README.md"
  if [[ ! -s "$readme" ]]; then
    return
  fi
  if ! grep -Fq -- "$pattern" "$readme"; then
    append_issue "missing-readme-command" "handoff-readme" "$message" \
      "$(jq -n --arg path "$readme" --arg pattern "$pattern" '{path:$path,pattern:$pattern}')"
  fi
}

manifest="$handoff_root/handoff-manifest.json"
if [[ ! -s "$manifest" ]]; then
  append_issue "missing-handoff-manifest" "handoff" "handoff-manifest.json is missing or empty" "$(jq -n --arg path "$manifest" '{path:$path}')"
  jq -n \
    --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg handoffRoot "$handoff_root" \
    --argjson issues "$(jq -s '.' "$issues_jsonl")" \
    '{checkedAt:$checkedAt,ready:false,issueCount:($issues|length),handoffRoot:$handoffRoot,issues:$issues}' >"$check_json"
  echo "Handoff check JSON: $check_json"
  echo "Handoff check report: $check_md"
  exit 1
fi

if ! jq -e '.kind == "raknet-lab-handoff"' "$manifest" >/dev/null; then
  append_issue "invalid-handoff-kind" "handoff" "handoff manifest has an unexpected kind" "$(jq -n --arg path "$manifest" '{path:$path}')"
fi

perfect_plan="$(jq -r '.perfectPlan // ""' "$manifest")"
impairment_plan="$(jq -r '.impairmentPlan // ""' "$manifest")"
curve_payloads_json="$(jq -c '.curvePayloadSizes // []' "$manifest")"
curve_rates_json="$(jq -c '.curveRatesMbps // []' "$manifest")"
profiles_json="$(jq -c '.profiles // []' "$manifest")"
expected_curve_rows="$(jq -r '((.curvePayloadSizes // []) | length) * ((.curveRatesMbps // []) | length)' "$manifest")"
expected_mtu="$(jq -r '.expectedMtu // empty' "$manifest")"
expected_min_cpus="$(jq -r '.expectedMinCpus // empty' "$manifest")"
require_cpu_performance="$(jq -r '.requireCpuPerformance // false' "$manifest")"
if [[ "$require_cpu_performance" != "true" ]]; then
  require_cpu_performance="false"
fi
expected_mtu_json="$expected_mtu"
if ! [[ "$expected_mtu_json" =~ ^[0-9]+$ ]]; then
  expected_mtu_json="0"
fi
expected_min_cpus_json="$expected_min_cpus"
if ! [[ "$expected_min_cpus_json" =~ ^[0-9]+$ ]]; then
  expected_min_cpus_json="0"
fi
expected_contention_clients="$(jq -r '
  def receiver_clients($spec):
    if ($spec | contains("=")) then ($spec | split("=")[-1] | tonumber)
    elif ($spec | contains(":")) then ($spec | split(":")[-1] | tonumber)
    else 0
    end;
  .contentionClientTotal // (((.contentionReceivers // []) | map(receiver_clients(.))) | add // 0)
' "$manifest")"
computed_contention_clients="$(jq -r '
  def receiver_clients($spec):
    if ($spec | contains("=")) then ($spec | split("=")[-1] | tonumber)
    elif ($spec | contains(":")) then ($spec | split(":")[-1] | tonumber)
    else 0
    end;
  ((.contentionReceivers // []) | map(receiver_clients(.))) | add // 0
' "$manifest")"
expected_per_client_mbps="$(jq -r '.perClientMbps // 0' "$manifest")"
expected_contention_scenarios_json="$(jq -c '
  def scenario($value):
    ($value | ascii_downcase) as $case
    | if $case == "fanout" or $case == "multi-client-fanout" then "multi-client-fanout"
      elif $case == "fairness" then "fairness"
      elif ($case | startswith("disappear")) or ($case | startswith("disappearing")) or $case == "close" or $case == "blackhole" or $case == "stopread" or $case == "stop-reading" then "disappearing-clients"
      else $case
      end;
  [(.contentionCases // [])[] | scenario(.)] | unique
' "$manifest")"

if [[ "$expected_contention_clients" != "$computed_contention_clients" ]]; then
  append_issue "handoff-contention-client-total-mismatch" "handoff" "handoff contentionClientTotal does not match contention receiver distribution" \
    "$(jq -n --argjson expected "$computed_contention_clients" --argjson actual "$expected_contention_clients" '{expectedFromReceivers:$expected,actualContentionClientTotal:$actual}')"
fi
if ! [[ "$expected_mtu" =~ ^[0-9]+$ && "$expected_mtu" -gt 0 ]]; then
  append_issue "handoff-missing-expected-mtu" "handoff" "handoff manifest does not include a concrete expected MTU" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if ! [[ "$expected_min_cpus" =~ ^[0-9]+$ && "$expected_min_cpus" -gt 0 ]]; then
  append_issue "handoff-missing-expected-min-cpus" "handoff" "handoff manifest does not include a concrete expected minimum CPU count" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if (( expected_contention_clients < required_min_contention_clients )); then
  append_issue "handoff-contention-clients-below-threshold" "handoff" "handoff contention client count is below the required baseline threshold" \
    "$(jq -n --argjson required "$required_min_contention_clients" --argjson actual "$expected_contention_clients" '{requiredMinContentionClients:$required,actualContentionClients:$actual}')"
fi
if ! jq -n -e --argjson actual "$expected_per_client_mbps" --argjson required "$required_min_contention_target_client_mbps" '$actual >= $required' >/dev/null; then
  append_issue "handoff-contention-target-client-mbps-below-threshold" "handoff" "handoff per-client Mbps target is below the required baseline threshold" \
    "$(jq -n --argjson required "$required_min_contention_target_client_mbps" --argjson actual "$expected_per_client_mbps" '{requiredMinContentionTargetClientMbps:$required,actualPerClientMbps:$actual}')"
fi

check_path "$handoff_root/README.md" "handoff"
check_readme_contains "benchmark/scripts/check-lab-handoff.sh --handoff" "handoff README does not show the preflight command"
check_readme_contains "benchmark/scripts/check-lab-host-prereqs.sh" "handoff README does not show the host prerequisite check"
if [[ -n "$expected_mtu" ]]; then
  check_readme_contains "--expect-mtu $expected_mtu" "handoff README host prerequisite command does not require the manifest expected MTU"
fi
if [[ -n "$expected_min_cpus" ]]; then
  check_readme_contains "--expect-min-cpus $expected_min_cpus" "handoff README host prerequisite command does not require the manifest minimum CPU count"
fi
check_readme_contains "--require-clock-sync" "handoff README host prerequisite command does not require clock-sync evidence"
check_readme_contains "--require-no-netem" "handoff README host prerequisite command does not require clean qdisc/no-netem evidence"
if [[ "$require_cpu_performance" == "true" ]]; then
  check_readme_contains "--require-cpu-performance" "handoff README host prerequisite command does not require CPU performance-governor evidence"
fi
check_readme_contains "benchmark/scripts/promote-lab-baseline.sh" "handoff README does not show the perfect-network promotion command"
check_readme_contains "--min-contention-clients \"$expected_contention_clients\"" "handoff README promotion command does not enforce the handoff contention client count"
check_readme_contains "--min-contention-target-client-mbps \"$expected_per_client_mbps\"" "handoff README promotion command does not enforce the handoff per-client Mbps target"
check_readme_contains "benchmark/scripts/promote-lab-impairment.sh" "handoff README does not show the impairment promotion command"
check_readme_contains "benchmark/scripts/check-baseline-readiness.sh" "handoff README does not show the final readiness command"
check_readme_contains "--required-min-contention-clients \"$expected_contention_clients\"" "handoff README readiness command does not enforce the handoff contention client count"
check_readme_contains "--required-min-contention-target-client-mbps \"$expected_per_client_mbps\"" "handoff README readiness command does not enforce the handoff per-client Mbps target"
check_path "$perfect_plan/check-plan-freshness.sh" "perfect-plan" true
check_path "$perfect_plan/host-capture-commands.sh" "perfect-plan" true
check_path "$perfect_plan/merge-all.sh" "perfect-plan" true
check_path "$perfect_plan/topology-template.md" "perfect-plan"
check_path "$impairment_plan/check-plan-freshness.sh" "impairment-plan" true
check_path "$impairment_plan/validate-all.sh" "impairment-plan" true
check_path "$impairment_plan/summarize-campaign.sh" "impairment-plan" true
check_path "$impairment_plan/manifest.jsonl" "impairment-plan"

check_curve_manifest() {
  local label="$1"
  local path="$2"
  check_path "$path" "$label"
  if [[ ! -s "$path" ]]; then
    return
  fi

  local actual_rows
  actual_rows="$(jq -s 'length' "$path")"
  if [[ "$actual_rows" != "$expected_curve_rows" ]]; then
    append_issue "curve-row-count-mismatch" "$label" "curve manifest row count does not match handoff payload/rate matrix" \
      "$(jq -n --arg path "$path" --argjson expected "$expected_curve_rows" --argjson actual "$actual_rows" '{path:$path,expectedRows:$expected,actualRows:$actual}')"
  fi

  local missing_payloads
  missing_payloads="$(jq -r -s --argjson expected "$curve_payloads_json" '
    ([.[] | (.payloadSize // empty | tonumber)] | unique) as $actual
    | $expected[] as $payload
    | select(($actual | index($payload)) == null)
    | $payload
  ' "$path")"
  while IFS= read -r payload; do
    [[ -z "$payload" ]] && continue
    append_issue "curve-missing-payload" "$label" "curve manifest is missing a required payload size" \
      "$(jq -n --arg path "$path" --argjson payloadSize "$payload" '{path:$path,payloadSize:$payloadSize}')"
  done <<<"$missing_payloads"

  local missing_rates
  missing_rates="$(jq -r -s --argjson expected "$curve_rates_json" '
    ([.[] | (.rateMbps // empty | tostring)] | unique) as $actual
    | $expected[] as $rate
    | select(($actual | index($rate)) == null)
    | $rate
  ' "$path")"
  while IFS= read -r rate; do
    [[ -z "$rate" ]] && continue
    append_issue "curve-missing-rate" "$label" "curve manifest is missing a required rate point" \
      "$(jq -n --arg path "$path" --arg rateMbps "$rate" '{path:$path,rateMbps:$rateMbps}')"
  done <<<"$missing_rates"
}

check_contention_manifest() {
  local label="$1"
  local path="$2"
  check_path "$path" "$label"
  if [[ ! -s "$path" ]]; then
    return
  fi

  local missing_scenarios
  missing_scenarios="$(jq -r -s --argjson expected "$expected_contention_scenarios_json" '
    ([.[] | (.benchmarkName // empty)] | unique) as $actual
    | $expected[] as $scenario
    | select(($actual | index($scenario)) == null)
    | $scenario
  ' "$path")"
  while IFS= read -r scenario; do
    [[ -z "$scenario" ]] && continue
    append_issue "contention-missing-scenario" "$label" "contention manifest is missing a required scenario" \
      "$(jq -n --arg path "$path" --arg scenario "$scenario" '{path:$path,scenario:$scenario}')"
  done <<<"$missing_scenarios"

  local client_mismatches
  client_mismatches="$(jq -r -s --argjson expected "$expected_contention_clients" '
    .[]
    | select(((.clients // -1) | tonumber) != $expected)
    | [(.case // ""), (.benchmarkName // ""), ((.clients // -1) | tostring)] | @tsv
  ' "$path")"
  while IFS=$'\t' read -r case_name benchmark_name actual_clients; do
    [[ -z "$case_name" && -z "$benchmark_name" ]] && continue
    append_issue "contention-client-count-mismatch" "$label" "contention manifest row client count does not match the handoff receiver total" \
      "$(jq -n --arg path "$path" --arg case "$case_name" --arg benchmarkName "$benchmark_name" --argjson expected "$expected_contention_clients" --argjson actual "${actual_clients:-0}" '{path:$path,case:$case,benchmarkName:$benchmarkName,expectedClients:$expected,actualClients:$actual}')"
  done <<<"$client_mismatches"

  local rate_mismatches
  rate_mismatches="$(jq -r -s --argjson expected "$expected_per_client_mbps" '
    .[]
    | select(((((.perClientMbps // -1) | tonumber) - $expected) | fabs) > 0.000001)
    | [(.case // ""), (.benchmarkName // ""), ((.perClientMbps // -1) | tostring)] | @tsv
  ' "$path")"
  while IFS=$'\t' read -r case_name benchmark_name actual_per_client_mbps; do
    [[ -z "$case_name" && -z "$benchmark_name" ]] && continue
    append_issue "contention-per-client-mbps-mismatch" "$label" "contention manifest row per-client Mbps does not match the handoff target" \
      "$(jq -n --arg path "$path" --arg case "$case_name" --arg benchmarkName "$benchmark_name" --argjson expected "$expected_per_client_mbps" --argjson actual "${actual_per_client_mbps:-0}" '{path:$path,case:$case,benchmarkName:$benchmarkName,expectedPerClientMbps:$expected,actualPerClientMbps:$actual}')"
  done <<<"$rate_mismatches"
}

check_curve_manifest "perfect-curve" "$perfect_plan/curve-plan/manifest.jsonl"
check_curve_manifest "perfect-raised-curve" "$perfect_plan/curve-raised-plan/manifest.jsonl"
check_contention_manifest "perfect-contention" "$perfect_plan/contention-plan/manifest.jsonl"

if [[ -s "$impairment_plan/manifest.jsonl" ]]; then
  missing_profiles="$(jq -r -s --argjson expected "$profiles_json" '
    ([.[] | (.profile // empty)] | unique) as $actual
    | $expected[] as $profile
    | select(($actual | index($profile)) == null)
    | $profile
  ' "$impairment_plan/manifest.jsonl")"
  while IFS= read -r profile; do
    [[ -z "$profile" ]] && continue
    append_issue "impairment-missing-profile" "impairment-plan" "impairment manifest is missing a handoff profile" \
      "$(jq -n --arg profile "$profile" '{profile:$profile}')"
  done <<<"$missing_profiles"

  while IFS=$'\t' read -r profile plan apply_script status_script clear_script; do
    [[ -z "$profile" ]] && continue
    check_path "$plan/check-plan-freshness.sh" "impairment-profile:$profile" true
    check_path "$plan/merge-all.sh" "impairment-profile:$profile" true
    check_path "$apply_script" "impairment-profile:$profile" true
    check_path "$status_script" "impairment-profile:$profile" true
    check_path "$clear_script" "impairment-profile:$profile" true
    check_curve_manifest "impairment-profile:$profile:curve" "$plan/curve-plan/manifest.jsonl"
    check_curve_manifest "impairment-profile:$profile:raised-curve" "$plan/curve-raised-plan/manifest.jsonl"
    check_contention_manifest "impairment-profile:$profile:contention" "$plan/contention-plan/manifest.jsonl"
  done < <(jq -r '[.profile, .plan, .applyScript, .statusScript, .clearScript] | @tsv' "$impairment_plan/manifest.jsonl")
fi

issues_array="$(jq -s '.' "$issues_jsonl")"

jq -n \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg handoffRoot "$handoff_root" \
  --arg manifest "$manifest" \
  --argjson expectedCurvePayloadSizes "$curve_payloads_json" \
  --argjson expectedCurveRatesMbps "$curve_rates_json" \
  --argjson expectedProfiles "$profiles_json" \
  --argjson expectedContentionScenarios "$expected_contention_scenarios_json" \
  --argjson expectedContentionClients "$expected_contention_clients" \
  --argjson expectedPerClientMbps "$expected_per_client_mbps" \
  --argjson expectedMtu "$expected_mtu_json" \
  --argjson expectedMinCpus "$expected_min_cpus_json" \
  --argjson requireCpuPerformance "$require_cpu_performance" \
  --argjson requiredMinContentionClients "$required_min_contention_clients" \
  --argjson requiredMinContentionTargetClientMbps "$required_min_contention_target_client_mbps" \
  --argjson expectedCurveRows "$expected_curve_rows" \
  --argjson issues "$issues_array" \
  '{
    checkedAt: $checkedAt,
    ready: (($issues | length) == 0),
    issueCount: ($issues | length),
    handoffRoot: $handoffRoot,
    handoffManifest: $manifest,
    expectedCurvePayloadSizes: $expectedCurvePayloadSizes,
    expectedCurveRatesMbps: $expectedCurveRatesMbps,
    expectedCurveRowsPerCurvePlan: $expectedCurveRows,
    expectedProfiles: $expectedProfiles,
    expectedContentionScenarios: $expectedContentionScenarios,
    expectedContentionClients: $expectedContentionClients,
    expectedPerClientMbps: $expectedPerClientMbps,
    expectedMtu: $expectedMtu,
    expectedMinCpus: $expectedMinCpus,
    requireCpuPerformance: $requireCpuPerformance,
    requiredMinContentionClients: $requiredMinContentionClients,
    requiredMinContentionTargetClientMbps: $requiredMinContentionTargetClientMbps,
    issues: $issues
  }' >"$check_json"

{
  echo "# RakNet Lab Handoff Preflight"
  echo
  echo "- Checked: \`$(jq -r '.checkedAt' "$check_json")\`"
  echo "- Result: \`$(jq -r 'if .ready then "ready" else "not-ready" end' "$check_json")\`"
  echo "- Issues: \`$(jq -r '.issueCount' "$check_json")\`"
  echo "- Handoff: \`$handoff_root\`"
  echo "- Expected curve rows per curve plan: \`$(jq -r '.expectedCurveRowsPerCurvePlan' "$check_json")\`"
  echo "- Expected contention clients: \`$(jq -r '.expectedContentionClients' "$check_json")\`"
  echo "- Expected per-client Mbps: \`$(jq -r '.expectedPerClientMbps' "$check_json")\`"
  echo "- Expected MTU: \`$(jq -r '.expectedMtu' "$check_json")\`"
  echo "- Expected minimum CPUs: \`$(jq -r '.expectedMinCpus' "$check_json")\`"
  echo "- Require CPU performance governor: \`$(jq -r '.requireCpuPerformance' "$check_json")\`"
  echo "- Required minimum contention clients: \`$(jq -r '.requiredMinContentionClients' "$check_json")\`"
  echo "- Required minimum per-client Mbps: \`$(jq -r '.requiredMinContentionTargetClientMbps' "$check_json")\`"
  echo
  echo "## Issues"
  echo
  if jq -e '.issues | length == 0' "$check_json" >/dev/null; then
    echo "No issues found."
  else
    echo "| Code | Component | Message |"
    echo "| --- | --- | --- |"
    jq -r '.issues[] | "| `\(.code)` | `\(.component)` | \(.message) |"' "$check_json"
  fi
} >"$check_md"

echo "Handoff check JSON: $check_json"
echo "Handoff check report: $check_md"

if jq -e '.ready == true' "$check_json" >/dev/null; then
  exit 0
fi
exit 1
