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
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.util.concurrent.CountDownLatch;

final class ClientReceiverHandler extends SimpleChannelInboundHandler<RakMessage> {
    private final PeerStats peer;
    private final CountDownLatch connectedLatch;
    private final RakReliability reliability;

    ClientReceiverHandler(PeerStats peer, CountDownLatch connectedLatch, RakReliability reliability) {
        this.peer = peer;
        this.connectedLatch = connectedLatch;
        this.reliability = reliability;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.connectedLatch.countDown();
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
        ByteBuf content = message.content();
        byte type = BenchmarkPayload.type(content);
        if (type == BenchmarkPayload.BULK) {
            this.peer.addBulkReceived(content.readableBytes());
            return;
        }
        if (type == BenchmarkPayload.PROBE) {
            long sequence = BenchmarkPayload.sequence(content);
            long sentNanos = BenchmarkPayload.timestampNanos(content);
            ctx.writeAndFlush(new RakMessage(
                    BenchmarkPayload.probeAck(ctx.alloc(), sequence, sentNanos),
                    this.reliability,
                    RakPriority.IMMEDIATE
            ));
            return;
        }
        ctx.fireChannelRead(message.retain());
    }
}
