# netty-transport-raknet

## Recovery observability

`RakChannelOption.RAK_METRICS` and `RakChannelOption.RAK_SERVER_METRICS`
provide compatibility-preserving callbacks for transport recovery internals.
In addition to packet, ACK/NACK, queue, and lifecycle counters, exporters can
observe:

- original, NACK-retransmitted, and timeout-retransmitted datagrams and bytes;
- one-based retransmission attempts and acknowledgement progress;
- reliable bytes and retransmitted datagrams in flight;
- congestion window, slow-start threshold, smoothed RTT, RTT variation, and
  retransmission timeout; and
- recovery-epoch start/end timing and explicit per-channel state removal on
  close.

Periodic recovery-state callbacks are limited to 10 Hz per channel; recovery
transitions and terminal state are emitted immediately, while send and ACK
events are not sampled. Prometheus exporters should turn the send type into a
bounded label and aggregate channel state server-wide or into a fixed set of
cohorts. Do not use remote addresses, RakNet GUIDs, or other peer identifiers
as labels: they create unbounded time-series cardinality.

## Established channel benchmarks

The benchmark kit for established RakNet channel bandwidth, latency, fanout, fairness, and impaired-network matrix runs lives in the sibling [`benchmark`](../benchmark) module.
