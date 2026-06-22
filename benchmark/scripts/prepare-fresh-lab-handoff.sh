#!/usr/bin/env bash
set -euo pipefail

handoff_out=""
artifact_root=""
source_audit_out=""
require_sources="geyser,cloudburst-protocol,cloudburst-nukkit,cubecraft"
include_paths=false
dry_run=false
skip_freshness_checks=false
capture_args=()
handoff_args=()
preflight_args=()

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/prepare-fresh-lab-handoff.sh --server-host HOST --interface NIC --expect-mtu N --expect-min-cpus N [options]

Refreshes production source evidence, generates a lab handoff from that exact
source-audit artifact, runs handoff preflight with --require-source-audit, and
runs the generated freshness checks.

Wrapper options:
  --out DIR                         Handoff output directory.
  --artifact-root DIR               Artifact root used by generated benchmark commands.
  --source-audit-out DIR            Directory for source-audit.json. Default: <handoff>/production-evidence.
  --require-sources CSV             Required source ids for capture-production-evidence.sh.
                                    Default: geyser,cloudburst-protocol,cloudburst-nukkit,cubecraft.
  --geyser DIR                      Geyser checkout passed to capture-production-evidence.sh.
  --cloudburst-protocol DIR         Cloudburst Protocol checkout passed to capture-production-evidence.sh.
  --cloudburst-nukkit DIR           Cloudburst Nukkit checkout passed to capture-production-evidence.sh.
  --cubecraft DIR                   CubeCraft checkout passed to capture-production-evidence.sh.
  --teamziax-ebpf DIR               TeamZiax eBPF checkout passed to capture-production-evidence.sh.
  --include-paths                   Include local source paths in the private source audit artifact.
  --required-min-contention-clients N       Preflight minimum contention clients. Default: check-lab-handoff default.
  --required-min-contention-target-client-mbps N Preflight minimum per-client Mbps. Default: check-lab-handoff default.
  --required-batch-intervals-ms CSV          Preflight required batch intervals.
  --required-resource-pack-chunk-sizes CSV   Preflight required resource-pack chunk sizes.
  --required-resource-pack-intervals-ms CSV  Preflight required resource-pack intervals.
  --required-disappearance-modes CSV         Preflight required disappearing-client modes.
  --skip-freshness-checks           Do not run generated check-plan-freshness.sh scripts.
  --dry-run                         Print the command sequence without executing it.
  --help                            Show this help.

All other options are passed through to prepare-lab-baseline-handoff.sh.

Do not pass --source-audit directly; this wrapper creates it and wires it into
the handoff so the source-audit fingerprint cannot be stale at generation time.
USAGE
}

print_command() {
  printf '+'
  for arg in "$@"; do
    printf ' %q' "$arg"
  done
  printf '\n'
}

run_command() {
  print_command "$@"
  "$@"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      handoff_out="$2"
      shift 2
      ;;
    --artifact-root)
      artifact_root="$2"
      shift 2
      ;;
    --source-audit-out)
      source_audit_out="$2"
      shift 2
      ;;
    --require-sources)
      require_sources="$2"
      shift 2
      ;;
    --geyser|--cloudburst-protocol|--cloudburst-nukkit|--cubecraft|--teamziax-ebpf|--teamziax-bedrock-ebpf-filter)
      capture_args+=("$1" "$2")
      shift 2
      ;;
    --include-paths)
      include_paths=true
      shift
      ;;
    --required-min-contention-clients|--required-min-contention-target-client-mbps|--required-batch-intervals-ms|--required-resource-pack-chunk-sizes|--required-resource-pack-intervals-ms|--required-disappearance-modes)
      preflight_args+=("$1" "$2")
      shift 2
      ;;
    --skip-freshness-checks)
      skip_freshness_checks=true
      shift
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --source-audit)
      echo "--source-audit is managed by prepare-fresh-lab-handoff.sh; use --source-audit-out if you need to control its directory" >&2
      exit 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      handoff_args+=("$1")
      shift
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

resolve_path() {
  local path="$1"
  if [[ "$path" == /* ]]; then
    printf '%s\n' "$path"
  else
    printf '%s\n' "$repo_root/$path"
  fi
}

if [[ -z "$handoff_out" ]]; then
  handoff_out="$repo_root/benchmark/build/benchmark-results/lab-handoff-fresh-$timestamp"
else
  handoff_out="$(resolve_path "$handoff_out")"
fi
if [[ -z "$artifact_root" ]]; then
  artifact_root="$repo_root/benchmark/build/benchmark-results/lab-run-fresh-$timestamp"
else
  artifact_root="$(resolve_path "$artifact_root")"
fi
if [[ -z "$source_audit_out" ]]; then
  source_audit_out="$handoff_out/production-evidence"
else
  source_audit_out="$(resolve_path "$source_audit_out")"
fi

source_audit_json="$source_audit_out/source-audit.json"
preflight_out="$handoff_out/preflight"
summary_json="$handoff_out/fresh-handoff-summary.json"

capture_cmd=(
  "$script_dir/capture-production-evidence.sh"
  --out "$source_audit_out"
  --require-sources "$require_sources"
)
if "$include_paths"; then
  capture_cmd+=(--include-paths)
fi
capture_cmd+=("${capture_args[@]}")

handoff_cmd=(
  "$script_dir/prepare-lab-baseline-handoff.sh"
  --out "$handoff_out"
  --artifact-root "$artifact_root"
  --source-audit "$source_audit_json"
)
handoff_cmd+=("${handoff_args[@]}")

preflight_cmd=(
  "$script_dir/check-lab-handoff.sh"
  --handoff "$handoff_out"
  --out "$preflight_out"
  --require-source-audit
)
preflight_cmd+=("${preflight_args[@]}")

perfect_freshness="$handoff_out/perfect-plan/check-plan-freshness.sh"
impairment_freshness="$handoff_out/impairment-plan/check-plan-freshness.sh"

if "$dry_run"; then
  print_command "${capture_cmd[@]}"
  print_command "${handoff_cmd[@]}"
  print_command "${preflight_cmd[@]}"
  if ! "$skip_freshness_checks"; then
    print_command "$perfect_freshness"
    print_command "$impairment_freshness"
  fi
  echo "Dry-run only. Re-run without --dry-run to generate and preflight the handoff."
  exit 0
fi

mkdir -p "$handoff_out"
run_command "${capture_cmd[@]}"
run_command "${handoff_cmd[@]}"
run_command "${preflight_cmd[@]}"

if ! "$skip_freshness_checks"; then
  run_command "$perfect_freshness"
  run_command "$impairment_freshness"
fi

jq -n \
  --arg kind "raknet-fresh-lab-handoff" \
  --arg generatedAt "$(date -u +%Y%m%dT%H%M%SZ)" \
  --arg handoff "$handoff_out" \
  --arg artifactRoot "$artifact_root" \
  --arg sourceAudit "$source_audit_json" \
  --arg preflight "$preflight_out/handoff-check.json" \
  --arg summary "$summary_json" \
  --argjson sourceAuditReady "$(jq '.ready == true' "$source_audit_json")" \
  --arg sourceAuditRevision "$(jq -r '.networkShortRevision // ""' "$source_audit_json")" \
  --argjson handoffReady "$(jq '.ready == true' "$preflight_out/handoff-check.json")" \
  --argjson handoffIssueCount "$(jq '.issueCount // 0' "$preflight_out/handoff-check.json")" \
  '{
    kind: $kind,
    generatedAt: $generatedAt,
    handoff: $handoff,
    artifactRoot: $artifactRoot,
    sourceAudit: {
      path: $sourceAudit,
      ready: $sourceAuditReady,
      networkShortRevision: $sourceAuditRevision
    },
    preflight: {
      path: $preflight,
      ready: $handoffReady,
      issueCount: $handoffIssueCount
    },
    summary: $summary
  }' >"$summary_json"

echo "Fresh lab handoff: $handoff_out"
echo "Source audit: $source_audit_json"
echo "Preflight: $preflight_out/handoff-check.json"
echo "Summary: $summary_json"
