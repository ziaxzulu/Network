#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 2 ]]; then
  echo "Usage: validate-qdisc-timeseries.sh FILE NAMESPACE:INTERFACE [NAMESPACE:INTERFACE ...]" >&2
  exit 2
fi

timeseries="$1"
shift

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to validate qdisc evidence" >&2
  exit 2
fi
if [[ ! -s "$timeseries" ]]; then
  echo "Qdisc timeseries is missing or empty: $timeseries" >&2
  exit 1
fi
if ! jq -e -s '
  length > 0 and
  all(.[];
    type == "object" and
    (.epochMillis | type == "number") and
    (.namespace | type == "string") and
    (.interface | type == "string") and
    (.qdisc | type == "array") and
    (has("error") | not))
' "$timeseries" >/dev/null; then
  echo "Qdisc timeseries contains malformed or failed samples: $timeseries" >&2
  exit 1
fi

for target in "$@"; do
  if [[ "$target" != *:* ]]; then
    echo "Invalid expected qdisc target: $target" >&2
    exit 2
  fi
  namespace="${target%%:*}"
  interface="${target##*:}"
  if ! jq -e --arg namespace "$namespace" --arg interface "$interface" \
    'select(.namespace == $namespace and .interface == $interface)' \
    "$timeseries" >/dev/null; then
    echo "Qdisc timeseries has no successful sample for $namespace/$interface" >&2
    exit 1
  fi
done
