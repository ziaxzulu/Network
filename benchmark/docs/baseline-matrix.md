# Baseline Performance Matrix

This document defines the baseline benchmark coverage we want before optimizing the RakNet transport or adding future transports such as WebRTC.

Source evidence for the production traffic shape is recorded in [`production-usage-evidence.md`](production-usage-evidence.md). This matrix is derived from that evidence plus the current benchmark implementation.

The benchmark suite is meant to answer two different questions:

- What is the best-case capacity of the transport stack when the network is not the bottleneck?
- Under production-like contention, do healthy clients remain fairly served when other clients are slow, lossy, or disappear?

## Current Synthetic Coverage

The current RakNet runner is a useful starting synthetic for established-channel server-to-client throughput:

- It uses normal `RakServerChannel`, `RakClientChannel`, and `RakMessage` APIs.
- It measures delivered payload throughput, offered throughput, probe RTT under load, per-client delivered Mbps percentiles, server send-work ratios, datagram/NACK/stale rates, Jain fairness, queue growth, ACK/NACK counters, stale datagrams, disconnects, benchmark-managed blackholed datagrams, open/active peer counts, and final RakNet state counts.
- It supports local loopback runs for regression checks and remote server/client worker roles for lab runs.
- It can sweep payload size, reliability mode, offered rate, and client count.
- It supports `--per-client-mbps` so fanout and fairness runs can express production-style per-client pull targets directly.
- It can apply benchmark-managed latency, jitter, and loss to selected impaired clients after their RakNet channels are established.
- It includes a `disappearing-clients` scenario where selected established clients close, stop reading, or blackhole datagrams during the measured window.
- It includes a `batched-game-traffic` scenario for fixed-cadence grouped fanout with synthetic length-framed batches.
- It records RakNet server packet-limit overrides so best-case bandwidth runs can distinguish library-default limiter behavior from raised-limiter capacity tests.

It should not yet be treated as a complete production synthetic:

- Benchmark-managed impairment runs inside the client JVM and is useful for repeatable local fairness tests. Use Linux `tc netem`, routing rules, or remote workers for host/NIC-level impairment before making lab claims.
- The batch workload is still synthetic. It models burst cadence, grouped fanout, logical packet counts, and encoded batch sizes, but not compression algorithms or real Bedrock packet distributions.
- The client-disappearance scenario covers close-mode, local stop-reading mode, and benchmark-managed datagram blackhole mode. Host/NIC-level blackholes still need external `tc`/routing rules.
- Local loopback is only a repeatable development baseline. Line-rate claims require separate machines, pinned CPU/NIC setup, and controlled network impairment.

## Production Usage Signals

Geyser, public Cloudburst/Nukkit, and CubeCraft production usage make the most important synthetic target clearer:

- The Bedrock edge uses established RakNet sessions with MTU around `1400`, one ordering channel, and normal gameplay sends on channel `0`.
- Application traffic is batch-oriented: many logical Bedrock packets are length-framed into a compressed batch before being sent over RakNet.
- The dominant reliability mode is `RELIABLE_ORDERED` on channel `0`; raw `RakMessage(ByteBuf)` sends default to that shape and production Bedrock frame paths expect it.
- Fanout is common. Some sends are the same payload to many clients, while others are grouped by protocol version, palette, dimension, world, or radius.
- Chunk/bootstrap delivery creates bursty high-volume traffic and has pacing in production code.
- Resource-pack style flows can be split-heavy; Geyser uses `256KiB` resource-pack chunks paced around `200ms`, while public Nukkit has smaller resource-pack chunk responses around `8KiB`.
- Public Cloudburst/Nukkit/Geyser style code uses batch flush cadences around `20ms` and `50ms`, while CubeCraft also has a `10ms` transport flush path.
- Compression thresholds vary by project/config. The current evidence supports private CubeCraft threshold `1`, public Nukkit threshold `256`, and public Geyser threshold `512`.
- Geyser has normal session tick/batch behavior as well as selected immediate sends, so latency-sensitive probes should be tested both inside and outside bulk/batch pressure.
- Slow-reader/backlog protection matters. Production code has explicit queue/backlog limits and disconnect paths for overloaded transfers.

Benchmark implications:

- Keep `RELIABLE_ORDERED` channel `0` as the primary recurring baseline.
- Prefer payload sizes near real RakNet/Bedrock shapes: small control packets, threshold-adjacent packets around `256B` and `512B`, near-MTU batches around `1200-1400B`, and split-heavy chunk/resource-pack payloads.
- Use `batched-game-traffic` for bursts every `10ms`, `20ms`, and `50ms` instead of only an evenly spaced fixed-size stream.
- Add a future Bedrock-like workload layer that uses captured logical packet distributions and optionally compresses batches with thresholds `1`, `256`, and `512`, distinguishing pass-through compressed batches from re-encoded modified batches.
- Use `resource-pack-transfer` for paced large-chunk transfers so split-heavy payload coverage is not limited to an always-on bulk stream. Immediate-send profiles outside resource-pack pacing remain a future gap.
- Use `--max-queued-bytes` queue/backlog cap sweeps on fairness and disappearance rows when measuring slow-client disconnect thresholds.
- Add host/NIC-level impairment and blackhole disappearance profiles before treating the suite as production-representative.
- Add a later proxy profile with one downstream and one upstream RakNet channel per user to represent pass-through deployments.

## Baseline Matrix

Run each baseline with at least `3` measured iterations after warmup. Treat a run as unstable when it has zero delivered throughput, fewer than `3` measured iterations, delivered throughput spread exceeds `10%`, or p99 probe RTT spread exceeds `10%`.

### 1. Best-Case Bandwidth

Purpose: find the highest stable payload throughput for one established client before optimizing implementation details.

Recommended starting command:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,750,1000,1500,2000,unlimited"
```

Matrix dimensions:

| Dimension | Values |
| --- | --- |
| Clients | `1` |
| Payload size | `64`, `256`, `512`, `1200`, `1340`, `1400`, `262144` |
| Reliability | `reliable_ordered`, `reliable`, `unreliable` |
| Network | localhost smoke, remote perfect-network lab |
| Offered rate | ramp until p99 RTT, queue bytes, retransmits, or drops climb sharply |

Primary acceptance metrics:

- highest stable delivered Gbps
- delivered messages/sec at each payload size
- p50/p95/p99/max probe RTT under load
- active/open peer counts and final disconnected-state counts for overloaded rows
- max queued bytes
- ACK/NACK and stale datagram pressure
- sender CPU and receiver CPU from the lab environment

The baseline suite runner writes `bandwidth-capacity.jsonl`, `bandwidth-capacity.csv`, and `bandwidth-capacity.md` to select the highest stable bandwidth-curve point per payload/reliability/impairment/packet-limit group. The selector chooses by delivered Gbps, not offered target, because uncapped `unlimited` rows are represented as target `0`. Remote lab plans can include a paired raised-limiter curve campaign with `--raised-packet-limit` and `--raised-global-packet-limit`.

### 2. Bandwidth-Latency Curve

Purpose: find the operating knee where additional offered bandwidth stops producing useful delivered throughput and starts increasing latency or queueing.

Recommended starting command:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,750,1000,1500,2000"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="bandwidth-latency-curve --clients 1 --warmup 5s --duration 30s --iterations 3 --payload-size 1200 --rates-mbps 100,250,500,750,1000,1500,2000 --packet-limit 100000 --global-packet-limit 1000000"
```

Run this under:

| Profile | Latency | Jitter | Loss |
| --- | ---: | ---: | ---: |
| perfect | `0ms` | `0ms` | `0%` |
| near | `10ms` | `0ms`, `2ms` | `0%`, `2%` |
| regional | `50ms` | `0ms`, `5ms` | `0%`, `2%`, `5%` |
| distant | `100ms` | `0ms`, `10ms` | `0%`, `2%`, `5%` |
| adverse | `200ms` | `0ms`, `20ms` | `2%`, `5%`, `10%` |

Primary acceptance metrics:

- throughput knee per network profile
- p95/p99 latency knee per network profile
- queue growth and retransmit pressure around the knee
- whether `packetLimit` and `globalPacketLimit` were library defaults or explicitly raised

For lab sign-off, rerun the selector with explicit gates that match the topology target, for example:

```bash
benchmark/scripts/select-stable-bandwidth.sh \
  --input benchmark/build/benchmark-results/lab-baseline \
  --max-p99-ms 20 \
  --max-queue-bytes 1048576 \
  --max-send-deliver-ratio 1.2
```

### 3. Multi-Client Fanout

Purpose: verify that established channels continue to be serviced with acceptable latency as client count rises.

Recommended starting commands:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --clients 20 --warmup 5s --duration 30s --iterations 3 --payload-size 512 --rate-mbps 100"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --clients 100 --warmup 5s --duration 30s --iterations 3 --payload-size 512 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --clients 500 --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5"
```

Use `--rate-mbps` for aggregate offered rate, or `--per-client-mbps` for a stable per-client target across different client counts.

Matrix dimensions:

| Dimension | Values |
| --- | --- |
| Clients | `20`, `100`, `500`, `1000` |
| Per-client target | `1Mbps`, `5Mbps`, `10Mbps` |
| Payload size | `128`, `512`, `1200`, `1400` |
| Reliability | `reliable_ordered` first, then `reliable` and `unreliable` spot checks |

Primary acceptance metrics:

- per-client delivered Mbps distribution
- Jain fairness index
- p95/p99 probe RTT distribution
- max queued bytes per channel
- disconnect count
- server datagrams and bytes out

### 4. Mixed-Network Fairness

Purpose: ensure poor links do not consume excessive send work or degrade healthy clients.

Start with a 100-client lab run:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="fairness --clients 100 --impaired-clients 10 --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --impairment-latency 100ms --impairment-jitter 10ms --impairment-loss 5"
```

The local harness applies this profile to the first `--impaired-clients` client datagram channels after connection establishment. For lab validation, also run host-level impairment outside the JVM against the impaired receiver host or network namespace. Suggested impaired profiles:

| Profile | Latency | Jitter | Loss | Expected use |
| --- | ---: | ---: | ---: | --- |
| mild | `50ms` | `5ms` | `2%` | common degraded client |
| poor | `100ms` | `10ms` | `5%` | bad Wi-Fi or distant route |
| severe | `200ms` | `20ms` | `10%` | worst regular client |

Primary acceptance metrics:

- healthy-client delivered Mbps and p99 probe RTT
- impaired-client queued bytes, NACKs, stale datagrams, and disconnects
- server send/deliver byte ratio and datagrams per second for all, healthy, and affected clients
- Jain fairness for all clients and healthy-only clients
- whether healthy clients regress when impaired clients are present

### 5. Disappearing Clients And Retry Pressure

Purpose: reproduce production behavior where clients vanish, stop reading, or become blackholed while the server still has data to send.

The harness now includes close, stop-reading, and benchmark-managed blackhole modes:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode close --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode stop-reading --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --clients 100 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5"
```

The scenario can:

- close a configurable percentage of clients after warmup
- stop reads on a configurable percentage of local clients after warmup while leaving server peers active
- blackhole datagrams on a configurable percentage of established local or receiver-worker clients after warmup
- keep healthy clients on the same configured per-client target
- report per-client delivered Mbps percentiles, send/deliver byte ratios, datagram/NACK/stale rates, all-client fairness, healthy-client fairness, disconnects, blackholed datagrams, queue growth, retransmits, stale datagrams, and channel state

The remaining required gap is harsher host/NIC-level blackhole behavior:

- blackhole a configurable percentage of client traffic with `tc` or routing rules outside the JVM
- keep retry pressure alive under real packet drops long enough to measure retransmit storms and queue drain behavior
- sweep lower `RAK_MAX_QUEUED_BYTES` caps with `--max-queued-bytes` to measure when slow clients are disconnected and whether healthy clients remain isolated

Initial target shape:

| Dimension | Values |
| --- | --- |
| Clients | `100`, `500`, `1000` |
| Disappearing clients | `1%`, `5%`, `10%` |
| Disappearance mode | close, stop reading, blackhole |
| Per-client target | `1Mbps`, `5Mbps` |
| Runtime | `10s` warmup, `60s` measured |

### 6. Batched Game-Traffic Shape

Purpose: approximate how Bedrock traffic is usually delivered: bursts of length-framed packets grouped into batches and flushed periodically.

The harness includes a first executable batch profile:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 10ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 20ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="batched-game-traffic --clients 100 --warmup 10s --duration 60s --iterations 3 --batch-interval 50ms --logical-packets-per-batch 8 --batch-payload-sizes 128,512,1200 --batch-groups 4 --per-client-mbps 5"
```

The current workload can:

- send a configurable burst every `10ms`, `20ms`, or `50ms`
- vary the number of logical packets per batch
- vary encoded batch payload sizes instead of sending one uniform payload size
- reuse the same payload for broadcast fanout and send a small number of payload variants for grouped fanout
- record logical packet counts in addition to aggregate bytes/messages

Remaining batch gaps:

- immediate-send lane outside periodic batch flushes
- captured gameplay packet-size distributions
- compression threshold and algorithm modeling around `1B`, `256B`, and `512B`, including pass-through versus re-encode behavior
- batch-size histograms beyond the configured payload-size list

Initial target shape:

| Dimension | Values |
| --- | --- |
| Clients | `20`, `100`, `500`, `1000` |
| Flush cadence | `10ms`, `20ms`, `50ms`, immediate |
| Batch payloads | small control, `256B`/`512B` threshold-adjacent, mixed gameplay, near-MTU, split/chunk/resource-pack-like |
| Compression threshold | future: `1`, `256`, `512`, disabled |
| Group count | `1`, `4`, `16` payload variants |
| Per-client target | `1Mbps`, `5Mbps` |

### 7. Resource-Pack And Paced Split Transfers

Purpose: approximate large, paced transfer paths such as Geyser resource-pack chunks and smaller Nukkit resource-pack responses without folding them into the continuous bandwidth curve.

The harness includes `resource-pack-transfer`, which sends one bulk chunk to each established client on a fixed interval while probe messages continue to measure latency:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="resource-pack-transfer --clients 100 --warmup 10s --duration 60s --iterations 3 --chunk-size 8192 --chunk-interval 200ms"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="resource-pack-transfer --clients 100 --warmup 10s --duration 60s --iterations 3 --chunk-size 262144 --chunk-interval 200ms"
```

Initial target shape:

| Dimension | Values |
| --- | --- |
| Clients | `20`, `100`, `500` |
| Chunk size | `8192`, `262144` |
| Chunk interval | `200ms` |
| Network | perfect plus host-level impairment profiles |

### 8. Proxy Pass-Through Shape

Purpose: approximate deployments where each user has one downstream RakNet session and one upstream RakNet session, so the process handles twice the connected channel count plus forwarding work.

This is a future benchmark gap. The harness should add a profile that can:

- create paired downstream/upstream channels per logical user
- forward unknown or opaque payloads between pairs
- measure added latency, queue growth, and fairness across both channel directions
- run with logging disabled so transport overhead is not hidden by application diagnostics

Initial target shape:

| Dimension | Values |
| --- | --- |
| Logical users | `20`, `100`, `500` |
| Channels | `2` RakNet sessions per logical user |
| Payloads | `512`, `1200`, split-heavy |
| Direction | downstream-only, upstream-only, bidirectional |

## Recommended Baseline Set

Use this smaller set as the first recurring perfect-network baseline before expanding the full matrix. These rows match the executable `lab` profile shape:

| Name | Clients | Payload | Reliability | Network | Offered load |
| --- | ---: | ---: | --- | --- | --- |
| `bestcase-1c-small` | `1` | `64` | `reliable_ordered` | perfect | ramp |
| `bestcase-1c-threshold256` | `1` | `256` | `reliable_ordered` | perfect | ramp |
| `bestcase-1c-medium` | `1` | `512` | `reliable_ordered` | perfect | ramp |
| `bestcase-1c-mtu` | `1` | `1200`, `1340`, `1400` | `reliable_ordered` | perfect | ramp |
| `bestcase-1c-split` | `1` | `262144` | `reliable_ordered` | perfect | ramp |
| `fanout-100x5` | `100` | `512` | `reliable_ordered` | perfect | `5Mbps` per client |
| `fanout-500x5` | `500` | `512` | `reliable_ordered` | perfect aggregate/proxy fanout | `5Mbps` per client |
| `fairness-100-10poor` | `100` | `512` | `reliable_ordered` | 10 poor clients | `5Mbps` per client |
| `disappear-100-10pct-close` | `100` | `512` | `reliable_ordered` | close 10 clients | `5Mbps` per client |
| `disappear-100-10pct-stopread` | `100` | `512` | `reliable_ordered` | stop reads on 10 clients | `5Mbps` per client |
| `disappear-100-10pct-blackhole` | `100` | `512` | `reliable_ordered` | blackhole 10 clients | `5Mbps` per client |
| `batch-fanout-100-10ms` | `100` | mixed | `reliable_ordered` | perfect | `10ms`, `5Mbps` per client |
| `batch-fanout-100-20ms` | `100` | mixed | `reliable_ordered` | perfect | `20ms`, `5Mbps` per client |
| `batch-fanout-100-50ms` | `100` | mixed | `reliable_ordered` | perfect | `50ms`, `5Mbps` per client |
| `resource-100-8k-200ms` | `100` | `8192` | `reliable_ordered` | perfect | one chunk/client every `200ms` |
| `resource-100-256k-200ms` | `100` | `262144` | `reliable_ordered` | perfect | one chunk/client every `200ms` |

Use host-level impairment as a companion baseline, not as part of the unshaped perfect-network profile:

| Name | Clients | Payload | Reliability | Network | Offered load |
| --- | ---: | ---: | --- | --- | --- |
| `loss-1c-50ms-2pct` | `1` | `1200`, `1340`, `1400` | `reliable_ordered` | `50ms`, `2%` loss | ramp |
| `loss-100-50ms-2pct` | `100+` | `512` | `reliable_ordered` | `50ms`, `2%` loss | `5Mbps` per client |
| `loss-100-100ms-5pct` | `100+` | `512` | `reliable_ordered` | `100ms`, `5%` loss | `5Mbps` per client |
| `blackhole-100-10pct` | `100+` | `512` | `reliable_ordered` | affected receiver host or namespace blackholed after warmup | `5Mbps` per client |

Run the executable profile with:

```bash
benchmark/scripts/run-baseline-matrix.sh --profile lab --out benchmark/build/benchmark-results/lab-baseline
```

That command is the perfect-network executable baseline. It covers the recommended one-client bandwidth curve, split-heavy bandwidth, fanout, fairness, disappearance, and batched-game-traffic cases under the current host conditions. For the latency/loss rows in the matrix, wrap the same profile with host-level impairment rather than treating benchmark-managed client impairment as line-rate evidence:

```bash
benchmark/scripts/run-impairment-matrix.sh \
  --interface <nic> \
  --runner-profile lab \
  --out benchmark/build/benchmark-results/lab-impairment \
  --profiles perfect,near-loss,regional-loss,poor,severe \
  --sudo-netem \
  --execute
```

For baseline-of-record capacity, prefer the remote planner over single-process loopback and include the raised-limiter curve pass. The default-limiter rows explain out-of-box behavior; the raised-limiter rows are the capacity ceiling to compare against production-like deployments that raise or disable packet/global packet limits:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan \
  --artifact-root benchmark/build/benchmark-results/lab-baseline \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=500 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000
```

For baseline-of-record adverse network runs, generate profile-specific remote plans and netem scripts:

```bash
benchmark/scripts/plan-lab-impairment.sh \
  --out benchmark/build/benchmark-results/lab-impairment-plan \
  --artifact-root benchmark/build/benchmark-results/lab-impairment \
  --interface <nic> \
  --target-host-role receiver-a \
  --profiles perfect,near-loss,regional-loss,poor,severe \
  --sudo-netem \
  -- \
  --server-host <server-ip> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=500 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000
```

The generated netem scripts write timestamped `tc` command output under each profile artifact root's `netem/` directory. The generated `validate-all.sh` requires `<profile>-status-*.txt` evidence by default, so keep those files with the merged benchmark artifacts before promoting the adverse-network run to a baseline. Campaign summary and promotion reject profile validation bypass flags by default; use `--allow-validation-bypasses` only for non-baseline smoke packages.

For slow-client backlog sensitivity, repeat the fairness or disappearance rows with explicit queue caps, for example `--max-queued-bytes 1048576` and `--max-queued-bytes 4194304`. The configured cap is recorded as `configuredMaxQueuedBytes`, selected bandwidth rows are grouped by it, and baseline comparisons fail if a candidate changes it while reusing the same case key.

After running the same profile on a candidate branch, compare the suite summaries:

```bash
benchmark/scripts/compare-baseline-suite.sh \
  --baseline benchmark/build/benchmark-results/lab-baseline \
  --candidate benchmark/build/benchmark-results/lab-candidate \
  --out benchmark/build/benchmark-results/lab-comparison.md \
  --require-validation
```

The comparison tool writes a Markdown report and raw comparison JSONL. It fails when a case is missing from the candidate run, when matched rows change matrix shape (clients, payload, reliability, batching, target rate, impairment, packet limits, or queue cap), when matched rows breach the configured throughput, p99 latency, or queue-growth thresholds, when either input contains a failed `validation.json`, or when validation passed only because a baseline bypass flag was used. For lab sign-off, use `--require-validation` so candidate runs without validation metadata also fail. `--allow-validation-bypasses` is only for non-baseline smoke comparisons.

For remote server/receiver worker runs, merge the server and receiver artifact directories first:

```bash
benchmark/scripts/merge-worker-results.sh \
  --server benchmark/build/benchmark-results/lab-server/server-1000x5 \
  --receiver benchmark/build/benchmark-results/lab-receiver-a/receiver-a-250 \
  --out benchmark/build/benchmark-results/lab-merged/server-1000x5 \
  --case server-1000x5
```

The merged directory exposes a `suite-aggregate.jsonl` row, so it can be compared with `compare-baseline-suite.sh` like any other suite artifact.

For a full lab baseline campaign, prefer `benchmark/scripts/plan-lab-baseline.sh`. It composes the remote bandwidth curve and contention planners, writes host-capture commands and a topology template, schedules non-overlapping case starts, and produces a combined `suite-aggregate.jsonl` for baseline-of-record comparisons.

Use `benchmark/scripts/validate-lab-baseline.sh` on the combined output before accepting a lab run as the baseline of record. The validator checks required scenario families, planned manifest rows when manifests are supplied, manifest-to-aggregate client/payload/per-client-rate consistency, measured iteration counts, unstable flags, zero-delivery rows, unexpected curve/fanout disconnects, retry-pressure signals in disappearance rows, selected bandwidth-capacity rows with concrete positive-throughput selected candidates, topology metadata, host reports, ready strict prereq reports, and optional contention gates for minimum client count, per-client offered Mbps, healthy-client fairness, healthy-client send work, affected-client send work, and contention p99 latency. The per-client Mbps gate applies to rate-controlled fanout, fairness, disappearance, and batched rows; paced resource-pack rows use the target derived from chunk size and interval. Strict prereq reports must prove clock sync, expected MTU, minimum CPU count, and no pre-existing netem qdisc. `plan-lab-baseline.sh` enables planned contention scale/rate, healthy fairness, and send-work gates by default.

After validation passes, use `benchmark/scripts/promote-lab-baseline.sh` with the generated `--handoff-manifest` to create the durable comparison package. It refuses validation bypass flags by default, copies the comparable aggregate, validation reports, capacity selector artifacts, topology/host/prereq evidence, planning manifests, and production-evidence fingerprint into a named baseline directory, and updates a `latest` symlink for candidate comparisons.

For multi-rate remote bandwidth curves, prefer `benchmark/scripts/plan-remote-worker-curve.sh`. It generates per-host server/receiver scripts, fixed `--start-at-epoch-ms` values, merge commands, a campaign `suite-aggregate.jsonl`, and `bandwidth-capacity.*` selector artifacts.

For production-like fanout, fairness, disappearing-client, and paced resource-pack campaigns, prefer `benchmark/scripts/plan-remote-contention.sh`. It uses the same remote worker scheduling and merge model, records affected-client distribution in `manifest.jsonl`, expands `resource-pack` into the configured chunk sizes, and keeps the contention artifacts comparable through one campaign-level `suite-aggregate.jsonl`.

For quick local verification of the runner itself:

```bash
benchmark/scripts/run-baseline-matrix.sh --profile smoke
```

## Interpretation Rules

- Compare optimized code against the same run IDs, same lab topology, same CPU pinning, and same impairment profiles.
- Keep the saved baseline and candidate `suite-summary.jsonl` files with the code revision, host, NIC, and impairment notes for that run.
- Use `suite-aggregate.jsonl` for baseline-of-record comparisons; keep `suite-summary.jsonl` for per-iteration diagnosis.
- Use local loopback only for quick regression and profiling. Do not use it for external line-rate claims.
- Prefer median delivered throughput across measured iterations, but fail the run if p99 latency or queue growth is unstable.
- Record healthy-client metrics separately from impaired-client metrics for fairness scenarios, including delivered throughput, per-client delivered Mbps percentiles, and send/deliver byte ratios.
- Treat rising queue bytes before rising loss as an early congestion signal.
