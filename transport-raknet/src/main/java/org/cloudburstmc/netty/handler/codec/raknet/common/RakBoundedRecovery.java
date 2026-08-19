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

package org.cloudburstmc.netty.handler.codec.raknet.common;

import org.cloudburstmc.netty.channel.raknet.RakSlidingWindow;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Event-loop-confined PTO timer state for bounded recovery. */
final class RakBoundedRecovery {
    static final int MAX_NACK_DATAGRAMS_PER_FLUSH = 2;
    static final long MAX_BACKED_OFF_RTO_MILLIS = 8_000L;

    private final LongSupplier clock;
    private final LongSupplier jitterSource;
    private long nextProbeAtMillis = -1L;
    private int ptoBackoff;
    private boolean closed;

    RakBoundedRecovery(LongSupplier clock) {
        this(clock, () -> ThreadLocalRandom.current().nextLong());
    }

    RakBoundedRecovery(LongSupplier clock, LongSupplier jitterSource) {
        this.clock = clock;
        this.jitterSource = jitterSource;
    }

    long currentTimeMillis() {
        return this.clock.getAsLong();
    }

    void onReliableSend(RakSlidingWindow window) {
        if (!this.closed && window.getUnackedBytes() > 0 && this.nextProbeAtMillis == -1L) {
            this.arm(window);
        }
    }

    void onNackRetransmission(RakSlidingWindow window) {
        // A NACK is useful loss information but not forward ACK progress. Do not let a stream of NACKs
        // keep moving the connection-wide PTO deadline; only arm if this is the first outstanding flight.
        if (!this.closed && this.nextProbeAtMillis == -1L) {
            this.arm(window);
        }
    }

    boolean scheduleNack(RakDatagramPacket datagram) {
        if (this.closed || datagram.isRetransmissionPending()) {
            return false;
        }
        datagram.setRetransmissionPending(true);
        return true;
    }

    void onAcknowledgementProgress(RakSlidingWindow window) {
        if (this.closed) {
            return;
        }
        this.ptoBackoff = 0;
        if (window.getUnackedBytes() == 0) {
            this.nextProbeAtMillis = -1L;
        } else {
            this.arm(window);
        }
    }

    boolean isProbeDue(RakSlidingWindow window) {
        return !this.closed && window.getUnackedBytes() > 0 && this.nextProbeAtMillis != -1L
                && this.currentTimeMillis() >= this.nextProbeAtMillis;
    }

    void onProbeSent(RakSlidingWindow window) {
        if (this.closed) {
            return;
        }
        if (this.ptoBackoff < 30) {
            this.ptoBackoff++;
        }
        this.arm(window);
    }

    void onProbeDeferred(RakSlidingWindow window) {
        if (!this.closed) {
            this.arm(window);
        }
    }

    private void arm(RakSlidingWindow window) {
        long rto = this.getEffectiveRtoMillis(window);
        long jitterRange = Math.max(1L, rto / 10L);
        long jitter = Math.floorMod(this.jitterSource.getAsLong(), jitterRange);
        this.nextProbeAtMillis = this.currentTimeMillis() + rto + jitter;
    }

    long getEffectiveRtoMillis(RakSlidingWindow window) {
        long rto = window.getRtoForRetransmission();
        for (int i = 0; i < this.ptoBackoff && rto < MAX_BACKED_OFF_RTO_MILLIS; i++) {
            rto = Math.min(MAX_BACKED_OFF_RTO_MILLIS, rto * 2L);
        }
        return rto;
    }

    long getNextProbeAtMillis() {
        return this.nextProbeAtMillis;
    }

    int getPtoBackoff() {
        return this.ptoBackoff;
    }

    void close() {
        this.closed = true;
        this.nextProbeAtMillis = -1L;
        this.ptoBackoff = 0;
    }

    static FlushBudget nackFlushBudget(int mtu) {
        return new FlushBudget(MAX_NACK_DATAGRAMS_PER_FLUSH,
                MAX_NACK_DATAGRAMS_PER_FLUSH * mtu);
    }

    static FlushBudget ptoFlushBudget(int mtu) {
        return new FlushBudget(1, mtu);
    }

    static final class FlushBudget {
        private final int maxDatagrams;
        private final int maxBytes;
        private int datagrams;
        private int bytes;

        private FlushBudget(int maxDatagrams, int maxBytes) {
            this.maxDatagrams = maxDatagrams;
            this.maxBytes = maxBytes;
        }

        boolean canConsume(int size) {
            return this.datagrams < this.maxDatagrams && this.bytes + size <= this.maxBytes;
        }

        void consume(int size) {
            if (!this.canConsume(size)) {
                throw new IllegalStateException("Recovery flush budget exceeded");
            }
            this.datagrams++;
            this.bytes += size;
        }

        int getDatagrams() {
            return this.datagrams;
        }
    }
}
