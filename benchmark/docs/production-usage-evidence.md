# Production Usage Evidence

This document records the source evidence behind the established-channel benchmark shape. It is intentionally focused on connected RakNet traffic, not unconnected ping/pong, OCR1/OCR2, cookie, or DDoS-offload paths.

Public source links are included where available. A private CubeCraft checkout was also inspected to validate the production shape; exact private paths and source snippets are intentionally omitted from this public benchmark document.

## Summary

The benchmark is a good transport-level synthetic for established RakNet behavior when it is used for:

- `RELIABLE_ORDERED` payloads on ordering channel `0`
- one ordering channel
- server-to-client bulk load with a separate latency probe stream
- payload sizes around small gameplay packets, `512B` threshold-adjacent messages, near-MTU batches, and split-heavy payloads
- batch-like burst cadence at `10ms`, `20ms`, and `50ms`
- fanout or grouped-fanout pressure across `100+` established clients
- slow, stopped, closed, or blackholed clients that create retry pressure

It is not a full Bedrock production emulator yet. Compression thresholds and algorithms are not modeled, logical packet size distributions are synthetic, and true host/NIC-level impairment still needs `tc` or lab routing rules.

## Evidence And Benchmark Implications

| Evidence | Source | Benchmark implication |
| --- | --- | --- |
| Raw `RakMessage(ByteBuf)` uses `RELIABLE_ORDERED`, normal priority, channel `0`. | [RakMessage.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/packet/RakMessage.java#L32) | Default benchmark reliability should remain `RELIABLE_ORDERED` on channel `0`; `RELIABLE` and `UNRELIABLE` are secondary spot checks. |
| Outbound `ByteBuf` traffic is wrapped into `RakMessage`. | [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L203) | Application-layer Bedrock frames usually reach RakNet as `ByteBuf`; benchmark use of normal `RakMessage`/`ByteBuf` APIs is representative at the transport boundary. |
| Ordered messages carry the Rak ordering channel into encapsulated packets. | [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L691), [EncapsulatedPacket.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/packet/EncapsulatedPacket.java#L75) | A single-channel ordered baseline is the right recurring default, with future multi-channel coverage only if production callers start using it. |
| RakNet session auto-flush defaults to `10ms`; ticks pack queued encapsulated packets into MTU-sized datagrams. | [DefaultRakSessionConfig.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/DefaultRakSessionConfig.java#L39), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L126), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L610) | Include fixed-cadence batch profiles and observe queue growth, datagrams out, stale datagrams, and ACK/NACK pressure under load. |
| Network exposes `RAK_MAX_QUEUED_BYTES`; default max queued bytes is `64MB`; sessions disconnect with `QUEUE_TOO_LONG` when the cap is exceeded. | [RakChannelOption.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/RakChannelOption.java#L186), [DefaultRakSessionConfig.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/config/DefaultRakSessionConfig.java#L41), [RakSessionCodec.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/handler/codec/raknet/common/RakSessionCodec.java#L431) | Fairness and disappearance cases must report max queued bytes, stale datagrams, NACKs, and disconnects. |
| Network constants define max MTU `1400` and common MTU sizes `1400`, `1200`, `576`. | [RakConstants.java](../../transport-raknet/src/main/java/org/cloudburstmc/netty/channel/raknet/RakConstants.java#L29) | Baseline payload sweep should include small payloads, `512B`, `1200B`, `1340B`, `1400B`, and split-heavy payloads. |
| Geyser sends Bedrock payloads per session through Protocol/Bedrock peer queues rather than choosing RakNet reliability at each call site. | [GeyserSession.java](https://github.com/GeyserMC/Geyser/blob/4b27e6f/core/src/main/java/org/geysermc/geyser/session/GeyserSession.java#L2038), [UpstreamSession.java](https://github.com/GeyserMC/Geyser/blob/4b27e6f/core/src/main/java/org/geysermc/geyser/session/UpstreamSession.java#L48), [BedrockSession.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/BedrockSession.java#L47) | The benchmark should focus on transport behavior after payloads reach RakNet, not application reliability selection. |
| Geyser/Protocol queues Bedrock packets and flushes queued packets every `50ms`; Geyser defaults MTU to `1400` and configures Rak max MTU. | [BedrockPeer.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/BedrockPeer.java#L84), [BedrockPeer.java](https://github.com/CloudburstMC/Protocol/blob/f8295d3/bedrock-connection/src/main/java/org/cloudburstmc/protocol/bedrock/BedrockPeer.java#L249), [GeyserConfig.java](https://github.com/GeyserMC/Geyser/blob/4b27e6f/core/src/main/java/org/geysermc/geyser/configuration/GeyserConfig.java#L399), [GeyserServer.java](https://github.com/GeyserMC/Geyser/blob/4b27e6f/core/src/main/java/org/geysermc/geyser/network/netty/GeyserServer.java#L224) | Include `50ms` batch cadence and near-MTU payloads in the recurring matrix. |
| Public Nukkit configures one Rak ordering channel and writes final Bedrock frames as `ByteBuf`. | [RakNetInterface.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/RakNetInterface.java#L71-L77), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L240-L269) | One ordered channel and transport-default reliability remain the main baseline. |
| Public Nukkit runs a per-session `20ms` network tick, queues outbound packets, batches them, length-prefixes batch contents, and compresses with none/zlib/snappy options. | [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L60-L64), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L140-L146), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L162-L201), [RakNetPlayerSession.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/session/RakNetPlayerSession.java#L219-L235), [CompressionProvider.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/network/CompressionProvider.java#L10-L74) | Include `20ms` batched-game-traffic profiles; add future compression modeling instead of treating current batch payload sizes as compressed output. |
| Public Nukkit has high-volume chunk and resource-pack flows: queued chunk sends, chunk request fanout, chunk-local broadcast, and request/response resource-pack chunks. | [Player.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/Player.java#L1148-L1189), [Player.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/Player.java#L1283-L1357), [Level.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/level/Level.java#L3071-L3135), [Level.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/level/Level.java#L909-L918), [Player.java](https://github.com/CloudburstMC/Nukkit/blob/master/src/main/java/cn/nukkit/Player.java#L3172-L3192) | Keep bursty fanout and split-heavy payload cases; add captured distribution and chunk/resource-pack-specific profiles later. |
| WaterdogPE and PowerNukkitX show the same broad shape with backlog caps and pacing variations. | [WaterdogPE RakNetInterface.java](https://github.com/WaterdogPE/WaterdogPE/blob/master/src/main/java/dev/waterdog/waterdogpe/network/RakNetInterface.java#L63-L70), [WaterdogPE ProxiedBedrockPeer.java](https://github.com/WaterdogPE/WaterdogPE/blob/master/src/main/java/dev/waterdog/waterdogpe/network/connection/peer/ProxiedBedrockPeer.java#L103-L113), [WaterdogPE PacketQueueHandler.java](https://github.com/WaterdogPE/WaterdogPE/blob/master/src/main/java/dev/waterdog/waterdogpe/network/connection/codec/server/PacketQueueHandler.java#L18-L24), [PowerNukkitX BedrockSession.java](https://github.com/PowerNukkitX/PowerNukkitX/blob/master/src/main/java/cn/nukkit/network/connection/BedrockSession.java#L792-L902) | Validate backlog/slow-client behavior with stop-reading and blackhole modes; later add paced high-volume profile. |

## Private CubeCraft Validation

The private CubeCraft repository was accessible and inspected for this benchmark plan. To avoid committing private source paths into this repository, the exact locations are not listed here.

The private source supported these benchmark assumptions:

- Bedrock capacity is role-specific, including roles in the tens to hundreds of players; this supports recurring `100+` and `500+` client fanout tests.
- RakNet is configured with one ordering channel.
- RakNet auto-flush is `10ms`.
- Downstream client batch flushing includes a `50ms` cadence.
- Batch framing preserves whole Bedrock batches through the proxy path.
- Compression settings include threshold `1` with zlib in the inspected path; no production evidence was found for thresholds `256` or `512`.
- Backlog protection disconnects slow clients after queue limits are exceeded.
- No production constant equivalent to `5Mbps` per client was found; the `5Mbps` benchmark target is a stress target, not a copied production configuration.

## Matrix Consequences

Keep the recurring matrix centered on these cases:

- Best-case one-client bandwidth curve with payloads `64`, `512`, `1200`, `1340`, `1400`, and split-heavy payloads.
- `20ms` and `50ms` batched-game-traffic profiles, with `10ms` included because RakNet flush ticks and CubeCraft validation both make that cadence relevant.
- Multi-client fanout at `100`, `500`, and `1000` clients, with per-client targets such as `1Mbps`, `5Mbps`, and `10Mbps`.
- Mixed-network fairness with externally applied latency/loss and healthy-client metrics separated from affected-client metrics.
- Disappearing-client runs using `close`, `stop-reading`, and benchmark-managed `blackhole`.

Keep these as known gaps:

- Compression modeling for zlib/snappy and threshold-specific behavior.
- Captured Bedrock logical-packet size distributions.
- Proxy pass-through with one downstream and one upstream RakNet session per logical user.
- Host/NIC-level blackhole rules that drop packets outside the JVM.
- Real lab line-rate runs on separate hosts; loopback results are only development baselines.
