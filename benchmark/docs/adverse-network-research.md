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
describes experimentation before deployment. That is evidence to evaluate
model-based controllers later; it is not evidence that selecting BBR fixes
incorrect in-flight accounting, timeout behavior, or queue ownership.

## Comparable implementations

Three official implementations provide useful, but different, reference
points:

- [GameNetworkingSockets](https://github.com/ValveSoftware/GameNetworkingSockets)
  is the closest semantic comparator. It provides reliable and unreliable
  messages, an acknowledgement-vector recovery design, per-lane priority and
  bandwidth sharing, impairment simulation, and detailed statistics. Its
  [real-time status API](https://github.com/ValveSoftware/GameNetworkingSockets/blob/master/include/steam/steamnetworkingtypes.h)
  exposes estimated send rate, pending reliable and unreliable bytes,
  unacknowledged reliable bytes, queue time, ping, jitter, and connection
  quality.
- [ENet](https://github.com/lsalzman/enet) is a useful genre baseline rather
  than an external definition of best practice. Its
  [public peer state](https://github.com/lsalzman/enet/blob/master/include/enet/enet.h)
  includes bandwidth throttling, RTT and variance, loss, reliable bytes in
  flight, queue limits, and configurable timeout behavior.
- [MsQuic](https://github.com/microsoft/msquic) is a mature standards-based
  comparator. Its [settings](https://github.com/microsoft/msquic/blob/main/docs/Settings.md)
  enable pacing by default and expose initial RTT/window, idle and disconnect
  timeouts, and congestion-controller selection. Its
  [statistics API](https://github.com/microsoft/msquic/blob/main/src/inc/msquic.h)
  tracks suspected and spurious loss, congestion and persistent-congestion
  events, congestion window, RTT variance, bytes in flight, estimated
  bandwidth, and queue delay.

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

Benchmark a two-packet versus larger initial/minimum window and proportional
window reduction instead of immediately porting CUBIC or BBR. Cloudflare's
death-spiral bug is a warning that a controller depends on the exact meaning
and timing of send, acknowledgement, idle, and recovery callbacks.

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

These are initial product gates to calibrate with the comparator run, not
claims of an industry-standard numeric threshold.

| Property | Initial gate |
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
