# Production-pinned RakNet versus NXS NetherNet — 7 September 2026

**Completed: 258 full-matrix rows plus 48 network controls, with failed attempts
retained and both campaigns independently reconciled.**

The proposed NetherNet transport does not show a general performance advantage
over the unchanged production-pinned RakNet implementation in this development
setup. It often has lower observed single-client probe latency at modest load,
but uses more combined client/server CPU for small messages. Its advantage
changes with workload: 100 clients receiving an 8 KiB message every 200 ms use
substantially less CPU with NetherNet, whereas heavier fanout and resource
bursts expose queue pressure and failed measurements. RakNet also has unstable
tails, overload disconnects and shutdown timeouts. These results support
workload-specific follow-up; they do not establish a production capacity or
tail-latency winner.

Warden's stateless admission is a separate architectural benefit. A join can
reach a game host without a per-join control request to that host. Established
connections still live in the selected host's native transport; this mechanism
does not transfer an active session to another host.

## What was compared

| Input | Frozen revision or artifact |
| --- | --- |
| RakNet | `org.cloudburstmc.netty:netty-transport-raknet:1.1.0.CR1-20260820.174333-6` |
| Production dependency catalog | CubeCraft `production` at `db97168467838187c80d469be21d78d6b5e39113` |
| Baseline source equivalence | All 77 published Java files match upstream `508ed83c8a1fe1ce4287ad6d192c9d38bb0d2ffd` byte for byte |
| Proposed transport | `teamziax/NetworkCompatible`, `nxs-dev`, `86b396686039c7da4e80a162d280700ca15af79b` |
| Native Java | `teamziax/libdatachannel-java`, `d855d4f3e9995b7ad926e6c0d23d0095666b2570` |
| Native dependencies | libdatachannel `070e9ba5327dfac1d59ca4fab9bb991daed8faee`; libjuice `5498f67aff2092aa2bb868f74971ae7c076e5735` |
| Workload model | Benchmark helpers from `082dcc803d5ac000e2fe3e92e1342585fb377fe5`, adapted through a shared driver |

The baseline is the production-pinned artifact. No running production JVM was
available for artifact readback. Its binary SHA-256 is
`24b72dd6e01e6707b91d759f7bf08bf0d3b166e392119632ce8430d8899d9971`.
The classpath audit found all 80 RakNet classes only in that JAR; the benchmark's
optimized runtime is neither linked nor substituted. The candidate JNI library
was separately built in Release and checked as loaded through `/proc/self/maps`.
Every campaign row verifies the same source, runtime JAR and native hashes.

The benchmark establishes real native NXS admission and reliable ordered
NetherNet channels, then measures server-to-client application traffic. An
in-process signalling fixture supplies the admission answer. Warden HTTP,
Internet ICE/STUN/TURN, login and stock-client gameplay are outside the timed
experiment. Setup timing is diagnostic only.

Both sides run in a fresh combined client/server JVM on CPUs 0–3 of the same
eight-vCPU Microsoft-hypervisor Linux guest. Java 26.0.2.1, Netty 4.2.15.Final,
1 GiB Java heap and two server plus two client Netty event loops are common.
NetherNet's additional native threads and encryption costs remain included.
The CPU allocation is an affinity limit, not an exclusive reservation. Each
row has five seconds of warmup, ten seconds of measurement and up to three
seconds of measured-data drain. Three repetitions alternate transport order.
The [harness README](../comparison/README.md) defines the complete contract.
RakNet uses NIO UDP in this harness; native NetherNet retains its own UDP stack.
The 5 Mbps/client load is a synthetic stress target, not a measured production
player bitrate. Payloads represent the transport boundary: compression CPU,
captured gameplay packet mixes and a proxy's paired upstream/downstream
connections are not modeled.

## Matched clean traffic

Values below are medians of three runs. CPU means combined client/server CPU
cores. RTT is the median of the three active-window p99s, conditional on returned
probes; it includes application queueing and reliable ordering. Every row in
this table returned 100% of probes and recorded no write failures. The p99
spread across repetitions exceeds 10%, so the RTT values are observations,
not a qualified claim of repeatable tail improvement.

| Workload | Goodput Mbps, Rak / Nether | CPU cores, Rak / Nether | p99 RTT ms, Rak / Nether |
| --- | ---: | ---: | ---: |
| 1 client, 256 B, 25 Mbps | 24.99 / 25.00 | 0.25 / 1.41 | 12.22 / 8.60 |
| 1 client, 512 B, 25 Mbps | 24.98 / 25.00 | 0.28 / 0.99 | 18.07 / 8.20 |
| 1 client, 1400 B, 25 Mbps | 24.98 / 25.00 | 0.26 / 0.87 | 13.09 / 6.65 |
| 1 client, 512 B, 100 Mbps | 99.71 / 99.99 | 0.42 / 2.09 | 36.49 / 18.44 |
| 1 client, 256 KiB, 25 Mbps | 24.75 / 24.96 | 0.20 / 0.23 | 49.93 / 11.68 |
| 1 client, 256 KiB, 100 Mbps | 99.61 / 99.82 | 0.33 / 0.74 | 64.14 / 14.74 |
| 100 clients, 8 KiB every 200 ms | 32.77 / 32.77 | 2.38 / 0.62 | 30.37 / 36.51 |

At 25 Mbps, 256–1400 B single-client messages use about 3.3–5.7 times as much
combined CPU with NetherNet in these runs. This is a whole-implementation
comparison; the experiment does not isolate encryption, JNI, SCTP, scheduling
or copies as the cause. At 8 KiB per client every 200 ms, the direction reverses:
RakNet uses 2.29–2.45 cores across repetitions, NetherNet 0.57–0.74, while both
deliver exactly the requested 32.768 Mbps.

The separate 64 B / 25 Mbps row is not an error-free comparison: NetherNet
records 1,326 whole-process write failures across three runs despite a median
24.99 Mbps measured goodput. Warmup and cleanup writes are included in that
failure counter, so it must not be divided by measured message count to invent
a window error rate.

Kernel traffic cost also changes with message size. At 512 B / 100 Mbps,
loopback qdisc bytes per delivered application byte are 1.093 for RakNet and
1.287 for NetherNet. At 256 KiB / 100 Mbps they are 1.267 and 1.145. These
include both directions, probes, framing and retransmissions, and slightly
bracket the measurement window; they are not physical NIC overhead ratios.
Median sampled peak RSS for the 512 B / 100 Mbps runs is 353 MiB and 402 MiB.
For the 100-client 256 KiB resource bursts it is 1,373 MiB and 1,827 MiB,
respectively, including native memory outside the 1 GiB Java heap.

## Fanout, bursts and overload

| Workload | RakNet observation | NetherNet observation |
| --- | --- | --- |
| 20 clients × 5 Mbps | 99.95 Mbps median; range 42.77–99.95; minimum active probe return 42.7% | 99.96 Mbps median; range 98.57–99.98; median p99 1.46 s; generator shortfall flag |
| 100 clients × 5 Mbps | 445.78 Mbps median; range 173.74–481.96; unstable tails and incomplete probe return | All three runs fail at the post-warmup submission barrier; no measured goodput |
| 100 clients × 1 Mbps, 256 B | 99.94 Mbps; 2.65 cores; median p99 20.40 ms, with variable tails | 97.92 Mbps; 3.51 cores; median p99 1.36 s; offered-load/generator shortfall |
| 100 clients, batches every 50 ms | 462.60 Mbps median; range 387.21–500.48 | 173.01 Mbps median; range 165.71–178.96; 1,074,347 recorded write failures |
| 100 clients, 256 KiB every 200 ms | 1,032.85 Mbps median; 2.44 cores; median p99 3.95 s | 364.49 Mbps median; 2.60 cores; median p99 6.01 s; 10,007 recorded write failures |

The 10 ms and 20 ms batch cases also fail to produce a measurement in all three
NetherNet runs. A retained stack trace places the five-second timeout at the
submission barrier after warmup, before impairment is installed or measurement
starts. This is a load-related limit of the candidate plus this harness and
resource budget, not evidence that regional packet loss prevented admission.
All 100 clients had established beforehand. Longer warmup-barrier tolerance or
a separate load-generator host could change the outcome; neither was changed
mid-campaign.

At 256 KiB and a requested 250 Mbps on one client, RakNet delivers 249.56 Mbps
at 0.32 median cores with no recorded write failures. NetherNet delivers
240.75 Mbps at 1.52 cores and records 125 write failures. At 500 Mbps both
transports expose failure limits: RakNet's three runs are disconnected before
measurement, while NetherNet delivers about 242.01 Mbps and records 5,358
write failures. These are offered-load experiments, not independent capacity
certificates.

RakNet also times out in some 1400 B overload rows. A retained read-only thread
dump, taken after the measurement window in one timed-out run, shows its event
loop in the unchanged `FastBinaryMinHeap.insertSeries` path while the main
thread waits for shutdown. It identifies an execution location, not a complete
root-cause proof. The frozen harness writes finalized metrics after cleanup;
rows killed before that retain unknown metrics rather than substituted zeros.
The historical benchmark-driven heap and scheduler improvements remain excluded.

## Network controls and recovery

The full matrix uses 100 clients with ten impaired peers; the control uses ten
clients with two impaired peers at the same 5 Mbps per client. Matched kernel
filtering applies latency, jitter, loss and blackhole to native UDP as well as
RakNet. Each sustained-loss row below has three measured repetitions per
transport. The healthy cohort requests 40 Mbps and the affected cohort 10 Mbps.

| One-way delay / jitter / loss, both directions | Healthy Mbps, Rak / Nether | Affected Mbps, Rak / Nether | Minimum affected probe return %, Rak / Nether | Recorded write failures, Rak / Nether |
| --- | ---: | ---: | ---: | ---: |
| Near: 10 / 2 ms / 2% | 39.98 / 40.00 | 9.968 / 4.626 | 100 / 44.5 | 0 / 10,806 |
| Regional: 50 / 5 ms / 2% | 39.98 / 39.96 | 0.044 / 1.122 | 1 / 11 | 0 / 43,932 |
| Poor: 100 / 10 ms / 5% | 39.97 / 39.99 | 0.044 / 0.420 | 1 / 4 | 0 / 48,540 |
| Severe: 200 / 20 ms / 10% | 39.98 / 40.00 | 0.054 / 0.144 | 1 / 2 | 0 / 52,869 |

Both transports preserve the healthy cohort's rate in these lower-load cases;
all healthy probes return within the active window. RakNet also sustains the
near-profile affected cohort. At regional delay and beyond, neither sustains
the affected target during the ten-second window. NetherNet delivers more
affected bytes there, alongside substantial write rejections; RakNet records
no write rejections but little affected delivery progress. Zero rejected writes
does not imply complete delivery. No clients are recorded disconnected in these
sustained-loss controls.

For example, the regional RakNet aggregate p99 is 29.24 ms despite a nominal
100 ms network round trip for the affected cohort: just 1% of its probes return,
so the percentile predominantly describes healthy clients. NetherNet's regional
p99 is 5.40 seconds with minimum affected return of 11%. Comparing those two
percentiles without the cohort counts would reverse the practical interpretation.

Both transports recover from the three-second packet blackout in all three
ten-client runs, deliver all offered bytes by the end of drain and return every
probe. Healthy delivery during the blackout stays near 40 Mbps. The observed
recovery bounds show why repetition ranges matter:

| Post-restoration observation | RakNet median (min–max), ms | NetherNet median (min–max), ms |
| --- | ---: | ---: |
| End of first wholly restored sample interval with affected delivery | 194 (193–196) | 198 (197–966) |
| End of three consecutive intervals at ≥90% pre-blackout affected rate | 593 (593–595) | 598 (597–1,369) |

Sampling is nominally every 200 ms, with restoration bracketed by timestamped
qdisc commands. These are application-delivery observations, not exact ACK
recovery or proof of cleared transport queues. The medians are similar, but
one NetherNet repetition recovers materially later. At 100 clients, RakNet also
recovers in all three runs; NetherNet's three warmup failures prevent a matched
high-load blackout comparison.

Closing the two affected clients at two seconds leaves healthy delivery near
40 Mbps for both transports and causes no unexpected disconnects. NetherNet's
offered rate falls to 42.00 Mbps, as expected when that cohort stops after two
seconds; RakNet still offers about 50.00 Mbps during the window. Both deliver
about 2 Mbps averaged across the window to the deliberately closed cohort.
NetherNet records two whole-process write failures across those three runs;
RakNet records none. These are intentional-close observations, not a spontaneous
disconnect rate.

The `autoRead(false)` control does not stop application delivery in either
transport: both continue delivering approximately 10 Mbps to the affected
cohort, with all probes returned and no write failures. It therefore cannot
stand in for a stalled receiver. Native callbacks bypass that setting; the
observed RakNet channel behavior likewise does not establish a socket stall.
The kernel blackhole results above supply the equivalent packet-stall evidence.

## Warden stateless connection handoff

The [detailed review](warden-stateless-handoff-review.md) traces current source,
public discovery, historical native evidence and proposed fleet changes.

1. A game host registers its fixed UDP endpoint, DTLS fingerprint and fresh
   process incarnation, then installs and acknowledges admission keys.
2. Warden authenticates a client's HTTPS join and selects a ready host. It
   returns an SDP answer whose ICE username fragment carries an encrypted
   admission ticket, bound to that host incarnation and the client's offer.
3. The client's first STUN packet carries the ticket. The native listener
   retains it while local Java admission verifies the claims, replay state
   and STUN integrity; it creates the native peer and resumes that packet.
4. DTLS authenticates the offer-bound client certificate. SCTP/NetherNet then
   carries traffic directly between client and host. Outcomes return to
   Warden asynchronously.

This removes a synchronous per-join host lookup, allocation command or pushed
offer. It does not remove Warden's own join work, durable decision recording,
background leases, keys, local replay state or active transport state. Host
restart changes the incarnation and invalidates old tickets; existing sessions
are lost. Draining can preserve current sessions while stopping new admission.
Rerouting after failure sends new joins to another host, without moving old
connections. Idle check-ins can back off as far as 60 minutes, so remote crash
detection and command delivery must be assessed separately from local admission.

The wider contract-simplification and fleet-control documents are proposals,
not current deployed behavior. The current public discovery advertises 17
operations; a seven-operation contract requires negotiation and implementation.
Reusable fleet pools and identity-preserving consolidation do not themselves
implement active-session migration.

Two source-level concerns remain material to evaluating the design. The
candidate's event queue can drain 256 outcomes while `ProviderClient` rejects
a fresh batch over 100 after draining, potentially losing a 101–256-event
burst from reporting. Admission can still succeed, but outcome telemetry may
under-count. Also, the NXS ticket ufrag exceeds RFC 8839's 32-character sender
limit even when it fits the 256-character receiver limit
([RFC 8839 section 5.4](https://www.rfc-editor.org/rfc/rfc8839.html#section-5.4)). Existing native
evidence and public discovery do not prove stock-client compatibility. The
review includes exact source references and the distinction between observed
behavior, source inference and unimplemented proposals.

## Evidence, confidence and next use

The full matrix is 43 workloads × two transports × three repetitions (258
rows); the smaller control adds 48. Failed attempts remain in the ledger and
tables. An interrupted original runner's unrecorded attempt is archived, and
guarded resume preserves the original result hashes and transport binaries.

CPU includes both endpoints, RSS includes Java and native allocations, and
loopback qdisc bytes include both UDP directions. Transport queue depth,
protocol retransmission counters and server-only CPU are unavailable. Normal
ordered probes do not reproduce the original optimized harness's priority and
ACK-based promotion gates. Ten-second windows on one shared host cannot prove
Internet behavior, production density or a stable p99 winner.

The next decisive validation would use separate client/server hosts, longer
windows and representative production payload/rate traces, retaining the exact
production artifact and socket configuration. For Warden, measure HTTPS answer,
first STUN, DTLS, channel readiness and game login separately with stock clients,
then exercise restart, drain, key rollover and burst outcome reporting. The
current evidence does not justify replacing RakNet on a blanket performance
claim or treating stateless admission as seamless session migration.

The [validation report](raknet-nethernet-validation-20260907.md) rates this
**share with caveats**. Strict checks passed for all 306 planned rows and their
unchanged runtime provenance. There are 253 finalized measurements, including
54 with recorded errors; 47 guard/barrier failures and six attempts without a
final result remain visible. These counts describe experiment outcomes, not
production connection-success rates. All retained measurements report zero
ordering errors.

The post-drain audit found snapshot skew in 21 full rows and ten controls.
Analysis uses retained per-peer drain totals; active-window totals reconcile
exactly. The largest observed differences are 30,208 bytes and 55 probes in
the full campaign, and 25,600 bytes and eight probes in the control. Late-snapshot
drain percentiles remain advisory.

- [Full matrix: all results and qualifications](../comparison/artifacts/comparison-20260907/REPORT.md)
- [Ten-client controls: all results and qualifications](../comparison/artifacts/network-control-20260907/REPORT.md)
- [Full independent audit](../comparison/artifacts/comparison-20260907/validation.json) and [control audit](../comparison/artifacts/network-control-20260907/validation.json)
- [Reproduction instructions](../comparison/README.md) and [immutable pins](../comparison/pins.json)
- [Evidence bundle](../comparison/artifacts/transport-comparison-20260907.tar.gz) and [SHA-256 checksum](../comparison/artifacts/transport-comparison-20260907.tar.gz.sha256)

The bundle retains raw per-peer results, timelines, kernel snapshots, commands,
host and artifact provenance, failures, the interrupted attempt, reports,
figures, analysis source and per-file hashes. Runtime binaries, caches and
private test-identity keys are excluded; `prepare.py` retrieves the pinned inputs.

![Delivered throughput by payload and requested load](../comparison/artifacts/comparison-20260907/figures/throughput-curves.png)

![CPU and conditional probe latency at 25 Mbps](../comparison/artifacts/comparison-20260907/figures/cost-and-latency-25mbps.png)
