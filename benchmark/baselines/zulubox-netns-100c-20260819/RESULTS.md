# Zulubox Network-Namespace Resilience Baseline

## Top-line answer

The library copes well with a minority of poor client connections in the most
important shared-server sense: across two campaigns, 90 healthy clients stayed
at roughly their 5 Mbps/client target with effectively perfect fairness while 10
other clients experienced latency, jitter, loss, or a complete blackhole.

That is strong evidence of client isolation. One bad cohort did not cause
head-of-line collapse across the healthy cohort.

The weaker result is resource handling for clients whose links have become
effectively unusable. At 200 ms latency, 20 ms jitter, and 10% loss, affected
clients received almost nothing while the server continued doing 42-45 Mbps of
undelivered affected-path work and the reported queue grew to about 22 MB. A
well-behaved reliable transport is expected to isolate those clients, which this
library does, but it should also bound or shed persistently unproductive work.
The short run did not establish that second property.

Overall assessment: **good isolation and moderate-loss resilience; incomplete
evidence of bounded behavior under severe disruption**.

## What was tested

- Host: `zulubox`, Fedora Linux `7.1.8-200.fc44.x86_64`, 8 processors
- JVM: Temurin/OpenJDK `26.0.2`, 1 GiB heap per worker
- Snapshot: `3ba49e9188ff+netns-14c4123c2ace`
- Topology: server, healthy receiver, and affected receiver in separate Linux
  network namespaces connected with veth pairs
- Impairment: external `tc netem` on the server-to-affected-receiver path
- Workload: 100 established clients, 512-byte `RELIABLE_ORDERED` payloads,
  5 Mbps/client, with 90 healthy and 10 affected clients
- Timing: 5-second warmup followed by three 10-second measured iterations
- Repetition: two complete six-profile campaigns, 36 measured windows in total

These are real kernel network-path measurements, not the benchmark's in-JVM
loss simulator. They are more representative than loopback, but they do not
measure physical-switch, NIC, or Internet path behavior.

## Resilience scorecard

The expectations below are engineering judgments for a server-side reliable
UDP transport serving residential and mobile clients, not claims of a formal
industry standard.

| Property | Practical expectation | Result |
| --- | --- | --- |
| Healthy-client isolation | A bad 10% cohort should not materially reduce throughput or fairness for the other 90% | **Strong:** healthy p50 stayed at 4.975-5.056 Mbps/client, fairness never fell below 0.999936, and healthy send/deliver cost stayed near 1.02-1.03 |
| Ordinary lossy-link service | Low single-digit loss and tens of milliseconds of latency should remain usable | **Strong:** 10 ms/2 ms/2% and 50 ms/5 ms/2% delivered the target workload, although the regional profile had a repeatable transition transient |
| Poor-link degradation | 100 ms/10 ms/5% should degrade the affected clients without harming others | **Mixed:** healthy clients were unaffected, but affected p50 varied from 1.77 to 3.99 Mbps and four peers disconnected in each campaign |
| Severe-link containment | 200 ms/20 ms/10% may be unusable, but damage should remain local | **Strong isolation:** healthy service held. **Weak efficiency:** affected delivery was about 0.002-0.003 Mbps/client while affected undelivered work was 42-45 Mbps |
| Complete-disruption containment | A disappeared 10% cohort should not stall active peers | **Strong over 30 seconds:** healthy delivery held near 0.4495 Gbps and the result repeated closely |
| Resource bounds and cleanup | Retries and queues for persistently bad peers should be capped or the peers should be evicted | **Not established:** severe queues reached about 22 MB without disconnects; blackholed peers were not disconnected during the measured interval |

## Cross-campaign results

Values are shown as `campaign 1 / campaign 2`.

| Profile | Delivered Gbps | Healthy p50 Mbps/client | Affected p50 Mbps/client | Affected undelivered Mbps | Max queue MB | Disconnects |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| perfect | 0.4991 / 0.4993 | 4.991 / 4.993 | n/a | 0 / 0 | 0.030 / 0.018 | 0 / 0 |
| near-loss, 10ms/2ms/2% | 0.4983 / 0.5127 | 4.984 / 5.056 | 4.977 / 5.758 | 8.72 / 2.21 | 0.022 / 3.583 | 0 / 0 |
| regional-loss, 50ms/5ms/2% | 0.5065 / 0.5042 | 5.002 / 4.975 | 5.762 / 5.748 | 11.49 / 7.28 | 2.634 / 2.265 | 0 / 0 |
| poor, 100ms/10ms/5% | 0.4666 / 0.4801 | 4.986 / 4.980 | 1.773 / 3.990 | 23.64 / 14.05 | 12.668 / 11.132 | 4 / 4 |
| severe, 200ms/20ms/10% | 0.44831 / 0.44839 | 4.981 / 4.982 | 0.0023 / 0.0027 | 45.06 / 41.93 | 22.069 / 22.778 | 0 / 0 |
| blackhole, 100% loss after warmup | 0.4575105 / 0.4575106 | 4.995 / 4.995 | 0.798 / 0.800 | 9.60 / 9.26 | 3.218 / 3.214 | 0 / 0 |

The blackhole affected-client throughput is data delivered before the timed
blackhole, not evidence of service after 100% loss.

## Main findings

1. Healthy-client isolation is the clearest positive result. Across every
   profile, healthy clients remained at target throughput with essentially
   perfect Jain fairness.
2. Perfect-path behavior is repeatable: 0.4991 and 0.4993 Gbps delivered from a
   nominal 0.5 Gbps target, with no NACKs, stale datagrams, or disconnects.
3. Low single-digit loss is serviceable. Near-loss and regional-loss affected
   clients delivered the requested workload, albeit with retransmission cost
   and a transition artifact.
4. Poor connections degrade irregularly. Both poor runs disconnected four
   peers, but affected throughput differed materially between campaigns. This
   looks more like a threshold region than a stable operating point.
5. Severe connections do not poison healthy peers, but they are very
   inefficient. The affected send/deliver byte ratio was roughly 1,600-2,090,
   and queues reached about 22 MB while useful delivery approached zero.
6. Blackhole containment is repeatable. Healthy delivery stayed near 0.4495
   Gbps while the server recorded roughly 1,094-1,145 stale datagrams/second.
   A longer hold is required to assess timeout and reclamation behavior.

## Stability and interpretation

All aggregate rows were marked unstable by the existing `p99-spread` rule.
That does not invalidate the resilience findings: the campaign was designed to
observe transition and disruption behavior, which is not stationary by nature.
It does mean this package should not be used as a precision latency baseline or
promoted as the repository's baseline of record.

Regional-loss showed a repeatable first-window recovery transient. Both
campaigns recorded a roughly 6.3-6.7 second first-window p99 outlier and a
2.3-2.6 MB queue, followed by roughly 142-156 ms p99 values. Delivery above the
nominal target is consistent with pre-window queued work draining into the
measurement window; it is not extra steady-state capacity.

The severe aggregate p99 is also easy to misread: clients that no longer answer
do not contribute useful latency samples, so the apparently low aggregate p99
mostly reflects responding clients. Affected throughput and send-work ratios
are the meaningful severe-path signals.

## Baseline qualification

This directory is a **development resilience baseline**, not a baseline of
record. It is suitable for comparing code changes on zulubox and detecting:

- loss of healthy-client throughput or fairness;
- increased retry/send-work amplification for impaired peers;
- faster queue growth;
- new disconnect behavior;
- changes in blackhole containment.

`reference-suite-aggregate.jsonl` uses campaign 2 as the canonical comparison
fixture. Case names include the impairment profile so the comparison tooling
does not conflate the four fairness rows. Both original campaign aggregates are
retained for repeatability context.

## Evidence

- Campaign 1 raw artifacts:
  `/var/lib/raknet-netns-benchmark/runs/netns-pilot-20260818T235447Z-PmWwPY`
- Campaign 2 raw artifacts:
  `/var/lib/raknet-netns-benchmark/runs/netns-pilot-20260819T000459Z-K8p6rF`
- Compact plans, statuses, and aggregates are checked into this directory.
- `netem-evidence.sha256` records the hashes of all applied-qdisc and qdisc
  status files retained with the raw campaigns.
- Both campaigns completed all six cases and left no benchmark process or
  network namespace behind.

## Next tests that answer the product question

1. Hold severe and blackhole profiles for 10-30 minutes to determine whether
   the default 64 MiB per-session queue cap and peer timeouts actually bound
   memory/send work and reclaim dead peers.
2. Sweep smaller per-session queue caps and compare affected-peer cleanup
   against healthy throughput and fairness.
3. Separate impairment transition windows from steady-state windows so mobile
   handover/recovery and sustained poor-signal behavior have distinct results.
4. Repeat with 25% and 50% impaired cohorts to find the isolation limit.
5. Repeat on separate hosts across a physical switch or a validated,
   high-performance eBPF switch before making production capacity claims.
6. Add burst/reorder/duplicate and changing-profile scenarios to represent
   Wi-Fi contention, mobile handover, and route changes more realistically.
