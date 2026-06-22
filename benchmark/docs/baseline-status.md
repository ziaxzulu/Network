# Baseline Status

This is the current handoff state for the established RakNet performance baseline. It records what is ready to use for performance engineering, what local evidence exists, and what still has to run in the lab before a result becomes the baseline of record.

## Current Answer

The benchmark is a good synthetic for established-channel transport pressure:

- primary `RELIABLE_ORDERED` channel `0` server-to-client traffic
- one-client bandwidth-latency curves for best-case transport capacity
- multi-client fanout, fairness, disappearing-client, and batched-game-traffic cases
- queue, ACK/NACK, stale datagram, disconnect, fairness, per-client throughput, send/deliver, and probe-latency indicators
- local loopback runs for regression and remote worker runs for separate-host lab evidence

It is not a full Bedrock production emulator yet. The main remaining workload gaps are compression modeling, captured logical packet distributions, pass-through versus re-encode batch behavior, immediate-send/resource-pack pacing, proxy pass-through, and captured host/NIC-level impairment results. Source evidence and the gap list are in [`production-usage-evidence.md`](production-usage-evidence.md).

## Base Matrix

Use [`baseline-matrix.md`](baseline-matrix.md) as the source of truth. The first recurring matrix should include:

- best-case one-client curves with payloads `64`, `256`, `512`, `1200`, `1340`, `1400`, and split-heavy payloads
- default-limiter and raised-limiter one-client curves
- `100+` client contention rows at `5Mbps` per client
- fanout, fairness, and disappearing-client rows
- `10ms`, `20ms`, and `50ms` batched-game-traffic rows
- host-level impairment campaigns for selected curve and contention rows

The executable local/lab profiles in `benchmark/scripts/run-baseline-matrix.sh` and the remote lab planner in `benchmark/scripts/plan-lab-baseline.sh` are aligned with that matrix.

## Local Development Evidence

Local loopback artifacts are useful for regression shape only. They should not be used for line-rate claims.

For a stronger single-host smoke path, use `benchmark/scripts/run-netns-worker-smoke.sh`. It runs the normal server/receiver worker roles through Linux network namespaces and veth pairs so `tc netem` and blackhole behavior are applied outside the JVM. This is useful for local retry-pressure and external-qdisc regression checks, but it is still not accepted as line-rate or baseline-of-record evidence.

Latest local best-case curve artifact in this worktree:

```text
benchmark/build/benchmark-results/local-bestcase-mtu1340-20260621T224721Z/
```

With payload `1340` and strict selector gates, the selected stable local point was:

| Selected | Target Mbps | Delivered Gbps | p99 RTT ms | Max queue bytes |
| --- | ---: | ---: | ---: | ---: |
| `curve-100_0mbps` | `100` | `0.099948992` | `9.015319` | `167500` |

The best observed local point was `curve-750_0mbps` at `0.748937792Gbps`, but it was rejected because it was unstable and had disconnect, p99 RTT, and queue-pressure failures. Zero-delivery curve rows are now rejected by the stability policy, capacity selector, and lab validator.

Latest local 100-client contention artifact in this worktree:

```text
benchmark/build/benchmark-results/local-contention-100-20260621T225857Z/
```

The local 100-client rows delivered roughly the expected aggregate load, but every row was unstable on p99 probe RTT spread. Treat these as development observations, not baseline candidates:

| Case | Delivered Gbps | Client p50 Mbps | p99 RTT ms | Max queue bytes | Unstable reason |
| --- | ---: | ---: | ---: | ---: | --- |
| `fanout-100x5` | `0.49625088` | `4.962304` | `695.90741` | `1593890` | `p99-spread` |
| `fairness-100-10poor` | `0.44868608` | `4.984832` | `59.137674` | `4225024` | `p99-spread` |
| `disappear-100-close` | `0.4727862613333333` | `4.988245333333333` | `212.572783` | `812253` | `p99-spread` |
| `disappear-100-stopread` | `0.4719602346666667` | `4.971861333333333` | `151.940389` | `1865197` | `p99-spread` |
| `disappear-100-blackhole` | `0.4718906026666667` | `4.969130666666667` | `146.109212` | `1819117` | `p99-spread` |
| `batch-100-20ms` | `0.51136` | `5.1008` | `54.356412` | `1035265` | `p99-spread` |

## Lab Baseline Of Record

The baseline of record is not complete until a separate-host lab run is captured, validated, and promoted. Generate the current recommended plan with:

```bash
benchmark/scripts/prepare-lab-baseline-handoff.sh \
  --out benchmark/build/benchmark-results/lab-handoff-current \
  --artifact-root benchmark/build/benchmark-results/lab-run-current \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --sudo-netem
```

The handoff writes the perfect-network plan, impairment campaign plan, top-level run order, promotion commands, and readiness-gate command. The underlying perfect-network baseline plan is equivalent to:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan-current \
  --artifact-root benchmark/build/benchmark-results/lab-baseline-current \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --contention-cases fanout,fairness,disappear-blackhole \
  --contention-payload-size 512 \
  --per-client-mbps 5 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000 \
  --max-queued-bytes 67108864 \
  --warmup 10s \
  --duration 60s \
  --iterations 3 \
  --start-delay 90s
```

The generated plan schedules:

- `40` default-limiter curve rows across payloads `256,512,1200,1340,1400`
- `40` raised-limiter curve rows across the same payloads
- `3` contention rows at `500` clients split across two receiver hosts

Before promotion, the lab output must include:

- server and receiver host reports
- at least two distinct captured hostnames
- topology metadata
- complete server and receiver artifacts
- merged `suite-aggregate.jsonl`
- selected `bandwidth-capacity.*` rows
- passing `validation.json`

Promote only after validation passes:

```bash
benchmark/scripts/promote-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline-current/combined \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan-current/curve-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan-current/curve-raised-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan-current/contention-plan/manifest.jsonl \
  --out benchmark/build/benchmark-baselines \
  --name lab-<date>-<topology>
```

Use the promoted directory, or `benchmark/build/benchmark-baselines/latest`, as the baseline input for candidate comparisons with `--require-validation`.

After the perfect-network baseline and adverse-network campaign are both promoted, run the final readiness gate:

```bash
benchmark/scripts/check-baseline-readiness.sh \
  --lab-baseline benchmark/build/benchmark-baselines/lab-<date>-<topology> \
  --impairment-baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \
  --out benchmark/build/benchmark-results/baseline-readiness
```

The baseline is not accepted as the comparison baseline until this readiness check passes.

## Adverse-Network Lab Plan

The current host/NIC-level impairment campaign is generated with:

```bash
benchmark/scripts/plan-lab-impairment.sh \
  --out benchmark/build/benchmark-results/lab-impairment-plan-current \
  --artifact-root benchmark/build/benchmark-results/lab-impairment-current \
  --interface <nic> \
  --profiles perfect,near-loss,regional-loss,poor,severe \
  --target-host-role receiver-a \
  -- \
  --server-host <server-ip> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --contention-cases fanout,fairness,disappear-blackhole \
  --contention-payload-size 512 \
  --per-client-mbps 5 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000 \
  --max-queued-bytes 67108864 \
  --warmup 10s \
  --duration 60s \
  --iterations 3 \
  --start-delay 90s
```

The current generated artifact in this worktree is:

```text
benchmark/build/benchmark-results/lab-impairment-plan-current/
```

That plan contains five profiles:

| Profile | Latency | Jitter | Loss |
| --- | ---: | ---: | ---: |
| `perfect` | `0ms` | `0ms` | `0%` |
| `near-loss` | `10ms` | `2ms` | `2%` |
| `regional-loss` | `50ms` | `5ms` | `2%` |
| `poor` | `100ms` | `10ms` | `5%` |
| `severe` | `200ms` | `20ms` | `10%` |

Each profile schedules:

- `40` default-limiter one-client bandwidth curve rows
- `40` raised-limiter one-client bandwidth curve rows
- `3` contention rows at `500` clients split across two receiver hosts

The impairment planner also writes `check-plan-freshness.sh`, `netem/<profile>-apply.sh`, `netem/<profile>-status.sh`, `netem/<profile>-clear.sh`, and `summarize-campaign.sh`. Run the campaign freshness check before starting profile workers, then run the netem scripts on the shaped receiver host or network namespace before and after the matching profile plan. Keep the generated `<profile>-status-*.txt` files with the copied benchmark artifacts; `validate-all.sh` requires that evidence by default. After profile merge and validation, keep `campaign-summary/impairment-summary.json`, `impairment-summary.jsonl`, and `impairment-summary.md` with the baseline package so adverse-network capacity and contention behavior are reviewed as one campaign. Promote that campaign with `benchmark/scripts/promote-lab-impairment.sh`, then compare future candidate campaigns with `benchmark/scripts/compare-lab-impairment.sh`. The `perfect` profile is the no-impairment companion and should still capture qdisc status so later comparisons can prove the baseline host was unshaped.
