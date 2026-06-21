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

import org.cloudburstmc.netty.channel.raknet.RakState;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class PeerStats {
    private final int id;
    private final boolean impaired;
    private volatile InetSocketAddress address;

    private final LongAdder serverBytesIn = new LongAdder();
    private final LongAdder serverBytesOut = new LongAdder();
    private final LongAdder serverDatagramsIn = new LongAdder();
    private final LongAdder serverDatagramsOut = new LongAdder();
    private final LongAdder encapsulatedIn = new LongAdder();
    private final LongAdder encapsulatedOut = new LongAdder();
    private final LongAdder staleDatagrams = new LongAdder();
    private final LongAdder ackIn = new LongAdder();
    private final LongAdder ackOut = new LongAdder();
    private final LongAdder nackIn = new LongAdder();
    private final LongAdder nackOut = new LongAdder();
    private final LongAdder bulkSentMessages = new LongAdder();
    private final LongAdder bulkSentBytes = new LongAdder();
    private final LongAdder logicalPacketsSent = new LongAdder();
    private final LongAdder bulkReceivedMessages = new LongAdder();
    private final LongAdder bulkReceivedBytes = new LongAdder();
    private final LongAdder logicalPacketsReceived = new LongAdder();
    private final LongAdder probesSent = new LongAdder();
    private final LongAdder probesAcked = new LongAdder();
    private final LongAdder disconnects = new LongAdder();
    private final AtomicLong maxQueuedBytes = new AtomicLong();
    private volatile RakState lastState = RakState.UNCONNECTED;

    public PeerStats(int id, boolean impaired) {
        this.id = id;
        this.impaired = impaired;
    }

    public void resetMeasurement() {
        this.serverBytesIn.reset();
        this.serverBytesOut.reset();
        this.serverDatagramsIn.reset();
        this.serverDatagramsOut.reset();
        this.encapsulatedIn.reset();
        this.encapsulatedOut.reset();
        this.staleDatagrams.reset();
        this.ackIn.reset();
        this.ackOut.reset();
        this.nackIn.reset();
        this.nackOut.reset();
        this.bulkSentMessages.reset();
        this.bulkSentBytes.reset();
        this.logicalPacketsSent.reset();
        this.bulkReceivedMessages.reset();
        this.bulkReceivedBytes.reset();
        this.logicalPacketsReceived.reset();
        this.probesSent.reset();
        this.probesAcked.reset();
        this.maxQueuedBytes.set(0L);
    }

    public int id() {
        return this.id;
    }

    public boolean impaired() {
        return this.impaired;
    }

    public InetSocketAddress address() {
        return this.address;
    }

    public void address(InetSocketAddress address) {
        this.address = address;
    }

    public void addServerBytesIn(int count) {
        this.serverBytesIn.add(count);
    }

    public void addServerBytesOut(int count) {
        this.serverBytesOut.add(count);
    }

    public void addServerDatagramsIn(int count) {
        this.serverDatagramsIn.add(count);
    }

    public void addServerDatagramsOut(int count) {
        this.serverDatagramsOut.add(count);
    }

    public void addEncapsulatedIn(int count) {
        this.encapsulatedIn.add(count);
    }

    public void addEncapsulatedOut(int count) {
        this.encapsulatedOut.add(count);
    }

    public void addStaleDatagrams(int count) {
        this.staleDatagrams.add(count);
    }

    public void addAckIn(int count) {
        this.ackIn.add(count);
    }

    public void addAckOut(int count) {
        this.ackOut.add(count);
    }

    public void addNackIn(int count) {
        this.nackIn.add(count);
    }

    public void addNackOut(int count) {
        this.nackOut.add(count);
    }

    public void addBulkSent(int bytes) {
        addBulkSent(bytes, 1);
    }

    public void addBulkSent(int bytes, int logicalPackets) {
        this.bulkSentMessages.increment();
        this.bulkSentBytes.add(bytes);
        this.logicalPacketsSent.add(Math.max(1, logicalPackets));
    }

    public void addBulkReceived(int bytes) {
        addBulkReceived(bytes, 1);
    }

    public void addBulkReceived(int bytes, int logicalPackets) {
        this.bulkReceivedMessages.increment();
        this.bulkReceivedBytes.add(bytes);
        this.logicalPacketsReceived.add(Math.max(1, logicalPackets));
    }

    public void addProbeSent() {
        this.probesSent.increment();
    }

    public void addProbeAcked() {
        this.probesAcked.increment();
    }

    public void addDisconnect() {
        this.disconnects.increment();
    }

    public void state(RakState state) {
        this.lastState = state;
    }

    public void queuedBytes(int count) {
        long current;
        do {
            current = this.maxQueuedBytes.get();
            if (count <= current) {
                return;
            }
        } while (!this.maxQueuedBytes.compareAndSet(current, count));
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.id,
                this.impaired,
                this.address,
                this.serverBytesIn.sum(),
                this.serverBytesOut.sum(),
                this.serverDatagramsIn.sum(),
                this.serverDatagramsOut.sum(),
                this.encapsulatedIn.sum(),
                this.encapsulatedOut.sum(),
                this.staleDatagrams.sum(),
                this.ackIn.sum(),
                this.ackOut.sum(),
                this.nackIn.sum(),
                this.nackOut.sum(),
                this.bulkSentMessages.sum(),
                this.bulkSentBytes.sum(),
                this.logicalPacketsSent.sum(),
                this.bulkReceivedMessages.sum(),
                this.bulkReceivedBytes.sum(),
                this.logicalPacketsReceived.sum(),
                this.probesSent.sum(),
                this.probesAcked.sum(),
                this.disconnects.sum(),
                this.maxQueuedBytes.get(),
                this.lastState
        );
    }

    public static final class Snapshot {
        public final int id;
        public final boolean impaired;
        public final InetSocketAddress address;
        public final long serverBytesIn;
        public final long serverBytesOut;
        public final long serverDatagramsIn;
        public final long serverDatagramsOut;
        public final long encapsulatedIn;
        public final long encapsulatedOut;
        public final long staleDatagrams;
        public final long ackIn;
        public final long ackOut;
        public final long nackIn;
        public final long nackOut;
        public final long bulkSentMessages;
        public final long bulkSentBytes;
        public final long logicalPacketsSent;
        public final long bulkReceivedMessages;
        public final long bulkReceivedBytes;
        public final long logicalPacketsReceived;
        public final long probesSent;
        public final long probesAcked;
        public final long disconnects;
        public final long maxQueuedBytes;
        public final RakState lastState;

        private Snapshot(int id, boolean impaired, InetSocketAddress address, long serverBytesIn, long serverBytesOut,
                         long serverDatagramsIn, long serverDatagramsOut, long encapsulatedIn, long encapsulatedOut,
                         long staleDatagrams, long ackIn, long ackOut, long nackIn, long nackOut,
                         long bulkSentMessages, long bulkSentBytes, long logicalPacketsSent,
                         long bulkReceivedMessages, long bulkReceivedBytes, long logicalPacketsReceived,
                         long probesSent, long probesAcked, long disconnects, long maxQueuedBytes, RakState lastState) {
            this.id = id;
            this.impaired = impaired;
            this.address = address;
            this.serverBytesIn = serverBytesIn;
            this.serverBytesOut = serverBytesOut;
            this.serverDatagramsIn = serverDatagramsIn;
            this.serverDatagramsOut = serverDatagramsOut;
            this.encapsulatedIn = encapsulatedIn;
            this.encapsulatedOut = encapsulatedOut;
            this.staleDatagrams = staleDatagrams;
            this.ackIn = ackIn;
            this.ackOut = ackOut;
            this.nackIn = nackIn;
            this.nackOut = nackOut;
            this.bulkSentMessages = bulkSentMessages;
            this.bulkSentBytes = bulkSentBytes;
            this.logicalPacketsSent = logicalPacketsSent;
            this.bulkReceivedMessages = bulkReceivedMessages;
            this.bulkReceivedBytes = bulkReceivedBytes;
            this.logicalPacketsReceived = logicalPacketsReceived;
            this.probesSent = probesSent;
            this.probesAcked = probesAcked;
            this.disconnects = disconnects;
            this.maxQueuedBytes = maxQueuedBytes;
            this.lastState = lastState;
        }
    }
}
