#!/usr/bin/env bash
set -euo pipefail

out_dir=""
interface=""
host_role="${HOST_ROLE:-host}"
require_sudo_netem=false
require_clock_sync=false
require_cpu_performance=false
require_no_netem=false
expect_mtu=""
expect_min_cpus=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/check-lab-host-prereqs.sh --out DIR --interface NIC [options]

Checks whether a lab host has the basic local prerequisites needed before
running coordinated RakNet benchmark workers. The script is read-only.

Options:
  --out DIR                 Output directory for prereq.json and prereq.md. Required.
  --interface NIC           Lab NIC/interface used by benchmark traffic and netem. Required.
  --host-role ROLE          Host role label written into reports. Default: HOST_ROLE or host.
  --require-sudo-netem      Require sudo to be installed for generated sudo netem scripts.
  --require-clock-sync      Fail when clock synchronization cannot be verified.
  --require-cpu-performance Fail unless all visible CPU governors are performance.
  --require-no-netem        Fail when the selected interface already has a netem qdisc.
  --expect-mtu N            Fail when the selected interface MTU differs from N.
  --expect-min-cpus N       Fail when the host has fewer than N online CPUs.
  --help                    Show this help.

Outputs:
  prereq.json               Machine-readable host prerequisite result.
  prereq.md                 Human-readable host prerequisite report.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      out_dir="$2"
      shift 2
      ;;
    --interface)
      interface="$2"
      shift 2
      ;;
    --host-role)
      host_role="$2"
      shift 2
      ;;
    --require-sudo-netem)
      require_sudo_netem=true
      shift
      ;;
    --require-clock-sync)
      require_clock_sync=true
      shift
      ;;
    --require-cpu-performance)
      require_cpu_performance=true
      shift
      ;;
    --require-no-netem)
      require_no_netem=true
      shift
      ;;
    --expect-mtu)
      expect_mtu="$2"
      shift 2
      ;;
    --expect-min-cpus)
      expect_min_cpus="$2"
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

if [[ -z "$out_dir" || -z "$interface" ]]; then
  usage >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to write lab host prerequisite reports" >&2
  exit 2
fi
if [[ -n "$expect_mtu" && ! "$expect_mtu" =~ ^[0-9]+$ ]]; then
  echo "--expect-mtu must be a non-negative integer" >&2
  exit 2
fi
if [[ -n "$expect_min_cpus" && ! "$expect_min_cpus" =~ ^[0-9]+$ ]]; then
  echo "--expect-min-cpus must be a non-negative integer" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
if [[ "$out_dir" != /* ]]; then
  out_dir="$repo_root/$out_dir"
fi
mkdir -p "$out_dir"

prereq_json="$out_dir/prereq.json"
prereq_md="$out_dir/prereq.md"
checks_jsonl="$(mktemp)"
issues_jsonl="$(mktemp)"
trap 'rm -f "$checks_jsonl" "$issues_jsonl"' EXIT
: >"$checks_jsonl"
: >"$issues_jsonl"

append_check() {
  local name="$1"
  local status="$2"
  local message="$3"
  local extra="${4-}"
  if [[ -z "$extra" ]]; then
    extra="{}"
  fi
  jq -n \
    --arg name "$name" \
    --arg status "$status" \
    --arg message "$message" \
    --argjson extra "$extra" \
    '{name:$name,status:$status,message:$message} + $extra' >>"$checks_jsonl"
}

append_issue() {
  local code="$1"
  local severity="$2"
  local message="$3"
  local extra="${4-}"
  if [[ -z "$extra" ]]; then
    extra="{}"
  fi
  jq -n \
    --arg code "$code" \
    --arg severity "$severity" \
    --arg message "$message" \
    --argjson extra "$extra" \
    '{code:$code,severity:$severity,message:$message} + $extra' >>"$issues_jsonl"
}

command_path() {
  command -v "$1" 2>/dev/null || true
}

require_command() {
  local command_name="$1"
  local code="$2"
  local description="$3"
  local path
  path="$(command_path "$command_name")"
  if [[ -n "$path" ]]; then
    append_check "$command_name" "pass" "$description is available" "$(jq -n --arg path "$path" '{path:$path}')"
  else
    append_check "$command_name" "fail" "$description is missing"
    append_issue "$code" "error" "$description is required for lab benchmark runs"
  fi
}

warn_command() {
  local command_name="$1"
  local code="$2"
  local description="$3"
  local path
  path="$(command_path "$command_name")"
  if [[ -n "$path" ]]; then
    append_check "$command_name" "pass" "$description is available" "$(jq -n --arg path "$path" '{path:$path}')"
  else
    append_check "$command_name" "warn" "$description is missing"
    append_issue "$code" "warning" "$description is useful for lab evidence but not required"
  fi
}

require_command "bash" "missing-bash" "bash"
require_command "java" "missing-java" "java"
require_command "ip" "missing-ip" "iproute2 ip"
require_command "tc" "missing-tc" "iproute2 tc"
warn_command "ethtool" "missing-ethtool" "ethtool"
warn_command "timedatectl" "missing-timedatectl" "timedatectl"

gradlew="$repo_root/gradlew"
if [[ -x "$gradlew" ]]; then
  append_check "gradle-wrapper" "pass" "Gradle wrapper is executable" "$(jq -n --arg path "$gradlew" '{path:$path}')"
else
  append_check "gradle-wrapper" "fail" "Gradle wrapper is missing or not executable" "$(jq -n --arg path "$gradlew" '{path:$path}')"
  append_issue "missing-gradle-wrapper" "error" "Gradle wrapper is required to launch benchmark workers" "$(jq -n --arg path "$gradlew" '{path:$path}')"
fi

if command -v java >/dev/null 2>&1; then
  java_version_output="$(java -version 2>&1 | head -n 1)"
  java_major="0"
  if [[ "$java_version_output" =~ \"([0-9]+) ]]; then
    java_major="${BASH_REMATCH[1]}"
  fi
  if [[ "$java_major" -ge 17 ]]; then
    append_check "java-version" "pass" "Java version is compatible" "$(jq -n --arg version "$java_version_output" --argjson major "$java_major" '{version:$version,major:$major}')"
  else
    append_check "java-version" "fail" "Java version is below 17" "$(jq -n --arg version "$java_version_output" --argjson major "$java_major" '{version:$version,major:$major}')"
    append_issue "java-too-old" "error" "Benchmark workers require JDK 17 or newer" "$(jq -n --arg version "$java_version_output" --argjson major "$java_major" '{version:$version,major:$major}')"
  fi
fi

if command -v ip >/dev/null 2>&1; then
  if ip link show dev "$interface" >/dev/null 2>&1; then
    interface_link="$(ip -o link show dev "$interface" 2>/dev/null || true)"
    interface_mtu="$(printf '%s\n' "$interface_link" | sed -n 's/.* mtu \([0-9][0-9]*\).*/\1/p' | head -n 1)"
    append_check "interface" "pass" "Interface exists" "$(jq -n --arg interface "$interface" '{interface:$interface}')"
    if [[ -n "$interface_mtu" ]]; then
      if [[ -n "$expect_mtu" && "$interface_mtu" != "$expect_mtu" ]]; then
        append_check "interface-mtu" "fail" "Interface MTU does not match expected value" "$(jq -n --arg interface "$interface" --argjson actual "$interface_mtu" --argjson expected "$expect_mtu" '{interface:$interface,actualMtu:$actual,expectedMtu:$expected}')"
        append_issue "interface-mtu-mismatch" "error" "Selected lab interface MTU differs from the expected value" "$(jq -n --arg interface "$interface" --argjson actual "$interface_mtu" --argjson expected "$expect_mtu" '{interface:$interface,actualMtu:$actual,expectedMtu:$expected}')"
      else
        append_check "interface-mtu" "pass" "Interface MTU recorded" "$(jq -n --arg interface "$interface" --argjson actual "$interface_mtu" --argjson expected "${expect_mtu:-0}" '{interface:$interface,actualMtu:$actual,expectedMtu:(if $expected == 0 then null else $expected end)}')"
      fi
    else
      append_check "interface-mtu" "warn" "Interface MTU could not be parsed" "$(jq -n --arg interface "$interface" '{interface:$interface}')"
      append_issue "interface-mtu-unparsed" "warning" "Selected lab interface MTU could not be parsed" "$(jq -n --arg interface "$interface" '{interface:$interface}')"
    fi
  else
    append_check "interface" "fail" "Interface does not exist" "$(jq -n --arg interface "$interface" '{interface:$interface}')"
    append_issue "missing-interface" "error" "Selected lab interface does not exist on this host" "$(jq -n --arg interface "$interface" '{interface:$interface}')"
  fi
fi

if command -v tc >/dev/null 2>&1; then
  if tc_qdisc_output="$(tc qdisc show dev "$interface" 2>&1)"; then
    append_check "tc-qdisc" "pass" "tc can inspect the selected interface" "$(jq -n --arg interface "$interface" --arg qdisc "$tc_qdisc_output" '{interface:$interface,qdisc:$qdisc}')"
    if printf '%s\n' "$tc_qdisc_output" | grep -Eq '(^|[[:space:]])netem($|[[:space:]])'; then
      if "$require_no_netem"; then
        append_check "tc-netem" "fail" "Selected interface already has a netem qdisc" "$(jq -n --arg interface "$interface" --arg qdisc "$tc_qdisc_output" '{interface:$interface,qdisc:$qdisc}')"
        append_issue "interface-netem-active" "error" "Selected lab interface already has netem active before the run" "$(jq -n --arg interface "$interface" --arg qdisc "$tc_qdisc_output" '{interface:$interface,qdisc:$qdisc}')"
      else
        append_check "tc-netem" "warn" "Selected interface already has a netem qdisc" "$(jq -n --arg interface "$interface" --arg qdisc "$tc_qdisc_output" '{interface:$interface,qdisc:$qdisc}')"
        append_issue "interface-netem-active" "warning" "Selected lab interface already has netem active before the run" "$(jq -n --arg interface "$interface" --arg qdisc "$tc_qdisc_output" '{interface:$interface,qdisc:$qdisc}')"
      fi
    else
      append_check "tc-netem" "pass" "No netem qdisc is active on the selected interface" "$(jq -n --arg interface "$interface" --arg qdisc "$tc_qdisc_output" '{interface:$interface,qdisc:$qdisc}')"
    fi
  else
    append_check "tc-qdisc" "fail" "tc cannot inspect the selected interface" "$(jq -n --arg interface "$interface" --arg output "$tc_qdisc_output" '{interface:$interface,output:$output}')"
    append_issue "tc-qdisc-unavailable" "error" "tc qdisc inspection must work for host-level impairment evidence" "$(jq -n --arg interface "$interface" --arg output "$tc_qdisc_output" '{interface:$interface,output:$output}')"
  fi
fi

cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf 0)"
append_check "cpu-count" "pass" "Online CPU count recorded" "$(jq -n --argjson cpuCount "$cpu_count" --argjson expectedMin "${expect_min_cpus:-0}" '{cpuCount:$cpuCount,expectedMinCpus:(if $expectedMin == 0 then null else $expectedMin end)}')"
if [[ -n "$expect_min_cpus" && "$cpu_count" -lt "$expect_min_cpus" ]]; then
  append_check "cpu-count-minimum" "fail" "Online CPU count is below expected minimum" "$(jq -n --argjson cpuCount "$cpu_count" --argjson expectedMin "$expect_min_cpus" '{cpuCount:$cpuCount,expectedMinCpus:$expectedMin}')"
  append_issue "cpu-count-below-minimum" "error" "Host has fewer online CPUs than expected for this lab topology" "$(jq -n --argjson cpuCount "$cpu_count" --argjson expectedMin "$expect_min_cpus" '{cpuCount:$cpuCount,expectedMinCpus:$expectedMin}')"
elif [[ -n "$expect_min_cpus" ]]; then
  append_check "cpu-count-minimum" "pass" "Online CPU count meets expected minimum" "$(jq -n --argjson cpuCount "$cpu_count" --argjson expectedMin "$expect_min_cpus" '{cpuCount:$cpuCount,expectedMinCpus:$expectedMin}')"
fi

governor_files=()
while IFS= read -r -d '' governor_file; do
  governor_files+=("$governor_file")
done < <(find /sys/devices/system/cpu -path '*/cpufreq/scaling_governor' -print0 2>/dev/null | sort -z)
if [[ "${#governor_files[@]}" -eq 0 ]]; then
  if "$require_cpu_performance"; then
    append_check "cpu-governor" "fail" "CPU governor files were not found"
    append_issue "cpu-governor-unavailable" "error" "CPU performance governor cannot be verified on this host"
  else
    append_check "cpu-governor" "warn" "CPU governor files were not found"
    append_issue "cpu-governor-unavailable" "warning" "CPU performance governor cannot be verified on this host"
  fi
else
  governors=()
  non_performance_count=0
  for governor_file in "${governor_files[@]}"; do
    governor_value="$(tr -d '\n' <"$governor_file" 2>/dev/null || printf unknown)"
    governors+=("$governor_value")
    if [[ "$governor_value" != "performance" ]]; then
      non_performance_count=$((non_performance_count + 1))
    fi
  done
  governors_json="$(printf '%s\n' "${governors[@]}" | jq -R -s 'split("\n") | map(select(length > 0))')"
  governor_extra="$(jq -n --argjson governorCount "${#governor_files[@]}" --argjson nonPerformanceCount "$non_performance_count" --argjson governors "$governors_json" '{governorCount:$governorCount,nonPerformanceCount:$nonPerformanceCount,governors:$governors}')"
  if [[ "$non_performance_count" -eq 0 ]]; then
    append_check "cpu-governor" "pass" "All visible CPU governors are performance" "$governor_extra"
  elif "$require_cpu_performance"; then
    append_check "cpu-governor" "fail" "One or more visible CPU governors is not performance" "$governor_extra"
    append_issue "cpu-governor-not-performance" "error" "CPU governors are not fixed to performance for a repeatable lab run" "$governor_extra"
  else
    append_check "cpu-governor" "warn" "One or more visible CPU governors is not performance" "$governor_extra"
    append_issue "cpu-governor-not-performance" "warning" "CPU governors are not fixed to performance for a repeatable lab run" "$governor_extra"
  fi
fi

if "$require_sudo_netem"; then
  sudo_path="$(command_path sudo)"
  if [[ -n "$sudo_path" ]]; then
    append_check "sudo" "pass" "sudo is available for generated netem scripts" "$(jq -n --arg path "$sudo_path" '{path:$path}')"
  else
    append_check "sudo" "fail" "sudo is missing"
    append_issue "missing-sudo" "error" "sudo is required because the generated handoff uses sudo netem scripts"
  fi
fi

if command -v timedatectl >/dev/null 2>&1; then
  ntp_sync="$(timedatectl show -p NTPSynchronized --value 2>/dev/null || printf unknown)"
  if [[ "$ntp_sync" == "yes" ]]; then
    append_check "clock-sync" "pass" "timedatectl reports synchronized clock" "$(jq -n --arg ntpSynchronized "$ntp_sync" '{ntpSynchronized:$ntpSynchronized}')"
  elif "$require_clock_sync"; then
    append_check "clock-sync" "fail" "timedatectl does not report synchronized clock" "$(jq -n --arg ntpSynchronized "$ntp_sync" '{ntpSynchronized:$ntpSynchronized}')"
    append_issue "clock-sync-unknown" "error" "NTP synchronization must be verified before coordinated start timestamps" "$(jq -n --arg ntpSynchronized "$ntp_sync" '{ntpSynchronized:$ntpSynchronized}')"
  else
    append_check "clock-sync" "warn" "timedatectl does not report synchronized clock" "$(jq -n --arg ntpSynchronized "$ntp_sync" '{ntpSynchronized:$ntpSynchronized}')"
    append_issue "clock-sync-unknown" "warning" "NTP synchronization should be verified before coordinated start timestamps" "$(jq -n --arg ntpSynchronized "$ntp_sync" '{ntpSynchronized:$ntpSynchronized}')"
  fi
elif "$require_clock_sync"; then
  append_check "clock-sync" "fail" "timedatectl is missing"
  append_issue "clock-sync-unverified" "error" "Clock synchronization cannot be verified because timedatectl is missing"
fi

checks_array="$(jq -s '.' "$checks_jsonl")"
issues_array="$(jq -s '.' "$issues_jsonl")"
expected_mtu_json="null"
if [[ -n "$expect_mtu" ]]; then
  expected_mtu_json="$expect_mtu"
fi
expected_min_cpus_json="null"
if [[ -n "$expect_min_cpus" ]]; then
  expected_min_cpus_json="$expect_min_cpus"
fi
interface_mtu_json="null"
if [[ -n "${interface_mtu:-}" ]]; then
  interface_mtu_json="$interface_mtu"
fi

jq -n \
  --arg checkedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg hostRole "$host_role" \
  --arg hostname "$(hostname 2>/dev/null || printf unknown)" \
  --arg repoRoot "$repo_root" \
  --arg interface "$interface" \
  --arg gitRevision "$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || printf unknown)" \
  --argjson cpuCount "$cpu_count" \
  --argjson requireSudoNetem "$require_sudo_netem" \
  --argjson requireClockSync "$require_clock_sync" \
  --argjson requireCpuPerformance "$require_cpu_performance" \
  --argjson requireNoNetem "$require_no_netem" \
  --argjson expectedMtu "$expected_mtu_json" \
  --argjson expectedMinCpus "$expected_min_cpus_json" \
  --argjson interfaceMtu "$interface_mtu_json" \
  --argjson checks "$checks_array" \
  --argjson issues "$issues_array" \
  '{
    checkedAt: $checkedAt,
    ready: (($issues | map(select(.severity == "error")) | length) == 0),
    errorCount: ($issues | map(select(.severity == "error")) | length),
    warningCount: ($issues | map(select(.severity == "warning")) | length),
    hostRole: $hostRole,
    hostname: $hostname,
    repoRoot: $repoRoot,
    interface: $interface,
    interfaceMtu: $interfaceMtu,
    gitRevision: $gitRevision,
    cpuCount: $cpuCount,
    requireSudoNetem: $requireSudoNetem,
    requireClockSync: $requireClockSync,
    requireCpuPerformance: $requireCpuPerformance,
    requireNoNetem: $requireNoNetem,
    expectedMtu: $expectedMtu,
    expectedMinCpus: $expectedMinCpus,
    checks: $checks,
    issues: $issues
  }' >"$prereq_json"

{
  echo "# Lab Host Prerequisites"
  echo
  echo "- Checked: \`$(jq -r '.checkedAt' "$prereq_json")\`"
  echo "- Result: \`$(jq -r 'if .ready then "ready" else "not-ready" end' "$prereq_json")\`"
  echo "- Host role: \`$host_role\`"
  echo "- Hostname: \`$(jq -r '.hostname' "$prereq_json")\`"
  echo "- Interface: \`$interface\`"
  echo "- Interface MTU: \`$(jq -r '.interfaceMtu // "unknown"' "$prereq_json")\`"
  echo "- Online CPUs: \`$(jq -r '.cpuCount' "$prereq_json")\`"
  echo "- Errors: \`$(jq -r '.errorCount' "$prereq_json")\`"
  echo "- Warnings: \`$(jq -r '.warningCount' "$prereq_json")\`"
  echo "- Strict clock sync: \`$(jq -r '.requireClockSync' "$prereq_json")\`"
  echo "- Strict CPU governor: \`$(jq -r '.requireCpuPerformance' "$prereq_json")\`"
  echo "- Strict no-netem: \`$(jq -r '.requireNoNetem' "$prereq_json")\`"
  echo
  echo "## Checks"
  echo
  echo "| Check | Status | Message |"
  echo "| --- | --- | --- |"
  jq -r '.checks[] | "| `\(.name)` | `\(.status)` | \(.message) |"' "$prereq_json"
  echo
  echo "## Issues"
  echo
  if jq -e '.issues | length == 0' "$prereq_json" >/dev/null; then
    echo "No issues found."
  else
    echo "| Severity | Code | Message |"
    echo "| --- | --- | --- |"
    jq -r '.issues[] | "| `\(.severity)` | `\(.code)` | \(.message) |"' "$prereq_json"
  fi
} >"$prereq_md"

echo "Prereq JSON: $prereq_json"
echo "Prereq report: $prereq_md"

if jq -e '.ready == true' "$prereq_json" >/dev/null; then
  exit 0
fi
exit 1
