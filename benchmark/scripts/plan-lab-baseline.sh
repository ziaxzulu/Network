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
curve_payload_sizes="1200,1340"
curve_rates_mbps="100,250,500,750,1000,1500,2000,unlimited"
contention_cases="fanout,fairness,disappear-blackhole"
contention_payload_size="512"
per_client_mbps="5"
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
workers=""
reliability="reliable_ordered"
common_args=""
max_p99_ms="20"
max_queue_bytes="1048576"
max_send_deliver_ratio="1.2"
max_nack_out_s="0"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/plan-lab-baseline.sh [options]

Generates a coordinated lab baseline plan:
  - remote 1-client bandwidth-latency curve plan
  - remote multi-client fanout/fairness/disappearance contention plan
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
  --curve-payload-sizes CSV         Payload sizes for bandwidth curve. Default: 1200,1340.
  --curve-rates-mbps CSV            Offered Mbps points for bandwidth curve. Default: 100,250,500,750,1000,1500,2000,unlimited.
  --contention-cases CSV            Contention cases. Default: fanout,fairness,disappear-blackhole.
  --contention-payload-size N       Payload size for contention cases. Default: 512.
  --per-client-mbps N               Contention per-client offered rate. Default: 5.
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
  --workers N                       Optional benchmark worker count.
  --reliability MODE                Reliability mode. Default: reliable_ordered.
  --common-args "..."               Extra benchmark args appended to every worker command.
  --max-p99-ms N                    Stable bandwidth selector p99 gate. Default: 20.
  --max-queue-bytes N               Stable bandwidth selector queue gate. Default: 1048576.
  --max-send-deliver-ratio N        Stable bandwidth selector send/deliver gate. Default: 1.2.
  --max-nack-out-s N                Stable bandwidth selector NACK/s gate. Default: 0, disabled.
  --help                            Show this help.

Outputs:
  curve-plan/                      Plan from plan-remote-worker-curve.sh.
  contention-plan/                 Plan from plan-remote-contention.sh.
  host-capture-commands.sh         Read-only host capture helper.
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
for value in "$curve_payload_sizes" "$curve_rates_mbps" "$contention_cases"; do
  if ! non_empty_csv "$value"; then
    echo "CSV options must be non-empty and cannot start or end with a comma: $value" >&2
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
if [[ -z "$contention_start_offset" ]]; then
  contention_start_offset="$((start_offset_ms + (curve_case_count * case_spacing_ms) + 60000))ms"
fi

mkdir -p "$output_root"

curve_plan="$output_root/curve-plan"
contention_plan="$output_root/contention-plan"
host_capture_script="$output_root/host-capture-commands.sh"
merge_all_script="$output_root/merge-all.sh"
topology_template="$output_root/topology-template.md"
readme="$output_root/README.md"

curve_artifact_root="$artifact_root/curve"
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
if [[ -n "$workers" ]]; then
  curve_cmd+=(--workers "$workers")
fi
if [[ -n "$common_args" ]]; then
  curve_cmd+=(--common-args "$common_args")
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
if [[ -n "$workers" ]]; then
  contention_cmd+=(--workers "$workers")
fi
if [[ -n "$common_args" ]]; then
  contention_cmd+=(--common-args "$common_args")
fi

"${curve_cmd[@]}"
"${contention_cmd[@]}"

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
ARTIFACT_ROOT="\$ARTIFACT_ROOT/contention" "$contention_plan/merge-commands.sh"

mkdir -p "\$COMBINED_OUT"
rm -f "\$COMBINED_OUT/suite-aggregate.jsonl"
for aggregate in "\$ARTIFACT_ROOT/curve/merged/suite-aggregate.jsonl" "\$ARTIFACT_ROOT/contention/merged/suite-aggregate.jsonl"; do
  if [[ ! -s "\$aggregate" ]]; then
    echo "Missing aggregate: \$aggregate" >&2
    exit 1
  fi
  cat "\$aggregate" >>"\$COMBINED_OUT/suite-aggregate.jsonl"
done

for file in bandwidth-capacity.jsonl bandwidth-capacity.csv bandwidth-capacity.md; do
  if [[ -s "\$ARTIFACT_ROOT/curve/merged/\$file" ]]; then
    cp "\$ARTIFACT_ROOT/curve/merged/\$file" "\$COMBINED_OUT/\$file"
  fi
done

cat >"\$COMBINED_OUT/README.md" <<'REPORT'
# Combined Lab Baseline

This directory combines the remote bandwidth-curve and remote contention campaign aggregates.

- suite-aggregate.jsonl contains all comparable baseline rows.
- bandwidth-capacity.* files are copied from the curve campaign when present.
- Keep the sibling curve/, contention/, and host report directories with this combined output.
REPORT

echo "Combined suite aggregate: \$COMBINED_OUT/suite-aggregate.jsonl"
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
EOF

{
  echo "# Lab Baseline Plan"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Artifact root: \`$artifact_root\`"
  echo "- Curve plan: \`$curve_plan\`"
  echo "- Contention plan: \`$contention_plan\`"
  echo "- Combined merge: \`$merge_all_script\`"
  echo "- Host capture: \`$host_capture_script\`"
  echo "- Topology template: \`$topology_template\`"
  echo
  echo "## Run Order"
  echo
  echo "1. Copy or fill \`topology-template.md\` as \`topology.md\` next to the final artifacts."
  echo "2. Run \`host-capture-commands.sh\` on the server and each receiver host with \`HOST_ROLE\` set, for example \`HOST_ROLE=server INTERFACE=$interface ./host-capture-commands.sh\`."
  echo "3. Run the curve receiver scripts, then \`curve-plan/server-commands.sh\` on the server host."
  echo "4. Copy curve receiver artifacts back under \`$curve_artifact_root\` on the merge host."
  echo "5. Run the contention receiver scripts, then \`contention-plan/server-commands.sh\` on the server host."
  echo "6. Copy contention receiver artifacts back under \`$contention_artifact_root\` on the merge host."
  echo "7. Run \`merge-all.sh\` to produce \`combined/suite-aggregate.jsonl\` and curve \`bandwidth-capacity.*\` selector files."
  echo
  echo "The generated start times are non-overlapping by default. Regenerate this plan shortly before lab execution if the scheduled timestamps have passed."
  echo
  echo "For exact healthy/affected server splits in multi-host contention runs, keep impaired or disappearing clients isolated to one receiver host. Otherwise server-side affected splits are accept-order based and advisory."
} >"$readme"

chmod +x "$host_capture_script" "$merge_all_script"

echo "Lab baseline plan: $output_root"
echo "Curve plan: $curve_plan"
echo "Contention plan: $contention_plan"
echo "Host capture commands: $host_capture_script"
echo "Merge all: $merge_all_script"
echo "Topology template: $topology_template"
echo "README: $readme"
