#!/usr/bin/env bash
set -euo pipefail

output_root=""
artifact_root="benchmark/build/benchmark-results/remote-worker-curve"
server_host="<server-ip>"
bind_host="0.0.0.0"
port="19132"
clients="1"
clients_set=false
receivers=()
payload_sizes="1200"
rates_mbps="100,250,500,750,1000,1500,2000,unlimited"
warmup="5s"
duration="30s"
iterations="3"
start_delay="30s"
start_offset="60s"
case_spacing=""
packet_limit=""
global_packet_limit=""
workers=""
reliability="reliable_ordered"
case_prefix="remote-curve"
common_args=""
max_p99_ms="0"
max_queue_bytes="0"
max_send_deliver_ratio="0"
max_nack_out_s="0"
selector_min_iterations="3"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/plan-remote-worker-curve.sh [options]

Options:
  --out DIR                         Plan output directory. Default: benchmark/build/benchmark-results/remote-worker-curve-plan-<timestamp>.
  --artifact-root DIR               Artifact root used in generated commands. Default: benchmark/build/benchmark-results/remote-worker-curve.
  --server-host HOST                Server host used by receiver commands. Default: <server-ip>.
  --bind-host HOST                  Server bind host. Default: 0.0.0.0.
  --server-bind-host HOST           Alias for --bind-host.
  --port PORT                       UDP port. Default: 19132.
  --clients N                       Total clients when no --receiver is supplied. Default: 1.
  --receiver NAME:CLIENTS           Receiver worker and client count. NAME=CLIENTS is also accepted. May be repeated.
  --payload-sizes CSV               Payload sizes to schedule. Default: 1200.
  --payload-size N                  Alias for --payload-sizes N.
  --rates-mbps CSV                  Rate points to schedule. Default: 100,250,500,750,1000,1500,2000,unlimited.
  --warmup DURATION                 Warmup per case. Default: 5s.
  --duration DURATION               Measurement duration per case. Default: 30s.
  --iterations N                    Iterations per case. Default: 3.
  --start-delay DURATION            Server connection wait before coordinated start. Default: 30s.
  --start-offset DURATION           First case start offset from planning time. Default: 60s.
  --case-spacing DURATION           Gap between scheduled case starts. Default: warmup + duration + 30s.
  --packet-limit N                  Optional RakNet packet limit override.
  --global-packet-limit N           Optional RakNet global packet limit override.
  --workers N                       Optional benchmark worker count.
  --reliability MODE                Reliability mode. Default: reliable_ordered.
  --case-prefix NAME                Prefix for generated case groups. Default: remote-curve.
  --case NAME                       Alias for --case-prefix.
  --common-args "..."               Extra benchmark args appended to every worker command.
  --max-p99-ms N                    Selector gate passed to merge script. Default: 0, disabled.
  --max-queue-bytes N               Selector gate passed to merge script. Default: 0, disabled.
  --max-send-deliver-ratio N        Selector gate passed to merge script. Default: 0, disabled.
  --max-nack-out-s N                Selector gate passed to merge script. Default: 0, disabled.
  --selector-min-iterations N       Selector minimum measured iterations. Default: 3.
  --help                            Show this help.

Outputs:
  server-commands.sh                Run on the server host.
  receiver-<name>-commands.sh       Run on each receiver host.
  merge-commands.sh                 Run after receiver artifacts are copied back.
  manifest.jsonl                    Case schedule, rates, payloads, start timestamps.
  README.md                         Campaign instructions.
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
    --clients)
      clients="$2"
      clients_set=true
      shift 2
      ;;
    --receiver)
      receivers+=("$2")
      shift 2
      ;;
    --payload-sizes|--payload-size)
      payload_sizes="$2"
      shift 2
      ;;
    --rates-mbps)
      rates_mbps="$2"
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
    --case-prefix|--case)
      case_prefix="$2"
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
    --selector-min-iterations)
      selector_min_iterations="$2"
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
  output_root="$repo_root/benchmark/build/benchmark-results/remote-worker-curve-plan-$timestamp"
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

rate_name() {
  local rate="$1"
  if [[ "${rate,,}" == "unlimited" || "$rate" == "0" || "$rate" == "0.0" ]]; then
    echo "unlimited"
  elif [[ "$rate" =~ ^[0-9]+$ ]]; then
    echo "${rate}_0mbps"
  else
    echo "${rate//./_}mbps"
  fi
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

safe_name() {
  local value="${1,,}"
  value="${value//[^a-z0-9_-]/-}"
  value="${value//--/-}"
  value="${value#-}"
  value="${value%-}"
  if [[ -z "$value" ]]; then
    value="worker"
  fi
  printf '%s' "$value"
}

env_name() {
  local value
  value="$(safe_name "$1" | tr '[:lower:]-' '[:upper:]_')"
  printf '%s' "$value"
}

if ! positive_int "$clients"; then
  echo "--clients must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$port"; then
  echo "--port must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$iterations"; then
  echo "--iterations must be a positive integer" >&2
  exit 2
fi
if [[ -n "$workers" ]] && ! positive_int "$workers"; then
  echo "--workers must be a positive integer" >&2
  exit 2
fi
if ! positive_int "$selector_min_iterations"; then
  echo "--selector-min-iterations must be a positive integer" >&2
  exit 2
fi
for value in "$max_p99_ms" "$max_queue_bytes" "$max_send_deliver_ratio" "$max_nack_out_s"; do
  if ! non_negative_number "$value"; then
    echo "selector gate values must be non-negative numbers: $value" >&2
    exit 2
  fi
done
if ! non_empty_csv "$payload_sizes" || ! non_empty_csv "$rates_mbps"; then
  echo "--payload-sizes and --rates-mbps must be non-empty CSV values" >&2
  exit 2
fi

IFS=',' read -r -a payload_array <<<"$payload_sizes"
IFS=',' read -r -a rate_array <<<"$rates_mbps"

for payload in "${payload_array[@]}"; do
  if ! positive_int "$payload"; then
    echo "payload size must be a positive integer: $payload" >&2
    exit 2
  fi
done

if [[ "${#receivers[@]}" -eq 0 ]]; then
  receivers=("receiver-a:$clients")
fi

receiver_names=()
receiver_clients=()
receiver_total=0
for receiver in "${receivers[@]}"; do
  receiver="${receiver/=:/:}"
  receiver="${receiver/=/:}"
  if [[ "$receiver" != *:* ]]; then
    echo "--receiver must be NAME:CLIENTS or NAME=CLIENTS: $receiver" >&2
    exit 2
  fi
  name="$(safe_name "${receiver%%:*}")"
  count="${receiver##*:}"
  if ! positive_int "$count"; then
    echo "receiver client count must be a positive integer: $receiver" >&2
    exit 2
  fi
  receiver_names+=("$name")
  receiver_clients+=("$count")
  receiver_total=$((receiver_total + count))
done

if "$clients_set" && [[ "$receiver_total" -ne "$clients" ]]; then
  echo "--clients ($clients) must equal the sum of --receiver counts ($receiver_total)" >&2
  exit 2
fi
clients="$receiver_total"

start_offset_ms="$(duration_millis "$start_offset")"
start_delay_ms="$(duration_millis "$start_delay")"
warmup_ms="$(duration_millis "$warmup")"
duration_ms="$(duration_millis "$duration")"
if [[ -z "$case_spacing" ]]; then
  case_spacing_ms=$((start_delay_ms + warmup_ms + duration_ms + 30000))
  case_spacing="${case_spacing_ms}ms"
else
  case_spacing_ms="$(duration_millis "$case_spacing")"
fi

if [[ "$case_spacing_ms" -lt $((start_delay_ms + warmup_ms + duration_ms)) ]]; then
  echo "--case-spacing should be at least start-delay + warmup + duration" >&2
  exit 2
fi

mkdir -p "$output_root"
manifest="$output_root/manifest.jsonl"
server_script="$output_root/server-commands.sh"
merge_script="$output_root/merge-commands.sh"
readme="$output_root/README.md"
: >"$manifest"

if [[ "$artifact_root" == /* ]]; then
  artifact_root_default="$artifact_root"
else
  artifact_root_default="\$REPO_ROOT/$artifact_root"
fi
server_out_default="\$ARTIFACT_ROOT/server"
merged_out_default="\$ARTIFACT_ROOT/merged"
base_start_at=$((($(date +%s) * 1000) + start_offset_ms))

common_worker_args="--warmup $warmup --duration $duration --iterations $iterations --reliability $reliability"
if [[ -n "$packet_limit" ]]; then
  common_worker_args="$common_worker_args --packet-limit $packet_limit"
fi
if [[ -n "$global_packet_limit" ]]; then
  common_worker_args="$common_worker_args --global-packet-limit $global_packet_limit"
fi
if [[ -n "$workers" ]]; then
  common_worker_args="$common_worker_args --workers $workers"
fi
if [[ -n "$common_args" ]]; then
  common_worker_args="$common_worker_args $common_args"
fi

selector_args="--min-iterations $selector_min_iterations --max-p99-ms $max_p99_ms --max-queue-bytes $max_queue_bytes --max-send-deliver-ratio $max_send_deliver_ratio --max-nack-out-s $max_nack_out_s"

cat >"$server_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
ARTIFACT_ROOT="\${ARTIFACT_ROOT:-$artifact_root_default}"
SERVER_OUT="\${SERVER_OUT:-$server_out_default}"
GRADLE="\${GRADLE:-./gradlew}"

cd "\$REPO_ROOT"
mkdir -p "\$SERVER_OUT"
EOF

for i in "${!receiver_names[@]}"; do
  receiver="${receiver_names[$i]}"
  var="$(env_name "$receiver")"
  receiver_script="$output_root/receiver-${receiver}-commands.sh"
  cat >"$receiver_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
ARTIFACT_ROOT="\${ARTIFACT_ROOT:-$artifact_root_default}"
RECEIVER_OUT="\${RECEIVER_OUT:-\$ARTIFACT_ROOT/$receiver}"
SERVER_HOST="\${SERVER_HOST:-$server_host}"
GRADLE="\${GRADLE:-./gradlew}"

cd "\$REPO_ROOT"
mkdir -p "\$RECEIVER_OUT"
EOF
  chmod +x "$receiver_script"
done

cat >"$merge_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
ARTIFACT_ROOT="\${ARTIFACT_ROOT:-$artifact_root_default}"
SERVER_OUT="\${SERVER_OUT:-\$ARTIFACT_ROOT/server}"
MERGED_OUT="\${MERGED_OUT:-$merged_out_default}"

cd "\$REPO_ROOT"
mkdir -p "\$MERGED_OUT"
rm -f "\$MERGED_OUT/suite-aggregate.jsonl"
EOF

case_index=0
for payload in "${payload_array[@]}"; do
  case_group="$case_prefix-p${payload}"
  for rate in "${rate_array[@]}"; do
    rate="${rate//[[:space:]]/}"
    if [[ -z "$rate" ]]; then
      continue
    fi
    rate_label="$(rate_name "$rate")"
    benchmark_name="curve-$rate_label"
    run_id="$case_group-$rate_label"
    start_at=$((base_start_at + (case_index * case_spacing_ms)))
    server_args="server-worker --role server --bind-host $bind_host --port $port --clients $clients --start-delay $start_delay --start-at-epoch-ms $start_at $common_worker_args --payload-size $payload --rate-mbps $rate --out \$SERVER_OUT --run-id $run_id"
    echo >>"$server_script"
    echo "echo '==> server $run_id start_at=$start_at'" >>"$server_script"
    echo "\"\$GRADLE\" :benchmark:raknetBenchmark -PbenchmarkArgs=\"$server_args\"" >>"$server_script"

    receiver_paths=()
    for i in "${!receiver_names[@]}"; do
      receiver="${receiver_names[$i]}"
      count="${receiver_clients[$i]}"
      receiver_script="$output_root/receiver-${receiver}-commands.sh"
      receiver_run_id="$run_id-$receiver"
      receiver_args="receiver-worker --role client --host \$SERVER_HOST --port $port --clients $count --start-at-epoch-ms $start_at $common_worker_args --payload-size $payload --rate-mbps $rate --out \$RECEIVER_OUT --run-id $receiver_run_id"
      echo >>"$receiver_script"
      echo "echo '==> receiver $receiver_run_id start_at=$start_at'" >>"$receiver_script"
      echo "\"\$GRADLE\" :benchmark:raknetBenchmark -PbenchmarkArgs=\"$receiver_args\"" >>"$receiver_script"
      receiver_paths+=("\"\$ARTIFACT_ROOT/$receiver/$receiver_run_id\"")
    done

    merge_args=()
    for path in "${receiver_paths[@]}"; do
      merge_args+=("--receiver $path")
    done
    echo >>"$merge_script"
    echo "echo '==> merge $run_id'" >>"$merge_script"
    echo "benchmark/scripts/merge-worker-results.sh --server \"\$SERVER_OUT/$run_id\" ${merge_args[*]} --out \"\$MERGED_OUT/$run_id\" --case \"$case_group\" --benchmark-name \"$benchmark_name\"" >>"$merge_script"
    echo "cat \"\$MERGED_OUT/$run_id/suite-aggregate.jsonl\" >>\"\$MERGED_OUT/suite-aggregate.jsonl\"" >>"$merge_script"

    printf '{"case":"%s","benchmarkName":"%s","runId":"%s","payloadSize":%s,"rateMbps":"%s","clients":%s,"startAtEpochMillis":%s,"serverArtifact":"%s","mergedArtifact":"%s"}\n' \
      "$(json_escape "$case_group")" \
      "$(json_escape "$benchmark_name")" \
      "$(json_escape "$run_id")" \
      "$payload" \
      "$(json_escape "$rate")" \
      "$clients" \
      "$start_at" \
      "$(json_escape "$artifact_root/server/$run_id")" \
      "$(json_escape "$artifact_root/merged/$run_id")" >>"$manifest"
    case_index=$((case_index + 1))
  done
done

cat >>"$merge_script" <<'EOF'

if [[ -s "$MERGED_OUT/suite-aggregate.jsonl" ]]; then
  benchmark/scripts/select-stable-bandwidth.sh --input "$MERGED_OUT/suite-aggregate.jsonl" --out "$MERGED_OUT" __SELECTOR_ARGS__
fi
EOF

sed -i "s#__SELECTOR_ARGS__#$selector_args#g" "$merge_script"

chmod +x "$server_script" "$merge_script"

{
  echo "# Remote Worker Bandwidth Curve Plan"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Artifact root: \`$artifact_root\`"
  echo "- Server host for receiver scripts: \`$server_host\`"
  echo "- Clients: \`$clients\`"
  echo "- Payload sizes: \`$payload_sizes\`"
  echo "- Rates Mbps: \`$rates_mbps\`"
  echo "- Warmup: \`$warmup\`"
  echo "- Duration: \`$duration\`"
  echo "- Iterations: \`$iterations\`"
  echo "- Start offset: \`$start_offset\`"
  echo "- Case spacing: \`$case_spacing\`"
  echo "- Selector gates: \`$selector_args\`"
  echo
  echo "## Files"
  echo
  echo "- Server commands: \`$server_script\`"
  for receiver in "${receiver_names[@]}"; do
    echo "- Receiver commands for \`$receiver\`: \`$output_root/receiver-$receiver-commands.sh\`"
  done
  echo "- Merge commands: \`$merge_script\`"
  echo "- Manifest: \`$manifest\`"
  echo
  echo "## Workflow"
  echo
  echo "1. Generate this plan shortly before the run so scheduled start timestamps are still in the future."
  echo "2. Copy the generated receiver command scripts to receiver hosts so every host uses the same scheduled start timestamps."
  echo "3. Start the server script first, then start all receiver scripts once the server is listening. Hosts should be NTP-synchronized and the scheduled start timestamp should still be in the future."
  echo "4. Copy receiver artifacts back under the same artifact root on the merge host."
  echo "5. Run \`merge-commands.sh\` to create merged per-rate artifacts, a combined \`suite-aggregate.jsonl\`, and stable bandwidth capacity artifacts."
  echo
  echo "The generated merge commands label rows as \`case=$case_prefix-p<payload>\` and \`benchmarkName=curve-<rate>\`, so \`select-stable-bandwidth.sh\` can choose the highest stable delivered remote-worker curve point."
} >"$readme"

echo "Remote worker curve plan: $output_root"
echo "Server commands: $server_script"
for receiver in "${receiver_names[@]}"; do
  echo "Receiver commands ($receiver): $output_root/receiver-$receiver-commands.sh"
done
echo "Merge commands: $merge_script"
echo "Manifest: $manifest"
echo "README: $readme"
