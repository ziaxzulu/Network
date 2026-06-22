#!/usr/bin/env bash
set -euo pipefail

output_root=""
artifact_root="benchmark/build/benchmark-results/lab-baseline"
server_host="<server-ip>"
bind_host="0.0.0.0"
port="19132"
interface="<nic>"
curve_receivers=("receiver-a:1")
contention_receivers=()
contention_clients="100"
case_prefix="lab-baseline"
curve_payload_sizes="64,256,512,1200,1340,1400,262144"
curve_rates_mbps="100,250,500,750,1000,1500,2000,unlimited"
contention_cases="fanout,immediate,fairness,disappear-blackhole,batched,resource-pack"
contention_payload_size="512"
per_client_mbps="5"
immediate_payload_size="256"
immediate_per_client_mbps="1"
batch_intervals="10ms,20ms,50ms"
batch_payload_sizes="128,512,1200"
logical_packets_per_batch="8"
batch_groups="4"
resource_pack_chunk_sizes="8192,262144"
resource_pack_interval="200ms"
impaired_clients="10%"
disappearing_clients="10%"
disappear_after="30s"
impairment_latency="100ms"
impairment_jitter="10ms"
impairment_loss="5"
warmup="10s"
duration="60s"
iterations="3"
start_delay="90s"
start_offset="120s"
contention_start_offset=""
case_spacing=""
packet_limit=""
global_packet_limit=""
max_queued_bytes=""
raised_packet_limit=""
raised_global_packet_limit=""
workers=""
reliability="reliable_ordered"
common_args=""
max_p99_ms="20"
max_queue_bytes="1048576"
max_send_deliver_ratio="1.2"
max_nack_out_s="0"
min_healthy_fairness="0.95"
max_healthy_send_deliver_ratio="1.2"
max_affected_send_deliver_ratio="5"
max_contention_p99_ms="0"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/plan-lab-baseline.sh [options]

Generates a coordinated lab baseline plan:
  - remote 1-client bandwidth-latency curve plan
  - remote multi-client fanout/fairness/disappearance/batched/resource-pack workload plan
  - host-capture commands
  - combined merge script and topology template

Options:
  --out DIR                         Plan output directory. Default: benchmark/build/benchmark-results/lab-baseline-plan-<timestamp>.
  --artifact-root DIR               Artifact root used in generated commands. Default: benchmark/build/benchmark-results/lab-baseline.
  --server-host HOST                Server host used by receiver commands. Default: <server-ip>.
  --bind-host HOST                  Server bind host. Default: 0.0.0.0.
  --server-bind-host HOST           Alias for --bind-host.
  --port PORT                       UDP port. Default: 19132.
  --interface NIC                   NIC name used in generated host-capture commands. Default: <nic>.
  --curve-receiver NAME:CLIENTS     Receiver for best-case curve. NAME=CLIENTS is also accepted. May be repeated. Default: receiver-a:1.
  --contention-receiver NAME:CLIENTS Receiver for contention campaign. NAME=CLIENTS is also accepted. May be repeated.
  --receiver NAME:CLIENTS           Alias for --contention-receiver.
  --contention-clients N            Total contention clients when no contention receiver is supplied. Default: 100.
  --case-prefix NAME                Prefix for generated case names. Default: lab-baseline.
  --case NAME                       Alias for --case-prefix.
  --curve-payload-sizes CSV         Payload sizes for bandwidth curve. Default: 64,256,512,1200,1340,1400,262144.
  --curve-rates-mbps CSV            Offered Mbps points for bandwidth curve. Default: 100,250,500,750,1000,1500,2000,unlimited.
  --contention-cases CSV            Contention cases. Default: fanout,immediate,fairness,disappear-blackhole,batched,resource-pack.
  --contention-payload-size N       Payload size for contention cases. Default: 512.
  --per-client-mbps N               Contention per-client offered rate. Default: 5.
  --immediate-payload-size N        Payload size for immediate small-packet fanout. Default: 256.
  --immediate-per-client-mbps N     Per-client offered rate for immediate small-packet fanout. Default: 1.
  --batch-intervals CSV             Batch intervals for batched cases. Default: 10ms,20ms,50ms.
  --batch-payload-sizes CSV         Batch payload sizes for batched cases. Default: 128,512,1200.
  --logical-packets-per-batch N     Logical packets encoded into each batch. Default: 8.
  --batch-groups N                  Payload variant groups for batched cases. Default: 4.
  --resource-pack-chunk-sizes CSV   Resource-pack chunk sizes. Default: 8192,262144.
  --resource-pack-interval DURATION Resource-pack chunk interval. Default: 200ms.
  --impaired-clients N|PCT          Affected clients for fairness. Default: 10%.
  --disappearing-clients N|PCT      Affected clients for disappearance cases. Default: 10%.
  --disappear-after DURATION        Disappearance trigger inside measurement. Default: 30s.
  --impairment-latency DURATION     Benchmark-managed fairness latency. Default: 100ms.
  --impairment-jitter DURATION      Benchmark-managed fairness jitter. Default: 10ms.
  --impairment-loss N               Benchmark-managed fairness loss percent. Default: 5.
  --warmup DURATION                 Warmup per case. Default: 10s.
  --duration DURATION               Measurement duration per case. Default: 60s.
  --iterations N                    Measured iterations. Default: 3.
  --start-delay DURATION            Server connection wait before coordinated start. Default: 90s.
  --start-offset DURATION           First curve case start offset from planning time. Default: 120s.
  --contention-start-offset DURATION First contention case start offset. Default: after curve cases finish plus 60s.
  --case-spacing DURATION           Gap between scheduled case starts. Default: planner defaults.
  --packet-limit N                  Optional RakNet packet limit override.
  --global-packet-limit N           Optional RakNet global packet limit override.
  --max-queued-bytes N              Optional per-session RAK_MAX_QUEUED_BYTES override.
  --raised-packet-limit N           Add a second raised-limiter curve campaign with this packet limit.
  --raised-global-packet-limit N    Raised-limiter curve global packet limit.
  --workers N                       Optional benchmark worker count.
  --reliability MODE                Reliability mode. Default: reliable_ordered.
  --common-args "..."               Extra benchmark args appended to every worker command.
  --max-p99-ms N                    Stable bandwidth selector p99 gate. Default: 20.
  --max-queue-bytes N               Stable bandwidth selector queue gate. Default: 1048576.
  --max-send-deliver-ratio N        Stable bandwidth selector send/deliver gate. Default: 1.2.
  --max-nack-out-s N                Stable bandwidth selector NACK/s gate. Default: 0, disabled.
  --min-healthy-fairness N          Lab validation healthy-client fairness gate. Default: 0.95.
  --max-healthy-send-deliver-ratio N Lab validation healthy-client send/deliver gate. Default: 1.2.
  --max-affected-send-deliver-ratio N Lab validation affected-client send/deliver gate. Default: 5.
  --max-contention-p99-ms N         Lab validation contention p99 gate. Default: 0, disabled.
  --help                            Show this help.

Outputs:
  curve-plan/                      Plan from plan-remote-worker-curve.sh.
  contention-plan/                 Plan from plan-remote-contention.sh.
  host-capture-commands.sh         Read-only host capture helper.
  check-plan-freshness.sh          Fails when scheduled start times are expired or too close.
  merge-all.sh                     Runs both merge scripts and writes combined/suite-aggregate.jsonl.
  topology-template.md             Baseline metadata template.
  README.md                        Run order and acceptance notes.
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
      if [[ "${curve_receivers[*]}" == "receiver-a:1" ]]; then
        curve_receivers=()
      fi
      curve_receivers+=("$2")
      shift 2
      ;;
    --contention-receiver|--receiver)
      contention_receivers+=("$2")
      shift 2
      ;;
    --contention-clients)
      contention_clients="$2"
      shift 2
      ;;
    --case-prefix|--case)
      case_prefix="$2"
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
    --contention-cases)
      contention_cases="$2"
      shift 2
      ;;
    --contention-payload-size)
      contention_payload_size="$2"
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
    --impaired-clients)
      impaired_clients="$2"
      shift 2
      ;;
    --disappearing-clients)
      disappearing_clients="$2"
      shift 2
      ;;
    --disappear-after)
      disappear_after="$2"
      shift 2
      ;;
    --impairment-latency|--impaired-latency)
      impairment_latency="$2"
      shift 2
      ;;
    --impairment-jitter|--impaired-jitter)
      impairment_jitter="$2"
      shift 2
      ;;
    --impairment-loss|--impaired-loss)
      impairment_loss="$2"
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
    --contention-start-offset)
      contention_start_offset="$2"
      shift 2
      ;;
    --case-spacing)
      case_spacing="$2"
      shift 2
      ;;
    --packet-limit)
      packet_limit="$2"
      shift 2
      ;;
    --global-packet-limit)
      global_packet_limit="$2"
      shift 2
      ;;
    --max-queued-bytes)
      max_queued_bytes="$2"
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
    --workers)
      workers="$2"
      shift 2
      ;;
    --reliability)
      reliability="$2"
      shift 2
      ;;
    --common-args)
      common_args="$2"
      shift 2
      ;;
    --max-p99-ms)
      max_p99_ms="$2"
      shift 2
      ;;
    --max-queue-bytes)
      max_queue_bytes="$2"
      shift 2
      ;;
    --max-send-deliver-ratio)
      max_send_deliver_ratio="$2"
      shift 2
      ;;
    --max-nack-out-s)
      max_nack_out_s="$2"
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

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/lab-baseline-plan-$timestamp"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi

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

positive_int() {
  [[ "$1" =~ ^[0-9]+$ && "$1" -gt 0 ]]
}

non_negative_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

non_empty_csv() {
  [[ -n "$1" && "$1" != *, && "$1" != ,* ]]
}

csv_count() {
  local value="${1//[[:space:]]/}"
  if [[ -z "$value" ]]; then
    echo "0"
    return
  fi
  local old_ifs="$IFS"
  IFS=',' read -r -a parts <<<"$value"
  IFS="$old_ifs"
  local count=0
  for part in "${parts[@]}"; do
    [[ -n "$part" ]] && count=$((count + 1))
  done
  echo "$count"
}

normalize_receiver() {
  local receiver="$1"
  receiver="${receiver/=:/:}"
  receiver="${receiver/=/:}"
  if [[ "$receiver" != *:* ]]; then
    echo "receiver must be NAME:CLIENTS or NAME=CLIENTS: $1" >&2
    exit 2
  fi
  local count="${receiver##*:}"
  if ! positive_int "$count"; then
    echo "receiver client count must be a positive integer: $1" >&2
    exit 2
  fi
  printf '%s' "$receiver"
}

if ! positive_int "$port"; then
  echo "--port must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$contention_clients"; then
  echo "--contention-clients must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$iterations"; then
  echo "--iterations must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$contention_payload_size"; then
  echo "--contention-payload-size must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$immediate_payload_size"; then
  echo "--immediate-payload-size must be a positive integer" >&2
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
resource_pack_interval_ms="$(duration_millis "$resource_pack_interval")"
if [[ "$resource_pack_interval_ms" -le 0 ]]; then
  echo "--resource-pack-interval must be greater than zero" >&2
  exit 2
fi
if [[ -n "$raised_packet_limit" ]] && ! positive_int "$raised_packet_limit"; then
  echo "--raised-packet-limit must be a positive integer" >&2
  exit 2
fi
if [[ -n "$raised_global_packet_limit" ]] && ! positive_int "$raised_global_packet_limit"; then
  echo "--raised-global-packet-limit must be a positive integer" >&2
  exit 2
fi
if [[ -n "$max_queued_bytes" ]] && ! positive_int "$max_queued_bytes"; then
  echo "--max-queued-bytes must be a positive integer" >&2
  exit 2
fi
if [[ -n "$raised_packet_limit" && -z "$raised_global_packet_limit" ]] || [[ -z "$raised_packet_limit" && -n "$raised_global_packet_limit" ]]; then
  echo "--raised-packet-limit and --raised-global-packet-limit must be supplied together" >&2
  exit 2
fi
for value_name in per_client_mbps immediate_per_client_mbps max_p99_ms max_queue_bytes max_send_deliver_ratio max_nack_out_s min_healthy_fairness max_healthy_send_deliver_ratio max_affected_send_deliver_ratio max_contention_p99_ms; do
  if ! non_negative_number "${!value_name}"; then
    echo "--${value_name//_/-} must be a non-negative number: ${!value_name}" >&2
    exit 2
  fi
done
for value in "$curve_payload_sizes" "$curve_rates_mbps" "$contention_cases" "$batch_intervals" "$batch_payload_sizes" "$resource_pack_chunk_sizes"; do
  if ! non_empty_csv "$value"; then
    echo "CSV options must be non-empty and cannot start or end with a comma: $value" >&2
    exit 2
  fi
done

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

normalized_curve_receivers=()
for receiver in "${curve_receivers[@]}"; do
  normalized_curve_receivers+=("$(normalize_receiver "$receiver")")
done
curve_receivers=("${normalized_curve_receivers[@]}")

if [[ "${#contention_receivers[@]}" -eq 0 ]]; then
  contention_receivers=("receiver-a:$contention_clients")
fi
normalized_contention_receivers=()
for receiver in "${contention_receivers[@]}"; do
  normalized_contention_receivers+=("$(normalize_receiver "$receiver")")
done
contention_receivers=("${normalized_contention_receivers[@]}")

sum_receiver_clients() {
  local total=0
  local receiver
  for receiver in "$@"; do
    total=$((total + ${receiver##*:}))
  done
  echo "$total"
}

validation_min_contention_clients="$(sum_receiver_clients "${contention_receivers[@]}")"

start_offset_ms="$(duration_millis "$start_offset")"
start_delay_ms="$(duration_millis "$start_delay")"
warmup_ms="$(duration_millis "$warmup")"
duration_ms="$(duration_millis "$duration")"
if [[ -n "$case_spacing" ]]; then
  case_spacing_ms="$(duration_millis "$case_spacing")"
else
  case_spacing_ms=$((start_delay_ms + warmup_ms + duration_ms + 30000))
fi
if [[ "$case_spacing_ms" -lt $((start_delay_ms + warmup_ms + duration_ms)) ]]; then
  echo "--case-spacing should be at least start-delay + warmup + duration" >&2
  exit 2
fi

curve_case_count=$(( $(csv_count "$curve_payload_sizes") * $(csv_count "$curve_rates_mbps") ))
if [[ "$curve_case_count" -le 0 ]]; then
  echo "curve case count must be positive" >&2
  exit 2
fi
curve_campaign_count=1
if [[ -n "$raised_packet_limit" ]]; then
  curve_campaign_count=2
fi
if [[ -z "$contention_start_offset" ]]; then
  contention_start_offset="$((start_offset_ms + (curve_campaign_count * curve_case_count * case_spacing_ms) + 60000))ms"
fi

mkdir -p "$output_root"

curve_plan="$output_root/curve-plan"
raised_curve_plan="$output_root/curve-raised-plan"
contention_plan="$output_root/contention-plan"
host_capture_script="$output_root/host-capture-commands.sh"
freshness_script="$output_root/check-plan-freshness.sh"
merge_all_script="$output_root/merge-all.sh"
topology_template="$output_root/topology-template.md"
readme="$output_root/README.md"

curve_artifact_root="$artifact_root/curve"
raised_curve_artifact_root="$artifact_root/curve-raised"
contention_artifact_root="$artifact_root/contention"

curve_cmd=(
  "$script_dir/plan-remote-worker-curve.sh"
  --out "$curve_plan"
  --artifact-root "$curve_artifact_root"
  --case "$case_prefix-curve"
  --server-host "$server_host"
  --server-bind-host "$bind_host"
  --port "$port"
  --payload-sizes "$curve_payload_sizes"
  --rates-mbps "$curve_rates_mbps"
  --warmup "$warmup"
  --duration "$duration"
  --iterations "$iterations"
  --start-delay "$start_delay"
  --start-offset "$start_offset"
  --reliability "$reliability"
  --max-p99-ms "$max_p99_ms"
  --max-queue-bytes "$max_queue_bytes"
  --max-send-deliver-ratio "$max_send_deliver_ratio"
  --max-nack-out-s "$max_nack_out_s"
  --selector-min-iterations "$iterations"
)
for receiver in "${curve_receivers[@]}"; do
  curve_cmd+=(--receiver "$receiver")
done
if [[ -n "$case_spacing" ]]; then
  curve_cmd+=(--case-spacing "$case_spacing")
fi
if [[ -n "$packet_limit" ]]; then
  curve_cmd+=(--packet-limit "$packet_limit")
fi
if [[ -n "$global_packet_limit" ]]; then
  curve_cmd+=(--global-packet-limit "$global_packet_limit")
fi
if [[ -n "$max_queued_bytes" ]]; then
  curve_cmd+=(--max-queued-bytes "$max_queued_bytes")
fi
if [[ -n "$workers" ]]; then
  curve_cmd+=(--workers "$workers")
fi
if [[ -n "$common_args" ]]; then
  curve_cmd+=(--common-args "$common_args")
fi

if [[ -n "$raised_packet_limit" ]]; then
  raised_curve_start_offset="$((start_offset_ms + (curve_case_count * case_spacing_ms) + 60000))ms"
  raised_curve_cmd=(
    "$script_dir/plan-remote-worker-curve.sh"
    --out "$raised_curve_plan"
    --artifact-root "$raised_curve_artifact_root"
    --case "$case_prefix-curve-raised"
    --server-host "$server_host"
    --server-bind-host "$bind_host"
    --port "$port"
    --payload-sizes "$curve_payload_sizes"
    --rates-mbps "$curve_rates_mbps"
    --warmup "$warmup"
    --duration "$duration"
    --iterations "$iterations"
    --start-delay "$start_delay"
    --start-offset "$raised_curve_start_offset"
    --reliability "$reliability"
    --max-p99-ms "$max_p99_ms"
    --max-queue-bytes "$max_queue_bytes"
    --max-send-deliver-ratio "$max_send_deliver_ratio"
    --max-nack-out-s "$max_nack_out_s"
    --selector-min-iterations "$iterations"
    --packet-limit "$raised_packet_limit"
    --global-packet-limit "$raised_global_packet_limit"
  )
  if [[ -n "$max_queued_bytes" ]]; then
    raised_curve_cmd+=(--max-queued-bytes "$max_queued_bytes")
  fi
  for receiver in "${curve_receivers[@]}"; do
    raised_curve_cmd+=(--receiver "$receiver")
  done
  if [[ -n "$case_spacing" ]]; then
    raised_curve_cmd+=(--case-spacing "$case_spacing")
  fi
  if [[ -n "$workers" ]]; then
    raised_curve_cmd+=(--workers "$workers")
  fi
  if [[ -n "$common_args" ]]; then
    raised_curve_cmd+=(--common-args "$common_args")
  fi
fi

contention_cmd=(
  "$script_dir/plan-remote-contention.sh"
  --out "$contention_plan"
  --artifact-root "$contention_artifact_root"
  --case "$case_prefix-contention"
  --server-host "$server_host"
  --server-bind-host "$bind_host"
  --port "$port"
  --cases "$contention_cases"
  --payload-size "$contention_payload_size"
  --per-client-mbps "$per_client_mbps"
  --immediate-payload-size "$immediate_payload_size"
  --immediate-per-client-mbps "$immediate_per_client_mbps"
  --batch-intervals "$batch_intervals"
  --batch-payload-sizes "$batch_payload_sizes"
  --logical-packets-per-batch "$logical_packets_per_batch"
  --batch-groups "$batch_groups"
  --resource-pack-chunk-sizes "$resource_pack_chunk_sizes"
  --resource-pack-interval "$resource_pack_interval"
  --impaired-clients "$impaired_clients"
  --disappearing-clients "$disappearing_clients"
  --disappear-after "$disappear_after"
  --impairment-latency "$impairment_latency"
  --impairment-jitter "$impairment_jitter"
  --impairment-loss "$impairment_loss"
  --warmup "$warmup"
  --duration "$duration"
  --iterations "$iterations"
  --start-delay "$start_delay"
  --start-offset "$contention_start_offset"
  --reliability "$reliability"
)
for receiver in "${contention_receivers[@]}"; do
  contention_cmd+=(--receiver "$receiver")
done
if [[ -n "$case_spacing" ]]; then
  contention_cmd+=(--case-spacing "$case_spacing")
fi
if [[ -n "$packet_limit" ]]; then
  contention_cmd+=(--packet-limit "$packet_limit")
fi
if [[ -n "$global_packet_limit" ]]; then
  contention_cmd+=(--global-packet-limit "$global_packet_limit")
fi
if [[ -n "$max_queued_bytes" ]]; then
  contention_cmd+=(--max-queued-bytes "$max_queued_bytes")
fi
if [[ -n "$workers" ]]; then
  contention_cmd+=(--workers "$workers")
fi
if [[ -n "$common_args" ]]; then
  contention_cmd+=(--common-args "$common_args")
fi

"${curve_cmd[@]}"
if [[ -n "$raised_packet_limit" ]]; then
  "${raised_curve_cmd[@]}"
fi
"${contention_cmd[@]}"

add_required_scenario() {
  local scenario="$1"
  if [[ ",$required_validation_scenarios," != *",$scenario,"* ]]; then
    required_validation_scenarios="$required_validation_scenarios,$scenario"
  fi
}

required_validation_scenarios="curve"
IFS=',' read -r -a contention_case_array <<<"$contention_cases"
for selected_case in "${contention_case_array[@]}"; do
  selected_case="${selected_case//[[:space:]]/}"
  selected_case="${selected_case,,}"
  case "$selected_case" in
    fanout|multi-client-fanout|immediate|immediate-send|immediate-fanout)
      add_required_scenario "multi-client-fanout"
      ;;
    fairness)
      add_required_scenario "fairness"
      ;;
    disappear-*|disappearing-*|close|blackhole|stopread|stop-reading)
      add_required_scenario "disappearing-clients"
      ;;
    batch|batched|batched-game-traffic)
      add_required_scenario "batched-game-traffic"
      ;;
    resource-pack|resource-pack-transfer|resource)
      add_required_scenario "resource-pack-transfer"
      ;;
  esac
done

if [[ "$artifact_root" == /* ]]; then
  artifact_root_default="$artifact_root"
else
  artifact_root_default="\$REPO_ROOT/$artifact_root"
fi

cat >"$host_capture_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
ARTIFACT_ROOT="\${ARTIFACT_ROOT:-$artifact_root_default}"
INTERFACE="\${INTERFACE:-$interface}"
HOST_ROLE="\${HOST_ROLE:-host}"

cd "\$REPO_ROOT"
benchmark/scripts/capture-lab-host.sh --interface "\$INTERFACE" --out "\$ARTIFACT_ROOT/host-\$HOST_ROLE-\$(hostname)"
EOF

cat >"$merge_all_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
ARTIFACT_ROOT="\${ARTIFACT_ROOT:-$artifact_root_default}"
COMBINED_OUT="\${COMBINED_OUT:-\$ARTIFACT_ROOT/combined}"

cd "\$REPO_ROOT"
ARTIFACT_ROOT="\$ARTIFACT_ROOT/curve" "$curve_plan/merge-commands.sh"
EOF

if [[ -n "$raised_packet_limit" ]]; then
  cat >>"$merge_all_script" <<EOF
ARTIFACT_ROOT="\$ARTIFACT_ROOT/curve-raised" "$raised_curve_plan/merge-commands.sh"
EOF
fi

cat >>"$merge_all_script" <<EOF
ARTIFACT_ROOT="\$ARTIFACT_ROOT/contention" "$contention_plan/merge-commands.sh"

mkdir -p "\$COMBINED_OUT"
rm -f "\$COMBINED_OUT/suite-aggregate.jsonl"
aggregate_inputs=(
  "\$ARTIFACT_ROOT/curve/merged/suite-aggregate.jsonl"
EOF

if [[ -n "$raised_packet_limit" ]]; then
  cat >>"$merge_all_script" <<'EOF'
  "$ARTIFACT_ROOT/curve-raised/merged/suite-aggregate.jsonl"
EOF
fi

cat >>"$merge_all_script" <<'EOF'
  "$ARTIFACT_ROOT/contention/merged/suite-aggregate.jsonl"
)

for aggregate in "${aggregate_inputs[@]}"; do
  if [[ ! -s "$aggregate" ]]; then
    echo "Missing aggregate: $aggregate" >&2
    exit 1
  fi
  cat "$aggregate" >>"$COMBINED_OUT/suite-aggregate.jsonl"
done

capacity_roots=(
  "$ARTIFACT_ROOT/curve/merged"
EOF

if [[ -n "$raised_packet_limit" ]]; then
  cat >>"$merge_all_script" <<'EOF'
  "$ARTIFACT_ROOT/curve-raised/merged"
EOF
fi

cat >>"$merge_all_script" <<'EOF'
)

rm -f "$COMBINED_OUT/bandwidth-capacity.jsonl" "$COMBINED_OUT/bandwidth-capacity.csv" "$COMBINED_OUT/bandwidth-capacity.md"
for capacity_root in "${capacity_roots[@]}"; do
  if [[ -s "$capacity_root/bandwidth-capacity.jsonl" ]]; then
    cat "$capacity_root/bandwidth-capacity.jsonl" >>"$COMBINED_OUT/bandwidth-capacity.jsonl"
  fi
  if [[ -s "$capacity_root/bandwidth-capacity.csv" ]]; then
    if [[ ! -s "$COMBINED_OUT/bandwidth-capacity.csv" ]]; then
      cat "$capacity_root/bandwidth-capacity.csv" >>"$COMBINED_OUT/bandwidth-capacity.csv"
    else
      tail -n +2 "$capacity_root/bandwidth-capacity.csv" >>"$COMBINED_OUT/bandwidth-capacity.csv"
    fi
  fi
done
if [[ -s "$COMBINED_OUT/bandwidth-capacity.jsonl" ]]; then
  {
    echo "# Combined Bandwidth Capacity"
    for capacity_root in "${capacity_roots[@]}"; do
      if [[ -s "$capacity_root/bandwidth-capacity.md" ]]; then
        echo
        echo "## $capacity_root"
        echo
        cat "$capacity_root/bandwidth-capacity.md"
      fi
    done
  } >"$COMBINED_OUT/bandwidth-capacity.md"
fi

cat >"$COMBINED_OUT/README.md" <<'REPORT'
# Combined Lab Baseline

This directory combines the remote bandwidth-curve and remote contention campaign aggregates.

- suite-aggregate.jsonl contains all comparable baseline rows.
- bandwidth-capacity.* files combine capacity selections from each curve campaign.
- Keep the sibling curve/, optional curve-raised/, contention/, and host report directories with this combined output.
REPORT

benchmark/scripts/validate-lab-baseline.sh --input "$ARTIFACT_ROOT" --out "$COMBINED_OUT" __CURVE_MANIFEST_ARG__ __RAISED_MANIFEST_ARG__ __CONTENTION_MANIFEST_ARG__ --min-iterations __MIN_ITERATIONS__ --required-scenarios __REQUIRED_SCENARIOS__ --min-healthy-fairness __MIN_HEALTHY_FAIRNESS__ --max-healthy-send-deliver-ratio __MAX_HEALTHY_SEND_DELIVER_RATIO__ --max-affected-send-deliver-ratio __MAX_AFFECTED_SEND_DELIVER_RATIO__ --max-contention-p99-ms __MAX_CONTENTION_P99_MS__ --min-contention-clients __MIN_CONTENTION_CLIENTS__ --min-contention-target-client-mbps __MIN_CONTENTION_TARGET_CLIENT_MBPS__

echo "Combined suite aggregate: $COMBINED_OUT/suite-aggregate.jsonl"
EOF

sed -i "s#__CURVE_MANIFEST_ARG__#--manifest $curve_plan/manifest.jsonl#g" "$merge_all_script"
if [[ -n "$raised_packet_limit" ]]; then
  sed -i "s#__RAISED_MANIFEST_ARG__#--manifest $raised_curve_plan/manifest.jsonl#g" "$merge_all_script"
else
  sed -i "s#__RAISED_MANIFEST_ARG__##g" "$merge_all_script"
fi
sed -i "s#__CONTENTION_MANIFEST_ARG__#--manifest $contention_plan/manifest.jsonl#g" "$merge_all_script"
sed -i "s#__MIN_ITERATIONS__#$iterations#g" "$merge_all_script"
sed -i "s#__REQUIRED_SCENARIOS__#$required_validation_scenarios#g" "$merge_all_script"
sed -i "s#__MIN_HEALTHY_FAIRNESS__#$min_healthy_fairness#g" "$merge_all_script"
sed -i "s#__MAX_HEALTHY_SEND_DELIVER_RATIO__#$max_healthy_send_deliver_ratio#g" "$merge_all_script"
sed -i "s#__MAX_AFFECTED_SEND_DELIVER_RATIO__#$max_affected_send_deliver_ratio#g" "$merge_all_script"
sed -i "s#__MAX_CONTENTION_P99_MS__#$max_contention_p99_ms#g" "$merge_all_script"
sed -i "s#__MIN_CONTENTION_CLIENTS__#$validation_min_contention_clients#g" "$merge_all_script"
sed -i "s#__MIN_CONTENTION_TARGET_CLIENT_MBPS__#$per_client_mbps#g" "$merge_all_script"

cat >"$freshness_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

MIN_LEAD_SECONDS="\${MIN_LEAD_SECONDS:-60}"

if ! [[ "\$MIN_LEAD_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "MIN_LEAD_SECONDS must be a non-negative integer" >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to check lab plan freshness" >&2
  exit 2
fi

iso_from_ms() {
  local millis="\$1"
  date -u -d "@\$((millis / 1000))" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || printf '%sms' "\$millis"
}

now_seconds="\$(date +%s)"
threshold_ms=\$(((now_seconds + MIN_LEAD_SECONDS) * 1000))
status=0
manifests=(
  "$curve_plan/manifest.jsonl"
EOF

if [[ -n "$raised_packet_limit" ]]; then
  cat >>"$freshness_script" <<EOF
  "$raised_curve_plan/manifest.jsonl"
EOF
fi

cat >>"$freshness_script" <<EOF
  "$contention_plan/manifest.jsonl"
)

echo "# Lab Plan Freshness"
echo "now=\$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "minimum_lead_seconds=\$MIN_LEAD_SECONDS"
echo

for manifest in "\${manifests[@]}"; do
  if [[ ! -s "\$manifest" ]]; then
    echo "missing manifest: \$manifest" >&2
    status=1
    continue
  fi

  count="\$(jq -s 'length' "\$manifest")"
  earliest="\$(jq -s 'map(.startAtEpochMillis // empty) | min // empty' "\$manifest")"
  latest="\$(jq -s 'map(.startAtEpochMillis // empty) | max // empty' "\$manifest")"
  if [[ -z "\$earliest" || -z "\$latest" ]]; then
    echo "stale: \$manifest has no startAtEpochMillis values" >&2
    status=1
    continue
  fi

  echo "manifest=\$manifest"
  echo "cases=\$count"
  echo "earliest_start=\$(iso_from_ms "\$earliest")"
  echo "latest_start=\$(iso_from_ms "\$latest")"
  if [[ "\$earliest" -le "\$threshold_ms" ]]; then
    echo "result=stale-or-too-close"
    echo "reason=earliest start is less than MIN_LEAD_SECONDS from now; regenerate the lab plan before running workers"
    status=1
  else
    echo "result=fresh"
  fi
  echo
done

exit "\$status"
EOF

cat >"$topology_template" <<EOF
# Lab Baseline Topology

- Generated UTC: \`$timestamp\`
- Git revision: \`$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || printf unknown)\`
- Plan directory: \`$output_root\`
- Artifact root: \`$artifact_root\`
- Server host: \`$server_host\`
- Server bind host: \`$bind_host\`
- Port: \`$port\`
- Interface: \`$interface\`
- Reliability: \`$reliability\`
- Warmup: \`$warmup\`
- Duration: \`$duration\`
- Iterations: \`$iterations\`
- Max queued bytes cap: \`${max_queued_bytes:-library default}\`
- Contention clients: \`$validation_min_contention_clients\`
- Contention per-client Mbps: \`$per_client_mbps\`
- Batch intervals: \`$batch_intervals\`
- Batch payload sizes: \`$batch_payload_sizes\`
- Logical packets per batch: \`$logical_packets_per_batch\`
- Batch groups: \`$batch_groups\`
- Resource-pack chunk sizes: \`$resource_pack_chunk_sizes\`
- Resource-pack interval: \`$resource_pack_interval\`

## Hosts

| Role | Hostname/IP | NIC | CPU pinning | JVM | Notes |
| --- | --- | --- | --- | --- | --- |
| server |  | \`$interface\` |  |  |  |
| curve receiver |  | \`$interface\` |  |  |  |
| contention receiver |  | \`$interface\` |  |  |  |

## Network

| Campaign | Impairment | MTU | Routing/NAT | Notes |
| --- | --- | --- | --- | --- |
| curve | perfect network unless externally configured |  |  |  |
| contention fanout | perfect network unless externally configured |  |  |  |
| contention fairness | benchmark-managed impaired clients plus any external profile recorded here |  |  |  |
| contention disappearance | benchmark-managed disappearance plus any external blackhole profile recorded here |  |  |  |

## Operator Notes

- Record CPU governor, IRQ pinning, NIC offloads, queue sizes, and any non-default kernel settings.
- Record whether affected contention clients are isolated to one receiver host or distributed.
- Record exact \`tc qdisc show\` output before and after each externally impaired run.
- Record any reason for changing validation gates from healthy fairness \`$min_healthy_fairness\`, healthy send/deliver \`$max_healthy_send_deliver_ratio\`, affected send/deliver \`$max_affected_send_deliver_ratio\`, and contention p99 \`$max_contention_p99_ms\`.
EOF

{
  echo "# Lab Baseline Plan"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Artifact root: \`$artifact_root\`"
  echo "- Curve plan: \`$curve_plan\`"
  if [[ -n "$raised_packet_limit" ]]; then
    echo "- Raised-limiter curve plan: \`$raised_curve_plan\`"
    echo "- Raised-limiter curve packet limits: \`$raised_packet_limit/$raised_global_packet_limit\`"
  fi
  echo "- Contention plan: \`$contention_plan\`"
  echo "- Contention cases: \`$contention_cases\`"
  echo "- Immediate payload size: \`$immediate_payload_size\`"
  echo "- Immediate per-client Mbps: \`$immediate_per_client_mbps\`"
  echo "- Batch intervals: \`$batch_intervals\`"
  echo "- Batch payload sizes: \`$batch_payload_sizes\`"
  echo "- Logical packets per batch: \`$logical_packets_per_batch\`"
  echo "- Batch groups: \`$batch_groups\`"
  echo "- Resource-pack chunk sizes: \`$resource_pack_chunk_sizes\`"
  echo "- Resource-pack interval: \`$resource_pack_interval\`"
  echo "- Max queued bytes cap: \`${max_queued_bytes:-library default}\`"
  echo "- Freshness check: \`$freshness_script\`"
  echo "- Combined merge: \`$merge_all_script\`"
  echo "- Host capture: \`$host_capture_script\`"
  echo "- Topology template: \`$topology_template\`"
  echo "- Validation scenarios: \`$required_validation_scenarios\`"
  echo "- Validation gates: healthy fairness >= \`$min_healthy_fairness\`; healthy send/deliver <= \`$max_healthy_send_deliver_ratio\`; affected send/deliver <= \`$max_affected_send_deliver_ratio\`; contention p99 <= \`$max_contention_p99_ms\` when non-zero; contention clients >= \`$validation_min_contention_clients\`; contention target/client Mbps >= \`$per_client_mbps\`"
  echo
  echo "## Run Order"
  echo
  echo "1. Run \`check-plan-freshness.sh\` on the merge host. Regenerate the plan if it reports \`stale-or-too-close\`."
  echo "2. Copy or fill \`topology-template.md\` as \`topology.md\` next to the final artifacts."
  echo "3. Run \`host-capture-commands.sh\` on the server and each receiver host with \`HOST_ROLE\` set, for example \`HOST_ROLE=server INTERFACE=$interface ./host-capture-commands.sh\`."
  echo "4. Start \`curve-plan/server-commands.sh\` on the server host, then run the curve receiver scripts once the server is listening."
  echo "5. Copy curve receiver artifacts back under \`$curve_artifact_root\` on the merge host."
  if [[ -n "$raised_packet_limit" ]]; then
    echo "6. Start \`curve-raised-plan/server-commands.sh\` on the server host, then run the raised-limiter curve receiver scripts once the server is listening."
    echo "7. Copy raised-limiter curve receiver artifacts back under \`$raised_curve_artifact_root\` on the merge host."
    echo "8. Start \`contention-plan/server-commands.sh\` on the server host, then run the contention receiver scripts once the server is listening."
    echo "9. Copy contention receiver artifacts back under \`$contention_artifact_root\` on the merge host."
    echo "10. Run \`merge-all.sh\` to produce \`combined/suite-aggregate.jsonl\`, combined curve \`bandwidth-capacity.*\` selector files, and validation reports. Validation expects \`topology.md\` and host reports under the artifact root."
  else
    echo "6. Start \`contention-plan/server-commands.sh\` on the server host, then run the contention receiver scripts once the server is listening."
    echo "7. Copy contention receiver artifacts back under \`$contention_artifact_root\` on the merge host."
    echo "8. Run \`merge-all.sh\` to produce \`combined/suite-aggregate.jsonl\`, curve \`bandwidth-capacity.*\` selector files, and validation reports. Validation expects \`topology.md\` and host reports under the artifact root."
  fi
  echo
  echo "The generated start times are non-overlapping by default. Regenerate this plan shortly before lab execution if \`check-plan-freshness.sh\` fails. Set \`MIN_LEAD_SECONDS\` to require a larger scheduling buffer."
  echo
  echo "For exact healthy/affected server splits in multi-host contention runs, keep impaired or disappearing clients isolated to one receiver host. Otherwise server-side affected splits are accept-order based and advisory."
} >"$readme"

chmod +x "$host_capture_script" "$freshness_script" "$merge_all_script"

echo "Lab baseline plan: $output_root"
echo "Curve plan: $curve_plan"
echo "Contention plan: $contention_plan"
echo "Host capture commands: $host_capture_script"
echo "Freshness check: $freshness_script"
echo "Merge all: $merge_all_script"
echo "Topology template: $topology_template"
echo "README: $readme"
