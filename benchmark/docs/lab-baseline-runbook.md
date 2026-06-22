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

Before generating the handoff, capture the source-evidence revision state that
backs the synthetic matrix:

```bash
benchmark/scripts/capture-production-evidence.sh \
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/production-evidence \
  --require-sources geyser,cloudburst-protocol,cloudburst-nukkit,cubecraft
```

Set `GEYSER_REPO`, `CLOUDBURST_PROTOCOL_REPO`, `CLOUDBURST_NUKKIT_REPO`, or
`CUBECRAFT_REPO` if those checkouts are not in the usual local worktree
locations. Keep `source-audit.json` and `source-audit.md` beside the lab
handoff; private checkout paths are omitted by default.

## VM Harness Relationship

The TeamZiax Bedrock eBPF filter repository has a useful VM benchmark pattern for root-free test orchestration: host-side QEMU lifecycle management, guest setup, shared capture/output directories, and repeatable artifact collection. That orchestration shape is reusable for future isolated Network lab runs.

Do not copy the eBPF benchmark workload directly into this baseline. Its PCAP/XDP replay path measures packet-filter behavior, while this benchmark baseline must use active established RakNet server and receiver workers so ACK/NACK, retransmit, queue, fairness, disappearance, and probe-latency behavior come from real connected sessions.

When lab captures exist, use the TeamZiax VM/eBPF bench as optional companion evidence rather than as the baseline itself. The recommended artifact layout, replay commands, and evidence checklist are in [`teamziax-vm-bench.md`](teamziax-vm-bench.md).

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
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/comparison.md \
  --require-validation
```

The comparison script uses `suite-aggregate.jsonl` automatically when comparing suite directories. That means the comparison is based on per-case medians and includes stability spread. It fails when matched rows change matrix shape, including clients, payload, reliability, batching, target rate, impairment, packet limits, or queue cap. For lab sign-off, pass `--require-validation`; the comparison then fails when either side lacks `validation.json`, when validation failed, when validation used baseline bypass flags, or when comparable rows are missing retry-pressure fields, so invalid promoted baselines or invalid candidate lab runs do not look like clean regressions. Use `--allow-validation-bypasses` and `--allow-missing-retry-pressure-fields` only for non-baseline smoke comparisons. Use the raw `suite-summary.jsonl` files only when diagnosing individual measured iterations.

## Remote Worker Runs

For line-rate validation, prefer explicit server and receiver workers instead of single-process local mode.

For the baseline-of-record run, generate the complete handoff package first. It creates the perfect-network plan, the host/NIC impairment campaign plan, and a top-level README with promotion and readiness commands:

```bash
benchmark/scripts/prepare-fresh-lab-handoff.sh \
  --out benchmark/build/benchmark-results/lab-handoff-<date>-<topology> \
  --artifact-root benchmark/build/benchmark-results/lab-run-<date>-<topology> \
  --source-audit-out benchmark/build/benchmark-results/lab-<date>-<topology>/production-evidence \
  --server-host <server-ip> \
  --interface <nic> \
  --expect-mtu <mtu> \
  --expect-min-cpus <min-cpus> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --sudo-netem
```

Use the generated handoff README as the operator run order. The wrapper refreshes source evidence, wires that exact `source-audit.json` into the handoff, runs the required handoff preflight, and runs generated freshness checks. The lower-level commands below are still useful when diagnosing or building a custom campaign.
The handoff manifest records the `benchmark/docs/production-usage-evidence.md` SHA-256 fingerprint and the `capture-production-evidence.sh` audit fingerprint so promoted lab artifacts can be traced back to the exact source revisions used to choose the matrix.

Before distributing the generated command scripts to lab hosts, confirm that the wrapper-created preflight is still ready, or rerun it manually on the merge/control host:

```bash
benchmark/scripts/check-lab-handoff.sh \
  --handoff benchmark/build/benchmark-results/lab-handoff-<date>-<topology> \
  --require-source-audit \
  --require-current-revision
```

The preflight writes `preflight/handoff-check.json` and `preflight/handoff-check.md`. It checks the handoff manifest, generated scripts, production-evidence SHA-256 freshness, optional source-audit SHA-256/readiness, optional source-audit revision match against the current checkout, perfect-network and impairment profile manifests, curve payload/rate coverage, contention scenario coverage, required `blackhole` disappearance mode, measured iteration count, and contention row client-count/per-client-rate consistency. The immediate small-packet row is checked against `immediatePerClientMbps`; it is intentionally lower-rate and does not satisfy the main contention-rate gate. By default the preflight requires at least `500` planned contention clients, at least `5Mbps` for the main contention target, and at least `3` measured iterations; use explicit lower `--required-min-*` overrides only for smoke handoffs that will not become the baseline of record. Run the generated freshness checks after this and shortly before execution so stale scheduled start times are still caught.
It also rejects handoffs that omit the required production-shape batch intervals, resource-pack chunk/interval rows, or blackhole disappearing-client row, so these mistakes are caught before the lab run rather than at final readiness.

Before host capture or worker startup, run the local prereq check on every server and receiver host:

```bash
HOST_ROLE=server benchmark/scripts/check-lab-host-prereqs.sh \
  --interface <nic> \
  --out benchmark/build/benchmark-results/lab-<date>-<topology>/prereq-server-$(hostname) \
  --expect-mtu <mtu> \
  --expect-min-cpus <min-cpus> \
  --require-clock-sync \
  --require-no-netem
```

Use the matching `HOST_ROLE` for receiver hosts, and add `--require-sudo-netem` on hosts that will run generated sudo netem scripts. Add `--require-cpu-performance` when the lab hosts have been pinned to the performance governor; leave it advisory on hosts where the governor is unavailable but document that in `topology.md`. The check is read-only and writes `prereq.json` plus `prereq.md`; it fails early for missing Java/JDK 17+, missing Gradle wrapper, missing `ip`/`tc`, a missing selected interface, qdisc inspection failures, strict clock/MTU/CPU-count/no-netem mismatches, or strict CPU-governor mismatches. Keep those directories under the lab artifact root. Baseline validation requires at least two ready strict `prereq.json` files from at least two distinct hostnames unless `--allow-missing-prereq-context` or `--allow-loose-prereq-gates` is used for a non-baseline smoke run.

For a baseline-of-record campaign, prefer the top-level handoff above. If you need to use the lower-level lab planner directly, keep the same `500`-client contention shape. It generates a remote bandwidth-curve plan, a remote contention plan, host-capture commands, a topology template, and a combined merge script:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan \
  --artifact-root benchmark/build/benchmark-results/lab-baseline \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --curve-payload-sizes 64,256,512,1200,1340,1400,262144 \
  --curve-rates-mbps 100,250,500,750,1000,1500,2000,unlimited \
  --contention-cases fanout,immediate,fairness,disappear-blackhole,batched,resource-pack \
  --contention-payload-size 512 \
  --per-client-mbps 5 \
  --warmup 10s \
  --duration 60s \
  --iterations 3 \
  --start-delay 90s
```

Include a raised-limiter curve pass in baseline-of-record capacity plans. Keep the default-limiter curve to show out-of-box behavior, but use the paired raised-limiter curve to estimate the established-channel capacity ceiling for production-like deployments that raise or disable packet/global packet limits:

```bash
benchmark/scripts/plan-lab-baseline.sh \
  --out benchmark/build/benchmark-results/lab-baseline-plan \
  --artifact-root benchmark/build/benchmark-results/lab-baseline \
  --server-host <server-ip> \
  --interface <nic> \
  --curve-receiver receiver-a=1 \
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000
```

Run the generated `check-plan-freshness.sh` before starting workers. It fails when scheduled start timestamps have passed or are too close to now; set `MIN_LEAD_SECONDS` when the lab needs a larger setup buffer. Then run the generated host-capture script on every host, followed by the curve and contention worker scripts in the order shown in the plan README. After receiver artifacts are copied back, run `merge-all.sh`; it writes `combined/suite-aggregate.jsonl` and combined curve `bandwidth-capacity.*` selector artifacts beside it.

Start the server worker first:

```bash
start_at_ms=$((($(date +%s) + 60) * 1000))
echo "$start_at_ms"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --role server --bind-host 0.0.0.0 --port 19132 --clients 1000 --start-delay 30s --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --out $(pwd)/benchmark/build/benchmark-results/lab-server --run-id server-1000x5"
```

Start receivers on one or more receiver hosts:

```bash
start_at_ms=<same-value-used-by-server>
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="multi-client-fanout --role client --host <server-ip> --port 19132 --clients 250 --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --disappearing-clients 0 --out $(pwd)/benchmark/build/benchmark-results/lab-receiver-a --run-id receiver-a-250"
```

When using `--disappear-mode blackhole`, pass matching affected-client settings to receiver workers and server worker so reports label the same client set:

```bash
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --role server --bind-host 0.0.0.0 --port 19132 --clients 100 --start-delay 30s --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --payload-size 512 --per-client-mbps 5 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --out $(pwd)/benchmark/build/benchmark-results/lab-server --run-id server-blackhole-100x5"
./gradlew :benchmark:raknetBenchmark -PbenchmarkArgs="disappearing-clients --role client --host <server-ip> --port 19132 --clients 100 --start-at-epoch-ms $start_at_ms --warmup 10s --duration 60s --iterations 3 --disappearing-clients 10 --disappear-after 30s --disappear-mode blackhole --out $(pwd)/benchmark/build/benchmark-results/lab-receiver --run-id receiver-blackhole-100x5"
```

The Gradle `raknetBenchmark` task runs from the repository root and resolves relative `--out` paths from that root. Keep `--iterations`, `--warmup`, `--duration`, and `--start-at-epoch-ms` aligned between server and receiver workers; the merge script warns when iteration counts or coordinated start timestamps differ. Hosts should be NTP-synchronized, and the start timestamp should be far enough in the future for all receiver clients to establish before warmup begins.

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

For remote contention campaigns, generate fanout, immediate small-packet fanout, fairness, disappearance, batched, and resource-pack cases together:

```bash
benchmark/scripts/plan-remote-contention.sh \
  --out benchmark/build/benchmark-results/lab-contention-plan \
  --artifact-root benchmark/build/benchmark-results/lab-contention \
  --case remote-contention-100x5 \
  --server-host <server-ip> \
  --receiver receiver-a=100 \
  --cases fanout,immediate,fairness,disappear-blackhole,batched,resource-pack \
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

For queue/backlog cap sweeps, pass `--max-queued-bytes <bytes>` to `plan-remote-contention.sh` or the combined `plan-lab-baseline.sh`. Use explicit case prefixes or separate artifact roots for each cap value so default-cap and low-cap runs are easy to compare. The generated manifests record `configuredMaxQueuedBytes`, and validation checks that merged aggregate rows keep the planned cap.

For host-level blackhole validation, isolate the clients that should disappear onto a dedicated receiver host or network namespace, then apply the drop outside the JVM after connection establishment and warmup. A simple lab recipe is:

1. Generate a remote contention plan with a dedicated affected receiver, for example `receiver-healthy=90` and `receiver-affected=10`.
2. Start the server and both receiver scripts with a shared future `--start-at-epoch-ms`.
3. On the affected receiver host or namespace, wait until the planned disappearance point, then apply `tc netem loss 100%` or an equivalent route/firewall drop for the benchmark UDP port.
4. Capture `tc qdisc show` or firewall rule output immediately after applying the drop and again before clearing it.
5. Clear the qdisc/rule after the case, copy the affected receiver artifacts and netem evidence back, then merge and validate normally.

Use benchmark-managed `--disappear-mode blackhole` for repeatable local smoke and for labeling affected clients in remote runs. Treat host-level blackhole evidence as stronger only when the drop is applied by `tc`, routing, firewall, or the lab network outside the benchmark JVM.

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

For remote-worker lab runs, use the impairment planner instead of the local suite wrapper:

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
  --contention-receiver receiver-a=250 \
  --contention-receiver receiver-b=250 \
  --raised-packet-limit 100000 \
  --raised-global-packet-limit 1000000
```

The generated campaign contains one `plan-lab-baseline.sh` output per impairment profile plus `check-plan-freshness.sh`, `netem/<profile>-apply.sh`, `netem/<profile>-status.sh`, and `netem/<profile>-clear.sh`. Run the campaign freshness check before starting profile workers, then run the netem scripts on the shaped receiver host or namespace before and after the matching profile plan. Each script writes timestamped command output under `<profile artifact root>/netem/`; copy that directory back with the profile artifacts so the final baseline records the actual qdisc state. The generated `validate-all.sh` requires `<profile>-status-*.txt` evidence by default; use `REQUIRE_NETEM_EVIDENCE=false` only for non-baseline smoke validation. After profile merge and validation, `summarize-campaign.sh` writes `campaign-summary/impairment-summary.json`, `impairment-summary.jsonl`, and `impairment-summary.md` with per-profile validation status, capacity selections, contention rows, and netem evidence counts. The summary and promotion steps reject profile validation bypass flags and contention rows missing required retry-pressure fields by default; `--allow-validation-bypasses` and `--allow-missing-retry-pressure-fields` are only for non-baseline smoke packages. Promote the passing campaign summary before treating it as a baseline of record:

```bash
benchmark/scripts/promote-lab-impairment.sh \
  --input benchmark/build/benchmark-results/lab-impairment-baseline/campaign-summary \
  --out benchmark/build/benchmark-baselines \
  --name lab-impairment-<date>-<topology>
```

Compare candidate adverse-network campaigns against the saved baseline campaign summary:

```bash
benchmark/scripts/compare-lab-impairment.sh \
  --baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \
  --candidate benchmark/build/benchmark-results/lab-impairment-candidate/campaign-summary \
  --out benchmark/build/benchmark-results/lab-impairment-comparison.md
```

The impairment comparator rejects failed summaries, summaries generated without required netem evidence, summaries that allowed profile validation bypass flags, and summaries that allowed missing retry-pressure fields by default. Use `--allow-validation-bypasses` and `--allow-missing-retry-pressure-fields` only for smoke comparisons that must inspect incomplete campaign output without treating it as baseline evidence.

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
- `suite-aggregate.jsonl` has one row per case/scenario and records median throughput, per-client delivered Mbps percentiles, send/deliver ratios, undelivered server send-work rates, healthy/affected datagram rates, datagram/NACK/stale rates, median p99 probe RTT, packet-limit settings, spread, retry-pressure totals, and unstable flags
- `bandwidth-capacity.jsonl` has one row per bandwidth curve group and selects the highest stable delivered Gbps, with `bandwidth-capacity.md` kept beside it for review
- at least three measured iterations exist for baseline-of-record runs
- topology metadata and host captures prove at least two distinct hostnames for baseline-of-record remote runs
- repeated runs under the same topology have non-zero delivered throughput and delivered throughput/p99 probe RTT spread within the configured stability threshold
- no unexpected disconnects occur in best-case and fanout scenarios
- blackhole or stop-reading scenarios show retry-pressure indicators such as stale datagrams, queue growth, NACKs, blackholed datagram counters, affected datagram/send-work rates, or rising send/deliver ratios
- healthy-client throughput, per-client delivered Mbps percentiles, send/deliver ratios, and p99 latency are reviewed separately from affected-client metrics
- generated plans keep the planned contention client count and per-client offered Mbps, healthy-client Jain fairness at or above `0.95`, healthy-client send/deliver byte ratio at or below `1.2`, and affected-client send/deliver byte ratio at or below `5` unless the topology notes justify different validation gates

Run the validator before promoting a lab run to the saved baseline:

```bash
benchmark/scripts/validate-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline
```

The lab planner's generated `merge-all.sh` runs the same validation automatically after it creates the combined aggregate, passing the curve and contention manifests so missing planned cases fail validation. Validation fails by default when `topology.md` is missing, fewer than two host reports were captured under the artifact root, those reports do not contain at least two distinct hostnames, fewer than two prereq reports were captured, any prereq report is not ready, prereq reports do not contain at least two distinct hostnames, or strict prereq evidence for clock sync, expected MTU, minimum CPU count, and no pre-existing netem qdisc is missing.

The top-level handoff manifest and every generated curve/contention manifest row record the planned reliability mode. `check-lab-handoff.sh` treats missing or mismatched reliability as a preflight failure so `RELIABLE_ORDERED`, `RELIABLE`, and `UNRELIABLE` campaigns cannot be compared as the same matrix shape by accident.

After validation passes, package the baseline of record. The generated handoff includes `promote-and-check.sh`, which reruns handoff preflight, runs perfect-network promotion, runs impairment promotion, and then runs the final readiness check with the exact handoff manifests and artifact roots. Prefer that helper after `perfect-plan/merge-all.sh`, `impairment-plan/validate-all.sh`, and `impairment-plan/summarize-campaign.sh` have completed. Set `BASELINE_ROOT`, `PERFECT_BASELINE_NAME`, `IMPAIRMENT_BASELINE_NAME`, `READINESS_OUT`, or `PREFLIGHT_OUT` only when the lab needs explicit package names.

The equivalent manual perfect-network promotion command is below. Promotion refuses validation bypass flags by default; `--allow-validation-bypasses` is only for non-baseline smoke packages:

```bash
benchmark/scripts/promote-lab-baseline.sh \
  --input benchmark/build/benchmark-results/lab-baseline \
  --handoff-manifest benchmark/build/benchmark-results/lab-handoff-<date>-<topology>/handoff-manifest.json \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan/curve-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan/curve-raised-plan/manifest.jsonl \
  --manifest benchmark/build/benchmark-results/lab-baseline-plan/contention-plan/manifest.jsonl \
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

The promoted baseline directory contains the comparable `suite-aggregate.jsonl`, validation reports, capacity selector artifacts, copied host/topology evidence, copied planning manifests, the copied handoff manifest, and a `baseline-manifest.json` with source paths, validation metadata, the production-evidence fingerprint, and source-audit metadata. Use that promoted directory, or the `benchmark/build/benchmark-baselines/latest` symlink, as the `--baseline` input for future candidate comparisons, and pass `--require-validation` so unvalidated candidates fail comparison.

After both the perfect-network baseline and adverse-network impairment campaign have been promoted, run the readiness check:

```bash
benchmark/scripts/check-baseline-readiness.sh \
  --lab-baseline benchmark/build/benchmark-baselines/lab-<date>-<topology> \
  --impairment-baseline benchmark/build/benchmark-baselines/lab-impairment-<date>-<topology> \
  --required-min-contention-clients 500 \
  --required-min-contention-target-client-mbps 5 \
  --out benchmark/build/benchmark-results/baseline-readiness
```

The readiness report is the final artifact-level gate for accepting the baseline package set. It requires passing lab validation without bypass markers, a copied handoff manifest with matching production-evidence and source-audit metadata, no missing retry-pressure-field bypass markers in the impairment package, separate-host evidence, required scenario families, selected capacity groups, immediate small-packet fanout at payload `256` and `1Mbps` per client, retry-pressure send-work fields in comparable aggregate rows, passing impairment profile validation without bypass allowances, required netem status evidence, and the expected impairment profiles.
The recommended baseline handoff uses `500` contention clients at `5Mbps` per client, so keep those explicit readiness gates when checking the production comparison baseline. Use lower readiness overrides only for smoke campaigns that will not become the baseline of record.

Keep local smoke results out of external line-rate claims. Use them only to catch regressions in runner behavior and output shape.
