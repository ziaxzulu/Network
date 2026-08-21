#!/usr/bin/env bash
set -euo pipefail

handoff_root=""
out_dir=""
required_min_contention_clients="500"
required_min_contention_target_client_mbps="5"
required_min_iterations="3"
required_batch_intervals_ms="10,20,50"
required_resource_pack_chunk_sizes="8192,262144"
required_resource_pack_intervals_ms="200"
required_disappearance_modes="blackhole"
require_source_audit=false
require_current_revision=false

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
  --required-min-iterations N       Required measured iterations. Default: 3.
  --required-batch-intervals-ms CSV Required batched-game-traffic intervals in milliseconds. Default: 10,20,50.
  --required-resource-pack-chunk-sizes CSV Required resource-pack chunk payload sizes. Default: 8192,262144.
  --required-resource-pack-intervals-ms CSV Required resource-pack intervals in milliseconds. Default: 200.
  --required-disappearance-modes CSV Required disappearing-client modes. Default: blackhole.
  --require-source-audit           Require a ready capture-production-evidence.sh source-audit artifact.
  --require-current-revision       Require the source-audit Network revision to match the current checkout.
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
    --required-min-iterations)
      required_min_iterations="$2"
      shift 2
      ;;
    --required-batch-intervals-ms)
      required_batch_intervals_ms="$2"
      shift 2
      ;;
    --required-resource-pack-chunk-sizes)
      required_resource_pack_chunk_sizes="$2"
      shift 2
      ;;
    --required-resource-pack-intervals-ms)
      required_resource_pack_intervals_ms="$2"
      shift 2
      ;;
    --required-disappearance-modes)
      required_disappearance_modes="$2"
      shift 2
      ;;
    --require-source-audit)
      require_source_audit=true
      shift
      ;;
    --require-current-revision)
      require_current_revision=true
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
if ! [[ "$required_min_iterations" =~ ^[0-9]+$ ]]; then
  echo "--required-min-iterations must be a non-negative integer" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
current_network_revision="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || true)"
current_network_short_revision="$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || true)"

resolve_path() {
  local path="$1"
  if [[ "$path" == /* ]]; then
    printf '%s\n' "$path"
  else
    printf '%s\n' "$repo_root/$path"
  fi
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    return 1
  fi
}

duration_millis() {
  local value="${1,,}"
  if [[ "$value" =~ ^([0-9]+)ms$ ]]; then
    echo "${BASH_REMATCH[1]}"
  elif [[ "$value" =~ ^([0-9]+)s$ ]]; then
    echo "$((BASH_REMATCH[1] * 1000))"
  elif [[ "$value" =~ ^([0-9]+)m$ ]]; then
    echo "$((BASH_REMATCH[1] * 60000))"
  elif [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "$value"
  else
    echo "Invalid duration: $1" >&2
    exit 2
  fi
}

csv_json_number_array() {
  printf '%s\n' "$1" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | jq -R -s 'split("\n") | map(select(length > 0) | tonumber)'
}

csv_json_duration_millis_array() {
  local value
  local -a values=()
  IFS=',' read -r -a values <<<"$1"
  for value in "${values[@]}"; do
    value="${value//[[:space:]]/}"
    [[ -n "$value" ]] || continue
    duration_millis "$value"
  done | jq -R -s 'split("\n") | map(select(length > 0) | tonumber)'
}

json_duration_millis_array_from_values() {
  local value
  while IFS= read -r value; do
    [[ -n "$value" ]] || continue
    duration_millis "$value"
  done | jq -R -s 'split("\n") | map(select(length > 0) | tonumber)'
}

required_batch_intervals_millis_json="$(csv_json_duration_millis_array "$required_batch_intervals_ms")"
required_resource_pack_payloads_json="$(csv_json_number_array "$required_resource_pack_chunk_sizes")"
required_resource_pack_intervals_millis_json="$(csv_json_duration_millis_array "$required_resource_pack_intervals_ms")"
required_disappearance_modes_json="$(printf '%s\n' "$required_disappearance_modes" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | jq -R -s 'split("\n") | map(select(length > 0))')"

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
  if [[ "$executable" == "true" && -s "$path" ]] && ! bash -n "$path" >/dev/null 2>&1; then
    append_issue "invalid-shell-syntax" "$component" "handoff script has invalid shell syntax" \
      "$(jq -n --arg path "$path" '{path:$path}')"
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

check_file_contains() {
  local path="$1"
  local pattern="$2"
  local component="$3"
  local message="$4"
  if [[ ! -s "$path" ]]; then
    return
  fi
  if ! grep -Fq -- "$pattern" "$path"; then
    append_issue "missing-helper-command" "$component" "$message" \
      "$(jq -n --arg path "$path" --arg pattern "$pattern" '{path:$path,pattern:$pattern}')"
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
perfect_artifacts="$(jq -r '.perfectArtifacts // ""' "$manifest")"
impairment_artifacts="$(jq -r '.impairmentArtifacts // ""' "$manifest")"
promote_script="$(jq -r '.promoteScript // ""' "$manifest")"
prereq_script="$(jq -r '.prereqScript // ""' "$manifest")"
artifact_collection_json="$(jq -r '.artifactCollectionJson // ""' "$manifest")"
artifact_collection_md="$(jq -r '.artifactCollectionMd // ""' "$manifest")"
curve_payloads_json="$(jq -c '.curvePayloadSizes // []' "$manifest")"
curve_rates_json="$(jq -c '.curveRatesMbps // []' "$manifest")"
profiles_json="$(jq -c '.profiles // []' "$manifest")"
batch_intervals="$(jq -r '(.batchIntervals // []) | join(",")' "$manifest")"
contention_cases="$(jq -r '(.contentionCases // []) | join(",")' "$manifest")"
resource_pack_chunk_sizes="$(jq -r '(.resourcePackChunkSizes // []) | join(",")' "$manifest")"
resource_pack_interval="$(jq -r '.resourcePackInterval // ""' "$manifest")"
expected_reliability="$(jq -r '.reliability // ""' "$manifest")"
expected_curve_rows="$(jq -r '((.curvePayloadSizes // []) | length) * ((.curveRatesMbps // []) | length)' "$manifest")"
expected_mtu="$(jq -r '.expectedMtu // empty' "$manifest")"
expected_min_cpus="$(jq -r '.expectedMinCpus // empty' "$manifest")"
expected_warmup="$(jq -r '.warmup // ""' "$manifest")"
expected_duration="$(jq -r '.duration // ""' "$manifest")"
expected_iterations="$(jq -r '.iterations // empty' "$manifest")"
expected_max_queued_bytes="$(jq -r '.maxQueuedBytes // empty' "$manifest")"
require_cpu_performance="$(jq -r '.requireCpuPerformance // false' "$manifest")"
if [[ "$require_cpu_performance" != "true" ]]; then
  require_cpu_performance="false"
fi
sudo_netem="$(jq -r '.sudoNetem // false' "$manifest")"
if [[ "$sudo_netem" != "true" ]]; then
  sudo_netem="false"
fi
target_host_role="$(jq -r '.targetHostRole // ""' "$manifest")"
expected_prereq_roles_json="$(jq -c '
  def role($spec):
    if ($spec | contains("=")) then ($spec | split("=")[0])
    elif ($spec | contains(":")) then ($spec | split(":")[0])
    else empty
    end;
  [
    "server",
    ((.curveReceivers // [])[] | role(.)),
    ((.contentionReceivers // [])[] | role(.)),
    (.targetHostRole // empty)
  ]
  | map(select(length > 0))
  | unique
' "$manifest")"
manifest_prereq_roles_json="$(jq -c '(.prereqRoles // []) | unique' "$manifest")"
helper_prereq_roles_json="[]"
expected_mtu_json="$expected_mtu"
if ! [[ "$expected_mtu_json" =~ ^[0-9]+$ ]]; then
  expected_mtu_json="0"
fi
expected_min_cpus_json="$expected_min_cpus"
if ! [[ "$expected_min_cpus_json" =~ ^[0-9]+$ ]]; then
  expected_min_cpus_json="0"
fi
expected_iterations_json="$expected_iterations"
if ! [[ "$expected_iterations_json" =~ ^[0-9]+$ ]]; then
  expected_iterations_json="0"
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
expected_immediate_per_client_mbps="$(jq -r '.immediatePerClientMbps // .perClientMbps // 0' "$manifest")"
production_evidence_json="$(jq -c '.productionEvidence // null' "$manifest")"
production_evidence_doc="$(jq -r '.productionEvidence.document // ""' "$manifest")"
production_evidence_sha256="$(jq -r '.productionEvidence.sha256 // ""' "$manifest")"
production_evidence_path=""
production_evidence_actual_sha256=""
source_audit_json="$(jq -c '.sourceAudit // null' "$manifest")"
source_audit_doc="$(jq -r '.sourceAudit.document // ""' "$manifest")"
source_audit_sha256="$(jq -r '.sourceAudit.sha256 // ""' "$manifest")"
source_audit_network_revision="$(jq -r '.sourceAudit.networkRevision // ""' "$manifest")"
source_audit_path=""
source_audit_actual_sha256=""
source_audit_actual_ready="null"
expected_contention_scenarios_json="$(jq -c '
  def scenario($value):
    ($value | ascii_downcase) as $case
    | if $case == "fanout" or $case == "multi-client-fanout" or $case == "immediate" or $case == "immediate-send" or $case == "immediate-fanout" then "multi-client-fanout"
      elif $case == "fairness" then "fairness"
      elif ($case | startswith("disappear")) or ($case | startswith("disappearing")) or $case == "close" or $case == "blackhole" or $case == "stopread" or $case == "stop-reading" then "disappearing-clients"
      elif $case == "batch" or $case == "batched" or $case == "batched-game-traffic" then "batched-game-traffic"
      elif $case == "resource-pack" or $case == "resource-pack-transfer" or $case == "resource" then "resource-pack-transfer"
      else $case
      end;
  [(.contentionCases // [])[] | scenario(.)] | unique
' "$manifest")"
expected_resource_pack_payloads_json="$(jq -c '.resourcePackChunkSizes // []' "$manifest")"
expected_batch_intervals_millis_json="$(jq -r '.batchIntervals[]? // empty' "$manifest" | json_duration_millis_array_from_values)"
expected_resource_pack_intervals_millis_json="$(jq -r '.resourcePackInterval // empty' "$manifest" | json_duration_millis_array_from_values)"
required_collection_groups_json="$(jq -n -c '[
  "perfect-topology",
  "perfect-host-captures",
  "perfect-prereq-reports",
  "perfect-worker-artifacts",
  "perfect-combined-artifacts",
  "impairment-profile-artifacts",
  "impairment-netem-evidence",
  "impairment-campaign-summary",
  "promotion-readiness"
]')"
artifact_collection_groups_json="[]"

if [[ "$expected_contention_clients" != "$computed_contention_clients" ]]; then
  append_issue "handoff-contention-client-total-mismatch" "handoff" "handoff contentionClientTotal does not match contention receiver distribution" \
    "$(jq -n --argjson expected "$computed_contention_clients" --argjson actual "$expected_contention_clients" '{expectedFromReceivers:$expected,actualContentionClientTotal:$actual}')"
fi
if [[ -z "$expected_reliability" ]]; then
  append_issue "handoff-missing-reliability" "handoff" "handoff manifest is missing the benchmark reliability mode" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if ! [[ "$expected_mtu" =~ ^[0-9]+$ && "$expected_mtu" -gt 0 ]]; then
  append_issue "handoff-missing-expected-mtu" "handoff" "handoff manifest does not include a concrete expected MTU" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if ! [[ "$expected_min_cpus" =~ ^[0-9]+$ && "$expected_min_cpus" -gt 0 ]]; then
  append_issue "handoff-missing-expected-min-cpus" "handoff" "handoff manifest does not include a concrete expected minimum CPU count" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if ! [[ "$expected_iterations" =~ ^[0-9]+$ && "$expected_iterations" -gt 0 ]]; then
  append_issue "handoff-missing-iterations" "handoff" "handoff manifest does not include a concrete measured iteration count" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
elif (( expected_iterations < required_min_iterations )); then
  append_issue "handoff-iterations-below-threshold" "handoff" "handoff measured iteration count is below the required baseline threshold" \
    "$(jq -n --argjson required "$required_min_iterations" --argjson actual "$expected_iterations" '{requiredMinIterations:$required,actualIterations:$actual}')"
fi
if (( expected_contention_clients < required_min_contention_clients )); then
  append_issue "handoff-contention-clients-below-threshold" "handoff" "handoff contention client count is below the required baseline threshold" \
    "$(jq -n --argjson required "$required_min_contention_clients" --argjson actual "$expected_contention_clients" '{requiredMinContentionClients:$required,actualContentionClients:$actual}')"
fi
if ! jq -n -e --argjson actual "$expected_per_client_mbps" --argjson required "$required_min_contention_target_client_mbps" '$actual >= $required' >/dev/null; then
  append_issue "handoff-contention-target-client-mbps-below-threshold" "handoff" "handoff per-client Mbps target is below the required baseline threshold" \
    "$(jq -n --argjson required "$required_min_contention_target_client_mbps" --argjson actual "$expected_per_client_mbps" '{requiredMinContentionTargetClientMbps:$required,actualPerClientMbps:$actual}')"
fi
if ! jq -e '(.productionEvidence.document // "") != "" and (.productionEvidence.exists == true) and ((.productionEvidence.sha256 // "") | test("^[0-9a-f]{64}$"))' "$manifest" >/dev/null; then
  append_issue "handoff-missing-production-evidence" "handoff" "handoff manifest does not include a concrete production evidence document fingerprint" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if [[ -n "$production_evidence_doc" ]]; then
  production_evidence_path="$(resolve_path "$production_evidence_doc")"
  if [[ ! -f "$production_evidence_path" ]]; then
    append_issue "handoff-production-evidence-document-missing" "handoff" "production evidence document referenced by the handoff manifest is missing" \
      "$(jq -n --arg document "$production_evidence_doc" --arg path "$production_evidence_path" '{document:$document,path:$path}')"
  elif ! production_evidence_actual_sha256="$(sha256_file "$production_evidence_path")"; then
    append_issue "handoff-production-evidence-sha-unavailable" "handoff" "could not compute production evidence SHA-256 on this host" \
      "$(jq -n --arg document "$production_evidence_doc" --arg path "$production_evidence_path" '{document:$document,path:$path}')"
  elif [[ -n "$production_evidence_sha256" && "$production_evidence_sha256" != "$production_evidence_actual_sha256" ]]; then
    append_issue "handoff-production-evidence-sha-mismatch" "handoff" "handoff production evidence fingerprint does not match the current evidence document" \
      "$(jq -n --arg document "$production_evidence_doc" --arg path "$production_evidence_path" --arg expected "$production_evidence_sha256" --arg actual "$production_evidence_actual_sha256" '{document:$document,path:$path,expectedSha256:$expected,actualSha256:$actual}')"
  fi
fi
if "$require_source_audit" && ! jq -e '.sourceAudit != null' "$manifest" >/dev/null; then
  append_issue "handoff-missing-source-audit" "handoff" "handoff manifest does not include a production source audit artifact" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
fi
if "$require_current_revision"; then
  if ! jq -e '.sourceAudit != null' "$manifest" >/dev/null; then
    append_issue "handoff-current-revision-missing-source-audit" "handoff" "cannot require current checkout revision without a source audit artifact" \
      "$(jq -n --arg path "$manifest" '{path:$path}')"
  elif [[ -z "$current_network_revision" ]]; then
    append_issue "handoff-current-revision-unavailable" "handoff" "could not determine the current Network git revision" \
      "$(jq -n --arg repoRoot "$repo_root" '{repoRoot:$repoRoot}')"
  elif [[ -z "$source_audit_network_revision" ]]; then
    append_issue "handoff-source-audit-missing-revision" "handoff" "source audit does not include a Network revision" \
      "$(jq -n --arg path "$manifest" '{path:$path}')"
  elif [[ "$source_audit_network_revision" != "$current_network_revision" ]]; then
    append_issue "handoff-source-audit-revision-mismatch" "handoff" "source audit Network revision does not match the current checkout" \
      "$(jq -n --arg expected "$current_network_revision" --arg actual "$source_audit_network_revision" '{currentNetworkRevision:$expected,sourceAuditNetworkRevision:$actual}')"
  fi
fi
if jq -e '.sourceAudit != null' "$manifest" >/dev/null; then
  if ! jq -e '(.sourceAudit.document // "") != "" and (.sourceAudit.exists == true) and (.sourceAudit.ready == true) and ((.sourceAudit.sha256 // "") | test("^[0-9a-f]{64}$"))' "$manifest" >/dev/null; then
    append_issue "handoff-invalid-source-audit" "handoff" "handoff manifest does not include a ready source audit fingerprint" \
      "$(jq -n --arg path "$manifest" '{path:$path}')"
  fi
  if [[ -n "$source_audit_doc" ]]; then
    source_audit_path="$(resolve_path "$source_audit_doc")"
    if [[ ! -f "$source_audit_path" ]]; then
      append_issue "handoff-source-audit-missing" "handoff" "source audit referenced by the handoff manifest is missing" \
        "$(jq -n --arg document "$source_audit_doc" --arg path "$source_audit_path" '{document:$document,path:$path}')"
    elif ! source_audit_actual_sha256="$(sha256_file "$source_audit_path")"; then
      append_issue "handoff-source-audit-sha-unavailable" "handoff" "could not compute source audit SHA-256 on this host" \
        "$(jq -n --arg document "$source_audit_doc" --arg path "$source_audit_path" '{document:$document,path:$path}')"
    else
      source_audit_actual_ready="$(jq -r 'if .ready == true then "true" else "false" end' "$source_audit_path" 2>/dev/null || echo false)"
      if [[ -n "$source_audit_sha256" && "$source_audit_sha256" != "$source_audit_actual_sha256" ]]; then
        append_issue "handoff-source-audit-sha-mismatch" "handoff" "handoff source audit fingerprint does not match the current source audit artifact" \
          "$(jq -n --arg document "$source_audit_doc" --arg path "$source_audit_path" --arg expected "$source_audit_sha256" --arg actual "$source_audit_actual_sha256" '{document:$document,path:$path,expectedSha256:$expected,actualSha256:$actual}')"
      fi
      if [[ "$source_audit_actual_ready" != "true" ]]; then
        append_issue "handoff-source-audit-not-ready" "handoff" "source audit referenced by the handoff manifest is not ready" \
          "$(jq -n --arg document "$source_audit_doc" --arg path "$source_audit_path" '{document:$document,path:$path}')"
      fi
    fi
  fi
fi
if jq -n -e --argjson scenarios "$expected_contention_scenarios_json" '$scenarios | index("batched-game-traffic") != null' >/dev/null; then
  missing_required_batch_intervals="$(jq -r -n --argjson actual "$expected_batch_intervals_millis_json" --argjson required "$required_batch_intervals_millis_json" '
    $required[] as $interval
    | select(($actual | index($interval)) == null)
    | $interval
  ')"
  while IFS= read -r interval; do
    [[ -z "$interval" ]] && continue
    append_issue "handoff-missing-required-batch-interval" "handoff" "handoff batch intervals do not include a required production-shape interval" \
      "$(jq -n --argjson batchIntervalMillis "$interval" '{batchIntervalMillis:$batchIntervalMillis}')"
  done <<<"$missing_required_batch_intervals"
fi
if jq -n -e --argjson scenarios "$expected_contention_scenarios_json" '$scenarios | index("resource-pack-transfer") != null' >/dev/null; then
  missing_required_resource_payloads="$(jq -r -n --argjson actual "$expected_resource_pack_payloads_json" --argjson required "$required_resource_pack_payloads_json" '
    $required[] as $payload
    | select(($actual | index($payload)) == null)
    | $payload
  ')"
  while IFS= read -r payload; do
    [[ -z "$payload" ]] && continue
    append_issue "handoff-missing-required-resource-pack-payload" "handoff" "handoff resource-pack chunk sizes do not include a required production-shape payload" \
      "$(jq -n --argjson payloadSize "$payload" '{payloadSize:$payloadSize}')"
  done <<<"$missing_required_resource_payloads"

  missing_required_resource_intervals="$(jq -r -n --argjson actual "$expected_resource_pack_intervals_millis_json" --argjson required "$required_resource_pack_intervals_millis_json" '
    $required[] as $interval
    | select(($actual | index($interval)) == null)
    | $interval
  ')"
  while IFS= read -r interval; do
    [[ -z "$interval" ]] && continue
    append_issue "handoff-missing-required-resource-pack-interval" "handoff" "handoff resource-pack intervals do not include a required production-shape interval" \
      "$(jq -n --argjson batchIntervalMillis "$interval" '{batchIntervalMillis:$batchIntervalMillis}')"
  done <<<"$missing_required_resource_intervals"
fi
if jq -n -e --argjson scenarios "$expected_contention_scenarios_json" '$scenarios | index("disappearing-clients") != null' >/dev/null; then
  missing_required_disappearance_modes="$(jq -r -n --argjson required "$required_disappearance_modes_json" --arg cases "$contention_cases" '
    def mode($value):
      ($value | ascii_downcase) as $case
      | if ($case | contains("blackhole")) then "blackhole"
        elif ($case | contains("stopread")) or ($case | contains("stop-reading")) then "stop-reading"
        elif ($case | contains("close")) then "close"
        else empty end;
    ($cases | split(",") | map(gsub("^\\s+|\\s+$"; "") | select(length > 0) | mode(.)) | unique) as $actual
    | $required[] as $mode
    | select(($actual | index($mode)) == null)
    | $mode
  ')"
  while IFS= read -r mode; do
    [[ -z "$mode" ]] && continue
    append_issue "handoff-missing-required-disappearance-mode" "handoff" "handoff contention cases do not include a required disappearing-client mode" \
      "$(jq -n --arg disappearanceMode "$mode" '{disappearanceMode:$disappearanceMode}')"
  done <<<"$missing_required_disappearance_modes"
fi

check_path "$handoff_root/README.md" "handoff"
if [[ -z "$prereq_script" ]]; then
  append_issue "handoff-missing-prereq-script" "handoff" "handoff manifest does not record prereq-commands.sh" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
  prereq_script="$handoff_root/prereq-commands.sh"
elif [[ "$prereq_script" != "$handoff_root/prereq-commands.sh" ]]; then
  append_issue "handoff-prereq-script-mismatch" "handoff" "handoff manifest prereq helper path does not match the handoff directory" \
    "$(jq -n --arg expected "$handoff_root/prereq-commands.sh" --arg actual "$prereq_script" '{expected:$expected,actual:$actual}')"
fi
if [[ -z "$promote_script" ]]; then
  append_issue "handoff-missing-promote-script" "handoff" "handoff manifest does not record promote-and-check.sh" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
  promote_script="$handoff_root/promote-and-check.sh"
elif [[ "$promote_script" != "$handoff_root/promote-and-check.sh" ]]; then
  append_issue "handoff-promote-script-mismatch" "handoff" "handoff manifest promote script path does not match the handoff directory" \
    "$(jq -n --arg expected "$handoff_root/promote-and-check.sh" --arg actual "$promote_script" '{expected:$expected,actual:$actual}')"
fi
if [[ -z "$artifact_collection_json" ]]; then
  append_issue "handoff-missing-artifact-collection-json" "handoff" "handoff manifest does not record artifact-collection.json" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
  artifact_collection_json="$handoff_root/artifact-collection.json"
elif [[ "$artifact_collection_json" != "$handoff_root/artifact-collection.json" ]]; then
  append_issue "handoff-artifact-collection-json-mismatch" "handoff" "handoff manifest artifact collection JSON path does not match the handoff directory" \
    "$(jq -n --arg expected "$handoff_root/artifact-collection.json" --arg actual "$artifact_collection_json" '{expected:$expected,actual:$actual}')"
fi
if [[ -z "$artifact_collection_md" ]]; then
  append_issue "handoff-missing-artifact-collection-md" "handoff" "handoff manifest does not record artifact-collection.md" \
    "$(jq -n --arg path "$manifest" '{path:$path}')"
  artifact_collection_md="$handoff_root/artifact-collection.md"
elif [[ "$artifact_collection_md" != "$handoff_root/artifact-collection.md" ]]; then
  append_issue "handoff-artifact-collection-md-mismatch" "handoff" "handoff manifest artifact collection Markdown path does not match the handoff directory" \
    "$(jq -n --arg expected "$handoff_root/artifact-collection.md" --arg actual "$artifact_collection_md" '{expected:$expected,actual:$actual}')"
fi
check_path "$prereq_script" "handoff" true
check_path "$promote_script" "handoff" true
check_path "$artifact_collection_json" "handoff"
check_path "$artifact_collection_md" "handoff"
if [[ -s "$artifact_collection_json" ]]; then
  if ! jq -e 'type == "object"' "$artifact_collection_json" >/dev/null; then
    append_issue "invalid-artifact-collection-json" "artifact-collection" "artifact collection JSON is not a JSON object" \
      "$(jq -n --arg path "$artifact_collection_json" '{path:$path}')"
  elif ! jq -e '.kind == "raknet-lab-artifact-collection"' "$artifact_collection_json" >/dev/null; then
    append_issue "invalid-artifact-collection-kind" "artifact-collection" "artifact collection JSON has an unexpected kind" \
      "$(jq -n --arg path "$artifact_collection_json" '{path:$path}')"
  else
    artifact_collection_groups_json="$(jq -c '[.collectionGroups[]?.id] | unique' "$artifact_collection_json")"
    if ! jq -e --arg manifest "$manifest" '.handoffManifest == $manifest' "$artifact_collection_json" >/dev/null; then
      append_issue "artifact-collection-handoff-manifest-mismatch" "artifact-collection" "artifact collection does not point at this handoff manifest" \
        "$(jq -n --arg expected "$manifest" --arg actual "$(jq -r '.handoffManifest // ""' "$artifact_collection_json")" '{expected:$expected,actual:$actual}')"
    fi
    if ! jq -e --arg path "$perfect_artifacts" '.perfectArtifacts == $path' "$artifact_collection_json" >/dev/null; then
      append_issue "artifact-collection-perfect-root-mismatch" "artifact-collection" "artifact collection perfect artifact root does not match the handoff manifest" \
        "$(jq -n --arg expected "$perfect_artifacts" --arg actual "$(jq -r '.perfectArtifacts // ""' "$artifact_collection_json")" '{expected:$expected,actual:$actual}')"
    fi
    if ! jq -e --arg path "$impairment_artifacts" '.impairmentArtifacts == $path' "$artifact_collection_json" >/dev/null; then
      append_issue "artifact-collection-impairment-root-mismatch" "artifact-collection" "artifact collection impairment artifact root does not match the handoff manifest" \
        "$(jq -n --arg expected "$impairment_artifacts" --arg actual "$(jq -r '.impairmentArtifacts // ""' "$artifact_collection_json")" '{expected:$expected,actual:$actual}')"
    fi
    if ! jq -e --argjson expected "$profiles_json" '(.profiles // []) == $expected' "$artifact_collection_json" >/dev/null; then
      append_issue "artifact-collection-profiles-mismatch" "artifact-collection" "artifact collection profile list does not match the handoff manifest" \
        "$(jq -n --arg path "$artifact_collection_json" '{path:$path}')"
    fi
    if ! jq -e --argjson expected "$expected_prereq_roles_json" '((.prereqRoles // []) | unique) == $expected' "$artifact_collection_json" >/dev/null; then
      append_issue "artifact-collection-prereq-roles-mismatch" "artifact-collection" "artifact collection prereq roles do not match the expected handoff roles" \
        "$(jq -n --arg path "$artifact_collection_json" '{path:$path}')"
    fi
    missing_collection_groups="$(jq -n -r --argjson expected "$required_collection_groups_json" --argjson actual "$artifact_collection_groups_json" '
      $expected[] as $group | select(($actual | index($group)) == null) | $group
    ')"
    while IFS= read -r group; do
      [[ -z "$group" ]] && continue
      append_issue "artifact-collection-group-missing" "artifact-collection" "artifact collection is missing a required group" \
        "$(jq -n --arg group "$group" --argjson expected "$required_collection_groups_json" --argjson actual "$artifact_collection_groups_json" '{group:$group,expectedGroups:$expected,actualGroups:$actual}')"
    done <<<"$missing_collection_groups"
  fi
fi
if [[ -x "$prereq_script" ]]; then
  helper_prereq_roles_output="$("$prereq_script" --list-roles 2>/dev/null || true)"
  if [[ -z "$helper_prereq_roles_output" ]]; then
    append_issue "handoff-prereq-helper-list-roles-failed" "prereq-helper" "prereq helper did not print valid roles" \
      "$(jq -n --arg path "$prereq_script" '{path:$path}')"
  else
    helper_prereq_roles_json="$(printf '%s\n' "$helper_prereq_roles_output" | jq -R -s 'split("\n") | map(select(length > 0)) | unique')"
  fi
fi
missing_manifest_prereq_roles="$(jq -n -r --argjson expected "$expected_prereq_roles_json" --argjson actual "$manifest_prereq_roles_json" '
  $expected[] as $role | select(($actual | index($role)) == null) | $role
')"
while IFS= read -r role; do
  [[ -z "$role" ]] && continue
  append_issue "handoff-prereq-role-missing" "handoff" "handoff manifest prereqRoles is missing a required role" \
    "$(jq -n --arg role "$role" --argjson expected "$expected_prereq_roles_json" --argjson actual "$manifest_prereq_roles_json" '{role:$role,expectedPrereqRoles:$expected,actualPrereqRoles:$actual}')"
done <<<"$missing_manifest_prereq_roles"
unexpected_manifest_prereq_roles="$(jq -n -r --argjson expected "$expected_prereq_roles_json" --argjson actual "$manifest_prereq_roles_json" '
  $actual[] as $role | select(($expected | index($role)) == null) | $role
')"
while IFS= read -r role; do
  [[ -z "$role" ]] && continue
  append_issue "handoff-prereq-role-unexpected" "handoff" "handoff manifest prereqRoles contains an unexpected role" \
    "$(jq -n --arg role "$role" --argjson expected "$expected_prereq_roles_json" --argjson actual "$manifest_prereq_roles_json" '{role:$role,expectedPrereqRoles:$expected,actualPrereqRoles:$actual}')"
done <<<"$unexpected_manifest_prereq_roles"
missing_helper_prereq_roles="$(jq -n -r --argjson expected "$expected_prereq_roles_json" --argjson actual "$helper_prereq_roles_json" '
  $expected[] as $role | select(($actual | index($role)) == null) | $role
')"
while IFS= read -r role; do
  [[ -z "$role" ]] && continue
  append_issue "handoff-prereq-helper-role-missing" "prereq-helper" "prereq helper does not list a required role" \
    "$(jq -n --arg role "$role" --argjson expected "$expected_prereq_roles_json" --argjson actual "$helper_prereq_roles_json" '{role:$role,expectedPrereqRoles:$expected,actualPrereqRoles:$actual}')"
done <<<"$missing_helper_prereq_roles"
unexpected_helper_prereq_roles="$(jq -n -r --argjson expected "$expected_prereq_roles_json" --argjson actual "$helper_prereq_roles_json" '
  $actual[] as $role | select(($expected | index($role)) == null) | $role
')"
while IFS= read -r role; do
  [[ -z "$role" ]] && continue
  append_issue "handoff-prereq-helper-role-unexpected" "prereq-helper" "prereq helper lists an unexpected role" \
    "$(jq -n --arg role "$role" --argjson expected "$expected_prereq_roles_json" --argjson actual "$helper_prereq_roles_json" '{role:$role,expectedPrereqRoles:$expected,actualPrereqRoles:$actual}')"
done <<<"$unexpected_helper_prereq_roles"
check_readme_contains "benchmark/scripts/check-lab-handoff.sh --handoff" "handoff README does not show the preflight command"
check_readme_contains "artifact-collection.json" "handoff README does not show the artifact collection JSON"
check_readme_contains "artifact-collection.md" "handoff README does not show the artifact collection checklist"
check_readme_contains "benchmark/scripts/check-lab-host-prereqs.sh" "handoff README does not show the host prerequisite check"
check_readme_contains "prereq-commands.sh" "handoff README does not show the generated prerequisite helper"
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
if [[ "$sudo_netem" == "true" ]]; then
  check_readme_contains "--require-sudo-netem" "handoff README host prerequisite command does not require sudo netem evidence for the shaped host"
fi
if [[ -n "$production_evidence_doc" ]]; then
  check_readme_contains "Production evidence document: \`$production_evidence_doc\`" "handoff README does not record the production evidence document"
fi
if [[ -n "$production_evidence_sha256" ]]; then
  check_readme_contains "Production evidence SHA-256: \`$production_evidence_sha256\`" "handoff README does not record the production evidence fingerprint"
fi
if [[ -n "$source_audit_doc" ]]; then
  check_readme_contains "Production source audit: \`$source_audit_doc\`" "handoff README does not record the production source audit"
  check_readme_contains "--require-current-revision" "handoff README preflight command does not require the current source-audit revision"
fi
if [[ -n "$source_audit_sha256" ]]; then
  check_readme_contains "Production source audit SHA-256: \`$source_audit_sha256\`" "handoff README does not record the production source audit fingerprint"
fi
check_readme_contains "benchmark/scripts/promote-lab-baseline.sh" "handoff README does not show the perfect-network promotion command"
check_readme_contains "promote-and-check.sh" "handoff README does not show the generated promotion/readiness helper"
check_readme_contains "--handoff-manifest \"$manifest\"" "handoff README promotion command does not pass the handoff manifest into baseline promotion"
check_readme_contains "--min-contention-clients \"$expected_contention_clients\"" "handoff README promotion command does not enforce the handoff contention client count"
check_readme_contains "--min-contention-target-client-mbps \"$expected_per_client_mbps\"" "handoff README promotion command does not enforce the handoff per-client Mbps target"
check_readme_contains "benchmark/scripts/promote-lab-impairment.sh" "handoff README does not show the impairment promotion command"
check_readme_contains "benchmark/scripts/check-baseline-readiness.sh" "handoff README does not show the final readiness command"
check_readme_contains "--handoff \"$handoff_root\"" "handoff README readiness command does not attach this handoff"
check_readme_contains "--required-min-contention-clients \"$expected_contention_clients\"" "handoff README readiness command does not enforce the handoff contention client count"
check_readme_contains "--required-min-contention-target-client-mbps \"$expected_per_client_mbps\"" "handoff README readiness command does not enforce the handoff per-client Mbps target"
check_readme_contains "--required-batch-intervals-ms \"$batch_intervals\"" "handoff README readiness command does not enforce the handoff batch intervals"
check_readme_contains "--required-resource-pack-chunk-sizes \"$resource_pack_chunk_sizes\"" "handoff README readiness command does not enforce the handoff resource-pack chunk sizes"
check_readme_contains "--required-resource-pack-intervals-ms \"$resource_pack_interval\"" "handoff README readiness command does not enforce the handoff resource-pack interval"
check_readme_contains "--required-disappearance-modes \"$required_disappearance_modes\"" "handoff README readiness command does not enforce required disappearance modes"
check_file_contains "$promote_script" "benchmark/scripts/check-lab-handoff.sh" "promotion-helper" "promotion helper does not rerun handoff preflight"
check_file_contains "$promote_script" "--handoff \"$handoff_root\"" "promotion-helper" "promotion helper preflight does not use this handoff directory"
check_file_contains "$promote_script" "--out \"\$PREFLIGHT_OUT\"" "promotion-helper" "promotion helper preflight does not write to the configured preflight output"
if [[ -n "$source_audit_doc" ]]; then
  check_file_contains "$promote_script" "--require-source-audit" "promotion-helper" "promotion helper preflight does not require the source audit"
  check_file_contains "$promote_script" "--require-current-revision" "promotion-helper" "promotion helper preflight does not require the current source-audit revision"
fi
check_file_contains "$promote_script" "benchmark/scripts/promote-lab-baseline.sh" "promotion-helper" "promotion helper does not promote the perfect-network baseline"
check_file_contains "$promote_script" "--input \"$perfect_artifacts/combined\"" "promotion-helper" "promotion helper perfect-network promotion does not use the handoff artifact root"
check_file_contains "$promote_script" "--handoff-manifest \"$manifest\"" "promotion-helper" "promotion helper perfect-network promotion does not pass the handoff manifest"
check_file_contains "$promote_script" "--manifest \"$perfect_plan/curve-plan/manifest.jsonl\"" "promotion-helper" "promotion helper perfect-network promotion does not pass the curve manifest"
check_file_contains "$promote_script" "--manifest \"$perfect_plan/curve-raised-plan/manifest.jsonl\"" "promotion-helper" "promotion helper perfect-network promotion does not pass the raised-curve manifest"
check_file_contains "$promote_script" "--manifest \"$perfect_plan/contention-plan/manifest.jsonl\"" "promotion-helper" "promotion helper perfect-network promotion does not pass the contention manifest"
check_file_contains "$promote_script" "--min-contention-clients \"$expected_contention_clients\"" "promotion-helper" "promotion helper perfect-network promotion does not enforce the handoff contention client count"
check_file_contains "$promote_script" "--min-contention-target-client-mbps \"$expected_per_client_mbps\"" "promotion-helper" "promotion helper perfect-network promotion does not enforce the handoff per-client Mbps target"
check_file_contains "$promote_script" "benchmark/scripts/promote-lab-impairment.sh" "promotion-helper" "promotion helper does not promote the impairment baseline"
check_file_contains "$promote_script" "--input \"$impairment_artifacts/campaign-summary\"" "promotion-helper" "promotion helper impairment promotion does not use the handoff artifact root"
check_file_contains "$promote_script" "benchmark/scripts/check-baseline-readiness.sh" "promotion-helper" "promotion helper does not run the final readiness gate"
check_file_contains "$promote_script" "--handoff \"$handoff_root\"" "promotion-helper" "promotion helper readiness check does not attach this handoff"
check_file_contains "$promote_script" "--required-min-contention-clients \"$expected_contention_clients\"" "promotion-helper" "promotion helper readiness check does not enforce the handoff contention client count"
check_file_contains "$promote_script" "--required-min-contention-target-client-mbps \"$expected_per_client_mbps\"" "promotion-helper" "promotion helper readiness check does not enforce the handoff per-client Mbps target"
check_file_contains "$promote_script" "--required-batch-intervals-ms \"$batch_intervals\"" "promotion-helper" "promotion helper readiness check does not enforce handoff batch intervals"
check_file_contains "$promote_script" "--required-resource-pack-chunk-sizes \"$resource_pack_chunk_sizes\"" "promotion-helper" "promotion helper readiness check does not enforce handoff resource-pack chunk sizes"
check_file_contains "$promote_script" "--required-resource-pack-intervals-ms \"$resource_pack_interval\"" "promotion-helper" "promotion helper readiness check does not enforce handoff resource-pack interval"
check_file_contains "$promote_script" "--required-disappearance-modes \"$required_disappearance_modes\"" "promotion-helper" "promotion helper readiness check does not enforce required disappearance modes"
check_file_contains "$prereq_script" "benchmark/scripts/check-lab-host-prereqs.sh" "prereq-helper" "prereq helper does not run the host prerequisite checker"
if [[ -n "$expected_mtu" ]]; then
  check_file_contains "$prereq_script" "--expect-mtu \"$expected_mtu\"" "prereq-helper" "prereq helper does not enforce the handoff expected MTU"
fi
if [[ -n "$expected_min_cpus" ]]; then
  check_file_contains "$prereq_script" "--expect-min-cpus \"$expected_min_cpus\"" "prereq-helper" "prereq helper does not enforce the handoff minimum CPU count"
fi
check_file_contains "$prereq_script" "--require-clock-sync" "prereq-helper" "prereq helper does not require clock-sync evidence"
check_file_contains "$prereq_script" "--require-no-netem" "prereq-helper" "prereq helper does not require a clean qdisc/no-netem state"
if [[ "$require_cpu_performance" == "true" ]]; then
  check_file_contains "$prereq_script" "--require-cpu-performance" "prereq-helper" "prereq helper does not require CPU performance-governor evidence"
fi
if [[ "$sudo_netem" == "true" && -n "$target_host_role" ]]; then
  check_file_contains "$prereq_script" "--require-sudo-netem" "prereq-helper" "prereq helper does not require sudo netem capability for the shaped host"
  check_file_contains "$prereq_script" "TARGET_HOST_ROLE=\"$target_host_role\"" "prereq-helper" "prereq helper does not record the handoff target host role"
fi
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

  if [[ -n "$expected_reliability" ]]; then
    local reliability_mismatches
    reliability_mismatches="$(jq -r -s --arg expected "$expected_reliability" '
      .[]
      | select((.reliability // "") != $expected)
      | [(.case // ""), (.benchmarkName // ""), (.reliability // "")] | @tsv
    ' "$path")"
    while IFS=$'\t' read -r case_name benchmark_name actual_reliability; do
      [[ -z "$case_name" && -z "$benchmark_name" ]] && continue
      append_issue "curve-reliability-mismatch" "$label" "curve manifest row reliability does not match the handoff reliability" \
        "$(jq -n --arg path "$path" --arg case "$case_name" --arg benchmarkName "$benchmark_name" --arg expected "$expected_reliability" --arg actual "$actual_reliability" '{path:$path,case:$case,benchmarkName:$benchmarkName,expectedReliability:$expected,actualReliability:$actual}')"
    done <<<"$reliability_mismatches"
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

check_worker_plan_scripts() {
  local label="$1"
  local plan="$2"
  check_path "$plan/server-commands.sh" "$label" true
  check_path "$plan/merge-commands.sh" "$label" true
  check_worker_command_script "$label" "$plan/server-commands.sh"

  local receiver_count=0
  local receiver_script
  while IFS= read -r receiver_script; do
    [[ -z "$receiver_script" ]] && continue
    receiver_count=$((receiver_count + 1))
    check_path "$receiver_script" "$label" true
    check_worker_command_script "$label" "$receiver_script"
  done < <(find "$plan" -maxdepth 1 -type f -name 'receiver-*-commands.sh' 2>/dev/null | sort)

  if [[ "$receiver_count" -eq 0 ]]; then
    append_issue "missing-receiver-command-script" "$label" "worker plan has no receiver command script" \
      "$(jq -n --arg path "$plan" '{path:$path}')"
  fi
}

check_worker_command_script() {
  local label="$1"
  local path="$2"
  [[ -s "$path" ]] || return

  local common_args="--warmup $expected_warmup --duration $expected_duration --iterations $expected_iterations --reliability $expected_reliability"
  if [[ -n "$expected_warmup" && -n "$expected_duration" && -n "$expected_iterations" && -n "$expected_reliability" ]] \
      && ! grep -Fq -- "$common_args" "$path"; then
    append_issue "worker-command-mismatch" "$label" "worker command script does not match handoff warmup, duration, iterations, or reliability" \
      "$(jq -n --arg path "$path" --arg expected "$common_args" '{path:$path,expectedCommandArgs:$expected}')"
  fi

  if [[ "$expected_max_queued_bytes" =~ ^[0-9]+$ ]] \
      && ! grep -Fq -- "--max-queued-bytes $expected_max_queued_bytes" "$path"; then
    append_issue "worker-command-mismatch" "$label" "worker command script does not match handoff max queued bytes" \
      "$(jq -n --arg path "$path" --argjson expectedMaxQueuedBytes "$expected_max_queued_bytes" '{path:$path,expectedMaxQueuedBytes:$expectedMaxQueuedBytes}')"
  fi
}

check_contention_manifest() {
  local label="$1"
  local path="$2"
  check_path "$path" "$label"
  if [[ ! -s "$path" ]]; then
    return
  fi

  if [[ -n "$expected_reliability" ]]; then
    local reliability_mismatches
    reliability_mismatches="$(jq -r -s --arg expected "$expected_reliability" '
      .[]
      | select((.reliability // "") != $expected)
      | [(.case // ""), (.benchmarkName // ""), (.reliability // "")] | @tsv
    ' "$path")"
    while IFS=$'\t' read -r case_name benchmark_name actual_reliability; do
      [[ -z "$case_name" && -z "$benchmark_name" ]] && continue
      append_issue "contention-reliability-mismatch" "$label" "contention manifest row reliability does not match the handoff reliability" \
        "$(jq -n --arg path "$path" --arg case "$case_name" --arg benchmarkName "$benchmark_name" --arg expected "$expected_reliability" --arg actual "$actual_reliability" '{path:$path,case:$case,benchmarkName:$benchmarkName,expectedReliability:$expected,actualReliability:$actual}')"
    done <<<"$reliability_mismatches"
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

	  if jq -n -e --argjson scenarios "$expected_contention_scenarios_json" '$scenarios | index("batched-game-traffic") != null' >/dev/null; then
	    local missing_batch_intervals
    missing_batch_intervals="$(jq -r -s --argjson expected "$required_batch_intervals_millis_json" '
      ([.[] | select((.benchmarkName // "") == "batched-game-traffic") | (.batchIntervalMillis // empty | tonumber)] | unique) as $actual
      | $expected[] as $interval
      | select(($actual | index($interval)) == null)
      | $interval
    ' "$path")"
    while IFS= read -r interval; do
      [[ -z "$interval" ]] && continue
      append_issue "contention-missing-batch-interval" "$label" "contention manifest is missing a required batched-game-traffic interval" \
        "$(jq -n --arg path "$path" --argjson batchIntervalMillis "$interval" '{path:$path,batchIntervalMillis:$batchIntervalMillis}')"
	    done <<<"$missing_batch_intervals"
	  fi

	  if jq -n -e --argjson scenarios "$expected_contention_scenarios_json" '$scenarios | index("disappearing-clients") != null' >/dev/null; then
	    local missing_disappearance_modes
	    missing_disappearance_modes="$(jq -r -s --argjson expected "$required_disappearance_modes_json" '
	      def mode($row):
	        if (($row.disappearanceMode // "") != "") then $row.disappearanceMode
	        elif (($row.affectedKind // "") | startswith("disappearing-")) then (($row.affectedKind // "") | sub("^disappearing-"; ""))
	        elif ((($row.case // "") | ascii_downcase) | contains("blackhole")) then "blackhole"
	        elif ((($row.case // "") | ascii_downcase) | contains("stopread")) or ((($row.case // "") | ascii_downcase) | contains("stop-reading")) then "stop-reading"
	        elif ((($row.case // "") | ascii_downcase) | contains("close")) then "close"
	        else null end;
	      ([.[] | select((.benchmarkName // "") == "disappearing-clients") | mode(.) | select(. != null)] | unique) as $actual
	      | $expected[] as $mode
	      | select(($actual | index($mode)) == null)
	      | $mode
	    ' "$path")"
	    while IFS= read -r mode; do
	      [[ -z "$mode" ]] && continue
	      append_issue "contention-missing-disappearance-mode" "$label" "contention manifest is missing a required disappearing-client mode" \
	        "$(jq -n --arg path "$path" --arg disappearanceMode "$mode" '{path:$path,disappearanceMode:$disappearanceMode}')"
	    done <<<"$missing_disappearance_modes"
	  fi

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

  if jq -n -e --argjson scenarios "$expected_contention_scenarios_json" '$scenarios | index("resource-pack-transfer") != null' >/dev/null; then
    if jq -n -e --argjson expected "$expected_resource_pack_payloads_json" '$expected | length == 0' >/dev/null; then
      append_issue "handoff-missing-resource-pack-chunk-sizes" "$label" "handoff manifest requires resource-pack-transfer but has no resourcePackChunkSizes" \
        "$(jq -n --arg path "$manifest" '{path:$path}')"
    fi

    local missing_resource_payloads
    missing_resource_payloads="$(jq -r -s --argjson expected "$expected_resource_pack_payloads_json" '
      ([.[] | select((.benchmarkName // "") == "resource-pack-transfer") | (.payloadSize // empty | tonumber)] | unique) as $actual
      | $expected[] as $payload
      | select(($actual | index($payload)) == null)
      | $payload
    ' "$path")"
    while IFS= read -r payload; do
      [[ -z "$payload" ]] && continue
      append_issue "contention-missing-resource-pack-payload" "$label" "contention manifest is missing a required resource-pack payload size" \
        "$(jq -n --arg path "$path" --argjson payloadSize "$payload" '{path:$path,payloadSize:$payloadSize}')"
    done <<<"$missing_resource_payloads"

    local missing_resource_shapes
    missing_resource_shapes="$(jq -r -s --argjson expectedPayloads "$required_resource_pack_payloads_json" --argjson expectedIntervals "$required_resource_pack_intervals_millis_json" '
      ([.[] | select((.benchmarkName // "") == "resource-pack-transfer") | {
        payloadSize: (.payloadSize // empty | tonumber),
        batchIntervalMillis: (.batchIntervalMillis // empty | tonumber)
      }]) as $actual
      | $expectedPayloads[] as $payload
      | $expectedIntervals[] as $interval
      | select((any($actual[]; .payloadSize == $payload and .batchIntervalMillis == $interval)) | not)
      | [$payload, $interval] | @tsv
    ' "$path")"
    while IFS=$'\t' read -r payload interval; do
      [[ -z "$payload" || -z "$interval" ]] && continue
      append_issue "contention-missing-resource-pack-shape" "$label" "contention manifest is missing a required resource-pack payload and interval shape" \
        "$(jq -n --arg path "$path" --argjson payloadSize "$payload" --argjson batchIntervalMillis "$interval" '{path:$path,payloadSize:$payloadSize,batchIntervalMillis:$batchIntervalMillis}')"
    done <<<"$missing_resource_shapes"
  fi

  local rate_mismatches
  rate_mismatches="$(jq -r -s --argjson expected "$expected_per_client_mbps" --argjson immediateExpected "$expected_immediate_per_client_mbps" '
    def immediate_row:
      ((.affectedKind // "") == "immediate")
      or (((.case // "") | ascii_downcase) | contains("immediate"));
    .[]
    | select((.benchmarkName // "") != "resource-pack-transfer")
    | (if immediate_row then $immediateExpected else $expected end) as $rowExpected
    | select(((((.perClientMbps // -1) | tonumber) - $rowExpected) | fabs) > 0.000001)
    | [(.case // ""), (.benchmarkName // ""), ($rowExpected | tostring), ((.perClientMbps // -1) | tostring)] | @tsv
  ' "$path")"
  while IFS=$'\t' read -r case_name benchmark_name expected_row_per_client_mbps actual_per_client_mbps; do
    [[ -z "$case_name" && -z "$benchmark_name" ]] && continue
    append_issue "contention-per-client-mbps-mismatch" "$label" "contention manifest row per-client Mbps does not match the handoff target" \
      "$(jq -n --arg path "$path" --arg case "$case_name" --arg benchmarkName "$benchmark_name" --argjson expected "${expected_row_per_client_mbps:-0}" --argjson actual "${actual_per_client_mbps:-0}" '{path:$path,case:$case,benchmarkName:$benchmarkName,expectedPerClientMbps:$expected,actualPerClientMbps:$actual}')"
  done <<<"$rate_mismatches"
}

check_curve_manifest "perfect-curve" "$perfect_plan/curve-plan/manifest.jsonl"
check_curve_manifest "perfect-raised-curve" "$perfect_plan/curve-raised-plan/manifest.jsonl"
check_contention_manifest "perfect-contention" "$perfect_plan/contention-plan/manifest.jsonl"
check_worker_plan_scripts "perfect-curve-plan" "$perfect_plan/curve-plan"
check_worker_plan_scripts "perfect-raised-curve-plan" "$perfect_plan/curve-raised-plan"
check_worker_plan_scripts "perfect-contention-plan" "$perfect_plan/contention-plan"

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
    check_worker_plan_scripts "impairment-profile:$profile:curve-plan" "$plan/curve-plan"
    check_worker_plan_scripts "impairment-profile:$profile:raised-curve-plan" "$plan/curve-raised-plan"
    check_worker_plan_scripts "impairment-profile:$profile:contention-plan" "$plan/contention-plan"
  done < <(jq -r '[.profile, .plan, .applyScript, .statusScript, .clearScript] | @tsv' "$impairment_plan/manifest.jsonl")
fi

row_count() {
  local path="$1"
  if [[ -s "$path" ]]; then
    jq -s 'length' "$path"
  else
    printf '0\n'
  fi
}

actual_perfect_curve_rows="$(row_count "$perfect_plan/curve-plan/manifest.jsonl")"
actual_perfect_raised_curve_rows="$(row_count "$perfect_plan/curve-raised-plan/manifest.jsonl")"
actual_perfect_contention_rows="$(row_count "$perfect_plan/contention-plan/manifest.jsonl")"
if [[ -s "$impairment_plan/manifest.jsonl" ]]; then
  actual_impairment_profile_rows="$(while IFS=$'\t' read -r profile plan; do
    [[ -z "$profile" ]] && continue
    jq -n \
      --arg profile "$profile" \
      --argjson curveRows "$(row_count "$plan/curve-plan/manifest.jsonl")" \
      --argjson raisedCurveRows "$(row_count "$plan/curve-raised-plan/manifest.jsonl")" \
      --argjson contentionRows "$(row_count "$plan/contention-plan/manifest.jsonl")" \
      '{profile:$profile,curveRows:$curveRows,raisedCurveRows:$raisedCurveRows,contentionRows:$contentionRows}'
  done < <(jq -r '[.profile, .plan] | @tsv' "$impairment_plan/manifest.jsonl") | jq -s '.')"
else
  actual_impairment_profile_rows="[]"
fi

issues_array="$(jq -s '.' "$issues_jsonl")"

jq -n \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg handoffRoot "$handoff_root" \
  --arg manifest "$manifest" \
  --arg artifactCollectionJson "$artifact_collection_json" \
  --arg artifactCollectionMd "$artifact_collection_md" \
  --argjson expectedCurvePayloadSizes "$curve_payloads_json" \
  --argjson expectedCurveRatesMbps "$curve_rates_json" \
  --argjson expectedProfiles "$profiles_json" \
  --argjson requiredArtifactCollectionGroups "$required_collection_groups_json" \
  --argjson artifactCollectionGroups "$artifact_collection_groups_json" \
  --argjson expectedContentionScenarios "$expected_contention_scenarios_json" \
  --argjson expectedResourcePackPayloadSizes "$expected_resource_pack_payloads_json" \
  --argjson expectedBatchIntervalsMillis "$expected_batch_intervals_millis_json" \
  --argjson expectedResourcePackIntervalsMillis "$expected_resource_pack_intervals_millis_json" \
  --argjson requiredBatchIntervalsMillis "$required_batch_intervals_millis_json" \
  --argjson requiredResourcePackPayloadSizes "$required_resource_pack_payloads_json" \
  --argjson requiredResourcePackIntervalsMillis "$required_resource_pack_intervals_millis_json" \
  --argjson requiredDisappearanceModes "$required_disappearance_modes_json" \
  --argjson expectedContentionClients "$expected_contention_clients" \
  --arg expectedReliability "$expected_reliability" \
  --argjson expectedPerClientMbps "$expected_per_client_mbps" \
  --argjson expectedImmediatePerClientMbps "$expected_immediate_per_client_mbps" \
  --argjson expectedMtu "$expected_mtu_json" \
  --argjson expectedMinCpus "$expected_min_cpus_json" \
  --argjson expectedPrereqRoles "$expected_prereq_roles_json" \
  --argjson manifestPrereqRoles "$manifest_prereq_roles_json" \
  --argjson helperPrereqRoles "$helper_prereq_roles_json" \
  --argjson requireCpuPerformance "$require_cpu_performance" \
  --argjson expectedIterations "$expected_iterations_json" \
  --argjson requiredMinIterations "$required_min_iterations" \
  --argjson requiredMinContentionClients "$required_min_contention_clients" \
  --argjson requiredMinContentionTargetClientMbps "$required_min_contention_target_client_mbps" \
  --argjson productionEvidence "$production_evidence_json" \
  --arg productionEvidencePath "$production_evidence_path" \
  --arg productionEvidenceActualSha256 "$production_evidence_actual_sha256" \
  --argjson sourceAudit "$source_audit_json" \
  --arg sourceAuditPath "$source_audit_path" \
  --arg sourceAuditActualSha256 "$source_audit_actual_sha256" \
  --arg currentNetworkRevision "$current_network_revision" \
  --arg currentNetworkShortRevision "$current_network_short_revision" \
  --argjson sourceAuditActualReady "$source_audit_actual_ready" \
  --argjson requireSourceAudit "$require_source_audit" \
  --argjson requireCurrentRevision "$require_current_revision" \
  --argjson expectedCurveRows "$expected_curve_rows" \
  --argjson actualPerfectCurveRows "$actual_perfect_curve_rows" \
  --argjson actualPerfectRaisedCurveRows "$actual_perfect_raised_curve_rows" \
  --argjson actualPerfectContentionRows "$actual_perfect_contention_rows" \
  --argjson actualImpairmentProfileRows "$actual_impairment_profile_rows" \
  --argjson issues "$issues_array" \
  '{
    checkedAt: $checkedAt,
    ready: (($issues | length) == 0),
    issueCount: ($issues | length),
    handoffRoot: $handoffRoot,
    handoffManifest: $manifest,
    artifactCollectionJson: $artifactCollectionJson,
    artifactCollectionMd: $artifactCollectionMd,
    requiredArtifactCollectionGroups: $requiredArtifactCollectionGroups,
    artifactCollectionGroups: $artifactCollectionGroups,
    expectedCurvePayloadSizes: $expectedCurvePayloadSizes,
    expectedCurveRatesMbps: $expectedCurveRatesMbps,
    expectedCurveRowsPerCurvePlan: $expectedCurveRows,
    actualPerfectCurveRows: $actualPerfectCurveRows,
    actualPerfectRaisedCurveRows: $actualPerfectRaisedCurveRows,
    actualPerfectContentionRows: $actualPerfectContentionRows,
    actualImpairmentProfileRows: $actualImpairmentProfileRows,
    expectedProfiles: $expectedProfiles,
    expectedContentionScenarios: $expectedContentionScenarios,
    expectedResourcePackPayloadSizes: $expectedResourcePackPayloadSizes,
    expectedBatchIntervalsMillis: $expectedBatchIntervalsMillis,
    expectedResourcePackIntervalsMillis: $expectedResourcePackIntervalsMillis,
    requiredBatchIntervalsMillis: $requiredBatchIntervalsMillis,
    requiredResourcePackPayloadSizes: $requiredResourcePackPayloadSizes,
    requiredResourcePackIntervalsMillis: $requiredResourcePackIntervalsMillis,
    requiredDisappearanceModes: $requiredDisappearanceModes,
    expectedContentionClients: $expectedContentionClients,
    expectedReliability: $expectedReliability,
    expectedPerClientMbps: $expectedPerClientMbps,
    expectedImmediatePerClientMbps: $expectedImmediatePerClientMbps,
    expectedMtu: $expectedMtu,
    expectedMinCpus: $expectedMinCpus,
    expectedPrereqRoles: $expectedPrereqRoles,
    manifestPrereqRoles: $manifestPrereqRoles,
    helperPrereqRoles: $helperPrereqRoles,
    requireCpuPerformance: $requireCpuPerformance,
    expectedIterations: $expectedIterations,
    requiredMinIterations: $requiredMinIterations,
    productionEvidence: $productionEvidence,
    productionEvidencePath: $productionEvidencePath,
    productionEvidenceActualSha256: $productionEvidenceActualSha256,
    sourceAudit: $sourceAudit,
    sourceAuditPath: $sourceAuditPath,
    sourceAuditActualSha256: $sourceAuditActualSha256,
    sourceAuditActualReady: $sourceAuditActualReady,
    requireSourceAudit: $requireSourceAudit,
    requireCurrentRevision: $requireCurrentRevision,
    currentNetworkRevision: $currentNetworkRevision,
    currentNetworkShortRevision: $currentNetworkShortRevision,
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
  echo "- Artifact collection JSON: \`$(jq -r '.artifactCollectionJson' "$check_json")\`"
  echo "- Artifact collection checklist: \`$(jq -r '.artifactCollectionMd' "$check_json")\`"
  echo "- Artifact collection groups: \`$(jq -r '.artifactCollectionGroups | join(",")' "$check_json")\`"
  echo "- Expected curve rows per curve plan: \`$(jq -r '.expectedCurveRowsPerCurvePlan' "$check_json")\`"
  echo "- Actual perfect curve rows: \`$(jq -r '.actualPerfectCurveRows' "$check_json")\`"
  echo "- Actual perfect raised curve rows: \`$(jq -r '.actualPerfectRaisedCurveRows' "$check_json")\`"
  echo "- Actual perfect contention rows: \`$(jq -r '.actualPerfectContentionRows' "$check_json")\`"
  echo "- Expected reliability: \`$(jq -r '.expectedReliability' "$check_json")\`"
  echo "- Expected contention clients: \`$(jq -r '.expectedContentionClients' "$check_json")\`"
  echo "- Expected per-client Mbps: \`$(jq -r '.expectedPerClientMbps' "$check_json")\`"
  echo "- Required batch intervals ms: \`$required_batch_intervals_ms\`"
  echo "- Required resource-pack chunk sizes: \`$required_resource_pack_chunk_sizes\`"
  echo "- Required resource-pack intervals ms: \`$required_resource_pack_intervals_ms\`"
  echo "- Required disappearance modes: \`$required_disappearance_modes\`"
  echo "- Expected MTU: \`$(jq -r '.expectedMtu' "$check_json")\`"
  echo "- Expected minimum CPUs: \`$(jq -r '.expectedMinCpus' "$check_json")\`"
  echo "- Expected prereq roles: \`$(jq -r '.expectedPrereqRoles | join(",")' "$check_json")\`"
  echo "- Helper prereq roles: \`$(jq -r '.helperPrereqRoles | join(",")' "$check_json")\`"
  echo "- Require CPU performance governor: \`$(jq -r '.requireCpuPerformance' "$check_json")\`"
  echo "- Expected measured iterations: \`$(jq -r '.expectedIterations' "$check_json")\`"
  echo "- Required minimum iterations: \`$(jq -r '.requiredMinIterations' "$check_json")\`"
  echo "- Require source audit: \`$(jq -r '.requireSourceAudit' "$check_json")\`"
  echo "- Require current revision: \`$(jq -r '.requireCurrentRevision' "$check_json")\`"
  echo "- Current Network revision: \`$(jq -r '.currentNetworkShortRevision' "$check_json")\`"
  echo "- Source audit ready: \`$(jq -r '.sourceAuditActualReady' "$check_json")\`"
  echo "- Required minimum contention clients: \`$(jq -r '.requiredMinContentionClients' "$check_json")\`"
  echo "- Required minimum per-client Mbps: \`$(jq -r '.requiredMinContentionTargetClientMbps' "$check_json")\`"
  echo
  echo "## Impairment Profile Rows"
  echo
  if jq -e '.actualImpairmentProfileRows | length == 0' "$check_json" >/dev/null; then
    echo "No impairment profiles found."
  else
    echo "| Profile | Curve rows | Raised curve rows | Contention rows |"
    echo "| --- | ---: | ---: | ---: |"
    jq -r '.actualImpairmentProfileRows[] | "| `\(.profile)` | \(.curveRows) | \(.raisedCurveRows) | \(.contentionRows) |"' "$check_json"
  fi
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
