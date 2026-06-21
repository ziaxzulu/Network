# Lab Baseline Runbook

This runbook is for producing repeatable RakNet established-channel baseline data before optimizing the transport. Local loopback smoke runs are useful for development, but the baseline of record should come from controlled lab hosts.

## Output Layout

Use one parent directory per lab campaign:

```text
benchmark/build/benchmark-results/lab-<date>-<topology>/
  baseline/
  candidate/
  comparison.md
  comparison.jsonl
  server-host-report.md
  receiver-host-report.md
  topology.md
```

Record the exact branch, commit, host names, NICs, CPU pinning, JVM, impairment profile, and any non-default kernel/NIC tuning in `topology.md`.

## Host Capture

Capture host state before each baseline or candidate suite. Run this on every server and receiver host:

```bash
benchmark/scripts/capture-lab-host.sh \
  --interface <nic> \
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/server-host
```

The script is read-only. It records kernel, CPU, memory, Java, Gradle, IP routing, `tc qdisc`, selected NIC details, and git revision. If optional tools such as `ethtool` are missing, the report records that gap instead of failing.

## Perfect-Network Baseline

Goal: find best-case transport capacity before network impairment is introduced.

Recommended setup:

- one server host and one or more receiver hosts on the same quiet L2/L3 lab network
- fixed NIC/interface names and MTU recorded in `topology.md`
- CPU governor fixed to performance mode where available
- JVM process pinned away from NIC interrupt-heavy cores where practical
- no `tc netem` impairment for the first pass
- no unrelated bulk traffic on the lab VLAN

Run the lab matrix from the server-side checkout:

```bash
benchmark/scripts/run-baseline-matrix.sh \
  --profile lab \
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/baseline
```

Use the same command and topology on candidate branches:

```bash
benchmark/scripts/run-baseline-matrix.sh \
  --profile lab \
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/candidate
```

Compare the candidate to the saved baseline:

```bash
benchmark/scripts/compare-baseline-suite.sh \
  --baseline benchmark/build/benchmark-results/lab-<date>-<topology>/baseline \
  --candidate benchmark/build/benchmark-results/lab-<date>-<topology>/candidate \
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/comparison.md
```

The comparison script uses `suite-aggregate.jsonl` automatically when comparing suite directories. That means the comparison is based on per-case medians and includes stability spread. Use the raw `suite-summary.jsonl` files only when diagnosing individual measured iterations.

## Remote Worker Runs

For line-rate validation, prefer explicit server and receiver workers instead of single-process local mode.

For a baseline-of-record campaign, start with the lab planner. It generates a remote bandwidth-curve plan, a remote contention plan, host-capture commands, a topology template, and a combined merge script:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan \
  --artifact-root benchmark/build/benchmark-results/lab-baseline \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=100 \
  --curve-payload-sizes 1200,1340,1400 \
  --curve-rates-mbps 100,250,500,750,1000,1500,2000,unlimited \
  --contention-cases fanout,fairness,disappear-blackhole \
  --contention-payload-size 512 \
  --per-client-mbps 5 \
  --warmup 10s \
  --duration 60s \
  --iterations 3 \
  --start-delay 90s
```

Add a raised-limiter curve pass when you need to separate out-of-box packet-limit behavior from the established-channel capacity ceiling:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan \
  --artifact-root benchmark/build/benchmark-results/lab-baseline \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=100 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000
```

Run the generated host-capture script on every host, then run the generated curve and contention worker scripts in the order shown in the plan README. After receiver artifacts are copied back, run `merge-all.sh`; it writes `combined/suite-aggregate.jsonl` and combined curve `bandwidth-capacity.*` selector artifacts beside it.

Start the server worker first:

```bash
start_at_ms=$((($(date +%s) + 60) * 1000))
echo "$start_at_ms"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="server-worker --role server --bind-host 0.0.0.0 --port 19132 --clients 1000 --start-delay 30s --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --out $(pwd)/benchmark/build/benchmark-results/lab-server --run-id server-1000x5"
```

Start receivers on one or more receiver hosts:

```bash
start_at_ms=<same-value-used-by-server>
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="receiver-worker --role client --host <server-ip> --port 19132 --clients 250 --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --disappearing-clients 0 --out $(pwd)/benchmark/build/benchmark-results/lab-receiver-a --run-id receiver-a-250"
```

When using `--disappear-mode blackhole`, pass matching affected-client settings to receiver workers and server worker so reports label the same client set:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="server-worker --role server --bind-host 0.0.0.0 --port 19132 --clients 100 --start-delay 30s --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --out $(pwd)/benchmark/build/benchmark-results/lab-server --run-id server-blackhole-100x5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="receiver-worker --role client --host <server-ip> --port 19132 --clients 100 --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --out $(pwd)/benchmark/build/benchmark-results/lab-receiver --run-id receiver-blackhole-100x5"
```

Direct `raknetBenchmark` runs should use absolute `--out` paths. The Gradle task runs from the benchmark module directory, so relative output paths can otherwise land under `benchmark/benchmark/...`. Keep `--iterations`, `--warmup`, `--duration`, and `--start-at-epoch-ms` aligned between server and receiver workers; the merge script warns when iteration counts or coordinated start timestamps differ. Hosts should be NTP-synchronized, and the start timestamp should be far enough in the future for all receiver clients to establish before warmup begins.

For disappearance runs, receiver workers apply `--disappear-mode` during the first measurement window and later windows measure the resulting post-disappearance state. If each iteration must repeat the disappearance event from fresh connections, run separate worker campaigns with unique `--run-id` values instead of one multi-iteration worker run.

After each remote-worker run, copy receiver artifact directories back to the server-side checkout and merge them:

```bash
benchmark/scripts/merge-worker-results.sh \
  --server benchmark/build/benchmark-results/lab-server/server-1000x5 \
  --receiver benchmark/build/benchmark-results/lab-receiver-a/receiver-a-250 \
  --receiver benchmark/build/benchmark-results/lab-receiver-b/receiver-b-250 \
  --out benchmark/build/benchmark-results/lab-merged/server-1000x5 \
  --case server-1000x5
```

The merged directory contains `lab-summary.json`, `lab-summary.csv`, `README.md`, and `suite-aggregate.jsonl`. Compare merged baseline and candidate directories with the normal comparison script:

```bash
benchmark/scripts/compare-baseline-suite.sh \
  --baseline benchmark/build/benchmark-results/lab-merged-baseline/server-1000x5 \
  --candidate benchmark/build/benchmark-results/lab-merged-candidate/server-1000x5 \
  --out benchmark/build/benchmark-results/lab-remote-comparison.md
```

For a repeatable remote bandwidth curve, generate a plan before the run:

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
  --start-delay 90s
```

Run `server-commands.sh` on the server host first, start the generated receiver scripts once the server is listening, copy receiver artifacts back under the same artifact root, then run `merge-commands.sh`. The merge output includes a campaign-level `suite-aggregate.jsonl` and `bandwidth-capacity.*` files for highest-stable-capacity review.

For remote contention campaigns, generate fanout, fairness, and disappearance cases together:

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

The generated manifest records the scheduled start times, receiver distribution, and affected-client counts. When using host/NIC-level impairment, place the affected receiver clients on the impaired host or network namespace. With multiple receiver hosts, server-side affected-client splits are accept-order based and should be treated as advisory unless the affected clients are isolated to one receiver.

## Impairment Profiles

Use `raknet-netem.sh` to apply controlled host-level impairment outside the JVM:

```bash
benchmark/scripts/raknet-netem.sh --interface <nic> --action dry-run --latency 50ms --jitter 5ms --loss 2%
sudo benchmark/scripts/raknet-netem.sh --interface <nic> --action apply --latency 50ms --jitter 5ms --loss 2%
sudo benchmark/scripts/raknet-netem.sh --interface <nic> --action status
sudo benchmark/scripts/raknet-netem.sh --interface <nic> --action clear
```

Run the same benchmark command after each impairment change. Record the active `tc qdisc show` output in the host report or `topology.md`.

For recurring runs, prefer the wrapper so the applied profile, suite command, and output directory are recorded together:

```bash
benchmark/scripts/run-impairment-matrix.sh \
  --interface <nic> \
  --runner-profile lab \
  --out benchmark/build/benchmark-results/lab-impairment \
  --sudo-netem \
  --execute
```

Without `--execute`, the wrapper prints the exact `tc` and baseline-suite commands and writes a dry-run manifest. Use that mode to review NIC selection and profile order before running on a lab host.

Start with this impairment set:

| Profile | Latency | Jitter | Loss |
| --- | ---: | ---: | ---: |
| perfect | `0ms` | `0ms` | `0%` |
| near-loss | `10ms` | `2ms` | `2%` |
| regional-loss | `50ms` | `5ms` | `2%` |
| poor | `100ms` | `10ms` | `5%` |
| severe | `200ms` | `20ms` | `10%` |

## Baseline Acceptance

Treat a lab baseline as usable only when:

- every selected case produces `summary.json`, `timeseries.csv`, `latency.hdr`, and `report.md`
- `suite-summary.jsonl` has one row per successful measured iteration
- `suite-aggregate.jsonl` has one row per case/scenario and records median throughput, per-client delivered Mbps percentiles, send/deliver ratios, datagram/NACK/stale rates, median p99 probe RTT, packet-limit settings, spread, retry-pressure totals, and unstable flags
- `bandwidth-capacity.jsonl` has one row per bandwidth curve group and selects the highest stable delivered Gbps, with `bandwidth-capacity.md` kept beside it for review
- at least three measured iterations exist for baseline-of-record runs
- repeated runs under the same topology have delivered throughput and p99 probe RTT spread within the configured stability threshold
- no unexpected disconnects occur in best-case and fanout scenarios
- blackhole or stop-reading scenarios show retry-pressure indicators such as stale datagrams, queue growth, NACKs, blackholed datagram counters, or rising send/deliver ratios
- healthy-client throughput, per-client delivered Mbps percentiles, send/deliver ratios, and p99 latency are reviewed separately from affected-client metrics

Run the validator before promoting a lab run to the saved baseline:

```bash
benchmark/scripts/validate-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline
```

The lab planner's generated `merge-all.sh` runs the same validation automatically after it creates the combined aggregate, passing the curve and contention manifests so missing planned cases fail validation. Validation fails by default when `topology.md` is missing or fewer than two host reports were captured under the artifact root.

Keep local smoke results out of external line-rate claims. Use them only to catch regressions in runner behavior and output shape.
