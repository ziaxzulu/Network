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
import org.cloudburstmc.netty.channel.raknet.RakState;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.util.concurrent.CountDownLatch;

final class ClientReceiverHandler extends SimpleChannelInboundHandler<RakMessage> {
    private final PeerStats peer;
    private final CountDownLatch connectedLatch;

    ClientReceiverHandler(PeerStats peer, CountDownLatch connectedLatch) {
        this.peer = peer;
        this.connectedLatch = connectedLatch;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.peer.state(RakState.CONNECTED);
        this.connectedLatch.countDown();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.peer.state(RakState.DISCONNECTED);
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
        ByteBuf content = message.content();
        byte type = BenchmarkPayload.type(content);
        if (type == BenchmarkPayload.BULK) {
            this.peer.addBulkReceived(content.readableBytes());
            return;
        }
        if (type == BenchmarkPayload.BATCH) {
            this.peer.addBulkReceived(content.readableBytes(), BenchmarkPayload.logicalPackets(content));
            return;
        }
        if (type == BenchmarkPayload.PROBE) {
            long sequence = BenchmarkPayload.sequence(content);
            long sentNanos = BenchmarkPayload.timestampNanos(content);
            ctx.writeAndFlush(BenchmarkMessages.probeAck(
                    BenchmarkPayload.probeAck(ctx.alloc(), sequence, sentNanos)));
            return;
        }
        ctx.fireChannelRead(message.retain());
    }
}
