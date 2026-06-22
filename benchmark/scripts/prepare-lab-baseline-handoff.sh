#!/usr/bin/env bash
set -euo pipefail

output_root=""
artifact_root=""
server_host=""
bind_host="0.0.0.0"
port="19132"
interface=""
case_prefix="lab"
profiles="perfect,near-loss,regional-loss,poor,severe"
target_host_role="receiver-a"
curve_receivers=("receiver-a=1")
contention_receivers=()
contention_cases="fanout,fairness,disappear-blackhole"
contention_payload_size="512"
per_client_mbps="5"
raised_packet_limit="100000"
raised_global_packet_limit="1000000"
max_queued_bytes="67108864"
warmup="10s"
duration="60s"
iterations="3"
start_delay="90s"
start_offset="180s"
sudo_netem=false
common_args=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/prepare-lab-baseline-handoff.sh --server-host HOST --interface NIC [options]

Generates a complete lab handoff directory for the established RakNet baseline:
  - perfect-network lab baseline plan
  - host/NIC-level impairment campaign plan
  - top-level run order and readiness commands

The generated handoff does not run benchmarks. It composes the lower-level
planners so lab operators have one directory to copy/review before execution.

Options:
  --out DIR                         Handoff output directory. Default: benchmark/build/benchmark-results/lab-handoff-<timestamp>.
  --artifact-root DIR               Artifact root used by generated benchmark commands. Default: benchmark/build/benchmark-results/lab-run-<timestamp>.
  --server-host HOST                Server host/IP used by receiver workers. Required.
  --bind-host HOST                  Server bind host. Default: 0.0.0.0.
  --server-bind-host HOST           Alias for --bind-host.
  --port PORT                       UDP port. Default: 19132.
  --interface NIC                   Lab NIC used by host capture and netem scripts. Required.
  --curve-receiver NAME:CLIENTS     Receiver for curve. NAME=CLIENTS is accepted. May repeat. Default: receiver-a=1.
  --contention-receiver NAME:CLIENTS Receiver for contention. NAME=CLIENTS is accepted. May repeat. Default: receiver-a=250, receiver-b=250.
  --receiver NAME:CLIENTS           Alias for --contention-receiver.
  --profiles CSV                    Impairment profiles. Default: perfect,near-loss,regional-loss,poor,severe.
  --target-host-role ROLE           Host/namespace role shaped by impairment netem scripts. Default: receiver-a.
  --case-prefix NAME                Case prefix. Default: lab.
  --contention-cases CSV            Contention cases. Default: fanout,fairness,disappear-blackhole.
  --contention-payload-size N       Payload size for contention. Default: 512.
  --per-client-mbps N               Contention per-client offered rate. Default: 5.
  --raised-packet-limit N           Raised-limiter packet limit. Default: 100000.
  --raised-global-packet-limit N    Raised-limiter global packet limit. Default: 1000000.
  --max-queued-bytes N              Queue cap for lab runs. Default: 67108864.
  --warmup DURATION                 Warmup. Default: 10s.
  --duration DURATION               Measurement duration. Default: 60s.
  --iterations N                    Measured iterations. Default: 3.
  --start-delay DURATION            Server connection wait. Default: 90s.
  --start-offset DURATION           First case start offset from planning time. Default: 180s.
  --sudo-netem                      Generate impairment netem scripts with sudo.
  --common-args "..."               Extra benchmark args appended to worker commands.
  --help                            Show this help.

Outputs:
  perfect-plan/                     plan-lab-baseline.sh output.
  impairment-plan/                  plan-lab-impairment.sh output.
  handoff-manifest.json             Machine-readable handoff metadata.
  README.md                         Handoff run order and promotion/readiness commands.
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
      if [[ "${curve_receivers[*]}" == "receiver-a=1" ]]; then
        curve_receivers=()
      fi
      curve_receivers+=("$2")
      shift 2
      ;;
    --contention-receiver|--receiver)
      contention_receivers+=("$2")
      shift 2
      ;;
    --profiles)
      profiles="$2"
      shift 2
      ;;
    --target-host-role)
      target_host_role="$2"
      shift 2
      ;;
    --case-prefix|--case)
      case_prefix="$2"
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
    --raised-packet-limit)
      raised_packet_limit="$2"
      shift 2
      ;;
    --raised-global-packet-limit)
      raised_global_packet_limit="$2"
      shift 2
      ;;
    --max-queued-bytes)
      max_queued_bytes="$2"
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
    --sudo-netem)
      sudo_netem=true
      shift
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

if [[ -z "$server_host" || "$server_host" == "<server-ip>" ]]; then
  echo "--server-host is required and must not be a placeholder" >&2
  exit 2
fi
if [[ -z "$interface" || "$interface" == "<nic>" ]]; then
  echo "--interface is required and must not be a placeholder" >&2
  exit 2
fi
if [[ "${#contention_receivers[@]}" -eq 0 ]]; then
  contention_receivers=("receiver-a=250" "receiver-b=250")
fi

positive_int() {
  [[ "$1" =~ ^[0-9]+$ && "$1" -gt 0 ]]
}

non_empty_csv() {
  [[ -n "$1" && "$1" != *, && "$1" != ,* ]]
}

for value_name in port contention_payload_size iterations raised_packet_limit raised_global_packet_limit max_queued_bytes; do
  if ! positive_int "${!value_name}"; then
    echo "--${value_name//_/-} must be a positive integer: ${!value_name}" >&2
    exit 2
  fi
done
for value in "$profiles" "$contention_cases"; do
  if ! non_empty_csv "$value"; then
    echo "CSV options must be non-empty and cannot start or end with a comma: $value" >&2
    exit 2
  fi
done

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to write the lab handoff manifest" >&2
  exit 2
fi

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/lab-handoff-$timestamp"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi
if [[ -z "$artifact_root" ]]; then
  artifact_root="$repo_root/benchmark/build/benchmark-results/lab-run-$timestamp"
elif [[ "$artifact_root" != /* ]]; then
  artifact_root="$repo_root/$artifact_root"
fi

mkdir -p "$output_root"

perfect_plan="$output_root/perfect-plan"
impairment_plan="$output_root/impairment-plan"
perfect_artifacts="$artifact_root/perfect"
impairment_artifacts="$artifact_root/impairment"
readme="$output_root/README.md"
handoff_manifest="$output_root/handoff-manifest.json"

json_array_from_args() {
  if [[ "$#" -eq 0 ]]; then
    printf '[]'
    return
  fi

  printf '%s\n' "$@" | jq -R -s 'split("\n") | map(select(length > 0))'
}

json_array_from_csv() {
  printf '%s' "$1" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
    | jq -R -s 'split("\n") | map(select(length > 0))'
}

common_baseline_args=(
  --server-host "$server_host"
  --bind-host "$bind_host"
  --port "$port"
  --interface "$interface"
  --contention-cases "$contention_cases"
  --contention-payload-size "$contention_payload_size"
  --per-client-mbps "$per_client_mbps"
  --raised-packet-limit "$raised_packet_limit"
  --raised-global-packet-limit "$raised_global_packet_limit"
  --max-queued-bytes "$max_queued_bytes"
  --warmup "$warmup"
  --duration "$duration"
  --iterations "$iterations"
  --start-delay "$start_delay"
  --start-offset "$start_offset"
)
if [[ -n "$common_args" ]]; then
  common_baseline_args+=(--common-args "$common_args")
fi
for receiver in "${curve_receivers[@]}"; do
  common_baseline_args+=(--curve-receiver "$receiver")
done
for receiver in "${contention_receivers[@]}"; do
  common_baseline_args+=(--contention-receiver "$receiver")
done

"$script_dir/plan-lab-baseline.sh" \
  --out "$perfect_plan" \
  --artifact-root "$perfect_artifacts" \
  --case-prefix "$case_prefix-perfect" \
  "${common_baseline_args[@]}"

impairment_args=(
  --out "$impairment_plan"
  --artifact-root "$impairment_artifacts"
  --interface "$interface"
  --profiles "$profiles"
  --target-host-role "$target_host_role"
  --case-prefix "$case_prefix-impairment"
)
if "$sudo_netem"; then
  impairment_args+=(--sudo-netem)
fi

"$script_dir/plan-lab-impairment.sh" \
  "${impairment_args[@]}" \
  -- \
  "${common_baseline_args[@]}"

git_revision="$(git -C "$repo_root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
curve_receivers_json="$(json_array_from_args "${curve_receivers[@]}")"
contention_receivers_json="$(json_array_from_args "${contention_receivers[@]}")"
profiles_json="$(json_array_from_csv "$profiles")"
contention_cases_json="$(json_array_from_csv "$contention_cases")"

jq -n \
  --arg kind "raknet-lab-handoff" \
  --arg generatedAt "$timestamp" \
  --arg gitRevision "$git_revision" \
  --arg repoRoot "$repo_root" \
  --arg outputRoot "$output_root" \
  --arg artifactRoot "$artifact_root" \
  --arg perfectPlan "$perfect_plan" \
  --arg impairmentPlan "$impairment_plan" \
  --arg perfectArtifacts "$perfect_artifacts" \
  --arg impairmentArtifacts "$impairment_artifacts" \
  --arg readme "$readme" \
  --arg serverHost "$server_host" \
  --arg bindHost "$bind_host" \
  --arg port "$port" \
  --arg interface "$interface" \
  --arg targetHostRole "$target_host_role" \
  --arg casePrefix "$case_prefix" \
  --arg contentionPayloadSize "$contention_payload_size" \
  --arg perClientMbps "$per_client_mbps" \
  --arg raisedPacketLimit "$raised_packet_limit" \
  --arg raisedGlobalPacketLimit "$raised_global_packet_limit" \
  --arg maxQueuedBytes "$max_queued_bytes" \
  --arg warmup "$warmup" \
  --arg duration "$duration" \
  --arg iterations "$iterations" \
  --arg startDelay "$start_delay" \
  --arg startOffset "$start_offset" \
  --arg commonArgs "$common_args" \
  --argjson curveReceivers "$curve_receivers_json" \
  --argjson contentionReceivers "$contention_receivers_json" \
  --argjson profiles "$profiles_json" \
  --argjson contentionCases "$contention_cases_json" \
  --argjson sudoNetem "$sudo_netem" \
  '{
    kind: $kind,
    generatedAt: $generatedAt,
    gitRevision: $gitRevision,
    repoRoot: $repoRoot,
    outputRoot: $outputRoot,
    artifactRoot: $artifactRoot,
    readme: $readme,
    perfectPlan: $perfectPlan,
    impairmentPlan: $impairmentPlan,
    perfectArtifacts: $perfectArtifacts,
    impairmentArtifacts: $impairmentArtifacts,
    serverHost: $serverHost,
    bindHost: $bindHost,
    port: ($port | tonumber),
    interface: $interface,
    profiles: $profiles,
    targetHostRole: $targetHostRole,
    curveReceivers: $curveReceivers,
    contentionReceivers: $contentionReceivers,
    contentionCases: $contentionCases,
    casePrefix: $casePrefix,
    contentionPayloadSize: ($contentionPayloadSize | tonumber),
    perClientMbps: ($perClientMbps | tonumber),
    raisedPacketLimit: ($raisedPacketLimit | tonumber),
    raisedGlobalPacketLimit: ($raisedGlobalPacketLimit | tonumber),
    maxQueuedBytes: ($maxQueuedBytes | tonumber),
    warmup: $warmup,
    duration: $duration,
    iterations: ($iterations | tonumber),
    startDelay: $startDelay,
    startOffset: $startOffset,
    sudoNetem: $sudoNetem,
    commonArgs: $commonArgs
  }' >"$handoff_manifest"

cat >"$readme" <<EOF
# RakNet Lab Baseline Handoff

- Generated: \`$timestamp\`
- Server host: \`$server_host\`
- Bind host: \`$bind_host\`
- Port: \`$port\`
- Interface: \`$interface\`
- Artifact root: \`$artifact_root\`
- Perfect-network plan: \`$perfect_plan\`
- Impairment campaign plan: \`$impairment_plan\`
- Handoff manifest: \`$handoff_manifest\`
- Profiles: \`$profiles\`
- Impairment target host role: \`$target_host_role\`
- Curve receivers: \`$(IFS=,; echo "${curve_receivers[*]}")\`
- Contention receivers: \`$(IFS=,; echo "${contention_receivers[*]}")\`
- Contention cases: \`$contention_cases\`
- Per-client Mbps: \`$per_client_mbps\`
- Raised packet limits: \`$raised_packet_limit/$raised_global_packet_limit\`
- Max queued bytes: \`$max_queued_bytes\`

This handoff packages the current recommended established RakNet baseline plan.
It does not run the benchmark. Review the generated commands, run the freshness
checks shortly before execution, then follow each generated plan README.

## Run Order

1. On the merge/control host, run \`perfect-plan/check-plan-freshness.sh\`.
2. Fill \`perfect-plan/topology-template.md\` as \`$perfect_artifacts/topology.md\`.
3. Run \`perfect-plan/host-capture-commands.sh\` on the server and each receiver host with the correct \`HOST_ROLE\`.
4. Run the perfect-network curve, raised-curve, and contention worker commands from \`perfect-plan/README.md\`.
5. Copy receiver artifacts and host captures back under \`$perfect_artifacts\`.
6. Run \`perfect-plan/merge-all.sh\` from the repository root.
7. Run \`impairment-plan/check-plan-freshness.sh\`.
8. Run each impairment profile from \`impairment-plan/README.md\`, including the generated netem apply/status/clear scripts on the shaped host or namespace.
9. Copy every profile's receiver artifacts and \`netem/\` evidence back under \`$impairment_artifacts\`.
10. Run \`impairment-plan/validate-all.sh\`, then \`impairment-plan/summarize-campaign.sh\`.

## Promote Baselines

\`\`\`bash
benchmark/scripts/promote-lab-baseline.sh \\
  --input "$perfect_artifacts/combined" \\
  --manifest "$perfect_plan/curve-plan/manifest.jsonl" \\
  --manifest "$perfect_plan/curve-raised-plan/manifest.jsonl" \\
  --manifest "$perfect_plan/contention-plan/manifest.jsonl" \\
  --out benchmark/build/benchmark-baselines \\
  --name lab-<date>-<topology>

benchmark/scripts/promote-lab-impairment.sh \\
  --input "$impairment_artifacts/campaign-summary" \\
  --out benchmark/build/benchmark-baselines \\
  --name lab-impairment-<date>-<topology>
\`\`\`

## Readiness Gate

\`\`\`bash
benchmark/scripts/check-baseline-readiness.sh \\
  --lab-baseline benchmark/build/benchmark-baselines/lab-<date>-<topology> \\
  --impairment-baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \\
  --out benchmark/build/benchmark-results/baseline-readiness
\`\`\`

The baseline is not accepted as the comparison baseline until the readiness gate
passes. Local loopback and single-host namespace runs are useful development
signals, but they do not replace this separate-host handoff.
EOF

echo "Lab handoff: $output_root"
echo "Perfect-network plan: $perfect_plan"
echo "Impairment campaign plan: $impairment_plan"
echo "Handoff manifest: $handoff_manifest"
echo "README: $readme"
