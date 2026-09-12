# Comparison validation — 7 September 2026

## Overall assessment: share with caveats

Both the 258-row full campaign and the 48-row control have passed strict
completeness, provenance and independent arithmetic checks. This experiment
can describe these pinned implementations on the recorded development host,
but cannot certify production capacity or a stable latency advantage. No
required analysis check remains unrun; production/gameplay evidence is outside
the completed experiment's scope.

## Methodology and source review

- The unchanged production-pinned RakNet binary is isolated from the fork's
  improved runtime. Published source equivalence covers 77 Java files; the
  classpath audit finds no alternative definitions of its 80 classes.
- Candidate and native source revisions, all runtime JARs, loaded Release JNI
  library, common JVM/Netty versions and Java benchmark sources are recorded.
  The campaign verifies their hashes before every row. No transport was rebuilt
  during the retained campaigns.
- Each matched pair uses the same client count, payloads, pacing, impairment,
  warmup, duration and affinity. All planned repetitions remain in the ledger,
  including guard failures, timeouts and measurements with errors.
- Warmup failures are distinguished from missing final results and measured
  stress behavior. A five-second post-warmup barrier timeout is not labelled
  an admission failure or a response to an impairment installed later.
- The independent ten-client control is prespecified before its execution and
  retained alongside the original 100-client cases. It does not replace them.

## Issues found and their treatment

1. **High impact: misleading baseline selection.** The old benchmark branch
   contains optimized runtime ancestry. The standalone build instead uses the
   immutable production dependency, with source, binary and classpath evidence.
2. **High impact: overloaded and incomplete observations.** Some cases do not
   reach measurement; some disconnect or under-generate. They remain visible.
   Missing metrics stay unavailable. Nominal offered load is not described as
   achieved load or independent transport capacity.
3. **High impact: percentile censoring and repeatability.** P99 includes only
   returned active-window probes. Aggregate and affected-cohort return rates,
   min–max ranges and quality flags accompany it. Only one of 86 full-campaign
   transport/workload groups passes every local qualification; no matched pair
   qualifies for a stable p99 winner.
4. **Medium impact: non-atomic drain snapshots.** In 21 full-campaign rows,
   delivery continues while final counters are assembled. The largest
   difference between per-peer and later-read global counters is 30,208 bytes;
   the largest relative byte difference is 0.024% of offered data, and the
   largest probe-count difference is 55. Analysis uses retained per-peer drain
   totals and exposes skew. Active-window goodput and probe counts reconcile
   exactly. Later-snapshot drain p99 is advisory. Raw evidence is preserved.
   Ten controls also have skew, with maxima of 25,600 bytes and eight probes;
   the same reconciliation passes there.
5. **Medium impact: stop-reading does not establish a socket stall.** Native
   callbacks bypass the client channel's `autoRead` setting, and both transports
   keep delivering to the affected cohort in the actual controls. This is
   reported as an API behavior case. Kernel blackhole supplies the matched
   packet-stall experiment.

## Calculation checks

`audit_results.py` independently checks the exact ordered plan, uniqueness,
result hashes, offered/delivered bytes from peers, delivery ratios, active probe
counts, post-drain snapshot skew, CPU time normalization and raw-to-aggregate
medians. `summarize.py` additionally checks matched options, loaded code source,
native Release evidence, CPU affinity, cohort coverage, monotonic qdisc/sample
counters, and recovery timestamp bounds. Both refuse incomplete campaigns by
default. Each produces retained JSON evidence under the campaign directory.

The existing three Java evidence tests passed with zero failures, covering
missing latency, corrupted/truncated payloads and invalid batch framing. Five
Python resume tests passed, covering evidence tampering, ledger ordering,
preservation of failures and interrupted attempts. Native bootstrap provenance
records transport, callback-cleanup, logging and test checks. These checks
support harness correctness; actual load evidence comes from the campaign rows.

## Visualization review

Two full-campaign scientific figures show payload/rate goodput and 25 Mbps CPU
plus p99. The underlying chart data, input ledger hash and renderer metadata
are retained. The figures show min–max repetition ranges, distinguish errors
and unmet load, leave missing measurements absent, disclose logarithmic latency
scaling and identify the shared host. Point fills describe load qualification,
not stable p99. PNGs are visually inspected after final rendering; SVG versions
are included for export. Both final PNGs were visually inspected; the updated
throughput legend was re-rendered and inspected after its wording correction.
Control metrics appear in the report tables.

## Required caveats

Production dependency selection is verified; deployed-process readback is not.
The host is shared, CPUs are affined rather than reserved, and client and server
share one JVM. Ten-second synthetic windows do not establish separate-host
capacity or gameplay. Warden signalling latency and stock-client admission are
not timed by this harness. Queue depth, exact protocol retry counters and
server-only CPU remain unavailable. The Warden review labels source inference,
historical native evidence, public discovery and unimplemented proposals
separately.
