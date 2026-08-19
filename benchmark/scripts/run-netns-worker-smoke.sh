#!/usr/bin/env bash
set -euo pipefail

execute=false
keep_netns=false
output_root=""
case_type="fanout"
clients="20"
clients_set=false
affected_clients=""
healthy_clients=""
payload_size="512"
per_client_mbps="5"
rate_mbps="100"
warmup="5s"
duration="10s"
iterations="3"
probe_interval="100ms"
start_delay="20s"
start_offset="45s"
netem_before_start="5s"
blackhole_after="5s"
blackhole_duration=""
port="19132"
latency="0ms"
jitter="0ms"
loss="0%"
netem_limit="10000"
direction="server-to-client"
packet_limit=""
global_packet_limit=""
max_queued_bytes=""
workers=""
reliability="reliable_ordered"
recovery_mode="legacy"
namespace_prefix=""
benchmark_run_user=""
benchmark_run_group=""
benchmark_run_home=""
benchmark_java_home=""
benchmark_git_revision=""
benchmark_distribution_dir=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/run-netns-worker-smoke.sh [options]
  sudo benchmark/scripts/run-netns-worker-smoke.sh --execute [options]

Runs or prints a single-host Linux network-namespace smoke benchmark. It reuses
normal server-worker, receiver-worker, and merge-worker-results artifacts, but
places workers in separate namespaces connected by veth pairs so tc netem loss,
latency, or blackhole rules are outside the JVM.

Default mode is dry-run. Pass --execute to create namespaces and run workers.

Options:
  --execute                         Create netns/veth pairs and run the benchmark.
  --keep-netns                      Do not delete namespaces during cleanup.
  --out DIR                         Output root. Default: benchmark/build/benchmark-results/netns-worker-smoke-<timestamp>.
  --case fanout|fairness|curve|blackhole
                                    Workload shape. Default: fanout.
  --clients N                       Total clients. Default: 20, or 1 for curve when omitted.
  --affected-clients N|PCT          Affected clients for fairness/blackhole. Default: 10% for those cases.
  --healthy-clients N               Healthy clients for fairness/blackhole. Default: clients - affected.
  --payload-size N                  Payload size. Default: 512.
  --per-client-mbps N               Fanout/fairness/blackhole per-client target. Default: 5.
  --rate-mbps N                     Curve aggregate target. Default: 100.
  --warmup DURATION                 Warmup per worker. Default: 5s.
  --duration DURATION               Measurement duration. Default: 10s.
  --iterations N                    Measured iterations. Default: 3.
  --probe-interval DURATION         Probe cadence and warmup-drain input. Default: 100ms.
  --start-delay DURATION            Server wait for clients. Default: 20s.
  --start-offset DURATION           Coordinated start offset from now. Default: 45s.
  --netem-before-start DURATION     Apply initial netem this far before coordinated start. Default: 5s.
  --blackhole-after DURATION        For --case blackhole, apply 100% loss this far into measurement. Default: 5s.
  --blackhole-duration DURATION     Restore the prior path after this duration. Default: permanent.
  --port PORT                       UDP port. Default: 19132.
  --latency DURATION                Netem latency for affected path. Default: 0ms.
  --jitter DURATION                 Netem jitter for affected path. Default: 0ms.
  --loss PCT                        Netem loss for affected path. Default: 0%.
  --netem-limit N                   Netem queue limit in packets. Default: 10000.
  --direction server-to-client|client-to-server|both
                                    Which direction receives netem. Default: server-to-client.
  --packet-limit N                  Optional RakNet packet limit override.
  --global-packet-limit N           Optional RakNet global packet limit override.
  --max-queued-bytes N              Optional per-session RAK_MAX_QUEUED_BYTES override.
  --workers N                       Optional benchmark worker count.
  --reliability MODE                Reliability mode. Default: reliable_ordered.
  --recovery-mode legacy|bounded    RakNet recovery algorithm. Default: legacy.
  --namespace-prefix NAME           Prefix for created network namespaces.
  --help                            Show this help.

Outputs:
  manifest.json                     Run plan and namespace topology.
  README.md                         Operator notes and command summary.
  server.log / receiver-*.log       Worker output when --execute is used.
  netem/*.txt                       qdisc apply/status evidence with millisecond apply bounds.
  netem/qdisc-timeseries.jsonl      One-second external qdisc counter samples.
  merged/                           merge-worker-results output when --execute is used.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute)
      execute=true
      shift
      ;;
    --keep-netns)
      keep_netns=true
      shift
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --case)
      case_type="$2"
      shift 2
      ;;
    --clients)
      clients="$2"
      clients_set=true
      shift 2
      ;;
    --affected-clients|--impaired-clients)
      affected_clients="$2"
      shift 2
      ;;
    --healthy-clients)
      healthy_clients="$2"
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
    --rate-mbps)
      rate_mbps="$2"
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
    --probe-interval)
      probe_interval="$2"
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
    --netem-before-start)
      netem_before_start="$2"
      shift 2
      ;;
    --blackhole-after)
      blackhole_after="$2"
      shift 2
      ;;
    --blackhole-duration)
      blackhole_duration="$2"
      shift 2
      ;;
    --port)
      port="$2"
      shift 2
      ;;
    --latency)
      latency="$2"
      shift 2
      ;;
    --jitter)
      jitter="$2"
      shift 2
      ;;
    --loss)
      loss="$2"
      shift 2
      ;;
    --netem-limit)
      netem_limit="$2"
      shift 2
      ;;
    --direction)
      direction="$2"
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
    --recovery-mode)
      recovery_mode="$2"
      shift 2
      ;;
    --namespace-prefix)
      namespace_prefix="$2"
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
benchmark_distribution_dir="${BENCHMARK_DISTRIBUTION_DIR:-$repo_root/benchmark/build/install/benchmark}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/netns-worker-smoke-$timestamp"
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

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

resolve_count() {
  local value="$1"
  local total="$2"
  if [[ "$value" =~ ^([0-9]+)%$ ]]; then
    local pct="${BASH_REMATCH[1]}"
    if [[ "$pct" -gt 100 ]]; then
      echo "percentage cannot exceed 100: $value" >&2
      exit 2
    fi
    echo "$(((total * pct + 99) / 100))"
  elif [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "$value"
  else
    echo "client count must be a non-negative integer or whole percent: $value" >&2
    exit 2
  fi
}

has_netem() {
  [[ "$latency" != "0" && "$latency" != "0ms" ]] || [[ "$jitter" != "0" && "$jitter" != "0ms" ]] || [[ "$loss" != "0" && "$loss" != "0%" ]]
}

case_type="${case_type,,}"
direction="${direction,,}"
case "$case_type" in
  curve|fanout|fairness|blackhole)
    ;;
  *)
    echo "--case must be one of: fanout, fairness, curve, blackhole" >&2
    exit 2
    ;;
esac
case "$direction" in
  server-to-client|client-to-server|both)
    ;;
  *)
    echo "--direction must be server-to-client, client-to-server, or both" >&2
    exit 2
    ;;
esac
recovery_mode="${recovery_mode,,}"
case "$recovery_mode" in
  legacy|bounded)
    ;;
  *)
    echo "--recovery-mode must be legacy or bounded" >&2
    exit 2
    ;;
esac

if [[ "$case_type" == "curve" && "$clients_set" == false ]]; then
  clients="1"
fi

for value_name in clients payload_size iterations port; do
  if ! positive_int "${!value_name}"; then
    echo "--${value_name//_/-} must be a positive integer: ${!value_name}" >&2
    exit 2
  fi
done
if ! positive_int "$netem_limit"; then
  echo "--netem-limit must be a positive integer" >&2
  exit 2
fi
for value_name in per_client_mbps rate_mbps; do
  if ! non_negative_number "${!value_name}"; then
    echo "--${value_name//_/-} must be a non-negative number: ${!value_name}" >&2
    exit 2
  fi
done
if [[ -n "$packet_limit" ]] && ! positive_int "$packet_limit"; then
  echo "--packet-limit must be a positive integer" >&2
  exit 2
fi
if [[ -n "$global_packet_limit" ]] && ! positive_int "$global_packet_limit"; then
  echo "--global-packet-limit must be a positive integer" >&2
  exit 2
fi
if [[ -n "$max_queued_bytes" ]] && ! positive_int "$max_queued_bytes"; then
  echo "--max-queued-bytes must be a positive integer" >&2
  exit 2
fi
if [[ -n "$workers" ]] && ! positive_int "$workers"; then
  echo "--workers must be a positive integer" >&2
  exit 2
fi

if [[ "$case_type" == "fairness" || "$case_type" == "blackhole" ]]; then
  if [[ -z "$affected_clients" ]]; then
    affected_clients="10%"
  fi
  affected_clients="$(resolve_count "$affected_clients" "$clients")"
  if [[ -z "$healthy_clients" ]]; then
    healthy_clients="$((clients - affected_clients))"
  fi
else
  affected_clients="${affected_clients:-0}"
  affected_clients="$(resolve_count "$affected_clients" "$clients")"
  healthy_clients="${healthy_clients:-$((clients - affected_clients))}"
fi

if ! [[ "$healthy_clients" =~ ^[0-9]+$ ]]; then
  echo "--healthy-clients must be a non-negative integer" >&2
  exit 2
fi
if [[ "$affected_clients" -gt "$clients" || "$healthy_clients" -gt "$clients" || $((affected_clients + healthy_clients)) -ne "$clients" ]]; then
  echo "healthy + affected clients must exactly equal total clients" >&2
  exit 2
fi
if [[ "$case_type" == "fairness" || "$case_type" == "blackhole" ]]; then
  if [[ "$affected_clients" -le 0 || "$healthy_clients" -le 0 ]]; then
    echo "$case_type requires at least one healthy and one affected client" >&2
    exit 2
  fi
fi

for duration_value in "$warmup" "$duration" "$probe_interval" "$start_delay" "$start_offset" "$netem_before_start" "$blackhole_after"; do
  duration_millis "$duration_value" >/dev/null
done
probe_interval_ms="$(duration_millis "$probe_interval")"
if [[ "$probe_interval_ms" -le 0 ]]; then
  echo "--probe-interval must be positive" >&2
  exit 2
fi
warmup_drain_ms=$((probe_interval_ms * 2))
if [[ "$warmup_drain_ms" -lt 100 ]]; then
  warmup_drain_ms=100
elif [[ "$warmup_drain_ms" -gt 1000 ]]; then
  warmup_drain_ms=1000
fi
if [[ -n "$blackhole_duration" ]]; then
  duration_millis "$blackhole_duration" >/dev/null
  if [[ "$case_type" != "blackhole" ]]; then
    echo "--blackhole-duration requires --case blackhole" >&2
    exit 2
  fi
  if [[ $(( $(duration_millis "$blackhole_after") + $(duration_millis "$blackhole_duration") )) \
      -ge "$(duration_millis "$duration")" ]]; then
    echo "--blackhole-after plus --blackhole-duration must leave a measured recovery window" >&2
    exit 2
  fi
fi
if [[ "$(duration_millis "$netem_before_start")" -ge "$(duration_millis "$start_offset")" ]]; then
  echo "--netem-before-start must be shorter than --start-offset" >&2
  exit 2
fi

if "$execute"; then
  if [[ "$(id -u)" -ne 0 ]]; then
    echo "--execute requires root or equivalent CAP_NET_ADMIN privileges to create namespaces and apply tc netem" >&2
    exit 2
  fi
  benchmark_run_user="${BENCHMARK_RUN_USER:-${SUDO_USER:-}}"
  if [[ -n "$benchmark_run_user" && "$benchmark_run_user" != "root" ]]; then
    if ! id "$benchmark_run_user" >/dev/null 2>&1; then
      echo "Benchmark run user does not exist: $benchmark_run_user" >&2
      exit 2
    fi
    benchmark_run_group="$(id -gn "$benchmark_run_user")"
    benchmark_run_home="$(getent passwd "$benchmark_run_user" | cut -d: -f6)"
    if [[ -z "$benchmark_run_home" || ! -d "$benchmark_run_home" ]]; then
      echo "Benchmark run user has no usable home directory: $benchmark_run_user" >&2
      exit 2
    fi
  fi
  for tool in ip tc jq; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "$tool is required for --execute" >&2
      exit 2
    fi
  done
  if [[ -n "$benchmark_run_user" && "$benchmark_run_user" != "root" ]] && ! command -v runuser >/dev/null 2>&1; then
    echo "runuser is required to execute benchmark workers as $benchmark_run_user" >&2
    exit 2
  fi

  benchmark_java_home="${BENCHMARK_JAVA_HOME:-}"
  if [[ -z "$benchmark_java_home" ]]; then
    for candidate in /usr/lib/jvm/java-26-temurin-jdk /usr/lib/jvm/temurin-26-jdk; do
      if [[ -x "$candidate/bin/java" ]]; then
        benchmark_java_home="$candidate"
        break
      fi
    done
  fi
  if [[ -z "$benchmark_java_home" || ! -x "$benchmark_java_home/bin/java" ]]; then
    echo "Java 26 is required; set BENCHMARK_JAVA_HOME to a Java 26+ installation" >&2
    exit 2
  fi
  benchmark_git_revision="${BENCHMARK_GIT_REVISION:-}"
  if [[ -z "$benchmark_git_revision" ]] && command -v git >/dev/null 2>&1; then
    benchmark_git_revision="$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || true)"
  fi
  benchmark_git_revision="${benchmark_git_revision:-unknown}"
  if [[ "$benchmark_distribution_dir" != /* ]]; then
    echo "BENCHMARK_DISTRIBUTION_DIR must be an absolute path" >&2
    exit 2
  fi
  benchmark_lib_dir="$benchmark_distribution_dir/lib"
  if [[ ! -d "$benchmark_lib_dir" ]] || ! find "$benchmark_lib_dir" -maxdepth 1 -type f -name 'benchmark-*.jar' -print -quit | grep -q .; then
    echo "Benchmark distribution is missing at $benchmark_distribution_dir. Run ./gradlew --no-daemon :benchmark:installDist before --execute" >&2
    exit 2
  fi
fi

mkdir -p "$output_root/netem"
cd "$repo_root"

if [[ -z "$namespace_prefix" ]]; then
  namespace_prefix="rakbench-$$"
fi
safe_prefix="$(printf '%s' "$namespace_prefix" | tr -cd '[:alnum:]' | tail -c 6)"
if [[ -z "$safe_prefix" ]]; then
  safe_prefix="rb$$"
fi
server_ns="$namespace_prefix-srv"
healthy_ns="$namespace_prefix-healthy"
affected_ns="$namespace_prefix-affected"

server_healthy_ip="10.248.0.1"
receiver_healthy_ip="10.248.0.2"
server_affected_ip="10.248.0.5"
receiver_affected_ip="10.248.0.6"
server_healthy_iface="srvh"
receiver_healthy_iface="rcvh"
server_affected_iface="srvi"
receiver_affected_iface="rcvi"
host_server_healthy="rb${safe_prefix}sh"
host_receiver_healthy="rb${safe_prefix}rh"
host_server_affected="rb${safe_prefix}si"
host_receiver_affected="rb${safe_prefix}ri"

run_id="netns-$case_type-${clients}c"
case_name="$run_id"
benchmark_name="multi-client-fanout"
if [[ "$case_type" == "curve" ]]; then
  benchmark_name="bandwidth-latency-curve"
elif [[ "$case_type" == "fairness" ]]; then
  benchmark_name="fairness"
elif [[ "$case_type" == "blackhole" ]]; then
  benchmark_name="disappearing-clients"
fi

server_out="$output_root/server"
healthy_out="$output_root/receiver-healthy"
affected_out="$output_root/receiver-affected"
merged_out="$output_root/merged"
manifest="$output_root/manifest.json"
report="$output_root/README.md"
server_log="$output_root/server.log"
healthy_log="$output_root/receiver-healthy.log"
affected_log="$output_root/receiver-affected.log"

mkdir -p "$server_out" "$healthy_out" "$affected_out" "$merged_out"
if "$execute" && [[ -n "$benchmark_run_user" && "$benchmark_run_user" != "root" ]]; then
  chown "$benchmark_run_user:$benchmark_run_group" \
    "$server_out" "$healthy_out" "$affected_out" "$merged_out"
fi

start_at_ms=$((($(date +%s) * 1000) + $(duration_millis "$start_offset")))
warmup_ms="$(duration_millis "$warmup")"
netem_before_start_ms="$(duration_millis "$netem_before_start")"
netem_at_ms=$((start_at_ms - netem_before_start_ms))
blackhole_after_ms="$(duration_millis "$blackhole_after")"
blackhole_at_ms=$((start_at_ms + warmup_ms + warmup_drain_ms + blackhole_after_ms))
recovery_at_ms=0
if [[ -n "$blackhole_duration" ]]; then
  recovery_at_ms=$((blackhole_at_ms + $(duration_millis "$blackhole_duration")))
fi

common_worker_args=(
  --warmup "$warmup"
  --duration "$duration"
  --iterations "$iterations"
  --probe-interval "$probe_interval"
  --payload-size "$payload_size"
  --reliability "$reliability"
  --recovery-mode "$recovery_mode"
)
if has_netem; then
  common_worker_args+=(--external-impairment-at-epoch-ms "$netem_at_ms")
fi
if [[ "$case_type" == "blackhole" ]]; then
  common_worker_args+=(--external-blackhole-at-epoch-ms "$blackhole_at_ms")
fi
if [[ "$recovery_at_ms" -gt 0 ]]; then
  common_worker_args+=(--external-recovery-at-epoch-ms "$recovery_at_ms")
fi
if [[ -n "$packet_limit" ]]; then
  common_worker_args+=(--packet-limit "$packet_limit")
fi
if [[ -n "$global_packet_limit" ]]; then
  common_worker_args+=(--global-packet-limit "$global_packet_limit")
fi
if [[ -n "$max_queued_bytes" ]]; then
  common_worker_args+=(--max-queued-bytes "$max_queued_bytes")
fi
if [[ -n "$workers" ]]; then
  common_worker_args+=(--workers "$workers")
fi

join_args() {
  if [[ "$#" -eq 0 ]]; then
    return
  fi
  printf '%q' "$1"
  shift
  if [[ "$#" -gt 0 ]]; then
    printf ' %q' "$@"
  fi
}

if [[ "$case_type" == "curve" ]]; then
  rate_args=(--rate-mbps "$rate_mbps")
else
  rate_args=(--per-client-mbps "$per_client_mbps")
fi

server_args_array=(
  server-worker --role server --bind-host 0.0.0.0 --port "$port" --clients "$clients"
  --start-delay "$start_delay" --start-at-epoch-ms "$start_at_ms"
  "${common_worker_args[@]}" "${rate_args[@]}"
  --impaired-clients "$affected_clients" --out "$server_out" --run-id "$run_id"
)
healthy_receiver_args_array=(
  receiver-worker --role client --host "$server_healthy_ip" --port "$port" --clients "$healthy_clients"
  --start-at-epoch-ms "$start_at_ms"
  "${common_worker_args[@]}" "${rate_args[@]}"
  --out "$healthy_out" --run-id "$run_id-healthy"
)
affected_receiver_args_array=(
  receiver-worker --role client --host "$server_affected_ip" --port "$port" --clients "$affected_clients"
  --start-at-epoch-ms "$start_at_ms"
  "${common_worker_args[@]}" "${rate_args[@]}"
  --impaired-clients "$affected_clients" --out "$affected_out" --run-id "$run_id-affected"
)

if [[ "$affected_clients" -eq 0 ]]; then
  healthy_receiver_args_array=(
    receiver-worker --role client --host "$server_healthy_ip" --port "$port" --clients "$healthy_clients"
    --start-at-epoch-ms "$start_at_ms"
    "${common_worker_args[@]}" "${rate_args[@]}"
    --out "$healthy_out" --run-id "$run_id-receiver"
  )
fi

server_args="$(join_args "${server_args_array[@]}")"
healthy_receiver_args="$(join_args "${healthy_receiver_args_array[@]}")"
affected_receiver_args="$(join_args "${affected_receiver_args_array[@]}")"

cat >"$manifest" <<EOF
{
  "kind": "raknet-netns-worker-smoke",
  "generatedAt": "$timestamp",
  "execute": $execute,
  "case": "$(json_escape "$case_type")",
  "benchmarkName": "$(json_escape "$benchmark_name")",
  "runId": "$(json_escape "$run_id")",
  "clients": $clients,
  "healthyClients": $healthy_clients,
  "affectedClients": $affected_clients,
  "payloadSize": $payload_size,
  "perClientMbps": $per_client_mbps,
  "rateMbps": $rate_mbps,
  "latency": "$(json_escape "$latency")",
  "jitter": "$(json_escape "$jitter")",
  "loss": "$(json_escape "$loss")",
  "netemLimitPackets": $netem_limit,
  "direction": "$(json_escape "$direction")",
  "recoveryMode": "$(json_escape "$recovery_mode")",
  "packetLimit": $(if [[ -n "$packet_limit" ]]; then echo "$packet_limit"; else echo "null"; fi),
  "globalPacketLimit": $(if [[ -n "$global_packet_limit" ]]; then echo "$global_packet_limit"; else echo "null"; fi),
  "maxQueuedBytes": $(if [[ -n "$max_queued_bytes" ]]; then echo "$max_queued_bytes"; else echo "null"; fi),
  "workers": $(if [[ -n "$workers" ]]; then echo "$workers"; else echo "null"; fi),
  "benchmarkDistribution": "$(json_escape "$benchmark_distribution_dir")",
  "startAtEpochMillis": $start_at_ms,
  "probeIntervalMillis": $probe_interval_ms,
  "warmupDrainMillis": $warmup_drain_ms,
  "netemAtEpochMillis": $(if has_netem; then echo "$netem_at_ms"; else echo "null"; fi),
  "blackholeAtEpochMillis": $(if [[ "$case_type" == "blackhole" ]]; then echo "$blackhole_at_ms"; else echo "null"; fi),
  "recoveryAtEpochMillis": $(if [[ "$recovery_at_ms" -gt 0 ]]; then echo "$recovery_at_ms"; else echo "null"; fi),
  "namespaces": {
    "server": "$(json_escape "$server_ns")",
    "healthy": "$(json_escape "$healthy_ns")",
    "affected": "$(json_escape "$affected_ns")"
  },
  "serverArgs": "$(json_escape "$server_args")",
  "healthyReceiverArgs": "$(json_escape "$healthy_receiver_args")",
  "affectedReceiverArgs": "$(json_escape "$affected_receiver_args")",
  "outputRoot": "$(json_escape "$output_root")"
}
EOF

cat >"$report" <<EOF
# Netns Worker Smoke

- Generated: \`$timestamp\`
- Execute: \`$execute\`
- Case: \`$case_type\`
- Benchmark name: \`$benchmark_name\`
- Clients: \`$clients\`
- Healthy clients: \`$healthy_clients\`
- Affected clients: \`$affected_clients\`
- Start at epoch ms: \`$start_at_ms\`
- Probe interval: \`$probe_interval_ms ms\`
- Warmup drain: \`$warmup_drain_ms ms\`
- Initial netem at epoch ms: \`$(if has_netem; then echo "$netem_at_ms"; else echo "not scheduled"; fi)\`
- External blackhole at epoch ms: \`$(if [[ "$case_type" == "blackhole" ]]; then echo "$blackhole_at_ms"; else echo "not scheduled"; fi)\`
- External recovery at epoch ms: \`$(if [[ "$recovery_at_ms" -gt 0 ]]; then echo "$recovery_at_ms"; else echo "not scheduled"; fi)\`
- Direction: \`$direction\`
- Recovery mode: \`$recovery_mode\`
- Netem queue limit: \`$netem_limit packets\`
- Benchmark distribution: \`$benchmark_distribution_dir\`
- Netem: latency \`$latency\`, jitter \`$jitter\`, loss \`$loss\`
- Output root: \`$output_root\`

This helper is a single-host smoke harness. It applies qdisc rules outside the
JVM, but it does not prove NIC line-rate. Use promoted separate-host lab runs as
the baseline of record.

## Worker Commands

\`\`\`text
server: $server_args
healthy receiver: $healthy_receiver_args
affected receiver: $affected_receiver_args
\`\`\`

EOF

log_command() {
  printf '+ '
  printf '%q ' "$@"
  printf '\n'
}

run_command() {
  log_command "$@"
  if "$execute"; then
    "$@"
  fi
}

worker_pids=()
helper_pids=()
sampler_pids=()
qdisc_sampler_required=false
qdisc_sampler_targets=()
namespaces_created=()

cleanup() {
  local pid
  for pid in "${worker_pids[@]:-}" "${helper_pids[@]:-}" "${sampler_pids[@]:-}"; do
    [[ -z "$pid" ]] && continue
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
  if "$execute" && ! "$keep_netns"; then
    local ns
    for ns in "${namespaces_created[@]:-}"; do
      ip netns del "$ns" >/dev/null 2>&1 || true
    done
  fi
}
trap cleanup EXIT

create_namespace() {
  local ns="$1"
  run_command ip netns add "$ns"
  namespaces_created+=("$ns")
  run_command ip -n "$ns" link set lo up
}

create_pair() {
  local left_ns="$1"
  local left_host_iface="$2"
  local left_iface="$3"
  local left_cidr="$4"
  local right_ns="$5"
  local right_host_iface="$6"
  local right_iface="$7"
  local right_cidr="$8"

  run_command ip link add "$left_host_iface" type veth peer name "$right_host_iface"
  run_command ip link set "$left_host_iface" netns "$left_ns"
  run_command ip link set "$right_host_iface" netns "$right_ns"
  run_command ip -n "$left_ns" link set "$left_host_iface" name "$left_iface"
  run_command ip -n "$right_ns" link set "$right_host_iface" name "$right_iface"
  run_command ip -n "$left_ns" addr add "$left_cidr" dev "$left_iface"
  run_command ip -n "$right_ns" addr add "$right_cidr" dev "$right_iface"
  run_command ip -n "$left_ns" link set "$left_iface" up
  run_command ip -n "$right_ns" link set "$right_iface" up
}

capture_status() {
  local ns="$1"
  local iface="$2"
  local label="$3"
  local file="$output_root/netem/$label.txt"
  if "$execute"; then
    {
      echo "# Netem Status"
      echo "namespace=$ns"
      echo "interface=$iface"
      echo "utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      ip netns exec "$ns" "$script_dir/raknet-netem.sh" --interface "$iface" --action status
    } >"$file" 2>&1 || true
  else
    echo "Would capture qdisc status for $ns/$iface -> $file"
  fi
}

apply_netem_path() {
  local label="$1"
  local action="$2"
  local target_latency="$latency"
  local target_jitter="$jitter"
  local target_loss="$loss"
  if [[ "$action" == "blackhole" ]]; then
    target_latency="0ms"
    target_jitter="0ms"
    target_loss="100%"
  fi

  local targets=()
  if [[ "$direction" == "server-to-client" || "$direction" == "both" ]]; then
    targets+=("$server_ns:$server_affected_iface")
  fi
  if [[ "$direction" == "client-to-server" || "$direction" == "both" ]]; then
    targets+=("$affected_ns:$receiver_affected_iface")
  fi

  local target
  for target in "${targets[@]}"; do
    local ns="${target%%:*}"
    local iface="${target##*:}"
    local evidence="$output_root/netem/$label-$ns-$iface.txt"
    if "$execute"; then
      {
        echo "# Netem Apply"
        echo "namespace=$ns"
        echo "interface=$iface"
        echo "action=$action"
        echo "latency=$target_latency"
        echo "jitter=$target_jitter"
        echo "loss=$target_loss"
        echo "limit=$netem_limit"
        echo "utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "applyStartedAtEpochMillis=$(date +%s%3N)"
        if [[ "$action" == "restore" ]] && ! has_netem; then
          ip netns exec "$ns" "$script_dir/raknet-netem.sh" --interface "$iface" --action clear
        else
          ip netns exec "$ns" "$script_dir/raknet-netem.sh" --interface "$iface" --action apply --latency "$target_latency" --jitter "$target_jitter" --loss "$target_loss" --limit "$netem_limit"
        fi
        echo "applyCompletedAtEpochMillis=$(date +%s%3N)"
        ip netns exec "$ns" "$script_dir/raknet-netem.sh" --interface "$iface" --action status
      } >"$evidence" 2>&1
      cat "$evidence"
    else
      if [[ "$action" == "restore" ]] && ! has_netem; then
        echo "+ ip netns exec $ns $script_dir/raknet-netem.sh --interface $iface --action clear"
      else
        echo "+ ip netns exec $ns $script_dir/raknet-netem.sh --interface $iface --action apply --latency $target_latency --jitter $target_jitter --loss $target_loss --limit $netem_limit"
      fi
      echo "  evidence: $evidence"
    fi
  done
}

start_qdisc_sampler() {
  if ! "$execute" || { ! has_netem && [[ "$case_type" != "blackhole" ]]; }; then
    return
  fi
  local output="$output_root/netem/qdisc-timeseries.jsonl"
  qdisc_sampler_required=true
  qdisc_sampler_targets=()
  if [[ "$direction" == "server-to-client" || "$direction" == "both" ]]; then
    qdisc_sampler_targets+=("$server_ns:$server_affected_iface")
  fi
  if [[ "$direction" == "client-to-server" || "$direction" == "both" ]]; then
    qdisc_sampler_targets+=("$affected_ns:$receiver_affected_iface")
  fi
  (
    while true; do
      local target ns iface qdisc_json error
      for target in "${qdisc_sampler_targets[@]}"; do
        ns="${target%%:*}"
        iface="${target##*:}"
        error=""
        if ! qdisc_json="$(ip netns exec "$ns" tc -s -j qdisc show dev "$iface" 2>&1)"; then
          error="$qdisc_json"
          qdisc_json='[]'
        elif [[ -z "$qdisc_json" ]]; then
          qdisc_json='[]'
        fi
        jq -cn \
          --argjson epochMillis "$(date +%s%3N)" \
          --arg namespace "$ns" \
          --arg interface "$iface" \
          --arg error "$error" \
          --argjson qdisc "$qdisc_json" \
          '{epochMillis: $epochMillis, namespace: $namespace, interface: $interface,
            qdisc: $qdisc} + (if $error == "" then {} else {error: $error} end)'
      done
      sleep 1
    done
  ) >>"$output" &
  sampler_pids+=("$!")
}

stop_qdisc_samplers() {
  local pid status was_running
  local failed=0
  for pid in "${sampler_pids[@]:-}"; do
    [[ -z "$pid" ]] && continue
    was_running=false
    if kill -0 "$pid" >/dev/null 2>&1; then
      was_running=true
      kill "$pid" >/dev/null 2>&1 || true
    fi
    if wait "$pid" >/dev/null 2>&1; then
      status=0
    else
      status=$?
    fi
    if ! "$was_running"; then
      echo "Qdisc sampler exited unexpectedly before benchmark completion (status $status)" >&2
      failed=1
    elif [[ "$status" -ne 0 && "$status" -ne 143 ]]; then
      echo "Qdisc sampler could not be stopped cleanly (status $status)" >&2
      failed=1
    fi
  done
  sampler_pids=()
  if "$qdisc_sampler_required"; then
    local output="$output_root/netem/qdisc-timeseries.jsonl"
    if ! "$script_dir/validate-qdisc-timeseries.sh" "$output" "${qdisc_sampler_targets[@]}"; then
      failed=1
    fi
  fi
  return "$failed"
}

sleep_until_epoch_ms() {
  local target_ms="$1"
  while true; do
    local now_ms
    now_ms="$(date +%s%3N)"
    if [[ "$now_ms" -ge "$target_ms" ]]; then
      break
    fi
    local remaining_ms=$((target_ms - now_ms))
    if [[ "$remaining_ms" -gt 1000 ]]; then
      sleep 1
    elif [[ "$remaining_ms" -gt 100 ]]; then
      sleep 0.1
    else
      sleep 0.01
    fi
  done
}

run_worker_bg() {
  local ns="$1"
  local log_file="$2"
  shift 2
  local worker_command=(ip netns exec "$ns")
  if "$execute" && [[ -n "$benchmark_run_user" && "$benchmark_run_user" != "root" ]]; then
    worker_command+=(runuser --user "$benchmark_run_user" -- env
      "HOME=$benchmark_run_home"
      "USER=$benchmark_run_user"
      "LOGNAME=$benchmark_run_user"
      "JAVA_HOME=$benchmark_java_home"
      "PATH=$benchmark_java_home/bin:$PATH")
  fi
  if "$execute"; then
    worker_command+=(
      "$benchmark_java_home/bin/java"
      -Xms1g -Xmx1g
      "-Dbenchmark.repoRoot=$repo_root"
      "-Dbenchmark.defaultOutputRoot=$repo_root/benchmark/build/benchmark-results"
      "-Dbenchmark.gitRevision=$benchmark_git_revision"
      -cp "$benchmark_lib_dir/*"
      org.cloudburstmc.netty.benchmark.BenchmarkMain
      "$@"
    )
  else
    local args
    args="$(join_args "$@")"
    worker_command+=(./gradlew --no-daemon :benchmark:raknetBenchmark "-PbenchmarkArgs=$args")
  fi
  log_command "${worker_command[@]}"
  if "$execute"; then
    "${worker_command[@]}" >"$log_file" 2>&1 &
    worker_pids+=("$!")
  fi
}

run_benchmark_command() {
  if "$execute" && [[ -n "$benchmark_run_user" && "$benchmark_run_user" != "root" ]]; then
    local command=(runuser --user "$benchmark_run_user" -- env
      "HOME=$benchmark_run_home"
      "USER=$benchmark_run_user"
      "LOGNAME=$benchmark_run_user"
      "PATH=$PATH"
      "$@")
    log_command "${command[@]}"
    "${command[@]}"
  else
    run_command "$@"
  fi
}

echo "Netns worker smoke output: $output_root"
echo "Manifest: $manifest"
echo "README: $report"
echo

if ! "$execute"; then
  echo "Dry-run only. Re-run with --execute as root/CAP_NET_ADMIN to create namespaces and run workers."
fi

create_namespace "$server_ns"
create_namespace "$healthy_ns"
create_pair "$server_ns" "$host_server_healthy" "$server_healthy_iface" "$server_healthy_ip/30" "$healthy_ns" "$host_receiver_healthy" "$receiver_healthy_iface" "$receiver_healthy_ip/30"

if [[ "$affected_clients" -gt 0 ]]; then
  create_namespace "$affected_ns"
  create_pair "$server_ns" "$host_server_affected" "$server_affected_iface" "$server_affected_ip/30" "$affected_ns" "$host_receiver_affected" "$receiver_affected_iface" "$receiver_affected_ip/30"
fi

capture_status "$server_ns" "$server_healthy_iface" "server-healthy-before"
capture_status "$healthy_ns" "$receiver_healthy_iface" "receiver-healthy-before"
if [[ "$affected_clients" -gt 0 ]]; then
  capture_status "$server_ns" "$server_affected_iface" "server-affected-before"
  capture_status "$affected_ns" "$receiver_affected_iface" "receiver-affected-before"
fi

if [[ "$affected_clients" -eq 0 ]] && has_netem; then
  affected_ns="$healthy_ns"
  server_affected_iface="$server_healthy_iface"
  receiver_affected_iface="$receiver_healthy_iface"
fi

start_qdisc_sampler

if has_netem; then
  echo "Initial netem scheduled at epoch ms $netem_at_ms, after connection establishment and before coordinated start"
  if "$execute"; then
    (
      sleep_until_epoch_ms "$netem_at_ms"
      apply_netem_path "initial-netem" "apply"
    ) &
    helper_pids+=("$!")
  else
    echo "+ sleep until $netem_at_ms; apply latency $latency jitter $jitter loss $loss to $direction affected path"
    apply_netem_path "initial-netem" "apply"
  fi
fi

if [[ "$case_type" == "blackhole" ]]; then
  echo "External blackhole scheduled at epoch ms $blackhole_at_ms"
  if "$execute"; then
    (
      sleep_until_epoch_ms "$blackhole_at_ms"
      apply_netem_path "external-blackhole" "blackhole"
    ) &
    helper_pids+=("$!")
  else
    echo "+ sleep until $blackhole_at_ms; apply 100% loss to $direction affected path"
  fi
fi

if [[ "$recovery_at_ms" -gt 0 ]]; then
  echo "External path recovery scheduled at epoch ms $recovery_at_ms"
  if "$execute"; then
    (
      sleep_until_epoch_ms "$recovery_at_ms"
      apply_netem_path "external-recovery" "restore"
    ) &
    helper_pids+=("$!")
  else
    echo "+ sleep until $recovery_at_ms; restore prior path conditions on $direction affected path"
    apply_netem_path "external-recovery" "restore"
  fi
fi

run_worker_bg "$server_ns" "$server_log" "${server_args_array[@]}"
if "$execute"; then
  sleep 2
fi
if [[ "$affected_clients" -gt 0 ]]; then
  run_worker_bg "$affected_ns" "$affected_log" "${affected_receiver_args_array[@]}"
  if "$execute"; then
    sleep 1
  fi
fi
if [[ "$healthy_clients" -gt 0 ]]; then
  run_worker_bg "$healthy_ns" "$healthy_log" "${healthy_receiver_args_array[@]}"
fi

if "$execute"; then
  failed=0
  for pid in "${worker_pids[@]}"; do
    if ! wait "$pid"; then
      failed=1
    fi
  done
  if ! stop_qdisc_samplers; then
    failed=1
  fi
  if [[ "$failed" -ne 0 ]]; then
    for pid in "${helper_pids[@]}"; do
      if kill -0 "$pid" >/dev/null 2>&1; then
        kill "$pid" >/dev/null 2>&1 || true
      fi
    done
    echo "One or more netns workers or required qdisc evidence collectors failed. See $output_root" >&2
    exit 1
  fi
  for pid in "${helper_pids[@]}"; do
    if ! wait "$pid"; then
      echo "A netns helper process failed. See netem evidence under $output_root/netem" >&2
      exit 1
    fi
  done

  merge_args=("$script_dir/merge-worker-results.sh" --server "$server_out/$run_id")
  if [[ "$affected_clients" -gt 0 ]]; then
    merge_args+=(--receiver "$affected_out/$run_id-affected")
  fi
  if [[ "$healthy_clients" -gt 0 ]]; then
    if [[ "$affected_clients" -eq 0 ]]; then
      merge_args+=(--receiver "$healthy_out/$run_id-receiver")
    else
      merge_args+=(--receiver "$healthy_out/$run_id-healthy")
    fi
  fi
  merge_args+=(--out "$merged_out" --case "$case_name" --benchmark-name "$benchmark_name")
  if has_netem; then
    merge_args+=(
      --external-impairment-latency-ms "$(duration_millis "$latency")"
      --external-impairment-jitter-ms "$(duration_millis "$jitter")"
      --external-impairment-loss-percent "${loss%\%}"
    )
  fi
  if [[ "$case_type" == "blackhole" ]]; then
    merge_args+=(--external-blackhole-at-epoch-ms "$blackhole_at_ms")
  fi
  if [[ "$recovery_at_ms" -gt 0 ]]; then
    merge_args+=(--external-recovery-at-epoch-ms "$recovery_at_ms")
  fi
  if has_netem || [[ "$case_type" == "blackhole" ]]; then
    merge_args+=(--external-netem-limit-packets "$netem_limit" --netem-evidence "$output_root/netem")
  fi
  run_benchmark_command "${merge_args[@]}"
  echo "Merged artifact: $merged_out"
else
  echo
  echo "Merge command after execution:"
  if [[ "$affected_clients" -gt 0 ]]; then
    merge_command="$script_dir/merge-worker-results.sh --server $server_out/$run_id --receiver $affected_out/$run_id-affected --receiver $healthy_out/$run_id-healthy --out $merged_out --case $case_name --benchmark-name $benchmark_name"
    if has_netem; then
      merge_command+=" --external-impairment-latency-ms $(duration_millis "$latency") --external-impairment-jitter-ms $(duration_millis "$jitter") --external-impairment-loss-percent ${loss%\%}"
    fi
    if [[ "$case_type" == "blackhole" ]]; then
      merge_command+=" --external-blackhole-at-epoch-ms $blackhole_at_ms"
    fi
    if [[ "$recovery_at_ms" -gt 0 ]]; then
      merge_command+=" --external-recovery-at-epoch-ms $recovery_at_ms"
    fi
    if has_netem || [[ "$case_type" == "blackhole" ]]; then
      merge_command+=" --external-netem-limit-packets $netem_limit --netem-evidence $output_root/netem"
    fi
    echo "$merge_command"
  else
    echo "$script_dir/merge-worker-results.sh --server $server_out/$run_id --receiver $healthy_out/$run_id-receiver --out $merged_out --case $case_name --benchmark-name $benchmark_name"
  fi
fi
