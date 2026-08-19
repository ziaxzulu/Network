# Autonomous Zulubox Resilience Runs

This launcher supports repeated candidate testing without granting general
passwordless root access. Root-owned orchestration creates network namespaces,
veth pairs, and qdiscs. Candidate Java runs as the explicitly configured `zulu`
user from a root-owned, read-only staged distribution; it never runs as root or
as the obsolete `rakbench` service account.

The passwordless launcher accepts no arguments. A small `zulu`-owned goal-mode
file selects one of six root-owned scenario definitions, while a second fixed
selection file chooses the transport recovery algorithm (`legacy` or
`bounded`). Recovery defaults to `legacy`.

| Mode | Purpose |
| --- | --- |
| `pilot` | Corrected six-profile downlink campaign plus a separate bidirectional disappearance case |
| `transition` | Ten independent bidirectional 3-second blackhole-and-recovery repetitions with fresh sessions and namespaces |
| `long-hold` | Five-minute severe downlink and permanent bidirectional disappearance cases |
| `cap-sweep` | Poor, severe, and disappearance runs at 1, 4, and 8 MiB application queue caps |
| `cohort-sweep` | Poor, severe, and disappearance runs with 25% and 50% affected cohorts |
| `all` | Run every preceding mode sequentially |

The pilot preserves the original server-to-client profile shape but sets an
explicit 10,000-packet netem limit, replacing the baseline's confounding
implicit 1,000-packet limit. It is therefore a corrected successor reference,
not a directly identical rerun. Its separate disappearance case uses a
bidirectional blackhole; a one-way blackhole does not prove how the transport
handles a vanished peer. That case measures for 40 seconds with the blackhole
applied 5 seconds into measurement, leaving enough evidence to prove a
30-second reclamation bound plus the required continuous one-second hold.

## One-time installation

Build and validate the candidate distribution as the normal user, then run the
installer once with ordinary passworded sudo:

```bash
cd /home/zulu/development/ziax/Network
./gradlew --no-daemon :benchmark:test :benchmark:installDist
sudo benchmark/scripts/install-raknet-netns-goal
sudo visudo -cf /etc/sudoers
```

The installer does not edit sudoers. It installs the reviewed scripts into
`/usr/local/libexec/raknet-netns-benchmark`, replaces the existing
`/usr/local/sbin/raknet-netns-pilot` launcher, validates the existing `zulu`
user and group, and creates `/var/lib/raknet-netns-benchmark/goal-mode` plus
`/var/lib/raknet-netns-benchmark/recovery-mode`. It does not create, modify, or
remove the old `rakbench` account if one already exists.

Keep the existing exact authorization:

```sudoers
zulu ALL=(root) NOPASSWD: /usr/local/sbin/raknet-netns-pilot ""
```

Do not grant passwordless access to the installer, scripts in this writable
checkout, `ip`, `tc`, a shell, or a launcher accepting arbitrary paths.

## Autonomous operation

For each candidate, build the distribution, select an allowlisted mode, and
invoke the fixed launcher:

```bash
cd /home/zulu/development/ziax/Network
./gradlew --no-daemon :benchmark:installDist
printf '%s\n' pilot > /var/lib/raknet-netns-benchmark/goal-mode
printf '%s\n' legacy > /var/lib/raknet-netns-benchmark/recovery-mode
sudo -n /usr/local/sbin/raknet-netns-pilot
```

For a candidate campaign using the bounded recovery implementation, change only
the recovery selection before invoking the same fixed launcher:

```bash
printf '%s\n' bounded > /var/lib/raknet-netns-benchmark/recovery-mode
sudo -n /usr/local/sbin/raknet-netns-pilot
```

Use `legacy` for every baseline campaign and `bounded` for every candidate
campaign. The launcher records the selected value in the goal manifest; each
campaign plan, case manifest, and Java timeline repeats it so analysis can
reject missing, mixed, or mislabeled results.

Results are readable by `zulu` below
`/var/lib/raknet-netns-benchmark/runs/netns-goal-<mode>-*`. Each run records the
candidate Git revision, the exact staged-jar hashes, the selected scenario
mode, and the selected recovery mode.
Worker `timeline.jsonl` files are flushed continuously at 100-250 ms cadence;
qdisc evidence includes millisecond apply bounds and one-second `tc -s`
snapshots. Transition runs also record the independently scheduled blackhole and
path-restoration epochs. This preserves pre-failure retry, queue, direct-memory,
CPU, recovery, and peer-lifecycle evidence even when a worker does not reach its
final summary.
Server workers also record aggregate and maximum event-loop pending tasks plus
non-blocking scheduling-lag probes. At most one probe is outstanding per event
loop, so a stalled loop cannot make the diagnostic build an unbounded task
backlog; unsupported executors and not-yet-completed probes are explicitly
reported as unavailable.

Every JVM receives the same resource-safety policy. The defaults abort a case
above `402653184` aggregate queued bytes (384 MiB) or `805306368` direct-memory
bytes (768 MiB). These are evidence-preservation limits, not performance gates:
the completed bounded pilot peaked at about 278 MiB queue and 285 MiB direct
memory, while the failed legacy disappearance trajectory reached 483 MiB queue
and the 1 GiB direct-memory ceiling. The direct threshold retains 256 MiB of
headroom before that observed hard OOM. An abort does not throttle or change
the offered workload; it flushes a structured `resource-safety-abort` event,
writes a partial server summary, exits non-zero, and leaves `case-status.json`
pointing at the diagnostic timeline. The campaign records the same failure kind
and never merges or accepts the case as successful.
External transition timing includes the same probe-derived warmup drain used by
the workers, so `--blackhole-after` is relative to measurement rather than the
end of warmup.

The launcher rejects concurrent runs, untrusted installed scripts, symlinked or
oversized selection controls, unsupported recovery values, and missing
candidate distributions. Each subcampaign has a 45-minute watchdog, and
current-run namespaces are removed during cleanup.

## Trust boundary

Candidate files are copied by `zulu`, made root-owned and read-only,
fingerprinted, then executed as `zulu`. Candidate dependencies receive no root
privileges, but they do have the normal filesystem access of the `zulu` account,
including its home directory; review candidate dependencies accordingly. Only
the installed, root-owned scripts perform privileged namespace and qdisc
operations. Within each result tree, campaign parents, manifests, qdisc
evidence, hashes, and logs stay root-owned; only the worker and merge output
directories are owned by `zulu` so the JVMs and unprivileged merge can write
their results.
