/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.benchmark;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

final class ServerProbeAckHandler extends SimpleChannelInboundHandler<RakMessage> {
    private final PeerStats peer;
    private final LatencyHistogram probeRtt;

    ServerProbeAckHandler(PeerStats peer, LatencyHistogram probeRtt) {
        this.peer = peer;
        this.probeRtt = probeRtt;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
        ByteBuf content = message.content();
        if (BenchmarkPayload.type(content) == BenchmarkPayload.PROBE_ACK) {
            long sentNanos = BenchmarkPayload.timestampNanos(content);
            this.probeRtt.record(System.nanoTime() - sentNanos);
            this.peer.addProbeAcked();
            return;
        }
        ctx.fireChannelRead(message.retain());
    }
}
