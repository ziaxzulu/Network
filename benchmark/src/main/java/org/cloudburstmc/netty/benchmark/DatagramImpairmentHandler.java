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

import java.util.Random;
import java.util.concurrent.TimeUnit;

final class DatagramImpairmentHandler extends ChannelDuplexHandler {
    static final String NAME = "benchmark-impairment";

    private final long latencyNanos;
    private final long jitterNanos;
    private final double lossPercent;
    private final Random random;
    private volatile boolean enabled;

    DatagramImpairmentHandler(long latencyMillis, long jitterMillis, double lossPercent, int peerId) {
        this.latencyNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, latencyMillis));
        this.jitterNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, jitterMillis));
        this.lossPercent = Math.max(0.0D, Math.min(100.0D, lossPercent));
        this.random = new Random(0xC10DBA5E00000000L ^ peerId);
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
        if (shouldDrop()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        long delayNanos = nextDelayNanos();
        if (delayNanos <= 0L) {
            super.channelRead(ctx, msg);
            return;
        }
        ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.fireChannelRead(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!this.enabled) {
            super.write(ctx, msg, promise);
            return;
        }
        if (shouldDrop()) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
            return;
        }
        long delayNanos = nextDelayNanos();
        if (delayNanos <= 0L) {
            super.write(ctx, msg, promise);
            return;
        }
        ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(msg, promise);
            } else {
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    private boolean shouldDrop() {
        if (this.lossPercent <= 0.0D) {
            return false;
        }
        if (this.lossPercent >= 100.0D) {
            return true;
        }
        return this.random.nextDouble() * 100.0D < this.lossPercent;
    }

    private long nextDelayNanos() {
        if (this.jitterNanos <= 0L) {
            return this.latencyNanos;
        }
        long jitterRange = this.jitterNanos * 2L + 1L;
        long jitterOffset = Math.floorMod(this.random.nextLong(), jitterRange) - this.jitterNanos;
        return Math.max(0L, this.latencyNanos + jitterOffset);
    }
}
