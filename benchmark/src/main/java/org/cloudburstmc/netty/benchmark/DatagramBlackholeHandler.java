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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

final class DatagramBlackholeHandler extends ChannelDuplexHandler {
    static final String NAME = "benchmark-blackhole";

    private final PeerStats peer;
    private volatile boolean enabled;

    DatagramBlackholeHandler(PeerStats peer) {
        this.peer = peer;
    }

    void enable() {
        this.enabled = true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!this.enabled) {
            super.channelRead(ctx, msg);
            return;
        }
        this.peer.addBlackholedDatagramIn();
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!this.enabled) {
            super.write(ctx, msg, promise);
            return;
        }
        this.peer.addBlackholedDatagramOut();
        ReferenceCountUtil.release(msg);
        promise.setSuccess();
    }
}
