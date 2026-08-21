# TeamZiax VM/eBPF Bench Companion Evidence

This note explains how the private TeamZiax Bedrock eBPF filter VM bench relates
to the Network established-channel benchmark baseline.

The short version: use the TeamZiax VM harness as companion evidence for packet
filter and capture replay behavior, not as the primary RakNet throughput or
fairness benchmark. The Network baseline must still come from active
established `RakServerChannel` / `RakClientChannel` workers.

## Access Status

The audit was performed against the private `teamziax/bedrock-ebpf-filter`
repository. In this workspace it was available at:

```text
/home/zulu/.codex/worktrees/teamziax-bedrock-ebpf-filter
```

The inspected checkout was on `main` at commit:

```text
68f39f1 Merge pull request #11 from teamziax/codex/address-pr9-review-comments
```

If a lab operator does not have access to that private repository, skip this
companion evidence. Missing TeamZiax artifacts should not block promotion of a
valid established-channel RakNet baseline unless the specific experiment is
measuring eBPF filter behavior or filter overhead.

## What The TeamZiax Harness Measures

The TeamZiax repository contains an Aya/XDP Bedrock packet filter and a QEMU
VM test harness. The useful files are:

- `docs/TESTING.md`
- `docs/ARCHITECTURE.md`
- `scripts/vmtest-qemu.sh`
- `scripts/vmtest-guest.sh`
- `scripts/test-kernel-bench.sh`
- `scripts/bench-pcap-perf.sh`
- `scripts/test-pcap-replay.sh`
- `scripts/test-pcap-timeseries.sh`

That harness can:

- boot an isolated Fedora guest with controlled CPU and memory,
- keep privileged kernel load/attach steps inside the guest instead of on the
  host,
- run kernel/XDP load and attached-interface replay checks,
- replay `.pcap` and `.pcap.zst` captures through a guest veth pair,
- mount large capture trees read-only through 9p instead of copying them,
- collect Prometheus metrics, admin-socket snapshots, replay logs, daemon logs,
  XDP program snapshots, and accept/drop timeseries artifacts.

The packet path it validates is the Bedrock DDoS/filter path: targeted listener
classification, unconnected ping, `OpenConnectionRequest1` reply from XDP,
`OpenConnectionRequest2` cookie validation, and pass/drop behavior for connected
datagrams. That is adjacent to this benchmark project, but it is not an active
established-channel throughput/fairness workload.

## Boundary For Network Baselines

For the Network benchmark baseline:

- keep the primary run as active established RakNet server and receiver workers,
- leave the eBPF filter out of the packet path for pure best-case throughput
  unless the case is explicitly measuring filter overhead,
- treat TeamZiax replay output as companion evidence about captured packet
  classification and DDoS/offload safety,
- do not substitute PCAP replay throughput for RakNet delivered throughput,
  probe RTT, queue growth, retransmits, or fairness metrics.

The TeamZiax harness is useful after a Network lab run when you have packet
captures from the same topology. Replay those captures through the VM harness to
answer "would the filter classify this traffic correctly?" while the Network
benchmark answers "how did established RakNet sessions behave under load?".

## Suggested Artifact Layout

When companion evidence is collected, keep it beside the Network lab artifacts:

```text
benchmark/build/benchmark-results/lab-<date>-<topology>/
  perfect/
  impairment/
  companion/
    teamziax-ebpf/
      README.md
      repo-revision.txt
      vmtest-env.txt
      pcap-replay/
      timeseries/
      kernel-bench/
```

Record the companion directory in `topology.md`, including:

- TeamZiax repository URL, branch, and commit,
- capture source paths or capture IDs,
- listener IP:port set used for replay,
- `VMTEST_CPUS`, `VMTEST_MEMORY_MB`, `VMTEST_DISK_SIZE`,
- `VMTEST_PCAP_SHARE_MODE` and share paths,
- `VMTEST_REPLAY_PORTS`,
- whether a real filter config was supplied with `VMTEST_PCAP_CONFIG_SOURCE`,
- whether runtime XDP stats were enabled.

## Replay Commands

Example sampled replay:

```bash
cd /path/to/bedrock-ebpf-filter

git rev-parse --short HEAD > /path/to/network-lab/companion/teamziax-ebpf/repo-revision.txt

VMTEST_IMAGE=.vmtest/fedora-cloud-base.qcow2 \
VMTEST_CPUS=8 \
VMTEST_MEMORY_MB=16384 \
VMTEST_PCAP_SHARE_MODE=9p \
VMTEST_PCAP_SHARE_HOST_PATH=/captures \
VMTEST_PCAP_SOURCE=/captures/network-lab-<date> \
VMTEST_REPLAY_PORTS=19132 \
VMTEST_REPLAY_LIMIT_FILES=1 \
VMTEST_REPLAY_MAX_PACKETS_PER_FILE=10000 \
VMTEST_PCAP_HOST_ARTIFACT_DIR=/path/to/network-lab/companion/teamziax-ebpf/pcap-replay \
bash ./scripts/vmtest-qemu.sh run pcap
```

Example timestamped replay:

```bash
cd /path/to/bedrock-ebpf-filter

VMTEST_IMAGE=.vmtest/fedora-cloud-base.qcow2 \
VMTEST_CPUS=8 \
VMTEST_MEMORY_MB=16384 \
VMTEST_PCAP_SHARE_MODE=9p \
VMTEST_PCAP_SHARE_HOST_PATH=/captures \
VMTEST_PCAP_SOURCE=/captures/network-lab-<date> \
VMTEST_PCAP_CONFIG_SOURCE=/path/to/real-bedrock-guard.toml \
VMTEST_REPLAY_PORTS=19132 \
VMTEST_PCAP_HOST_ARTIFACT_DIR=/path/to/network-lab/companion/teamziax-ebpf/timeseries \
bash ./scripts/vmtest-qemu.sh run timeseries
```

For full production windows, leave `VMTEST_REPLAY_MAX_PACKETS_PER_FILE` unset
and raise `VMTEST_DISK_SIZE` enough for the compressed capture set.

## Evidence Checklist

Keep these TeamZiax artifacts when they exist:

- `summary.txt`
- `metrics-before.prom`
- `metrics-after.prom`
- `metrics-before.json`
- `metrics-after.json`
- `packets-delta.tsv`
- `bytes-delta.tsv`
- `accept-drop-timeseries.csv`
- `accept-drop-timeseries.svg`
- `capture-ranges.tsv`
- `replay.log`
- `daemon.log`
- `listeners.txt`
- `skipped-captures.tsv`
- generated replay config
- kernel bench log when `scripts/test-kernel-bench.sh` was run

These artifacts should be reviewed beside the Network `suite-aggregate.jsonl`,
`bandwidth-capacity.*`, `validation.json`, host captures, prereq reports, and
netem evidence. They are supporting packet-path evidence, not replacements for
RakNet benchmark results.

## Caveats

- The VM replay veth MTU defaults high because captures can contain
  offload-inflated packets from GRO/GSO/TSO/LRO. That is replay accommodation,
  not evidence of production jumbo MTU.
- `enable_runtime_stats` and `bpftool prog profile` are useful for filter
  debugging but add measurement overhead; keep that distinction in the notes.
- QEMU user networking is for SSH into the guest. The packet replay workload
  uses the guest veth path created by the bench scripts.
- The eBPF repository has its own listener limits and configured listener
  model. Make the replay listener set explicit rather than inferring it from
  Network benchmark defaults.
- PCAP replay cannot prove established-channel latency or fairness because it
  does not create live RakNet sender/receiver feedback loops.
