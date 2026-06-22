# Established RakNet Benchmarking

This benchmark kit measures established RakNet channel behavior. It does not measure unconnected ping/pong, OCR1/OCR2, cookie handling, or DDoS-offload paths.

The primary benchmark shape is server-to-client bulk traffic with a separate small probe stream. Bulk messages load the RakNet stack; probes are echoed by clients so the server can report probe RTT while the bulk stream is active.

See [`baseline-status.md`](baseline-status.md) for the current baseline handoff state, [`baseline-matrix.md`](baseline-matrix.md) for the recommended recurring baseline matrix and current production-synthetic gaps, [`production-usage-evidence.md`](production-usage-evidence.md) for the source evidence behind the synthetic, and [`lab-baseline-runbook.md`](lab-baseline-runbook.md) for the lab workflow that captures host state, remote-worker topology, impairment profiles, and baseline-versus-candidate comparison artifacts.

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

For remote lab plans, pass `--raised-packet-limit` and `--raised-global-packet-limit` to `plan-lab-baseline.sh`. The generated plan schedules a second curve campaign and combines both default and raised-limiter capacity rows in the final baseline output.

The `fairness` scenario supports benchmark-managed impairment for the first `--impaired-clients` clients. The impairment is enabled only after the client channel is established, so local runs stay focused on established-channel behavior:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="fairness --clients 100 --impaired-clients 10 --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5"
```

Use this for repeatable local healthy-versus-poor-client smoke runs. Use `tc netem` or remote workers when you need host/NIC-level impairment for lab claims.

The `disappearing-clients` scenario supports `--disappear-mode close` for clean disconnect churn, `--disappear-mode stop-reading` for local retry-pressure smoke runs where selected clients stop reading while the server keeps sending, and `--disappear-mode blackhole` for benchmark-managed datagram drops after the connection is established. In remote-worker lab runs, pass matching `--disappearing-clients`, `--disappear-after`, and `--disappear-mode blackhole` values to the receiver workers and the server worker so both sides label the same affected client set. Use external `tc`/routing rules when you need host/NIC-level blackhole behavior outside the JVM.

Use `--max-queued-bytes N` to override per-session `RAK_MAX_QUEUED_BYTES` for slow-client and disappearance cap sweeps. The configured cap is recorded as `configuredMaxQueuedBytes`; the observed `maxQueuedBytes` metric remains the largest queue depth seen during the run.

The `batched-game-traffic` scenario sends bursty, length-framed synthetic batches on a fixed flush cadence. Use `--batch-interval 10ms|20ms|50ms`, `--logical-packets-per-batch`, `--batch-payload-sizes`, and `--batch-groups` to approximate CubeCraft, Nukkit, Cloudburst, and Geyser-style grouped fanout. Compression is not modeled yet; batch payload sizes represent already-encoded batch bytes.

## Baseline Suite Runner

The baseline runner executes named cases and writes:

- per-case benchmark artifacts under the suite output directory
- `manifest.jsonl` with command, status, timestamps, and artifact path
- `suite-summary.csv` and `suite-summary.jsonl` with key metrics extracted from each successful case, including per-client delivered Mbps percentiles, server send-work ratios, and retry-pressure rates
- `suite-aggregate.csv` and `suite-aggregate.jsonl` with per-case median throughput, healthy/affected throughput, per-client delivered Mbps percentiles, send/deliver ratios, median p99 probe RTT, impairment profile, packet-limit settings, spread, retry-pressure rates/totals, and unstable flags
- `bandwidth-capacity.jsonl`, `bandwidth-capacity.csv`, and `bandwidth-capacity.md` with the highest stable delivered bandwidth selected from bandwidth-latency curve rows
- `README.md` with a compact case table

Profiles:

```bash
benchmark/scripts/run-baseline-matrix.sh --profile smoke --dry-run
benchmark/scripts/run-baseline-matrix.sh --profile local --out benchmark/build/benchmark-results/local-baseline
benchmark/scripts/run-baseline-matrix.sh --profile lab --out benchmark/build/benchmark-results/lab-baseline
```

`smoke` is short and intended for local regression. Because smoke cases use one measured iteration, aggregate rows are marked `insufficient-iterations`; use them to verify execution and artifact shape, not baseline stability. `local` is longer but still loopback-only. `lab` matches the recurring baseline matrix and should be used on controlled hosts/NICs, optionally with `tc netem` applied outside the JVM.

For baseline-of-record runs, follow [`lab-baseline-runbook.md`](lab-baseline-runbook.md) and capture host reports with `benchmark/scripts/capture-lab-host.sh` before running the suite.

The suite runner writes a stable bandwidth capacity report automatically after aggregate generation. The selector requires at least three measured iterations and rejects aggregate rows already marked unstable, rows with zero delivered throughput, rows with disconnects, and any rows that exceed optional gates:

```bash
benchmark/scripts/select-stable-bandwidth.sh \
  --input benchmark/build/benchmark-results/lab-baseline \
  --max-p99-ms 20 \
  --max-queue-bytes 1048576 \
  --max-send-deliver-ratio 1.2
```

Use the selected stable row as the capacity baseline. The report also shows the best observed row separately so unstable or queue-heavy high-throughput results remain visible.

For lab baselines, validate the merged aggregate before using it as a comparison baseline:

```bash
benchmark/scripts/validate-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline
```

The validator writes `validation.json` and `validation.md`, checks required scenario families, requires the configured measured-iteration count, rejects unstable rows, zero-delivery rows, and unexpected curve/fanout disconnects, verifies planned manifest rows when manifests are provided, requires bandwidth capacity groups to select a stable candidate, and requires `topology.md` plus at least two captured host reports from at least two distinct hostnames by default. Plans generated by `plan-lab-baseline.sh` run this validator automatically from `merge-all.sh`.

For lab-generated baselines, the planner also passes contention gates into validation: healthy-client Jain fairness must stay at or above `0.95`, healthy-client send/deliver byte ratio must stay at or below `1.2`, affected-client send/deliver byte ratio must stay at or below `5`, merged contention rows must keep the planned client count, and merged contention rows must keep at least the planned per-client offered Mbps. Override the fairness/send-work gates with `--min-healthy-fairness`, `--max-healthy-send-deliver-ratio`, `--max-affected-send-deliver-ratio`, and `--max-contention-p99-ms` when a topology needs a different acceptance policy.

After validation passes, promote the lab run into a compact baseline package:

```bash
benchmark/scripts/promote-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan/curve-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan/curve-raised-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan/contention-plan/manifest.jsonl \
  --out benchmark/build/benchmark-baselines \
  --name lab-<date>-<topology>
```

The promotion script reruns validation, then writes `BASELINE.md`, `baseline-manifest.json`, `suite-aggregate.jsonl`, `validation.*`, capacity selector files, topology metadata, host reports, and copied planning manifests under the promoted baseline directory. It also updates `benchmark/build/benchmark-baselines/latest` unless `--no-latest` is supplied.

For remote lab campaigns where the impairment must happen outside the JVM, generate one coordinated lab baseline plan per host-level profile:

```bash
benchmark/scripts/prepare-lab-baseline-handoff.sh \
  --out benchmark/build/benchmark-results/lab-handoff \
  --artifact-root benchmark/build/benchmark-results/lab-run \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --sudo-netem
```

That top-level handoff command creates both the perfect-network baseline plan and the host/NIC-level impairment campaign plan. Use it for baseline-of-record prep; use the lower-level impairment planner directly when you need a custom profile set or are debugging one campaign layer.

```bash
benchmark/scripts/plan-lab-impairment.sh \
  --out benchmark/build/benchmark-results/lab-impairment-plan \
  --artifact-root benchmark/build/benchmark-results/lab-impairment \
  --interface <nic> \
  --target-host-role receiver-a \
  --sudo-netem \
  -- \
  --server-host <server-ip> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=100 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000
```

The impairment planner writes per-profile `plan-lab-baseline.sh` outputs plus `netem/<profile>-apply.sh`, `netem/<profile>-status.sh`, and `netem/<profile>-clear.sh` for the shaped receiver host or network namespace. Each generated netem script writes timestamped command output under `<profile artifact root>/netem/`; copy that directory back with the receiver artifacts so the baseline records the actual qdisc state. The generated `validate-all.sh` requires `<profile>-status-*.txt` evidence by default; use `REQUIRE_NETEM_EVIDENCE=false` only for non-baseline smoke validation. After profile merge and validation, `summarize-campaign.sh` writes `campaign-summary/impairment-summary.json`, `impairment-summary.jsonl`, and `impairment-summary.md` with per-profile validation status, capacity selections, contention rows, and netem evidence counts. Promote a passing campaign summary with `promote-lab-impairment.sh` before using it as the saved adverse-network baseline. Use it when you need the same remote worker baseline under perfect, near-loss, regional-loss, poor, and severe host/NIC conditions.

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
  --out benchmark/build/benchmark-results/lab-comparison.md \
  --require-validation
```

The comparison matches aggregate rows by case and benchmark scenario, or raw summary rows by case, benchmark scenario, and iteration. It exits non-zero when a candidate row is missing, when matrix shape differs (clients, payload, reliability, batching, target rate, impairment, packet limits, or queue cap), when delivered throughput, p99 probe RTT, or max queued bytes breach the configured regression thresholds, or when a present `validation.json` on either input is failed. For lab/promoted-baseline comparisons, pass `--require-validation` so both sides must include validation metadata. Reports also include per-client delivered Mbps percentiles, send/deliver ratio deltas, datagram/NACK/stale rate deltas, and healthy-vs-affected throughput/fairness deltas for contention cases. Defaults are `10%` throughput regression, `10%` p99 latency regression, and `50%` queue growth regression.

When comparing suite directories, `compare-baseline-suite.sh` uses `suite-aggregate.jsonl` if present, so baseline-of-record comparisons operate on per-case medians and include throughput/p99 stability spread. Pass explicit `suite-summary.jsonl` paths only when you want raw per-iteration comparison.

For host-level impairment campaigns, compare the campaign summaries after every profile has been merged, validated, and summarized:

```bash
benchmark/scripts/promote-lab-impairment.sh \
  --input benchmark/build/benchmark-results/lab-impairment-baseline/campaign-summary \
  --out benchmark/build/benchmark-baselines \
  --name lab-impairment-<date>-<topology>

benchmark/scripts/compare-lab-impairment.sh \
  --baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \
  --candidate benchmark/build/benchmark-results/lab-impairment-candidate/campaign-summary \
  --out benchmark/build/benchmark-results/lab-impairment-comparison.md
```

The promotion script refuses failed campaign summaries and summaries generated without required netem evidence by default. The campaign comparator fails when a candidate profile or planned capacity/contention row is missing, when profile network shape differs, when either campaign summary failed, when netem status evidence was not required, or when delivered throughput, p99 probe RTT, or max queued bytes breaches the configured thresholds. Extra candidate rows are reported but do not fail the comparison.

After promoting both the perfect-network lab baseline and adverse-network impairment campaign, run the readiness gate before treating the package set as the baseline of record:

```bash
benchmark/scripts/check-baseline-readiness.sh \
  --lab-baseline benchmark/build/benchmark-baselines/lab-<date>-<topology> \
  --impairment-baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \
  --out benchmark/build/benchmark-results/baseline-readiness
```

The readiness gate fails when promoted artifacts are missing, validation did not pass, separate host evidence is absent, required scenario families are missing, capacity groups are unselected, required impairment profiles are missing, or netem status evidence was not captured.

## Single-Host Namespace Smoke

Use `run-netns-worker-smoke.sh` when separate lab hosts are not available but you still need impairment outside the JVM. It creates a server namespace and one or two receiver namespaces connected with veth pairs, runs the normal `server-worker` and `receiver-worker` benchmark roles, applies `tc netem` inside the selected namespace path, and merges results with `merge-worker-results.sh`.

Dry-run first to inspect the namespace topology and exact worker commands:

```bash
benchmark/scripts/run-netns-worker-smoke.sh \
  --case fairness \
  --clients 100 \
  --affected-clients 10 \
  --latency 50ms \
  --jitter 5ms \
  --loss 2% \
  --direction both \
  --payload-size 512 \
  --per-client-mbps 5
```

Run it with root or equivalent `CAP_NET_ADMIN` privileges to create namespaces and qdiscs:

```bash
sudo benchmark/scripts/run-netns-worker-smoke.sh \
  --execute \
  --case blackhole \
  --clients 100 \
  --affected-clients 10 \
  --blackhole-after 30s \
  --payload-size 512 \
  --per-client-mbps 5 \
  --warmup 10s \
  --duration 60s \
  --iterations 3
```

This is stronger than loopback because server-to-client, client-to-server, or symmetric impairment can be applied as real qdisc egress on veth devices. It is still a single-host smoke path: it does not prove NIC line-rate, interrupt behavior, switch path behavior, or separate-host clock/topology evidence. Use it to catch regression shape, retry pressure, and external blackhole behavior before spending lab time; use promoted separate-host lab baselines for performance-engineering comparisons.

## Remote Worker Runs

For lab validation, run the server and receiver workers on separate machines. Start the server first:

```bash
start_at_ms=$((($(date +%s) + 60) * 1000))
echo "$start_at_ms"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="server-worker --role server --bind-host 0.0.0.0 --port 19132 --clients 1000 --start-delay 30s --start-at-epoch-ms $start_at_ms --warmup 5s --duration 30s --iterations 3 --payload-size 512 --per-client-mbps 5 --out $(pwd)/benchmark/build/benchmark-results/lab-server --run-id server-1000x5"
```

Then start receivers from one or more client machines:

```bash
start_at_ms=<same-value-used-by-server>
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="receiver-worker --role client --host <server-ip> --port 19132 --clients 250 --start-at-epoch-ms $start_at_ms --warmup 5s --duration 30s --iterations 3 --out $(pwd)/benchmark/build/benchmark-results/lab-receiver-a --run-id receiver-a-250"
```

The server report contains send-side RakNet metrics and probe RTTs. Receiver reports contain delivered bytes/messages from that worker. Keep `--iterations`, `--warmup`, `--duration`, and `--start-at-epoch-ms` aligned between server and receiver workers for comparable merged artifacts. Use NTP-synchronized hosts and choose a timestamp far enough in the future for receivers to connect before warmup begins. Use absolute `--out` paths for direct `raknetBenchmark` invocations so artifacts land in the same place regardless of the Gradle task working directory.

After copying receiver summaries back to the server-side checkout, merge the worker reports into a comparable lab artifact:

```bash
benchmark/scripts/merge-worker-results.sh \
  --server benchmark/build/benchmark-results/lab-server/server-1000x5 \
  --receiver benchmark/build/benchmark-results/lab-receiver-a/receiver-a-250 \
  --out benchmark/build/benchmark-results/lab-merged/server-1000x5 \
  --case server-1000x5
```

Repeat `--receiver` for each receiver host. The merge writes `lab-summary.json`, `lab-summary.csv`, `README.md`, and a `suite-aggregate.jsonl` row that can be passed to `compare-baseline-suite.sh`. For line-rate work, pin JVMs and interrupts consistently between runs and keep other host traffic quiet.

For multi-rate remote bandwidth curves, generate a coordinated command plan instead of hand-assembling each rate:

```bash
benchmark/scripts/plan-remote-worker-curve.sh \
  --out benchmark/build/benchmark-results/lab-remote-curve-plan \
  --artifact-root benchmark/build/benchmark-results/lab-remote-curve \
  --case remote-curve-1c-mtu \
  --server-host <server-ip> \
  --receiver receiver-a=1 \
  --payload-size 1200 \
  --rates-mbps 100,250,500,750,1000,1500,2000,unlimited \
  --warmup 10s \
  --duration 60s \
  --iterations 3 \
  --start-delay 90s \
  --max-p99-ms 20 \
  --max-send-deliver-ratio 1.2
```

The plan writes `server-commands.sh`, one `receiver-<name>-commands.sh` per receiver, `merge-commands.sh`, a manifest, and a README. The combined lab planner also writes `check-plan-freshness.sh`; run it before starting workers so expired scheduled start times are caught before a lab run starts. The merge script labels remote rows as `curve-*`, concatenates a campaign-level `suite-aggregate.jsonl`, and runs `select-stable-bandwidth.sh` so the remote curve produces the same capacity artifacts as local suites.

For production-like remote contention runs, generate a coordinated fanout, fairness, and disappearance plan:

```bash
benchmark/scripts/plan-remote-contention.sh \
  --out benchmark/build/benchmark-results/lab-contention-plan \
  --artifact-root benchmark/build/benchmark-results/lab-contention \
  --case remote-contention-100x5 \
  --server-host <server-ip> \
  --receiver receiver-a=100 \
  --cases fanout,fairness,disappear-blackhole \
  --payload-size 512 \
  --per-client-mbps 5 \
  --impaired-clients 10% \
  --disappearing-clients 10% \
  --warmup 10s \
  --duration 60s \
  --iterations 3 \
  --start-delay 90s
```

The contention planner writes the same per-host command scripts and merge script shape as the curve planner, but each generated case uses `multi-client-fanout`, `fairness`, or `disappearing-clients` worker modes. Affected clients are assigned to receiver scripts in receiver order. Keep affected clients on one receiver host when you need exact healthy/affected splits, or treat server-side affected splits as advisory because the server labels peers by accept order across hosts.

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
- `sentToDeliveredBytesRatio`: server outbound bytes divided by receiver-observed delivered bulk bytes; sustained increases can indicate retransmit or ACK/NACK overhead
- `healthySentToDeliveredBytesRatio` and `affectedSentToDeliveredBytesRatio`: the same ratio split by healthy versus impaired/disappearing clients
- `serverDatagramsOutPerSecond`: server outbound datagram work rate
- `staleDatagramsPerSecond`, `nackInPerSecond`, and `nackOutPerSecond`: normalized retry-pressure indicators for comparing runs with different durations
- `packetLimit`, `globalPacketLimit`, and `configuredMaxQueuedBytes`: configured RakNet server/session overrides, or `null` when library defaults were used
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
- `staleDatagrams`, `nackIn`, `nackOut`: raw retransmission-pressure counters
- `maxQueuedBytes`: largest observed RakNet queued payload bytes per channel

Treat a case as unstable when it has zero delivered throughput, fewer than three measured iterations, throughput spread is above 10 percent, or p99 probe RTT spread is above 10 percent across measured iterations. Increase duration, reduce unrelated host activity, and rerun before comparing code changes.
