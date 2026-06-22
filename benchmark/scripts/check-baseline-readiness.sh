#!/usr/bin/env bash
set -euo pipefail

lab_baseline="benchmark/build/benchmark-baselines/latest"
impairment_baseline="benchmark/build/benchmark-baselines/latest-impairment"
handoff_root=""
out_dir=""
expected_impairment_profiles="perfect,near-loss,regional-loss,poor,severe"
required_curve_payload_sizes="64,256,512,1200,1340,1400,262144"
required_impairment_contention_scenarios="multi-client-fanout,fairness,disappearing-clients,batched-game-traffic,resource-pack-transfer"
required_batch_intervals_ms="10,20,50"
required_immediate_payload_sizes="256"
required_immediate_target_client_mbps="1"
required_resource_pack_chunk_sizes="8192,262144"
required_resource_pack_intervals_ms="200"
required_disappearance_modes="blackhole"
required_retry_pressure_fields="undeliveredServerGbps,affectedUndeliveredServerGbps,affectedServerDatagramsOutPerSecond"
required_min_iterations="3"
required_min_contention_clients="500"
required_min_contention_target_client_mbps="5"
required_min_prereq_reports="2"
required_min_ready_prereq_reports="2"
required_min_prereq_distinct_hostnames="2"
require_source_audit=true

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/check-baseline-readiness.sh [options]

Checks whether promoted benchmark artifacts are ready to be used as the
baseline of record for performance engineering comparisons.

Options:
  --lab-baseline PATH              Promoted perfect-network baseline directory. Default: benchmark/build/benchmark-baselines/latest.
  --impairment-baseline PATH       Promoted impairment campaign baseline directory. Default: benchmark/build/benchmark-baselines/latest-impairment.
  --handoff PATH                   Optional fresh lab handoff directory to include in readiness evidence.
  --expected-impairment-profiles CSV Required impairment profiles. Default: perfect,near-loss,regional-loss,poor,severe.
  --required-curve-payload-sizes CSV Required perfect-network curve payload sizes. Default: 64,256,512,1200,1340,1400,262144.
  --required-impairment-contention-scenarios CSV Required contention scenarios per impairment profile. Default: multi-client-fanout,fairness,disappearing-clients,batched-game-traffic,resource-pack-transfer.
  --required-batch-intervals-ms CSV Required batched-game-traffic intervals in milliseconds. Default: 10,20,50.
  --required-immediate-payload-sizes CSV Required immediate small-packet fanout payload sizes. Default: 256.
  --required-immediate-target-client-mbps N Required immediate small-packet fanout target/client Mbps. Default: 1.
  --required-resource-pack-chunk-sizes CSV Required resource-pack chunk payload sizes. Default: 8192,262144.
  --required-resource-pack-intervals-ms CSV Required resource-pack intervals in milliseconds. Default: 200.
  --required-disappearance-modes CSV Required disappearing-client modes. Default: blackhole.
  --required-retry-pressure-fields CSV Required aggregate fields for send-work/retry-pressure comparison. Default: undeliveredServerGbps,affectedUndeliveredServerGbps,affectedServerDatagramsOutPerSecond.
  --required-min-iterations N     Required lab validation iteration gate. Default: 3.
  --required-min-contention-clients N Required lab validation contention-client gate. Default: 500.
  --required-min-contention-target-client-mbps N Required lab validation per-client Mbps gate. Default: 5.
  --required-min-prereq-reports N Required lab prereq reports. Default: 2.
  --required-min-ready-prereq-reports N Required ready lab prereq reports. Default: 2.
  --required-min-prereq-distinct-hostnames N Required distinct lab prereq hostnames. Default: 2.
  --require-source-audit          Require promoted baseline source-audit metadata. Default.
  --no-require-source-audit       Do not require source-audit metadata. Intended for legacy smoke artifacts only.
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
    --handoff)
      handoff_root="$2"
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
    --required-batch-intervals-ms)
      required_batch_intervals_ms="$2"
      shift 2
      ;;
    --required-immediate-payload-sizes)
      required_immediate_payload_sizes="$2"
      shift 2
      ;;
    --required-immediate-target-client-mbps)
      required_immediate_target_client_mbps="$2"
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
    --required-retry-pressure-fields)
      required_retry_pressure_fields="$2"
      shift 2
      ;;
    --required-min-iterations)
      required_min_iterations="$2"
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
    --require-source-audit)
      require_source_audit=true
      shift
      ;;
    --no-require-source-audit)
      require_source_audit=false
      shift
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
if ! [[ "$required_immediate_target_client_mbps" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "--required-immediate-target-client-mbps must be a non-negative number" >&2
  exit 2
fi
if ! [[ "$required_min_iterations" =~ ^[0-9]+$ ]]; then
  echo "--required-min-iterations must be a non-negative integer" >&2
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
if [[ -n "$handoff_root" ]]; then
  handoff_root="$(resolve_path "$handoff_root")"
fi
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
impairment_packaged_profiles_jsonl="$(mktemp)"
trap 'rm -f "$issues_jsonl" "$impairment_packaged_profiles_jsonl"' EXIT
: >"$issues_jsonl"
: >"$impairment_packaged_profiles_jsonl"

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

safe_name() {
  local value="${1,,}"
  value="${value//[^a-z0-9._-]/-}"
  value="${value//--/-}"
  value="${value#-}"
  value="${value%-}"
  if [[ -z "$value" ]]; then
    value="profile"
  fi
  printf '%s' "$value"
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

lab_manifest="$lab_baseline/baseline-manifest.json"
lab_handoff_manifest="$lab_baseline/handoff-manifest.json"
lab_artifact_collection_json="$lab_baseline/artifact-collection.json"
lab_artifact_collection_md="$lab_baseline/artifact-collection.md"
lab_validation="$lab_baseline/validation.json"
lab_aggregate="$lab_baseline/suite-aggregate.jsonl"
lab_capacity="$lab_baseline/bandwidth-capacity.jsonl"
lab_source_audit_json="null"
lab_handoff_source_audit_json="null"
lab_handoff_kind=""
lab_artifact_collection_groups_json="[]"
lab_packaged_manifest_count="0"
lab_packaged_host_report_count="0"
lab_packaged_prereq_summary="$(jq -n '{
  reportCount: 0,
  readyReportCount: 0,
  notReadyReportCount: 0,
  distinctHostnameCount: 0,
  strictReportCount: 0,
  strictDistinctHostnameCount: 0,
  hostnames: [],
  strictHostnames: []
}')"
required_curve_payloads_json="$(csv_json_number_array "$required_curve_payload_sizes")"
required_impairment_contention_json="$(csv_json_array "$required_impairment_contention_scenarios")"
required_batch_intervals_json="$(csv_json_duration_millis_array "$required_batch_intervals_ms")"
required_immediate_payloads_json="$(csv_json_number_array "$required_immediate_payload_sizes")"
required_resource_pack_chunks_json="$(csv_json_number_array "$required_resource_pack_chunk_sizes")"
required_resource_pack_intervals_json="$(csv_json_duration_millis_array "$required_resource_pack_intervals_ms")"
required_disappearance_modes_json="$(csv_json_array "$required_disappearance_modes")"
required_retry_pressure_fields_json="$(csv_json_array "$required_retry_pressure_fields")"
required_artifact_collection_groups_json="$(jq -n -c '[
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
handoff_summary_json="null"
handoff_preflight_json="null"
handoff_manifest_path=""
handoff_summary_path=""
handoff_preflight_path=""
handoff_provided_json=false
handoff_ready_json=false
if [[ -n "$handoff_root" ]]; then
  handoff_provided_json=true
  handoff_manifest_path="$handoff_root/handoff-manifest.json"
  handoff_summary_path="$handoff_root/fresh-handoff-summary.json"
  handoff_preflight_path="$handoff_root/preflight/handoff-check.json"
  if [[ ! -s "$handoff_manifest_path" ]]; then
    append_issue "handoff-missing-manifest" "handoff" "fresh lab handoff manifest is missing" "{\"path\":\"$handoff_manifest_path\"}"
  elif ! jq -e '.kind == "raknet-lab-handoff"' "$handoff_manifest_path" >/dev/null; then
    append_issue "handoff-invalid-manifest-kind" "handoff" "fresh lab handoff manifest has an unexpected kind" "{\"path\":\"$handoff_manifest_path\"}"
  fi
  if [[ ! -s "$handoff_summary_path" ]]; then
    append_issue "handoff-missing-summary" "handoff" "fresh lab handoff summary is missing" "{\"path\":\"$handoff_summary_path\"}"
  else
    handoff_summary_json="$(jq -c '.' "$handoff_summary_path")"
    if ! jq -e '.ready == true and (.issueCount // 1) == 0' "$handoff_summary_path" >/dev/null; then
      append_issue "handoff-summary-not-ready" "handoff" "fresh lab handoff summary is not ready" "{\"path\":\"$handoff_summary_path\"}"
    fi
    if jq -e '.networkDirtyTrackedFiles == true' "$handoff_summary_path" >/dev/null; then
      append_issue "handoff-dirty-network-worktree" "handoff" "fresh lab handoff was generated from a dirty tracked Network worktree" "{\"path\":\"$handoff_summary_path\"}"
    fi
  fi
  if [[ ! -s "$handoff_preflight_path" ]]; then
    append_issue "handoff-missing-preflight" "handoff" "fresh lab handoff preflight result is missing" "{\"path\":\"$handoff_preflight_path\"}"
  else
    handoff_preflight_json="$(jq -c '.' "$handoff_preflight_path")"
    if ! jq -e '.ready == true and (.issueCount // 1) == 0' "$handoff_preflight_path" >/dev/null; then
      append_issue "handoff-preflight-not-ready" "handoff" "fresh lab handoff preflight is not ready" "{\"path\":\"$handoff_preflight_path\"}"
    fi
  fi
  if [[ -s "$handoff_manifest_path" && -s "$handoff_summary_path" && -s "$handoff_preflight_path" ]] \
    && jq -e '.kind == "raknet-lab-handoff"' "$handoff_manifest_path" >/dev/null \
    && jq -e '.ready == true and (.issueCount // 1) == 0 and ((.networkDirtyTrackedFiles // false) == false)' "$handoff_summary_path" >/dev/null \
    && jq -e '.ready == true and (.issueCount // 1) == 0' "$handoff_preflight_path" >/dev/null; then
    handoff_ready_json=true
  fi
fi

if [[ -d "$lab_baseline/host-reports" ]]; then
  lab_packaged_host_report_count="$(find "$lab_baseline/host-reports" -maxdepth 1 -type f -name '*-host-report.md' 2>/dev/null | wc -l | tr -d ' ')"
fi
if [[ -d "$lab_baseline/manifests" ]]; then
  lab_packaged_manifest_count="$(find "$lab_baseline/manifests" -maxdepth 1 -type f -name '*-manifest.jsonl' 2>/dev/null | wc -l | tr -d ' ')"
fi

lab_packaged_prereq_files=()
if [[ -d "$lab_baseline/prereq-reports" ]]; then
  mapfile -t lab_packaged_prereq_files < <(find "$lab_baseline/prereq-reports" -maxdepth 1 -type f -name '*-prereq.json' 2>/dev/null | sort)
fi
if [[ "${#lab_packaged_prereq_files[@]}" -gt 0 ]]; then
  lab_packaged_prereq_summary="$(jq -s '
    def hostname:
      (.hostname // empty | select(length > 0));
    def strict:
      .ready == true
      and .requireClockSync == true
      and .requireNoNetem == true
      and ((.expectedMtu // null) | type == "number")
      and ((.interfaceMtu // null) | type == "number")
      and (.interfaceMtu == .expectedMtu)
      and ((.expectedMinCpus // null) | type == "number")
      and ((.cpuCount // null) | type == "number")
      and (.cpuCount >= .expectedMinCpus);
    {
      reportCount: length,
      readyReportCount: ([.[] | select(.ready == true)] | length),
      notReadyReportCount: ([.[] | select((.ready // false) != true)] | length),
      distinctHostnameCount: ([.[] | hostname] | unique | length),
      strictReportCount: ([.[] | select(strict)] | length),
      strictDistinctHostnameCount: ([.[] | select(strict) | hostname] | unique | length),
      hostnames: ([.[] | hostname] | unique),
      strictHostnames: ([.[] | select(strict) | hostname] | unique)
    }
  ' "${lab_packaged_prereq_files[@]}")"
fi

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
if [[ -s "$lab_handoff_manifest" ]]; then
  lab_handoff_kind="$(jq -r '.kind // ""' "$lab_handoff_manifest" 2>/dev/null || true)"
fi

if [[ -s "$lab_manifest" ]]; then
  if ! jq -e '.baselineKind == "raknet-lab-baseline"' "$lab_manifest" >/dev/null; then
    append_issue "invalid-lab-baseline-kind" "lab-baseline" "promoted lab baseline has an unexpected baselineKind" "{\"path\":\"$lab_manifest\"}"
  fi
  if jq -e '.allowValidationBypasses == true' "$lab_manifest" >/dev/null; then
    append_issue "lab-validation-bypasses-allowed" "lab-baseline" "promoted lab baseline allowed validation bypasses" "{\"path\":\"$lab_manifest\"}"
  fi
  if ! jq -e '(.productionEvidence.document // "") != "" and (.productionEvidence.exists == true) and ((.productionEvidence.sha256 // "") | test("^[0-9a-f]{64}$"))' "$lab_manifest" >/dev/null; then
    append_issue "lab-missing-production-evidence" "lab-baseline" "promoted lab baseline does not include a concrete production evidence fingerprint" "{\"path\":\"$lab_manifest\"}"
  fi
  lab_source_audit_json="$(jq -c '.sourceAudit // null' "$lab_manifest")"
  if "$require_source_audit"; then
    if ! jq -e '.sourceAudit != null' "$lab_manifest" >/dev/null; then
      append_issue "lab-missing-source-audit" "lab-baseline" "promoted lab baseline does not include source-audit metadata" "{\"path\":\"$lab_manifest\"}"
    elif ! jq -e '(.sourceAudit.document // "") != "" and (.sourceAudit.exists == true) and (.sourceAudit.ready == true) and ((.sourceAudit.sha256 // "") | test("^[0-9a-f]{64}$"))' "$lab_manifest" >/dev/null; then
      append_issue "lab-invalid-source-audit" "lab-baseline" "promoted lab baseline source-audit metadata is not a ready fingerprint" "{\"path\":\"$lab_manifest\"}"
    fi
  fi
  if [[ ! -s "$lab_handoff_manifest" ]]; then
    append_issue "lab-missing-handoff-manifest" "lab-baseline" "promoted lab baseline is missing its copied handoff manifest" "{\"path\":\"$lab_handoff_manifest\"}"
  elif ! jq -e '.kind == "raknet-lab-handoff"' "$lab_handoff_manifest" >/dev/null; then
    append_issue "lab-invalid-handoff-manifest-kind" "lab-baseline" "promoted lab baseline handoff manifest has an unexpected kind" "{\"path\":\"$lab_handoff_manifest\"}"
  elif ! jq -e '(.productionEvidence.document // "") != "" and (.productionEvidence.exists == true) and ((.productionEvidence.sha256 // "") | test("^[0-9a-f]{64}$"))' "$lab_handoff_manifest" >/dev/null; then
    append_issue "lab-handoff-missing-production-evidence" "lab-baseline" "promoted lab baseline handoff manifest does not include a concrete production evidence fingerprint" "{\"path\":\"$lab_handoff_manifest\"}"
  elif ! jq -e --slurpfile handoff "$lab_handoff_manifest" '.productionEvidence == $handoff[0].productionEvidence' "$lab_manifest" >/dev/null; then
    extra="$(jq -n --slurpfile baseline "$lab_manifest" --slurpfile handoff "$lab_handoff_manifest" --arg baselinePath "$lab_manifest" --arg handoffPath "$lab_handoff_manifest" '{baselinePath:$baselinePath,handoffPath:$handoffPath,baselineProductionEvidence:$baseline[0].productionEvidence,handoffProductionEvidence:$handoff[0].productionEvidence}')"
    append_issue "lab-production-evidence-handoff-mismatch" "lab-baseline" "promoted lab baseline production evidence does not match its copied handoff manifest" "$extra"
  fi
  if "$require_source_audit" && [[ -s "$lab_handoff_manifest" ]]; then
    lab_handoff_source_audit_json="$(jq -c '.sourceAudit // null' "$lab_handoff_manifest")"
    if ! jq -e '.sourceAudit != null' "$lab_handoff_manifest" >/dev/null; then
      append_issue "lab-handoff-missing-source-audit" "lab-baseline" "promoted lab baseline handoff manifest does not include source-audit metadata" "{\"path\":\"$lab_handoff_manifest\"}"
    elif ! jq -e '(.sourceAudit.document // "") != "" and (.sourceAudit.exists == true) and (.sourceAudit.ready == true) and ((.sourceAudit.sha256 // "") | test("^[0-9a-f]{64}$"))' "$lab_handoff_manifest" >/dev/null; then
      append_issue "lab-handoff-invalid-source-audit" "lab-baseline" "promoted lab baseline handoff source-audit metadata is not a ready fingerprint" "{\"path\":\"$lab_handoff_manifest\"}"
    elif [[ "$lab_source_audit_json" != "$lab_handoff_source_audit_json" ]]; then
      extra="$(jq -n --arg baselinePath "$lab_manifest" --arg handoffPath "$lab_handoff_manifest" --argjson baselineSourceAudit "$lab_source_audit_json" --argjson handoffSourceAudit "$lab_handoff_source_audit_json" '{baselinePath:$baselinePath,handoffPath:$handoffPath,baselineSourceAudit:$baselineSourceAudit,handoffSourceAudit:$handoffSourceAudit}')"
      append_issue "lab-source-audit-handoff-mismatch" "lab-baseline" "promoted lab baseline source audit does not match its copied handoff manifest" "$extra"
    fi
  fi
  if [[ ! -s "$lab_artifact_collection_json" ]]; then
    append_issue "lab-missing-artifact-collection-json" "lab-baseline" "promoted lab baseline is missing its copied artifact collection JSON" "{\"path\":\"$lab_artifact_collection_json\"}"
  elif ! jq -e '.kind == "raknet-lab-artifact-collection"' "$lab_artifact_collection_json" >/dev/null; then
    append_issue "lab-invalid-artifact-collection-kind" "lab-baseline" "promoted lab baseline artifact collection JSON has an unexpected kind" "{\"path\":\"$lab_artifact_collection_json\"}"
  else
    lab_artifact_collection_groups_json="$(jq -c '[.collectionGroups[]?.id] | unique' "$lab_artifact_collection_json")"
    missing_artifact_collection_groups="$(jq -n -r --argjson expected "$required_artifact_collection_groups_json" --argjson actual "$lab_artifact_collection_groups_json" '
      $expected[] as $group | select(($actual | index($group)) == null) | $group
    ')"
    while IFS= read -r group; do
      [[ -z "$group" ]] && continue
      extra="$(jq -n --arg group "$group" --argjson expected "$required_artifact_collection_groups_json" --argjson actual "$lab_artifact_collection_groups_json" '{group:$group,expectedGroups:$expected,actualGroups:$actual}')"
      append_issue "lab-artifact-collection-group-missing" "lab-baseline" "promoted lab baseline artifact collection is missing a required group" "$extra"
    done <<<"$missing_artifact_collection_groups"
    if [[ -s "$lab_handoff_manifest" && "$lab_handoff_kind" == "raknet-lab-handoff" ]]; then
      if ! jq -e --slurpfile handoff "$lab_handoff_manifest" '.perfectArtifacts == ($handoff[0].perfectArtifacts // "")' "$lab_artifact_collection_json" >/dev/null; then
        extra="$(jq -n --slurpfile handoff "$lab_handoff_manifest" --slurpfile collection "$lab_artifact_collection_json" '{handoffPerfectArtifacts:$handoff[0].perfectArtifacts,collectionPerfectArtifacts:$collection[0].perfectArtifacts}')"
        append_issue "lab-artifact-collection-perfect-root-mismatch" "lab-baseline" "promoted lab baseline artifact collection perfect root does not match the copied handoff manifest" "$extra"
      fi
      if ! jq -e --slurpfile handoff "$lab_handoff_manifest" '.impairmentArtifacts == ($handoff[0].impairmentArtifacts // "")' "$lab_artifact_collection_json" >/dev/null; then
        extra="$(jq -n --slurpfile handoff "$lab_handoff_manifest" --slurpfile collection "$lab_artifact_collection_json" '{handoffImpairmentArtifacts:$handoff[0].impairmentArtifacts,collectionImpairmentArtifacts:$collection[0].impairmentArtifacts}')"
        append_issue "lab-artifact-collection-impairment-root-mismatch" "lab-baseline" "promoted lab baseline artifact collection impairment root does not match the copied handoff manifest" "$extra"
      fi
    fi
  fi
  if [[ ! -s "$lab_artifact_collection_md" ]]; then
    append_issue "lab-missing-artifact-collection-md" "lab-baseline" "promoted lab baseline is missing its copied artifact collection checklist" "{\"path\":\"$lab_artifact_collection_md\"}"
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
  if (( lab_packaged_manifest_count < 3 )); then
    extra="$(jq -n --argjson actual "$lab_packaged_manifest_count" '{requiredPackagedManifests:3,actualPackagedManifestCount:$actual}')"
    append_issue "lab-missing-packaged-manifests" "lab-baseline" "promoted lab baseline package does not contain copied curve, raised-curve, and contention manifests" "$extra"
  fi
  if (( lab_packaged_host_report_count < 2 )); then
    extra="$(jq -n --argjson actual "$lab_packaged_host_report_count" '{requiredPackagedHostReports:2,actualPackagedHostReportCount:$actual}')"
    append_issue "lab-missing-packaged-host-reports" "lab-baseline" "promoted lab baseline package does not contain enough copied host reports" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_reports" '(.prereqReportCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_reports" --argjson actual "$(jq -r '.prereqReportCount // 0' "$lab_validation")" '{requiredMinPrereqReports:$required,actualPrereqReportCount:$actual}')"
    append_issue "lab-missing-prereq-reports" "lab-baseline" "lab baseline does not include enough host prerequisite reports" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_reports" '(.reportCount // 0) >= $required' <<<"$lab_packaged_prereq_summary" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_reports" --argjson actual "$(jq -r '.reportCount // 0' <<<"$lab_packaged_prereq_summary")" '{requiredMinPackagedPrereqReports:$required,actualPackagedPrereqReportCount:$actual}')"
    append_issue "lab-missing-packaged-prereq-reports" "lab-baseline" "promoted lab baseline package does not contain enough copied prerequisite reports" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_ready_prereq_reports" '(.readyPrereqReportCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_ready_prereq_reports" --argjson actual "$(jq -r '.readyPrereqReportCount // 0' "$lab_validation")" '{requiredMinReadyPrereqReports:$required,actualReadyPrereqReportCount:$actual}')"
    append_issue "lab-prereq-not-ready" "lab-baseline" "lab baseline does not prove enough ready host prerequisite reports" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_ready_prereq_reports" '(.readyReportCount // 0) >= $required' <<<"$lab_packaged_prereq_summary" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_ready_prereq_reports" --argjson actual "$(jq -r '.readyReportCount // 0' <<<"$lab_packaged_prereq_summary")" '{requiredMinReadyPackagedPrereqReports:$required,actualReadyPackagedPrereqReportCount:$actual}')"
    append_issue "lab-packaged-prereq-not-ready" "lab-baseline" "promoted lab baseline package does not contain enough ready prerequisite reports" "$extra"
  fi
  if ! jq -e '(.notReadyPrereqReportCount // 0) == 0' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson actual "$(jq -r '.notReadyPrereqReportCount // 0' "$lab_validation")" '{notReadyPrereqReportCount:$actual}')"
    append_issue "lab-prereq-report-failed" "lab-baseline" "one or more lab host prerequisite reports was not ready" "$extra"
  fi
  if ! jq -e '(.notReadyReportCount // 0) == 0' <<<"$lab_packaged_prereq_summary" >/dev/null; then
    extra="$(jq -n --argjson actual "$(jq -r '.notReadyReportCount // 0' <<<"$lab_packaged_prereq_summary")" '{notReadyPackagedPrereqReportCount:$actual}')"
    append_issue "lab-packaged-prereq-report-failed" "lab-baseline" "one or more copied prerequisite reports in the promoted lab baseline package is not ready" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_distinct_hostnames" '(.prereqDistinctHostnameCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_distinct_hostnames" --argjson actual "$(jq -r '.prereqDistinctHostnameCount // 0' "$lab_validation")" '{requiredMinPrereqDistinctHostnames:$required,actualPrereqDistinctHostnameCount:$actual}')"
    append_issue "lab-prereq-not-separate-hosts" "lab-baseline" "lab baseline does not prove prerequisite checks from enough distinct hostnames" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_distinct_hostnames" '(.distinctHostnameCount // 0) >= $required' <<<"$lab_packaged_prereq_summary" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_distinct_hostnames" --argjson actual "$(jq -r '.distinctHostnameCount // 0' <<<"$lab_packaged_prereq_summary")" --argjson hostnames "$(jq -c '.hostnames // []' <<<"$lab_packaged_prereq_summary")" '{requiredMinPackagedPrereqDistinctHostnames:$required,actualPackagedPrereqDistinctHostnameCount:$actual,packagedPrereqHostnames:$hostnames}')"
    append_issue "lab-packaged-prereq-not-separate-hosts" "lab-baseline" "promoted lab baseline package does not contain copied prerequisite reports from enough distinct hostnames" "$extra"
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
  if ! jq -e --argjson required "$required_min_ready_prereq_reports" '(.strictReportCount // 0) >= $required' <<<"$lab_packaged_prereq_summary" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_ready_prereq_reports" --argjson actual "$(jq -r '.strictReportCount // 0' <<<"$lab_packaged_prereq_summary")" '{requiredMinStrictPackagedPrereqReports:$required,actualStrictPackagedPrereqReportCount:$actual}')"
    append_issue "lab-packaged-prereq-strict-gates-missing" "lab-baseline" "promoted lab baseline package does not contain enough copied prerequisite reports proving strict clock, MTU, CPU-count, and no-netem gates" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_distinct_hostnames" '(.strictPrereqDistinctHostnameCount // 0) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_distinct_hostnames" --argjson actual "$(jq -r '.strictPrereqDistinctHostnameCount // 0' "$lab_validation")" '{requiredMinStrictPrereqDistinctHostnames:$required,actualStrictPrereqDistinctHostnameCount:$actual}')"
    append_issue "lab-prereq-strict-gates-not-separate-hosts" "lab-baseline" "lab baseline does not prove strict prerequisite gates from enough distinct hostnames" "$extra"
  fi
  if ! jq -e --argjson required "$required_min_prereq_distinct_hostnames" '(.strictDistinctHostnameCount // 0) >= $required' <<<"$lab_packaged_prereq_summary" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_prereq_distinct_hostnames" --argjson actual "$(jq -r '.strictDistinctHostnameCount // 0' <<<"$lab_packaged_prereq_summary")" --argjson hostnames "$(jq -c '.strictHostnames // []' <<<"$lab_packaged_prereq_summary")" '{requiredMinStrictPackagedPrereqDistinctHostnames:$required,actualStrictPackagedPrereqDistinctHostnameCount:$actual,strictPackagedPrereqHostnames:$hostnames}')"
    append_issue "lab-packaged-prereq-strict-gates-not-separate-hosts" "lab-baseline" "promoted lab baseline package does not contain copied strict prerequisite reports from enough distinct hostnames" "$extra"
  fi
  for scenario in curve multi-client-fanout fairness disappearing-clients batched-game-traffic resource-pack-transfer; do
    if ! jq -e --arg scenario "$scenario" '(.scenarioCounts[$scenario] // 0) > 0' "$lab_validation" >/dev/null; then
      append_issue "lab-missing-scenario" "lab-baseline" "lab baseline is missing a required scenario family" "{\"scenario\":\"$scenario\"}"
    fi
  done
  if ! jq -e '.capacityRowCount > 0' "$lab_validation" >/dev/null; then
    append_issue "lab-missing-capacity-rows" "lab-baseline" "lab baseline has no capacity selector rows" "{\"path\":\"$lab_validation\"}"
  fi
  if ! jq -e --argjson required "$required_min_iterations" '(.minIterations // -1) >= $required' "$lab_validation" >/dev/null; then
    extra="$(jq -n --argjson required "$required_min_iterations" --argjson actual "$(jq -r '.minIterations // -1' "$lab_validation")" '{requiredMinIterations:$required,actualMinIterations:$actual}')"
    append_issue "lab-iteration-gate-too-low" "lab-baseline" "lab validation did not enforce the required measured iteration count" "$extra"
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
  missing_lab_retry_fields="$(jq -r -s --argjson expected "$required_retry_pressure_fields_json" '
    .[] as $row
    | $expected[] as $field
    | select(($row | has($field)) | not)
    | [($row.case // ""), ($row.benchmarkName // ""), $field] | @tsv
  ' "$lab_aggregate")"
  while IFS=$'\t' read -r case_name benchmark_name field_name; do
    [[ -z "$field_name" ]] && continue
    extra="$(jq -n --arg case "$case_name" --arg benchmarkName "$benchmark_name" --arg field "$field_name" '{case:$case,benchmarkName:$benchmarkName,field:$field}')"
    append_issue "lab-missing-retry-pressure-field" "lab-baseline" "lab aggregate row is missing a required retry-pressure field" "$extra"
  done <<<"$missing_lab_retry_fields"

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

  missing_batch_intervals="$(jq -r -s --argjson expected "$required_batch_intervals_json" '
    def is_batch:
      ((.scenario // "") == "batched-game-traffic")
      or ((.benchmarkName // "") == "batched-game-traffic");
    ([.[] | select(is_batch) | (.batchIntervalMillis // empty | tonumber)] | unique) as $actual
    | $expected[] as $interval
    | select(($actual | index($interval)) == null)
    | $interval
  ' "$lab_aggregate")"
  while IFS= read -r missing_interval; do
    [[ -z "$missing_interval" ]] && continue
    append_issue "lab-missing-batch-interval" "lab-baseline" "lab baseline is missing a required batched-game-traffic interval" "{\"batchIntervalMillis\":$missing_interval}"
  done <<<"$missing_batch_intervals"

  missing_immediate_shapes="$(jq -r -s --argjson expectedPayloads "$required_immediate_payloads_json" --argjson expectedTarget "$required_immediate_target_client_mbps" '
    def is_immediate:
      ((.benchmarkName // "") == "multi-client-fanout")
      and (
        ((.affectedKind // "") == "immediate")
        or (((.case // "") | ascii_downcase) | contains("immediate"))
      );
    ([.[] | select(is_immediate) | {
      payloadSize: (.payloadSize // empty | tonumber),
      targetClientMbps: (.targetClientMbps // .perClientMbps // empty | tonumber)
    }]) as $actual
    | $expectedPayloads[] as $payload
    | select((any($actual[]; .payloadSize == $payload and (((.targetClientMbps - $expectedTarget) | fabs) <= 0.000001))) | not)
    | [$payload, $expectedTarget] | @tsv
  ' "$lab_aggregate")"
  while IFS=$'\t' read -r missing_payload missing_target; do
    [[ -z "$missing_payload" || -z "$missing_target" ]] && continue
    extra="$(jq -n --argjson payloadSize "$missing_payload" --argjson targetClientMbps "$missing_target" '{payloadSize:$payloadSize,targetClientMbps:$targetClientMbps}')"
    append_issue "lab-missing-immediate-shape" "lab-baseline" "lab baseline is missing a required immediate small-packet fanout payload and target shape" "$extra"
  done <<<"$missing_immediate_shapes"

  missing_resource_shapes="$(jq -r -s --argjson expectedChunks "$required_resource_pack_chunks_json" --argjson expectedIntervals "$required_resource_pack_intervals_json" '
    def is_resource:
      ((.scenario // "") == "resource-pack-transfer")
      or ((.benchmarkName // "") == "resource-pack-transfer");
    ([.[] | select(is_resource) | {
      payloadSize: (.payloadSize // empty | tonumber),
      batchIntervalMillis: (.batchIntervalMillis // empty | tonumber)
    }]) as $actual
    | $expectedChunks[] as $chunk
    | $expectedIntervals[] as $interval
    | select((any($actual[]; .payloadSize == $chunk and .batchIntervalMillis == $interval)) | not)
    | [$chunk, $interval] | @tsv
  ' "$lab_aggregate")"
	  while IFS=$'\t' read -r missing_chunk missing_interval; do
	    [[ -z "$missing_chunk" || -z "$missing_interval" ]] && continue
	    extra="$(jq -n --argjson payloadSize "$missing_chunk" --argjson batchIntervalMillis "$missing_interval" '{payloadSize:$payloadSize,batchIntervalMillis:$batchIntervalMillis}')"
	    append_issue "lab-missing-resource-pack-shape" "lab-baseline" "lab baseline is missing a required resource-pack chunk and interval shape" "$extra"
	  done <<<"$missing_resource_shapes"

	  missing_disappearance_modes="$(jq -r -s --argjson expected "$required_disappearance_modes_json" '
	    def is_disappearance:
	      ((.scenario // "") == "disappearing-clients")
	      or ((.benchmarkName // "") == "disappearing-clients");
	    def mode($row):
	      if (($row.disappearanceMode // "") != "") then $row.disappearanceMode
	      elif ((($row.case // "") | ascii_downcase) | contains("blackhole")) then "blackhole"
	      elif ((($row.case // "") | ascii_downcase) | contains("stopread")) or ((($row.case // "") | ascii_downcase) | contains("stop-reading")) then "stop-reading"
	      elif ((($row.case // "") | ascii_downcase) | contains("close")) then "close"
	      else null end;
	    ([.[] | select(is_disappearance) | mode(.) | select(. != null)] | unique) as $actual
	    | $expected[] as $mode
	    | select(($actual | index($mode)) == null)
	    | $mode
	  ' "$lab_aggregate")"
	  while IFS= read -r missing_mode; do
	    [[ -z "$missing_mode" ]] && continue
	    append_issue "lab-missing-disappearance-mode" "lab-baseline" "lab baseline is missing a required disappearing-client mode" "{\"disappearanceMode\":\"$missing_mode\"}"
	  done <<<"$missing_disappearance_modes"
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
impairment_campaign_manifest="$impairment_baseline/campaign-manifest.jsonl"
impairment_packaged_campaign_manifest_exists=false
impairment_packaged_profiles_json="[]"

if [[ ! -s "$impairment_manifest" ]]; then
  append_issue "missing-impairment-baseline-manifest" "impairment-baseline" "promoted impairment baseline manifest is missing" "{\"path\":\"$impairment_manifest\"}"
fi
if [[ ! -s "$impairment_summary" ]]; then
  append_issue "missing-impairment-summary" "impairment-baseline" "promoted impairment baseline summary is missing" "{\"path\":\"$impairment_summary\"}"
fi
if [[ -s "$impairment_campaign_manifest" ]]; then
  impairment_packaged_campaign_manifest_exists=true
fi

if [[ -s "$impairment_summary" ]]; then
  if [[ "$impairment_packaged_campaign_manifest_exists" != "true" ]]; then
    append_issue "impairment-missing-packaged-campaign-manifest" "impairment-baseline" "promoted impairment baseline package is missing its copied campaign manifest" "{\"path\":\"$impairment_campaign_manifest\"}"
  fi
  while IFS= read -r profile; do
    [[ -z "$profile" ]] && continue
    safe_profile="$(safe_name "$profile")"
    profile_dir="$impairment_baseline/profiles/$safe_profile"
    profile_dir_exists=false
    validation_exists=false
    aggregate_exists=false
    capacity_exists=false
    netem_status_count=0
    if [[ -d "$profile_dir" ]]; then
      profile_dir_exists=true
      [[ -s "$profile_dir/validation.json" ]] && validation_exists=true
      [[ -s "$profile_dir/suite-aggregate.jsonl" ]] && aggregate_exists=true
      [[ -s "$profile_dir/bandwidth-capacity.jsonl" ]] && capacity_exists=true
      if [[ -d "$profile_dir/netem" ]]; then
        netem_status_count="$(find "$profile_dir/netem" -maxdepth 1 -type f -name "$profile-status-*.txt" 2>/dev/null | wc -l | tr -d ' ')"
      fi
    fi
    jq -n \
      --arg profile "$profile" \
      --arg path "$profile_dir" \
      --argjson profileDirExists "$profile_dir_exists" \
      --argjson validationExists "$validation_exists" \
      --argjson aggregateExists "$aggregate_exists" \
      --argjson capacityExists "$capacity_exists" \
      --argjson netemStatusCount "$netem_status_count" \
      '{
        profile: $profile,
        path: $path,
        profileDirExists: $profileDirExists,
        validationExists: $validationExists,
        aggregateExists: $aggregateExists,
        capacityExists: $capacityExists,
        netemStatusCount: $netemStatusCount
      }' >>"$impairment_packaged_profiles_jsonl"
  done < <(jq -r '.profiles[]?.profile // empty' "$impairment_summary")
  impairment_packaged_profiles_json="$(jq -s '.' "$impairment_packaged_profiles_jsonl")"
fi

if [[ -s "$impairment_manifest" ]]; then
  if ! jq -e '.baselineKind == "raknet-lab-impairment-campaign"' "$impairment_manifest" >/dev/null; then
    append_issue "invalid-impairment-baseline-kind" "impairment-baseline" "promoted impairment baseline has an unexpected baselineKind" "{\"path\":\"$impairment_manifest\"}"
  fi
  if jq -e '.allowValidationBypasses == true' "$impairment_manifest" >/dev/null; then
    append_issue "impairment-validation-bypasses-allowed" "impairment-baseline" "promoted impairment baseline allowed validation bypasses" "{\"path\":\"$impairment_manifest\"}"
  fi
  if jq -e '.allowMissingRetryPressureFields == true' "$impairment_manifest" >/dev/null; then
    append_issue "impairment-missing-retry-pressure-bypass-allowed" "impairment-baseline" "promoted impairment baseline allowed missing retry-pressure fields" "{\"path\":\"$impairment_manifest\"}"
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
  if jq -e '.allowMissingRetryPressureFields == true' "$impairment_summary" >/dev/null; then
    append_issue "impairment-summary-missing-retry-pressure-bypass-allowed" "impairment-baseline" "impairment campaign summary allowed missing retry-pressure fields" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.validationPassedCount == .profileCount and .profileCount > 0' "$impairment_summary" >/dev/null; then
    append_issue "impairment-profile-validation-incomplete" "impairment-baseline" "not every impairment profile has passing validation" "{\"path\":\"$impairment_summary\"}"
  fi
  if ! jq -e '.netemStatusEvidenceCount >= .profileCount and .profileCount > 0' "$impairment_summary" >/dev/null; then
    append_issue "impairment-missing-netem-status" "impairment-baseline" "not every impairment profile has netem status evidence" "{\"path\":\"$impairment_summary\"}"
  fi
  missing_packaged_profiles="$(jq -r '.[] | select(.profileDirExists != true) | [.profile, .path] | @tsv' <<<"$impairment_packaged_profiles_json")"
  while IFS=$'\t' read -r profile profile_path; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg path "$profile_path" '{profile:$profile,path:$path}')"
    append_issue "impairment-missing-packaged-profile" "impairment-baseline" "promoted impairment baseline package is missing copied profile evidence" "$extra"
  done <<<"$missing_packaged_profiles"

  missing_packaged_validation="$(jq -r '.[] | select(.profileDirExists == true and .validationExists != true) | [.profile, .path] | @tsv' <<<"$impairment_packaged_profiles_json")"
  while IFS=$'\t' read -r profile profile_path; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg path "$profile_path/validation.json" '{profile:$profile,path:$path}')"
    append_issue "impairment-missing-packaged-validation" "impairment-baseline" "promoted impairment baseline package is missing copied profile validation" "$extra"
  done <<<"$missing_packaged_validation"

  missing_packaged_aggregate="$(jq -r '.[] | select(.profileDirExists == true and .aggregateExists != true) | [.profile, .path] | @tsv' <<<"$impairment_packaged_profiles_json")"
  while IFS=$'\t' read -r profile profile_path; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg path "$profile_path/suite-aggregate.jsonl" '{profile:$profile,path:$path}')"
    append_issue "impairment-missing-packaged-aggregate" "impairment-baseline" "promoted impairment baseline package is missing copied profile aggregate rows" "$extra"
  done <<<"$missing_packaged_aggregate"

  missing_packaged_capacity="$(jq -r '.[] | select(.profileDirExists == true and .capacityExists != true) | [.profile, .path] | @tsv' <<<"$impairment_packaged_profiles_json")"
  while IFS=$'\t' read -r profile profile_path; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg path "$profile_path/bandwidth-capacity.jsonl" '{profile:$profile,path:$path}')"
    append_issue "impairment-missing-packaged-capacity" "impairment-baseline" "promoted impairment baseline package is missing copied profile capacity selector rows" "$extra"
  done <<<"$missing_packaged_capacity"

  missing_packaged_netem="$(jq -r '.[] | select(.profileDirExists == true and (.netemStatusCount // 0) < 1) | [.profile, .path] | @tsv' <<<"$impairment_packaged_profiles_json")"
  while IFS=$'\t' read -r profile profile_path; do
    [[ -z "$profile" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg path "$profile_path/netem" '{profile:$profile,path:$path}')"
    append_issue "impairment-missing-packaged-netem-status" "impairment-baseline" "promoted impairment baseline package is missing copied profile netem status evidence" "$extra"
  done <<<"$missing_packaged_netem"

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

  missing_impairment_retry_fields="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedFields "$required_retry_pressure_fields_json" '
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | (.aggregate.contentionRows[]? as $row
      | $expectedFields[] as $field
      | select(($row | has($field)) | not)
      | [$profile, ($row.case // ""), ($row.benchmarkName // ""), $field] | @tsv)
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile case_name benchmark_name field_name; do
    [[ -z "$profile" || -z "$field_name" ]] && continue
    extra="$(jq -n --arg profile "$profile" --arg case "$case_name" --arg benchmarkName "$benchmark_name" --arg field "$field_name" '{profile:$profile,case:$case,benchmarkName:$benchmarkName,field:$field}')"
    append_issue "impairment-missing-retry-pressure-field" "impairment-baseline" "impairment contention row is missing a required retry-pressure field" "$extra"
  done <<<"$missing_impairment_retry_fields"

  missing_impairment_batch_intervals="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedIntervals "$required_batch_intervals_json" '
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | ([.aggregate.contentionRows[]?
        | select((.benchmarkName // "") == "batched-game-traffic")
        | (.batchIntervalMillis // empty | tonumber)] | unique) as $actual
    | $expectedIntervals[] as $interval
    | select(($actual | index($interval)) == null)
    | [$profile, $interval] | @tsv
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile missing_interval; do
    [[ -z "$profile" || -z "$missing_interval" ]] && continue
    extra="$(jq -n --arg profile "$profile" --argjson batchIntervalMillis "$missing_interval" '{profile:$profile,batchIntervalMillis:$batchIntervalMillis}')"
    append_issue "impairment-missing-batch-interval" "impairment-baseline" "impairment profile is missing a required batched-game-traffic interval" "$extra"
  done <<<"$missing_impairment_batch_intervals"

  missing_impairment_immediate_shapes="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedPayloads "$required_immediate_payloads_json" --argjson expectedTarget "$required_immediate_target_client_mbps" '
    def is_immediate:
      ((.benchmarkName // "") == "multi-client-fanout")
      and (
        ((.affectedKind // "") == "immediate")
        or (((.case // "") | ascii_downcase) | contains("immediate"))
      );
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | ([.aggregate.contentionRows[]? | select(is_immediate) | {
        payloadSize: (.payloadSize // empty | tonumber),
        targetClientMbps: (.targetClientMbps // .perClientMbps // empty | tonumber)
      }]) as $actual
    | $expectedPayloads[] as $payload
    | select((any($actual[]; .payloadSize == $payload and (((.targetClientMbps - $expectedTarget) | fabs) <= 0.000001))) | not)
    | [$profile, $payload, $expectedTarget] | @tsv
  ' "$impairment_summary")"
  while IFS=$'\t' read -r profile missing_payload missing_target; do
    [[ -z "$profile" || -z "$missing_payload" || -z "$missing_target" ]] && continue
    extra="$(jq -n --arg profile "$profile" --argjson payloadSize "$missing_payload" --argjson targetClientMbps "$missing_target" '{profile:$profile,payloadSize:$payloadSize,targetClientMbps:$targetClientMbps}')"
    append_issue "impairment-missing-immediate-shape" "impairment-baseline" "impairment profile is missing a required immediate small-packet fanout payload and target shape" "$extra"
  done <<<"$missing_impairment_immediate_shapes"

  missing_impairment_resource_shapes="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedChunks "$required_resource_pack_chunks_json" --argjson expectedIntervals "$required_resource_pack_intervals_json" '
    (.profiles // [])[]
    | .profile as $profile
    | select(($expectedProfiles | index($profile)) != null)
    | ([.aggregate.contentionRows[]?
        | select((.benchmarkName // "") == "resource-pack-transfer")
        | {
            payloadSize: (.payloadSize // empty | tonumber),
            batchIntervalMillis: (.batchIntervalMillis // empty | tonumber)
          }]) as $actual
    | $expectedChunks[] as $chunk
    | $expectedIntervals[] as $interval
    | select((any($actual[]; .payloadSize == $chunk and .batchIntervalMillis == $interval)) | not)
    | [$profile, $chunk, $interval] | @tsv
  ' "$impairment_summary")"
	  while IFS=$'\t' read -r profile missing_chunk missing_interval; do
	    [[ -z "$profile" || -z "$missing_chunk" || -z "$missing_interval" ]] && continue
	    extra="$(jq -n --arg profile "$profile" --argjson payloadSize "$missing_chunk" --argjson batchIntervalMillis "$missing_interval" '{profile:$profile,payloadSize:$payloadSize,batchIntervalMillis:$batchIntervalMillis}')"
	    append_issue "impairment-missing-resource-pack-shape" "impairment-baseline" "impairment profile is missing a required resource-pack chunk and interval shape" "$extra"
	  done <<<"$missing_impairment_resource_shapes"

	  missing_impairment_disappearance_modes="$(jq -r --argjson expectedProfiles "$expected_profiles_json" --argjson expectedModes "$required_disappearance_modes_json" '
	    def mode($row):
	      if (($row.disappearanceMode // "") != "") then $row.disappearanceMode
	      elif ((($row.case // "") | ascii_downcase) | contains("blackhole")) then "blackhole"
	      elif ((($row.case // "") | ascii_downcase) | contains("stopread")) or ((($row.case // "") | ascii_downcase) | contains("stop-reading")) then "stop-reading"
	      elif ((($row.case // "") | ascii_downcase) | contains("close")) then "close"
	      else null end;
	    (.profiles // [])[]
	    | .profile as $profile
	    | select(($expectedProfiles | index($profile)) != null)
	    | ([.aggregate.contentionRows[]?
	        | select((.benchmarkName // "") == "disappearing-clients")
	        | mode(.) | select(. != null)] | unique) as $actual
	    | $expectedModes[] as $mode
	    | select(($actual | index($mode)) == null)
	    | [$profile, $mode] | @tsv
	  ' "$impairment_summary")"
	  while IFS=$'\t' read -r profile missing_mode; do
	    [[ -z "$profile" || -z "$missing_mode" ]] && continue
	    extra="$(jq -n --arg profile "$profile" --arg disappearanceMode "$missing_mode" '{profile:$profile,disappearanceMode:$disappearanceMode}')"
	    append_issue "impairment-missing-disappearance-mode" "impairment-baseline" "impairment profile is missing a required disappearing-client mode" "$extra"
	  done <<<"$missing_impairment_disappearance_modes"
	fi

issues_array="$(jq -s '.' "$issues_jsonl")"
next_actions_json="$(jq -s \
  --argjson handoffProvided "$handoff_provided_json" \
  --argjson handoffReady "$handoff_ready_json" \
  --argjson handoffSummary "$handoff_summary_json" \
  '
  def has_code($code): any(.[]; .code == $code);
  def has_any_code($codes): any(.[]; (.code as $code | ($codes | index($code)) != null));
  def has_prefix($prefix): any(.[]; (.code | startswith($prefix)));
  def has_handoff_issue: has_prefix("handoff-");
  def execution_path($key):
    if (($handoffSummary.execution // null) == null) then ""
    else ($handoffSummary.execution[$key] // "")
    end;
  def maybe_path($key):
    (execution_path($key)) as $path | if $path == "" then null else $path end;
  def perfect_plan_command:
    if execution_path("perfectPlan") != "" then
      "\(execution_path("perfectPlan"))/check-plan-freshness.sh && \(execution_path("perfectPlan"))/merge-all.sh"
    else
      "<lab-handoff>/perfect-plan/check-plan-freshness.sh && <lab-handoff>/perfect-plan/merge-all.sh"
    end;
  def promote_perfect_command:
    if execution_path("perfectArtifacts") != "" and execution_path("handoffManifest") != "" and execution_path("perfectPlan") != "" then
      "benchmark/scripts/promote-lab-baseline.sh --input \(execution_path("perfectArtifacts"))/combined --handoff-manifest \(execution_path("handoffManifest")) --manifest \(execution_path("perfectPlan"))/curve-plan/manifest.jsonl --manifest \(execution_path("perfectPlan"))/curve-raised-plan/manifest.jsonl --manifest \(execution_path("perfectPlan"))/contention-plan/manifest.jsonl"
    else
      "benchmark/scripts/promote-lab-baseline.sh --input <perfect-artifacts>/combined --handoff-manifest <lab-handoff>/handoff-manifest.json --manifest <curve-manifest.jsonl> --manifest <raised-curve-manifest.jsonl> --manifest <contention-manifest.jsonl>"
    end;
  def impairment_plan_command:
    if execution_path("impairmentPlan") != "" then
      "\(execution_path("impairmentPlan"))/check-plan-freshness.sh && \(execution_path("impairmentPlan"))/validate-all.sh && \(execution_path("impairmentPlan"))/summarize-campaign.sh"
    else
      "<lab-handoff>/impairment-plan/check-plan-freshness.sh && <lab-handoff>/impairment-plan/validate-all.sh && <lab-handoff>/impairment-plan/summarize-campaign.sh"
    end;
  def promote_impairment_command:
    if execution_path("impairmentArtifacts") != "" then
      "benchmark/scripts/promote-lab-impairment.sh --input \(execution_path("impairmentArtifacts"))/campaign-summary"
    else
      "benchmark/scripts/promote-lab-impairment.sh --input <impairment-artifacts>/campaign-summary"
    end;
  [
    if has_handoff_issue or
      (((($handoffProvided | not) or ($handoffReady | not)) and has_any_code([
        "missing-lab-baseline-manifest",
        "missing-lab-validation",
        "missing-lab-aggregate",
        "missing-lab-capacity",
        "missing-impairment-baseline-manifest",
        "missing-impairment-summary"
      ]))) then {
      code: "prepare-fresh-lab-handoff",
      title: "Generate a fresh lab handoff",
      detail: "Create a current-revision handoff before lab operators run remote workers so source audit, plans, preflight, and freshness checks match the checkout.",
      command: "benchmark/scripts/prepare-fresh-lab-handoff.sh --out benchmark/build/benchmark-results/lab-handoff-<date>-<topology> --artifact-root benchmark/build/benchmark-results/lab-run-<date>-<topology> --source-audit-out benchmark/build/benchmark-results/production-evidence-current --server-host <server-ip> --interface <nic> --expect-mtu <mtu> --expect-min-cpus <min-cpus> --curve-receiver receiver-a=1 --contention-receiver receiver-a=250 --contention-receiver receiver-b=250 --sudo-netem"
    } else empty end,
    if has_any_code([
      "missing-lab-baseline-manifest",
      "missing-lab-validation",
      "missing-lab-aggregate",
      "missing-lab-capacity"
    ]) then {
      code: "run-perfect-lab-plan",
      title: "Run and merge the perfect-network lab plan",
      detail: "Run the generated perfect-plan server and receiver scripts on separate hosts, copy receiver artifacts back, and merge/validate the perfect-network baseline artifacts.",
      command: perfect_plan_command,
      readme: maybe_path("readme"),
      plan: maybe_path("perfectPlan"),
      artifactRoot: maybe_path("perfectArtifacts")
    } else empty end,
    if has_any_code([
      "missing-lab-baseline-manifest",
      "missing-lab-validation",
      "missing-lab-aggregate",
      "missing-lab-capacity",
      "invalid-lab-baseline-kind",
      "lab-missing-artifact-collection-json",
      "lab-missing-artifact-collection-md",
      "lab-invalid-artifact-collection-kind",
      "lab-artifact-collection-group-missing",
      "lab-artifact-collection-perfect-root-mismatch",
      "lab-artifact-collection-impairment-root-mismatch"
    ]) then {
      code: "promote-lab-baseline",
      title: "Promote the perfect-network lab baseline",
      detail: "Create a promoted lab baseline package after merged perfect-network artifacts exist, passing the current handoff manifest so source and artifact collection evidence are packaged.",
      command: promote_perfect_command,
      helper: maybe_path("promoteScript"),
      handoffManifest: maybe_path("handoffManifest"),
      artifactRoot: maybe_path("perfectArtifacts")
    } else empty end,
    if has_any_code([
      "failed-lab-validation",
      "lab-not-separate-hosts",
      "lab-missing-host-reports",
      "lab-missing-packaged-host-reports",
      "lab-missing-prereq-reports",
      "lab-missing-packaged-prereq-reports",
      "lab-prereq-not-ready",
      "lab-packaged-prereq-not-ready",
      "lab-prereq-report-failed",
      "lab-packaged-prereq-report-failed",
      "lab-prereq-not-separate-hosts",
      "lab-packaged-prereq-not-separate-hosts",
      "lab-validation-bypasses-allowed",
      "lab-validation-bypass-flags",
      "lab-prereq-strict-gates-bypassed",
      "lab-prereq-strict-gates-missing",
      "lab-packaged-prereq-strict-gates-missing",
      "lab-prereq-strict-gates-not-separate-hosts",
      "lab-packaged-prereq-strict-gates-not-separate-hosts"
    ]) then {
      code: "fix-lab-evidence",
      title: "Fix perfect-network validation evidence",
      detail: "Capture topology, host reports, and strict ready prereq reports on the lab hosts, then rerun validation.",
      command: "benchmark/scripts/validate-lab-baseline.sh --input <perfect-artifacts>/combined --manifest <curve-manifest.jsonl> --manifest <raised-curve-manifest.jsonl> --manifest <contention-manifest.jsonl>"
    } else empty end,
    if has_prefix("lab-missing-") or has_any_code([
      "lab-iteration-gate-too-low",
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
      "missing-impairment-summary"
    ]) then {
      code: "run-impairment-campaign",
      title: "Run, validate, and summarize the impairment campaign",
      detail: "Run every generated impairment profile with netem evidence, merge profile artifacts, validate the campaign, and write the campaign summary before promotion.",
      command: impairment_plan_command,
      readme: maybe_path("readme"),
      plan: maybe_path("impairmentPlan"),
      artifactRoot: maybe_path("impairmentArtifacts")
    } else empty end,
    if has_any_code([
      "missing-impairment-baseline-manifest",
      "missing-impairment-summary",
      "invalid-impairment-baseline-kind"
    ]) then {
      code: "promote-impairment-baseline",
      title: "Promote the adverse-network impairment campaign",
      detail: "Create a promoted impairment package after campaign summary artifacts exist.",
      command: promote_impairment_command,
      helper: maybe_path("promoteScript"),
      artifactRoot: maybe_path("impairmentArtifacts")
    } else empty end,
    if has_prefix("impairment-") or has_any_code(["failed-impairment-summary"]) then {
      code: "rerun-impairment-campaign",
      title: "Fix or rerun the impairment campaign",
      detail: "The impairment package is missing required profiles, netem status evidence, validation, capacity, or contention rows.",
      command: "benchmark/scripts/plan-lab-impairment.sh --interface <nic> --target-host-role <receiver-role> --server-host <server-ip>"
    } else empty end
  ] | reduce .[] as $action ([]; if any(.[]; .code == $action.code) then . else . + [$action] end)
' "$issues_jsonl")"

proof_checklist_json="$(jq -n '
  [
    {
      stage: "Fresh handoff",
      requiredProof: [
        "handoff-manifest.json",
        "artifact-collection.json",
        "artifact-collection.md",
        "fresh-handoff-summary.json",
        "source-audit.json",
        "preflight/handoff-check.json"
      ],
      producedBy: [
        "benchmark/scripts/prepare-fresh-lab-handoff.sh"
      ]
    },
    {
      stage: "Perfect-network execution",
      requiredProof: [
        "host/prereq reports from at least two distinct hosts",
        "topology notes",
        "complete server/receiver artifacts",
        "combined/suite-aggregate.jsonl",
        "combined/bandwidth-capacity.jsonl",
        "combined/validation.json"
      ],
      producedBy: [
        "perfect-plan/README.md",
        "perfect-plan/merge-all.sh"
      ]
    },
    {
      stage: "Impairment execution",
      requiredProof: [
        "per-profile validation",
        "per-profile aggregate",
        "per-profile capacity",
        "netem/*-status-*.txt",
        "campaign-summary/impairment-summary.json"
      ],
      producedBy: [
        "impairment-plan/README.md",
        "impairment-plan/validate-all.sh",
        "impairment-plan/summarize-campaign.sh"
      ]
    },
    {
      stage: "Promotion",
      requiredProof: [
        "baseline-manifest.json",
        "copied planning manifests",
        "copied handoff/source-audit metadata",
        "copied artifact collection contract",
        "validation.json",
        "suite-aggregate.jsonl",
        "bandwidth-capacity.jsonl",
        "impairment-baseline-manifest.json",
        "copied campaign manifest",
        "copied per-profile evidence",
        "campaign summary"
      ],
      producedBy: [
        "promote-and-check.sh",
        "promote-lab-baseline.sh",
        "promote-lab-impairment.sh"
      ]
    }
  ]
')"

jq -n \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg labBaseline "$lab_baseline" \
  --arg impairmentBaseline "$impairment_baseline" \
  --arg handoffRoot "$handoff_root" \
  --arg handoffManifest "$handoff_manifest_path" \
  --arg handoffSummaryPath "$handoff_summary_path" \
  --arg handoffPreflightPath "$handoff_preflight_path" \
  --argjson handoffProvided "$handoff_provided_json" \
  --argjson handoffReady "$handoff_ready_json" \
  --argjson handoffSummary "$handoff_summary_json" \
  --argjson handoffPreflight "$handoff_preflight_json" \
  --argjson expectedImpairmentProfiles "$(csv_json_array "$expected_impairment_profiles")" \
  --argjson requiredCurvePayloadSizes "$required_curve_payloads_json" \
  --argjson requiredImpairmentContentionScenarios "$required_impairment_contention_json" \
  --argjson requiredBatchIntervalsMillis "$required_batch_intervals_json" \
  --argjson requiredImmediatePayloadSizes "$required_immediate_payloads_json" \
  --argjson requiredImmediateTargetClientMbps "$required_immediate_target_client_mbps" \
  --argjson requiredResourcePackChunkSizes "$required_resource_pack_chunks_json" \
  --argjson requiredResourcePackIntervalsMillis "$required_resource_pack_intervals_json" \
  --argjson requiredDisappearanceModes "$required_disappearance_modes_json" \
  --argjson requiredRetryPressureFields "$required_retry_pressure_fields_json" \
  --argjson requiredMinIterations "$required_min_iterations" \
  --argjson requiredMinContentionClients "$required_min_contention_clients" \
  --argjson requiredMinContentionTargetClientMbps "$required_min_contention_target_client_mbps" \
  --argjson requiredMinPrereqReports "$required_min_prereq_reports" \
  --argjson requiredMinReadyPrereqReports "$required_min_ready_prereq_reports" \
  --argjson requiredMinPrereqDistinctHostnames "$required_min_prereq_distinct_hostnames" \
  --argjson requireSourceAudit "$require_source_audit" \
  --argjson packagedManifestCount "$lab_packaged_manifest_count" \
  --argjson packagedHostReportCount "$lab_packaged_host_report_count" \
  --argjson packagedPrereq "$lab_packaged_prereq_summary" \
  --arg labArtifactCollectionJson "$lab_artifact_collection_json" \
  --arg labArtifactCollectionMd "$lab_artifact_collection_md" \
  --argjson labArtifactCollectionGroups "$lab_artifact_collection_groups_json" \
  --argjson requiredArtifactCollectionGroups "$required_artifact_collection_groups_json" \
  --argjson labSourceAudit "$lab_source_audit_json" \
  --argjson labHandoffSourceAudit "$lab_handoff_source_audit_json" \
  --argjson impairmentPackagedCampaignManifestExists "$impairment_packaged_campaign_manifest_exists" \
  --argjson impairmentPackagedProfiles "$impairment_packaged_profiles_json" \
  --argjson issues "$issues_array" \
  --argjson nextActions "$next_actions_json" \
  --argjson proofChecklist "$proof_checklist_json" \
  --slurpfile labValidation "$([[ -s "$lab_validation" ]] && printf '%s' "$lab_validation" || printf '%s' /dev/null)" \
  --slurpfile impairmentSummary "$([[ -s "$impairment_summary" ]] && printf '%s' "$impairment_summary" || printf '%s' /dev/null)" \
  '{
    checkedAt: $checkedAt,
    ready: (($issues | length) == 0),
    issueCount: ($issues | length),
    handoff: {
      provided: $handoffProvided,
      ready: $handoffReady,
      path: (if $handoffRoot == "" then null else $handoffRoot end),
      manifest: (if $handoffManifest == "" then null else $handoffManifest end),
      summaryPath: (if $handoffSummaryPath == "" then null else $handoffSummaryPath end),
      preflightPath: (if $handoffPreflightPath == "" then null else $handoffPreflightPath end),
      summary: $handoffSummary,
      preflight: $handoffPreflight
    },
    labBaseline: {
      path: $labBaseline,
      packagedManifestCount: $packagedManifestCount,
      packagedHostReportCount: $packagedHostReportCount,
      packagedPrereq: $packagedPrereq,
      artifactCollectionJson: $labArtifactCollectionJson,
      artifactCollectionMd: $labArtifactCollectionMd,
      artifactCollectionGroups: $labArtifactCollectionGroups,
      validation: ($labValidation[0] // null)
    },
    impairmentBaseline: {
      path: $impairmentBaseline,
      packagedCampaignManifestExists: $impairmentPackagedCampaignManifestExists,
      packagedProfiles: $impairmentPackagedProfiles,
      summary: ($impairmentSummary[0] // null)
    },
    expectedImpairmentProfiles: $expectedImpairmentProfiles,
    requiredCurvePayloadSizes: $requiredCurvePayloadSizes,
    requiredImpairmentContentionScenarios: $requiredImpairmentContentionScenarios,
    requiredBatchIntervalsMillis: $requiredBatchIntervalsMillis,
    requiredImmediatePayloadSizes: $requiredImmediatePayloadSizes,
    requiredImmediateTargetClientMbps: $requiredImmediateTargetClientMbps,
    requiredResourcePackChunkSizes: $requiredResourcePackChunkSizes,
    requiredResourcePackIntervalsMillis: $requiredResourcePackIntervalsMillis,
    requiredDisappearanceModes: $requiredDisappearanceModes,
    requiredRetryPressureFields: $requiredRetryPressureFields,
    requiredMinIterations: $requiredMinIterations,
    requiredMinContentionClients: $requiredMinContentionClients,
    requiredMinContentionTargetClientMbps: $requiredMinContentionTargetClientMbps,
    requiredMinPrereqReports: $requiredMinPrereqReports,
    requiredMinReadyPrereqReports: $requiredMinReadyPrereqReports,
    requiredMinPrereqDistinctHostnames: $requiredMinPrereqDistinctHostnames,
    requiredArtifactCollectionGroups: $requiredArtifactCollectionGroups,
    requireSourceAudit: $requireSourceAudit,
    labSourceAudit: $labSourceAudit,
    labHandoffSourceAudit: $labHandoffSourceAudit,
    proofChecklist: $proofChecklist,
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
  if [[ -n "$handoff_root" ]]; then
    echo "- Fresh handoff: \`$handoff_root\`"
    echo "- Fresh handoff ready: \`$(jq -r 'if .handoff.summary == null then "unknown" elif .handoff.summary.ready then "true" else "false" end' "$readiness_json")\`"
    echo "- Fresh handoff issues: \`$(jq -r '.handoff.summary.issueCount // "unknown"' "$readiness_json")\`"
  fi
  echo "- Required curve payload sizes: \`$required_curve_payload_sizes\`"
  echo "- Required impairment contention scenarios: \`$required_impairment_contention_scenarios\`"
  echo "- Required batch intervals ms: \`$required_batch_intervals_ms\`"
  echo "- Required immediate payload sizes: \`$required_immediate_payload_sizes\`"
  echo "- Required immediate target/client Mbps: \`$required_immediate_target_client_mbps\`"
  echo "- Required resource-pack chunk sizes: \`$required_resource_pack_chunk_sizes\`"
  echo "- Required resource-pack intervals ms: \`$required_resource_pack_intervals_ms\`"
  echo "- Required disappearance modes: \`$required_disappearance_modes\`"
  echo "- Required retry-pressure fields: \`$required_retry_pressure_fields\`"
  echo "- Required minimum measured iterations: \`$required_min_iterations\`"
  echo "- Required minimum contention clients: \`$required_min_contention_clients\`"
  echo "- Required minimum contention target/client Mbps: \`$required_min_contention_target_client_mbps\`"
  echo "- Required prereq reports: \`$required_min_prereq_reports\`"
  echo "- Required ready prereq reports: \`$required_min_ready_prereq_reports\`"
  echo "- Required distinct prereq hostnames: \`$required_min_prereq_distinct_hostnames\`"
  echo "- Require source audit: \`$require_source_audit\`"
  echo "- Artifact collection JSON: \`$lab_artifact_collection_json\`"
  echo "- Artifact collection checklist: \`$lab_artifact_collection_md\`"
  echo "- Artifact collection groups: \`$(jq -r '.labBaseline.artifactCollectionGroups | join(",")' "$readiness_json")\`"
  echo
  echo "## Lab Baseline"
  echo
  if [[ -s "$lab_validation" ]]; then
    echo "- Validation: \`$(jq -r 'if .passed then "passed" else "failed" end' "$lab_validation")\`"
    echo "- Rows: \`$(jq -r '.rowCount // 0' "$lab_validation")\`"
    echo "- Capacity rows: \`$(jq -r '.capacityRowCount // 0' "$lab_validation")\`"
    echo "- Packaged planning manifests: \`$lab_packaged_manifest_count\`"
    echo "- Host reports: \`$(jq -r '.hostReportCount // 0' "$lab_validation")\`"
    echo "- Packaged host reports: \`$lab_packaged_host_report_count\`"
    echo "- Distinct hostnames: \`$(jq -r '.distinctHostnameCount // 0' "$lab_validation")\`"
    echo "- Prereq reports: \`$(jq -r '.prereqReportCount // 0' "$lab_validation")\`"
    echo "- Ready prereq reports: \`$(jq -r '.readyPrereqReportCount // 0' "$lab_validation")\`"
    echo "- Strict prereq reports: \`$(jq -r '.strictPrereqReportCount // 0' "$lab_validation")\`"
    echo "- Distinct prereq hostnames: \`$(jq -r '.prereqDistinctHostnameCount // 0' "$lab_validation")\`"
    echo "- Strict prereq hostnames: \`$(jq -r '.strictPrereqDistinctHostnameCount // 0' "$lab_validation")\`"
    echo "- Packaged prereq reports: \`$(jq -r '.reportCount // 0' <<<"$lab_packaged_prereq_summary")\`"
    echo "- Packaged ready prereq reports: \`$(jq -r '.readyReportCount // 0' <<<"$lab_packaged_prereq_summary")\`"
    echo "- Packaged strict prereq reports: \`$(jq -r '.strictReportCount // 0' <<<"$lab_packaged_prereq_summary")\`"
    echo "- Packaged distinct prereq hostnames: \`$(jq -r '.distinctHostnameCount // 0' <<<"$lab_packaged_prereq_summary")\`"
    echo "- Packaged strict prereq hostnames: \`$(jq -r '.strictDistinctHostnameCount // 0' <<<"$lab_packaged_prereq_summary")\`"
    echo "- Validated minimum iterations: \`$(jq -r '.minIterations // "missing"' "$lab_validation")\`"
    echo "- Validated minimum contention clients: \`$(jq -r '.minContentionClients // "missing"' "$lab_validation")\`"
    echo "- Validated minimum contention target/client Mbps: \`$(jq -r '.minContentionTargetClientMbps // "missing"' "$lab_validation")\`"
    echo "- Packaged artifact collection groups: \`$(jq -r '.labBaseline.artifactCollectionGroups | join(",")' "$readiness_json")\`"
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
    echo "- Packaged campaign manifest: \`$impairment_packaged_campaign_manifest_exists\`"
    echo "- Packaged profiles: \`$(jq -r 'length' <<<"$impairment_packaged_profiles_json")\`"
    echo "- Packaged profile validations: \`$(jq -r '[.[] | select(.validationExists == true)] | length' <<<"$impairment_packaged_profiles_json")\`"
    echo "- Packaged profile aggregates: \`$(jq -r '[.[] | select(.aggregateExists == true)] | length' <<<"$impairment_packaged_profiles_json")\`"
    echo "- Packaged profile capacity files: \`$(jq -r '[.[] | select(.capacityExists == true)] | length' <<<"$impairment_packaged_profiles_json")\`"
    echo "- Packaged profile netem status files: \`$(jq -r '[.[] | .netemStatusCount] | add // 0' <<<"$impairment_packaged_profiles_json")\`"
    echo "- Allow missing retry-pressure fields: \`$(jq -r '.allowMissingRetryPressureFields // false' "$impairment_summary")\`"
  else
    echo "No impairment summary found."
  fi
  echo
  echo "## Baseline Proof Checklist"
  echo
  echo "| Stage | Required proof | Produced by |"
  echo "| --- | --- | --- |"
  jq -r '.proofChecklist[] | "| \(.stage) | \(.requiredProof | join(", ")) | \(.producedBy | join(", ")) |"' "$readiness_json"
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
