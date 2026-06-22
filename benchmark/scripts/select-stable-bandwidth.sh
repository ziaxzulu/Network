#!/usr/bin/env bash
set -euo pipefail

input_path=""
output_root=""
min_iterations="3"
max_p99_ms="0"
max_queue_bytes="0"
max_send_deliver_ratio="0"
max_nack_out_s="0"
allow_unstable=false

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/select-stable-bandwidth.sh --input DIR|suite-aggregate.jsonl [options]

Options:
  --input PATH                  Baseline suite directory or suite-aggregate.jsonl.
  --out DIR                     Output directory. Default: input directory.
  --min-iterations N            Minimum measured iterations for a stable selection. Default: 3.
  --max-p99-ms N                Reject rows with p99 probe RTT above N ms. Default: 0, disabled.
  --max-queue-bytes N           Reject rows with max queued bytes above N. Default: 0, disabled.
  --max-send-deliver-ratio N    Reject rows with send/deliver byte ratio above N. Default: 0, disabled.
  --max-nack-out-s N            Reject rows with NACK out/s above N. Default: 0, disabled.
  --allow-unstable              Allow rows already marked unstable by the suite aggregator.
  --help                        Show this help.

Outputs:
  bandwidth-capacity.jsonl      One capacity-selection row per bandwidth curve group.
  bandwidth-capacity.csv        Spreadsheet-friendly flattened selection rows.
  bandwidth-capacity.md         Human-readable report.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      input_path="$2"
      shift 2
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --min-iterations)
      min_iterations="$2"
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
    --allow-unstable)
      allow_unstable=true
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

if [[ -z "$input_path" ]]; then
  usage >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to select stable bandwidth rows" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

is_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

for value in "$min_iterations" "$max_p99_ms" "$max_queue_bytes" "$max_send_deliver_ratio" "$max_nack_out_s"; do
  if ! is_number "$value"; then
    echo "Threshold values must be non-negative numbers: $value" >&2
    exit 2
  fi
done

if [[ "$input_path" != /* ]]; then
  input_path="$repo_root/$input_path"
fi

input_summary="$input_path"
if [[ -d "$input_summary" ]]; then
  if [[ ! -s "$input_summary/suite-aggregate.jsonl" ]]; then
    echo "suite-aggregate.jsonl not found or empty in: $input_summary" >&2
    exit 2
  fi
  if [[ -z "$output_root" ]]; then
    output_root="$input_summary"
  fi
  input_summary="$input_summary/suite-aggregate.jsonl"
fi

if [[ ! -s "$input_summary" ]]; then
  echo "suite aggregate not found or empty: $input_summary" >&2
  exit 2
fi

if [[ -z "$output_root" ]]; then
  output_root="$(dirname "$input_summary")"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi

mkdir -p "$output_root"
jsonl_out="$output_root/bandwidth-capacity.jsonl"
csv_out="$output_root/bandwidth-capacity.csv"
report_out="$output_root/bandwidth-capacity.md"
allow_unstable_json="false"
if "$allow_unstable"; then
  allow_unstable_json="true"
fi

jq -c -s \
  --arg inputSummary "$input_summary" \
  --argjson minIterations "$min_iterations" \
  --argjson maxP99Millis "$max_p99_ms" \
  --argjson maxQueueBytes "$max_queue_bytes" \
  --argjson maxSendDeliverRatio "$max_send_deliver_ratio" \
  --argjson maxNackOutPerSecond "$max_nack_out_s" \
  --argjson allowUnstable "$allow_unstable_json" '
  def n($value): ($value // 0) | tonumber;
  def curve_row: ((.benchmarkName // "") | startswith("curve-"));
  def candidate_reasons($row):
    []
    + (if (($allowUnstable | not) and (($row.unstable // false) == true)) then ["unstable"] else [] end)
    + (if n($row.measuredIterations) < $minIterations then ["insufficient-iterations"] else [] end)
    + (if n($row.deliveredGbps) <= 0 then ["zero-delivery"] else [] end)
    + (if n($row.disconnects) > 0 then ["disconnects"] else [] end)
    + (if $maxP99Millis > 0 and n($row.probeRttP99Millis) > $maxP99Millis then ["p99-rtt"] else [] end)
    + (if $maxQueueBytes > 0 and n($row.maxQueuedBytes) > $maxQueueBytes then ["queue-bytes"] else [] end)
    + (if $maxSendDeliverRatio > 0 and n($row.sentToDeliveredBytesRatio) > $maxSendDeliverRatio then ["send-deliver-ratio"] else [] end)
    + (if $maxNackOutPerSecond > 0 and n($row.nackOutPerSecond) > $maxNackOutPerSecond then ["nack-out-rate"] else [] end);
  def candidate_json($row):
    if $row == null then null
    else {
      benchmarkName: ($row.benchmarkName // null),
      targetMbps: ($row.targetMbps // null),
      targetClientMbps: ($row.targetClientMbps // null),
      deliveredGbps: ($row.deliveredGbps // null),
      offeredGbps: ($row.offeredGbps // null),
      deliveredMessagesPerSecond: ($row.deliveredMessagesPerSecond // null),
      deliveredLogicalPacketsPerSecond: ($row.deliveredLogicalPacketsPerSecond // null),
      probeRttP99Millis: ($row.probeRttP99Millis // null),
      openPeers: ($row.openPeers // null),
      activePeers: ($row.activePeers // null),
      activePeersMin: ($row.activePeersMin // null),
      stateDisconnectedPeers: ($row.stateDisconnectedPeers // null),
      stateUnconnectedPeers: ($row.stateUnconnectedPeers // null),
      deliveredGbpsSpreadPct: ($row.deliveredGbpsSpreadPct // null),
      probeRttP99MillisSpreadPct: ($row.probeRttP99MillisSpreadPct // null),
      maxQueuedBytes: ($row.maxQueuedBytes // null),
      sentToDeliveredBytesRatio: ($row.sentToDeliveredBytesRatio // null),
      serverDatagramsOutPerSecond: ($row.serverDatagramsOutPerSecond // null),
      staleDatagramsPerSecond: ($row.staleDatagramsPerSecond // null),
      nackOutPerSecond: ($row.nackOutPerSecond // null),
      measuredIterations: ($row.measuredIterations // null),
      unstable: ($row.unstable // false),
      unstableReasons: ($row.unstableReasons // []),
      eligible: ($row.eligible // false),
      rejectionReasons: ($row.rejectionReasons // [])
    }
    end;

  [ .[] | select(curve_row) ] |
  sort_by([
    (.case // ""),
    n(.clients),
    n(.payloadSize),
    (.reliability // ""),
    (.impairmentProfile // "0ms/0ms/0%"),
    n(.packetLimit),
    n(.globalPacketLimit),
    n(.configuredMaxQueuedBytes)
  ]) |
  group_by([
    (.case // ""),
    n(.clients),
    n(.payloadSize),
    (.reliability // ""),
    (.impairmentProfile // "0ms/0ms/0%"),
    n(.packetLimit),
    n(.globalPacketLimit),
    n(.configuredMaxQueuedBytes)
  ])[] as $rows |
  ($rows[0]) as $first |
  (
    $rows |
    map(. as $row | (candidate_reasons($row)) as $reasons | $row + {
      eligible: (($reasons | length) == 0),
      rejectionReasons: $reasons
    })
  ) as $candidates |
  ($candidates | map(select(.eligible)) | sort_by([(n(.deliveredGbps) * -1), n(.probeRttP99Millis), n(.maxQueuedBytes)]) | .[0] // null) as $selected |
  ($candidates | sort_by([(n(.deliveredGbps) * -1), n(.probeRttP99Millis), n(.maxQueuedBytes)]) | .[0] // null) as $bestObserved |
  {
    summaryKind: "bandwidth-capacity",
    inputSummary: $inputSummary,
    case: ($first.case // null),
    clients: ($first.clients // null),
    payloadSize: ($first.payloadSize // null),
    reliability: ($first.reliability // null),
    batched: ($first.batched // false),
    impairmentProfile: ($first.impairmentProfile // "0ms/0ms/0%"),
    impairmentLatencyMillis: ($first.impairmentLatencyMillis // 0),
    impairmentJitterMillis: ($first.impairmentJitterMillis // 0),
    impairmentLossPercent: ($first.impairmentLossPercent // 0),
    scenario: ($first.scenario // null),
    packetLimit: ($first.packetLimit // null),
    globalPacketLimit: ($first.globalPacketLimit // null),
    configuredMaxQueuedBytes: ($first.configuredMaxQueuedBytes // null),
    minIterations: $minIterations,
    maxP99Millis: $maxP99Millis,
    maxQueueBytes: $maxQueueBytes,
    maxSendDeliverRatio: $maxSendDeliverRatio,
    maxNackOutPerSecond: $maxNackOutPerSecond,
    allowUnstable: $allowUnstable,
    candidateCount: ($candidates | length),
    eligibleCandidateCount: ($candidates | map(select(.eligible)) | length),
    selected: ($selected != null),
    selectedCandidate: candidate_json($selected),
    bestObservedCandidate: candidate_json($bestObserved),
    rejectedCandidates: ($candidates | map(select(.eligible | not) | candidate_json(.)))
  }
' "$input_summary" >"$jsonl_out"

{
  echo "case,clients,payload_size,reliability,impairment_profile,packet_limit,global_packet_limit,configured_max_queued_bytes,selected,eligible_candidates,candidate_count,selected_benchmark,selected_target_mbps,selected_delivered_gbps,selected_p99_ms,selected_spread_pct,selected_max_queue_bytes,selected_send_deliver_ratio,selected_nack_out_s,best_observed_benchmark,best_observed_target_mbps,best_observed_delivered_gbps,best_observed_p99_ms,best_observed_reasons"
  jq -r '
    def value($candidate; $name):
      if $candidate == null then null else $candidate[$name] end;
    [
      .case,
      .clients,
      .payloadSize,
      .reliability,
      .impairmentProfile,
      .packetLimit,
      .globalPacketLimit,
      .configuredMaxQueuedBytes,
      .selected,
      .eligibleCandidateCount,
      .candidateCount,
      value(.selectedCandidate; "benchmarkName"),
      value(.selectedCandidate; "targetMbps"),
      value(.selectedCandidate; "deliveredGbps"),
      value(.selectedCandidate; "probeRttP99Millis"),
      value(.selectedCandidate; "deliveredGbpsSpreadPct"),
      value(.selectedCandidate; "maxQueuedBytes"),
      value(.selectedCandidate; "sentToDeliveredBytesRatio"),
      value(.selectedCandidate; "nackOutPerSecond"),
      value(.bestObservedCandidate; "benchmarkName"),
      value(.bestObservedCandidate; "targetMbps"),
      value(.bestObservedCandidate; "deliveredGbps"),
      value(.bestObservedCandidate; "probeRttP99Millis"),
      (value(.bestObservedCandidate; "rejectionReasons") // [] | join(";"))
    ] | @csv
  ' "$jsonl_out"
} >"$csv_out"

{
  echo "# Stable Bandwidth Capacity"
  echo
  echo "- Input summary: \`$input_summary\`"
  echo "- Minimum iterations: \`$min_iterations\`"
  echo "- Max p99 RTT: \`$(if [[ "$max_p99_ms" == "0" ]]; then echo "disabled"; else echo "${max_p99_ms}ms"; fi)\`"
  echo "- Max queue bytes: \`$(if [[ "$max_queue_bytes" == "0" ]]; then echo "disabled"; else echo "$max_queue_bytes"; fi)\`"
  echo "- Max send/deliver ratio: \`$(if [[ "$max_send_deliver_ratio" == "0" ]]; then echo "disabled"; else echo "$max_send_deliver_ratio"; fi)\`"
  echo "- Max NACK out/s: \`$(if [[ "$max_nack_out_s" == "0" ]]; then echo "disabled"; else echo "$max_nack_out_s"; fi)\`"
  echo "- Allow unstable rows: \`$allow_unstable\`"
  echo
  if [[ ! -s "$jsonl_out" ]]; then
    echo "No bandwidth-latency curve aggregate rows were found."
  else
    echo "| Case | Payload | Reliability | Impairment | Packet limit | Global limit | Queue cap | Selected | Stable Gbps | Stable target Mbps | Stable p99 ms | Stable spread | Best observed Gbps | Best observed target Mbps | Best observed reasons |"
    echo "| --- | ---: | --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
    jq -r '
      def fmt($value):
        if $value == null then "n/a"
        elif ($value | type) == "number" and $value != 0 and (($value | fabs) < 1) then (($value * 1000000 | round) / 1000000 | tostring)
        elif ($value | type) == "number" then (($value * 1000 | round) / 1000 | tostring)
        else ($value | tostring)
        end;
      def pct($value):
        if $value == null then "n/a" else fmt($value) + "%" end;
      def field($candidate; $name):
        if $candidate == null then null else $candidate[$name] end;
      [
        "`" + (.case // "unknown") + "`",
        (.payloadSize | tostring),
        "`" + (.reliability // "unknown") + "`",
        "`" + (.impairmentProfile // "0ms/0ms/0%") + "`",
        fmt(.packetLimit),
        fmt(.globalPacketLimit),
        fmt(.configuredMaxQueuedBytes),
        (.selected | tostring),
        fmt(field(.selectedCandidate; "deliveredGbps")),
        fmt(field(.selectedCandidate; "targetMbps")),
        fmt(field(.selectedCandidate; "probeRttP99Millis")),
        pct(field(.selectedCandidate; "deliveredGbpsSpreadPct")),
        fmt(field(.bestObservedCandidate; "deliveredGbps")),
        fmt(field(.bestObservedCandidate; "targetMbps")),
        "`" + ((field(.bestObservedCandidate; "rejectionReasons") // []) | join(",")) + "`"
      ] | @tsv
    ' "$jsonl_out" | while IFS=$'\t' read -r case_name payload reliability impairment packet_limit global_limit queue_cap selected stable_gbps stable_target stable_p99 stable_spread best_gbps best_target best_reasons; do
      echo "| $case_name | $payload | $reliability | $impairment | $packet_limit | $global_limit | $queue_cap | $selected | $stable_gbps | $stable_target | $stable_p99 | $stable_spread | $best_gbps | $best_target | $best_reasons |"
    done
  fi
  echo
  echo "Stable Gbps is the highest delivered curve row that passes the configured gates. Best observed Gbps is shown separately so failed or unstable high-throughput rows are visible instead of silently discarded."
} >"$report_out"

echo "Bandwidth capacity JSONL: $jsonl_out"
echo "Bandwidth capacity CSV: $csv_out"
echo "Bandwidth capacity report: $report_out"
