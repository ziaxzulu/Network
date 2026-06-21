#!/usr/bin/env bash
set -euo pipefail

out_dir=""
interface=""

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/capture-lab-host.sh --out DIR [--interface eth0]

Captures host, JVM, kernel, NIC, and git context next to benchmark artifacts so
baseline and candidate runs can be compared against the same lab conditions.
The script is read-only and skips optional commands that are not installed.
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

if [[ -z "$out_dir" ]]; then
  usage >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
if [[ "$out_dir" != /* ]]; then
  out_dir="$repo_root/$out_dir"
fi

mkdir -p "$out_dir"
host_report="$out_dir/host-report.md"

append_command() {
  local title="$1"
  shift
  {
    echo "## $title"
    echo
    echo '```text'
    if command -v "$1" >/dev/null 2>&1; then
      "$@" 2>&1 || true
    else
      echo "command not found: $1"
    fi
    echo '```'
    echo
  } >>"$host_report"
}

append_shell() {
  local title="$1"
  local command="$2"
  {
    echo "## $title"
    echo
    echo '```text'
    bash -lc "$command" 2>&1 || true
    echo '```'
    echo
  } >>"$host_report"
}

{
  echo "# Benchmark Host Report"
  echo
  echo "- Captured UTC: \`$(date -u +%Y-%m-%dT%H:%M:%SZ)\`"
  echo "- Hostname: \`$(hostname 2>/dev/null || printf unknown)\`"
  echo "- Repo: \`$repo_root\`"
  echo "- Git revision: \`$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || printf unknown)\`"
  echo "- Git status: \`$(git -C "$repo_root" status --short 2>/dev/null | wc -l | tr -d ' ')\` changed path(s)"
  if [[ -n "$interface" ]]; then
    echo "- Interface: \`$interface\`"
  fi
  echo
} >"$host_report"

append_command "Kernel" uname -a
append_command "CPU" lscpu
append_command "Memory" free -h
append_command "Java" java -version
append_command "Gradle" "$repo_root/gradlew" --version
append_command "IP Addresses" ip addr
append_command "IP Routes" ip route
append_command "Queue Disciplines" tc qdisc show
append_shell "Interrupt Affinity" "grep -H . /proc/irq/*/smp_affinity_list 2>/dev/null | head -200"
append_shell "TCP/UDP Kernel Settings" "sysctl net.core.rmem_max net.core.wmem_max net.core.netdev_max_backlog net.ipv4.udp_mem net.ipv4.udp_rmem_min net.ipv4.udp_wmem_min 2>/dev/null"

if [[ -n "$interface" ]]; then
  append_command "Interface Link" ip -details link show dev "$interface"
  append_command "Interface Stats" ip -s link show dev "$interface"
  append_command "Ethtool Driver" ethtool -i "$interface"
  append_command "Ethtool Channels" ethtool -l "$interface"
  append_command "Ethtool Ring" ethtool -g "$interface"
  append_command "Ethtool Offloads" ethtool -k "$interface"
fi

echo "Host report: $host_report"
