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

Start the server worker first:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="server-worker --role server --bind-host 0.0.0.0 --port 19132 --clients 1000 --start-delay 30s --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --out benchmark/build/benchmark-results/lab-server --run-id server-1000x5"
```

Start receivers on one or more receiver hosts:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="receiver-worker --role client --host <server-ip> --port 19132 --clients 250 --warmup 10s --duration 60s --disappearing-clients 0 --out benchmark/build/benchmark-results/lab-receiver-a --run-id receiver-a-250"
```

When using `--disappear-mode blackhole`, pass matching affected-client settings to receiver workers and server worker so reports label the same client set:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="server-worker --role server --bind-host 0.0.0.0 --port 19132 --clients 100 --start-delay 30s --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --out benchmark/build/benchmark-results/lab-server --run-id server-blackhole-100x5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="receiver-worker --role client --host <server-ip> --port 19132 --clients 100 --warmup 10s --duration 60s --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --out benchmark/build/benchmark-results/lab-receiver --run-id receiver-blackhole-100x5"
```

## Impairment Profiles

Use `raknet-netem.sh` to apply controlled host-level impairment outside the JVM:

```bash
benchmark/scripts/raknet-netem.sh --interface <nic> --action dry-run --latency 50ms --jitter 5ms --loss 2%
sudo benchmark/scripts/raknet-netem.sh --interface <nic> --action apply --latency 50ms --jitter 5ms --loss 2%
sudo benchmark/scripts/raknet-netem.sh --interface <nic> --action status
sudo benchmark/scripts/raknet-netem.sh --interface <nic> --action clear
```

Run the same benchmark command after each impairment change. Record the active `tc qdisc show` output in the host report or `topology.md`.

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
- `suite-aggregate.jsonl` has one row per case/scenario and records median throughput, median p99 probe RTT, spread, retry-pressure totals, and unstable flags
- at least three measured iterations exist for baseline-of-record runs
- repeated runs under the same topology have delivered throughput and p99 probe RTT spread within the configured stability threshold
- no unexpected disconnects occur in best-case and fanout scenarios
- blackhole or stop-reading scenarios show retry-pressure indicators such as stale datagrams, queue growth, NACKs, or blackholed datagram counters
- healthy-client throughput and p99 latency are reviewed separately from affected-client metrics

Keep local smoke results out of external line-rate claims. Use them only to catch regressions in runner behavior and output shape.
