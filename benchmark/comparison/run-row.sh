#!/usr/bin/env bash
set -euo pipefail
comparison_root=$(cd "$(dirname "$0")" && pwd)
if [[ ${COMPARISON_IN_NS:-} != 1 ]]; then
    exec unshare -Urn env COMPARISON_IN_NS=1 "$comparison_root/run-row.sh" "$@"
fi
cd "$comparison_root"
ip link set dev lo up
# All unclassified traffic uses the clean band. Only the affected client IP
# crosses netem, in both directions, including native STUN/DTLS/SCTP traffic.
tc qdisc add dev lo root handle 1: prio bands 3 priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
tc qdisc add dev lo parent 1:3 handle 30: netem limit 100000 delay 0ms
tc filter add dev lo protocol ip parent 1: prio 1 u32 match ip protocol 17 0xff match ip dst 127.0.0.2/32 flowid 1:3
tc filter add dev lo protocol ip parent 1: prio 2 u32 match ip protocol 17 0xff match ip src 127.0.0.2/32 flowid 1:3
native_library="$comparison_root/.inputs/.native-deps/libdatachannel-java-d855d4f3e9995b7ad926e6c0d23d0095666b2570/build/benchmark-release/libdatachannel-java.so"
test -s "$native_library"
exec taskset -c "${COMPARISON_CPUS:-0-3}" "${COMPARISON_JAVA:-/usr/lib/jvm/java-26-temurin-jdk/bin/java}" \
    -Xms1g -Xmx1g -XX:ActiveProcessorCount=4 \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
    "-Dlibdatachannel.native.datachannel-java.path=$native_library" \
    -cp "$comparison_root/build/install/transport-comparison/lib/*" \
    org.cloudburstmc.netty.benchmark.ComparisonMain "$@"
