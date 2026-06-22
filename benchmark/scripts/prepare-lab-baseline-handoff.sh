#!/usr/bin/env bash
set -euo pipefail

output_root=""
artifact_root=""
server_host=""
bind_host="0.0.0.0"
port="19132"
interface=""
case_prefix="lab"
profiles="perfect,near-loss,regional-loss,poor,severe"
target_host_role="receiver-a"
curve_receivers=("receiver-a=1")
curve_payload_sizes="64,256,512,1200,1340,1400,262144"
curve_rates_mbps="100,250,500,750,1000,1500,2000,unlimited"
contention_receivers=()
contention_cases="fanout,immediate,fairness,disappear-blackhole,batched,resource-pack"
contention_payload_size="512"
reliability="reliable_ordered"
per_client_mbps="5"
immediate_payload_size="256"
immediate_per_client_mbps="1"
batch_intervals="10ms,20ms,50ms"
batch_payload_sizes="128,512,1200"
logical_packets_per_batch="8"
batch_groups="4"
resource_pack_chunk_sizes="8192,262144"
resource_pack_interval="200ms"
raised_packet_limit="100000"
raised_global_packet_limit="1000000"
max_queued_bytes="67108864"
warmup="10s"
duration="60s"
iterations="3"
start_delay="90s"
start_offset="180s"
sudo_netem=false
expect_mtu=""
expect_min_cpus=""
require_cpu_performance=false
common_args=""
source_audit=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/prepare-lab-baseline-handoff.sh --server-host HOST --interface NIC --expect-mtu N --expect-min-cpus N [options]

Generates a complete lab handoff directory for the established RakNet baseline:
  - perfect-network lab baseline plan
  - host/NIC-level impairment campaign plan
  - top-level run order and readiness commands

The generated handoff does not run benchmarks. It composes the lower-level
planners so lab operators have one directory to copy/review before execution.

Options:
  --out DIR                         Handoff output directory. Default: benchmark/build/benchmark-results/lab-handoff-<timestamp>.
  --artifact-root DIR               Artifact root used by generated benchmark commands. Default: benchmark/build/benchmark-results/lab-run-<timestamp>.
  --server-host HOST                Server host/IP used by receiver workers. Required.
  --bind-host HOST                  Server bind host. Default: 0.0.0.0.
  --server-bind-host HOST           Alias for --bind-host.
  --port PORT                       UDP port. Default: 19132.
  --interface NIC                   Lab NIC used by host capture and netem scripts. Required.
  --curve-receiver NAME:CLIENTS     Receiver for curve. NAME=CLIENTS is accepted. May repeat. Default: receiver-a=1.
  --curve-payload-sizes CSV         Payload sizes for bandwidth curve. Default: 64,256,512,1200,1340,1400,262144.
  --curve-rates-mbps CSV            Offered Mbps points for bandwidth curve. Default: 100,250,500,750,1000,1500,2000,unlimited.
  --contention-receiver NAME:CLIENTS Receiver for contention. NAME=CLIENTS is accepted. May repeat. Default: receiver-a=250, receiver-b=250.
  --receiver NAME:CLIENTS           Alias for --contention-receiver.
  --profiles CSV                    Impairment profiles. Default: perfect,near-loss,regional-loss,poor,severe.
  --target-host-role ROLE           Host/namespace role shaped by impairment netem scripts. Default: receiver-a.
  --case-prefix NAME                Case prefix. Default: lab.
  --contention-cases CSV            Contention cases. Default: fanout,immediate,fairness,disappear-blackhole,batched,resource-pack.
  --contention-payload-size N       Payload size for contention. Default: 512.
  --reliability MODE                Reliability mode passed to benchmark workers. Default: reliable_ordered.
  --per-client-mbps N               Contention per-client offered rate. Default: 5.
  --immediate-payload-size N        Payload size for immediate small-packet fanout. Default: 256.
  --immediate-per-client-mbps N     Per-client offered rate for immediate small-packet fanout. Default: 1.
  --batch-intervals CSV             Batch intervals for batched cases. Default: 10ms,20ms,50ms.
  --batch-payload-sizes CSV         Batch payload sizes for batched cases. Default: 128,512,1200.
  --logical-packets-per-batch N     Logical packets encoded into each batch. Default: 8.
  --batch-groups N                  Payload variant groups for batched cases. Default: 4.
  --resource-pack-chunk-sizes CSV   Resource-pack chunk sizes. Default: 8192,262144.
  --resource-pack-interval DURATION Resource-pack chunk interval. Default: 200ms.
  --raised-packet-limit N           Raised-limiter packet limit. Default: 100000.
  --raised-global-packet-limit N    Raised-limiter global packet limit. Default: 1000000.
  --max-queued-bytes N              Queue cap for lab runs. Default: 67108864.
  --warmup DURATION                 Warmup. Default: 10s.
  --duration DURATION               Measurement duration. Default: 60s.
  --iterations N                    Measured iterations. Default: 3.
  --start-delay DURATION            Server connection wait. Default: 90s.
  --start-offset DURATION           First case start offset from planning time. Default: 180s.
  --sudo-netem                      Generate impairment netem scripts with sudo.
  --expect-mtu N                    Expected lab interface MTU for strict prereq reports. Required.
  --expect-min-cpus N               Expected minimum online CPU count for strict prereq reports. Required.
  --require-cpu-performance         Include strict CPU performance-governor prereq gate.
  --common-args "..."               Extra benchmark args appended to worker commands.
  --source-audit FILE               Optional source-audit.json from capture-production-evidence.sh.
  --help                            Show this help.

Outputs:
  perfect-plan/                     plan-lab-baseline.sh output.
  impairment-plan/                  plan-lab-impairment.sh output.
  handoff-manifest.json             Machine-readable handoff metadata.
  artifact-collection.json          Machine-readable artifact collection checklist.
  artifact-collection.md            Human-readable artifact collection checklist.
  README.md                         Handoff preflight, run order, promotion, and readiness commands.
  promote-and-check.sh              Promotes completed artifacts and runs readiness with this handoff's paths.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      output_root="$2"
      shift 2
      ;;
    --artifact-root)
      artifact_root="$2"
      shift 2
      ;;
    --server-host)
      server_host="$2"
      shift 2
      ;;
    --bind-host|--server-bind-host)
      bind_host="$2"
      shift 2
      ;;
    --port)
      port="$2"
      shift 2
      ;;
    --interface)
      interface="$2"
      shift 2
      ;;
    --curve-receiver)
      if [[ "${curve_receivers[*]}" == "receiver-a=1" ]]; then
        curve_receivers=()
      fi
      curve_receivers+=("$2")
      shift 2
      ;;
    --contention-receiver|--receiver)
      contention_receivers+=("$2")
      shift 2
      ;;
    --profiles)
      profiles="$2"
      shift 2
      ;;
    --target-host-role)
      target_host_role="$2"
      shift 2
      ;;
    --curve-payload-sizes)
      curve_payload_sizes="$2"
      shift 2
      ;;
    --curve-rates-mbps)
      curve_rates_mbps="$2"
      shift 2
      ;;
    --case-prefix|--case)
      case_prefix="$2"
      shift 2
      ;;
    --contention-cases)
      contention_cases="$2"
      shift 2
      ;;
    --contention-payload-size)
      contention_payload_size="$2"
      shift 2
      ;;
    --reliability)
      reliability="$2"
      shift 2
      ;;
    --per-client-mbps)
      per_client_mbps="$2"
      shift 2
      ;;
    --immediate-payload-size)
      immediate_payload_size="$2"
      shift 2
      ;;
    --immediate-per-client-mbps|--immediate-target-client-mbps)
      immediate_per_client_mbps="$2"
      shift 2
      ;;
    --batch-intervals)
      batch_intervals="$2"
      shift 2
      ;;
    --batch-payload-sizes)
      batch_payload_sizes="$2"
      shift 2
      ;;
    --logical-packets-per-batch|--batch-logical-packets)
      logical_packets_per_batch="$2"
      shift 2
      ;;
    --batch-groups|--group-count)
      batch_groups="$2"
      shift 2
      ;;
    --resource-pack-chunk-sizes|--chunk-sizes)
      resource_pack_chunk_sizes="$2"
      shift 2
      ;;
    --resource-pack-interval|--chunk-interval)
      resource_pack_interval="$2"
      shift 2
      ;;
    --raised-packet-limit)
      raised_packet_limit="$2"
      shift 2
      ;;
    --raised-global-packet-limit)
      raised_global_packet_limit="$2"
      shift 2
      ;;
    --max-queued-bytes)
      max_queued_bytes="$2"
      shift 2
      ;;
    --warmup)
      warmup="$2"
      shift 2
      ;;
    --duration)
      duration="$2"
      shift 2
      ;;
    --iterations)
      iterations="$2"
      shift 2
      ;;
    --start-delay)
      start_delay="$2"
      shift 2
      ;;
    --start-offset)
      start_offset="$2"
      shift 2
      ;;
    --sudo-netem)
      sudo_netem=true
      shift
      ;;
    --expect-mtu|--expected-mtu)
      expect_mtu="$2"
      shift 2
      ;;
    --expect-min-cpus|--expected-min-cpus)
      expect_min_cpus="$2"
      shift 2
      ;;
    --require-cpu-performance)
      require_cpu_performance=true
      shift
      ;;
    --common-args)
      common_args="$2"
      shift 2
      ;;
    --source-audit)
      source_audit="$2"
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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

if [[ -z "$server_host" || "$server_host" == "<server-ip>" ]]; then
  echo "--server-host is required and must not be a placeholder" >&2
  exit 2
fi
if [[ -z "$interface" || "$interface" == "<nic>" ]]; then
  echo "--interface is required and must not be a placeholder" >&2
  exit 2
fi
if [[ -z "$expect_mtu" || "$expect_mtu" == "<mtu>" ]]; then
  echo "--expect-mtu is required and must not be a placeholder" >&2
  exit 2
fi
if [[ -z "$expect_min_cpus" || "$expect_min_cpus" == "<min-cpus>" ]]; then
  echo "--expect-min-cpus is required and must not be a placeholder" >&2
  exit 2
fi
if [[ "${#contention_receivers[@]}" -eq 0 ]]; then
  contention_receivers=("receiver-a=250" "receiver-b=250")
fi

positive_int() {
  [[ "$1" =~ ^[0-9]+$ && "$1" -gt 0 ]]
}

receiver_client_count() {
  local spec="$1"
  local clients=""
  if [[ "$spec" == *"="* ]]; then
    clients="${spec##*=}"
  elif [[ "$spec" == *":"* ]]; then
    clients="${spec##*:}"
  else
    return 1
  fi
  positive_int "$clients" || return 1
  printf '%s\n' "$clients"
}

receiver_role_name() {
  local spec="$1"
  if [[ "$spec" == *"="* ]]; then
    printf '%s\n' "${spec%%=*}"
  elif [[ "$spec" == *":"* ]]; then
    printf '%s\n' "${spec%%:*}"
  else
    return 1
  fi
}

append_unique_role() {
  local role="$1"
  local existing
  [[ -n "$role" ]] || return
  for existing in "${prereq_roles[@]}"; do
    if [[ "$existing" == "$role" ]]; then
      return
    fi
  done
  prereq_roles+=("$role")
}

non_empty_csv() {
  [[ -n "$1" && "$1" != *, && "$1" != ,* ]]
}

non_negative_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

for value_name in port contention_payload_size immediate_payload_size iterations raised_packet_limit raised_global_packet_limit max_queued_bytes expect_mtu expect_min_cpus; do
  if ! positive_int "${!value_name}"; then
    echo "--${value_name//_/-} must be a positive integer: ${!value_name}" >&2
    exit 2
  fi
done
for value_name in per_client_mbps immediate_per_client_mbps; do
  if ! non_negative_number "${!value_name}"; then
    echo "--${value_name//_/-} must be a non-negative number: ${!value_name}" >&2
    exit 2
  fi
done
for value in "$profiles" "$curve_payload_sizes" "$curve_rates_mbps" "$contention_cases" "$batch_intervals" "$batch_payload_sizes" "$resource_pack_chunk_sizes"; do
  if ! non_empty_csv "$value"; then
    echo "CSV options must be non-empty and cannot start or end with a comma: $value" >&2
    exit 2
  fi
done
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
if [[ "$(duration_millis "$resource_pack_interval")" -le 0 ]]; then
  echo "--resource-pack-interval must be greater than zero" >&2
  exit 2
fi
if ! positive_int "$logical_packets_per_batch"; then
  echo "--logical-packets-per-batch must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$batch_groups"; then
  echo "--batch-groups must be a positive integer" >&2
  exit 2
fi
IFS=',' read -r -a batch_interval_array <<<"$batch_intervals"
for batch_interval in "${batch_interval_array[@]}"; do
  batch_interval="${batch_interval//[[:space:]]/}"
  if [[ "$(duration_millis "$batch_interval")" -le 0 ]]; then
    echo "--batch-intervals entries must be positive durations: $batch_interval" >&2
    exit 2
  fi
done
IFS=',' read -r -a batch_payload_array <<<"$batch_payload_sizes"
for batch_payload_size in "${batch_payload_array[@]}"; do
  batch_payload_size="${batch_payload_size//[[:space:]]/}"
  if ! positive_int "$batch_payload_size"; then
    echo "--batch-payload-sizes entries must be positive integers: $batch_payload_size" >&2
    exit 2
  fi
done
IFS=',' read -r -a resource_pack_chunk_array <<<"$resource_pack_chunk_sizes"
for chunk_size in "${resource_pack_chunk_array[@]}"; do
  chunk_size="${chunk_size//[[:space:]]/}"
  if ! positive_int "$chunk_size"; then
    echo "--resource-pack-chunk-sizes entries must be positive integers: $chunk_size" >&2
    exit 2
  fi
done

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to write the lab handoff manifest" >&2
  exit 2
fi

contention_client_total=0
prereq_roles=("server")
for receiver in "${curve_receivers[@]}"; do
  receiver_role="$(receiver_role_name "$receiver")" || {
    echo "--curve-receiver must be NAME=CLIENTS or NAME:CLIENTS with a positive integer client count: $receiver" >&2
    exit 2
  }
  append_unique_role "$receiver_role"
done
for receiver in "${contention_receivers[@]}"; do
  receiver_role="$(receiver_role_name "$receiver")" || {
    echo "--contention-receiver must be NAME=CLIENTS or NAME:CLIENTS with a positive integer client count: $receiver" >&2
    exit 2
  }
  append_unique_role "$receiver_role"
  receiver_clients="$(receiver_client_count "$receiver")" || {
    echo "--contention-receiver must be NAME=CLIENTS or NAME:CLIENTS with a positive integer client count: $receiver" >&2
    exit 2
  }
  contention_client_total=$((contention_client_total + receiver_clients))
done
append_unique_role "$target_host_role"

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/lab-handoff-$timestamp"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi
if [[ -z "$artifact_root" ]]; then
  artifact_root="$repo_root/benchmark/build/benchmark-results/lab-run-$timestamp"
elif [[ "$artifact_root" != /* ]]; then
  artifact_root="$repo_root/$artifact_root"
fi

mkdir -p "$output_root"

perfect_plan="$output_root/perfect-plan"
impairment_plan="$output_root/impairment-plan"
perfect_artifacts="$artifact_root/perfect"
impairment_artifacts="$artifact_root/impairment"
readme="$output_root/README.md"
handoff_manifest="$output_root/handoff-manifest.json"
artifact_collection_json="$output_root/artifact-collection.json"
artifact_collection_md="$output_root/artifact-collection.md"
promote_script="$output_root/promote-and-check.sh"
prereq_script="$output_root/prereq-commands.sh"
production_evidence_doc_rel="benchmark/docs/production-usage-evidence.md"
production_evidence_doc="$repo_root/$production_evidence_doc_rel"
production_evidence_exists=false
production_evidence_sha256=""

if [[ -s "$production_evidence_doc" ]]; then
  production_evidence_exists=true
  if command -v sha256sum >/dev/null 2>&1; then
    production_evidence_sha256="$(sha256sum "$production_evidence_doc" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    production_evidence_sha256="$(shasum -a 256 "$production_evidence_doc" | awk '{print $1}')"
  fi
fi
if [[ "$production_evidence_exists" != "true" ]]; then
  echo "production evidence document is required for lab handoffs: $production_evidence_doc" >&2
  exit 2
fi
if [[ ! "$production_evidence_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "unable to compute SHA-256 for production evidence document: $production_evidence_doc" >&2
  exit 2
fi

source_audit_json="null"
source_audit_path=""
source_audit_sha256=""
if [[ -n "$source_audit" ]]; then
  if [[ "$source_audit" == /* ]]; then
    source_audit_path="$source_audit"
  else
    source_audit_path="$repo_root/$source_audit"
  fi
  if [[ ! -s "$source_audit_path" ]]; then
    echo "source audit file is missing or empty: $source_audit_path" >&2
    exit 2
  fi
  if ! jq -e '.kind == "raknet-production-source-audit" and .ready == true' "$source_audit_path" >/dev/null; then
    echo "source audit must be a ready raknet-production-source-audit artifact: $source_audit_path" >&2
    exit 2
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    source_audit_sha256="$(sha256sum "$source_audit_path" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    source_audit_sha256="$(shasum -a 256 "$source_audit_path" | awk '{print $1}')"
  fi
  if [[ ! "$source_audit_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "unable to compute SHA-256 for source audit: $source_audit_path" >&2
    exit 2
  fi
  source_audit_json="$(jq -c --arg document "$source_audit_path" --arg sha256 "$source_audit_sha256" '
    {
      document: $document,
      exists: true,
      sha256: $sha256,
      ready: (.ready == true),
      issueCount: (.issueCount // 0),
      networkRevision: (.networkRevision // ""),
      networkShortRevision: (.networkShortRevision // ""),
      networkDirtyTrackedFiles: (
        if has("networkDirtyTrackedFiles") then
          .networkDirtyTrackedFiles
        else
          null
        end
      ),
      evidenceDocument: (.evidenceDocument // null),
      requiredSources: (.requiredSources // []),
      sources: [(.sources // [])[] | {
        id,
        visibility,
        available,
        revision,
        shortRevision,
        dirtyTrackedFiles,
        pathIncluded: (.path != null)
      }]
    }
  ' "$source_audit_path")"
fi

json_array_from_args() {
  if [[ "$#" -eq 0 ]]; then
    printf '[]'
    return
  fi

  printf '%s\n' "$@" | jq -R -s 'split("\n") | map(select(length > 0))'
}

json_array_from_csv() {
  printf '%s' "$1" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
    | jq -R -s 'split("\n") | map(select(length > 0))'
}

json_number_array_from_csv() {
  printf '%s' "$1" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
    | jq -R -s 'split("\n") | map(select(length > 0) | tonumber)'
}

common_baseline_args=(
  --server-host "$server_host"
  --bind-host "$bind_host"
  --port "$port"
  --interface "$interface"
  --curve-payload-sizes "$curve_payload_sizes"
  --curve-rates-mbps "$curve_rates_mbps"
  --contention-cases "$contention_cases"
  --contention-payload-size "$contention_payload_size"
  --reliability "$reliability"
  --per-client-mbps "$per_client_mbps"
  --immediate-payload-size "$immediate_payload_size"
  --immediate-per-client-mbps "$immediate_per_client_mbps"
  --batch-intervals "$batch_intervals"
  --batch-payload-sizes "$batch_payload_sizes"
  --logical-packets-per-batch "$logical_packets_per_batch"
  --batch-groups "$batch_groups"
  --resource-pack-chunk-sizes "$resource_pack_chunk_sizes"
  --resource-pack-interval "$resource_pack_interval"
  --raised-packet-limit "$raised_packet_limit"
  --raised-global-packet-limit "$raised_global_packet_limit"
  --max-queued-bytes "$max_queued_bytes"
  --warmup "$warmup"
  --duration "$duration"
  --iterations "$iterations"
  --start-delay "$start_delay"
  --start-offset "$start_offset"
)
if [[ -n "$common_args" ]]; then
  common_baseline_args+=(--common-args "$common_args")
fi
for receiver in "${curve_receivers[@]}"; do
  common_baseline_args+=(--curve-receiver "$receiver")
done
for receiver in "${contention_receivers[@]}"; do
  common_baseline_args+=(--contention-receiver "$receiver")
done

"$script_dir/plan-lab-baseline.sh" \
  --out "$perfect_plan" \
  --artifact-root "$perfect_artifacts" \
  --case-prefix "$case_prefix-perfect" \
  "${common_baseline_args[@]}"

impairment_args=(
  --out "$impairment_plan"
  --artifact-root "$impairment_artifacts"
  --interface "$interface"
  --profiles "$profiles"
  --target-host-role "$target_host_role"
  --case-prefix "$case_prefix-impairment"
)
if "$sudo_netem"; then
  impairment_args+=(--sudo-netem)
fi

"$script_dir/plan-lab-impairment.sh" \
  "${impairment_args[@]}" \
  -- \
  "${common_baseline_args[@]}"

git_revision="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
curve_receivers_json="$(json_array_from_args "${curve_receivers[@]}")"
contention_receivers_json="$(json_array_from_args "${contention_receivers[@]}")"
prereq_roles_json="$(json_array_from_args "${prereq_roles[@]}")"
profiles_json="$(json_array_from_csv "$profiles")"
curve_payload_sizes_json="$(json_number_array_from_csv "$curve_payload_sizes")"
curve_rates_mbps_json="$(json_array_from_csv "$curve_rates_mbps")"
contention_cases_json="$(json_array_from_csv "$contention_cases")"
batch_intervals_json="$(json_array_from_csv "$batch_intervals")"
batch_payload_sizes_json="$(json_number_array_from_csv "$batch_payload_sizes")"
resource_pack_chunk_sizes_json="$(json_number_array_from_csv "$resource_pack_chunk_sizes")"
strict_prereq_args=(--expect-mtu "$expect_mtu" --expect-min-cpus "$expect_min_cpus" --require-clock-sync --require-no-netem)
if "$require_cpu_performance"; then
  strict_prereq_args+=(--require-cpu-performance)
fi
strict_prereq_flags="$(printf ' %q' "${strict_prereq_args[@]}")"
strict_prereq_flags="${strict_prereq_flags# }"
prereq_role_flags="$(printf ' %q' "${prereq_roles[@]}")"
prereq_role_flags="${prereq_role_flags# }"
preflight_flags=""
if [[ -n "$source_audit_path" ]]; then
  preflight_flags=" --require-source-audit --require-current-revision"
fi

jq -n \
  --arg kind "raknet-lab-handoff" \
  --arg generatedAt "$timestamp" \
  --arg gitRevision "$git_revision" \
  --arg repoRoot "$repo_root" \
  --arg outputRoot "$output_root" \
  --arg artifactRoot "$artifact_root" \
  --arg perfectPlan "$perfect_plan" \
  --arg impairmentPlan "$impairment_plan" \
  --arg perfectArtifacts "$perfect_artifacts" \
  --arg impairmentArtifacts "$impairment_artifacts" \
  --arg productionEvidenceDoc "$production_evidence_doc_rel" \
  --arg productionEvidenceSha256 "$production_evidence_sha256" \
  --arg artifactCollectionJson "$artifact_collection_json" \
  --arg artifactCollectionMd "$artifact_collection_md" \
  --arg readme "$readme" \
  --arg promoteScript "$promote_script" \
  --arg prereqScript "$prereq_script" \
  --arg serverHost "$server_host" \
  --arg bindHost "$bind_host" \
  --arg port "$port" \
  --arg interface "$interface" \
  --arg targetHostRole "$target_host_role" \
  --arg casePrefix "$case_prefix" \
  --arg contentionPayloadSize "$contention_payload_size" \
  --arg contentionClientTotal "$contention_client_total" \
  --arg reliability "$reliability" \
  --arg perClientMbps "$per_client_mbps" \
  --arg immediatePayloadSize "$immediate_payload_size" \
  --arg immediatePerClientMbps "$immediate_per_client_mbps" \
  --arg logicalPacketsPerBatch "$logical_packets_per_batch" \
  --arg batchGroups "$batch_groups" \
  --arg resourcePackInterval "$resource_pack_interval" \
  --arg raisedPacketLimit "$raised_packet_limit" \
  --arg raisedGlobalPacketLimit "$raised_global_packet_limit" \
  --arg maxQueuedBytes "$max_queued_bytes" \
  --arg warmup "$warmup" \
  --arg duration "$duration" \
  --arg iterations "$iterations" \
  --arg startDelay "$start_delay" \
  --arg startOffset "$start_offset" \
  --arg expectMtu "$expect_mtu" \
  --arg expectMinCpus "$expect_min_cpus" \
  --arg commonArgs "$common_args" \
  --argjson curveReceivers "$curve_receivers_json" \
  --argjson contentionReceivers "$contention_receivers_json" \
  --argjson prereqRoles "$prereq_roles_json" \
  --argjson profiles "$profiles_json" \
  --argjson curvePayloadSizesList "$curve_payload_sizes_json" \
  --argjson curveRatesMbpsList "$curve_rates_mbps_json" \
  --argjson contentionCases "$contention_cases_json" \
  --argjson batchIntervals "$batch_intervals_json" \
  --argjson batchPayloadSizes "$batch_payload_sizes_json" \
  --argjson resourcePackChunkSizes "$resource_pack_chunk_sizes_json" \
  --argjson productionEvidenceExists "$production_evidence_exists" \
  --argjson sourceAudit "$source_audit_json" \
  --argjson sudoNetem "$sudo_netem" \
  --argjson requireCpuPerformance "$require_cpu_performance" \
  '{
    kind: $kind,
    generatedAt: $generatedAt,
    gitRevision: $gitRevision,
    repoRoot: $repoRoot,
    outputRoot: $outputRoot,
    artifactRoot: $artifactRoot,
    readme: $readme,
    artifactCollectionJson: $artifactCollectionJson,
    artifactCollectionMd: $artifactCollectionMd,
    promoteScript: $promoteScript,
    prereqScript: $prereqScript,
    perfectPlan: $perfectPlan,
    impairmentPlan: $impairmentPlan,
    perfectArtifacts: $perfectArtifacts,
    impairmentArtifacts: $impairmentArtifacts,
    productionEvidence: {
      document: $productionEvidenceDoc,
      exists: $productionEvidenceExists,
      sha256: $productionEvidenceSha256
    },
    sourceAudit: $sourceAudit,
    serverHost: $serverHost,
    bindHost: $bindHost,
    port: ($port | tonumber),
    interface: $interface,
    profiles: $profiles,
    targetHostRole: $targetHostRole,
    prereqRoles: $prereqRoles,
    curveReceivers: $curveReceivers,
    curvePayloadSizes: $curvePayloadSizesList,
    curveRatesMbps: $curveRatesMbpsList,
    contentionReceivers: $contentionReceivers,
    contentionCases: $contentionCases,
    casePrefix: $casePrefix,
    contentionPayloadSize: ($contentionPayloadSize | tonumber),
    contentionClientTotal: ($contentionClientTotal | tonumber),
    reliability: $reliability,
    perClientMbps: ($perClientMbps | tonumber),
    immediatePayloadSize: ($immediatePayloadSize | tonumber),
    immediatePerClientMbps: ($immediatePerClientMbps | tonumber),
    batchIntervals: $batchIntervals,
    batchPayloadSizes: $batchPayloadSizes,
    logicalPacketsPerBatch: ($logicalPacketsPerBatch | tonumber),
    batchGroups: ($batchGroups | tonumber),
    resourcePackChunkSizes: $resourcePackChunkSizes,
    resourcePackInterval: $resourcePackInterval,
    raisedPacketLimit: ($raisedPacketLimit | tonumber),
    raisedGlobalPacketLimit: ($raisedGlobalPacketLimit | tonumber),
    maxQueuedBytes: ($maxQueuedBytes | tonumber),
    warmup: $warmup,
    duration: $duration,
    iterations: ($iterations | tonumber),
    startDelay: $startDelay,
    startOffset: $startOffset,
    expectedMtu: ($expectMtu | tonumber),
    expectedMinCpus: ($expectMinCpus | tonumber),
    sudoNetem: $sudoNetem,
    requireCpuPerformance: $requireCpuPerformance,
    commonArgs: $commonArgs
  }' >"$handoff_manifest"

jq -n \
  --arg kind "raknet-lab-artifact-collection" \
  --arg generatedAt "$timestamp" \
  --arg handoffManifest "$handoff_manifest" \
  --arg outputRoot "$output_root" \
  --arg artifactRoot "$artifact_root" \
  --arg perfectPlan "$perfect_plan" \
  --arg impairmentPlan "$impairment_plan" \
  --arg perfectArtifacts "$perfect_artifacts" \
  --arg impairmentArtifacts "$impairment_artifacts" \
  --arg promoteScript "$promote_script" \
  --arg prereqScript "$prereq_script" \
  --argjson prereqRoles "$prereq_roles_json" \
  --argjson profiles "$profiles_json" \
  --slurpfile impairmentManifest "$impairment_plan/manifest.jsonl" \
  '{
    kind: $kind,
    generatedAt: $generatedAt,
    handoffManifest: $handoffManifest,
    outputRoot: $outputRoot,
    artifactRoot: $artifactRoot,
    perfectPlan: $perfectPlan,
    impairmentPlan: $impairmentPlan,
    perfectArtifacts: $perfectArtifacts,
    impairmentArtifacts: $impairmentArtifacts,
    promoteScript: $promoteScript,
    prereqScript: $prereqScript,
    prereqRoles: $prereqRoles,
    profiles: $profiles,
    requiredBeforePromotion: [
      "perfect-topology",
      "perfect-host-captures",
      "perfect-prereq-reports",
      "perfect-worker-artifacts",
      "perfect-combined-artifacts",
      "impairment-profile-artifacts",
      "impairment-netem-evidence",
      "impairment-campaign-summary"
    ],
    collectionGroups: [
      {
        id: "perfect-topology",
        phase: "perfect-network",
        required: true,
        destination: ($perfectArtifacts + "/topology.md"),
        producer: ($perfectPlan + "/topology-template.md"),
        expected: ["topology.md"]
      },
      {
        id: "perfect-host-captures",
        phase: "perfect-network",
        required: true,
        destination: ($perfectArtifacts + "/host-<role>-<hostname>/"),
        producer: ($perfectPlan + "/host-capture-commands.sh"),
        roles: $prereqRoles,
        expected: ["host-report.md"]
      },
      {
        id: "perfect-prereq-reports",
        phase: "perfect-network",
        required: true,
        destination: ($perfectArtifacts + "/prereq-<role>-<hostname>/"),
        producer: ($prereqScript + " with ARTIFACT_ROOT=" + $perfectArtifacts),
        roles: $prereqRoles,
        expected: ["prereq.json", "prereq.md"]
      },
      {
        id: "perfect-worker-artifacts",
        phase: "perfect-network",
        required: true,
        destination: $perfectArtifacts,
        producer: ($perfectPlan + "/README.md worker commands"),
        expected: [
          "curve/server-*/summary.json",
          "curve/receiver-*/summary.json",
          "curve-raised/server-*/summary.json",
          "curve-raised/receiver-*/summary.json",
          "contention/server-*/summary.json",
          "contention/receiver-*/summary.json"
        ]
      },
      {
        id: "perfect-combined-artifacts",
        phase: "perfect-network",
        required: true,
        destination: ($perfectArtifacts + "/combined/"),
        producer: ($perfectPlan + "/merge-all.sh"),
        expected: [
          "suite-aggregate.jsonl",
          "bandwidth-capacity.jsonl",
          "validation.json",
          "validation.md"
        ]
      },
      {
        id: "impairment-profile-artifacts",
        phase: "impairment-campaign",
        required: true,
        destination: $impairmentArtifacts,
        producer: ($impairmentPlan + "/README.md profile commands"),
        profiles: [
          $impairmentManifest[] | {
            profile,
            artifactRoot,
            plan,
            expected: [
              "topology.md",
              "host-<role>-<hostname>/host-report.md",
              "prereq-<role>-<hostname>/prereq.json",
              "curve/server-*/summary.json",
              "curve/receiver-*/summary.json",
              "curve-raised/server-*/summary.json",
              "curve-raised/receiver-*/summary.json",
              "contention/server-*/summary.json",
              "contention/receiver-*/summary.json",
              "combined/suite-aggregate.jsonl",
              "combined/bandwidth-capacity.jsonl",
              "combined/validation.json"
            ]
          }
        ]
      },
      {
        id: "impairment-netem-evidence",
        phase: "impairment-campaign",
        required: true,
        destination: ($impairmentArtifacts + "/<profile>/netem/"),
        producer: ($impairmentPlan + "/netem/<profile>-*.sh"),
        profiles: [
          $impairmentManifest[] | {
            profile,
            netemEvidenceDir,
            expected: [
              (.profile + "-apply-*.txt"),
              (.profile + "-status-*.txt"),
              (.profile + "-clear-*.txt")
            ]
          }
        ]
      },
      {
        id: "impairment-campaign-summary",
        phase: "impairment-campaign",
        required: true,
        destination: ($impairmentArtifacts + "/campaign-summary/"),
        producer: ($impairmentPlan + "/summarize-campaign.sh"),
        expected: [
          "impairment-summary.json",
          "impairment-summary.jsonl",
          "impairment-summary.md"
        ]
      },
      {
        id: "promotion-readiness",
        phase: "promotion",
        required: true,
        destination: "benchmark/build/benchmark-baselines/ and benchmark/build/benchmark-results/baseline-readiness-*",
        producer: $promoteScript,
        expected: [
          "baseline-manifest.json",
          "impairment-baseline-manifest.json",
          "readiness.json",
          "readiness.md"
        ]
      }
    ]
  }' >"$artifact_collection_json"

{
  echo "# RakNet Lab Artifact Collection"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Handoff manifest: \`$handoff_manifest\`"
  echo "- Artifact root: \`$artifact_root\`"
  echo "- Perfect-network artifacts: \`$perfect_artifacts\`"
  echo "- Impairment artifacts: \`$impairment_artifacts\`"
  echo "- Prereq roles: \`$(IFS=,; echo "${prereq_roles[*]}")\`"
  echo "- Profiles: \`$profiles\`"
  echo
  echo "Use this checklist while copying remote lab artifacts back to the merge/control host. The JSON file next to this document is the machine-readable contract validated by \`check-lab-handoff.sh\`."
  echo
  echo "## Required Groups"
  echo
  echo "| ID | Phase | Destination | Producer |"
  echo "| --- | --- | --- | --- |"
  jq -r '.collectionGroups[] | "| `\(.id)` | `\(.phase)` | `\(.destination)` | `\(.producer)` |"' "$artifact_collection_json"
  echo
  echo "## Impairment Profiles"
  echo
  echo "| Profile | Artifact root | Netem evidence |"
  echo "| --- | --- | --- |"
  jq -r '.collectionGroups[] | select(.id == "impairment-netem-evidence") | .profiles[] | "| `\(.profile)` | `\(.netemEvidenceDir | sub("/netem$"; ""))` | `\(.netemEvidenceDir)` |"' "$artifact_collection_json"
  echo
  echo "Promotion must wait until the perfect-network combined artifacts and impairment campaign summary exist."
} >"$artifact_collection_md"

cat >"$prereq_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-$repo_root}"
ARTIFACT_ROOT="\${ARTIFACT_ROOT:-$perfect_artifacts}"
INTERFACE="\${INTERFACE:-$interface}"
HOST_ROLE="\${HOST_ROLE:-}"
OUT="\${OUT:-}"
PRINT_COMMAND=false
VALID_ROLES=($prereq_role_flags)
TARGET_HOST_ROLE="$target_host_role"
SUDO_NETEM="$sudo_netem"
REQUIRE_CPU_PERFORMANCE="$require_cpu_performance"

usage() {
  cat <<'USAGE'
Usage:
  HOST_ROLE=<role> ./prereq-commands.sh [options]

Runs the strict host prerequisite check with the same MTU, CPU, clock-sync, and
no-netem gates required by this handoff.

Options:
  --role ROLE          Host role. Overrides HOST_ROLE.
  --interface NIC      Lab interface. Overrides INTERFACE.
  --artifact-root DIR  Artifact root for prereq output. Overrides ARTIFACT_ROOT.
  --out DIR            Exact output directory for prereq.json and prereq.md.
  --print-command      Print the resolved command instead of executing it.
  --list-roles         Print valid roles for this handoff.
  --help               Show this help.
USAGE
}

print_command() {
  printf '+'
  for arg in "\$@"; do
    printf ' %q' "\$arg"
  done
  printf '\n'
}

role_is_valid() {
  local role="\$1"
  local valid
  for valid in "\${VALID_ROLES[@]}"; do
    if [[ "\$valid" == "\$role" ]]; then
      return 0
    fi
  done
  return 1
}

while [[ \$# -gt 0 ]]; do
  case "\$1" in
    --role)
      HOST_ROLE="\$2"
      shift 2
      ;;
    --interface)
      INTERFACE="\$2"
      shift 2
      ;;
    --artifact-root)
      ARTIFACT_ROOT="\$2"
      shift 2
      ;;
    --out)
      OUT="\$2"
      shift 2
      ;;
    --print-command)
      PRINT_COMMAND=true
      shift
      ;;
    --list-roles)
      printf '%s\n' "\${VALID_ROLES[@]}"
      exit 0
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: \$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "\$HOST_ROLE" ]]; then
  echo "HOST_ROLE or --role is required. Valid roles: \${VALID_ROLES[*]}" >&2
  exit 2
fi
if ! role_is_valid "\$HOST_ROLE"; then
  echo "Invalid HOST_ROLE: \$HOST_ROLE. Valid roles: \${VALID_ROLES[*]}" >&2
  exit 2
fi
if [[ -z "\$OUT" ]]; then
  OUT="\$ARTIFACT_ROOT/prereq-\$HOST_ROLE-\$(hostname)"
fi

cmd=(
  benchmark/scripts/check-lab-host-prereqs.sh
  --interface "\$INTERFACE"
  --out "\$OUT"
  --host-role "\$HOST_ROLE"
  --expect-mtu "$expect_mtu"
  --expect-min-cpus "$expect_min_cpus"
  --require-clock-sync
  --require-no-netem
)
if [[ "\$REQUIRE_CPU_PERFORMANCE" == "true" ]]; then
  cmd+=(--require-cpu-performance)
fi
if [[ "\$SUDO_NETEM" == "true" && "\$HOST_ROLE" == "\$TARGET_HOST_ROLE" ]]; then
  cmd+=(--require-sudo-netem)
fi

cd "\$REPO_ROOT"
if "\$PRINT_COMMAND"; then
  print_command "\${cmd[@]}"
else
  exec "\${cmd[@]}"
fi
EOF
chmod +x "$prereq_script"

cat >"$promote_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

# Run this after perfect-plan/merge-all.sh and impairment-plan/summarize-campaign.sh
# have completed and the resulting artifacts have been copied back to this host.
cd "$repo_root"

BASELINE_ROOT="\${BASELINE_ROOT:-benchmark/build/benchmark-baselines}"
PERFECT_BASELINE_NAME="\${PERFECT_BASELINE_NAME:-lab-$timestamp}"
IMPAIRMENT_BASELINE_NAME="\${IMPAIRMENT_BASELINE_NAME:-lab-impairment-$timestamp}"
READINESS_OUT="\${READINESS_OUT:-benchmark/build/benchmark-results/baseline-readiness-$timestamp}"
PREFLIGHT_OUT="\${PREFLIGHT_OUT:-$output_root/pre-promotion-preflight}"

benchmark/scripts/check-lab-handoff.sh \\
  --handoff "$output_root" \\
  --out "\$PREFLIGHT_OUT"$preflight_flags

benchmark/scripts/promote-lab-baseline.sh \\
  --input "$perfect_artifacts/combined" \\
  --handoff-manifest "$handoff_manifest" \\
  --manifest "$perfect_plan/curve-plan/manifest.jsonl" \\
  --manifest "$perfect_plan/curve-raised-plan/manifest.jsonl" \\
  --manifest "$perfect_plan/contention-plan/manifest.jsonl" \\
  --out "\$BASELINE_ROOT" \\
  --name "\$PERFECT_BASELINE_NAME" \\
  -- \\
  --min-iterations "$iterations" \\
  --min-healthy-fairness 0.95 \\
  --max-healthy-send-deliver-ratio 1.2 \\
  --max-affected-send-deliver-ratio 5 \\
  --min-contention-clients "$contention_client_total" \\
  --min-contention-target-client-mbps "$per_client_mbps"

benchmark/scripts/promote-lab-impairment.sh \\
  --input "$impairment_artifacts/campaign-summary" \\
  --out "\$BASELINE_ROOT" \\
  --name "\$IMPAIRMENT_BASELINE_NAME"

benchmark/scripts/check-baseline-readiness.sh \\
  --handoff "$output_root" \\
  --lab-baseline "\$BASELINE_ROOT/\$PERFECT_BASELINE_NAME" \\
  --impairment-baseline "\$BASELINE_ROOT/\$IMPAIRMENT_BASELINE_NAME" \\
  --required-min-contention-clients "$contention_client_total" \\
  --required-min-contention-target-client-mbps "$per_client_mbps" \\
  --required-batch-intervals-ms "$batch_intervals" \\
  --required-resource-pack-chunk-sizes "$resource_pack_chunk_sizes" \\
  --required-resource-pack-intervals-ms "$resource_pack_interval" \\
  --required-disappearance-modes "blackhole" \\
  --out "\$READINESS_OUT"

echo "Perfect baseline: \$BASELINE_ROOT/\$PERFECT_BASELINE_NAME"
echo "Impairment baseline: \$BASELINE_ROOT/\$IMPAIRMENT_BASELINE_NAME"
echo "Readiness report: \$READINESS_OUT"
EOF
chmod +x "$promote_script"

cat >"$readme" <<EOF
# RakNet Lab Baseline Handoff

- Generated: \`$timestamp\`
- Server host: \`$server_host\`
- Bind host: \`$bind_host\`
- Port: \`$port\`
- Interface: \`$interface\`
- Artifact root: \`$artifact_root\`
- Perfect-network plan: \`$perfect_plan\`
- Impairment campaign plan: \`$impairment_plan\`
- Handoff manifest: \`$handoff_manifest\`
- Artifact collection JSON: \`$artifact_collection_json\`
- Artifact collection checklist: \`$artifact_collection_md\`
- Prereq helper: \`$prereq_script\`
- Promotion/readiness helper: \`$promote_script\`
- Production evidence document: \`$production_evidence_doc_rel\`
- Production evidence SHA-256: \`$production_evidence_sha256\`
- Production source audit: \`$(if [[ -n "$source_audit_path" ]]; then echo "$source_audit_path"; else echo "not provided"; fi)\`
- Production source audit SHA-256: \`$(if [[ -n "$source_audit_sha256" ]]; then echo "$source_audit_sha256"; else echo "not provided"; fi)\`
- Profiles: \`$profiles\`
- Impairment target host role: \`$target_host_role\`
- Curve receivers: \`$(IFS=,; echo "${curve_receivers[*]}")\`
- Curve payload sizes: \`$curve_payload_sizes\`
- Curve rates Mbps: \`$curve_rates_mbps\`
- Contention receivers: \`$(IFS=,; echo "${contention_receivers[*]}")\`
- Contention clients: \`$contention_client_total\`
- Contention cases: \`$contention_cases\`
- Reliability: \`$reliability\`
- Per-client Mbps: \`$per_client_mbps\`
- Immediate payload size: \`$immediate_payload_size\`
- Immediate per-client Mbps: \`$immediate_per_client_mbps\`
- Batch intervals: \`$batch_intervals\`
- Batch payload sizes: \`$batch_payload_sizes\`
- Logical packets per batch: \`$logical_packets_per_batch\`
- Batch groups: \`$batch_groups\`
- Resource-pack chunk sizes: \`$resource_pack_chunk_sizes\`
- Resource-pack interval: \`$resource_pack_interval\`
- Raised packet limits: \`$raised_packet_limit/$raised_global_packet_limit\`
- Max queued bytes: \`$max_queued_bytes\`
- Expected MTU: \`$expect_mtu\`
- Expected minimum CPUs: \`$expect_min_cpus\`
- Require CPU performance governor: \`$require_cpu_performance\`
- Prereq roles: \`$(IFS=,; echo "${prereq_roles[*]}")\`

This handoff packages the current recommended established RakNet baseline plan.
It does not run the benchmark. Review the generated commands, run the freshness
checks shortly before execution, then follow each generated plan README.

## Run Order

1. On the merge/control host, run \`benchmark/scripts/check-lab-handoff.sh --handoff "$output_root"$preflight_flags\`.
2. Run \`perfect-plan/check-plan-freshness.sh\` shortly before execution.
3. Keep \`artifact-collection.md\` open as the copy-back checklist. Its sibling \`artifact-collection.json\` is the machine-readable collection contract checked by the handoff preflight.
4. On each server and receiver host, run \`prereq-commands.sh\` with the correct role, for example \`HOST_ROLE=server "$prereq_script"\` or \`HOST_ROLE=$target_host_role "$prereq_script"\`. Valid roles for this handoff are \`$(IFS=,; echo "${prereq_roles[*]}")\`. The helper runs \`benchmark/scripts/check-lab-host-prereqs.sh\`, writes strict \`prereq.json\` and \`prereq.md\` reports under \`$perfect_artifacts\` by default using \`$strict_prereq_flags\`, and adds \`--require-sudo-netem\` automatically for \`$target_host_role\` when sudo netem is enabled.
5. Fill \`perfect-plan/topology-template.md\` as \`$perfect_artifacts/topology.md\`.
6. Run \`perfect-plan/host-capture-commands.sh\` on the server and each receiver host with the correct \`HOST_ROLE\`.
7. Run the perfect-network curve, raised-curve, and contention worker commands from \`perfect-plan/README.md\`.
8. Copy receiver artifacts, prereq reports, and host captures back under \`$perfect_artifacts\`.
9. Run \`perfect-plan/merge-all.sh\` from the repository root.
10. Run \`impairment-plan/check-plan-freshness.sh\`.
11. Run each impairment profile from \`impairment-plan/README.md\`, including the generated netem apply/status/clear scripts on the shaped host or namespace.
12. Copy every profile's receiver artifacts, prereq reports, and \`netem/\` evidence back under \`$impairment_artifacts\`. For per-profile prereq checks, rerun \`prereq-commands.sh\` with \`ARTIFACT_ROOT\` set to the profile artifact root before validation.
13. Run \`impairment-plan/validate-all.sh\`, then \`impairment-plan/summarize-campaign.sh\`.
14. Run \`promote-and-check.sh\` to promote the perfect-network and impairment baselines with this handoff's manifests, then run the final readiness gate.

## Optional TeamZiax VM/eBPF Companion Evidence

If packet captures were taken during the lab campaign and the private
\`teamziax/bedrock-ebpf-filter\` repository is available, replay those captures
through its VM harness after the RakNet baseline artifacts are complete. Store
the output under \`$artifact_root/companion/teamziax-ebpf/\` and reference it
from topology notes. This companion evidence validates eBPF packet-filter and
capture-replay behavior; it does not replace the active established RakNet
worker results used for baseline promotion. See
\`benchmark/docs/teamziax-vm-bench.md\` for the artifact layout and replay
commands.

## Promote Baselines

After the merge and impairment summary are complete, run the generated helper
from the repository root. Override \`BASELINE_ROOT\`, \`PERFECT_BASELINE_NAME\`,
\`IMPAIRMENT_BASELINE_NAME\`, or \`READINESS_OUT\` if the lab package needs
site-specific names:

\`\`\`bash
"$promote_script"
\`\`\`

The helper runs the equivalent commands below with this handoff's exact paths:

\`\`\`bash
benchmark/scripts/promote-lab-baseline.sh \\
  --input "$perfect_artifacts/combined" \\
  --handoff-manifest "$handoff_manifest" \\
  --manifest "$perfect_plan/curve-plan/manifest.jsonl" \\
  --manifest "$perfect_plan/curve-raised-plan/manifest.jsonl" \\
  --manifest "$perfect_plan/contention-plan/manifest.jsonl" \\
  --out benchmark/build/benchmark-baselines \\
  --name lab-<date>-<topology> \\
  -- \\
  --min-iterations "$iterations" \\
  --min-healthy-fairness 0.95 \\
  --max-healthy-send-deliver-ratio 1.2 \\
  --max-affected-send-deliver-ratio 5 \\
  --min-contention-clients "$contention_client_total" \\
  --min-contention-target-client-mbps "$per_client_mbps"

benchmark/scripts/promote-lab-impairment.sh \\
  --input "$impairment_artifacts/campaign-summary" \\
  --out benchmark/build/benchmark-baselines \\
  --name lab-impairment-<date>-<topology>
\`\`\`

## Readiness Gate

\`\`\`bash
benchmark/scripts/check-baseline-readiness.sh \\
  --handoff "$output_root" \\
  --lab-baseline benchmark/build/benchmark-baselines/lab-<date>-<topology> \\
  --impairment-baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \\
  --required-min-contention-clients "$contention_client_total" \\
  --required-min-contention-target-client-mbps "$per_client_mbps" \\
  --required-batch-intervals-ms "$batch_intervals" \\
  --required-resource-pack-chunk-sizes "$resource_pack_chunk_sizes" \\
  --required-resource-pack-intervals-ms "$resource_pack_interval" \\
  --required-disappearance-modes "blackhole" \\
  --out benchmark/build/benchmark-results/baseline-readiness
\`\`\`

The baseline is not accepted as the comparison baseline until the readiness gate
passes. Local loopback and single-host namespace runs are useful development
signals, but they do not replace this separate-host handoff.
EOF

echo "Lab handoff: $output_root"
echo "Perfect-network plan: $perfect_plan"
echo "Impairment campaign plan: $impairment_plan"
echo "Handoff manifest: $handoff_manifest"
echo "Artifact collection JSON: $artifact_collection_json"
echo "Artifact collection checklist: $artifact_collection_md"
echo "Prereq helper: $prereq_script"
echo "Promotion/readiness helper: $promote_script"
echo "README: $readme"
