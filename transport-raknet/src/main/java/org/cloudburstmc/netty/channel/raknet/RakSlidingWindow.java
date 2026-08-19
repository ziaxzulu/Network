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
    private final RakModelCongestionController modelController;
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
    private int modelUnreliableBytesInFlight;
    private int recoveryProbeBytes;
    private boolean inRecovery;
    private long recoveryBoundary = -1L;

    public RakSlidingWindow(int mtu) {
        this(mtu, RakRecoveryMode.LEGACY);
    }

    public RakSlidingWindow(int mtu, RakRecoveryMode recoveryMode) {
        this(mtu, recoveryMode, 10L);
    }

    /**
     * Creates a sliding window whose model controller accounts for the session's actual send opportunity quantum.
     *
     * @param mtu datagram MTU in bytes
     * @param recoveryMode recovery and congestion-control mode
     * @param sendQuantumMillis maximum regular interval between session send opportunities
     */
    public RakSlidingWindow(int mtu, RakRecoveryMode recoveryMode, long sendQuantumMillis) {
        this.mtu = mtu;
        this.recoveryMode = recoveryMode;
        this.modelController = recoveryMode.usesModelBasedCongestionControl()
                ? new RakModelCongestionController(mtu, sendQuantumMillis) : null;
        this.cwnd = this.modelController == null ? mtu : this.modelController.getCongestionWindow();
    }

    public int getRetransmissionBandwidth() {
        return unackedBytes;
    }

    public int getTransmissionBandwidth() {
        return this.getTransmissionBandwidth(-1L);
    }

    /** Returns new-send admission at {@code curTime}, including model pacing when enabled. */
    public int getTransmissionBandwidth(long curTime) {
        int chargedBytes = this.recoveryMode.usesBoundedRecovery()
                ? this.congestionControlledBytesInFlight() : this.unackedBytes;
        if (this.modelController != null && curTime >= 0L) {
            return this.modelController.transmissionAllowance(curTime, chargedBytes);
        }
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
        if (this.recoveryMode.usesBoundedRecovery()) {
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
        long rttSample = -1L;
        if (datagram.getRetransmissionCount() == 0) {
            rttSample = curTime - datagram.getSendTime();
            this.updateRtt(rttSample);
        }

        if (this.modelController != null) {
            this.modelController.onAcknowledged(datagram, curTime, rttSample, this.estimatedRTT,
                    this.congestionControlledBytesInFlight());
            this.cwnd = this.modelController.getCongestionWindow();
        }

        if (this.inRecovery && (datagram.getSendOrdinal() > this.recoveryBoundary || this.unackedBytes == 0)) {
            this.inRecovery = false;
            this.recoveryBoundary = -1L;
        }

        // ACKs inside a recovery epoch only drain flight; they do not immediately regrow the reduced window.
        if (this.modelController == null && !recovering) {
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
        this.onReliableSend(datagram, false);
    }

    /** Tracks a new reliable send and whether the sender had no further work available. */
    public void onReliableSend(RakDatagramPacket datagram, boolean appLimited) {
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
        int previousBytesInFlight = this.congestionControlledBytesInFlight();
        this.unackedBytes += size;
        this.bytesInFlight += size;
        datagram.setReliableOutstanding(true);
        datagram.setInFlight(true);
        if (this.modelController != null) {
            this.modelController.onPacketSent(datagram, datagram.getSendTime(), previousBytesInFlight + size,
                    appLimited);
        }
    }

    /**
     * Removes a detected-lost physical transmission from flight while retaining the logical reliable datagram.
     * Returns {@code true} when this loss began a new recovery epoch.
     */
    public boolean onBoundedLoss(RakDatagramPacket datagram, long largestSentOrdinal) {
        if (!this.recoveryMode.usesBoundedRecovery() || !datagram.isReliableOutstanding()) {
            return false;
        }

        int size = datagram.getSize();
        boolean physicalAttemptWasInFlight = datagram.isInFlight();
        if (datagram.isInFlight()) {
            this.bytesInFlight = Math.max(0, this.bytesInFlight - size);
            datagram.setInFlight(false);
        }
        if (datagram.isRecoveryProbe()) {
            this.recoveryProbeBytes = Math.max(0, this.recoveryProbeBytes - size);
            datagram.setRecoveryProbe(false);
        }

        if (this.modelController != null && physicalAttemptWasInFlight) {
            this.modelController.onLost(datagram, this.estimatedRTT);
            this.cwnd = this.modelController.getCongestionWindow();
        }

        if (this.inRecovery) {
            return false;
        }

        if (this.modelController == null) {
            this.ssThresh = Math.max(this.mtu, this.cwnd * 0.5D);
            this.cwnd = this.ssThresh;
        }
        this.inRecovery = true;
        this.recoveryBoundary = largestSentOrdinal;
        return true;
    }

    /** Returns whether a bounded retransmission can be charged to the congestion window. */
    public boolean canSendBoundedRecovery(int size) {
        return this.congestionControlledBytesInFlight() + size <= this.cwnd;
    }

    /** Returns whether recovery fits both the congestion window and model pacer at {@code curTime}. */
    public boolean canSendBoundedRecovery(int size, long curTime) {
        int controlledBytesInFlight = this.congestionControlledBytesInFlight();
        return controlledBytesInFlight + size <= this.cwnd
                && (this.modelController == null
                || this.modelController.canSend(curTime, controlledBytesInFlight, size));
    }

    /**
     * Returns whether the one-MTU probe exception is available. At most one such probe is charged at a time.
     */
    public boolean canSendBoundedProbe(int size) {
        return size <= this.mtu && this.recoveryProbeBytes == 0;
    }

    /** Charges a retransmitted physical attempt to flight, optionally using the single probe exception. */
    public void onBoundedRetransmit(RakDatagramPacket datagram, boolean probe) {
        this.onBoundedRetransmit(datagram, probe, datagram.getSendTime());
    }

    /** Charges a retransmission at its actual handoff time for model sampling and pacing. */
    public void onBoundedRetransmit(RakDatagramPacket datagram, boolean probe, long curTime) {
        this.onBoundedRetransmit(datagram, probe, curTime, false);
    }

    /** Charges a retransmission and records whether recovery exhausted all currently queued work. */
    public void onBoundedRetransmit(RakDatagramPacket datagram, boolean probe, long curTime,
                                    boolean appLimited) {
        if (!this.recoveryMode.usesBoundedRecovery() || !datagram.isReliableOutstanding()
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
        if (this.modelController != null) {
            this.modelController.onPacketSent(datagram, curTime, this.congestionControlledBytesInFlight(),
                    appLimited);
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

    /** Consumes model pacing credit for an unreliable datagram admitted by the shared send budget. */
    public void onUnreliableSend(int size, long curTime) {
        if (this.modelController != null) {
            this.modelController.onUnreliablePacketSent(size, curTime);
        }
    }

    /** Tracks a payload-free physical datagram sample so model pacing covers unreliable as well as reliable data. */
    public ModelDatagramSample onUnreliableSendTracked(int size, long curTime, boolean appLimited) {
        if (this.modelController == null) {
            this.onUnreliableSend(size, curTime);
            return null;
        }
        RakModelCongestionController.UnreliableSendState rollbackState =
                this.modelController.captureUnreliableSendState();
        this.modelUnreliableBytesInFlight += size;
        return new ModelDatagramSample(this.modelController.onUnreliablePacketSent(size, curTime,
                this.congestionControlledBytesInFlight(), appLimited), rollbackState);
    }

    /** Credits a tracked unreliable physical datagram acknowledged by RakNet without retaining its payload. */
    public void onUnreliableAck(ModelDatagramSample sample, long curTime) {
        if (this.modelController == null || sample == null || sample.completed) {
            return;
        }
        sample.completed = true;
        this.modelUnreliableBytesInFlight = Math.max(0,
                this.modelUnreliableBytesInFlight - sample.controllerState.size());
        long rttSample = curTime - sample.controllerState.sendTime();
        this.updateRtt(rttSample);
        this.modelController.onUnreliableAcknowledged(sample.controllerState, curTime, rttSample,
                this.estimatedRTT, this.congestionControlledBytesInFlight());
        this.cwnd = this.modelController.getCongestionWindow();
    }

    /** Retires a tracked unreliable physical datagram after NACK or bounded metadata expiry. */
    public void onUnreliableLoss(ModelDatagramSample sample) {
        if (this.modelController == null || sample == null || sample.completed) {
            return;
        }
        sample.completed = true;
        this.modelUnreliableBytesInFlight = Math.max(0,
                this.modelUnreliableBytesInFlight - sample.controllerState.size());
        this.modelController.onUnreliableLost(sample.controllerState, this.estimatedRTT);
    }

    /** Rolls back metadata, flight, and pacing when an unreliable handoff fails synchronously. */
    public void onUnreliableSendFailed(ModelDatagramSample sample) {
        if (this.modelController == null || sample == null || sample.completed) {
            return;
        }
        sample.completed = true;
        this.modelUnreliableBytesInFlight = Math.max(0,
                this.modelUnreliableBytesInFlight - sample.controllerState.size());
        this.modelController.restoreUnreliableSendState(sample.rollbackState);
    }

    private int congestionControlledBytesInFlight() {
        return this.bytesInFlight + this.modelUnreliableBytesInFlight;
    }

    /** Applies a minimum-window response after multiple exponentially backed-off PTOs without ACK progress. */
    public void onPersistentCongestion() {
        if (this.modelController != null) {
            this.modelController.onPersistentCongestion();
            this.cwnd = this.modelController.getCongestionWindow();
        }
    }

    /** Captures send-only controller state so a failed retransmission handoff can be rolled back exactly. */
    public ModelSendState captureModelSendState(RakDatagramPacket datagram) {
        return this.modelController == null ? ModelSendState.EMPTY
                : new ModelSendState(this.modelController.captureSendState(datagram));
    }

    /** Restores a state captured by {@link #captureModelSendState(RakDatagramPacket)}. */
    public void restoreModelSendState(RakDatagramPacket datagram, ModelSendState state) {
        if (this.modelController != null && state.controllerState != null) {
            this.modelController.restoreSendState(datagram, state.controllerState);
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
        if (this.recoveryMode.usesBoundedRecovery()) {
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
        return this.congestionControlledBytesInFlight();
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

    public double getModelBandwidthBytesPerMillis() {
        return this.modelController == null ? -1D : this.modelController.getMaxBandwidthBytesPerMillis();
    }

    public double getModelPacingRateBytesPerMillis() {
        return this.modelController == null ? -1D : this.modelController.getPacingRateBytesPerMillis();
    }

    public long getModelMinimumRttMillis() {
        return this.modelController == null ? -1L : this.modelController.getMinimumRttMillis();
    }

    /**
     * Returns the model mode's NACK reordering window. RACK uses a fraction of minimum RTT to avoid treating a
     * short-lived sequence gap as immediate loss; the bounds keep startup useful before an RTT sample exists.
     */
    public long getNackReorderingDelayMillis() {
        double referenceRtt = this.modelController != null && this.modelController.getMinimumRttMillis() > 0L
                ? this.modelController.getMinimumRttMillis() : this.estimatedRTT;
        if (referenceRtt < 0D) {
            return 50L;
        }
        return Math.max(50L, Math.min(200L, (long) Math.ceil(referenceRtt / 4D)));
    }

    /** Returns the earliest time at which a NACKed physical attempt can be declared lost. */
    public long getNackLossDeadlineMillis(long attemptSendTimeMillis, long nackObservedAtMillis) {
        double referenceRtt = this.modelController != null && this.modelController.getMinimumRttMillis() > 0L
                ? this.modelController.getMinimumRttMillis() : this.estimatedRTT;
        if (referenceRtt < 0D) {
            referenceRtt = 200D;
        }
        long reorderingDelay = this.getNackReorderingDelayMillis();
        long attemptThreshold = attemptSendTimeMillis + (long) Math.ceil(referenceRtt + reorderingDelay);
        return Math.max(nackObservedAtMillis + reorderingDelay, attemptThreshold);
    }

    public boolean isModelStartup() {
        return this.modelController != null && this.modelController.isStartup();
    }

    public long getModelRoundCount() {
        return this.modelController == null ? 0L : this.modelController.getRoundCount();
    }

    public boolean isModelPersistentCongestion() {
        return this.modelController != null && this.modelController.isPersistentCongestion();
    }

    public double getModelRecentLossRate() {
        return this.modelController == null ? -1D : this.modelController.getRecentLossRate();
    }

    /** Clears session-owned accounting during terminal resource reclamation. */
    public void close() {
        this.unackedBytes = 0;
        this.bytesInFlight = 0;
        this.modelUnreliableBytesInFlight = 0;
        this.recoveryProbeBytes = 0;
        this.inRecovery = false;
        this.recoveryBoundary = -1L;
    }

    public static final class ModelSendState {
        private static final ModelSendState EMPTY = new ModelSendState(null);
        private final RakModelCongestionController.SendState controllerState;

        private ModelSendState(RakModelCongestionController.SendState controllerState) {
            this.controllerState = controllerState;
        }
    }

    /** Opaque payload-free ACK/loss sample retained only until an unreliable physical datagram is resolved. */
    public static final class ModelDatagramSample {
        private final RakModelCongestionController.DatagramSample controllerState;
        private final RakModelCongestionController.UnreliableSendState rollbackState;
        private boolean completed;
        private boolean nackPending;
        private long nackObservedAtMillis = -1L;

        private ModelDatagramSample(RakModelCongestionController.DatagramSample controllerState,
                                    RakModelCongestionController.UnreliableSendState rollbackState) {
            this.controllerState = controllerState;
            this.rollbackState = rollbackState;
        }

        public long getSendTimeMillis() {
            return this.controllerState.sendTime();
        }

        public boolean scheduleNack(long observedAtMillis) {
            if (this.completed || this.nackPending) {
                return false;
            }
            this.nackPending = true;
            this.nackObservedAtMillis = observedAtMillis;
            return true;
        }

        public boolean hasPendingNack() {
            return this.nackPending;
        }

        public long getNackObservedAtMillis() {
            return this.nackObservedAtMillis;
        }
    }
}
