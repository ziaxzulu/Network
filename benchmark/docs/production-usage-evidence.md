# Production Usage Evidence

This document records the source evidence behind the established-channel benchmark shape. It is intentionally focused on connected RakNet traffic, not unconnected ping/pong, OCR1/OCR2, cookie, or DDoS-offload paths.

Public source links are included where available. A private CubeCraft checkout was also inspected to validate the production shape; exact private paths and source snippets are intentionally omitted from this public benchmark document.

Evidence was refreshed on 2026-06-22 against shallow public checkouts at Geyser `0d65b201f26c1ecdf676df26463081119a55d291`, Cloudburst Protocol `f8295d3258fcb4e5c707d852dac981e964b336aa`, and Cloudburst Nukkit `dbbb7ca6fe7e097ba25a451f9a280a9f7b251471`. The local private CubeCraft checkout was also accessible and rechecked at revision `c2ea06b92c19` for the same transport-shape assumptions, but it remains private evidence rather than a public source citation.

To refresh the local source availability and revision audit before preparing a lab handoff, run:

```bash
benchmark/scripts/capture-production-evidence.sh \
  --out benchmark/build/benchmark-results/production-evidence-current \
  --require-sources geyser,cloudburst-protocol,cloudburst-nukkit,cubecraft
```

Use `GEYSER_REPO`, `CLOUDBURST_PROTOCOL_REPO`, `CLOUDBURST_NUKKIT_REPO`, and `CUBECRAFT_REPO` when the checkouts are not in the usual local worktree locations. The audit writes `source-audit.json` and `source-audit.md`; private checkout paths are omitted unless `--include-paths` is passed for a local-only handoff artifact.
When TeamZiax companion evidence is part of the lab campaign, set `TEAMZIAX_EBPF_REPO` or pass `--teamziax-ebpf`, and include `teamziax-ebpf` in `--require-sources` only for runs where missing VM/eBPF replay evidence should fail the handoff.

## Summary

The benchmark is a good transport-level synthetic for established RakNet behavior when it is used for:

- `RELIABLE_ORDERED` payloads on ordering channel `0`
- one ordering channel
- server-to-client bulk load with a separate latency probe stream
- payload sizes around small gameplay packets, `256B`/`512B` threshold-adjacent messages, near-MTU batches, and split-heavy payloads
- immediate small-packet fanout outside periodic batch/resource-pack pacing
- batch-like burst cadence at `10ms`, `20ms`, and `50ms`
- fanout or grouped-fanout pressure across `100+` established clients
- slow, stopped, closed, or blackholed clients that create retry pressure

It is not a full Bedrock production emulator yet. Compression thresholds and algorithms are not modeled, logical packet size distributions are synthetic, and true host/NIC-level validation still needs `tc`, lab routing rules, or remote workers.

## Synthetic Fitness Verdict

The current benchmark is fit for the first optimization baseline if the baseline claim is scoped correctly:

- Good synthetic for Network transport behavior after Bedrock or proxy code has produced outbound `ByteBuf` payloads.
- Good synthetic for comparing code changes that affect RakNet send loops, queueing, ACK/NACK handling, split packets, datagram packing, retry pressure, and peer servicing fairness.
- Good synthetic for detecting regressions where aggregate throughput looks healthy but poor links consume extra send work or healthy clients stop receiving their intended share.
- Not sufficient for claims about Bedrock application behavior, compression CPU cost, exact gameplay packet distributions, proxy pass-through versus re-encode behavior, or real NIC line rate until lab workers and host-level impairment are used.

Use the local `smoke` and `pilot` profiles as development regression fixtures. Use the generated remote lab handoff as the baseline of record. Local loopback can reveal overload knees and artifact-shape regressions, but it must not be used as proof that the library can sustain a given Gbps rate on commodity network hardware.

The base synthetic therefore needs two lanes:

| Lane | What it proves | Why it matches production | What remains outside the claim |
| --- | --- | --- | --- |
| Best-case bandwidth curve | Maximum stable established-channel payload throughput and the overload knee for one client/server pair. | Production sends eventually become `RakMessage(ByteBuf)` traffic on one ordered Rak channel, with MTU and split-packet behavior owned by Network. | Real NIC line rate, compression cost, and exact application packet mix. |
| Multi-client contention | Whether many established clients keep receiving fair service while server send work, queues, stale datagrams, ACK/NACK, and disconnects stay bounded. | Production has hundreds of connected clients, batch/fanout/resource-pack traffic, and slow-client backlog protection. | Exact role topology, proxy upstream/downstream pairing, and host-level packet loss unless remote workers plus `tc` are used. |
| Mixed-network fairness | Whether impaired clients degrade healthy clients or burn disproportionate server send work. | Real deployments include clients on poor Wi-Fi, distant routes, jitter, and packet loss. | Congestion-control correctness is observed but not changed by this phase. |
| Disappearing clients | Whether closed, stalled, or blackholed clients create retry storms or queue growth that harms healthy clients. | Production clients vanish or become unreachable while the server still has data queued. | Kernel/NIC blackhole fidelity unless the lab applies packet drops outside the JVM. |

## Evidence And Benchmark Implications

| Evidence | Source | Benchmark implication |
| --- | --- | --- |
| Raw `RakMessage(ByteBuf)` uses `RELIABLE_ORDERED`, normal priority, channel `0`. | [RakMessage.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/packet/RakMessage.java#L32) | Default benchmark reliability should remain `RELIABLE_ORDERED` on channel `0`; `RELIABLE` and `UNRELIABLE` are secondary spot checks. |
| Outbound `ByteBuf` traffic is wrapped into `RakMessage`. | [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L203) | Application-layer Bedrock frames usually reach RakNet as `ByteBuf`; benchmark use of normal `RakMessage`/`ByteBuf` APIs is representative at the transport boundary. |
| Ordered messages carry the Rak ordering channel into encapsulated packets. | [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L691), [EncapsulatedPacket.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/packet/EncapsulatedPacket.java#L75) | A single-channel ordered baseline is the right recurring default, with future multi-channel coverage only if production callers start using it. |
| RakNet session auto-flush defaults to `10ms`; ticks pack queued encapsulated packets into MTU-sized datagrams. | [DefaultRakSessionConfig.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/DefaultRakSessionConfig.java#L39), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L126), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L610) | Include fixed-cadence batch profiles and observe queue growth, datagrams out, stale datagrams, and ACK/NACK pressure under load. |
| Network exposes `RAK_MAX_QUEUED_BYTES`; default max queued bytes is `64MB`; sessions disconnect with `QUEUE_TOO_LONG` when the cap is exceeded. | [RakChannelOption.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/RakChannelOption.java#L186), [DefaultRakSessionConfig.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/DefaultRakSessionConfig.java#L41), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L431) | Fairness and disappearance cases must report configured queue cap, max queued bytes, stale datagrams, NACKs, and disconnects; cap sweeps should use `--max-queued-bytes`. |
| Network constants define max MTU `1400` and common MTU sizes `1400`, `1200`, `576`. | [RakConstants.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/RakConstants.java#L29) | Baseline payload sweep should include small payloads, `256B`/`512B`, `1200B`, `1340B`, `1400B`, and split-heavy payloads. |
| Geyser sends Bedrock payloads per session through Protocol/Bedrock peer queues rather than choosing RakNet reliability at each call site. | [GeyserSession.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/session/GeyserSession.java#L2038-L2048), [UpstreamSession.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/session/UpstreamSession.java#L48-L57), [BedrockSession.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3258fcb4e5c707d852dac981e964b336aa/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/BedrockSession.java#L47-L53) | The benchmark should focus on transport behavior after payloads reach RakNet, not application reliability selection. |
| Geyser/Protocol queues Bedrock packets and flushes queued packets every `50ms`; Geyser defaults MTU to `1400` and configures Rak max MTU. | [BedrockPeer.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3258fcb4e5c707d852dac981e964b336aa/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/BedrockPeer.java#L84-L95), [BedrockPeer.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3258fcb4e5c707d852dac981e964b336aa/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/BedrockPeer.java#L249-L251), [GeyserConfig.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/configuration/GeyserConfig.java#L399-L403), [GeyserServer.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/network/netty/GeyserServer.java#L224-L230) | Include `50ms` batch cadence and near-MTU payloads in the recurring matrix. |
| Protocol batches packet wrappers, compresses them, frames them, then Network wraps outbound `ByteBuf` into default RakNet messages. | [BedrockBatchEncoder.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3258fcb4e5c707d852dac981e964b336aa/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/netty/codec/batch/BedrockBatchEncoder.java#L28-L67), [CompressionCodec.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3258fcb4e5c707d852dac981e964b336aa/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/netty/codec/compression/CompressionCodec.java#L35-L52), [FrameIdCodec.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3258fcb4e5c707d852dac981e964b336aa/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/netty/codec/FrameIdCodec.java#L23-L38), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L203) | Current batch profiles model transport pressure after the application has produced a `ByteBuf`; future Bedrock-like profiles should add compression and frame/batch realism before RakNet. |
| Public Geyser configures zlib compression with threshold `512` during the upstream resource-pack/login flow. | [UpstreamPacketHandler.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/network/UpstreamPacketHandler.java#L170-L177) | Keep `512B` as a threshold-adjacent public Geyser payload point; do not treat it as evidence for private CubeCraft thresholds. |
| Geyser has immediate sends and high-volume resource-pack flows, including `256KiB` resource-pack chunks paced around `200ms`; public Nukkit has smaller resource-pack chunk responses around `8KiB`. | [UpstreamPacketHandler.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/network/UpstreamPacketHandler.java#L171-L177), [GeyserResourcePack.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/pack/GeyserResourcePack.java#L43-L47), [UpstreamPacketHandler.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/network/UpstreamPacketHandler.java#L97), [UpstreamPacketHandler.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/network/UpstreamPacketHandler.java#L343-L414), [Player.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/Player.java#L3148-L3192) | Include immediate small-packet fanout plus paced `resource-pack-transfer` rows for `8KiB` and `256KiB` chunks around `200ms`. |
| Public Geyser chunk sends use the normal queued upstream path, with Java chunk-batch feedback around `20` desired chunks per tick. | [JavaLevelChunkWithLightTranslator.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaLevelChunkWithLightTranslator.java#L377-L384), [JavaChunkBatchFinishedTranslator.java](https://github.com/GeyserMC/Geyser/blob/0d65b201f26c1ecdf676df26463081119a55d291/core/src/main/java/org/geysermc/geyser/translator/protocol/java/level/JavaChunkBatchFinishedTranslator.java#L39-L42) | Chunk pressure belongs in queued batch/fanout profiles and future captured-distribution workloads; it should not be modeled only as immediate resource-pack traffic. |
| Public Nukkit configures one Rak ordering channel and writes final Bedrock frames as `ByteBuf`. | [RakNetInterface.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/RakNetInterface.java#L71-L77), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L240-L269) | One ordered channel and transport-default reliability remain the main baseline. |
| Public Nukkit runs a per-session `20ms` network tick, queues outbound packets, batches them, length-prefixes batch contents, and compresses with none/zlib/snappy options. | [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L60-L64), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L140-L146), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L162-L201), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L219-L235), [CompressionProvider.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/network/CompressionProvider.java#L10-L74) | Include `20ms` batched-game-traffic profiles; add future compression modeling instead of treating current batch payload sizes as compressed output. |
| Public Nukkit exposes a default compression threshold of `256`. | [Server.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/Server.java#L2880-L2881), [ZlibThreadLocal.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/utils/ZlibThreadLocal.java#L88-L92) | Keep `256B` as a public Nukkit threshold-adjacent point; threshold-specific behavior still needs a compression-aware workload. |
| Public Nukkit has high-volume chunk and resource-pack flows: queued chunk sends, chunk request fanout, chunk-local broadcast, and request/response resource-pack chunks. | [Player.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/Player.java#L1148-L1189), [Player.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/Player.java#L1283-L1357), [Level.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/level/Level.java#L3071-L3135), [Level.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/level/Level.java#L909-L918), [Player.java](https://github.com/CloudburstMC/Nukkit/blob/dbbb7ca6fe7e097ba25a451f9a280a9f7b251471/src/main/java/cn/nukkit/Player.java#L3172-L3192) | Keep bursty fanout, split-heavy payload, and paced resource-pack cases; add captured distribution profiles later. |
| WaterdogPE and PowerNukkitX show the same broad shape with backlog caps and pacing variations. | [WaterdogPE RakNetInterface.java](https://github.com/WaterdogPE/WaterdogPE/blob/master/src/main/java/dev/waterdog/waterdogpe/network/RakNetInterface.java#L63-L70), [WaterdogPE ProxiedBedrockPeer.java](https://github.com/WaterdogPE/WaterdogPE/blob/master/src/main/java/dev/waterdog/waterdogpe/network/connection/peer/ProxiedBedrockPeer.java#L103-L113), [WaterdogPE PacketQueueHandler.java](https://github.com/WaterdogPE/WaterdogPE/blob/master/src/main/java/dev/waterdog/waterdogpe/network/connection/codec/server/PacketQueueHandler.java#L18-L24), [PowerNukkitX BedrockSession.java](https://github.com/PowerNukkitX/PowerNukkitX/blob/master/src/main/java/cn/nukkit/network/connection/BedrockSession.java#L792-L902) | Validate backlog/slow-client behavior with stop-reading and blackhole modes; later add paced high-volume profile. |

## Private CubeCraft Validation

The private CubeCraft repository was accessible and inspected for this benchmark plan. To avoid committing private source paths into this repository, the exact locations are not listed here.

The private source supported these benchmark assumptions:

- Bedrock capacity is role-specific, with inspected role caps roughly in the tens to low hundreds of connected players. This supports recurring `100+` per-role fanout tests; `500+` client tests should be treated as aggregate/proxy fanout rather than a single game-role cap.
- RakNet is configured with one ordering channel.
- RakNet auto-flush is `10ms`.
- The inspected proxy path disables Rak packet and global packet limits; keep default-limiter curves for out-of-box library behavior and raised-limiter curves for production-like capacity ceilings.
- Downstream client batch flushing includes a `50ms` cadence.
- Batch framing preserves whole Bedrock batches through the proxy path.
- Compression settings include threshold `1` with zlib in the inspected private path; public Nukkit and Geyser separately support `256` and `512` threshold-adjacent coverage.
- Production proxy paths can pass through unmodified compressed batches or re-encode modified batches, so future compression-aware workloads should distinguish pass-through from re-encode behavior.
- Backlog protection disconnects slow clients after queue limits are exceeded.
- Production metrics expose active Rak channels, bytes/datagrams, ACK/NACK, stale datagrams, disconnect reasons, connection state, and per-session datagram percentiles; benchmark result rows should keep these indicators and derived undelivered send-work rates in the baseline.
- No production constant equivalent to `5Mbps` per client was found; the `5Mbps` benchmark target is a stress target, not a copied production configuration.

## Matrix Consequences

Keep the recurring matrix centered on these cases:

- Best-case one-client bandwidth curve with payloads `64`, `256`, `512`, `1200`, `1340`, `1400`, and split-heavy payloads.
- Immediate small-packet fanout outside periodic batch/resource-pack pacing.
- `20ms` and `50ms` batched-game-traffic profiles, with `10ms` included because RakNet flush ticks and CubeCraft validation both make that cadence relevant.
- Paced resource-pack transfer profiles for `8KiB` and `256KiB` chunks around `200ms`.
- Multi-client fanout at `100`, `500`, and `1000` clients, with per-client targets such as `1Mbps`, `5Mbps`, and `10Mbps`.
- Mixed-network fairness with benchmark-managed latency/loss for repeatable local runs, plus host/NIC-level impairment for lab validation.
- Disappearing-client runs using `close`, `stop-reading`, and benchmark-managed `blackhole`.

Keep these as known gaps:

- Compression modeling for zlib/snappy/no-compression, threshold-specific behavior, and pass-through versus re-encode behavior.
- Captured Bedrock logical-packet size distributions.
- Production-derived queue/backlog cap values beyond the current configurable `--max-queued-bytes` sweep primitive.
- Proxy pass-through with one downstream and one upstream RakNet session per logical user.
- Host/NIC-level impairment and blackhole rules that shape or drop packets outside the JVM.
- Real lab line-rate runs on separate hosts; loopback results are only development baselines.
