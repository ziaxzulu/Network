# Resilience Result Analysis

`analyze-resilience-results.py` turns autonomous netns goal, campaign, case, or
raw server-timeline artifacts into one fail-closed JSON result and one Markdown
report. It does not execute a benchmark or require privilege.

## Usage

Analyze one or more goal or campaign roots:

```bash
benchmark/scripts/analyze-resilience-results.py \
  --root /var/lib/raknet-netns-benchmark/runs/netns-goal-all-... \
  --out benchmark/build/resilience-analysis
```

A raw `server/<run-id>/timeline.jsonl`, a case `manifest.json`, or a case root
is also accepted with `--root`; the analyzer infers the case root and requires
its `merged/lab-summary.json` and external qdisc evidence.

Compare two or more complete baseline and candidate campaigns:

```bash
benchmark/scripts/analyze-resilience-results.py \
  --baseline-root /results/baseline-goal-1 \
  --baseline-root /results/baseline-goal-2 \
  --candidate-root /results/candidate-goal-1 \
  --candidate-root /results/candidate-goal-2 \
  --out benchmark/build/resilience-comparison
```

The command writes `resilience-analysis.json` and
`resilience-analysis.md`. Exit status `0` means every applicable gate passed;
status `1` means evidence was missing/invalid or a gate failed; status `2` is a
command-line usage error.

## Active absolute gates

| Gate | Default |
| --- | ---: |
| Healthy p50 | at least 4.75 Mbps/client in every case, including transition cases |
| Healthy Jain fairness | at least 0.99 |
| Healthy send/deliver ratio | at most 1.10 |
| Poor affected p50 | at least 3.5 Mbps/client |
| Poor affected disconnects | at most 1 |
| Poor affected send/deliver ratio | at most 2.0 |
| Maximum queue for any peer | at most 8 MiB |
| Affected cohort observed queue | at most 8 MiB times the configured affected-client count |
| Healthy collateral queue | at most 1 MiB times the configured healthy-client count |
| Aggregate queue/direct memory | at most the exact campaign watchdog thresholds; defaults 384 MiB/768 MiB |
| Transition ACK progress | within 2 seconds of actual recovery application |
| Transition queue/in-flight reclamation | within 10 seconds |
| Permanent disappearance peer reclamation | affected open/active peers are zero and queue/in-flight return within 5% of pre-event for a continuous second, starting within 30 seconds of actual blackhole application |
| External qdisc application | no more than 250 ms early or 1 second late |

The healthy p50 ratio to a matching perfect case is retained as informational
context only; it is not the active acceptance gate.
Command-line threshold options may tighten these gates but reject values that
would weaken the active goal.

## Event windows and pressure definitions

Event time is the privileged qdisc evidence, not merely the planned worker
epoch. The pre-event sample is the last server sample at or before the earliest
target apply start. A normal post-event window ends at the first sample within
500 ms after T+10. Timeline samples and the one-second sustained-reclamation
proof may have no gap over 500 ms. An initial-netem window is truncated and explicitly
marked unavailable at T+10 when another external event intervenes.

The comparison never combines pressure into a weighted score. It reports and
gates these lower-is-better components separately for severe initial-netem and
scheduled external blackhole events:

- affected total retransmitted datagram and byte deltas across NACK and timeout
  recovery; and
- affected total retransmitted datagrams/second across both send types.

NACK and timeout components remain separately reported as informational
diagnostics. They are not separately gated because moving one necessary repair
from NACK classification to PTO classification is not additional pressure. A
zero legacy subtype and a small positive candidate subtype therefore cannot
override a 90% reduction in total retry work.

Bytes in flight are unacknowledged transport state, not inferred application
delivery. It remains an explicit informational T+10 value, but is not a 90%
reduction component: ACK-evidence recovery can legitimately retain bytes in
flight rather than declaring every outstanding packet lost. Server timelines
correctly label useful event-window delivery as
`unavailable-on-server-worker`. Whole-run healthy/affected send/deliver values
come from the merged server/receiver summary.

Every comparable non-zero pressure component must fall by at least 90% from
baseline to candidate. A zero baseline with zero candidate pressure is marked
not applicable; a positive candidate against a zero baseline fails. Queue
reduction is reported, but acceptance uses an 8 MiB per-peer target and scales
the affected cohort bound by the number of affected clients. This corrects the
old non-scaling 8 MiB cohort interpretation; it does not relax the per-peer
target, and a run with any peer over 8 MiB still fails. Healthy collateral has
a separate tighter 1 MiB/client bound, while aggregate queue and direct memory
are independently bounded by the provenance-recorded watchdog policy.
Recovery pressure remains visible in each event row, but recovery acceptance is
the ACK-progress, disconnect, and sustained queue/in-flight reclamation gates;
useful repair retransmission is not required to fall by 90%.

## Evidence completeness

Each case requires an executed netns manifest, exactly one raw server timeline,
a merged summary, monotonic counters, usable CPU/RSS/direct-memory samples, and
valid apply/qdisc evidence for every configured external event. Qdisc sampler
coverage is checked per unique namespace/interface target for every apply, not
as a global union. The first sample after each target completes (and before the
next apply) must prove the expected netem delay/jitter/loss/limit or cleared
state, must agree with the post-command text status evidence, and must retain
that state in subsequent samples until the next apply.
Recovery must clear to `noqueue` for a 0ms/0ms/0% base path and must replace
netem with the declared shape for a nonzero base impairment.
An impaired profile is invalid without its initial-netem event, and a
blackhole case is invalid without its external-blackhole event; omitting the
same required event from baseline and candidate cannot bypass comparison.
Every plan, manifest, server/receiver timeline record, and merged summary must
agree on both resource-safety thresholds. A structured safety abort is retained
as partial diagnostic evidence but always makes the case and campaign fail.
Shared server event-loop pending-task and scheduling-lag availability and maxima
are reported without inventing values when the runtime cannot expose them.

Comparison additionally requires at least two distinct complete campaign
execution identities on each side, not two directory copies of one execution.
All baseline campaign plans, case manifests, and timeline records must say
`legacy`; all candidate evidence must say `bounded`. The analyzer permits that
single intentional algorithm difference, then requires every other network and
workload parameter to match exactly across all four or more campaigns. Missing,
invalid, mixed, or plan/manifest/timeline-disagreeing recovery values fail the
comparison. A
complete campaign must have an executed and passing campaign
summary, exactly the profiles `perfect`, `near-loss`, `regional-loss`, `poor`,
`severe`, and `blackhole`, one completed status and result per profile, and a
matching discovered case/manifest set. Transition or separate disappearance campaigns
remain useful event evidence but do not satisfy this repetition gate.

Malformed or missing evidence is retained in the JSON/Markdown availability
section and makes the command exit non-zero. Invalid cases are excluded from
numeric comparison and listed explicitly; they cannot silently make a
candidate pass.
