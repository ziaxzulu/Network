#!/usr/bin/env bash
set -euo pipefail

interface=""
runner_profile="smoke"
profiles="perfect,near-loss,regional-loss,poor,severe"
output_root=""
only_case=""
common_args=""
gradle="./gradlew"
continue_on_error=false
execute=false
sudo_netem=false

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/run-impairment-matrix.sh --interface eth0 [options]

Options:
  --interface IFACE              Interface to shape with tc netem.
  --runner-profile smoke|local|lab
                                Baseline suite profile to run under each impairment. Default: smoke.
  --profiles LIST                Comma-separated impairment profiles. Default: perfect,near-loss,regional-loss,poor,severe.
  --out DIR                      Output root. Default: benchmark/build/benchmark-results/impairment-<timestamp>.
  --only PATTERN                 Pass through to run-baseline-matrix.sh --only.
  --common-args "..."            Extra benchmark args appended to each baseline case.
  --gradle ./gradlew             Gradle executable passed to run-baseline-matrix.sh.
  --continue-on-error            Keep running remaining profiles/cases after a failure.
  --sudo-netem                   Prefix tc netem operations with sudo.
  --execute                      Apply/clear netem and run benchmarks. Without this, print a dry-run plan only.
  --help                         Show this help.

Profiles:
  perfect        0ms latency, 0ms jitter, 0% loss
  near-loss      10ms latency, 2ms jitter, 2% loss
  regional-loss  50ms latency, 5ms jitter, 2% loss
  poor           100ms latency, 10ms jitter, 5% loss
  severe         200ms latency, 20ms jitter, 10% loss
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interface)
      interface="$2"
      shift 2
      ;;
    --runner-profile)
      runner_profile="$2"
      shift 2
      ;;
    --profiles)
      profiles="$2"
      shift 2
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --only)
      only_case="$2"
      shift 2
      ;;
    --common-args)
      common_args="$2"
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
    --sudo-netem)
      sudo_netem=true
      shift
      ;;
    --execute)
      execute=true
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

if [[ -z "$interface" ]]; then
  echo "--interface is required" >&2
  usage >&2
  exit 2
fi

case "$runner_profile" in
  smoke|local|lab)
    ;;
  *)
    echo "Unknown runner profile: $runner_profile" >&2
    usage >&2
    exit 2
    ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
netem_script="$script_dir/raknet-netem.sh"
runner_script="$script_dir/run-baseline-matrix.sh"

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/impairment-${timestamp}"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi

mkdir -p "$output_root"
manifest="$output_root/impairment-manifest.jsonl"
report="$output_root/README.md"
: >"$manifest"

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

profile_spec() {
  case "$1" in
    perfect)
      printf '%s|%s|%s\n' "0ms" "0ms" "0%"
      ;;
    near-loss)
      printf '%s|%s|%s\n' "10ms" "2ms" "2%"
      ;;
    regional-loss)
      printf '%s|%s|%s\n' "50ms" "5ms" "2%"
      ;;
    poor)
      printf '%s|%s|%s\n' "100ms" "10ms" "5%"
      ;;
    severe)
      printf '%s|%s|%s\n' "200ms" "20ms" "10%"
      ;;
    *)
      echo "Unknown impairment profile: $1" >&2
      exit 2
      ;;
  esac
}

netem_command() {
  local action="$1"
  local latency="$2"
  local jitter="$3"
  local loss="$4"
  local command=()
  if "$sudo_netem"; then
    command+=(sudo)
  fi
  command+=("$netem_script" --interface "$interface" --action "$action")
  if [[ "$action" == "apply" || "$action" == "dry-run" ]]; then
    command+=(--latency "$latency" --jitter "$jitter" --loss "$loss")
  fi
  printf '%s\0' "${command[@]}"
}

run_or_print() {
  local -a command=("$@")
  echo "$(command_line "${command[@]}")"
  if "$execute"; then
    "${command[@]}"
  fi
}

write_report_header() {
  {
    echo "# RakNet Impairment Matrix"
    echo
    echo "- Started: \`$timestamp\`"
    echo "- Output root: \`$output_root\`"
    echo "- Interface: \`$interface\`"
    echo "- Runner profile: \`$runner_profile\`"
    echo "- Profiles: \`$profiles\`"
    echo "- Execute: \`$execute\`"
    echo
    echo "| Profile | Latency | Jitter | Loss | Status | Artifact |"
    echo "| --- | ---: | ---: | ---: | --- | --- |"
  } >"$report"
}

append_report_row() {
  local profile="$1"
  local latency="$2"
  local jitter="$3"
  local loss="$4"
  local status="$5"
  local artifact="$6"
  echo "| \`$profile\` | \`$latency\` | \`$jitter\` | \`$loss\` | \`$status\` | \`$artifact\` |" >>"$report"
}

record_manifest() {
  local profile="$1"
  local latency="$2"
  local jitter="$3"
  local loss="$4"
  local status="$5"
  local artifact="$6"
  local command="$7"
  printf '{"profile":"%s","latency":"%s","jitter":"%s","loss":"%s","status":"%s","artifact":"%s","command":"%s"}\n' \
    "$(json_escape "$profile")" \
    "$(json_escape "$latency")" \
    "$(json_escape "$jitter")" \
    "$(json_escape "$loss")" \
    "$(json_escape "$status")" \
    "$(json_escape "$artifact")" \
    "$(json_escape "$command")" >>"$manifest"
}

run_profile() {
  local profile="$1"
  local spec
  local latency
  local jitter
  local loss
  local artifact
  local status="passed"
  local runner_command=()
  local netem_apply=()
  local netem_clear=()
  local netem_status=()
  spec="$(profile_spec "$profile")"
  IFS='|' read -r latency jitter loss <<<"$spec"
  artifact="$output_root/$profile"

  echo
  echo "==> impairment profile: $profile ($latency latency, $jitter jitter, $loss loss)"

  readarray -d '' -t netem_clear < <(netem_command clear "$latency" "$jitter" "$loss")
  readarray -d '' -t netem_status < <(netem_command status "$latency" "$jitter" "$loss")
  if [[ "$profile" == "perfect" ]]; then
    run_or_print "${netem_clear[@]}" || status="failed"
  else
    readarray -d '' -t netem_apply < <(netem_command apply "$latency" "$jitter" "$loss")
    run_or_print "${netem_apply[@]}" || status="failed"
  fi
  run_or_print "${netem_status[@]}" || true

  runner_command=("$runner_script" --profile "$runner_profile" --out "$artifact" --gradle "$gradle")
  if [[ -n "$only_case" ]]; then
    runner_command+=(--only "$only_case")
  fi
  if [[ -n "$common_args" ]]; then
    runner_command+=(--common-args "$common_args")
  fi
  if "$continue_on_error"; then
    runner_command+=(--continue-on-error)
  fi
  if ! run_or_print "${runner_command[@]}"; then
    status="failed"
  fi

  if "$execute"; then
    run_or_print "${netem_clear[@]}" || true
  else
    echo "$(command_line "${netem_clear[@]}")"
  fi

  if [[ "$execute" == "false" ]]; then
    status="dry-run"
  fi

  append_report_row "$profile" "$latency" "$jitter" "$loss" "$status" "$artifact"
  record_manifest "$profile" "$latency" "$jitter" "$loss" "$status" "$artifact" "$(command_line "${runner_command[@]}")"

  if [[ "$status" == "failed" && "$continue_on_error" == "false" ]]; then
    echo "Impairment profile failed: $profile" >&2
    exit 1
  fi
}

write_report_header
cd "$repo_root"

IFS=',' read -r -a selected_profiles <<<"$profiles"
if [[ "${#selected_profiles[@]}" -eq 0 ]]; then
  echo "No impairment profiles selected" >&2
  exit 2
fi

for profile in "${selected_profiles[@]}"; do
  run_profile "$profile"
done

echo
echo "Impairment matrix artifacts: $output_root"
echo "Manifest: $manifest"
echo "Report: $report"
