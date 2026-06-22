#!/usr/bin/env bash
set -euo pipefail

profile="smoke"
dry_run=false
continue_on_error=false
gradle="./gradlew"
output_root=""
common_args=""
only_pattern=""
stability_threshold_pct="10"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/run-baseline-matrix.sh [options]

Options:
  --profile smoke|pilot|local|lab Baseline profile to run. Default: smoke.
  --out DIR                     Suite output directory. Default: benchmark/build/benchmark-results/baseline-<profile>-<timestamp>.
  --dry-run                     Print Gradle commands and write a manifest without running benchmarks.
  --only PATTERN                Run only cases whose name contains PATTERN.
  --common-args "..."           Extra benchmark args appended to every case.
  --stability-threshold-pct N   Mark aggregate rows unstable when fewer than 3 measured iterations exist or throughput/p99 spread exceeds N percent. Default: 10.
  --gradle ./gradlew            Gradle executable to use.
  --continue-on-error           Keep running remaining cases after a benchmark failure.
  --help                        Show this help.

Profiles:
  smoke  Short one-iteration local regression baseline. Not line-rate evidence.
  pilot  Short three-iteration local development baseline subset. Not line-rate evidence.
  local  Longer single-host baseline for local comparisons. Not line-rate evidence.
  lab    Recommended remote/lab-oriented baseline command set. Use controlled hosts/NICs.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="$2"
      shift 2
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --only)
      only_pattern="$2"
      shift 2
      ;;
    --common-args)
      common_args="$2"
      shift 2
      ;;
    --stability-threshold-pct)
      stability_threshold_pct="$2"
      shift 2
      ;;
    --gradle)
      gradle="$2"
      shift 2
      ;;
    --continue-on-error)
      continue_on_error=true
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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

case "$profile" in
  smoke|pilot|local|lab)
    ;;
  *)
    echo "Unknown profile: $profile" >&2
    usage >&2
    exit 2
    ;;
esac

is_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

if ! is_number "$stability_threshold_pct"; then
  echo "--stability-threshold-pct must be a non-negative number: $stability_threshold_pct" >&2
  exit 2
fi

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/baseline-${profile}-${timestamp}"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi

mkdir -p "$output_root"
manifest="$output_root/manifest.jsonl"
report="$output_root/README.md"
suite_summary_jsonl="$output_root/suite-summary.jsonl"
suite_summary_csv="$output_root/suite-summary.csv"
suite_aggregate_jsonl="$output_root/suite-aggregate.jsonl"
suite_aggregate_csv="$output_root/suite-aggregate.csv"
: >"$manifest"
: >"$suite_summary_jsonl"
: >"$suite_aggregate_jsonl"
cat >"$suite_summary_csv" <<'CSV'
case,benchmark_name,iteration,clients,open_peers,active_peers,state_connected_peers,state_disconnecting_peers,state_disconnected_peers,state_unconnected_peers,payload_size,reliability,batched,batch_interval_ms,logical_packets_per_batch,batch_groups,target_mbps,target_client_mbps,impairment_profile,impairment_latency_ms,impairment_jitter_ms,impairment_loss_pct,elapsed_ms,offered_gbps,delivered_gbps,healthy_delivered_gbps,affected_delivered_gbps,server_bytes_out,server_datagrams_out,server_datagrams_out_s,sent_delivered_bytes_ratio,healthy_sent_delivered_bytes_ratio,affected_sent_delivered_bytes_ratio,client_mbps_min,client_mbps_p50,client_mbps_p95,client_mbps_p99,client_mbps_max,healthy_client_mbps_p50,healthy_client_mbps_p99,affected_client_mbps_p50,affected_client_mbps_p99,delivered_msg_s,delivered_logical_packets_s,p95_ms,p99_ms,fairness,healthy_fairness,affected_fairness,affected_clients,disconnects,blackholed_datagrams_in,blackholed_datagrams_out,stale_datagrams,stale_datagrams_s,nack_in,nack_in_s,nack_out,nack_out_s,max_queued_bytes,artifact,scenario,packet_limit,global_packet_limit,configured_max_queued_bytes
CSV
cat >"$suite_aggregate_csv" <<'CSV'
case,benchmark_name,iterations,clients,median_open_peers,median_active_peers,min_active_peers,max_state_disconnected_peers,max_state_unconnected_peers,payload_size,reliability,batched,batch_interval_ms,logical_packets_per_batch,batch_groups,target_mbps,target_client_mbps,impairment_profile,impairment_latency_ms,impairment_jitter_ms,impairment_loss_pct,median_delivered_gbps,median_healthy_delivered_gbps,median_affected_delivered_gbps,median_server_datagrams_out_s,median_sent_delivered_bytes_ratio,median_healthy_sent_delivered_bytes_ratio,median_affected_sent_delivered_bytes_ratio,median_client_mbps_p50,median_client_mbps_p99,median_healthy_client_mbps_p50,median_healthy_client_mbps_p99,median_affected_client_mbps_p50,median_affected_client_mbps_p99,delivered_gbps_spread_pct,median_p99_ms,p99_spread_pct,max_queued_bytes,median_stale_datagrams_s,median_nack_out_s,median_fairness,median_healthy_fairness,median_affected_fairness,disconnects,blackholed_datagrams_in,blackholed_datagrams_out,stale_datagrams,nack_in,nack_out,unstable,unstable_reasons,artifact,scenario,packet_limit,global_packet_limit,configured_max_queued_bytes
CSV

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

command_line() {
  printf '%q ' "$@"
}

write_report_header() {
  {
    echo "# RakNet Baseline Suite"
    echo
    echo "- Profile: \`$profile\`"
    echo "- Started: \`$timestamp\`"
    echo "- Output root: \`$output_root\`"
    echo "- Dry run: \`$dry_run\`"
    echo
    echo "| Case | Status | Artifact |"
    echo "| --- | --- | --- |"
  } >"$report"
}

append_report_row() {
  local name="$1"
  local status="$2"
  local artifact="$3"
  echo "| \`$name\` | \`$status\` | \`$artifact\` |" >>"$report"
}

record_manifest() {
  local name="$1"
  local status="$2"
  local args="$3"
  local command="$4"
  local artifact="$5"
  local started="$6"
  local ended="$7"

  printf '{"name":"%s","profile":"%s","status":"%s","started":"%s","ended":"%s","args":"%s","command":"%s","artifact":"%s"}\n' \
    "$(json_escape "$name")" \
    "$(json_escape "$profile")" \
    "$(json_escape "$status")" \
    "$(json_escape "$started")" \
    "$(json_escape "$ended")" \
    "$(json_escape "$args")" \
    "$(json_escape "$command")" \
    "$(json_escape "$artifact")" >>"$manifest"
}

append_case_metrics() {
  local name="$1"
  local artifact="$2"
  local summary="$artifact/summary.json"

  if [[ ! -f "$summary" ]]; then
    echo "No summary.json found for $name at $summary" >&2
    return 0
  fi
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq not found; skipping suite summary extraction for $name" >&2
    return 0
  fi

  jq -c --arg case "$name" --arg artifact "$artifact" '
    . as $summary |
    ((($summary.impairmentLatencyMillis // 0) | tostring) + "ms/" + (($summary.impairmentJitterMillis // 0) | tostring) + "ms/" + (($summary.impairmentLossPercent // 0) | tostring) + "%") as $impairmentProfile |
    .iterations[] | {
      case: $case,
      benchmarkName: .name,
      iteration: .iteration,
      clients: .clients,
      openPeers: (.openPeers // .clients),
      activePeers: (.activePeers // .clients),
      stateConnectedPeers: (.connectedStatePeers // 0),
      stateDisconnectingPeers: (.disconnectingStatePeers // 0),
      stateDisconnectedPeers: (.disconnectedStatePeers // 0),
      stateUnconnectedPeers: (.unconnectedStatePeers // 0),
      payloadSize: .payloadSize,
      reliability: .reliability,
      batched: (.batched // false),
      batchIntervalMillis: (.batchIntervalMillis // $summary.batchIntervalMillis // 0),
      logicalPacketsPerBatch: (.logicalPacketsPerBatch // $summary.logicalPacketsPerBatch // 1),
      batchGroups: (.batchGroups // $summary.batchGroups // 1),
      targetMbps: .targetMbps,
      targetClientMbps: (.targetClientMbps // 0),
      impairmentProfile: $impairmentProfile,
      impairmentLatencyMillis: ($summary.impairmentLatencyMillis // 0),
      impairmentJitterMillis: ($summary.impairmentJitterMillis // 0),
      impairmentLossPercent: ($summary.impairmentLossPercent // 0),
      scenario: ($summary.scenario // null),
      packetLimit: ($summary.packetLimit // null),
      globalPacketLimit: ($summary.globalPacketLimit // null),
      configuredMaxQueuedBytes: ($summary.configuredMaxQueuedBytes // null),
      elapsedMillis: .elapsedMillis,
      offeredGbps: .offeredGbps,
      deliveredGbps: .deliveredGbps,
      healthyDeliveredGbps: (.healthyDeliveredGbps // .deliveredGbps),
      affectedDeliveredGbps: (.affectedDeliveredGbps // 0),
      serverBytesOut: (.serverBytesOut // 0),
      serverDatagramsOut: (.serverDatagramsOut // 0),
      serverDatagramsOutPerSecond: (.serverDatagramsOutPerSecond // 0),
      sentToDeliveredBytesRatio: (.sentToDeliveredBytesRatio // 0),
      healthySentToDeliveredBytesRatio: (.healthySentToDeliveredBytesRatio // 0),
      affectedSentToDeliveredBytesRatio: (.affectedSentToDeliveredBytesRatio // 0),
      clientMbpsMin: (.perClientThroughput.minMbps // 0),
      clientMbpsP50: (.perClientThroughput.p50Mbps // 0),
      clientMbpsP95: (.perClientThroughput.p95Mbps // 0),
      clientMbpsP99: (.perClientThroughput.p99Mbps // 0),
      clientMbpsMax: (.perClientThroughput.maxMbps // 0),
      healthyClientMbpsP50: (.healthyClientThroughput.p50Mbps // 0),
      healthyClientMbpsP99: (.healthyClientThroughput.p99Mbps // 0),
      affectedClientMbpsP50: (.affectedClientThroughput.p50Mbps // 0),
      affectedClientMbpsP99: (.affectedClientThroughput.p99Mbps // 0),
      deliveredMessagesPerSecond: .deliveredMessagesPerSecond,
      deliveredLogicalPacketsPerSecond: (.deliveredLogicalPacketsPerSecond // 0),
      probeRttP95Millis: .probeRttP95Millis,
      probeRttP99Millis: .probeRttP99Millis,
      fairnessIndex: .fairnessIndex,
      healthyFairnessIndex: (.healthyFairnessIndex // 1),
      affectedFairnessIndex: (.affectedFairnessIndex // 1),
      affectedClients: (.affectedClients // 0),
      disconnects: (.disconnects // 0),
      blackholedDatagramsIn: (.blackholedDatagramsIn // 0),
      blackholedDatagramsOut: (.blackholedDatagramsOut // 0),
      staleDatagrams: .staleDatagrams,
      staleDatagramsPerSecond: (.staleDatagramsPerSecond // 0),
      nackIn: .nackIn,
      nackInPerSecond: (.nackInPerSecond // 0),
      nackOut: .nackOut,
      nackOutPerSecond: (.nackOutPerSecond // 0),
      maxQueuedBytes: .maxQueuedBytes,
      artifact: $artifact
    }
  ' "$summary" >>"$suite_summary_jsonl"

  jq -r --arg case "$name" --arg artifact "$artifact" '
    . as $summary |
    ((($summary.impairmentLatencyMillis // 0) | tostring) + "ms/" + (($summary.impairmentJitterMillis // 0) | tostring) + "ms/" + (($summary.impairmentLossPercent // 0) | tostring) + "%") as $impairmentProfile |
    .iterations[] | [
      $case,
      .name,
      .iteration,
      .clients,
      (.openPeers // .clients),
      (.activePeers // .clients),
      (.connectedStatePeers // 0),
      (.disconnectingStatePeers // 0),
      (.disconnectedStatePeers // 0),
      (.unconnectedStatePeers // 0),
      .payloadSize,
      .reliability,
      (.batched // false),
      (.batchIntervalMillis // $summary.batchIntervalMillis // 0),
      (.logicalPacketsPerBatch // $summary.logicalPacketsPerBatch // 1),
      (.batchGroups // $summary.batchGroups // 1),
      .targetMbps,
      (.targetClientMbps // 0),
      $impairmentProfile,
      ($summary.impairmentLatencyMillis // 0),
      ($summary.impairmentJitterMillis // 0),
      ($summary.impairmentLossPercent // 0),
      .elapsedMillis,
      .offeredGbps,
      .deliveredGbps,
      (.healthyDeliveredGbps // .deliveredGbps),
      (.affectedDeliveredGbps // 0),
      (.serverBytesOut // 0),
      (.serverDatagramsOut // 0),
      (.serverDatagramsOutPerSecond // 0),
      (.sentToDeliveredBytesRatio // 0),
      (.healthySentToDeliveredBytesRatio // 0),
      (.affectedSentToDeliveredBytesRatio // 0),
      (.perClientThroughput.minMbps // 0),
      (.perClientThroughput.p50Mbps // 0),
      (.perClientThroughput.p95Mbps // 0),
      (.perClientThroughput.p99Mbps // 0),
      (.perClientThroughput.maxMbps // 0),
      (.healthyClientThroughput.p50Mbps // 0),
      (.healthyClientThroughput.p99Mbps // 0),
      (.affectedClientThroughput.p50Mbps // 0),
      (.affectedClientThroughput.p99Mbps // 0),
      .deliveredMessagesPerSecond,
      (.deliveredLogicalPacketsPerSecond // 0),
      .probeRttP95Millis,
      .probeRttP99Millis,
      .fairnessIndex,
      (.healthyFairnessIndex // 1),
      (.affectedFairnessIndex // 1),
      (.affectedClients // 0),
      (.disconnects // 0),
      (.blackholedDatagramsIn // 0),
      (.blackholedDatagramsOut // 0),
      .staleDatagrams,
      (.staleDatagramsPerSecond // 0),
      .nackIn,
      (.nackInPerSecond // 0),
      .nackOut,
      (.nackOutPerSecond // 0),
      .maxQueuedBytes,
      $artifact,
      ($summary.scenario // null),
      ($summary.packetLimit // null),
      ($summary.globalPacketLimit // null),
      ($summary.configuredMaxQueuedBytes // null)
    ] | @csv
  ' "$summary" >>"$suite_summary_csv"
}

write_suite_aggregates() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq not found; skipping suite aggregate extraction" >&2
    return 0
  fi
  if [[ ! -s "$suite_summary_jsonl" ]]; then
    {
      echo
      echo "No successful benchmark case metrics were available for aggregate summary generation."
    } >>"$report"
    return 0
  fi

  jq -c -s --argjson stabilityThreshold "$stability_threshold_pct" '
    def median:
      if length == 0 then 0
      else sort as $s | $s[(length - 1) / 2 | floor]
      end;

    def spread_pct($values):
      ($values | map(. // 0) | sort) as $s |
      if ($s | length) == 0 then 0
      else
        ($s[0] // 0) as $min |
        ($s[-1] // 0) as $max |
        ($s[((($s | length) - 1) / 2 | floor)] // 0) as $median |
        if $median == 0 then
          (if $max == $min then 0 else 100 end)
        else
          ((($max - $min) / $median) * 100)
        end
      end;

    group_by([.case, .benchmarkName])[] as $rows |
    ($rows[0]) as $first |
    ($rows | map(.deliveredGbps)) as $throughput |
    ($rows | map(.probeRttP99Millis)) as $p99 |
    (spread_pct($throughput)) as $throughputSpread |
    (spread_pct($p99)) as $p99Spread |
    ($throughput | max) as $maxDeliveredGbps |
    (
      []
      + (if ($rows | length) < 3 then ["insufficient-iterations"] else [] end)
      + (if $throughputSpread > $stabilityThreshold then ["throughput-spread"] else [] end)
      + (if $p99Spread > $stabilityThreshold then ["p99-spread"] else [] end)
      + (if $maxDeliveredGbps <= 0 then ["zero-delivery"] else [] end)
    ) as $unstableReasons |
    {
      summaryKind: "aggregate",
      case: $first.case,
      benchmarkName: $first.benchmarkName,
      iteration: "aggregate",
      measuredIterations: ($rows | length),
      clients: $first.clients,
      openPeers: ($rows | map(.openPeers) | median),
      activePeers: ($rows | map(.activePeers) | median),
      activePeersMin: ($rows | map(.activePeers) | min),
      activePeersMax: ($rows | map(.activePeers) | max),
      stateConnectedPeers: ($rows | map(.stateConnectedPeers) | median),
      stateDisconnectingPeers: ($rows | map(.stateDisconnectingPeers) | max),
      stateDisconnectedPeers: ($rows | map(.stateDisconnectedPeers) | max),
      stateUnconnectedPeers: ($rows | map(.stateUnconnectedPeers) | max),
      payloadSize: $first.payloadSize,
      reliability: $first.reliability,
      batched: $first.batched,
      batchIntervalMillis: ($first.batchIntervalMillis // 0),
      logicalPacketsPerBatch: ($first.logicalPacketsPerBatch // 1),
      batchGroups: ($first.batchGroups // 1),
      targetMbps: $first.targetMbps,
      targetClientMbps: $first.targetClientMbps,
      scenario: ($first.scenario // null),
      packetLimit: ($first.packetLimit // null),
      globalPacketLimit: ($first.globalPacketLimit // null),
      configuredMaxQueuedBytes: ($first.configuredMaxQueuedBytes // null),
      impairmentProfile: ($first.impairmentProfile // "0ms/0ms/0%"),
      impairmentLatencyMillis: ($first.impairmentLatencyMillis // 0),
      impairmentJitterMillis: ($first.impairmentJitterMillis // 0),
      impairmentLossPercent: ($first.impairmentLossPercent // 0),
      elapsedMillis: ($rows | map(.elapsedMillis) | median),
      offeredGbps: ($rows | map(.offeredGbps) | median),
      deliveredGbps: ($throughput | median),
      healthyDeliveredGbps: ($rows | map(.healthyDeliveredGbps) | median),
      affectedDeliveredGbps: ($rows | map(.affectedDeliveredGbps) | median),
      serverDatagramsOutPerSecond: ($rows | map(.serverDatagramsOutPerSecond) | median),
      sentToDeliveredBytesRatio: ($rows | map(.sentToDeliveredBytesRatio) | median),
      healthySentToDeliveredBytesRatio: ($rows | map(.healthySentToDeliveredBytesRatio) | median),
      affectedSentToDeliveredBytesRatio: ($rows | map(.affectedSentToDeliveredBytesRatio) | median),
      clientMbpsP50: ($rows | map(.clientMbpsP50) | median),
      clientMbpsP99: ($rows | map(.clientMbpsP99) | median),
      healthyClientMbpsP50: ($rows | map(.healthyClientMbpsP50) | median),
      healthyClientMbpsP99: ($rows | map(.healthyClientMbpsP99) | median),
      affectedClientMbpsP50: ($rows | map(.affectedClientMbpsP50) | median),
      affectedClientMbpsP99: ($rows | map(.affectedClientMbpsP99) | median),
      deliveredGbpsMin: ($throughput | min),
      deliveredGbpsMax: ($throughput | max),
      deliveredGbpsSpreadPct: $throughputSpread,
      deliveredMessagesPerSecond: ($rows | map(.deliveredMessagesPerSecond) | median),
      deliveredLogicalPacketsPerSecond: ($rows | map(.deliveredLogicalPacketsPerSecond) | median),
      probeRttP95Millis: ($rows | map(.probeRttP95Millis) | median),
      probeRttP99Millis: ($p99 | median),
      probeRttP99MillisMin: ($p99 | min),
      probeRttP99MillisMax: ($p99 | max),
      probeRttP99MillisSpreadPct: $p99Spread,
      fairnessIndex: ($rows | map(.fairnessIndex) | median),
      healthyFairnessIndex: ($rows | map(.healthyFairnessIndex) | median),
      affectedFairnessIndex: ($rows | map(.affectedFairnessIndex) | median),
      affectedClients: ($rows | map(.affectedClients) | max),
      disconnects: ($rows | map(.disconnects) | add),
      blackholedDatagramsIn: ($rows | map(.blackholedDatagramsIn) | add),
      blackholedDatagramsOut: ($rows | map(.blackholedDatagramsOut) | add),
      staleDatagrams: ($rows | map(.staleDatagrams) | add),
      staleDatagramsPerSecond: ($rows | map(.staleDatagramsPerSecond) | median),
      nackIn: ($rows | map(.nackIn) | add),
      nackInPerSecond: ($rows | map(.nackInPerSecond) | median),
      nackOut: ($rows | map(.nackOut) | add),
      nackOutPerSecond: ($rows | map(.nackOutPerSecond) | median),
      maxQueuedBytes: ($rows | map(.maxQueuedBytes) | max),
      maxQueuedBytesMedian: ($rows | map(.maxQueuedBytes) | median),
      unstable: (($unstableReasons | length) > 0),
      unstableReasons: $unstableReasons,
      artifact: $first.artifact
    }
  ' "$suite_summary_jsonl" >"$suite_aggregate_jsonl"

  jq -r '
    [
      .case,
      .benchmarkName,
      .measuredIterations,
      .clients,
      .openPeers,
      .activePeers,
      .activePeersMin,
      .stateDisconnectedPeers,
      .stateUnconnectedPeers,
      .payloadSize,
      .reliability,
      .batched,
      .batchIntervalMillis,
      .logicalPacketsPerBatch,
      .batchGroups,
      .targetMbps,
      .targetClientMbps,
      .impairmentProfile,
      .impairmentLatencyMillis,
      .impairmentJitterMillis,
      .impairmentLossPercent,
      .deliveredGbps,
      .healthyDeliveredGbps,
      .affectedDeliveredGbps,
      .serverDatagramsOutPerSecond,
      .sentToDeliveredBytesRatio,
      .healthySentToDeliveredBytesRatio,
      .affectedSentToDeliveredBytesRatio,
      .clientMbpsP50,
      .clientMbpsP99,
      .healthyClientMbpsP50,
      .healthyClientMbpsP99,
      .affectedClientMbpsP50,
      .affectedClientMbpsP99,
      .deliveredGbpsSpreadPct,
      .probeRttP99Millis,
      .probeRttP99MillisSpreadPct,
      .maxQueuedBytes,
      .staleDatagramsPerSecond,
      .nackOutPerSecond,
      .fairnessIndex,
      .healthyFairnessIndex,
      .affectedFairnessIndex,
      .disconnects,
      .blackholedDatagramsIn,
      .blackholedDatagramsOut,
      .staleDatagrams,
      .nackIn,
      .nackOut,
      .unstable,
      (.unstableReasons | join(";")),
      .artifact,
      .scenario,
      .packetLimit,
      .globalPacketLimit,
      .configuredMaxQueuedBytes
    ] | @csv
  ' "$suite_aggregate_jsonl" >>"$suite_aggregate_csv"

  {
    echo
    echo "## Aggregate Stability"
    echo
    echo "- Stability threshold: zero delivered throughput, fewer than \`3\` measured iterations, or \`$stability_threshold_pct%\` relative spread for delivered throughput or p99 probe RTT."
    echo "- Aggregate JSONL: \`$suite_aggregate_jsonl\`"
    echo "- Aggregate CSV: \`$suite_aggregate_csv\`"
    echo
    echo "| Case | Scenario | Impairment | Iterations | Active Peers | Disconnected State | Median Gbps | Healthy Gbps | Affected Gbps | Client Mbps p50 | Client Mbps p99 | Send/Deliver | Datagram Out/s | Stale/s | NACK Out/s | Throughput Spread | Median p99 ms | p99 Spread | Max Queue | Unstable | Reasons |"
    echo "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |"
    jq -r '
      def fmt($value):
        if $value == null then "n/a"
        elif ($value | type) == "number" and $value != 0 and (($value | fabs) < 1) then (($value * 1000000 | round) / 1000000 | tostring)
        elif ($value | type) == "number" then (($value * 1000 | round) / 1000 | tostring)
        else ($value | tostring)
        end;
      [
        "`" + .case + "`",
        "`" + .benchmarkName + "`",
        "`" + (.impairmentProfile // "0ms/0ms/0%") + "`",
        (.measuredIterations | tostring),
        fmt(.activePeers),
        fmt(.stateDisconnectedPeers),
        fmt(.deliveredGbps),
        fmt(.healthyDeliveredGbps),
        fmt(.affectedDeliveredGbps),
        fmt(.clientMbpsP50),
        fmt(.clientMbpsP99),
        fmt(.sentToDeliveredBytesRatio),
        fmt(.serverDatagramsOutPerSecond),
        fmt(.staleDatagramsPerSecond),
        fmt(.nackOutPerSecond),
        fmt(.deliveredGbpsSpreadPct) + "%",
        fmt(.probeRttP99Millis),
        fmt(.probeRttP99MillisSpreadPct) + "%",
        (.maxQueuedBytes | tostring),
        (.unstable | tostring),
        "`" + ((.unstableReasons // []) | join(",")) + "`"
      ] | @tsv
    ' "$suite_aggregate_jsonl" | while IFS=$'\t' read -r case_name scenario impairment iterations active_peers disconnected_state gbps healthy_gbps affected_gbps client_p50 client_p99 send_ratio datagram_out_s stale_s nack_out_s throughput_spread p99 p99_spread queue unstable reasons; do
      echo "| $case_name | $scenario | $impairment | $iterations | $active_peers | $disconnected_state | $gbps | $healthy_gbps | $affected_gbps | $client_p50 | $client_p99 | $send_ratio | $datagram_out_s | $stale_s | $nack_out_s | $throughput_spread | $p99 | $p99_spread | $queue | $unstable | $reasons |"
    done
  } >>"$report"
}

case_list_smoke() {
  cat <<'CASES'
bestcase-1c-medium|baseline-bandwidth --clients 1 --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --rate-mbps 50 --workers 1
curve-1c-mtu|bandwidth-latency-curve --clients 1 --warmup 0ms --duration 1s --iterations 1 --payload-size 1200 --rates-mbps 50,100 --workers 1
fanout-10x0_2|multi-client-fanout --clients 10 --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --per-client-mbps 0.2 --workers 1
fairness-10-2poor|fairness --clients 10 --impaired-clients 2 --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --per-client-mbps 0.2 --impairment-latency 50ms --impairment-jitter 5ms --impairment-loss 5 --workers 1
disappear-10-stopread|disappearing-clients --clients 10 --disappearing-clients 1 --disappear-after 500ms --disappear-mode stop-reading --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --per-client-mbps 0.2 --workers 1
disappear-10-blackhole|disappearing-clients --clients 10 --disappearing-clients 1 --disappear-after 500ms --disappear-mode blackhole --warmup 0ms --duration 1s --iterations 1 --payload-size 512 --per-client-mbps 0.2 --workers 1
batch-10-20ms|batched-game-traffic --clients 10 --warmup 0ms --duration 1s --iterations 1 --batch-interval 20ms --logical-packets-per-batch 4 --batch-payload-sizes 64,256 --batch-groups 2 --per-client-mbps 0.2 --workers 1
resource-10-8k-200ms|resource-pack-transfer --clients 10 --warmup 0ms --duration 1s --iterations 1 --chunk-size 8192 --chunk-interval 200ms --workers 1
CASES
}

case_list_pilot() {
  cat <<'CASES'
pilot-curve-1c-mtu|bandwidth-latency-curve --clients 1 --warmup 1s --duration 5s --iterations 3 --payload-size 1200 --rates-mbps 50,100,250 --workers 1
pilot-fanout-100x5|multi-client-fanout --clients 100 --warmup 1s --duration 5s --iterations 3 --payload-size 512 --per-client-mbps 5 --workers 1
pilot-fairness-100-10poor|fairness --clients 100 --impaired-clients 10 --warmup 1s --duration 6s --iterations 3 --payload-size 512 --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5 --workers 1
pilot-disappear-100-blackhole|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 2s --disappear-mode blackhole --warmup 1s --duration 6s --iterations 3 --payload-size 512 --per-client-mbps 5 --workers 1
pilot-batch-100-20ms|batched-game-traffic --clients 100 --warmup 1s --duration 5s --iterations 3 --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5 --workers 1
pilot-resource-100-8k-200ms|resource-pack-transfer --clients 100 --warmup 1s --duration 5s --iterations 3 --chunk-size 8192 --chunk-interval 200ms --workers 1
CASES
}

case_list_local() {
  cat <<'CASES'
bestcase-1c-small|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 64 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-threshold256|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 256 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-medium|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 512 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-mtu1200|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-mtu1340|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 1340 --rates-mbps 100,250,500,1000,unlimited
bestcase-1c-mtu1400|bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 1400 --rates-mbps 100,250,500,1000,unlimited
fanout-20x5|multi-client-fanout --clients 20 --warmup 2s --duration 10s --iterations 3 --payload-size 512 --per-client-mbps 5
fanout-100x5|multi-client-fanout --clients 100 --warmup 2s --duration 10s --iterations 3 --payload-size 512 --per-client-mbps 5
fairness-100-10poor|fairness --clients 100 --impaired-clients 10 --warmup 2s --duration 15s --iterations 3 --payload-size 512 --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5
disappear-100-close|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode close --warmup 2s --duration 15s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-stopread|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode stop-reading --warmup 2s --duration 15s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-blackhole|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode blackhole --warmup 2s --duration 15s --iterations 3 --payload-size 512 --per-client-mbps 5
batch-100-20ms|batched-game-traffic --clients 100 --warmup 2s --duration 10s --iterations 3 --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
resource-100-8k-200ms|resource-pack-transfer --clients 100 --warmup 2s --duration 10s --iterations 3 --chunk-size 8192 --chunk-interval 200ms
CASES
}

case_list_lab() {
  cat <<'CASES'
bestcase-1c-small|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 64 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-threshold256|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 256 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-medium|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 512 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-mtu1200|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-mtu1340|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1340 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-mtu1400|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1400 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
bestcase-1c-split|bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 262144 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited
fanout-100x5|multi-client-fanout --clients 100 --warmup 5s --duration 30s --iterations 3 --payload-size 512 --per-client-mbps 5
fanout-500x5|multi-client-fanout --clients 500 --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
fairness-100-10poor|fairness --clients 100 --impaired-clients 10 --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5
disappear-100-close|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode close --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-stopread|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode stop-reading --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
disappear-100-blackhole|disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5
batch-100-10ms|batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 10ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
batch-100-20ms|batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
batch-100-50ms|batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 50ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5
resource-100-8k-200ms|resource-pack-transfer --clients 100 --warmup 10s --duration 60s --iterations 3 --chunk-size 8192 --chunk-interval 200ms
resource-100-256k-200ms|resource-pack-transfer --clients 100 --warmup 10s --duration 60s --iterations 3 --chunk-size 262144 --chunk-interval 200ms
CASES
}

run_case() {
  local name="$1"
  local args="$2"
  local run_args="$args --out $output_root --run-id $name"
  if [[ -n "$common_args" ]]; then
    run_args="$run_args $common_args"
  fi

  local artifact="$output_root/$name"
  local started
  local ended
  local status="passed"
  local command_text
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  command_text="$(command_line "$gradle" ":benchmark:raknetBenchmark" "-PbenchmarkArgs=$run_args")"

  echo
  echo "==> $name"
  echo "$command_text"

  if "$dry_run"; then
    status="dry-run"
  else
    if ! "$gradle" ":benchmark:raknetBenchmark" "-PbenchmarkArgs=$run_args"; then
      status="failed"
    fi
  fi

  ended="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  record_manifest "$name" "$status" "$run_args" "$command_text" "$artifact" "$started" "$ended"
  append_report_row "$name" "$status" "$artifact"
  if [[ "$status" == "passed" ]]; then
    append_case_metrics "$name" "$artifact"
  fi

  if [[ "$status" == "failed" && "$continue_on_error" == "false" ]]; then
    echo "Benchmark case failed: $name" >&2
    exit 1
  fi
}

write_report_header
cd "$repo_root"

case "$profile" in
  smoke)
    cases="$(case_list_smoke)"
    ;;
  pilot)
    cases="$(case_list_pilot)"
    ;;
  local)
    cases="$(case_list_local)"
    ;;
  lab)
    cases="$(case_list_lab)"
    ;;
esac

selected=0
while IFS='|' read -r name args; do
  [[ -z "$name" ]] && continue
  if [[ -n "$only_pattern" && "$name" != *"$only_pattern"* ]]; then
    continue
  fi
  selected=$((selected + 1))
  run_case "$name" "$args"
done <<<"$cases"

if [[ "$selected" -eq 0 ]]; then
  echo "No cases selected for profile '$profile' and filter '$only_pattern'" >&2
  exit 2
fi

write_suite_aggregates

if [[ -s "$suite_aggregate_jsonl" && -x "$script_dir/select-stable-bandwidth.sh" ]]; then
  "$script_dir/select-stable-bandwidth.sh" --input "$suite_aggregate_jsonl" --out "$output_root" >/dev/null
  {
    echo
    echo "## Stable Bandwidth Capacity"
    echo
    echo "- Capacity JSONL: \`$output_root/bandwidth-capacity.jsonl\`"
    echo "- Capacity CSV: \`$output_root/bandwidth-capacity.csv\`"
    echo "- Capacity report: \`$output_root/bandwidth-capacity.md\`"
  } >>"$report"
fi

echo
echo "Baseline suite artifacts: $output_root"
echo "Manifest: $manifest"
echo "Suite summary JSONL: $suite_summary_jsonl"
echo "Suite summary CSV: $suite_summary_csv"
echo "Suite aggregate JSONL: $suite_aggregate_jsonl"
echo "Suite aggregate CSV: $suite_aggregate_csv"
echo "Report: $report"
