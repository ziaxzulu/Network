# Baseline Status

This is the current handoff state for the established RakNet performance baseline. It records what is ready to use for performance engineering, what local evidence exists, and what still has to run in the lab before a result becomes the baseline of record.

## Current Answer

The benchmark is a good synthetic for established-channel transport pressure:

- primary `RELIABLE_ORDERED` channel `0` server-to-client traffic
- one-client bandwidth-latency curves for best-case transport capacity
- multi-client fanout, immediate small-packet fanout, fairness, disappearing-client, and batched-game-traffic cases
- queue, ACK/NACK, stale datagram, disconnect, open/active peer, final channel-state, fairness, per-client throughput, send/deliver, undelivered send-work, healthy/affected datagram-rate, and probe-latency indicators
- local loopback runs for regression and remote worker runs for separate-host lab evidence

It is not a full Bedrock production emulator yet. The main remaining workload gaps are compression modeling, captured logical packet distributions, pass-through versus re-encode batch behavior, proxy pass-through, and captured host/NIC-level impairment results. Source evidence and the gap list are in [`production-usage-evidence.md`](production-usage-evidence.md).

## Base Matrix

Use [`baseline-matrix.md`](baseline-matrix.md) as the source of truth. The first recurring matrix should include:

- best-case one-client curves with payloads `64`, `256`, `512`, `1200`, `1340`, `1400`, and split-heavy payloads
- default-limiter and raised-limiter one-client curves
- `100+` client contention rows at `5Mbps` per client
- fanout, immediate small-packet fanout, fairness, and disappearing-client rows
- `10ms`, `20ms`, and `50ms` batched-game-traffic rows
- paced resource-pack rows for `8KiB` and `256KiB` chunks
- host-level impairment campaigns for selected curve and contention rows

The executable local/lab profiles in `benchmark/scripts/run-baseline-matrix.sh` and the remote lab planner in `benchmark/scripts/plan-lab-baseline.sh` are aligned with that matrix.

## Local Development Evidence

Local loopback artifacts are useful for regression shape only. They should not be used for line-rate claims. Use `benchmark/scripts/run-baseline-matrix.sh --profile pilot` when you need a short three-iteration local development comparison without running the full local matrix.

For a stronger single-host smoke path, use `benchmark/scripts/run-netns-worker-smoke.sh`. It runs the normal server/receiver worker roles through Linux network namespaces and veth pairs so `tc netem` and blackhole behavior are applied outside the JVM. This is useful for local retry-pressure and external-qdisc regression checks, but it is still not accepted as line-rate or baseline-of-record evidence.

Current netns blackhole dry-run artifact in this worktree:

```text
benchmark/build/benchmark-results/netns-blackhole-dry-current/
```

This dry-run generated the server, healthy-receiver, affected-receiver, qdisc-status, blackhole-scheduling, and merge commands for a `20` client disappearing-client smoke with `18` healthy clients, `2` affected clients, payload `512`, `5Mbps` per client, and server-to-client blackhole applied outside the JVM. It did not execute traffic: this host has `ip` and `tc`, but `sudo -n true` reports that a password is required, and `run-netns-worker-smoke.sh --execute` requires root or equivalent `CAP_NET_ADMIN`.

Latest current-branch smoke artifact in this worktree:

```text
benchmark/build/benchmark-results/current-branch-smoke-20260622T061238Z/
```

This run completed the current smoke profile and produced `10` aggregate rows across best-case, two curve rate points, fanout, immediate small-packet fanout, fairness, stop-reading disappearance, blackhole disappearance, batched game traffic, and resource-pack transfer cases. Every aggregate row includes the current retry-pressure send-work fields: `undeliveredServerGbps`, `affectedUndeliveredServerGbps`, and `affectedServerDatagramsOutPerSecond`. Treat it as output-schema and instrumentation proof only; it uses one measured iteration per case and all rows are intentionally marked unstable by the default stability policy:

| Case | Delivered Gbps | p99 RTT ms | Max queue bytes | Undelivered Gbps | Affected undelivered Gbps | Affected datagram out/s | Retry signal |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `immediate-10x0_1-p256` | `0.000997376` | `10.481245` | `273` | `0.000083408` | `0` | `0` | none |
| `fanout-10x0_2` | `0.001994752` | `9.912738` | `529` | `0.000083504` | `0` | `0` | none |
| `disappear-10-blackhole` | `0.001892352` | `12.106768` | `10820` | `0.000203104` | `0.000127936` | `57` | `25` stale datagrams/s |
| `fairness-10-2poor` | `0.001961984` | `476.258398` | `2065` | `0.000158544` | `0.000091920` | `109` | `2` NACK out/s |

The smoke capacity selector did not choose a stable point, which is expected for a one-iteration smoke. Its best observed curve row was `curve-100_0mbps` at `0.097824Gbps`, rejected for `insufficient-iterations`.

Latest local pilot artifact in this worktree:

```text
benchmark/build/benchmark-results/current-local-pilot-post-impairment-cleanup-20260622T110109Z/
```

This run completed the representative pilot profile after the benchmark-managed impairment cleanup in git revision `4d1c961cc7cd`. It produced parseable suite, aggregate, and capacity-selector artifacts and the saved runner log did not contain `LEAK:` or `ResourceLeakDetector` lines. It is current-branch loopback evidence that the benchmark shape executes and emits the retry-pressure fields, but it is still not a baseline-of-record result. Seven of the nine aggregate rows were unstable under the default stability policy, mostly because the short loopback run had p99 probe-latency spread. The selected local capacity row and immediate small-packet fanout row were stable locally:

| Case | Delivered Gbps | Client p50 Mbps | p99 RTT ms | Max queue bytes | Affected undelivered Gbps | Affected datagram out/s | Retry signal | Unstable reason |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `pilot-curve-1c-mtu` / `curve-50_0mbps` | `0.04997952` | `49.97952` | `9.273926` | `70800` | `0` | `0` | none | `p99-spread` |
| `pilot-curve-1c-mtu` / `curve-100_0mbps` | `0.09996864` | `99.96864` | `10.201239` | `158400` | `0` | `0` | none | none |
| `pilot-curve-1c-mtu` / `curve-250_0mbps` | `0.24945216` | `249.45216` | `21.656095` | `433200` | `0` | `0` | none | `p99-spread` |
| `pilot-fanout-100x5` | `0.499666944` | `4.99712` | `63.911171` | `1335825` | `0` | `0` | none | `throughput-spread`, `p99-spread` |
| `pilot-immediate-100x1-p256` | `0.0999374848` | `0.999424` | `10.088538` | `1792` | `0` | `0` | none | none |
| `pilot-fairness-100-10poor` | `0.449705984` | `4.996437333333333` | `27.805779` | `4227072` | `0.003963556` | `618` | `22.833333333333332` stale datagrams/s, `5.833333333333333` NACK out/s | `throughput-spread`, `p99-spread` |
| `pilot-disappear-100-blackhole` | `0.4655356586666667` | `4.989610666666667` | `176.084499` | `2491031` | `0.03108224533333333` | `5745.5` | `3609.1666666666665` stale datagrams/s | `p99-spread` |
| `pilot-batch-100-20ms` | `0.51136` | `5.1008` | `37.022604` | `306133` | `0` | `0` | `4` stale datagrams/s, `3` NACK out/s | `p99-spread` |
| `pilot-resource-100-8k-200ms` | `0.032768` | `0.32768` | `10.836547` | `8209` | `0` | `0` | none | `p99-spread` |

The capacity selector chose `curve-100_0mbps` at `0.09996864Gbps` as the stable local pilot capacity point. The best observed curve row was `curve-250_0mbps` at `0.24945216Gbps`, rejected because unstable rows are not allowed. This is useful as a current developer regression fixture and artifact-shape proof, but capacity selection for the baseline of record still requires separate-host lab evidence.

Latest local best-case curve artifact in this worktree:

```text
benchmark/build/benchmark-results/current-local-bestcase-mtu1340-20260622T071105Z/
```

With payload `1340`, three measured iterations, and default stability policy, the capacity selector did not choose a stable local point. The positive-throughput rows delivered the offered rate up to the `500Mbps` target, but each was rejected for p99 probe-latency spread. The `1000Mbps` and `unlimited` rows were rejected as zero-delivery rows after disconnect/no active peers:

| Candidate | Target Mbps | Delivered Gbps | p99 RTT ms | Max queue bytes | Rejection |
| --- | ---: | ---: | ---: | ---: | --- |
| `curve-100_0mbps` | `100` | `0.099984368` | `10.242443` | `148740` | `p99-spread` |
| `curve-250_0mbps` | `250` | `0.249718112` | `11.406958` | `505197` | `p99-spread` |
| `curve-500_0mbps` | `500` | `0.498546464` | `34.466579` | `790600` | `p99-spread` |
| `curve-1000_0mbps` | `1000` | `0` | `0` | `0` | `zero-delivery` |
| `curve-unlimited` | `0` | `0` | `0` | `0` | `zero-delivery` |

The best observed current local point was `curve-500_0mbps` at `0.498546Gbps`, but it was rejected because unstable rows are not allowed. Zero-delivery curve rows are rejected by the stability policy, capacity selector, and lab validator. This makes the local loopback evidence useful for regression shape only; it is not a stable capacity baseline.

Latest raised-limiter local best-case curve artifact in this worktree:

```text
benchmark/build/benchmark-results/current-raised-bestcase-mtu1340-20260622T094358Z/20260622-104359/
```

This run used payload `1340`, three measured iterations, raised packet limits (`--packet-limit 100000 --global-packet-limit 1000000`), `64MiB` max queued bytes, and git revision `98eac0e16eb7`. It selected a stable local capacity row at the `500Mbps` target. The `750Mbps` row showed the overload knee: one iteration delivered close to target, but the row as a whole was rejected for throughput spread, p99 spread, and disconnects. Higher targets mostly failed after queue growth or no active delivery.

| Candidate | Target Mbps | Delivered Gbps | p99 RTT ms | Max queue bytes | Send/deliver | Rejection |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `curve-100_0mbps` | `100` | `0.09996668` | `29.139101` | `151420` | `1.0107511922972734` | `p99-spread` |
| `curve-250_0mbps` | `250` | `0.24964468` | `31.96974` | `416740` | `1.0234830920490674` | `p99-spread` |
| `curve-500_0mbps` | `500` | `0.49818118` | `45.85103` | `876360` | `1.1109891841468333` | none |
| `curve-750_0mbps` | `750` | `0.0938067` | `761.225065` | `65044957` | `4.175580507575685` | `throughput-spread`, `p99-spread`, `disconnects` |
| `curve-1000_0mbps` | `1000` | `0` | `0` | `66785600` | `1375.0159903186768` | `throughput-spread`, `zero-delivery`, `disconnects` |
| `curve-1500_0mbps` | `1500` | `0` | `0` | `0` | `0` | `zero-delivery` |
| `curve-2000_0mbps` | `2000` | `0` | `0` | `0` | `0` | `zero-delivery` |
| `curve-unlimited` | `0` | `0` | `0` | `0` | `0` | `zero-delivery` |

This is the best current local capacity selector result, but it is still loopback-only. Treat it as a regression baseline and overload-knee clue, not as line-rate evidence.

Latest current-branch 100-client local contention suite artifact in this worktree:

```text
benchmark/build/benchmark-results/current-local-contention-100-20260622T095159Z/
```

This run used the local profile filtered to 100-client contention rows on git revision `609ca9c45431`. All selected cases passed and produced aggregate artifacts. It is the best current single-host evidence for the contention half of the synthetic benchmark: healthy clients generally held the intended local throughput, while poor-link and disappearing-client rows exposed the expected extra send work, queue pressure, stale datagrams, and p99 probe spread. It is still loopback-only and should not be promoted as the baseline of record.

| Case | Delivered Gbps | Healthy Gbps | Affected Gbps | Client p50 Mbps | Healthy p50 Mbps | Affected p50 Mbps | Healthy fairness | p99 RTT ms | Max queue bytes | Undelivered Gbps | Affected undelivered Gbps | Affected datagram out/s | Retry signal | Unstable reason |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `fanout-100x5` | `0.4942262272` | `0.4942262272` | `0` | `4.9422336` | `4.9422336` | `0` | `0.9999999061398828` | `217.97969` | `390707` | `0.0115993368` | `0` | `0` | none | `p99-spread` |
| `immediate-100x1-p256` | `0.099980288` | `0.099980288` | `0` | `0.9998336` | `0.9998336` | `0` | `0.9999999468162345` | `10.125639` | `2048` | `0.0045202416` | `0` | `0` | none | none |
| `fairness-100-10poor` | `0.4351164416` | `0.43511453013333334` | `0.0000027306666666666666` | `4.834372266666667` | `4.8346453333333335` | `0` | `0.99999997982028` | `339.494957` | `9895936` | `0.01395084` | `0.0034922272` | `545.0666666666667` | `20.8` stale datagrams/s, `7.133333333333334` NACK out/s | `p99-spread` |
| `disappear-100-close` | `0.46484302506666664` | `0.4495848789333333` | `0.016499234133333332` | `4.995208533333333` | `4.9954816` | `1.6498688000000001` | `0.9999991096459783` | `269.03889` | `461346` | `0.011331053866666668` | `0.00040264479999999996` | `2063.866666666667` | none | `p99-spread` |
| `disappear-100-stopread` | `0.44664668160000004` | `0.43016437760000004` | `0.016482304` | `4.780032` | `4.780032` | `1.6471381333333333` | `0.9999999424044643` | `342.923905` | `6122626` | `0.05401864106666667` | `0.042254070399999996` | `7202.533333333334` | `5164.133333333333` stale datagrams/s | `p99-spread` |
| `disappear-100-blackhole` | `0.45202172586666667` | `0.435458048` | `0.016563677866666668` | `4.828637866666666` | `4.8300032` | `1.6564223999999999` | `0.999951282033574` | `200.320648` | `6186643` | `0.04825476693333333` | `0.0348397072` | `6331.133333333333` | `4512.2` stale datagrams/s | `p99-spread` |
| `batch-100-20ms` | `0.51136` | `0.51136` | `0` | `5.1008` | `5.1008` | `0` | `0.9999812033902818` | `34.25217` | `25777` | `0.0106383968` | `0` | `0` | `0.4` NACK out/s | `p99-spread` |
| `resource-100-8k-200ms` | `0.032768` | `0.032768` | `0` | `0.32768` | `0.32768` | `0` | `1.0` | `13.944991` | `8209` | `0.0009968776` | `0` | `0` | none | `p99-spread` |

Previous focused current-branch 100-client fanout artifact in this worktree:

```text
benchmark/build/benchmark-results/current-local-fanout-100-20260622T072219Z/
```

This run used `100` established healthy clients, payload `512`, `5Mbps` per client, `2s` warmup, `10s` measurement, and `3` measured iterations. It is the clean local contention reference for the impaired-client rows below. It passed execution, delivered the expected aggregate load, and had near-perfect per-client fairness, but it is still local loopback evidence and was unstable on p99 probe RTT spread:

| Case | Delivered Gbps | Client p50 Mbps | Client p99 Mbps | Fairness | p99 RTT ms | Max queue bytes | Undelivered Gbps | Datagram out/s | Retry signal | Unstable reason |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `fanout-100x5` | `0.49545584639999996` | `4.9545216` | `4.95616` | `0.9999843765777217` | `325.667429` | `274449` | `0.011588379199999999` | `61988.0` | none | `p99-spread` |

Previous focused current-branch 100-client fairness artifact in this worktree:

```text
benchmark/build/benchmark-results/current-local-fairness-100-20260622T071941Z/
```

This run used `100` established clients, `10` impaired clients, payload `512`, `5Mbps` per client, `100ms` latency, `10ms` jitter, `5%` packet loss, `2s` warmup, `15s` measurement, and `3` measured iterations. Healthy clients held the intended local throughput, while impaired clients received almost no useful traffic. It passed execution and emitted current send-work fields, but it is still local loopback evidence and was unstable on p99 probe RTT spread:

| Case | Delivered Gbps | Healthy Gbps | Affected Gbps | Healthy client p50 Mbps | Affected client p50 Mbps | Healthy fairness | Affected fairness | p99 RTT ms | Max queue bytes | Affected undelivered Gbps | Affected datagram out/s | Retry signal | Unstable reason |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `fairness-100-10poor` | `0.449581056` | `0.4495805098666667` | `0.0000016384` | `4.995208533333333` | `0` | `0.9999999480568666` | `0.2790943396226415` | `161.770849` | `10082816` | `0.0035025002666666666` | `539.3333333333334` | `19.266666666666666` stale datagrams/s, `6.6` NACK out/s | `p99-spread` |

Previous focused current-branch 100-client blackhole artifact in this worktree:

```text
benchmark/build/benchmark-results/current-local-disappear-blackhole-100-20260622T071700Z/
```

This run used `100` established clients, `10` blackholed clients, payload `512`, `5Mbps` per client, `2s` warmup, `15s` measurement, and `3` measured iterations. It passed execution and emitted current retry-pressure fields, but it is still local loopback evidence and was unstable on p99 probe RTT spread:

| Case | Delivered Gbps | Healthy Gbps | Affected Gbps | Healthy client p50 Mbps | Affected client p50 Mbps | p99 RTT ms | Max queue bytes | Affected undelivered Gbps | Affected datagram out/s | Retry signal | Unstable reason |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `disappear-100-blackhole` | `0.46292609706666665` | `0.4463916373333333` | `0.016534459733333333` | `4.958890666666667` | `1.6534186666666668` | `275.023124` | `6145683` | `0.03576692213333333` | `6356.8` | `4242.866666666667` stale datagrams/s | `p99-spread` |

Previous broader local 100-client contention artifact in this worktree:

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

The baseline of record is not complete until a separate-host lab run is captured, validated, and promoted. Generate and preflight the current recommended handoff with:

```bash
benchmark/scripts/prepare-fresh-lab-handoff.sh \
  --out benchmark/build/benchmark-results/lab-handoff-current \
  --artifact-root benchmark/build/benchmark-results/lab-run-current \
  --source-audit-out benchmark/build/benchmark-results/production-evidence-current \
  --server-host <server-ip> \
  --interface <nic> \
  --expect-mtu <mtu> \
  --expect-min-cpus <min-cpus> \
  --curve-receiver receiver-a=1 \
  --curve-payload-sizes 64,256,512,1200,1340,1400,262144 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --sudo-netem
```

This wrapper refreshes `source-audit.json`, generates the perfect-network plan and impairment campaign plan, runs `check-lab-handoff.sh --require-source-audit --require-current-revision`, and runs the generated freshness checks. The handoff writes the top-level run order, `prereq-commands.sh`, promotion commands, readiness-gate command, and `fresh-handoff-summary.json` for automation. That summary includes the Network revision, clean/dirty tracked-file state, source-audit readiness, required/optional source availability, preflight readiness, combined and per-stage issue counts, planned row counts, and the preflight requirements for reliability, worker command argument consistency, payload/rate/profile coverage, contention gates, minimum measured iterations, batch/resource-pack rows, blackhole coverage, source-audit, and current-revision checks.

Each handoff also includes `promote-and-check.sh`. After lab workers finish, receiver artifacts are copied back, `perfect-plan/merge-all.sh` runs, and `impairment-plan/summarize-campaign.sh` completes, run that helper to rerun handoff preflight, promote both baseline packages, and run the final readiness gate with the handoff's exact manifest and artifact paths.

That preflight checks handoff structure, generated scripts, the prereq helper, profile plans, the production-evidence fingerprint, optional source-audit fingerprint/readiness, optional source-audit revision match against the current checkout, curve matrix coverage, contention scenario coverage, required `blackhole` disappearance mode, and whether contention plan rows keep the handoff's receiver-total client count and per-client Mbps target. The immediate small-packet row uses its own lower `immediatePerClientMbps` target and is not used to satisfy the main `5Mbps` contention gate. By default the preflight rejects handoffs below `500` contention clients or below `5Mbps` for the main contention target, matching the baseline readiness gate. It also checks that generated contention plans include the production-shape batch/resource rows and blackhole disappearance row before operators spend lab time on them. The underlying perfect-network baseline plan is equivalent to:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan-current \
  --artifact-root benchmark/build/benchmark-results/lab-baseline-current \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --contention-cases fanout,immediate,fairness,disappear-blackhole,batched,resource-pack \
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

- `56` default-limiter curve rows across payloads `64,256,512,1200,1340,1400,262144`
- `56` raised-limiter curve rows across the same payloads
- `9` contention/workload rows at `500` clients split across two receiver hosts: fanout, immediate small-packet fanout, fairness, blackhole disappearance, three batched cadences, and two resource-pack transfers

Fresh structural handoff audits should pass `check-lab-handoff.sh --require-source-audit --require-current-revision` with `ready=true` and `0` issues. The expected generated shape is `56` default-limiter curve rows, `56` raised-limiter curve rows, `9` perfect-network contention rows, at least `3` measured iterations, and five impairment profiles (`perfect`, `near-loss`, `regional-loss`, `poor`, `severe`) each with the same `56/56/9` row shape. The perfect contention plan includes `lab-perfect-contention-immediate-500x1-p256` at payload `256` and `1Mbps` per client, plus the main `5Mbps` fanout, fairness, blackhole disappearance, batched, and resource-pack rows.

Current-revision structural handoffs are generated artifacts, not durable committed references. Any commit changes the Network revision fingerprint embedded in the source audit and handoff manifest, so a handoff generated before a documentation or script commit can become stale for `--require-current-revision`.

Before lab operators distribute commands, regenerate a fresh handoff from the exact checkout that will be used for lab execution. A valid handoff should report `ready=true`, `issueCount=0`, `sourceAuditIssueCount=0`, `handoffIssueCount=0`, no dirty tracked Network files, and the expected `56` default curve, `56` raised-limiter curve, and `9` contention rows for the perfect-network plan. The impairment campaign should contain `perfect`, `near-loss`, `regional-loss`, `poor`, and `severe` profiles, each with the same `56/56/9` row shape. The refreshed source audit should also be `ready=true` with `0` issues and confirm the required Geyser, Cloudburst Protocol, Cloudburst Nukkit, and private CubeCraft checkouts are available. TeamZiax eBPF availability is captured in the same summary as optional companion evidence unless the operator explicitly includes `teamziax-ebpf` in `--require-sources`.

Latest structural handoff generated during this status pass:

```text
benchmark/build/benchmark-results/current-fresh-handoff-20260622T110854Z/
```

It was generated from Network revision `30aeb80673d2` and reported `ready=true`, `issueCount=0`, source-audit ready, preflight ready, `56` default curve rows, `56` raised-limiter curve rows, and `9` contention rows. The expected, manifest, and generated helper prereq roles matched: `receiver-a`, `receiver-b`, and `server`. Because any subsequent commit changes the source-audit revision fingerprint, regenerate the handoff again from the exact checkout used for lab execution.

The readiness report's `proofChecklist` JSON and Markdown sections show the required evidence chain: fresh handoff, perfect-network execution, impairment execution, and promotion. It remains intentionally `ready=false` until separate-host lab execution produces promoted perfect-network and impairment baselines.

Older generated handoffs are intentionally not reusable after source or plan commits. Rerunning `check-lab-handoff.sh --require-source-audit --require-current-revision` against an older generated handoff can report `not-ready` with `handoff-source-audit-revision-mismatch` or `handoff-source-audit-sha-mismatch`, because the handoff embeds the source-audit revision and fingerprint from the checkout that created it. This is expected and useful. Before lab operators distribute commands, rerun `prepare-fresh-lab-handoff.sh` from the current checkout so source evidence, handoff generation, required preflight, freshness checks, and `fresh-handoff-summary.json` are produced together.

Local placeholder host values (`127.0.0.1`, `lo`) prove planner/preflight structure only. They are not reusable lab execution handoffs, not separate-host line-rate evidence, and do not replace the lab baseline run.

Before promotion, the lab output must include:

- ready server and receiver prereq reports
- strict prereq gates for clock sync, expected MTU, minimum CPU count, and no pre-existing netem qdisc
- server and receiver host reports
- at least two distinct captured hostnames
- topology metadata
- complete server and receiver artifacts
- merged `suite-aggregate.jsonl`
- selected `bandwidth-capacity.*` rows
- passing `validation.json`
- matching handoff and plan-manifest reliability metadata

Promote only after validation passes:

```bash
benchmark/scripts/promote-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline-current/combined \
  --handoff-manifest benchmark/build/benchmark-results/lab-handoff-current/handoff-manifest.json \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan-current/curve-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan-current/curve-raised-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan-current/contention-plan/manifest.jsonl \
  --out benchmark/build/benchmark-baselines \
  --name lab-<date>-<topology> \
  -- \
  --min-iterations 3 \
  --min-healthy-fairness 0.95 \
  --max-healthy-send-deliver-ratio 1.2 \
  --max-affected-send-deliver-ratio 5 \
  --min-contention-clients 500 \
  --min-contention-target-client-mbps 5
```

Use the promoted directory, or `benchmark/build/benchmark-baselines/latest`, as the baseline input for candidate comparisons with `--require-validation`.

After the perfect-network baseline and adverse-network campaign are both promoted, run the final readiness gate:

```bash
benchmark/scripts/check-baseline-readiness.sh \
  --lab-baseline benchmark/build/benchmark-baselines/lab-<date>-<topology> \
  --impairment-baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \
  --required-min-contention-clients 500 \
  --required-min-contention-target-client-mbps 5 \
  --required-disappearance-modes blackhole \
  --out benchmark/build/benchmark-results/baseline-readiness
```

The baseline is not accepted as the comparison baseline until this readiness check passes.
Readiness also checks that the promoted artifacts have no validation or missing retry-pressure-field bypass markers, that the perfect-network baseline retained its copied handoff manifest with matching production-evidence and source-audit fingerprints, that the promoted perfect-network package still contains copied curve, raised-curve, and contention manifests plus host reports and strict ready prereq reports from separate hosts, that the promoted impairment package still contains its copied campaign manifest plus per-profile validation, aggregate, capacity, and netem status evidence, that the perfect-network validation enforced the requested measured-iteration count, contention scale, and per-client Mbps target, and that both perfect-network and impairment packages include `blackhole` disappearing-client coverage. The recommended handoff uses `3` measured iterations and `500` clients split across two receiver hosts, so keep the explicit readiness arguments above when checking the promoted baseline of record.
It also requires the production-shape contention rows from the source audit: immediate small-packet fanout at payload `256` and `1Mbps` per client, `10ms`, `20ms`, and `50ms` batched-game-traffic rows, plus `8192` and `262144` byte resource-pack rows at `200ms`. Promoted aggregate rows must include retry-pressure send-work fields such as `undeliveredServerGbps`, `affectedUndeliveredServerGbps`, and `affectedServerDatagramsOutPerSecond`, so future candidate comparisons can detect send work consumed by impaired or disappeared clients.

Current readiness audit in this worktree:

```bash
benchmark/scripts/check-baseline-readiness.sh \
  --out benchmark/build/benchmark-results/readiness-current-fresh-handoff-20260622T110911Z
```

The audit correctly reports `not-ready` with `6` issues because no promoted perfect-network baseline or promoted impairment baseline exists yet. Its blocking issues are the missing promoted lab baseline manifest, `validation.json`, `suite-aggregate.jsonl`, `bandwidth-capacity.jsonl`, impairment baseline manifest, and impairment campaign summary. This is the expected state before the separate-host lab campaign has been run, validated, and promoted.

TeamZiax VM/eBPF replay artifacts are optional companion evidence for lab captures. They help validate filter and capture-replay behavior, but they do not replace active established-channel RakNet throughput, latency, fairness, and retry-pressure measurements. See [`teamziax-vm-bench.md`](teamziax-vm-bench.md) before attaching those artifacts to a baseline package.

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
  --curve-payload-sizes 64,256,512,1200,1340,1400,262144 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --contention-cases fanout,immediate,fairness,disappear-blackhole,batched,resource-pack \
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

- `56` default-limiter one-client bandwidth curve rows
- `56` raised-limiter one-client bandwidth curve rows
- `9` contention/workload rows at `500` clients split across two receiver hosts

The impairment planner also writes `check-plan-freshness.sh`, `netem/<profile>-apply.sh`, `netem/<profile>-status.sh`, `netem/<profile>-clear.sh`, and `summarize-campaign.sh`. Run the campaign freshness check before starting profile workers, then run the netem scripts on the shaped receiver host or network namespace before and after the matching profile plan. Keep the generated `<profile>-status-*.txt` files with the copied benchmark artifacts; `validate-all.sh` requires that evidence by default. After profile merge and validation, keep `campaign-summary/impairment-summary.json`, `impairment-summary.jsonl`, and `impairment-summary.md` with the baseline package so adverse-network capacity and contention behavior are reviewed as one campaign. Campaign summary and promotion reject profile validation bypass flags and missing retry-pressure-field bypasses by default; `--allow-validation-bypasses` and `--allow-missing-retry-pressure-fields` are only for non-baseline smoke packages. Promote that campaign with `benchmark/scripts/promote-lab-impairment.sh`, then compare future candidate campaigns with `benchmark/scripts/compare-lab-impairment.sh`. The `perfect` profile is the no-impairment companion and should still capture qdisc status so later comparisons can prove the baseline host was unshaped.
