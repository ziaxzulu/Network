#!/usr/bin/env bash
set -euo pipefail

server_path=""
receivers=()
output_root=""
case_name=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/merge-worker-results.sh --server DIR|summary.json --receiver DIR|summary.json [--receiver ...] --out DIR [options]

Options:
  --server PATH       Server-worker artifact directory or summary.json.
  --receiver PATH     Receiver-worker artifact directory or summary.json. May be repeated.
  --out DIR           Output directory for merged lab artifacts.
  --case NAME         Case name for suite-aggregate.jsonl. Default: server runId.
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

jq -s \
  --arg generatedAt "$generated_at" \
  --arg caseName "$case_name" \
  --arg serverPath "$server_summary" \
  --argjson receiverPaths "$receiver_paths_json" \
  --arg outputRoot "$output_root" '
  def median:
    if length == 0 then 0
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
  def ratio($num; $den): if ($den // 0) == 0 then 0 else (($num // 0) / $den) end;
  def gbps($bytes; $elapsed_ms): if ($elapsed_ms // 0) <= 0 then 0 else (($bytes // 0) * 8 / ($elapsed_ms * 1000000)) end;
  def mbps($bytes; $elapsed_ms): if ($elapsed_ms // 0) <= 0 then 0 else (($bytes // 0) * 8 / ($elapsed_ms * 1000)) end;

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
  ($server.iterations // []) as $server_iterations |
  ([$receivers[] | (.iterations // [])[]] ) as $receiver_iterations |
  ([$receivers[] | .runId // "receiver"] ) as $receiver_run_ids |
  (elapsed_by_iteration($receiver_iterations)) as $receiver_elapsed_ms |
  (elapsed_by_iteration($server_iterations)) as $server_elapsed_ms |
  ($receiver_iterations | map(.bulkReceivedBytes // 0) | sum_or_zero) as $receiver_bytes |
  ($receiver_iterations | map(.bulkReceivedMessages // 0) | sum_or_zero) as $receiver_messages |
  ($receiver_iterations | map(.logicalPacketsReceived // 0) | sum_or_zero) as $receiver_logical_packets |
  ($receivers | map((.iterations // []) | map(.clients // 0) | max_or_zero) | sum_or_zero) as $receiver_clients |
  ($receivers | map((.iterations // []) | map(.affectedClients // 0) | max_or_zero) | sum_or_zero) as $receiver_affected_clients |
  ($server_iterations | map(.serverBytesOut // 0) | sum_or_zero) as $server_bytes_out |
  ($server_iterations | map(.serverDatagramsOut // 0) | sum_or_zero) as $server_datagrams_out |
  ($server_iterations | map(.staleDatagrams // 0) | sum_or_zero) as $server_stale_datagrams |
  ($server_iterations | map(.nackIn // 0) | sum_or_zero) as $server_nack_in |
  ($server_iterations | map(.nackOut // 0) | sum_or_zero) as $server_nack_out |
  ($server_iterations | map(.disconnects // 0) | sum_or_zero) as $server_disconnects |
  ($server_iterations | map(.blackholedDatagramsIn // 0) | sum_or_zero) as $server_blackholed_in |
  ($server_iterations | map(.blackholedDatagramsOut // 0) | sum_or_zero) as $server_blackholed_out |
  (
    [
      $receivers[] as $receiver |
      ($receiver.iterations // [])[] as $iteration |
      ($iteration.peers // [])[] |
      {
        receiverRunId: ($receiver.runId // "receiver"),
        iteration: ($iteration.iteration // 1),
        impaired: (.impaired // false),
        bytes: (.bulkReceivedBytes // 0),
        elapsedMillis: ($iteration.elapsedMillis // 0)
      }
    ]
  ) as $receiver_peers |
  ($receiver_peers | map(.bytes)) as $receiver_peer_bytes |
  ($receiver_peers | map(select(.impaired | not) | .bytes)) as $healthy_peer_bytes |
  ($receiver_peers | map(select(.impaired) | .bytes)) as $affected_peer_bytes |
  ($receiver_peers | map(mbps(.bytes; .elapsedMillis))) as $receiver_peer_mbps |
  ($receiver_peers | map(select(.impaired | not) | mbps(.bytes; .elapsedMillis))) as $healthy_peer_mbps |
  ($receiver_peers | map(select(.impaired) | mbps(.bytes; .elapsedMillis))) as $affected_peer_mbps |
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
  (ratio($server_bytes_out; $receiver_bytes)) as $send_deliver_ratio |
  (ratio(($server_iterations | map(.healthyServerBytesOut // 0) | sum_or_zero); $healthy_receiver_bytes)) as $healthy_send_deliver_ratio |
  (ratio(($server_iterations | map(.affectedServerBytesOut // 0) | sum_or_zero); $affected_receiver_bytes)) as $affected_send_deliver_ratio |
  (
    {
      summaryKind: "aggregate",
      case: $caseName,
      benchmarkName: ($server.scenario // "server-worker"),
      iteration: "aggregate",
      measuredIterations: ($server_iterations | length),
      receiverWorkers: ($receivers | length),
      receiverRunIds: $receiver_run_ids,
      clients: $receiver_clients,
      serverConnectedClients: ($server_iterations | map(.clients // 0) | max_or_zero),
      receiverClients: $receiver_clients,
      payloadSize: ($server_iterations[0].payloadSize // 0),
      reliability: ($server_iterations[0].reliability // "unknown"),
      batched: ($server_iterations[0].batched // false),
      targetMbps: ($server_iterations[0].targetMbps // 0),
      targetClientMbps: ($server_iterations[0].targetClientMbps // 0),
      impairmentProfile: ((($server.impairmentLatencyMillis // 0) | tostring) + "ms/" + (($server.impairmentJitterMillis // 0) | tostring) + "ms/" + (($server.impairmentLossPercent // 0) | tostring) + "%"),
      impairmentLatencyMillis: ($server.impairmentLatencyMillis // 0),
      impairmentJitterMillis: ($server.impairmentJitterMillis // 0),
      impairmentLossPercent: ($server.impairmentLossPercent // 0),
      elapsedMillis: $receiver_elapsed_ms,
      serverElapsedMillis: $server_elapsed_ms,
      offeredGbps: ($server_iterations | map(.offeredGbps // 0) | median),
      deliveredGbps: $receiver_delivered_gbps,
      healthyDeliveredGbps: $healthy_delivered_gbps,
      affectedDeliveredGbps: $affected_delivered_gbps,
      serverBytesOut: $server_bytes_out,
      serverDatagramsOut: $server_datagrams_out,
      serverDatagramsOutPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_datagrams_out * 1000 / $server_elapsed_ms) end),
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
      deliveredGbpsSpreadPct: 0,
      probeRttP95Millis: ($server_iterations | map(.probeRttP95Millis // 0) | median),
      probeRttP99Millis: ($server_iterations | map(.probeRttP99Millis // 0) | median),
      probeRttP99MillisSpreadPct: 0,
      fairnessIndex: fairness($receiver_peer_bytes),
      healthyFairnessIndex: fairness($healthy_peer_bytes),
      affectedFairnessIndex: fairness($affected_peer_bytes),
      affectedClients: $receiver_affected_clients,
      disconnects: $server_disconnects,
      blackholedDatagramsIn: $server_blackholed_in,
      blackholedDatagramsOut: $server_blackholed_out,
      staleDatagrams: $server_stale_datagrams,
      staleDatagramsPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_stale_datagrams * 1000 / $server_elapsed_ms) end),
      nackIn: $server_nack_in,
      nackInPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_nack_in * 1000 / $server_elapsed_ms) end),
      nackOut: $server_nack_out,
      nackOutPerSecond: (if $server_elapsed_ms <= 0 then 0 else ($server_nack_out * 1000 / $server_elapsed_ms) end),
      maxQueuedBytes: ($server_iterations | map(.maxQueuedBytes // 0) | max_or_zero),
      unstable: false,
      unstableReasons: [],
      artifact: $outputRoot
    }
  ) as $aggregate |
  {
    generatedAt: $generatedAt,
    artifactKind: "remote-worker-merge",
    case: $caseName,
    serverSummary: $serverPath,
    receiverSummaries: $receiverPaths,
    server: {
      runId: ($server.runId // null),
      scenario: ($server.scenario // null),
      role: ($server.role // null),
      iterations: ($server_iterations | length),
      connectedClients: ($aggregate.serverConnectedClients // 0),
      gitRevision: ($server.environment.gitRevision // null),
      javaVersion: ($server.environment.javaVersion // null),
      osName: ($server.environment.osName // null)
    },
    receivers: [
      $receivers[] | {
        runId: (.runId // null),
        scenario: (.scenario // null),
        role: (.role // null),
        iterations: ((.iterations // []) | length),
        clients: ((.iterations // []) | map(.clients // 0) | max_or_zero),
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
      + (if (($receiver_iterations | length) == 0) then ["no-receiver-iterations"] else [] end)
      + (if (($server_iterations | length) == 0) then ["no-server-iterations"] else [] end)
  }
' "$server_summary" "${receiver_summaries[@]}" >"$lab_summary"

jq -c '.aggregate' "$lab_summary" >"$suite_aggregate"

{
  echo "case,benchmark_name,server_iterations,receiver_workers,server_connected_clients,receiver_clients,payload_size,reliability,target_mbps,target_client_mbps,delivered_gbps,healthy_delivered_gbps,affected_delivered_gbps,client_mbps_p50,client_mbps_p99,send_delivered_bytes_ratio,server_datagrams_out_s,stale_datagrams_s,nack_out_s,probe_p99_ms,max_queued_bytes,fairness,healthy_fairness,affected_fairness,warnings,artifact"
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
      $a.targetMbps,
      $a.targetClientMbps,
      $a.deliveredGbps,
      $a.healthyDeliveredGbps,
      $a.affectedDeliveredGbps,
      $a.clientMbpsP50,
      $a.clientMbpsP99,
      $a.sentToDeliveredBytesRatio,
      $a.serverDatagramsOutPerSecond,
      $a.staleDatagramsPerSecond,
      $a.nackOutPerSecond,
      $a.probeRttP99Millis,
      $a.maxQueuedBytes,
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
      ["Client Mbps p50", fmt($a.clientMbpsP50)],
      ["Client Mbps p99", fmt($a.clientMbpsP99)],
      ["Send/deliver byte ratio", fmt($a.sentToDeliveredBytesRatio)],
      ["Server datagrams out/s", fmt($a.serverDatagramsOutPerSecond)],
      ["Stale datagrams/s", fmt($a.staleDatagramsPerSecond)],
      ["NACK out/s", fmt($a.nackOutPerSecond)],
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
