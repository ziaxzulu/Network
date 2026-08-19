# Adverse-Network Transport Research

## Purpose

This note translates standards and production transport experience into design
and measurement guidance for Cloudburst Network's RakNet implementation. The
product objective is not merely to complete a benchmark. It is to deliver
useful, predictable service to residential and mobile clients while bounding
the wire work, timers, queues, and memory retained for a peer that is no longer
making progress.

The current zulubox baseline already establishes strong healthy-client
isolation. It also shows the opportunity clearly:

- at 100 ms latency, 10 ms jitter, and 5% loss, affected-client median
  throughput varied from 1.77 to 3.99 Mbps/client, four clients disconnected
  in each campaign, the affected send/deliver ratio was 1.44-2.32, and the
  maximum reported single-peer queue was 11-12.7 MiB;
- at 200 ms latency, 20 ms jitter, and 10% loss, affected-client median
  throughput was only 0.0023-0.0027 Mbps/client, the affected send/deliver
  ratio was about 1,600-2,090, and the maximum single-peer queue reached about
  22 MiB. That exact result is confounded by netem's implicit 1,000-packet
  limit, which was smaller than the path bandwidth-delay product; and
- the blackhole case produced 3,281-3,434 stale datagrams/second in its first
  measured window, but it dropped only server-to-client traffic.
  Client-to-server traffic still arrived, so this is a half-path failure, not
  a disappeared client. All ten affected peers were no longer open by the
  second window and retries had stopped; measurement resets hid the exact
  transition time in the aggregate.

The product-facing workload in scope is overwhelmingly RakNet reliable,
ordered Minecraft traffic. Reliable goodput, ordered-hole recovery, queue
growth, and retry amplification therefore drive the design and acceptance
decision. The controller still charges unreliable datagrams to aggregate
flight and pacing state so they cannot bypass congestion control, but this
work does not prioritize an unreliable-traffic benchmark or a new
deadline-aware unreliable API.

## What the standards establish

[RFC 8085, UDP Usage Guidelines](https://www.rfc-editor.org/rfc/rfc8085.html)
is the baseline obligation. A UDP application performing bulk transfer should
control its aggregate sending rate, respond promptly to congestion, and apply
congestion control to retransmissions as well as new traffic. Retransmission
timers should back off after loss. Slight timer jitter is recommended where
periodic traffic from many endpoints could synchronize.

[RFC 9002, QUIC Loss Detection and Congestion Control](https://www.rfc-editor.org/rfc/rfc9002.html)
provides a mature reference design whose most relevant properties are:

- loss detection combines packet and time thresholds and tolerates reordering;
- the probe timeout (PTO) is derived from smoothed RTT, RTT variation, timer
  granularity, and expected acknowledgement delay;
- PTO expiry is a request for evidence, not proof that every outstanding packet
  was lost; one or at most two ack-eliciting datagrams are sent as probes, and
  consecutive PTOs back off exponentially;
- retransmitted work remains congestion-controlled and contributes to bytes in
  flight; ordinary transmission cannot exceed the congestion window;
- persistent congestion is established over a duration rather than by a single
  timer expiry, then reduces the congestion window to a small floor; and
- packets should be paced, or bursts must be explicitly limited.

[RFC 8985, RACK-TLP](https://www.rfc-editor.org/rfc/rfc8985.html) is an
additional useful reference for the failure that the first bounded prototype
exposed. It uses per-segment transmit timestamps, time-based loss inference,
and a tail-loss probe to recover lost retransmissions and application-limited
tails. It permits at most one additional probe beyond the congestion window at
a time. RakNet cannot copy its TCP sequence/SACK machinery, but the important
invariant carries over: acknowledgement of newer work must not postpone the
loss deadline of an older outstanding attempt indefinitely.

[RFC 9000, QUIC Transport](https://www.rfc-editor.org/rfc/rfc9000.html)
keeps three controls distinct: congestion control bounds network load, flow
control bounds receiver memory commitments, and idle timeout bounds connection
state. Its idle handling allows multiple probes but avoids letting locally
generated traffic perpetually prove that a silent peer is alive.

[RFC 9221, QUIC DATAGRAM](https://www.rfc-editor.org/rfc/rfc9221.html)
is relevant to game traffic because its unreliable datagrams remain subject to
the connection's congestion controller and may be dropped when they cannot be
sent in time. This is not a reliable-delivery mechanism. QUIC DATAGRAM is an
appropriate comparator for RakNet `UNRELIABLE` or sequenced, deadline-sensitive
traffic, not for RakNet `RELIABLE_ORDERED`.

## Production engineering evidence

Cloudflare's first-party
[quiche congestion-recovery investigation](https://blog.cloudflare.com/quic-death-spiral-fix/)
demonstrates why recovery regimes need dedicated tests. A test injected 30%
loss for two seconds and then removed the loss. A subtle CUBIC time/state bug
left some connections cycling at minimum congestion window even after the path
recovered. It failed about 60% of repeated runs before the fix and passed 100%
afterward. Ordinary high-throughput tests did not expose the fault.

Cloudflare's
[UDP transmission study](https://blog.cloudflare.com/accelerating-udp-packet-transmission-for-quic/)
also describes the tension between syscall batching and pacing. Sending a large
batch as fast as possible saves CPU but recreates the bursts that pacing is
intended to remove. A RakNet design should preserve batching efficiency while
assigning transmission opportunities to individual packets or limiting each
batch by an explicit burst budget.

Cloudflare reports in
[New standards for a faster and more private Internet](https://blog.cloudflare.com/new-standards/)
that BBRv3 reduced loss and retransmissions in its Oxy proxy experiments and
describes experimentation before deployment. That supports evaluating a
model-based controller; it is not evidence that selecting BBR fixes
incorrect in-flight accounting, timeout behavior, or queue ownership.

As of 2026-08-19, the current IETF CCWG document is the 6 July 2026
[BBRv3 working-group draft-06](https://datatracker.ietf.org/doc/html/draft-ietf-ccwg-bbr-06).
It is an active Internet-Draft targeting Experimental status, not an RFC. It
makes the poor-link tradeoff explicit: even 1% loss over a
100 ms path limits CUBIC to about 3 Mbps, then specifies a sender-side model
using delivery rate, minimum RTT, and loss to control both pacing rate and
maximum in-flight data. That is directly relevant to the active 3.5 Mbps goal
at 5% loss: repairing RakNet's recovery and scheduler is necessary, but a
Reno-style multiplicative decrease is unlikely to meet that target. The draft
is also a roughly hundred-page state machine with transport integration
requirements, not permission to ignore loss or pin a large minimum window. A
RakNet adaptation therefore needs trustworthy delivery-rate samples,
application-limited marking, packet-timed rounds, and a real pacer; its
fairness and queue-pressure behavior then need comparison against both
loss-based traffic and the genre transports below.

## Comparable implementations

Three official implementations provide useful, but different, reference
points:

- [GameNetworkingSockets](https://github.com/ValveSoftware/GameNetworkingSockets/tree/e707b3a6b638f4de31ee3e13a64284bd9abfa98e)
  is the closest semantic comparator. It provides reliable and unreliable
  messages, an acknowledgement-vector recovery design, per-lane priority and
  bandwidth sharing, impairment simulation, and detailed statistics. Its
  [real-time status API](https://github.com/ValveSoftware/GameNetworkingSockets/blob/e707b3a6b638f4de31ee3e13a64284bd9abfa98e/include/steam/steamnetworkingtypes.h)
  exposes estimated send rate, pending reliable and unreliable bytes,
  unacknowledged reliable bytes, queue time, ping, jitter, and connection
  quality.
- [ENet](https://github.com/lsalzman/enet/tree/5a9c537fd464b3c6d3c55e1d3bd47588faf71b42) is a useful genre baseline rather
  than an external definition of best practice. Its
  [public peer state](https://github.com/lsalzman/enet/blob/5a9c537fd464b3c6d3c55e1d3bd47588faf71b42/include/enet/enet.h)
  includes bandwidth throttling, RTT and variance, loss, reliable bytes in
  flight, queue limits, and configurable timeout behavior.
- [MsQuic](https://github.com/microsoft/msquic/tree/6a6a75870e8f9f4a7ccb97a3e1bc455db630aa30) is a mature standards-based
  comparator. Its [settings](https://github.com/microsoft/msquic/blob/6a6a75870e8f9f4a7ccb97a3e1bc455db630aa30/docs/Settings.md)
  enable pacing by default and expose initial RTT/window, idle and disconnect
  timeouts, and congestion-controller selection. Its
  [statistics API](https://github.com/microsoft/msquic/blob/6a6a75870e8f9f4a7ccb97a3e1bc455db630aa30/src/inc/msquic.h)
  tracks suspected and spurious loss, congestion and persistent-congestion
  events, congestion window, RTT variance, bytes in flight, estimated
  bandwidth, and queue delay.

Those repository references are pinned to the upstream heads inspected on
2026-08-19 so later API drift cannot silently change the comparison basis.

An apples-to-apples run should map one RakNet ordering channel to one
GameNetworkingSockets reliable lane, one ENet reliable channel, and one framed
MsQuic reliable stream. Message framing must be added above the MsQuic stream so
delivery accounting uses the same application messages. QUIC encryption and
handshake costs should be reported, but CPU results must not be treated as a
pure congestion-controller comparison. QUIC DATAGRAM results belong in a
separate unreliable-traffic comparison.

## RakNet-specific design options

The existing code suggests a staged approach.

### 1. Correct the recovery accounting first

`RakSlidingWindow.getRetransmissionBandwidth()` currently returns all
unacknowledged bytes. `RakSessionCodec.sendStaleDatagrams()` can therefore
spend the whole allowance in one flush even after a resend has collapsed the
congestion window. That is a plausible mechanism for the observed storm.

Make original and retransmitted reliable datagrams share one explicit
bytes-in-flight budget. Maintain a recovery epoch so one loss episode does not
repeatedly reduce the window. Permit only a deliberately bounded probe to
exceed the window, and account for it until delivery or loss is established.

Treat a NACK and a timeout differently. A valid NACK is prompt evidence of a
gap, while a timeout is evidence that a probe is needed. Reordering tests must
ensure that either signal cannot create duplicate retransmission or repeated
window collapse.

### 2. Replace periodic bulk resend with progress-aware probes

Use an RTT/variation-derived timer with exponential backoff for consecutive
no-progress events. The QUIC limit of one or two probe datagrams per PTO is a
sound first experimental bound, not a value to copy without measurement.
Record why every retry was scheduled.

Track peer liveness separately from outbound acknowledgement progress. A peer
may still send pings while the server-to-peer path is broken. That should not
necessarily close the session, but it must stop unlimited reliable data from
being accepted and retried on the blocked direction.

### 3. Pace both new work and recovery work

A token-bucket or scheduled-send pacer can use the congestion window and
smoothed RTT to determine a rate, with an explicit small burst capacity.
Recovery packets must use the same pacer. Add bounded timer jitter across
connections so a cohort sharing the same tick and timeout does not retransmit
in lockstep.

### 4. Make queue ownership explicit

Use low/high watermarks and expose channel writability before the hard queue
cap. Track queue bytes and oldest-message age per session. Once reliable
ordered data has been accepted, it must be delivered or explicitly failed; it
cannot silently expire. Unreliable and sequenced messages can support an
application deadline and be discarded before transmission when they are no
longer useful.

Queue limits are local backpressure, not QUIC-style receiver flow control. A
wire-compatible receiver-credit mechanism would require an explicitly
negotiated protocol extension.

### 5. Change congestion algorithms only after invariants are observable

Retain the loss-based bounded controller as a direct control and keep its
initial/minimum-window and proportional-reduction choices measurable. A model
adaptation must not hide recovery regressions behind a higher window.
Cloudflare's death-spiral bug is a warning that any controller depends on the
exact meaning and timing of send, acknowledgement, idle, and recovery
callbacks.

## Selected experimental implementation

There are now two opt-in sender policies beside the default
`RakRecoveryMode.LEGACY`. `BOUNDED` retains the loss-based window while bounding
NACK and PTO recovery. `MODEL_BASED` reuses those recovery invariants and adds
an experimental delivery model and sender pacer. Applications select either
through `RakChannelOption.RAK_RECOVERY_MODE`. Neither option changes a RakNet
packet or handshake, so both remain wire-compatible with existing peers.

### Recovery foundation shared with `BOUNDED`

The model does not bypass the earlier safety work:

- reliable outstanding bytes remain distinct from physical attempts in
  flight; original sends and retransmissions share admission and accounting;
- a NACK schedules one idempotent recovery item; a flush sends at most two
  NACK retransmissions and two MTUs;
- each reliable physical attempt retains an immutable loss deadline, so an ACK
  for newer work cannot indefinitely rearm the deadline for an older ordered
  hole;
- a PTO selects at most one oldest attempt, consecutive no-progress PTOs back
  off to at most eight seconds, and sub-10% timer jitter reduces cohort
  synchronization; and
- the one-probe window exception, retransmission rollback, queue ownership,
  and terminal state reclamation remain bounded and observable.

These are recovery invariants, not evidence that the selected congestion model
is effective.

### Why a RakNet NACK needs a reordering window

A RakNet receiver emits a NACK when it observes a sequence gap. Under variable
delay, a later datagram can overtake an earlier one, so the gap proves only
that the earlier datagram has not arrived *yet*. Treating every NACK as
immediate physical loss converts ordinary jitter into spurious retransmission,
false loss samples, repeated window reduction, and avoidable reliable traffic.

`MODEL_BASED` therefore keeps the NACK as a prompt recovery hint but delays the
loss declaration. The validation window is one quarter of filtered minimum
RTT, clamped to 50-200 ms. The attempt becomes eligible no earlier than both
`NACK time + window` and `attempt send time + minRTT + window`; a late ACK
before that deadline cancels recovery and is recorded as resolved reordering.
Before a model minRTT exists, the calculation uses the smoothed RTT estimate.
Before either estimate exists, it uses a 50 ms window and a 200 ms RTT
reference for the attempt-age leg, so the initial deadline is the later of
`NACK time + 50 ms` and `attempt send time + 250 ms`.

The one-quarter-minRTT starting point is borrowed from
[RFC 8985 RACK](https://www.rfc-editor.org/rfc/rfc8985.html), which explicitly
uses a bounded reordering window to reduce spurious loss detection. RakNet does
not have TCP SACK/DSACK, so this prototype does **not** copy RACK's DSACK-driven
adaptation or claim equivalent loss inference. The 50 ms floor is a deliberate
starting guard against the observed case where a clean low-latency handshake
was followed by a much more jittery path; real campaigns must calibrate the
latency/retransmission tradeoff.

### Delivery-rate, pacing, minRTT, and BDP model

The implementation borrows the following ideas from
[BBRv3 draft-06](https://datatracker.ietf.org/doc/html/draft-ietf-ccwg-bbr-06),
but not its complete state machine:

- **Delivery-rate samples.** Each physical attempt records delivered bytes and
  delivery/send timestamps. On ACK, the sample divides newly delivered bytes
  by the larger of the ACK-elapsed and send-elapsed intervals. Samples shorter
  than minRTT are rejected. An ACK for retransmitted data is ambiguous and
  contributes delivered bytes but not a new rate sample. Application-limited
  samples cannot lower the bandwidth estimate, but may raise it.
- **Packet-timed filtering.** A packet-timed round starts when an ACK covers
  data sent after the previous round boundary. The controller keeps the
  maximum delivery-rate sample from ten recent rounds. This is a small custom
  filter, not BBRv3's two-`ProbeBW`-cycle max filter. During startup, a
  completed round must deliver at least four MTUs before it can advance the
  full-bandwidth plateau counter; tiny handshake-only rounds cannot end
  discovery.
- **minRTT and BDP.** Clean RTT samples maintain a raw minimum propagation-time
  estimate. The target window is
  `2 * estimated bandwidth * max(minRTT, captured session send quantum)`, with
  a two-MTU floor and a 4 MiB implementation ceiling. This effective RTT is
  used only for the BDP/quantization budget; path-change logic and reported
  minRTT retain the raw observation. Growth is limited by newly acknowledged
  bytes. The initial window copies the RFC 9002 formula
  `min(10*MDS, max(2*MDS, 14720))`; this does not import QUIC's wire protocol.
- **Pacing.** A per-session token bucket gates ordinary/data reliable original
  sends and retransmissions through the same budget. The terminal disconnect
  notification retains the bounded policy's one-time handoff exception
  because the channel closes immediately afterward. Before a delivery
  estimate exists, the rate derives from the initial window and a 333 ms
  assumed RTT. Startup uses a 2.77 pacing gain. The steady experiment cycles
  once over eight rounds through gains 1.25, 0.75, then 1.0. At session
  activation the controller captures the same fixed interval used by the
  scheduled send task (`RAK_FLUSH_INTERVAL` with auto-flush, otherwise the
  10 ms maintenance tick). Burst capacity is
  `max(2*MTU, min(8*MTU, pacingRate*capturedSendQuantum + MTU))`. The 0.75 drain
  gain is custom. Startup and path-transition pacing retain a progress floor
  of `2*MTU/capturedSendQuantum`, while the burst ceiling remains eight MTUs.
  BBR draft-06 specifies a 0.90 `ProbeDown` pacing gain. This is a simplified
  capacity probe, not BBRv3 `Startup`, `Drain`, or full `ProbeBW`.
- **Loss response.** After startup, per-round loss at least 20%, or above 2%
  together with smoothed RTT at least 1.25 times minRTT, caps flight at 70% of
  the smaller of the prior window and observed maximum flight. Three
  non-congestive rounds release that cap gradually. Startup does not classify
  one tiny first-flight loss as a mature round: it evaluates disjoint aggregate
  lost/total byte buckets once each bucket reaches four MTUs. A mature startup
  bucket retains the at-least-20% hard-loss response but does not use delay
  inflation until startup is complete; a non-congestive bucket is then cleared
  so neither an old clean history nor an old loss episode can dominate later
  evidence. These are experimental guardrails, not BBRv3's loss-bound or ECN
  algorithms.

RFC 9002 and the BBR draft both make pacing and in-flight volume separate
controls: a BDP-sized window sent as one burst can still build a BDP-sized
queue. Cloudflare's
[UDP transmission study](https://blog.cloudflare.com/accelerating-udp-packet-transmission-for-quic/)
likewise shows why CPU-efficient batching cannot be allowed to erase packet
pacing. The current token bucket limits a Netty flush rather than using Linux
`SO_TXTIME`; kernel offload and finer-grained pacing remain future options.

Unreliable RakNet datagrams are charged to the same model and retain
payload-free ACK/loss metadata until resolution. This closes an accounting
bypass, but reliable ordered Minecraft traffic remains the evaluation focus.

### Path-change guardrails

A lower RTT sample may immediately improve minRTT. Raising minRTT is dangerous:
a standing queue can look exactly like a new, longer propagation path, and
accepting that queued RTT would inflate both BDP and the allowed queue. The
prototype therefore applies narrower guardrails:

- an aged minRTT can refresh normally only from flight at or below two MTUs;
- an apparent upward step must meet the greater of four times the old minRTT
  and the old minRTT plus 50 ms, and needs two stable observations from
  distinct delivery progress and observation times;
- the sender then saves its useful pre-probe window and delivered boundary and
  drains to the two-MTU floor. A candidate must be an original, Karn-safe
  attempt sent after that boundary and after
  `clamp(suspectRTT, 50 ms, 500 ms)` has elapsed from an observed low-flight
  ACK. Its send-time and ACK-time flight snapshots must both be at or below two
  MTUs. Two stable candidates from distinct delivery progress and observation
  times accept the new minRTT;
- each drain/sample attempt has a
  `clamp(10*suspectRTT, 2 s, 3 s)` wall-clock deadline. Failure restores the
  smaller of the saved pre-probe window and the safe old-path model target,
  still bounded by any active loss cap, then waits
  `clamp(2*suspectRTT, 250 ms, 1 s)` before retrying. Candidate and
  low-flight states are reset between attempts, while pre-boundary ACKs remain
  ineligible across cooldown and retry; and
- at most three drain attempts suppress moderate delay-qualified loss. The
  suspicion stage itself does not suppress loss, the third failed attempt
  restores normal delay response immediately, and the at-least-20% hard-loss
  and persistent-congestion responses remain active throughout, subject to
  startup's four-MTU maturity gate.

Acceptance restores the safe pre-probe window, preserves the filtered
bandwidth seed and any finite hard-loss cap, and restarts startup plateau
discovery against the new BDP. A lower original RTT sample that is eligible
under the active provenance boundary can still improve minRTT immediately; if
it aborts an active drain, the useful window is restored before transition
state is cleared. Deadline arithmetic saturates rather than wrapping at the
monotonic-clock boundary.

This is inspired by BBR's requirement to obtain propagation-delay evidence at
low flight, but it is not BBRv3 `ProbeRTT`, connection migration, or a general
path-validation mechanism. Mobile handover and rapidly alternating routes are
explicit campaign cases, not solved claims.

### Persistent no-progress handling

RFC 9002 establishes persistent congestion from an ACK-delimited lost period
whose duration exceeds a multiple of PTO; it explicitly does not define it as
a count of PTO expiries. RakNet does not expose QUIC's packet-number spaces or
ACK evidence. This experiment therefore uses a conservative local surrogate:
after two PTO probes have backed off without any ACK progress, the next due
probe resets bandwidth state, pacing credit, and flight allowance to the
two-MTU minimum. The first subsequent ACK clears the persistent flag and
restarts model startup. This is intentionally documented as a RakNet heuristic,
not RFC 9002 persistent-congestion conformance.

### Observability and current status

Existing recovery callbacks expose send reason, attempt, ACK progress,
congestion window, physical flight, RTT, timeout, and lifecycle. Model sessions
add filtered delivery rate, pacing rate, minRTT, recent round loss, packet
round, startup, and persistent-congestion state, plus counts and delays for
NACK hints, late-ACK reordering resolutions, and validated loss. Exporters must
aggregate per-channel state into fixed cohorts rather than peer labels.

Deterministic transport tests exercise rate sampling, pacing bounds,
long-running random loss, reordering transitions, capacity step-up, idle
restart, path-step rejection/acceptance, persistent no-progress, callback
failure rollback, and buffer ownership. They validate invariants only. No
external-qdisc A/B campaign has yet established `MODEL_BASED` throughput,
fairness, amplification, queue bounds, CPU cost, handover recovery, or
disappearance behavior. Benchmark-mode/provenance integration and repeated
real campaigns are still required before any performance claim or default-mode
change.

### What was deliberately not implemented

`MODEL_BASED` is deliberately not called BBR. It does not implement the BBRv3
state machine, `Drain`, the complete `ProbeBW` phases, standard `ProbeRTT`, ACK
aggregation compensation, ECN, loss-bound undo, policer handling, or the
draft's full validation envelope. It also does not add QUIC acknowledgement
delay, flow control, packet-number spaces, migration, cryptography, or
wire-visible persistent-congestion signalling. It does not add RACK's
SACK/DSACK machinery or adaptive reordering window. Queue caps and application
backpressure remain separate from congestion control.

### Why the bounded precursor was insufficient

The first bounded prototype instead rearmed one connection-wide PTO from every
new acknowledgement and allowed a deferred timeout to occupy the NACK FIFO.
External-qdisc near-loss evidence rejected that design: later packets continued
to be acknowledged, timeout retransmissions remained zero for the run, an
early `RELIABLE_ORDERED` hole did not repair, and the affected queue grew to
about 163 MiB. The immutable attempt deadline and ephemeral timeout selection
above are the deliberately narrow correction. Deterministic tests now keep
ACKing newer datagrams before each RTO yet require the lost older
retransmission to be probed by its original deadline; a separate test proves a
deferred probe cannot block later NACK work or a new original. The correction
passed its deterministic transport tests but remains only one part of a viable
candidate.

The first external-qdisc run of that corrected timer design failed the
candidate decision, but for a separate, older transport defect. Healthy clients
remained at roughly 4.99 Mbps/client while lossy clients continued to ACK wire
data yet delivered virtually no `RELIABLE_ORDERED` application data. Affected
queues reached about 159 MiB at 10 ms/2 ms/2% and 277 MiB at
200 ms/20 ms/10%. The bidirectional-disappearance case was nevertheless well
contained: all ten affected peers disconnected and sustained zero queue and
in-flight state about 10.1 seconds after the applied blackhole.

Source and event evidence identify the ordered-delivery stall in the Java
priority scheduler rather than the PTO. Reliability and ordering indices are
assigned before an encapsulated packet enters the priority heap. The Java port
then derives each new heap weight from the last enqueue, uses the inverse of the
reference comparison, and advances a priority only inside that condition. A
periodic high-priority probe can therefore jump ahead of an older normal packet
indefinitely while later normal packets are transmitted and acknowledged; the
receiver correctly waits forever for the older ordering index, which was never
put on the wire and therefore cannot be recovered by NACK or PTO. The RakNet
[reference implementation](https://github.com/facebookarchive/RakNet/blob/1a169895a900c9fc4841c556e16514182b75faf8/Source/ReliabilityLayer.cpp#L3880-L3903)
instead derives the scheduling floor from the actual heap root and advances the
selected priority on every nonempty enqueue. That prerequisite was repaired
before `MODEL_BASED` was added, and benchmark probes were moved outside the
workload's ordered stream. New campaigns must preserve both controls before
recovery or congestion-control conclusions are drawn.

The benchmark requires the same launcher-recorded source revision and staged
distribution manifest for both sides of the A/B comparison and records
`legacy`, `bounded`, or `model_based` in the goal manifest, campaign plan, case
manifest, every server and receiver timeline record, CSV, JSON, and Markdown
output. Every
merged worker must report the same composite revision. The fail-closed analyzer
treats the explicitly selected pair of recovery modes as the only intentional
configuration difference and rejects missing, mixed, stale, partial, or
mislabeled candidate evidence. Because the temporary jar stage is removed after
execution, this is reconciliation within the root-owned launcher/evidence trust
boundary rather than post-run cryptographic attestation of the executed
classpath. The trusted launcher must still be reinstalled before external
`model_based` campaigns; until then, model runs remain development evidence.

## What not to copy blindly from QUIC

- QUIC corrects RTT using peer-reported acknowledgement delay. RakNet cannot
  assume that signal exists without a negotiated wire change.
- QUIC flow control protects stream and connection receive buffers. It does not
  replace outbound application backpressure.
- QUIC packet-number spaces, connection IDs, migration, anti-amplification,
  stateless reset, and crypto handshake behavior depend on QUIC's wire and
  security model.
- QUIC persistent congestion normally uses later acknowledgement evidence to
  delimit a lost period. A complete blackhole is bounded by backed-off probes
  and liveness timeout instead.
- QUIC streams are ordered byte streams. RakNet preserves messages and provides
  ordering channels, so head-of-line and priority behavior differ.
- QUIC's two-probe allowance assumes its own exact loss declaration and
  bytes-in-flight invariants. Applying only the number without those invariants
  would not fix a retransmission storm.

## Comparator experiment plan

Run every implementation through the same external network namespaces and
qdisc, payload size, offered rate, client count, impairment seed, warmup, and
measurement windows. Separate transition windows from steady state.

The scenario matrix should include:

1. asymmetric residential latency, jitter, rate limits, and 0.5-2% loss;
2. the current 100 ms/10 ms/5% poor profile;
3. the current 200 ms/20 ms/10% severe profile;
4. stateful burst loss, reordering, and duplication;
5. bandwidth step-down and recovery;
6. 30% loss for two seconds followed by a clean path;
7. a three-second bidirectional blackhole followed by recovery;
8. permanent bidirectional disappearance; and
9. simultaneous disappearance of a cohort large enough to expose synchronized
   timers.

Record per connection and in 1, 10, and 100 ms wire buckets:

- accepted, delivered, expired, rejected, and failed messages;
- accepted-to-delivered message age, counting a disconnected client as zero
  goodput rather than dropping it from percentiles;
- bytes in flight, congestion window, RTT, RTT variation, and timer state;
- original, retransmitted, probe, and ultimately useless wire bytes;
- queue bytes, oldest queued-message age, and writability transitions;
- retry reason, largest retry burst, and time of last retry; and
- process CPU, event-loop delay, RSS, allocation rate, and cleanup time.

Use at least 30 deterministic seeds for ordinary profiles, 100 repetitions for
transition/recovery cases, ten-minute poor/severe runs, and a 30-minute
disruption soak. Acceptance should use lower confidence bounds for useful work
and upper confidence bounds for amplification and resource use.

## Evidence-driven acceptance gates

The active candidate decision uses the following contractual gates across two
complete, independently executed external-qdisc campaigns per recovery mode.
They are engineering targets for this workload, not claims of universal
industry-standard thresholds.

| Property | Active gate |
| --- | --- |
| Healthy-client useful throughput | p50 at least 4.75 Mbps/client in every profile |
| Healthy-client isolation | Jain fairness at least 0.99 and send/deliver ratio at most 1.10 |
| Poor-link usefulness | At 100 ms latency, 10 ms jitter, and 5% loss: affected p50 at least 3.5 Mbps/client, no more than one disconnect, and send/deliver ratio at most 2.0 |
| Severe/disruption pressure | For severe loss and timed blackhole, reduce affected-path retransmitted datagrams, retransmitted bytes, and retransmitted datagrams/second by at least 90% from matching legacy evidence by T+10 seconds |
| Queue bound | Maximum observed queue at most 8 MiB per peer, with independently scaled affected-cohort, healthy-collateral, aggregate-queue, and direct-memory guards |
| Permanent disappearance | Affected open/active peers reach zero and queue/in-flight state remains reclaimed for a continuous second, beginning within 30 seconds of the applied bidirectional blackhole |

The transition/recovery, long-hold, queue-cap, and impaired-cohort campaigns
exercise additional failure regimes and must not weaken the same healthy-client
guardrails. The fail-closed analyzer definitions and evidence requirements are
documented in `resilience-result-analysis.md`.

The following stricter values remain research stretch targets to calibrate
against semantically comparable transports; they do not replace the active
candidate decision above:

| Property | Research stretch target |
| --- | --- |
| Healthy-client isolation | Healthy median goodput at least 98% of the perfect profile and Jain fairness at least 0.999 with 10%, 25%, and 50% impaired cohorts |
| Poor-link usefulness | Affected p50 at least 4 Mbps/client and p10 at least 3 Mbps/client; zero loss-induced disconnects; send/deliver ratio at most 1.35; maximum queue at most 2 MiB/session |
| Severe-link progress | Affected p50 at least 0.25 Mbps/client (about 100 times the current result); send/deliver ratio at most 8; maximum queue at most 4 MiB/session |
| Collapse recovery | In the two-second 30%-loss test, 100/100 sessions recover; no session remains at minimum window for more than three clean RTTs; at least 90% of nominal goodput returns within three seconds |
| Mobile handover | A three-second bidirectional blackhole does not close the session and 90% of nominal goodput returns within two seconds of restoration |
| Permanent disappearance | After failure detection, no more than two probe datagrams per peer per timeout, consecutive probes back off, ten vanished peers generate at most 20 probes/s and 0.25 Mbps useless egress, state closes by the configured timeout plus one scheduler interval, and no traffic is emitted afterward |
| Resource reclamation | Per-peer buffers and timers are released after close; post-cleanup retained memory returns to within 5% of the pre-failure steady state |
| Fast-path regression | Perfect-path goodput regresses by no more than 3%; near-loss and regional-loss by no more than 5% |

After comparator calibration, add a relative gate: RakNet must deliver at least
80% of the best semantically comparable implementation's impaired-client
goodput and must use no more than 1.25 times its wire amplification or bounded
queue memory. Report absolute results as well so a weak comparator cannot make
the gate trivially pass.

The implementation is complete only when tests enforce the underlying
invariants: bytes in flight remains internally consistent, window exceptions
are bounded and observable, one loss epoch causes at most one reduction,
timers back off without synchronizing, accepted reliable work reaches an
explicit terminal state, queues remain bounded, and post-timeout work is zero.
