# Established RakNet Benchmarking

This benchmark kit measures established RakNet channel behavior. It does not measure unconnected ping/pong, OCR1/OCR2, cookie handling, or DDoS-offload paths.

The primary benchmark shape is server-to-client bulk traffic with a separate small probe stream. Bulk messages load the RakNet stack; probes are echoed by clients so the server can report probe RTT while the bulk stream is active.

See [`baseline-matrix.md`](baseline-matrix.md) for the recommended recurring baseline matrix and current production-synthetic gaps. See [`production-usage-evidence.md`](production-usage-evidence.md) for the source evidence behind the synthetic, and [`lab-baseline-runbook.md`](lab-baseline-runbook.md) for the lab workflow that captures host state, remote-worker topology, impairment profiles, and baseline-versus-candidate comparison artifacts.

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

`summary.json` and `report.md` include per-case stability rows. For curve and matrix scenarios, read stability per rate/payload/reliability case rather than across the whole run.

## Local Smoke Runs

Local loopback runs are useful for regression checks, but they are not proof of NIC line rate.

To run the repeatable smoke suite and write a suite manifest:

```bash
benchmark/scripts/run-baseline-matrix.sh --profile smoke
```

Use `--dry-run` to inspect the command set without executing it, `--only <case-substring>` to run one case, and `--out <dir>` to control the suite artifact directory.

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="baseline-bandwidth --clients 1 --warmup 2s --duration 10s --iterations 3 --payload-size 512"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 2s --duration 10s --rates-mbps 100,500,1000,unlimited"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --clients 100 --warmup 2s --duration 10s --payload-size 256 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="fairness --clients 100 --impaired-clients 10 --warmup 2s --duration 15s --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode close --warmup 2s --duration 15s --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode stop-reading --warmup 2s --duration 15s --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 5s --disappear-mode blackhole --warmup 2s --duration 15s --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="batched-game-traffic --clients 100 --warmup 2s --duration 10s --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5"
```

Use `--rate-mbps 0` or `--rates-mbps unlimited` for an uncapped sender. Use `--target-gbps 1` as shorthand for `--rate-mbps 1000`. For production-style fanout, prefer `--per-client-mbps 5`; the runner converts that to aggregate offered rate from the established client count.

RakNet's server packet limiter stays at library defaults unless overridden. For one-client best-case bandwidth sweeps, record both the default-limiter result and a raised-limiter result:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 250,500,750,1000,unlimited"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 250,500,750,1000,unlimited --packet-limit 100000 --global-packet-limit 1000000"
```

Use the default-limiter run to understand out-of-box behavior, and the raised-limiter run to avoid mistaking the anti-abuse guardrail for the established-channel send/receive ceiling. The selected limits are written into `summary.json` and `report.md`.

The `fairness` scenario supports benchmark-managed impairment for the first `--impaired-clients` clients. The impairment is enabled only after the client channel is established, so local runs stay focused on established-channel behavior:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="fairness --clients 100 --impaired-clients 10 --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5"
```

Use this for repeatable local healthy-versus-poor-client smoke runs. Use `tc netem` or remote workers when you need host/NIC-level impairment for lab claims.

The `disappearing-clients` scenario supports `--disappear-mode close` for clean disconnect churn, `--disappear-mode stop-reading` for local retry-pressure smoke runs where selected clients stop reading while the server keeps sending, and `--disappear-mode blackhole` for benchmark-managed datagram drops after the connection is established. In remote-worker lab runs, pass matching `--disappearing-clients`, `--disappear-after`, and `--disappear-mode blackhole` values to the receiver workers and the server worker so both sides label the same affected client set. Use external `tc`/routing rules when you need host/NIC-level blackhole behavior outside the JVM.

The `batched-game-traffic` scenario sends bursty, length-framed synthetic batches on a fixed flush cadence. Use `--batch-interval 10ms|20ms|50ms`, `--logical-packets-per-batch`, `--batch-payload-sizes`, and `--batch-groups` to approximate CubeCraft, Nukkit, Cloudburst, and Geyser-style grouped fanout. Compression is not modeled yet; batch payload sizes represent already-encoded batch bytes.

## Baseline Suite Runner

The baseline runner executes named cases and writes:

- per-case benchmark artifacts under the suite output directory
- `manifest.jsonl` with command, status, timestamps, and artifact path
- `suite-summary.csv` and `suite-summary.jsonl` with key metrics extracted from each successful case, including per-client delivered Mbps percentiles
- `suite-aggregate.csv` and `suite-aggregate.jsonl` with per-case median throughput, healthy/affected throughput, per-client delivered Mbps percentiles, median p99 probe RTT, impairment profile, spread, retry-pressure totals, and unstable flags
- `README.md` with a compact case table

Profiles:

```bash
benchmark/scripts/run-baseline-matrix.sh --profile smoke --dry-run
benchmark/scripts/run-baseline-matrix.sh --profile local --out benchmark/build/benchmark-results/local-baseline
benchmark/scripts/run-baseline-matrix.sh --profile lab --out benchmark/build/benchmark-results/lab-baseline
```

`smoke` is short and intended for local regression. `local` is longer but still loopback-only. `lab` matches the recurring baseline matrix and should be used on controlled hosts/NICs, optionally with `tc netem` applied outside the JVM.

For baseline-of-record runs, follow [`lab-baseline-runbook.md`](lab-baseline-runbook.md) and capture host reports with `benchmark/scripts/capture-lab-host.sh` before running the suite.

To repeat a baseline suite under a stable set of host-level impairments, use the impairment matrix wrapper. It defaults to a dry-run plan so the selected NIC and commands can be reviewed before changing host qdisc state:

```bash
benchmark/scripts/run-impairment-matrix.sh \
  --interface eth0 \
  --runner-profile smoke \
  --only bestcase-1c-medium
```

Run with `--execute` to apply each profile, run the suite, then clear the qdisc before moving to the next profile:

```bash
benchmark/scripts/run-impairment-matrix.sh \
  --interface eth0 \
  --runner-profile lab \
  --out benchmark/build/benchmark-results/lab-impairment \
  --sudo-netem \
  --execute
```

The wrapper writes `impairment-manifest.jsonl`, a top-level `README.md`, and one baseline-suite artifact directory per impairment profile. Use `--profiles perfect,near-loss,regional-loss,poor,severe` to select profiles and `--common-args "..."` to append shared benchmark arguments to every case.

To compare an optimization branch against a saved baseline, run the same suite shape twice and compare the generated suite directories:

```bash
benchmark/scripts/run-baseline-matrix.sh --profile lab --out benchmark/build/benchmark-results/lab-baseline
benchmark/scripts/run-baseline-matrix.sh --profile lab --out benchmark/build/benchmark-results/lab-candidate
benchmark/scripts/compare-baseline-suite.sh \
  --baseline benchmark/build/benchmark-results/lab-baseline \
  --candidate benchmark/build/benchmark-results/lab-candidate \
  --out benchmark/build/benchmark-results/lab-comparison.md
```

The comparison matches aggregate rows by case and benchmark scenario, or raw summary rows by case, benchmark scenario, and iteration. It exits non-zero when a candidate row is missing, when the impairment profile differs, or when delivered throughput, p99 probe RTT, or max queued bytes breach the configured regression thresholds. Reports also include per-client delivered Mbps percentiles plus healthy-vs-affected throughput and fairness deltas for contention cases. Defaults are `10%` throughput regression, `10%` p99 latency regression, and `50%` queue growth regression.

When comparing suite directories, `compare-baseline-suite.sh` uses `suite-aggregate.jsonl` if present, so baseline-of-record comparisons operate on per-case medians and include throughput/p99 stability spread. Pass explicit `suite-summary.jsonl` paths only when you want raw per-iteration comparison.

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
- `perClientThroughput`: min/p50/p95/p99/max delivered Mbps across all clients
- `healthyClientThroughput` and `affectedClientThroughput`: delivered Mbps percentiles split by clients not marked impaired/disappearing versus clients that are affected
- `packetLimit` and `globalPacketLimit`: configured RakNet server packet-limit overrides, or `null` when library defaults were used
- `impairmentLatencyMillis`, `impairmentJitterMillis`, and `impairmentLossPercent`: benchmark-managed client impairment applied to marked impaired clients
- `stability`: per-case delivered-throughput and p99 probe RTT spread, plus unstable reasons
- `deliveredLogicalPacketsPerSecond`: synthetic logical game packets delivered per second for batch runs
- `disappearanceMode`: clean close or stop-reading behavior for disappearance runs
- `probeRttP95Millis` and `probeRttP99Millis`: latency under bulk load
- `fairnessIndex`: Jain fairness index across clients, where `1.0` is perfectly even delivery
- `healthyFairnessIndex`: Jain fairness for clients not marked impaired/disappearing
- `affectedDeliveredGbps` and `affectedFairnessIndex`: throughput and fairness for impaired/disappearing clients
- `disconnects`: established channels closed during the measured iteration
- `blackholedDatagramsIn` and `blackholedDatagramsOut`: benchmark-managed datagrams dropped by `--disappear-mode blackhole`
- `staleDatagrams`, `nackIn`, `nackOut`: retransmission pressure
- `maxQueuedBytes`: largest observed RakNet queued payload bytes per channel

Treat a case as unstable when throughput or p99 probe RTT spread is above 10 percent across measured iterations, or when there are not enough iterations to calculate spread. Increase duration, reduce unrelated host activity, and rerun before comparing code changes.
