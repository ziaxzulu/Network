# Established RakNet Benchmarking

This benchmark kit measures established RakNet channel behavior. It does not measure unconnected ping/pong, OCR1/OCR2, cookie handling, or DDoS-offload paths.

The primary benchmark shape is server-to-client bulk traffic with a separate small probe stream. Bulk messages load the RakNet stack; probes are echoed by clients so the server can report probe RTT while the bulk stream is active.

See [`baseline-matrix.md`](baseline-matrix.md) for the recommended recurring baseline matrix and current production-synthetic gaps.

## Build

The published transport modules keep Java 8 bytecode compatibility. The benchmark module is non-published and uses the latest configured Java toolchain so the harness can use modern JVM features. Run:

```bash
./gradlew :transport-raknet:test
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="baseline-bandwidth --clients 1 --duration 10s"
```

Benchmark artifacts are written to:

```text
benchmark/build/benchmark-results/<run-id>/
```

Each run writes:

- `summary.json`
- `timeseries.csv`
- `latency.hdr`
- `report.md`

## Local Smoke Runs

Local loopback runs are useful for regression checks, but they are not proof of NIC line rate.

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="baseline-bandwidth --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 512"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --rates-mbps 100,500,1000,unlimited"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --clients 100 --warmup 2s --duration 10s --payload-size 256 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="fairness --clients 100 --impaired-clients 10 --warmup 2s --duration 15s --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode close --warmup 2s --duration 15s --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode stop-reading --warmup 2s --duration 15s --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="batched-game-traffic --clients 100 --warmup 2s --duration 10s --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5"
```

Use `--rate-mbps 0` or `--rates-mbps unlimited` for an uncapped sender. Use `--target-gbps 1` as shorthand for `--rate-mbps 1000`. For production-style fanout, prefer `--per-client-mbps 5`; the runner converts that to aggregate offered rate from the established client count.

The `disappearing-clients` scenario supports `--disappear-mode close` for clean disconnect churn and `--disappear-mode stop-reading` for local retry-pressure smoke runs where selected clients stop reading while the server keeps sending. Use external `tc`/routing rules or remote workers for true blackhole profiles.

The `batched-game-traffic` scenario sends bursty, length-framed synthetic batches on a fixed flush cadence. Use `--batch-interval 10ms|20ms|50ms`, `--logical-packets-per-batch`, `--batch-payload-sizes`, and `--batch-groups` to approximate CubeCraft, Nukkit, Cloudburst, and Geyser-style grouped fanout. Compression is not modeled yet; batch payload sizes represent already-encoded batch bytes.

## Remote Worker Runs

For lab validation, run the server and receiver workers on separate machines. Start the server first:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="server-worker --role server --bind-host 0.0.0.0 --port 19132 --clients 1000 --start-delay 30s --warmup 5s --duration 30s --iterations 3 --payload-size 512 --per-client-mbps 5"
```

Then start receivers from one or more client machines:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="receiver-worker --role client --host <server-ip> --port 19132 --clients 250 --warmup 5s --duration 120s"
```

The server report contains send-side RakNet metrics and probe RTTs. Receiver reports contain delivered bytes/messages from that worker. For line-rate work, pin JVMs and interrupts consistently between runs and keep other host traffic quiet.

## Matrix Profiles

The `matrix` scenario sweeps payload size, reliability, and offered rate:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="matrix --clients 100 --payload-sizes 64,512,1200 --reliabilities reliable_ordered,reliable,unreliable --rates-mbps 100,500,1000,unlimited --warmup 5s --duration 30s"
```

Network impairment is applied outside the JVM with Linux `tc netem`.

```bash
benchmark/scripts/raknet-netem.sh --interface eth0 --action dry-run --latency 50ms --jitter 5ms --loss 2%
sudo benchmark/scripts/raknet-netem.sh --interface eth0 --action apply --latency 50ms --jitter 5ms --loss 2%
sudo benchmark/scripts/raknet-netem.sh --interface eth0 --action clear
```

Recommended impairment matrix:

- Latency: `10ms`, `50ms`, `100ms`, `200ms`
- Loss: `0%`, `2%`, `5%`, `10%`
- Optional jitter: start with `0ms`, then add a latency-proportional value such as `5ms` on selected runs
- Payloads: small gameplay-like messages, medium messages, and near-MTU or split messages

## Reading Results

Start with `report.md` for a compact table. Use `summary.json` for automation and `timeseries.csv` for comparisons across runs.

Important fields:

- `deliveredGbps`: receiver-observed payload throughput
- `offeredGbps`: benchmark sender payload rate
- `targetClientMbps`: configured or derived per-client offered target
- `deliveredLogicalPacketsPerSecond`: synthetic logical game packets delivered per second for batch runs
- `disappearanceMode`: clean close or stop-reading behavior for disappearance runs
- `probeRttP95Millis` and `probeRttP99Millis`: latency under bulk load
- `fairnessIndex`: Jain fairness index across clients, where `1.0` is perfectly even delivery
- `healthyFairnessIndex`: Jain fairness for clients not marked impaired/disappearing
- `disconnects`: established channels closed during the measured iteration
- `staleDatagrams`, `nackIn`, `nackOut`: retransmission pressure
- `maxQueuedBytes`: largest observed RakNet queued payload bytes per channel

Treat results as unstable when throughput or p99 probe RTT spread is above 10 percent across measured iterations. Increase duration, reduce unrelated host activity, and rerun before comparing code changes.
