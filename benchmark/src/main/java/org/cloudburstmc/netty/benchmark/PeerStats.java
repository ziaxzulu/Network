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
import org.cloudburstmc.netty.channel.raknet.config.RakDatagramSendType;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final LongAdder probeAckSpillover = new LongAdder();
    private final LongAdder disconnects = new LongAdder();
    private final LongAdder blackholedDatagramsIn = new LongAdder();
    private final LongAdder blackholedDatagramsOut = new LongAdder();
    private final AtomicLong maxQueuedBytes = new AtomicLong();

    /*
     * Measurement-window counters above intentionally reset between iterations. These run-lifetime
     * counters back the event timeline, where a reset must never erase a disconnect or recovery event.
     */
    private final LongAdder lifetimeServerBytesOut = new LongAdder();
    private final LongAdder lifetimeServerDatagramsOut = new LongAdder();
    private final LongAdder lifetimeStaleDatagrams = new LongAdder();
    private final LongAdder lifetimeNackIn = new LongAdder();
    private final LongAdder lifetimeNackOut = new LongAdder();
    private final LongAdder lifetimeBulkSentMessages = new LongAdder();
    private final LongAdder lifetimeBulkSentBytes = new LongAdder();
    private final LongAdder lifetimeLogicalPacketsSent = new LongAdder();
    private final LongAdder lifetimeBulkReceivedMessages = new LongAdder();
    private final LongAdder lifetimeBulkReceivedBytes = new LongAdder();
    private final LongAdder lifetimeLogicalPacketsReceived = new LongAdder();
    private final LongAdder lifetimeDisconnects = new LongAdder();
    private final LongAdder lifetimeBlackholedDatagramsIn = new LongAdder();
    private final LongAdder lifetimeBlackholedDatagramsOut = new LongAdder();
    private final LongAdder lifetimeOriginalDatagrams = new LongAdder();
    private final LongAdder lifetimeOriginalDatagramBytes = new LongAdder();
    private final LongAdder lifetimeNackRetransmittedDatagrams = new LongAdder();
    private final LongAdder lifetimeNackRetransmittedBytes = new LongAdder();
    private final LongAdder lifetimeTimeoutRetransmittedDatagrams = new LongAdder();
    private final LongAdder lifetimeTimeoutRetransmittedBytes = new LongAdder();
    private final LongAdder lifetimeAcknowledgementProgressEvents = new LongAdder();
    private final LongAdder lifetimeAcknowledgementProgressBytes = new LongAdder();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicLong currentQueuedBytes = new AtomicLong();
    private final AtomicLong lifetimeMaxQueuedBytes = new AtomicLong();
    private final AtomicLong currentBytesInFlight = new AtomicLong();
    private final AtomicLong lifetimeMaxBytesInFlight = new AtomicLong();
    private final AtomicInteger maxRetransmissionAttempt = new AtomicInteger();
    private volatile long recoveryObservedAtMillis = -1L;
    private volatile double congestionWindow = -1.0D;
    private volatile double slowStartThreshold = -1.0D;
    private volatile double smoothedRtt = -1.0D;
    private volatile double rttVariance = -1.0D;
    private volatile long retransmissionTimeout = -1L;
    private volatile int retransmittedDatagramsInFlight;
    private volatile long lastAckProgressAtMillis = -1L;
    private volatile long recoveryStartedAtMillis = -1L;
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
        this.probeAckSpillover.reset();
        this.disconnects.reset();
        this.blackholedDatagramsIn.reset();
        this.blackholedDatagramsOut.reset();
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
        this.lifetimeServerBytesOut.add(count);
    }

    public void addServerDatagramsIn(int count) {
        this.serverDatagramsIn.add(count);
    }

    public void addServerDatagramsOut(int count) {
        this.serverDatagramsOut.add(count);
        this.lifetimeServerDatagramsOut.add(count);
    }

    public void addEncapsulatedIn(int count) {
        this.encapsulatedIn.add(count);
    }

    public void addEncapsulatedOut(int count) {
        this.encapsulatedOut.add(count);
    }

    public void addStaleDatagrams(int count) {
        this.staleDatagrams.add(count);
        this.lifetimeStaleDatagrams.add(count);
    }

    public void addAckIn(int count) {
        this.ackIn.add(count);
    }

    public void addAckOut(int count) {
        this.ackOut.add(count);
    }

    public void addNackIn(int count) {
        this.nackIn.add(count);
        this.lifetimeNackIn.add(count);
    }

    public void addNackOut(int count) {
        this.nackOut.add(count);
        this.lifetimeNackOut.add(count);
    }

    public void addBulkSent(int bytes) {
        addBulkSent(bytes, 1);
    }

    public void addBulkSent(int bytes, int logicalPackets) {
        this.bulkSentMessages.increment();
        this.bulkSentBytes.add(bytes);
        this.logicalPacketsSent.add(Math.max(1, logicalPackets));
        this.lifetimeBulkSentMessages.increment();
        this.lifetimeBulkSentBytes.add(bytes);
        this.lifetimeLogicalPacketsSent.add(Math.max(1, logicalPackets));
    }

    public void addBulkReceived(int bytes) {
        addBulkReceived(bytes, 1);
    }

    public void addBulkReceived(int bytes, int logicalPackets) {
        this.bulkReceivedMessages.increment();
        this.bulkReceivedBytes.add(bytes);
        this.logicalPacketsReceived.add(Math.max(1, logicalPackets));
        this.lifetimeBulkReceivedMessages.increment();
        this.lifetimeBulkReceivedBytes.add(bytes);
        this.lifetimeLogicalPacketsReceived.add(Math.max(1, logicalPackets));
    }

    public void addProbeSent() {
        this.probesSent.increment();
    }

    public void addProbeAcked() {
        this.probesAcked.increment();
    }

    public void addProbeAckSpillover() {
        this.probeAckSpillover.increment();
    }

    public void addDisconnect() {
        if (this.disconnected.compareAndSet(false, true)) {
            this.disconnects.increment();
            this.lifetimeDisconnects.increment();
        }
        this.currentQueuedBytes.set(0L);
        this.clearRecoveryState();
        this.lastState = RakState.DISCONNECTED;
    }

    public void addBlackholedDatagramIn() {
        this.blackholedDatagramsIn.increment();
        this.lifetimeBlackholedDatagramsIn.increment();
    }

    public void addBlackholedDatagramOut() {
        this.blackholedDatagramsOut.increment();
        this.lifetimeBlackholedDatagramsOut.increment();
    }

    public void state(RakState state) {
        this.lastState = state;
    }

    public void queuedBytes(int count) {
        long value = Math.max(0, count);
        this.currentQueuedBytes.set(value);
        updateMaximum(this.maxQueuedBytes, value);
        updateMaximum(this.lifetimeMaxQueuedBytes, value);
    }

    public void datagramSent(RakDatagramSendType sendType, int bytes, int retransmissionAttempt, int bytesInFlight) {
        int safeBytes = Math.max(0, bytes);
        if (sendType == RakDatagramSendType.ORIGINAL) {
            this.lifetimeOriginalDatagrams.increment();
            this.lifetimeOriginalDatagramBytes.add(safeBytes);
        } else if (sendType == RakDatagramSendType.NACK_RETRANSMISSION) {
            this.lifetimeNackRetransmittedDatagrams.increment();
            this.lifetimeNackRetransmittedBytes.add(safeBytes);
        } else if (sendType == RakDatagramSendType.TIMEOUT_RETRANSMISSION) {
            this.lifetimeTimeoutRetransmittedDatagrams.increment();
            this.lifetimeTimeoutRetransmittedBytes.add(safeBytes);
        }
        updateMaximum(this.maxRetransmissionAttempt, Math.max(0, retransmissionAttempt));
        updateBytesInFlight(bytesInFlight);
    }

    public void acknowledgementProgress(int bytes, int retransmissionAttempt, long observedAtMillis,
                                        long previousAckProgressAtMillis, long recoveryStartedAtMillis) {
        this.lifetimeAcknowledgementProgressEvents.increment();
        this.lifetimeAcknowledgementProgressBytes.add(Math.max(0, bytes));
        updateMaximum(this.maxRetransmissionAttempt, Math.max(0, retransmissionAttempt));
        this.recoveryObservedAtMillis = observedAtMillis;
        this.lastAckProgressAtMillis = observedAtMillis;
        this.recoveryStartedAtMillis = recoveryStartedAtMillis;
    }

    public void recoveryState(long observedAtMillis, int bytesInFlight, double congestionWindow,
                              double slowStartThreshold, double smoothedRtt, double rttVariance,
                              long retransmissionTimeout, int retransmittedDatagramsInFlight,
                              long lastAckProgressAtMillis, long recoveryStartedAtMillis) {
        this.recoveryObservedAtMillis = observedAtMillis;
        updateBytesInFlight(bytesInFlight);
        this.congestionWindow = congestionWindow;
        this.slowStartThreshold = slowStartThreshold;
        this.smoothedRtt = smoothedRtt;
        this.rttVariance = rttVariance;
        this.retransmissionTimeout = retransmissionTimeout;
        this.retransmittedDatagramsInFlight = Math.max(0, retransmittedDatagramsInFlight);
        this.lastAckProgressAtMillis = lastAckProgressAtMillis;
        this.recoveryStartedAtMillis = recoveryStartedAtMillis;
    }

    public void recoveryStateClosed(long observedAtMillis) {
        // This is the terminal per-session callback. A final queued-bytes tick can race the earlier
        // parent-channel close notification, so make the terminal gauge authoritative while retaining
        // the lifetime high-water mark for post-run analysis.
        this.currentQueuedBytes.set(0L);
        this.clearRecoveryState();
    }

    private void clearRecoveryState() {
        this.recoveryObservedAtMillis = -1L;
        this.currentBytesInFlight.set(0L);
        this.congestionWindow = -1.0D;
        this.slowStartThreshold = -1.0D;
        this.smoothedRtt = -1.0D;
        this.rttVariance = -1.0D;
        this.retransmissionTimeout = -1L;
        this.retransmittedDatagramsInFlight = 0;
        this.lastAckProgressAtMillis = -1L;
        this.recoveryStartedAtMillis = -1L;
    }

    private void updateBytesInFlight(int bytesInFlight) {
        long value = Math.max(0, bytesInFlight);
        this.currentBytesInFlight.set(value);
        updateMaximum(this.lifetimeMaxBytesInFlight, value);
    }

    private static void updateMaximum(AtomicLong maximum, long value) {
        long current;
        do {
            current = maximum.get();
            if (value <= current) {
                return;
            }
        } while (!maximum.compareAndSet(current, value));
    }

    private static void updateMaximum(AtomicInteger maximum, int value) {
        int current;
        do {
            current = maximum.get();
            if (value <= current) {
                return;
            }
        } while (!maximum.compareAndSet(current, value));
    }

    public TimelineSnapshot timelineSnapshot(boolean channelOpen, boolean channelActive) {
        return new TimelineSnapshot(
                this.id,
                this.impaired,
                channelOpen,
                channelActive,
                this.disconnected.get(),
                this.lifetimeDisconnects.sum(),
                this.lifetimeBulkSentMessages.sum(),
                this.lifetimeBulkSentBytes.sum(),
                this.lifetimeLogicalPacketsSent.sum(),
                this.lifetimeBulkReceivedMessages.sum(),
                this.lifetimeBulkReceivedBytes.sum(),
                this.lifetimeLogicalPacketsReceived.sum(),
                this.lifetimeServerBytesOut.sum(),
                this.lifetimeServerDatagramsOut.sum(),
                this.lifetimeStaleDatagrams.sum(),
                this.lifetimeNackIn.sum(),
                this.lifetimeNackOut.sum(),
                this.lifetimeBlackholedDatagramsIn.sum(),
                this.lifetimeBlackholedDatagramsOut.sum(),
                this.lifetimeOriginalDatagrams.sum(),
                this.lifetimeOriginalDatagramBytes.sum(),
                this.lifetimeNackRetransmittedDatagrams.sum(),
                this.lifetimeNackRetransmittedBytes.sum(),
                this.lifetimeTimeoutRetransmittedDatagrams.sum(),
                this.lifetimeTimeoutRetransmittedBytes.sum(),
                this.lifetimeAcknowledgementProgressEvents.sum(),
                this.lifetimeAcknowledgementProgressBytes.sum(),
                this.currentQueuedBytes.get(),
                this.lifetimeMaxQueuedBytes.get(),
                this.currentBytesInFlight.get(),
                this.lifetimeMaxBytesInFlight.get(),
                this.maxRetransmissionAttempt.get(),
                this.recoveryObservedAtMillis,
                this.congestionWindow,
                this.slowStartThreshold,
                this.smoothedRtt,
                this.rttVariance,
                this.retransmissionTimeout,
                this.retransmittedDatagramsInFlight,
                this.lastAckProgressAtMillis,
                this.recoveryStartedAtMillis
        );
    }

    public Snapshot snapshot() {
        return snapshot(false, false);
    }

    public Snapshot snapshot(boolean channelOpen, boolean channelActive) {
        return new Snapshot(
                this.id,
                this.impaired,
                this.address,
                channelOpen,
                channelActive,
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
                this.probeAckSpillover.sum(),
                this.disconnects.sum(),
                this.blackholedDatagramsIn.sum(),
                this.blackholedDatagramsOut.sum(),
                this.maxQueuedBytes.get(),
                this.lastState
        );
    }

    public static final class Snapshot {
        public final int id;
        public final boolean impaired;
        public final InetSocketAddress address;
        public final boolean channelOpen;
        public final boolean channelActive;
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
        public final long probeAckSpillover;
        public final long disconnects;
        public final long blackholedDatagramsIn;
        public final long blackholedDatagramsOut;
        public final long maxQueuedBytes;
        public final RakState lastState;

        private Snapshot(int id, boolean impaired, InetSocketAddress address, boolean channelOpen, boolean channelActive,
                         long serverBytesIn, long serverBytesOut,
                         long serverDatagramsIn, long serverDatagramsOut, long encapsulatedIn, long encapsulatedOut,
                         long staleDatagrams, long ackIn, long ackOut, long nackIn, long nackOut,
                         long bulkSentMessages, long bulkSentBytes, long logicalPacketsSent,
                         long bulkReceivedMessages, long bulkReceivedBytes, long logicalPacketsReceived,
                         long probesSent, long probesAcked, long probeAckSpillover, long disconnects,
                         long blackholedDatagramsIn,
                         long blackholedDatagramsOut, long maxQueuedBytes, RakState lastState) {
            this.id = id;
            this.impaired = impaired;
            this.address = address;
            this.channelOpen = channelOpen;
            this.channelActive = channelActive;
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
            this.probeAckSpillover = probeAckSpillover;
            this.disconnects = disconnects;
            this.blackholedDatagramsIn = blackholedDatagramsIn;
            this.blackholedDatagramsOut = blackholedDatagramsOut;
            this.maxQueuedBytes = maxQueuedBytes;
            this.lastState = lastState;
        }
    }

    public record TimelineSnapshot(
            int id,
            boolean impaired,
            boolean channelOpen,
            boolean channelActive,
            boolean disconnected,
            long disconnectEvents,
            long usefulSentMessages,
            long usefulSentBytes,
            long logicalPacketsSent,
            long usefulReceivedMessages,
            long usefulReceivedBytes,
            long logicalPacketsReceived,
            long serverBytesOut,
            long serverDatagramsOut,
            long staleDatagrams,
            long nackIn,
            long nackOut,
            long blackholedDatagramsIn,
            long blackholedDatagramsOut,
            long originalDatagrams,
            long originalDatagramBytes,
            long nackRetransmittedDatagrams,
            long nackRetransmittedBytes,
            long timeoutRetransmittedDatagrams,
            long timeoutRetransmittedBytes,
            long acknowledgementProgressEvents,
            long acknowledgementProgressBytes,
            long currentQueuedBytes,
            long maxQueuedBytes,
            long bytesInFlight,
            long maxBytesInFlight,
            int maxRetransmissionAttempt,
            long recoveryObservedAtMillis,
            double congestionWindow,
            double slowStartThreshold,
            double smoothedRtt,
            double rttVariance,
            long retransmissionTimeout,
            int retransmittedDatagramsInFlight,
            long lastAckProgressAtMillis,
            long recoveryStartedAtMillis
    ) {
    }
}
