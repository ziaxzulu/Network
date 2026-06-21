#!/usr/bin/env bash
set -euo pipefail

action="dry-run"
interface=""
latency="0ms"
jitter=""
loss="0%"

usage() {
  cat <<'USAGE'
Usage:
  raknet-netem.sh --interface eth0 --action dry-run --latency 50ms --jitter 5ms --loss 2%
  raknet-netem.sh --interface eth0 --action apply --latency 100ms --loss 5%
  raknet-netem.sh --interface eth0 --action status
  raknet-netem.sh --interface eth0 --action clear

Actions:
  dry-run  Print tc commands without applying them.
  apply    Apply/replace the root netem qdisc.
  clear    Remove the root qdisc.
  status   Show the current qdisc state.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --action)
      action="$2"
      shift 2
      ;;
    --interface)
      interface="$2"
      shift 2
      ;;
    --latency)
      latency="$2"
      shift 2
      ;;
    --jitter)
      jitter="$2"
      shift 2
      ;;
    --loss)
      loss="$2"
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

if [[ -z "$interface" ]]; then
  echo "--interface is required" >&2
  usage >&2
  exit 2
fi

build_apply_command() {
  local cmd=(tc qdisc replace dev "$interface" root netem)
  if [[ -n "$latency" && "$latency" != "0" && "$latency" != "0ms" ]]; then
    cmd+=(delay "$latency")
    if [[ -n "$jitter" && "$jitter" != "0" && "$jitter" != "0ms" ]]; then
      cmd+=("$jitter")
    fi
  fi
  if [[ -n "$loss" && "$loss" != "0" && "$loss" != "0%" ]]; then
    cmd+=(loss "$loss")
  fi
  printf '%q ' "${cmd[@]}"
  printf '\n'
}

run_command() {
  echo "+ $*"
  "$@"
}

case "$action" in
  dry-run)
    build_apply_command
    ;;
  apply)
    read -r -a command <<<"$(build_apply_command)"
    run_command "${command[@]}"
    ;;
  clear)
    run_command tc qdisc del dev "$interface" root
    ;;
  status)
    run_command tc qdisc show dev "$interface"
    ;;
  *)
    echo "Unknown action: $action" >&2
    usage >&2
    exit 2
    ;;
esac
