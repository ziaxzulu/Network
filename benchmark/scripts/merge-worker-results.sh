#!/usr/bin/env bash
set -euo pipefail

server_path=""
receivers=()
output_root=""
case_name=""
benchmark_name=""
external_impairment_latency_ms=""
external_impairment_jitter_ms=""
external_impairment_loss_percent=""
external_netem_limit_packets=""
external_blackhole_at_epoch_ms=""
external_recovery_at_epoch_ms=""
netem_evidence_dir=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/merge-worker-results.sh --server DIR|summary.json --receiver DIR|summary.json [--receiver ...] --out DIR [options]

Options:
  --server PATH       Server-worker artifact directory or summary.json.
  --receiver PATH     Receiver-worker artifact directory or summary.json. May be repeated.
  --out DIR           Output directory for merged lab artifacts.
  --case NAME         Case name for suite-aggregate.jsonl. Default: server runId.
  --benchmark-name    Benchmark name for suite-aggregate.jsonl. Default: server scenario.
  --external-impairment-latency-ms N
                      Override merged impairment latency for an external qdisc run.
  --external-impairment-jitter-ms N
                      Override merged impairment jitter for an external qdisc run.
  --external-impairment-loss-percent N
                      Override merged impairment loss for an external qdisc run.
  --external-netem-limit-packets N
                      Record the external netem queue limit in packets.
  --external-blackhole-at-epoch-ms N
                      Record a timed external 100% loss event.
  --external-recovery-at-epoch-ms N
                      Record when the externally blackholed path was restored.
  --netem-evidence DIR
                      Directory containing qdisc apply/status evidence.
  --help              Show this help.

Outputs:
  lab-summary.json       Full merged server/receiver worker summary.
  lab-summary.csv        Single-row CSV summary for spreadsheets.
  suite-aggregate.jsonl  Aggregate row compatible with compare-baseline-suite.sh.
  README.md              Human-readable report.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server)
      server_path="$2"
      shift 2
      ;;
    --receiver)
      receivers+=("$2")
      shift 2
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --case)
      case_name="$2"
      shift 2
      ;;
    --benchmark-name)
      benchmark_name="$2"
      shift 2
      ;;
    --external-impairment-latency-ms)
      external_impairment_latency_ms="$2"
      shift 2
      ;;
    --external-impairment-jitter-ms)
      external_impairment_jitter_ms="$2"
      shift 2
      ;;
    --external-impairment-loss-percent)
      external_impairment_loss_percent="$2"
      shift 2
      ;;
    --external-netem-limit-packets)
      external_netem_limit_packets="$2"
      shift 2
      ;;
    --external-blackhole-at-epoch-ms)
      external_blackhole_at_epoch_ms="$2"
      shift 2
      ;;
    --external-recovery-at-epoch-ms)
      external_recovery_at_epoch_ms="$2"
      shift 2
      ;;
    --netem-evidence)
      netem_evidence_dir="$2"
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

if [[ -z "$server_path" || "${#receivers[@]}" -eq 0 || -z "$output_root" ]]; then
  usage >&2
  exit 2
fi

external_impairment_count=0
for value in "$external_impairment_latency_ms" "$external_impairment_jitter_ms" "$external_impairment_loss_percent"; do
  [[ -n "$value" ]] && external_impairment_count=$((external_impairment_count + 1))
done
if [[ "$external_impairment_count" -ne 0 && "$external_impairment_count" -ne 3 ]]; then
  echo "External impairment latency, jitter, and loss overrides must be supplied together" >&2
  exit 2
fi
if [[ "$external_impairment_count" -eq 3 ]]; then
  if ! [[ "$external_impairment_latency_ms" =~ ^[0-9]+$ && "$external_impairment_jitter_ms" =~ ^[0-9]+$ && "$external_impairment_loss_percent" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "External impairment latency/jitter must be integer milliseconds and loss must be numeric" >&2
    exit 2
  fi
fi
if [[ -n "$external_blackhole_at_epoch_ms" ]] && ! [[ "$external_blackhole_at_epoch_ms" =~ ^[0-9]+$ && "$external_blackhole_at_epoch_ms" -gt 0 ]]; then
  echo "External blackhole epoch must be a positive integer" >&2
  exit 2
fi
if [[ -n "$external_recovery_at_epoch_ms" ]] && ! [[ "$external_recovery_at_epoch_ms" =~ ^[0-9]+$ && "$external_recovery_at_epoch_ms" -gt 0 ]]; then
  echo "External recovery epoch must be a positive integer" >&2
  exit 2
fi
if [[ -n "$external_recovery_at_epoch_ms" && -z "$external_blackhole_at_epoch_ms" ]]; then
  echo "External recovery requires a timed external blackhole" >&2
  exit 2
fi
if [[ -n "$external_recovery_at_epoch_ms" && "$external_recovery_at_epoch_ms" -le "$external_blackhole_at_epoch_ms" ]]; then
  echo "External recovery epoch must follow the external blackhole epoch" >&2
  exit 2
fi
if [[ -n "$external_netem_limit_packets" ]] && ! [[ "$external_netem_limit_packets" =~ ^[0-9]+$ && "$external_netem_limit_packets" -gt 0 ]]; then
  echo "External netem limit must be a positive packet count" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to merge worker benchmark summaries" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

resolve_summary() {
  local path="$1"
  if [[ -d "$path" ]]; then
    path="$path/summary.json"
  fi
  if [[ ! -f "$path" ]]; then
    echo "summary.json not found: $path" >&2
    exit 2
  fi
  printf '%s\n' "$path"
}

if [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi
mkdir -p "$output_root"

server_summary="$(resolve_summary "$server_path")"
receiver_summaries=()
for receiver in "${receivers[@]}"; do
  receiver_summaries+=("$(resolve_summary "$receiver")")
done
receiver_paths_json="$(printf '%s\n' "${receiver_summaries[@]}" | jq -R . | jq -s .)"

if [[ -z "$case_name" ]]; then
  case_name="$(jq -r '.runId // "remote-worker-merge"' "$server_summary")"
fi

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
lab_summary="$output_root/lab-summary.json"
lab_csv="$output_root/lab-summary.csv"
suite_aggregate="$output_root/suite-aggregate.jsonl"
report="$output_root/README.md"
external_impairment_latency_json="${external_impairment_latency_ms:-null}"
external_impairment_jitter_json="${external_impairment_jitter_ms:-null}"
external_impairment_loss_json="${external_impairment_loss_percent:-null}"
external_netem_limit_json="${external_netem_limit_packets:-null}"
external_blackhole_at_epoch_json="${external_blackhole_at_epoch_ms:-null}"
external_recovery_at_epoch_json="${external_recovery_at_epoch_ms:-null}"

jq -s \
  --arg generatedAt "$generated_at" \
  --arg caseName "$case_name" \
  --arg benchmarkName "$benchmark_name" \
  --arg serverPath "$server_summary" \
  --argjson receiverPaths "$receiver_paths_json" \
  --argjson externalImpairmentLatencyMillis "$external_impairment_latency_json" \
  --argjson externalImpairmentJitterMillis "$external_impairment_jitter_json" \
  --argjson externalImpairmentLossPercent "$external_impairment_loss_json" \
  --argjson externalNetemLimitPackets "$external_netem_limit_json" \
  --argjson externalBlackholeAtEpochMillis "$external_blackhole_at_epoch_json" \
  --argjson externalRecoveryAtEpochMillis "$external_recovery_at_epoch_json" \
  --argjson minimumProbeResponsesPerIteration 10 \
  --argjson minimumProbeResponseRate 0.5 \
  --arg expectedProbeSemantics "RELIABLE_ORDERED/HIGH through the weighted scheduler; RTT includes ordering and loss recovery" \
  --arg netemEvidenceDir "$netem_evidence_dir" \
  --arg outputRoot "$output_root" '
  def median:
    if length == 0 then 0
    else sort as $s | $s[((length - 1) / 2 | floor)]
    end;

  def median_or_null:
    if length == 0 then null
    else sort as $s | $s[((length - 1) / 2 | floor)]
    end;

  def percentile($p):
    if length == 0 then 0
    else
      sort as $s |
      (($p / 100) * ($s | length) | ceil - 1) as $raw |
      ($raw | if . < 0 then 0 elif . >= ($s | length) then (($s | length) - 1) else . end) as $idx |
      $s[$idx]
    end;

  def sum_or_zero: if length == 0 then 0 else add end;
  def max_or_zero: if length == 0 then 0 else max end;
  def max0($value): if ($value // 0) < 0 then 0 else ($value // 0) end;
  def ratio($num; $den): if ($den // 0) == 0 then 0 else (($num // 0) / $den) end;
  def gbps($bytes; $elapsed_ms): if ($elapsed_ms // 0) <= 0 then 0 else (($bytes // 0) * 8 / ($elapsed_ms * 1000000)) end;
  def mbps($bytes; $elapsed_ms): if ($elapsed_ms // 0) <= 0 then 0 else (($bytes // 0) * 8 / ($elapsed_ms * 1000)) end;
  def per_second($count; $elapsed_ms): if ($elapsed_ms // 0) <= 0 then 0 else (($count // 0) * 1000 / $elapsed_ms) end;
  def spread_pct($values):
    ($values | map(. // 0) | sort) as $s |
    if ($s | length) < 2 then 0
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

  def spread_pct_or_null($values):
    if ($values | length) == 0 then null else spread_pct($values) end;

  def fairness($values):
    if ($values | length) == 0 then 1
    else
      ($values | map((. // 0) | if . < 0 then 0 else . end)) as $v |
      ($v | add) as $sum |
      ($v | map(. * .) | add) as $sum_squares |
      if $sum_squares == 0 then 1 else (($sum * $sum) / (($v | length) * $sum_squares)) end
    end;

  def elapsed_by_iteration($iterations):
    ($iterations | sort_by(.iteration // 1) | group_by(.iteration // 1) | map(map(.elapsedMillis // 0) | max_or_zero) | sum_or_zero);

  .[0] as $server |
  .[1:] as $receivers |
  ([ $server ] + $receivers) as $probe_transport_sources |
  ($probe_transport_sources | map(.recoveryMode // null)) as $recovery_modes |
  (($recovery_modes | all(. == "legacy" or . == "bounded" or . == "model_based"))
    and (($recovery_modes | unique | length) == 1)) as $recovery_mode_provenance_valid |
  ($probe_transport_sources | all(
    (.probeReliability // null) == "RELIABLE_ORDERED"
    and (.probePriority // null) == "HIGH"
    and (.probeSemantics // null) == $expectedProbeSemantics
  )) as $probe_transport_provenance_valid |
  ($server.iterations // []) as $server_iterations |
  ([$receivers[] | (.iterations // [])[]] ) as $receiver_iterations |
  ([$receivers[] | .runId // "receiver"] ) as $receiver_run_ids |
  ($server_iterations | length) as $server_iteration_count |
  ($receivers | map((.iterations // []) | length)) as $receiver_iteration_counts |
  ([$server.startAtEpochMillis // 0] + [$receivers[] | (.startAtEpochMillis // 0)] | unique) as $start_at_epoch_values |
  (elapsed_by_iteration($receiver_iterations)) as $receiver_elapsed_ms |
  (elapsed_by_iteration($server_iterations)) as $server_elapsed_ms |
  (
    $receiver_iterations |
    sort_by(.iteration // 1) |
    group_by(.iteration // 1) |
    map({
      bytes: (map(.bulkReceivedBytes // 0) | sum_or_zero),
      elapsedMillis: (map(.elapsedMillis // 0) | max_or_zero)
    } | gbps(.bytes; .elapsedMillis))
  ) as $receiver_iteration_gbps |
  ($server_iterations | map(.probeRttP99Millis | select(type == "number"))) as $server_p99_values |
  ($server_iterations | map(.probeRttP95Millis | select(type == "number"))) as $server_p95_values |
  (($server_p99_values | length) == $server_iteration_count and $server_iteration_count > 0) as $server_p99_complete |
  (($server_p95_values | length) == $server_iteration_count and $server_iteration_count > 0) as $server_p95_complete |
  ($server_iterations | map(.probesSent // 0) | sum_or_zero) as $server_probes_sent |
  ($server_iterations | map(.probesAcked // 0) | sum_or_zero) as $server_probes_acked |
  ($server_iterations | map(.probeAckSpillover | select(type == "number" and . >= 0 and floor == .))) as $server_probe_ack_spillover_values |
  (($server_probe_ack_spillover_values | length) == $server_iteration_count and $server_iteration_count > 0) as $server_probe_ack_spillover_complete |
  (if $server_probe_ack_spillover_complete then ($server_probe_ack_spillover_values | sum_or_zero) else null end) as $server_probe_ack_spillover |
  ($server_iterations | map(.probeRttCount // 0) | if length == 0 then 0 else min end) as $minimum_probe_responses |
  ($server_iterations | map(.probeResponseRate | select(type == "number"))) as $server_probe_response_rates |
  (($server_probe_response_rates | length) == $server_iteration_count and $server_iteration_count > 0) as $server_probe_rates_complete |
  (if $server_probe_rates_complete then ($server_probe_response_rates | min) else null end) as $minimum_probe_response_rate |
  (if $server_probes_sent <= 0 then null
   else ([ $server_probes_sent, $server_probes_acked ] | min) / $server_probes_sent
   end) as $probe_response_rate |
  (spread_pct($receiver_iteration_gbps)) as $delivered_gbps_spread_pct |
  (if $server_p99_complete then spread_pct_or_null($server_p99_values) else null end) as $probe_p99_spread_pct |
  ($receiver_iteration_gbps | max_or_zero) as $max_receiver_gbps |
  (
    []
    + (if $server_iteration_count < 3 then ["insufficient-iterations"] else [] end)
    + (if $delivered_gbps_spread_pct > 10 then ["throughput-spread"] else [] end)
    + (if $server_p99_complete | not then ["missing-probe-p99"] else [] end)
    + (if $probe_transport_provenance_valid then [] else ["invalid-probe-transport-provenance"] end)
    + (if $recovery_mode_provenance_valid then [] else ["invalid-recovery-mode-provenance"] end)
    + (if ($server_probe_ack_spillover_complete | not) then ["invalid-probe-ack-spillover"] else [] end)
    + (if $server_probe_ack_spillover != null and $server_probe_ack_spillover > 0 then ["probe-ack-spillover"] else [] end)
    + (if $minimum_probe_responses < $minimumProbeResponsesPerIteration then ["insufficient-probe-responses"] else [] end)
    + (if (($minimum_probe_response_rate == null) or ($minimum_probe_response_rate < $minimumProbeResponseRate)) then ["insufficient-probe-return-rate"] else [] end)
    + (if $probe_p99_spread_pct != null and $probe_p99_spread_pct > 10 then ["p99-spread"] else [] end)
    + (if $max_receiver_gbps <= 0 then ["zero-delivery"] else [] end)
  ) as $unstable_reasons |
  ($receiver_iterations | map(.bulkReceivedBytes // 0) | sum_or_zero) as $receiver_bytes |
  ($receiver_iterations | map(.bulkReceivedMessages // 0) | sum_or_zero) as $receiver_messages |
  ($receiver_iterations | map(.logicalPacketsReceived // 0) | sum_or_zero) as $receiver_logical_packets |
  ($receivers | map((.iterations // []) | map(.clients // 0) | max_or_zero) | sum_or_zero) as $receiver_clients |
  ($receivers | map((.iterations // []) | map(.affectedClients // 0) | max_or_zero) | sum_or_zero) as $receiver_affected_clients |
  ($server_iterations | map(.serverBytesOut // 0) | sum_or_zero) as $server_bytes_out |
  ($server_iterations | map(.healthyServerBytesOut // 0) | sum_or_zero) as $server_healthy_bytes_out |
  ($server_iterations | map(.affectedServerBytesOut // 0) | sum_or_zero) as $server_affected_bytes_out |
  ($server_iterations | map(.serverDatagramsOut // 0) | sum_or_zero) as $server_datagrams_out |
  ($server_iterations | map(.healthyServerDatagramsOut // 0) | sum_or_zero) as $server_healthy_datagrams_out |
  ($server_iterations | map(.affectedServerDatagramsOut // 0) | sum_or_zero) as $server_affected_datagrams_out |
  ($server_iterations | map(.staleDatagrams // 0) | sum_or_zero) as $server_stale_datagrams |
  ($server_iterations | map(.nackIn // 0) | sum_or_zero) as $server_nack_in |
  ($server_iterations | map(.nackOut // 0) | sum_or_zero) as $server_nack_out |
  ($server_iterations | map(.disconnects // 0) | sum_or_zero) as $server_disconnects |
  ($server_iterations | map(.blackholedDatagramsIn // 0) | sum_or_zero) as $server_blackholed_in |
  ($server_iterations | map(.blackholedDatagramsOut // 0) | sum_or_zero) as $server_blackholed_out |
  ($receiver_iterations | map(.blackholedDatagramsIn // 0) | sum_or_zero) as $receiver_blackholed_in |
  ($receiver_iterations | map(.blackholedDatagramsOut // 0) | sum_or_zero) as $receiver_blackholed_out |
  (
    [
      $receivers[] as $receiver |
      ($receiver.iterations // [])[] as $iteration |
      ($iteration.peers // [])[] |
      {
        receiverRunId: ($receiver.runId // "receiver"),
        id: (.id // 0),
        iteration: ($iteration.iteration // 1),
        impaired: (.impaired // false),
        bytes: (.bulkReceivedBytes // 0),
        elapsedMillis: ($iteration.elapsedMillis // 0)
      }
    ]
  ) as $receiver_peers |
  (
    $receiver_peers |
    sort_by(.receiverRunId, .id) |
    group_by([.receiverRunId, .id]) |
    map({
      receiverRunId: .[0].receiverRunId,
      id: .[0].id,
      impaired: (map(.impaired) | any),
      bytes: (map(.bytes) | sum_or_zero),
      elapsedMillis: (map(.elapsedMillis) | sum_or_zero)
    })
  ) as $receiver_clients_rollup |
  ($receiver_clients_rollup | map(.bytes)) as $receiver_peer_bytes |
  ($receiver_clients_rollup | map(select(.impaired | not) | .bytes)) as $healthy_peer_bytes |
  ($receiver_clients_rollup | map(select(.impaired) | .bytes)) as $affected_peer_bytes |
  ($receiver_clients_rollup | map(mbps(.bytes; .elapsedMillis))) as $receiver_peer_mbps |
  ($receiver_clients_rollup | map(select(.impaired | not) | mbps(.bytes; .elapsedMillis))) as $healthy_peer_mbps |
  ($receiver_clients_rollup | map(select(.impaired) | mbps(.bytes; .elapsedMillis))) as $affected_peer_mbps |
  ($receiver_peer_mbps | percentile(50)) as $client_mbps_p50 |
  ($receiver_peer_mbps | percentile(95)) as $client_mbps_p95 |
  ($receiver_peer_mbps | percentile(99)) as $client_mbps_p99 |
  ($healthy_peer_mbps | percentile(50)) as $healthy_client_mbps_p50 |
  ($healthy_peer_mbps | percentile(99)) as $healthy_client_mbps_p99 |
  ($affected_peer_mbps | percentile(50)) as $affected_client_mbps_p50 |
  ($affected_peer_mbps | percentile(99)) as $affected_client_mbps_p99 |
  ($healthy_peer_bytes | sum_or_zero) as $healthy_receiver_bytes |
  ($affected_peer_bytes | sum_or_zero) as $affected_receiver_bytes |
  (gbps($receiver_bytes; $receiver_elapsed_ms)) as $receiver_delivered_gbps |
  (gbps($healthy_receiver_bytes; $receiver_elapsed_ms)) as $healthy_delivered_gbps |
  (gbps($affected_receiver_bytes; $receiver_elapsed_ms)) as $affected_delivered_gbps |
  (max0($server_bytes_out - $receiver_bytes)) as $undelivered_server_bytes_out |
  (max0($server_healthy_bytes_out - $healthy_receiver_bytes)) as $healthy_undelivered_server_bytes_out |
  (max0($server_affected_bytes_out - $affected_receiver_bytes)) as $affected_undelivered_server_bytes_out |
  (ratio($server_bytes_out; $receiver_bytes)) as $send_deliver_ratio |
  (ratio($server_healthy_bytes_out; $healthy_receiver_bytes)) as $healthy_send_deliver_ratio |
  (ratio($server_affected_bytes_out; $affected_receiver_bytes)) as $affected_send_deliver_ratio |
  ($externalImpairmentLatencyMillis // ($server.impairmentLatencyMillis // 0)) as $impairment_latency_ms |
  ($externalImpairmentJitterMillis // ($server.impairmentJitterMillis // 0)) as $impairment_jitter_ms |
  ($externalImpairmentLossPercent // ($server.impairmentLossPercent // 0)) as $impairment_loss_percent |
  (
    {
      summaryKind: "aggregate",
      case: $caseName,
      benchmarkName: (if $benchmarkName == "" then ($server.scenario // "server-worker") else $benchmarkName end),
      iteration: "aggregate",
      measuredIterations: $server_iteration_count,
      receiverWorkers: ($receivers | length),
      receiverRunIds: $receiver_run_ids,
      clients: $receiver_clients,
      serverConnectedClients: ($server_iterations | map(.clients // 0) | max_or_zero),
      receiverClients: $receiver_clients,
      payloadSize: ($server_iterations[0].payloadSize // 0),
      reliability: ($server_iterations[0].reliability // "unknown"),
      recoveryModeProvenanceValid: $recovery_mode_provenance_valid,
      recoveryMode: (if $recovery_mode_provenance_valid then $recovery_modes[0] else null end),
      probeTransportProvenanceValid: $probe_transport_provenance_valid,
      probeReliability: (if $probe_transport_provenance_valid then $server.probeReliability else null end),
      probePriority: (if $probe_transport_provenance_valid then $server.probePriority else null end),
      probeSemantics: (if $probe_transport_provenance_valid then $server.probeSemantics else null end),
      minimumProbeResponsesPerIteration: $minimumProbeResponsesPerIteration,
      minimumProbeResponseRateRequired: $minimumProbeResponseRate,
      batched: ($server_iterations[0].batched // false),
      batchIntervalMillis: ($server_iterations[0].batchIntervalMillis // $server.batchIntervalMillis // 0),
      logicalPacketsPerBatch: ($server_iterations[0].logicalPacketsPerBatch // $server.logicalPacketsPerBatch // 1),
      batchGroups: ($server_iterations[0].batchGroups // $server.batchGroups // 1),
      packetLimit: ($server.packetLimit // null),
      globalPacketLimit: ($server.globalPacketLimit // null),
	      configuredMaxQueuedBytes: ($server.configuredMaxQueuedBytes // null),
	      resourceSafetyMaxAggregateQueuedBytes: ($server.resourceSafetyMaxAggregateQueuedBytes // null),
	      resourceSafetyMaxDirectMemoryUsedBytes: ($server.resourceSafetyMaxDirectMemoryUsedBytes // null),
	      resourceSafetyStatus: ($server.resourceSafetyStatus // null),
	      targetMbps: ($server_iterations[0].targetMbps // 0),
	      targetClientMbps: ($server_iterations[0].targetClientMbps // 0),
	      disappearanceMode: ($server_iterations[0].disappearanceMode // $server.disappearanceMode // null),
	      startAtEpochMillis: ($server.startAtEpochMillis // 0),
      impairmentProfile: (
        if $externalRecoveryAtEpochMillis != null then "external-blackhole-transition"
        elif $externalBlackholeAtEpochMillis != null then "external-blackhole"
        else (($impairment_latency_ms | tostring) + "ms/" + ($impairment_jitter_ms | tostring) + "ms/" + ($impairment_loss_percent | tostring) + "%")
        end
      ),
      impairmentLatencyMillis: $impairment_latency_ms,
      impairmentJitterMillis: $impairment_jitter_ms,
      impairmentLossPercent: $impairment_loss_percent,
      externalImpairment: ($externalImpairmentLatencyMillis != null or $externalBlackholeAtEpochMillis != null),
      externalBlackhole: ($externalBlackholeAtEpochMillis != null),
      externalBlackholeAtEpochMillis: $externalBlackholeAtEpochMillis,
      externalRecovery: ($externalRecoveryAtEpochMillis != null),
      externalRecoveryAtEpochMillis: $externalRecoveryAtEpochMillis,
      externalNetemLimitPackets: $externalNetemLimitPackets,
      netemEvidenceDir: (if $netemEvidenceDir == "" then null else $netemEvidenceDir end),
      elapsedMillis: $receiver_elapsed_ms,
      serverElapsedMillis: $server_elapsed_ms,
      offeredGbps: ($server_iterations | map(.offeredGbps // 0) | median),
      deliveredGbps: $receiver_delivered_gbps,
      healthyDeliveredGbps: $healthy_delivered_gbps,
      affectedDeliveredGbps: $affected_delivered_gbps,
      serverBytesOut: $server_bytes_out,
      serverDatagramsOut: $server_datagrams_out,
      serverDatagramsOutPerSecond: per_second($server_datagrams_out; $server_elapsed_ms),
      healthyServerDatagramsOutPerSecond: per_second($server_healthy_datagrams_out; $server_elapsed_ms),
      affectedServerDatagramsOutPerSecond: per_second($server_affected_datagrams_out; $server_elapsed_ms),
      undeliveredServerBytesOut: $undelivered_server_bytes_out,
      undeliveredServerGbps: gbps($undelivered_server_bytes_out; $server_elapsed_ms),
      healthyUndeliveredServerBytesOut: $healthy_undelivered_server_bytes_out,
      healthyUndeliveredServerGbps: gbps($healthy_undelivered_server_bytes_out; $server_elapsed_ms),
      affectedUndeliveredServerBytesOut: $affected_undelivered_server_bytes_out,
      affectedUndeliveredServerGbps: gbps($affected_undelivered_server_bytes_out; $server_elapsed_ms),
      sentToDeliveredBytesRatio: $send_deliver_ratio,
      healthySentToDeliveredBytesRatio: $healthy_send_deliver_ratio,
      affectedSentToDeliveredBytesRatio: $affected_send_deliver_ratio,
      receiverBulkReceivedBytes: $receiver_bytes,
      receiverBulkReceivedMessages: $receiver_messages,
      receiverLogicalPacketsReceived: $receiver_logical_packets,
      deliveredMessagesPerSecond: (if $receiver_elapsed_ms <= 0 then 0 else ($receiver_messages * 1000 / $receiver_elapsed_ms) end),
      deliveredLogicalPacketsPerSecond: (if $receiver_elapsed_ms <= 0 then 0 else ($receiver_logical_packets * 1000 / $receiver_elapsed_ms) end),
      clientMbpsP50: $client_mbps_p50,
      clientMbpsP95: $client_mbps_p95,
      clientMbpsP99: $client_mbps_p99,
      healthyClientMbpsP50: $healthy_client_mbps_p50,
      healthyClientMbpsP99: $healthy_client_mbps_p99,
      affectedClientMbpsP50: $affected_client_mbps_p50,
      affectedClientMbpsP99: $affected_client_mbps_p99,
      deliveredGbpsSpreadPct: $delivered_gbps_spread_pct,
      probesSent: $server_probes_sent,
      probesAcked: $server_probes_acked,
      probeAckSpillover: $server_probe_ack_spillover,
      probeResponseRate: $probe_response_rate,
      minimumProbeResponses: $minimum_probe_responses,
      minimumProbeResponseRate: $minimum_probe_response_rate,
      probeRttCount: ($server_iterations | map(.probeRttCount // 0) | sum_or_zero),
      probeRttP95Millis: (if $server_p95_complete then ($server_p95_values | median_or_null) else null end),
      probeRttP99Millis: (if $server_p99_complete then ($server_p99_values | median_or_null) else null end),
      probeRttP99MillisSpreadPct: $probe_p99_spread_pct,
      fairnessIndex: fairness($receiver_peer_bytes),
      healthyFairnessIndex: fairness($healthy_peer_bytes),
      affectedFairnessIndex: fairness($affected_peer_bytes),
      affectedClients: $receiver_affected_clients,
      disconnects: $server_disconnects,
      blackholedDatagramsIn: ($server_blackholed_in + $receiver_blackholed_in),
      blackholedDatagramsOut: ($server_blackholed_out + $receiver_blackholed_out),
      staleDatagrams: $server_stale_datagrams,
      staleDatagramsPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_stale_datagrams * 1000 / $server_elapsed_ms) end),
      nackIn: $server_nack_in,
      nackInPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_nack_in * 1000 / $server_elapsed_ms) end),
      nackOut: $server_nack_out,
      nackOutPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_nack_out * 1000 / $server_elapsed_ms) end),
      maxQueuedBytes: ($server_iterations | map(.maxQueuedBytes // 0) | max_or_zero),
      unstable: (($unstable_reasons | length) > 0),
      unstableReasons: $unstable_reasons,
      artifact: $outputRoot
    }
  ) as $aggregate |
  {
    generatedAt: $generatedAt,
    artifactKind: "remote-worker-merge",
    case: $caseName,
    serverSummary: $serverPath,
    receiverSummaries: $receiverPaths,
    externalImpairment:
      if $externalBlackholeAtEpochMillis != null then {
        kind: (if $externalRecoveryAtEpochMillis == null then "blackhole" else "blackhole-transition" end),
        latencyMillisBeforeBlackhole: $externalImpairmentLatencyMillis,
        jitterMillisBeforeBlackhole: $externalImpairmentJitterMillis,
        lossPercentBeforeBlackhole: $externalImpairmentLossPercent,
        lossPercent: 100,
        blackholeLossPercent: 100,
        blackholeAtEpochMillis: $externalBlackholeAtEpochMillis,
        recoveryAtEpochMillis: $externalRecoveryAtEpochMillis,
        limitPackets: $externalNetemLimitPackets,
        netemEvidenceDir: (if $netemEvidenceDir == "" then null else $netemEvidenceDir end)
      }
      elif $externalImpairmentLatencyMillis != null then {
        kind: "netem",
        latencyMillis: $externalImpairmentLatencyMillis,
        jitterMillis: $externalImpairmentJitterMillis,
        lossPercent: $externalImpairmentLossPercent,
        limitPackets: $externalNetemLimitPackets,
        netemEvidenceDir: (if $netemEvidenceDir == "" then null else $netemEvidenceDir end)
      }
      else null
      end,
    server: {
      runId: ($server.runId // null),
      scenario: ($server.scenario // null),
      role: ($server.role // null),
      recoveryMode: ($server.recoveryMode // null),
      iterations: ($server_iterations | length),
      connectedClients: ($aggregate.serverConnectedClients // 0),
      startAtEpochMillis: ($server.startAtEpochMillis // 0),
      resourceSafetyMaxAggregateQueuedBytes: ($server.resourceSafetyMaxAggregateQueuedBytes // null),
      resourceSafetyMaxDirectMemoryUsedBytes: ($server.resourceSafetyMaxDirectMemoryUsedBytes // null),
      resourceSafetyStatus: ($server.resourceSafetyStatus // null),
      probeReliability: ($server.probeReliability // null),
      probePriority: ($server.probePriority // null),
      probeSemantics: ($server.probeSemantics // null),
      gitRevision: ($server.environment.gitRevision // null),
      javaVersion: ($server.environment.javaVersion // null),
      osName: ($server.environment.osName // null)
    },
    receivers: [
      $receivers[] | {
        runId: (.runId // null),
        scenario: (.scenario // null),
        role: (.role // null),
        recoveryMode: (.recoveryMode // null),
        iterations: ((.iterations // []) | length),
        clients: ((.iterations // []) | map(.clients // 0) | max_or_zero),
        startAtEpochMillis: (.startAtEpochMillis // 0),
        probeReliability: (.probeReliability // null),
        probePriority: (.probePriority // null),
        probeSemantics: (.probeSemantics // null),
        gitRevision: (.environment.gitRevision // null),
        javaVersion: (.environment.javaVersion // null),
        osName: (.environment.osName // null)
      }
    ],
    aggregate: $aggregate,
    warnings:
      []
      + (if ($server.role // "") != "server" then ["server-summary-role-is-not-server"] else [] end)
      + (if ([$receivers[] | select((.role // "") != "client")] | length) > 0 then ["one-or-more-receiver-summaries-are-not-client-role"] else [] end)
      + (if ($aggregate.serverConnectedClients // 0) != ($aggregate.receiverClients // 0) then ["server-receiver-client-count-mismatch"] else [] end)
      + (if ($receiver_clients_rollup | length) != $receiver_clients then ["receiver-peer-client-count-mismatch"] else [] end)
      + (if ($receiver_iteration_counts | map(select(. != $server_iteration_count)) | length) > 0 then ["server-receiver-iteration-count-mismatch"] else [] end)
      + (if ($start_at_epoch_values | length) > 1 then ["server-receiver-start-at-epoch-mismatch"] else [] end)
      + (if (($receiver_iterations | length) == 0) then ["no-receiver-iterations"] else [] end)
      + (if (($server_iterations | length) == 0) then ["no-server-iterations"] else [] end)
  }
' "$server_summary" "${receiver_summaries[@]}" >"$lab_summary"

if ! jq -e '.aggregate.recoveryModeProvenanceValid == true' "$lab_summary" >/dev/null; then
  echo "Server and every receiver summary must declare one identical supported recoveryMode" >&2
  exit 1
fi

jq -c '.aggregate' "$lab_summary" >"$suite_aggregate"

{
  echo "case,benchmark_name,server_iterations,receiver_workers,server_connected_clients,receiver_clients,payload_size,reliability,recovery_mode,probe_reliability,probe_priority,probe_semantics,minimum_probe_responses_required,minimum_probe_response_rate_required,batched,batch_interval_ms,logical_packets_per_batch,batch_groups,target_mbps,target_client_mbps,disappearance_mode,start_at_epoch_ms,impairment_profile,external_impairment,external_blackhole_at_epoch_ms,external_recovery_at_epoch_ms,netem_limit_packets,netem_evidence_dir,delivered_gbps,healthy_delivered_gbps,affected_delivered_gbps,undelivered_server_gbps,healthy_undelivered_server_gbps,affected_undelivered_server_gbps,client_mbps_p50,client_mbps_p99,send_delivered_bytes_ratio,server_datagrams_out_s,healthy_server_datagrams_out_s,affected_server_datagrams_out_s,stale_datagrams_s,nack_out_s,probes_sent,probes_acked,probe_ack_spillover,probe_response_rate,minimum_probe_responses,minimum_probe_response_rate,probe_rtt_count,probe_p99_ms,max_queued_bytes,configured_max_queued_bytes,fairness,healthy_fairness,affected_fairness,warnings,artifact"
  jq -r '
    .aggregate as $a |
    [
      $a.case,
      $a.benchmarkName,
      $a.measuredIterations,
      $a.receiverWorkers,
      $a.serverConnectedClients,
      $a.receiverClients,
      $a.payloadSize,
      $a.reliability,
      $a.recoveryMode,
      $a.probeReliability,
      $a.probePriority,
      $a.probeSemantics,
      $a.minimumProbeResponsesPerIteration,
      $a.minimumProbeResponseRateRequired,
      $a.batched,
      $a.batchIntervalMillis,
      $a.logicalPacketsPerBatch,
      $a.batchGroups,
      $a.targetMbps,
      $a.targetClientMbps,
      $a.disappearanceMode,
      $a.startAtEpochMillis,
      $a.impairmentProfile,
      $a.externalImpairment,
      $a.externalBlackholeAtEpochMillis,
      $a.externalRecoveryAtEpochMillis,
      $a.externalNetemLimitPackets,
      $a.netemEvidenceDir,
      $a.deliveredGbps,
      $a.healthyDeliveredGbps,
      $a.affectedDeliveredGbps,
      $a.undeliveredServerGbps,
      $a.healthyUndeliveredServerGbps,
      $a.affectedUndeliveredServerGbps,
      $a.clientMbpsP50,
      $a.clientMbpsP99,
      $a.sentToDeliveredBytesRatio,
      $a.serverDatagramsOutPerSecond,
      $a.healthyServerDatagramsOutPerSecond,
      $a.affectedServerDatagramsOutPerSecond,
      $a.staleDatagramsPerSecond,
      $a.nackOutPerSecond,
      $a.probesSent,
      $a.probesAcked,
      $a.probeAckSpillover,
      $a.probeResponseRate,
      $a.minimumProbeResponses,
      $a.minimumProbeResponseRate,
      $a.probeRttCount,
      $a.probeRttP99Millis,
      $a.maxQueuedBytes,
      $a.configuredMaxQueuedBytes,
      $a.fairnessIndex,
      $a.healthyFairnessIndex,
      $a.affectedFairnessIndex,
      (.warnings | join(";")),
      $a.artifact
    ] | @csv
  ' "$lab_summary"
} >"$lab_csv"

{
  echo "# Remote Worker Benchmark Merge"
  echo
  jq -r '
    .aggregate as $a |
    "- Generated: `" + .generatedAt + "`\n" +
    "- Case: `" + .case + "`\n" +
    "- Server run: `" + (.server.runId // "unknown") + "` (`" + (.server.gitRevision // "unknown") + "`)\n" +
    "- Receiver runs: `" + (.receivers | map(.runId // "unknown") | join(", ")) + "`\n" +
    "- Start at epoch ms: `" + (($a.startAtEpochMillis // 0) | tostring) + "`\n" +
    "- Impairment: `" + ($a.impairmentProfile // "unknown") + "` (external qdisc: `" + (($a.externalImpairment // false) | tostring) + "`)\n" +
    "- Recovery mode: `" + ($a.recoveryMode // "unavailable") + "`\n" +
    "- Probe transport: `" + ($a.probeReliability // "unavailable") + "/" + ($a.probePriority // "unavailable") + "`\n" +
    "- Probe evidence gates: at least `" + (($a.minimumProbeResponsesPerIteration // 0) | tostring) + "` responses and `" + (($a.minimumProbeResponseRateRequired // 0) | tostring) + "` bounded return per iteration\n" +
    (if $a.externalBlackholeAtEpochMillis == null then "" else "- External blackhole at epoch ms: `" + ($a.externalBlackholeAtEpochMillis | tostring) + "`\n" end) +
    (if $a.externalRecoveryAtEpochMillis == null then "" else "- External recovery at epoch ms: `" + ($a.externalRecoveryAtEpochMillis | tostring) + "`\n" end) +
    (if $a.externalNetemLimitPackets == null then "" else "- Netem queue limit: `" + ($a.externalNetemLimitPackets | tostring) + " packets`\n" end) +
    (if $a.netemEvidenceDir == null then "" else "- Netem evidence: `" + $a.netemEvidenceDir + "`\n" end) +
    "- Warnings: `" + ((.warnings // []) | if length == 0 then "none" else join(",") end) + "`\n"
  ' "$lab_summary"
  echo "| Metric | Value |"
  echo "| --- | ---: |"
  jq -r '
    def fmt($value):
      if $value == null then "n/a"
      elif ($value | type) == "number" and $value != 0 and (($value | fabs) < 1) then (($value * 1000000 | round) / 1000000 | tostring)
      elif ($value | type) == "number" then (($value * 1000 | round) / 1000 | tostring)
      else ($value | tostring)
      end;
    .aggregate as $a |
    [
      ["Server connected clients", $a.serverConnectedClients],
      ["Receiver clients", $a.receiverClients],
      ["Receiver delivered Gbps", fmt($a.deliveredGbps)],
      ["Healthy delivered Gbps", fmt($a.healthyDeliveredGbps)],
      ["Affected delivered Gbps", fmt($a.affectedDeliveredGbps)],
      ["Undelivered server Gbps", fmt($a.undeliveredServerGbps)],
      ["Healthy undelivered server Gbps", fmt($a.healthyUndeliveredServerGbps)],
      ["Affected undelivered server Gbps", fmt($a.affectedUndeliveredServerGbps)],
      ["Client Mbps p50", fmt($a.clientMbpsP50)],
      ["Client Mbps p99", fmt($a.clientMbpsP99)],
      ["Send/deliver byte ratio", fmt($a.sentToDeliveredBytesRatio)],
      ["Server datagrams out/s", fmt($a.serverDatagramsOutPerSecond)],
      ["Healthy datagrams out/s", fmt($a.healthyServerDatagramsOutPerSecond)],
      ["Affected datagrams out/s", fmt($a.affectedServerDatagramsOutPerSecond)],
      ["Stale datagrams/s", fmt($a.staleDatagramsPerSecond)],
      ["NACK out/s", fmt($a.nackOutPerSecond)],
      ["Probes ACKed/sent", (($a.probesAcked // 0) | tostring) + "/" + (($a.probesSent // 0) | tostring)],
      ["Probe ACK spillover", $a.probeAckSpillover],
      ["Probe return", fmt($a.probeResponseRate)],
      ["Minimum probe responses", $a.minimumProbeResponses],
      ["Minimum probe return", fmt($a.minimumProbeResponseRate)],
      ["Probe RTT samples", $a.probeRttCount],
      ["Probe p99 ms", fmt($a.probeRttP99Millis)],
      ["Max queued bytes", $a.maxQueuedBytes],
      ["Fairness", fmt($a.fairnessIndex)],
      ["Healthy fairness", fmt($a.healthyFairnessIndex)],
      ["Affected fairness", fmt($a.affectedFairnessIndex)]
    ][] | "| " + .[0] + " | " + (.[1] | tostring) + " |"
  ' "$lab_summary"
  echo
  echo "## Artifacts"
  echo
  echo "- Full JSON: \`$lab_summary\`"
  echo "- CSV: \`$lab_csv\`"
  echo "- Comparable aggregate JSONL: \`$suite_aggregate\`"
} >"$report"

echo "Merged worker artifacts: $output_root"
echo "Lab summary JSON: $lab_summary"
echo "Lab summary CSV: $lab_csv"
echo "Suite aggregate JSONL: $suite_aggregate"
echo "Report: $report"
