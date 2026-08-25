#!/usr/bin/env bash
set -euo pipefail

execute=false
output_root=""
profiles="perfect,near-loss,regional-loss,poor,severe,blackhole"
clients="100"
affected_clients="10"
payload_size="512"
per_client_mbps="5"
warmup="5s"
duration="10s"
iterations="3"
probe_interval="100ms"
start_delay="20s"
start_offset="45s"
netem_before_start="5s"
netem_limit="10000"
blackhole_after="5s"
blackhole_duration=""
direction="server-to-client"
packet_limit=""
global_packet_limit=""
max_queued_bytes=""
workers=""
reliability="reliable_ordered"
recovery_mode="model_based"
resource_safety_max_aggregate_queued_bytes="402653184"
resource_safety_max_direct_memory_used_bytes="805306368"
campaign_run_user=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/run-netns-pilot-campaign.sh [options]
  sudo benchmark/scripts/run-netns-pilot-campaign.sh --execute [options]

Runs the single-host network-namespace pilot matrix sequentially with one sudo
invocation. Each case gets fresh namespaces, veth pairs, external tc netem
evidence, worker artifacts, and a merged summary.

Default mode is dry-run. Pass --execute as root to run the campaign.

Options:
  --execute                         Run the campaign. Default: dry-run.
  --out DIR                         Campaign output root.
  --profiles CSV                    Profiles to run. Default: perfect,near-loss,regional-loss,poor,severe,blackhole.
  --clients N                       Total clients per case. Default: 100.
  --affected-clients N|PCT          Affected clients in fairness/blackhole cases. Default: 10.
  --payload-size N                  Payload bytes. Default: 512.
  --per-client-mbps N               Offered Mbps per client. Default: 5.
  --warmup DURATION                 Warmup per worker. Default: 5s.
  --duration DURATION               Measurement duration per iteration. Default: 10s.
  --iterations N                    Measured iterations. Default: 3.
  --probe-interval DURATION         Probe cadence and warmup-drain input. Default: 100ms.
  --start-delay DURATION            Server connection wait. Default: 20s.
  --start-offset DURATION           Coordinated start offset per case. Default: 45s.
  --netem-before-start DURATION     Apply initial netem before start. Default: 5s.
  --netem-limit N                   Netem queue limit in packets. Default: 10000.
  --blackhole-after DURATION        Apply external blackhole during measurement. Default: 5s.
  --blackhole-duration DURATION     Restore the path after this interval. Default: permanent.
  --direction server-to-client|client-to-server|both
                                    Shaped direction. Default: server-to-client.
  --packet-limit N                  Optional RakNet packet limit override.
  --global-packet-limit N           Optional global packet limit override.
  --max-queued-bytes N              Optional per-session queue cap.
  --workers N                       Optional benchmark worker count.
  --reliability MODE                Reliability mode. Default: reliable_ordered.
  --resource-safety-max-aggregate-queued-bytes N
                                    Fail if cohort queue exceeds N. Default: 402653184 (384 MiB).
  --resource-safety-max-direct-memory-used-bytes N
                                    Fail if direct memory exceeds N. Default: 805306368 (768 MiB).
  --help                            Show this help.

Profiles:
  perfect         fanout, 0ms latency, 0ms jitter, 0% loss
  near-loss       fairness, 10ms latency, 2ms jitter, 2% loss
  regional-loss   fairness, 50ms latency, 5ms jitter, 2% loss
  poor            fairness, 100ms latency, 10ms jitter, 5% loss
  severe          fairness, 200ms latency, 20ms jitter, 10% loss
  blackhole       blackhole 10% (or --affected-clients) after connection/warmup

Outputs:
  campaign-plan.json                Exact matrix and parameters.
  campaign-status.jsonl             One completion/failure record per attempted case.
  campaign-summary.json/jsonl/md    Combined successful aggregate rows.
  cases/<index>-<profile>/           Per-case manifest, qdisc evidence, logs, and merged artifacts.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute)
      execute=true
      shift
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --profiles)
      profiles="$2"
      shift 2
      ;;
    --clients)
      clients="$2"
      shift 2
      ;;
    --affected-clients|--impaired-clients)
      affected_clients="$2"
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
    --netem-limit)
      netem_limit="$2"
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
    --resource-safety-max-aggregate-queued-bytes)
      resource_safety_max_aggregate_queued_bytes="$2"
      shift 2
      ;;
    --resource-safety-max-direct-memory-used-bytes)
      resource_safety_max_direct_memory_used_bytes="$2"
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
runner="$script_dir/run-netns-worker-smoke.sh"

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/netns-pilot-$timestamp"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to plan and summarize the campaign" >&2
  exit 2
fi
if ! [[ "$netem_limit" =~ ^[0-9]+$ && "$netem_limit" -gt 0 ]]; then
  echo "--netem-limit must be a positive integer" >&2
  exit 2
fi
for optional_name in packet_limit global_packet_limit max_queued_bytes workers; do
  if [[ -n "${!optional_name}" ]] \
      && ! [[ "${!optional_name}" =~ ^[0-9]+$ && "${!optional_name}" -gt 0 ]]; then
    echo "--${optional_name//_/-} must be a positive integer" >&2
    exit 2
  fi
done
for safety_name in resource_safety_max_aggregate_queued_bytes resource_safety_max_direct_memory_used_bytes; do
  if ! [[ "${!safety_name}" =~ ^[0-9]+$ && "${!safety_name}" -gt 0 ]]; then
    echo "--${safety_name//_/-} must be a positive integer" >&2
    exit 2
  fi
done
if [[ ! -x "$runner" ]]; then
  echo "Netns worker runner is missing or not executable: $runner" >&2
  exit 2
fi
if "$execute" && [[ "$(id -u)" -ne 0 ]]; then
  echo "--execute requires root; invoke this campaign once with sudo" >&2
  exit 2
fi

if "$execute"; then
  campaign_run_user="${BENCHMARK_RUN_USER:-${SUDO_USER:-}}"
  if [[ -n "$campaign_run_user" && "$campaign_run_user" != "root" ]]; then
    if ! id "$campaign_run_user" >/dev/null 2>&1; then
      echo "Benchmark run user does not exist: $campaign_run_user" >&2
      exit 2
    fi
  fi
fi

IFS=',' read -r -a requested_profiles <<<"$profiles"
if [[ "${#requested_profiles[@]}" -eq 0 ]]; then
  echo "--profiles must contain at least one profile" >&2
  exit 2
fi

profile_json='[]'
normalized_profiles=()
for requested_profile in "${requested_profiles[@]}"; do
  profile="${requested_profile,,}"
  profile="${profile//_/-}"
  case_type=""
  latency="0ms"
  jitter="0ms"
  loss="0%"
  case "$profile" in
    perfect)
      case_type="fanout"
      ;;
    near-loss)
      case_type="fairness"
      latency="10ms"
      jitter="2ms"
      loss="2%"
      ;;
    regional-loss)
      case_type="fairness"
      latency="50ms"
      jitter="5ms"
      loss="2%"
      ;;
    poor)
      case_type="fairness"
      latency="100ms"
      jitter="10ms"
      loss="5%"
      ;;
    severe)
      case_type="fairness"
      latency="200ms"
      jitter="20ms"
      loss="10%"
      ;;
    blackhole)
      case_type="blackhole"
      ;;
    *)
      echo "Unknown profile: $requested_profile" >&2
      exit 2
      ;;
  esac
  normalized_profiles+=("$profile")
  profile_json="$(jq -c \
    --arg profile "$profile" \
    --arg caseType "$case_type" \
    --arg latency "$latency" \
    --arg jitter "$jitter" \
    --arg loss "$loss" \
    '. + [{profile: $profile, caseType: $caseType, latency: $latency, jitter: $jitter, loss: $loss}]' \
    <<<"$profile_json")"
done

if [[ -n "$blackhole_duration" ]]; then
  if [[ "${#normalized_profiles[@]}" -ne 1 || "${normalized_profiles[0]}" != "blackhole" ]]; then
    echo "--blackhole-duration requires --profiles blackhole" >&2
    exit 2
  fi
fi

mkdir -p "$output_root/cases"

campaign_plan="$output_root/campaign-plan.json"
campaign_status="$output_root/campaign-status.jsonl"
summary_jsonl="$output_root/campaign-summary.jsonl"
summary_json="$output_root/campaign-summary.json"
summary_md="$output_root/campaign-summary.md"
: >"$campaign_status"
: >"$summary_jsonl"

jq -n \
  --arg generatedAt "$timestamp" \
  --argjson execute "$execute" \
  --arg outputRoot "$output_root" \
  --argjson profiles "$profile_json" \
  --arg clients "$clients" \
  --arg affectedClients "$affected_clients" \
  --arg payloadSize "$payload_size" \
  --arg perClientMbps "$per_client_mbps" \
  --arg warmup "$warmup" \
  --arg duration "$duration" \
  --arg iterations "$iterations" \
  --arg probeInterval "$probe_interval" \
  --arg startDelay "$start_delay" \
  --arg startOffset "$start_offset" \
  --arg netemBeforeStart "$netem_before_start" \
  --argjson netemLimit "$netem_limit" \
  --arg blackholeAfter "$blackhole_after" \
  --arg blackholeDuration "$blackhole_duration" \
  --arg direction "$direction" \
  --arg reliability "$reliability" \
  --arg recoveryMode "$recovery_mode" \
  --arg packetLimit "$packet_limit" \
  --arg globalPacketLimit "$global_packet_limit" \
  --arg maxQueuedBytes "$max_queued_bytes" \
  --arg workers "$workers" \
  --argjson resourceSafetyMaxAggregateQueuedBytes "$resource_safety_max_aggregate_queued_bytes" \
  --argjson resourceSafetyMaxDirectMemoryUsedBytes "$resource_safety_max_direct_memory_used_bytes" \
  '{
    kind: "raknet-netns-pilot-campaign",
    generatedAt: $generatedAt,
    execute: $execute,
    outputRoot: $outputRoot,
    profiles: $profiles,
    parameters: {
      clients: $clients,
      affectedClients: $affectedClients,
      payloadSize: $payloadSize,
      perClientMbps: $perClientMbps,
      warmup: $warmup,
      duration: $duration,
      iterations: $iterations,
      probeInterval: $probeInterval,
      startDelay: $startDelay,
      startOffset: $startOffset,
      netemBeforeStart: $netemBeforeStart,
      netemLimitPackets: $netemLimit,
      blackholeAfter: $blackholeAfter,
      blackholeDuration: (if $blackholeDuration == "" then null else $blackholeDuration end),
      direction: $direction,
      reliability: $reliability,
      recoveryMode: $recoveryMode,
      packetLimit: (if $packetLimit == "" then null else ($packetLimit | tonumber) end),
      globalPacketLimit: (if $globalPacketLimit == "" then null else ($globalPacketLimit | tonumber) end),
      maxQueuedBytes: (if $maxQueuedBytes == "" then null else ($maxQueuedBytes | tonumber) end),
      workers: (if $workers == "" then null else ($workers | tonumber) end),
      resourceSafetyMaxAggregateQueuedBytes: $resourceSafetyMaxAggregateQueuedBytes,
      resourceSafetyMaxDirectMemoryUsedBytes: $resourceSafetyMaxDirectMemoryUsedBytes
    }
  }' >"$campaign_plan"

optional_args=()
optional_args+=(
  --resource-safety-max-aggregate-queued-bytes "$resource_safety_max_aggregate_queued_bytes"
  --resource-safety-max-direct-memory-used-bytes "$resource_safety_max_direct_memory_used_bytes"
)
if [[ -n "$packet_limit" ]]; then
  optional_args+=(--packet-limit "$packet_limit")
fi
if [[ -n "$global_packet_limit" ]]; then
  optional_args+=(--global-packet-limit "$global_packet_limit")
fi
if [[ -n "$max_queued_bytes" ]]; then
  optional_args+=(--max-queued-bytes "$max_queued_bytes")
fi
if [[ -n "$workers" ]]; then
  optional_args+=(--workers "$workers")
fi
if [[ -n "$blackhole_duration" ]]; then
  optional_args+=(--blackhole-duration "$blackhole_duration")
fi

echo "Netns pilot campaign: $output_root"
echo "Profiles: ${normalized_profiles[*]}"
echo "Transport controller: bounded recovery + delivery model"
if ! "$execute"; then
  echo "Dry-run only. Re-run this command with sudo and --execute after reviewing campaign-plan.json."
fi

failed=0
index=0
for profile in "${normalized_profiles[@]}"; do
  index=$((index + 1))
  case_type="$(jq -r --arg profile "$profile" '.[] | select(.profile == $profile) | .caseType' <<<"$profile_json")"
  latency="$(jq -r --arg profile "$profile" '.[] | select(.profile == $profile) | .latency' <<<"$profile_json")"
  jitter="$(jq -r --arg profile "$profile" '.[] | select(.profile == $profile) | .jitter' <<<"$profile_json")"
  loss="$(jq -r --arg profile "$profile" '.[] | select(.profile == $profile) | .loss' <<<"$profile_json")"
  case_dir="$output_root/cases/$(printf '%02d' "$index")-$profile"
  namespace_prefix="rbp${index}-$$"
  command=(
    "$runner"
    --out "$case_dir"
    --namespace-prefix "$namespace_prefix"
    --case "$case_type"
    --clients "$clients"
    --payload-size "$payload_size"
    --per-client-mbps "$per_client_mbps"
    --warmup "$warmup"
    --duration "$duration"
    --iterations "$iterations"
    --probe-interval "$probe_interval"
    --start-delay "$start_delay"
    --start-offset "$start_offset"
    --netem-before-start "$netem_before_start"
    --netem-limit "$netem_limit"
    --blackhole-after "$blackhole_after"
    --latency "$latency"
    --jitter "$jitter"
    --loss "$loss"
    --direction "$direction"
    --reliability "$reliability"
    "${optional_args[@]}"
  )
  if [[ "$case_type" == "fairness" || "$case_type" == "blackhole" ]]; then
    command+=(--affected-clients "$affected_clients")
  fi
  if "$execute"; then
    command+=(--execute)
  fi

  echo
  echo "[$index/${#normalized_profiles[@]}] $profile ($case_type, $latency/$jitter/$loss)"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if "${command[@]}"; then
    completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    jq -nc \
      --arg profile "$profile" --arg caseType "$case_type" --arg status "completed" \
      --arg startedAt "$started_at" --arg completedAt "$completed_at" --arg artifact "$case_dir" \
      '{profile: $profile, caseType: $caseType, status: $status, startedAt: $startedAt, completedAt: $completedAt, artifact: $artifact}' \
      >>"$campaign_status"
    if "$execute"; then
      jq -c \
        --arg profile "$profile" \
        --arg caseType "$case_type" \
        --arg caseArtifact "$case_dir" \
        '.aggregate
          + {profile: $profile, caseType: $caseType, caseArtifact: $caseArtifact}
          | .case = ((.case // "netns") + "-" + $profile)' \
        "$case_dir/merged/lab-summary.json" >>"$summary_jsonl"
    fi
  else
    completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    failure_kind="worker-case-failure"
    diagnostic_artifact=""
    if [[ -s "$case_dir/case-status.json" ]]; then
      failure_kind="$(jq -r '.failureKind // "worker-case-failure"' "$case_dir/case-status.json")"
      diagnostic_artifact="$(jq -r '.diagnosticArtifact // ""' "$case_dir/case-status.json")"
    fi
    jq -nc \
      --arg profile "$profile" --arg caseType "$case_type" --arg status "failed" \
      --arg startedAt "$started_at" --arg completedAt "$completed_at" --arg artifact "$case_dir" \
      --arg failureKind "$failure_kind" --arg diagnosticArtifact "$diagnostic_artifact" \
      '{profile: $profile, caseType: $caseType, status: $status, startedAt: $startedAt,
        completedAt: $completedAt, artifact: $artifact, failureKind: $failureKind,
        diagnosticArtifact: (if $diagnosticArtifact == "" then null else $diagnosticArtifact end)}' \
      >>"$campaign_status"
    failed=1
    break
  fi
done

jq -s \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg plan "$campaign_plan" \
  --arg statusPath "$campaign_status" \
  --argjson executed "$execute" \
  --argjson failed "$failed" \
  --slurpfile statuses "$campaign_status" \
  '{
    kind: "raknet-netns-pilot-summary",
    generatedAt: $generatedAt,
    executed: $executed,
    executionPassed: ($executed and ($failed == 0)),
    passed: ($executed and ($failed == 0)),
    campaignPlan: $plan,
    campaignStatus: $statusPath,
    statuses: $statuses,
    results: .
  }' "$summary_jsonl" >"$summary_json"

{
  echo "# Netns Pilot Campaign"
  echo
  echo "- Generated: \`$(date -u +%Y-%m-%dT%H:%M:%SZ)\`"
  echo "- Executed: \`$execute\`"
  echo "- Execution passed: \`$(if "$execute" && [[ "$failed" -eq 0 ]]; then echo true; else echo false; fi)\`"
  echo "- Stability gate: \`$(if "$execute" && jq -e -s 'length > 0 and all(.unstable == false)' "$summary_jsonl" >/dev/null; then echo passed; elif "$execute"; then echo failed; else echo not-run; fi)\`"
  echo "- Clients per case: \`$clients\`"
  echo "- Affected clients: \`$affected_clients\`"
  echo "- Offered rate: \`${per_client_mbps}Mbps/client\`"
  echo "- Transport controller: \`bounded recovery + delivery model\`"
  echo
  if "$execute"; then
    echo "| Profile | Impairment | Delivered Gbps | Healthy Gbps | Affected Gbps | Healthy Mbps p50 | Probe p99 ms | Max queue bytes | Stable |"
    echo "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
    jq -r '
      [
        .profile,
        .impairmentProfile,
        (.deliveredGbps | tostring),
        (.healthyDeliveredGbps | tostring),
        (.affectedDeliveredGbps | tostring),
        (.healthyClientMbpsP50 | tostring),
        (.probeRttP99Millis | tostring),
        (.maxQueuedBytes | tostring),
        ((.unstable | not) | tostring)
      ] | "| " + join(" | ") + " |"
    ' "$summary_jsonl"
  else
    echo "Dry-run complete. Per-case manifests and commands are under \`$output_root/cases\`."
  fi
  echo
  echo "## Artifacts"
  echo
  echo "- Plan: \`$campaign_plan\`"
  echo "- Status: \`$campaign_status\`"
  echo "- Summary JSON: \`$summary_json\`"
  echo "- Summary JSONL: \`$summary_jsonl\`"
} >"$summary_md"

echo
echo "Campaign plan: $campaign_plan"
echo "Campaign summary: $summary_json"
echo "Campaign report: $summary_md"

if [[ "$failed" -ne 0 ]]; then
  echo "Campaign stopped after a failed case; inspect campaign-status.jsonl and the case logs" >&2
  exit 1
fi
