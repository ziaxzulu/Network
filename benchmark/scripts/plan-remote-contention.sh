#!/usr/bin/env bash
set -euo pipefail

output_root=""
artifact_root="benchmark/build/benchmark-results/remote-contention"
server_host="<server-ip>"
bind_host="0.0.0.0"
port="19132"
clients="100"
clients_set=false
receivers=()
cases="fanout,fairness,disappear-blackhole"
payload_size="512"
per_client_mbps="5"
warmup="10s"
duration="60s"
iterations="3"
start_delay="90s"
start_offset="120s"
case_spacing=""
packet_limit=""
global_packet_limit=""
max_queued_bytes=""
workers=""
reliability="reliable_ordered"
case_prefix="remote-contention"
impaired_clients="10%"
disappearing_clients="10%"
disappear_after="30s"
impairment_latency="100ms"
impairment_jitter="10ms"
impairment_loss="5"
common_args=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/plan-remote-contention.sh [options]

Options:
  --out DIR                         Plan output directory. Default: benchmark/build/benchmark-results/remote-contention-plan-<timestamp>.
  --artifact-root DIR               Artifact root used in generated commands. Default: benchmark/build/benchmark-results/remote-contention.
  --server-host HOST                Server host used by receiver commands. Default: <server-ip>.
  --bind-host HOST                  Server bind host. Default: 0.0.0.0.
  --server-bind-host HOST           Alias for --bind-host.
  --port PORT                       UDP port. Default: 19132.
  --clients N                       Total clients when no --receiver is supplied. Default: 100.
  --receiver NAME:CLIENTS           Receiver worker and client count. NAME=CLIENTS is also accepted. May be repeated.
  --cases CSV                       Cases: fanout,fairness,disappear-close,disappear-stopread,disappear-blackhole.
                                    Default: fanout,fairness,disappear-blackhole.
  --payload-size N                  Payload size. Default: 512.
  --per-client-mbps N               Per-client offered rate. Default: 5.
  --warmup DURATION                 Warmup per case. Default: 10s.
  --duration DURATION               Measurement duration per case. Default: 60s.
  --iterations N                    Iterations per case. Default: 3.
  --start-delay DURATION            Server connection wait before coordinated start. Default: 90s.
  --start-offset DURATION           First case start offset from planning time. Default: 120s.
  --case-spacing DURATION           Gap between scheduled case starts. Default: start-delay + warmup + duration + 30s.
  --impaired-clients N|PCT          Affected clients for fairness. Default: 10%.
  --disappearing-clients N|PCT      Affected clients for disappearance cases. Default: 10%.
  --disappear-after DURATION        Disappearance trigger inside measurement. Default: 30s.
  --impairment-latency DURATION     Benchmark-managed fairness latency. Default: 100ms.
  --impairment-jitter DURATION      Benchmark-managed fairness jitter. Default: 10ms.
  --impairment-loss N               Benchmark-managed fairness loss percent. Default: 5.
  --packet-limit N                  Optional RakNet packet limit override.
  --global-packet-limit N           Optional RakNet global packet limit override.
  --max-queued-bytes N              Optional per-session RAK_MAX_QUEUED_BYTES override.
  --workers N                       Optional benchmark worker count.
  --reliability MODE                Reliability mode. Default: reliable_ordered.
  --case-prefix NAME                Prefix for generated case names. Default: remote-contention.
  --case NAME                       Alias for --case-prefix.
  --common-args "..."               Extra benchmark args appended to every worker command.
  --help                            Show this help.

Outputs:
  server-commands.sh                Run on the server host.
  receiver-<name>-commands.sh       Run on each receiver host.
  merge-commands.sh                 Run after receiver artifacts are copied back.
  manifest.jsonl                    Case schedule, affected-client distribution, start timestamps.
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
    --cases)
      cases="$2"
      shift 2
      ;;
    --payload-size)
      payload_size="$2"
      shift 2
      ;;
    --per-client-mbps)
      per_client_mbps="$2"
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
  output_root="$repo_root/benchmark/build/benchmark-results/remote-contention-plan-$timestamp"
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

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

parse_count() {
  local value="$1"
  local total="$2"
  if [[ "$value" =~ ^([0-9]+)%$ ]]; then
    local pct="${BASH_REMATCH[1]}"
    local count=$(((total * pct + 99) / 100))
    if [[ "$count" -gt "$total" ]]; then
      count="$total"
    fi
    echo "$count"
  elif [[ "$value" =~ ^[0-9]+$ ]]; then
    if [[ "$value" -gt "$total" ]]; then
      echo "-- affected client count exceeds total clients: $value > $total" >&2
      exit 2
    fi
    echo "$value"
  else
    echo "client counts must be an integer or integer percent: $value" >&2
    exit 2
  fi
}

canonical_case() {
  case "${1,,}" in
    fanout|multi-client-fanout)
      echo "fanout"
      ;;
    fairness)
      echo "fairness"
      ;;
    disappear-close|disappearing-close|close)
      echo "disappear-close"
      ;;
    disappear-stopread|disappear-stop-reading|disappearing-stopread|disappearing-stop-reading|stopread|stop-reading)
      echo "disappear-stopread"
      ;;
    disappear-blackhole|disappearing-blackhole|blackhole)
      echo "disappear-blackhole"
      ;;
    *)
      echo "Unknown case: $1" >&2
      exit 2
      ;;
  esac
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
if ! positive_int "$payload_size"; then
  echo "--payload-size must be a positive integer" >&2
  exit 2
fi
for value in "$per_client_mbps" "$impairment_loss"; do
  if ! non_negative_number "$value"; then
    echo "rate and loss values must be non-negative numbers: $value" >&2
    exit 2
  fi
done
if [[ -n "$workers" ]] && ! positive_int "$workers"; then
  echo "--workers must be a positive integer" >&2
  exit 2
fi
if [[ -n "$max_queued_bytes" ]] && ! positive_int "$max_queued_bytes"; then
  echo "--max-queued-bytes must be a positive integer" >&2
  exit 2
fi
if ! non_empty_csv "$cases"; then
  echo "--cases must be a non-empty CSV value" >&2
  exit 2
fi

IFS=',' read -r -a case_array_raw <<<"$cases"
case_array=()
for raw_case in "${case_array_raw[@]}"; do
  raw_case="${raw_case//[[:space:]]/}"
  [[ -z "$raw_case" ]] && continue
  case_array+=("$(canonical_case "$raw_case")")
done
if [[ "${#case_array[@]}" -eq 0 ]]; then
  echo "No valid cases selected" >&2
  exit 2
fi

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

impaired_count="$(parse_count "$impaired_clients" "$clients")"
disappearing_count="$(parse_count "$disappearing_clients" "$clients")"

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

common_worker_args="--warmup $warmup --duration $duration --iterations $iterations --reliability $reliability --payload-size $payload_size --per-client-mbps $per_client_mbps"
if [[ -n "$packet_limit" ]]; then
  common_worker_args="$common_worker_args --packet-limit $packet_limit"
fi
if [[ -n "$global_packet_limit" ]]; then
  common_worker_args="$common_worker_args --global-packet-limit $global_packet_limit"
fi
if [[ -n "$max_queued_bytes" ]]; then
  common_worker_args="$common_worker_args --max-queued-bytes $max_queued_bytes"
fi
if [[ -n "$workers" ]]; then
  common_worker_args="$common_worker_args --workers $workers"
fi
if [[ -n "$common_args" ]]; then
  common_worker_args="$common_worker_args $common_args"
fi

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

distributed_counts() {
  local total="$1"
  local remaining="$total"
  local counts=()
  for count in "${receiver_clients[@]}"; do
    local assigned=0
    if [[ "$remaining" -gt 0 ]]; then
      if [[ "$remaining" -gt "$count" ]]; then
        assigned="$count"
      else
        assigned="$remaining"
      fi
      remaining=$((remaining - assigned))
    fi
    counts+=("$assigned")
  done
  printf '%s\n' "${counts[@]}"
}

mapfile -t impaired_distribution < <(distributed_counts "$impaired_count")
mapfile -t disappearing_distribution < <(distributed_counts "$disappearing_count")

case_index=0
for selected_case in "${case_array[@]}"; do
  benchmark_name=""
  run_suffix=""
  server_case_args=""
  receiver_case_args=()
  affected_total=0
  affected_kind="none"

  case "$selected_case" in
    fanout)
      benchmark_name="multi-client-fanout"
      run_suffix="fanout-${clients}x${per_client_mbps//./_}"
      affected_total=0
      affected_kind="none"
      ;;
    fairness)
      benchmark_name="fairness"
      run_suffix="fairness-${clients}-${impaired_count}poor"
      server_case_args="--impaired-clients $impaired_count --impairment-latency $impairment_latency --impairment-jitter $impairment_jitter --impairment-loss $impairment_loss"
      affected_total="$impaired_count"
      affected_kind="impaired"
      ;;
    disappear-close)
      benchmark_name="disappearing-clients"
      run_suffix="disappear-${clients}-${disappearing_count}-close"
      server_case_args="--disappearing-clients $disappearing_count --disappear-after $disappear_after --disappear-mode close"
      affected_total="$disappearing_count"
      affected_kind="disappearing-close"
      ;;
    disappear-stopread)
      benchmark_name="disappearing-clients"
      run_suffix="disappear-${clients}-${disappearing_count}-stopread"
      server_case_args="--disappearing-clients $disappearing_count --disappear-after $disappear_after --disappear-mode stop-reading"
      affected_total="$disappearing_count"
      affected_kind="disappearing-stopread"
      ;;
    disappear-blackhole)
      benchmark_name="disappearing-clients"
      run_suffix="disappear-${clients}-${disappearing_count}-blackhole"
      server_case_args="--disappearing-clients $disappearing_count --disappear-after $disappear_after --disappear-mode blackhole"
      affected_total="$disappearing_count"
      affected_kind="disappearing-blackhole"
      ;;
  esac

  run_id="$case_prefix-$run_suffix"
  case_name="$run_id"
  start_at=$((base_start_at + (case_index * case_spacing_ms)))

  server_args="server-worker --role server --bind-host $bind_host --port $port --clients $clients --start-delay $start_delay --start-at-epoch-ms $start_at $common_worker_args $server_case_args --out \$SERVER_OUT --run-id $run_id"
  echo >>"$server_script"
  echo "echo '==> server $run_id start_at=$start_at'" >>"$server_script"
  echo "\"\$GRADLE\" :benchmark:raknetBenchmark -PbenchmarkArgs=\"$server_args\"" >>"$server_script"

  receiver_paths=()
  affected_json=""
  for i in "${!receiver_names[@]}"; do
    receiver="${receiver_names[$i]}"
    count="${receiver_clients[$i]}"
    receiver_script="$output_root/receiver-${receiver}-commands.sh"
    receiver_run_id="$run_id-$receiver"
    extra_receiver_args=""
    assigned=0
    if [[ "$selected_case" == "fairness" ]]; then
      assigned="${impaired_distribution[$i]}"
      extra_receiver_args="--impaired-clients $assigned --impairment-latency $impairment_latency --impairment-jitter $impairment_jitter --impairment-loss $impairment_loss"
    elif [[ "$selected_case" == disappear-* ]]; then
      assigned="${disappearing_distribution[$i]}"
      mode="${selected_case#disappear-}"
      if [[ "$mode" == "stopread" ]]; then
        mode="stop-reading"
      fi
      extra_receiver_args="--disappearing-clients $assigned --disappear-after $disappear_after --disappear-mode $mode"
    fi
    if [[ -n "$affected_json" ]]; then
      affected_json="$affected_json,"
    fi
    affected_json="$affected_json{\"receiver\":\"$(json_escape "$receiver")\",\"clients\":$count,\"affected\":$assigned}"

    receiver_args="receiver-worker --role client --host \$SERVER_HOST --port $port --clients $count --start-at-epoch-ms $start_at $common_worker_args $extra_receiver_args --out \$RECEIVER_OUT --run-id $receiver_run_id"
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
  echo "benchmark/scripts/merge-worker-results.sh --server \"\$SERVER_OUT/$run_id\" ${merge_args[*]} --out \"\$MERGED_OUT/$run_id\" --case \"$case_name\" --benchmark-name \"$benchmark_name\"" >>"$merge_script"
  echo "cat \"\$MERGED_OUT/$run_id/suite-aggregate.jsonl\" >>\"\$MERGED_OUT/suite-aggregate.jsonl\"" >>"$merge_script"

  printf '{"case":"%s","benchmarkName":"%s","runId":"%s","clients":%s,"payloadSize":%s,"perClientMbps":%s,"configuredMaxQueuedBytes":%s,"affectedKind":"%s","affectedClients":%s,"startAtEpochMillis":%s,"receiverDistribution":[%s],"serverArtifact":"%s","mergedArtifact":"%s"}\n' \
    "$(json_escape "$case_name")" \
    "$(json_escape "$benchmark_name")" \
    "$(json_escape "$run_id")" \
    "$clients" \
    "$payload_size" \
    "$per_client_mbps" \
    "${max_queued_bytes:-null}" \
    "$(json_escape "$affected_kind")" \
    "$affected_total" \
    "$start_at" \
    "$affected_json" \
    "$(json_escape "$artifact_root/server/$run_id")" \
    "$(json_escape "$artifact_root/merged/$run_id")" >>"$manifest"
  case_index=$((case_index + 1))
done

chmod +x "$server_script" "$merge_script"

{
  echo "# Remote Worker Contention Plan"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Artifact root: \`$artifact_root\`"
  echo "- Server host for receiver scripts: \`$server_host\`"
  echo "- Clients: \`$clients\`"
  echo "- Receivers: \`$(IFS=,; echo "${receivers[*]}")\`"
  echo "- Cases: \`$(IFS=,; echo "${case_array[*]}")\`"
  echo "- Payload size: \`$payload_size\`"
  echo "- Per-client Mbps: \`$per_client_mbps\`"
  echo "- Impaired clients: \`$impaired_count\` from \`$impaired_clients\`"
  echo "- Disappearing clients: \`$disappearing_count\` from \`$disappearing_clients\`"
  echo "- Warmup: \`$warmup\`"
  echo "- Duration: \`$duration\`"
  echo "- Iterations: \`$iterations\`"
  echo "- Max queued bytes cap: \`${max_queued_bytes:-library default}\`"
  echo "- Start offset: \`$start_offset\`"
  echo "- Case spacing: \`$case_spacing\`"
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
  echo "5. Run \`merge-commands.sh\` to create merged per-case artifacts and a campaign-level \`suite-aggregate.jsonl\`."
  echo
  echo "Affected clients are assigned to receiver scripts in receiver order. For host/NIC-level impairment, place the affected receiver clients on the host or namespace where external impairment is applied."
  echo
  echo "Disappearance cases repeat one worker process across all configured iterations. For close or blackhole modes, later iterations measure the post-disappearance state rather than reconnecting fresh clients."
} >"$readme"

echo "Remote worker contention plan: $output_root"
echo "Server commands: $server_script"
for receiver in "${receiver_names[@]}"; do
  echo "Receiver commands ($receiver): $output_root/receiver-$receiver-commands.sh"
done
echo "Merge commands: $merge_script"
echo "Manifest: $manifest"
echo "README: $readme"
