# netty-transport-raknet

## Recovery observability

`RakChannelOption.RAK_METRICS` and `RakChannelOption.RAK_SERVER_METRICS`
provide compatibility-preserving callbacks for transport recovery internals.
In addition to packet, ACK/NACK, queue, and lifecycle counters, exporters can
observe:

- original, NACK-retransmitted, and timeout-retransmitted datagrams and bytes;
- one-based retransmission attempts and acknowledgement progress;
- congestion-controlled datagram bytes and retransmitted reliable datagrams in
  flight; model sessions also count tracked unreliable datagrams, whose
  retained samples are payload-free;
- congestion window, slow-start threshold, smoothed RTT, RTT variation, and
  retransmission timeout; and
- recovery-epoch start/end timing and explicit per-channel state removal on
  close.

Sessions using the experimental `RakRecoveryMode.MODEL_BASED` additionally
report filtered delivery and pacing rates, minimum RTT, recent packet-round
loss, packet-timed round, startup, and persistent no-progress state. Separate
callbacks distinguish a NACK reordering hint, a late ACK that resolves that
hint, and a hint that survives the validation window and becomes loss. These
signals are intended to explain controller decisions; they are not performance
claims.

Periodic recovery-state callbacks are limited to 10 Hz per channel; recovery
transitions and terminal state are emitted immediately, while send and ACK
events are not sampled. Prometheus exporters should turn the send type into a
bounded label and aggregate channel state server-wide or into a fixed set of
cohorts. Do not use remote addresses, RakNet GUIDs, or other peer identifiers
as labels: they create unbounded time-series cardinality.

## Experimental model-based recovery

`RakChannelOption.RAK_RECOVERY_MODE` defaults to `RakRecoveryMode.LEGACY`.
`BOUNDED` is the opt-in bounded NACK/PTO policy. `MODEL_BASED` reuses that
wire-compatible recovery policy and adds a sender-side delivery-rate/minRTT
model, BDP-derived in-flight limit, bounded token pacer, NACK reordering window,
path-step guardrails, and a persistent no-progress reset. Its experimental loss
policy keeps path-independent hard-loss evidence separate from path-scoped
delay/loss evidence, applies a 0.70 HARD response or a less severe 0.90 DELAY
response at most once while an evidence epoch is held, and uses mode-specific
rearming evidence. HARD needs two disjoint
actionable clear buckets; DELAY needs two consecutive actionable 256-packet
windows with no validated loss. PTO expiry is a progress probe rather than
congestion-loss proof; validated NACK evidence remains exact-once, while the
third backed-off no-progress probe invokes the persistent reset.
Rearming retains minRTT/path provenance and the filtered bandwidth seed while
restarting bounded bandwidth discovery; persistent no-progress instead starts
a fresh loss-evidence epoch.

The mode borrows a limited set of ideas from
[IETF BBR draft-06](https://datatracker.ietf.org/doc/html/draft-ietf-ccwg-bbr-06),
[RFC 9002](https://www.rfc-editor.org/rfc/rfc9002.html), and
[RFC 8985](https://www.rfc-editor.org/rfc/rfc8985.html). It is not a BBR, QUIC,
or RACK implementation and remains experimental. Deterministic tests establish
accounting and state-machine invariants, but real external-qdisc campaigns are
still pending. See
[`adverse-network-research.md`](../benchmark/docs/adverse-network-research.md)
for the exact borrowed mechanisms, deliberate omissions, and validation plan.

Minecraft's relevant application traffic is predominantly reliable and
ordered, so that path is the performance focus. Unreliable datagrams still
share model accounting and pacing to prevent a congestion-control bypass, but
no new unreliable-message scheduling or deadline API is implied.

Model snapshots also expose cumulative hard-loss and delay-qualified
loss-response counts. These are monotonic for the lifetime of one session and
count actual congestion-window reductions, not raw loss reports. The extended
`rakCongestionModelState` overload default-delegates to the original callback,
so existing `RakChannelMetrics` and `RakServerMetrics` implementations continue
to receive snapshots. Exporters should sum the counters only across fixed
cohorts, never attach a remote address or peer identifier, and remove retained
session state when `rakRecoveryStateClosed` arrives.

## Established channel benchmarks

The benchmark kit for established RakNet channel bandwidth, latency, fanout, fairness, and impaired-network matrix runs lives in the sibling [`benchmark`](../benchmark) module.
