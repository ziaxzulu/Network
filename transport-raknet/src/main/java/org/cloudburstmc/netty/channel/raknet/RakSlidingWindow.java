/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet;

import org.cloudburstmc.netty.channel.raknet.config.RakRecoveryMode;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakSlidingWindow {
    private static final long BOUNDED_INITIAL_RTO_MILLIS = 1_000L;
    private static final long BOUNDED_MINIMUM_RTO_MILLIS = 500L;
    private static final long BOUNDED_MAXIMUM_BASE_RTO_MILLIS = 2_000L;

    private final int mtu;
    private final RakRecoveryMode recoveryMode;
    private double cwnd;
    private double ssThresh;
    private double estimatedRTT = -1;
    private double lastRTT = -1;
    private double deviationRTT = -1;
    private long oldestUnsentAck;
    private long nextCongestionControlBlock;
    private boolean backoffThisBlock;
    private int unackedBytes;
    private int bytesInFlight;
    private int recoveryProbeBytes;
    private boolean inRecovery;
    private long recoveryBoundary = -1L;

    public RakSlidingWindow(int mtu) {
        this(mtu, RakRecoveryMode.LEGACY);
    }

    public RakSlidingWindow(int mtu, RakRecoveryMode recoveryMode) {
        this.mtu = mtu;
        this.recoveryMode = recoveryMode;
        this.cwnd = mtu;
    }

    public int getRetransmissionBandwidth() {
        return unackedBytes;
    }

    public int getTransmissionBandwidth() {
        int chargedBytes = this.recoveryMode == RakRecoveryMode.BOUNDED ? this.bytesInFlight : this.unackedBytes;
        if (chargedBytes <= this.cwnd) {
            return (int) (this.cwnd - chargedBytes);
        } else {
            return 0;
        }
    }

    public void onPacketReceived(long curTime) {
        if (this.oldestUnsentAck == 0) {
            this.oldestUnsentAck = curTime;
        }
    }

    public void onResend(long curSequenceIndex) {
        if (!this.backoffThisBlock && this.cwnd > this.mtu * 2D) {
            this.ssThresh = this.cwnd * 0.5D;

            if (this.ssThresh < this.mtu) {
                this.ssThresh = this.mtu;
            }
            this.cwnd = this.mtu;

            this.nextCongestionControlBlock = curSequenceIndex;
            this.backoffThisBlock = true;
        }
    }

    public void onNak() {
        if (!this.backoffThisBlock) {
            this.ssThresh = this.cwnd * 0.75D;
        }
    }

    public void onAck(long curTime, RakDatagramPacket datagram, long curSequenceIndex) {
        if (this.recoveryMode == RakRecoveryMode.BOUNDED) {
            this.onBoundedAck(curTime, datagram, curSequenceIndex);
            return;
        }

        int size = datagram.getSize();
        this.unackedBytes -= size;
        this.bytesInFlight -= size;
        datagram.setReliableOutstanding(false);
        datagram.setInFlight(false);
        this.updateRtt(curTime - datagram.getSendTime());

        this.growWindow(datagram, curSequenceIndex);
    }

    private void onBoundedAck(long curTime, RakDatagramPacket datagram, long curSequenceIndex) {
        boolean recovering = this.inRecovery;
        this.completeAcknowledgement(datagram);

        // Karn's algorithm: an ACK after any retransmission cannot identify which attempt it acknowledges.
        if (datagram.getRetransmissionCount() == 0) {
            this.updateRtt(curTime - datagram.getSendTime());
        }

        if (this.inRecovery && (datagram.getSendOrdinal() > this.recoveryBoundary || this.unackedBytes == 0)) {
            this.inRecovery = false;
            this.recoveryBoundary = -1L;
        }

        // ACKs inside a recovery epoch only drain flight; they do not immediately regrow the reduced window.
        if (!recovering) {
            this.growWindow(datagram, curSequenceIndex);
        }
    }

    private void completeAcknowledgement(RakDatagramPacket datagram) {
        int size = datagram.getSize();
        if (datagram.isReliableOutstanding()) {
            this.unackedBytes = Math.max(0, this.unackedBytes - size);
            datagram.setReliableOutstanding(false);
        }
        if (datagram.isInFlight()) {
            this.bytesInFlight = Math.max(0, this.bytesInFlight - size);
            datagram.setInFlight(false);
        }
        if (datagram.isRecoveryProbe()) {
            this.recoveryProbeBytes = Math.max(0, this.recoveryProbeBytes - size);
            datagram.setRecoveryProbe(false);
        }
        datagram.setRetransmissionPending(false);
    }

    private void updateRtt(long rtt) {
        this.lastRTT = rtt;

        if (this.estimatedRTT == -1) {
            this.estimatedRTT = rtt;
            this.deviationRTT = rtt;
        } else {
            double d = 0.05D;
            double difference = rtt - this.estimatedRTT;
            this.estimatedRTT += d * difference;
            this.deviationRTT += d * (Math.abs(difference) - this.deviationRTT);
        }
    }

    private void growWindow(RakDatagramPacket datagram, long curSequenceIndex) {
        boolean isNewCongestionControlPeriod = datagram.getSequenceIndex() > this.nextCongestionControlBlock;

        if (isNewCongestionControlPeriod) {
            this.backoffThisBlock = false;
            this.nextCongestionControlBlock = curSequenceIndex;
        }

        if (this.isInSlowStart()) {
            this.cwnd += this.mtu;

            if (this.cwnd > this.ssThresh && this.ssThresh != 0) {
                this.cwnd = this.ssThresh + this.mtu * this.mtu / this.cwnd;
            }
        } else if (isNewCongestionControlPeriod) {
            this.cwnd += this.mtu * this.mtu / this.cwnd;
        }
    }

    public void onReliableSend(RakDatagramPacket datagram) {
        if (this.recoveryMode == RakRecoveryMode.LEGACY) {
            int size = datagram.getSize();
            this.unackedBytes += size;
            this.bytesInFlight += size;
            datagram.setReliableOutstanding(true);
            datagram.setInFlight(true);
            return;
        }
        if (datagram.isReliableOutstanding()) {
            return;
        }
        int size = datagram.getSize();
        this.unackedBytes += size;
        this.bytesInFlight += size;
        datagram.setReliableOutstanding(true);
        datagram.setInFlight(true);
    }

    /**
     * Removes a detected-lost physical transmission from flight while retaining the logical reliable datagram.
     * Returns {@code true} when this loss began a new recovery epoch.
     */
    public boolean onBoundedLoss(RakDatagramPacket datagram, long largestSentOrdinal) {
        if (this.recoveryMode != RakRecoveryMode.BOUNDED || !datagram.isReliableOutstanding()) {
            return false;
        }

        int size = datagram.getSize();
        if (datagram.isInFlight()) {
            this.bytesInFlight = Math.max(0, this.bytesInFlight - size);
            datagram.setInFlight(false);
        }
        if (datagram.isRecoveryProbe()) {
            this.recoveryProbeBytes = Math.max(0, this.recoveryProbeBytes - size);
            datagram.setRecoveryProbe(false);
        }

        if (this.inRecovery) {
            return false;
        }

        this.ssThresh = Math.max(this.mtu, this.cwnd * 0.5D);
        this.cwnd = this.ssThresh;
        this.inRecovery = true;
        this.recoveryBoundary = largestSentOrdinal;
        return true;
    }

    /** Returns whether a bounded retransmission can be charged to the congestion window. */
    public boolean canSendBoundedRecovery(int size) {
        return this.bytesInFlight + size <= this.cwnd;
    }

    /**
     * Returns whether the one-MTU probe exception is available. At most one such probe is charged at a time.
     */
    public boolean canSendBoundedProbe(int size) {
        return size <= this.mtu && this.recoveryProbeBytes == 0;
    }

    /** Charges a retransmitted physical attempt to flight, optionally using the single probe exception. */
    public void onBoundedRetransmit(RakDatagramPacket datagram, boolean probe) {
        if (this.recoveryMode != RakRecoveryMode.BOUNDED || !datagram.isReliableOutstanding()
                || datagram.isInFlight()) {
            throw new IllegalStateException("Invalid bounded retransmission accounting state");
        }
        int size = datagram.getSize();
        this.bytesInFlight += size;
        datagram.setInFlight(true);
        datagram.setRecoveryProbe(probe);
        if (probe) {
            this.recoveryProbeBytes += size;
        }
    }

    /** Restores the lost-but-outstanding state when a retransmission cannot be handed to the channel. */
    public void onBoundedRetransmitFailed(RakDatagramPacket datagram) {
        int size = datagram.getSize();
        if (datagram.isInFlight()) {
            this.bytesInFlight = Math.max(0, this.bytesInFlight - size);
            datagram.setInFlight(false);
        }
        if (datagram.isRecoveryProbe()) {
            this.recoveryProbeBytes = Math.max(0, this.recoveryProbeBytes - size);
            datagram.setRecoveryProbe(false);
        }
    }

    public boolean isInSlowStart() {
        return this.cwnd <= this.ssThresh || this.ssThresh == 0;
    }

    public void onSendAck() {
        this.oldestUnsentAck = 0;
    }

    @SuppressWarnings("ManualMinMaxCalculation")
    public long getRtoForRetransmission() {
        if (this.recoveryMode == RakRecoveryMode.BOUNDED) {
            if (this.estimatedRTT == -1) {
                return BOUNDED_INITIAL_RTO_MILLIS;
            }
            long variation = Math.max(10L, (long) (4.0D * this.deviationRTT));
            long threshold = (long) this.estimatedRTT + variation + CC_ADDITIONAL_VARIANCE;
            return Math.max(BOUNDED_MINIMUM_RTO_MILLIS,
                    Math.min(BOUNDED_MAXIMUM_BASE_RTO_MILLIS, threshold));
        }
        if (this.estimatedRTT == -1) {
            return CC_MAXIMUM_THRESHOLD;
        }

        long threshold = (long) ((2.0D * this.estimatedRTT + 4.0D * this.deviationRTT) + CC_ADDITIONAL_VARIANCE);

        return threshold > CC_MAXIMUM_THRESHOLD ? CC_MAXIMUM_THRESHOLD : threshold;
    }

    public double getRTT() {
        return this.estimatedRTT;
    }

    public double getRttDeviation() {
        return this.deviationRTT;
    }

    public double getCongestionWindow() {
        return this.cwnd;
    }

    public double getSlowStartThreshold() {
        return this.ssThresh;
    }

    public boolean shouldSendAcks(long curTime) {
        long rto = this.getSenderRtoForAck();

        return rto == -1 || curTime >= this.oldestUnsentAck + CC_SYN;
    }

    public long getSenderRtoForAck() {
        if (this.lastRTT == -1) {
            return -1;
        } else {
            return (long) (this.lastRTT + CC_SYN);
        }
    }

    public int getUnackedBytes() {
        return unackedBytes;
    }

    public int getBytesInFlight() {
        return this.bytesInFlight;
    }

    public boolean isInRecovery() {
        return this.inRecovery;
    }

    public long getRecoveryBoundary() {
        return this.recoveryBoundary;
    }

    public int getRecoveryProbeBytes() {
        return this.recoveryProbeBytes;
    }

    /** Clears session-owned accounting during terminal resource reclamation. */
    public void close() {
        this.unackedBytes = 0;
        this.bytesInFlight = 0;
        this.recoveryProbeBytes = 0;
        this.inRecovery = false;
        this.recoveryBoundary = -1L;
    }
}
