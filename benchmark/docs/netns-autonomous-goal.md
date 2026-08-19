# Autonomous Zulubox Resilience Runs

This launcher supports repeated candidate testing without granting general
passwordless root access. Root-owned orchestration creates network namespaces,
veth pairs, and qdiscs. Candidate Java runs as the locked `rakbench` service
account from a root-owned, read-only staged distribution.

The passwordless launcher accepts no arguments. A small `zulu`-owned mode file
selects one of five root-owned scenario definitions:

| Mode | Purpose |
| --- | --- |
| `pilot` | Corrected six-profile downlink campaign plus a separate bidirectional disappearance case |
| `long-hold` | Five-minute severe downlink and permanent bidirectional disappearance cases |
| `cap-sweep` | Poor, severe, and disappearance runs at 1, 4, and 8 MiB application queue caps |
| `cohort-sweep` | Poor, severe, and disappearance runs with 25% and 50% affected cohorts |
| `all` | Run every preceding mode sequentially |

The pilot preserves the original server-to-client profile shape but sets an
explicit 10,000-packet netem limit, replacing the baseline's confounding
implicit 1,000-packet limit. It is therefore a corrected successor reference,
not a directly identical rerun. Its separate disappearance case uses a
bidirectional blackhole; a one-way blackhole does not prove how the transport
handles a vanished peer.

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
`/usr/local/sbin/raknet-netns-pilot` launcher, creates the locked `rakbench`
account, and creates `/var/lib/raknet-netns-benchmark/goal-mode`.

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
sudo -n /usr/local/sbin/raknet-netns-pilot
```

Results are readable by `zulu` below
`/var/lib/raknet-netns-benchmark/runs/netns-goal-<mode>-*`. Each run records the
candidate Git revision, the exact staged-jar hashes, and the selected mode.

The launcher rejects concurrent runs, untrusted installed scripts, symlinked or
oversized mode controls, and missing candidate distributions. Each subcampaign
has a 45-minute watchdog, and current-run namespaces are removed during cleanup.

## Trust boundary

Candidate files are copied by `zulu`, made root-owned and read-only, fingerprinted,
then executed as `rakbench`. Candidate dependencies therefore receive neither
root privileges nor access to the user's home directory. Only the installed,
root-owned scripts perform privileged namespace and qdisc operations.
