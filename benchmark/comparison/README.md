# Live RakNet / NXS NetherNet comparison

This branch preserves the frozen 2026-09-07 experiment. Revision and integration
status statements below describe that date, not current release readiness. Raw
measurements, figures, and recovery bundles are retained separately on disk;
links into ignored artifact directories require that archive. This preservation
branch does not propose changes to the transport runtime.

This standalone build compares the production-pinned RakNet JAR with the
proposed NXS NetherNet data path without changing either transport. It does not
depend on this checkout's `:transport-raknet` project or the optimized RakNet
branch. See [pins.json](pins.json) and the
[goal](../docs/raknet-nethernet-comparison-goal.md).

The [completed 2026-09-07 comparison](../docs/raknet-nethernet-comparison-20260907.md)
contains 258 full-matrix rows and 48 smaller network controls, including failed
attempts. Its linked raw reports, audits, figures and evidence bundle preserve
the measurements and limitations.

## Inputs and scope

- RakNet: immutable `1.1.0.CR1-20260820.174333-6`. All 77 published Java sources
  match upstream `508ed83c8a1fe1ce4287ad6d192c9d38bb0d2ffd` byte for byte. The
  production dependency catalog was refreshed on 2026-09-07. A running
  production process was not inspected.
- NetherNet: `teamziax/NetworkCompatible` `nxs-dev` at
  `86b396686039c7da4e80a162d280700ca15af79b`. The server uses
  `NativeProviderTransport` / `AdmittedNetherNetChildChannel`; clients use the
  module's `NetherNetClientChannel`. Both reliable and unreliable channels are
  established, with benchmark traffic using the reliable ordered channel.
- Native Java, libdatachannel, and libjuice use the candidate's immutable pins.
  The native library is built in **Release**, with system OpenSSL and loopback
  ICE enabled. The candidate's bootstrap builds a Debug test library too; the
  benchmark explicitly loads the separate Release `.so` and checks `/proc/self/maps`.
- The benchmark's `BenchmarkPayload`, `BenchmarkMath`, and `LatencyHistogram`
  are copied unchanged from `082dcc803d5ac000e2fe3e92e1342585fb377fe5`. The shared
  driver implements that model's offered-load curves, fanout, batch, resource,
  mixed-link, and disappearance cases without its optimized-runtime metrics.
  `TestSignallingProvider` comes from the candidate's native integration tests;
  its ticket IDs are randomized so separate clients do not reuse a claim.

Admission signalling is an in-process fixture used only to establish the real
native data path. This comparison does not measure Warden HTTP latency, public
STUN/TURN traversal, login, or stock-client gameplay. Setup time is recorded
for diagnostics and must not be presented as real-world connection latency.
The [Warden handoff review](../docs/warden-stateless-handoff-review.md) explains
the actual background registration, first-STUN admission, restart and routing
behavior, and the separate compatibility and connection-latency evidence needed.

`summarize.py` also uses `path_metrics.py` to derive loopback qdisc traffic and
sampled blackout recovery from the retained evidence. Qdisc byte counts include
both directions and protocol traffic; they are not physical NIC bytes. Recovery
reports the first wholly post-restore sample interval with affected delivery and
three consecutive intervals at least 90% of that run's pre-blackout rate. These
observations do not establish exact ACK recovery or cleared transport queues.
The 200 ms sampling and qdisc-command timestamp bracket limit timing precision.

## Reproduce

Linux with unprivileged user/network namespaces, `ip`, `tc`, `taskset`, Java 26,
the candidate native build prerequisites, Git, Python 3, and OpenSSL is needed.
The prepared Java/JNI dependency build also uses Java 17 and Java 21 toolchains.

From this directory:

```bash
python3 prepare.py > artifacts-prepare.log 2>&1
./run-row.sh --transport raknet --clients 2 --duration-ms 1500 --out artifacts/smoke-raknet
./run-row.sh --transport nethernet --clients 2 --duration-ms 1500 --out artifacts/smoke-nethernet
python3 campaign.py --profile full --out artifacts/comparison-YYYYMMDD \
  --iterations 3 --warmup-ms 5000 --duration-ms 10000
python3 summarize.py artifacts/comparison-YYYYMMDD
# Optional scientific figures; use a Python with Matplotlib installed:
/usr/bin/python3 plot_results.py artifacts/comparison-YYYYMMDD
# Run sequentially after the full matrix, with the same immutable binaries:
python3 campaign.py --profile network-control --out artifacts/network-control-YYYYMMDD \
  --iterations 3 --warmup-ms 5000 --duration-ms 10000
python3 summarize.py artifacts/network-control-YYYYMMDD
# Independent reconciliation of raw per-peer numerators and report medians:
python3 audit_results.py artifacts/comparison-YYYYMMDD
python3 audit_results.py artifacts/network-control-YYYYMMDD
# Package raw results, failures, analysis source, reports, figures and hashes:
python3 bundle_results.py artifacts/comparison-YYYYMMDD \
  artifacts/network-control-YYYYMMDD artifacts/transport-comparison-YYYYMMDD.tar.gz
```

`prepare.py` verifies candidate source blobs, the published RakNet source and
binary checksums, native provenance, and absence of the benchmark-improved
RakNet classes. It records all runtime JAR hashes and the Release library hash.
The campaign refuses a changed Java distribution between rows. Keep the native
build unchanged for the entire campaign.

For an interrupted run, first confirm the previous runner and its benchmark child
are no longer active. Resume with the original profile, output, duration, warmup,
iterations and case arguments, adding `--resume`. `--max-rows 48` checkpoints a
bounded number of new rows while preserving the original full plan. Resume
validates the exact plan, completion prefix, raw result hashes, Java sources,
runtime JARs and Release native hash. It retains an unrecorded attempt under
`interrupted-attempts/` before rerunning that row; recorded failures are preserved
as completed observations. An exclusive lock prevents two updated runners from
using the same campaign. `complete.json` appears only after every planned row.
`--max-seconds 900` additionally checkpoints between rows after about 15 minutes;
an already running row retains its original 150-second timeout.

The 2026-09-07 full campaign's initial runner exited with status 143 after 107
recorded rows. Its final unrecorded attempt was preserved and the same frozen
experiment resumed in bounded batches. Each resumed invocation records host
metadata and its runner hash. The transport implementation was not rebuilt.

One timeout (`curve-p1400-250mbps-raknet-3`) has a retained read-only thread dump
captured after its measurement window: the event loop was in the unmodified
`FastBinaryMinHeap.insertSeries` path while the main thread waited for shutdown.
The frozen harness writes final results after cleanup, so a shutdown timeout can
leave qdisc evidence without a finalized result/timeline. Such rows remain
unavailable measurements, not zero-throughput substitutions. The dump identifies
an observed execution location; it does not alone prove the complete root cause.

Every row runs sequentially in a fresh JVM and an isolated network namespace.
The default CPU affinity is `0-3`, with `-XX:ActiveProcessorCount=4`, a 1 GiB heap,
two server and two client Netty event-loop threads, and Netty `4.2.15.Final` for
both transports. Override `COMPARISON_CPUS` / `COMPARISON_JAVA` only for a new
campaign and record the change; the current campaign planner describes the
default setup. These CPUs are not reserved against other host workloads.

## Measurement contract

The primary workload is server-to-client reliable ordered application data.
The common driver allocates per-client byte budgets at 1 ms intervals for bulk
traffic and flushes each submitted budget. Batch traffic uses the original
mixed 128/512/1200-byte format, eight logical packets per batch, and 10/20/50 ms
cadences. Resource traffic sends one 8 KiB or 256 KiB message per 200 ms.
Batch bursts may exceed the nominal byte target by the final whole message;
actual offered throughput is always recorded.

The 5 Mbps/client target comes from the benchmark stress model, not a measured
production player bitrate. The synthetic represents outbound transport payloads;
compression CPU, captured gameplay packet distributions and paired proxy
upstream/downstream sessions are not modeled. RakNet uses `NioDatagramChannel`
here, while NetherNet retains its native UDP implementation.

Both transports use normal-priority reliable ordered probes on the same stream
as bulk data. The old optimized benchmark's high-priority probe scheduler is
not used, since NetherNet does not expose the same scheduling policy. Probe RTT
includes sender queueing, ordering, recovery, and the echo path.

The immutable RakNet runtime keeps its original transport defaults, with the
packet/global limit disabled as in the inspected production proxy configuration
and one ordering channel. The proposed admitted NetherNet path keeps its native
and Netty queue bounds. A full-queue write rejection is retained as a result;
the harness does not retry that application write or change the library.
Write failure counts cover the whole process, including warmup and cleanup;
they are not a measurement-window error rate.

Each row has warmup, a fixed measurement window, and up to three seconds for
draining measured data. Negative sequence IDs distinguish warmup traffic.
Application payload length, initial payload contents, and ordering are checked;
sequence gaps are separate from reorder/duplicate observations because rejected
writes can cause gaps. Bulk bytes are counted separately during the fixed window
and including drain. Work reaching the sender event loop after the measurement
deadline is discarded and counted as `generatorDeadlineMissedBytes`, so it does
not inflate offered throughput. This identifies generator/host saturation.

Probe p50/p95/p99 use responses received within the active window. The additional
drain p99 and probe return counts expose delayed/missing responses. Missing
latency is `null`, never zero. Inspect affected-peer probe counts when loss is
high: an aggregate percentile can predominantly describe healthy clients.

The frozen driver assembles its final post-drain counters while late receive
callbacks may still run. The analysis recomputes drain delivery ratios and
settled probe returns from the retained peer records, and exposes any additional
bytes/probes observed by the later-read global counters. The independent audit
checks active-window totals exactly and quantifies this post-drain snapshot
skew. Drain p99 is a later snapshot and remains advisory. Raw results are never
rewritten to normalize these differences.

CPU is for the **combined server and client JVM**, normalized by its recorded
CPU measurement duration. It does not estimate server-only production CPU.
RSS includes heap and native memory. Resource sampling runs every 200 ms during
the window; the process aborts above 4 GiB RSS or 384 MiB of pending generator
work. The process timeout is retained as a failed row, not omitted.

Transport queue bytes and protocol-specific retransmission counters are not
equally exposed by these unmodified artifacts and remain explicitly unavailable.
Kernel qdisc snapshots retain bytes, packets, drops, and backlog before/after
the window and impairment changes. These are loopback qdisc accounting, not
physical NIC wire-rate measurements or exact SCTP/RakNet retry counters.

## Network cases

The server binds `127.0.0.1`; affected clients bind `127.0.0.2`, and healthy
clients bind `127.0.0.3`. Kernel filters send affected UDP traffic in both
directions through `tc netem`; native traffic cannot bypass it. Unclassified
traffic uses the clean band. `netem limit 100000` is explicit for every case.

Near/regional/poor/severe profiles use one-way delay/jitter/loss of
10/2 ms/2%, 50/5 ms/2%, 100/10 ms/5%, and 200/20 ms/10%, respectively. They start
after warmup. The blackhole profile drops affected traffic from approximately
2 to 5 seconds into measurement and then restores it. Timestamped qdisc status
and the 200 ms delivery timeline are required when interpreting recovery.

The close row closes affected client channels. The `stop-reading` row applies
`setAutoRead(false)` to the client channel and observes the implementation's
behavior. It is an API behavior comparison: the NetherNet client dispatches
native receive callbacks directly, so this must not be assumed to produce the
same underlying socket stall as RakNet. Kernel blackhole is the equivalent
packet-loss comparison across both transports.
In the completed ten-client controls, both transports continue delivering to
the `autoRead(false)` cohort. Neither observed API behavior establishes a
receiver stall; use the kernel blackout for that comparison.

## Evidence classification

The full campaign has 43 workloads, two transports, and three repetitions:
258 rows, including failures. It alternates transport order between repetitions.
It sweeps seven payload sizes at 25/100/250/500 Mbps and includes 20- and
100-client fanout, batched/resource traffic, four lossy profiles, blackout and
recovery, and two disappearance behaviors.

The supplemental `network-control` profile repeats clean fanout, all five
impairment profiles, and both disappearance behaviors at ten clients (two
affected), 5 Mbps per client. Its 48 rows help isolate network behavior from the
CPU saturation observed at 100 clients; they supplement the full stress matrix.

This is a single-host development comparison. It does not satisfy the original
separate-host baseline promotion gates. Preserve throughput and p99 spread,
probe coverage, write failures, disconnects, generator saturation, and achieved
load when comparing rows. A nominal 500 Mbps test that generates less than that
cannot establish the transport's independent 500 Mbps capacity.

Artifacts under `artifacts/preflight-*` and `artifacts/smoke-*` are setup evidence.
In particular, the original `preflight-*-100` results used obsolete CPU-window
accounting; do not include them in comparisons. Only the hash-pinned campaign
directory supplies the repeated comparison results.
