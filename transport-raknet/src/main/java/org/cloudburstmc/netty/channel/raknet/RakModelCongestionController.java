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

package org.cloudburstmc.netty.channel.raknet;

import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

/**
 * Event-loop-confined prototype of a delivery-model congestion controller.
 *
 * <p>This is deliberately not named BBR: it implements only the minimum RakNet integration needed to evaluate
 * per-attempt delivery-rate sampling, packet-timed bandwidth filtering, BDP-based in-flight control, and bounded
 * pacing. It does not claim the full state machine or validation envelope of the IETF BBR draft.</p>
 */
final class RakModelCongestionController {
    private static final int BANDWIDTH_FILTER_ROUNDS = 10;
    private static final long INITIAL_RTT_MILLIS = 333L;
    private static final double STARTUP_PACING_GAIN = 2.77D;
    private static final double PROBE_UP_PACING_GAIN = 1.25D;
    private static final double PROBE_DOWN_PACING_GAIN = 0.75D;
    private static final double CWND_GAIN = 2.0D;
    private static final double FULL_BANDWIDTH_GROWTH = 1.25D;
    private static final int FULL_BANDWIDTH_ROUNDS = 3;
    private static final double LOSS_THRESHOLD = 0.02D;
    private static final double HARD_LOSS_THRESHOLD = 0.20D;
    private static final double DELAY_INFLATION_THRESHOLD = 1.25D;
    private static final double LOSS_BETA = 0.70D;
    private static final int MAX_BURST_DATAGRAMS = 8;
    private static final long MINIMUM_RTT_WINDOW_MILLIS = 10_000L;
    private static final double PATH_STEP_MULTIPLIER = 4.0D;
    private static final long PATH_STEP_ABSOLUTE_DELTA_MILLIS = 50L;
    private static final int PATH_SUSPICION_CONFIRMATION_SAMPLES = 2;
    private static final int PATH_STEP_CONFIRMATION_SAMPLES = 2;
    private static final double PATH_STEP_STABILITY_MULTIPLIER = 1.25D;
    private static final long PATH_TIMEOUT_RTT_MULTIPLIER = 10L;
    private static final long PATH_TIMEOUT_MIN_MILLIS = 2_000L;
    private static final long PATH_TIMEOUT_MAX_MILLIS = 3_000L;
    private static final long PATH_DRAIN_HOLD_MIN_MILLIS = 50L;
    private static final long PATH_DRAIN_HOLD_MAX_MILLIS = 500L;
    private static final long PATH_COOLDOWN_RTT_MULTIPLIER = 2L;
    private static final long PATH_COOLDOWN_MIN_MILLIS = 250L;
    private static final long PATH_COOLDOWN_MAX_MILLIS = 1_000L;
    private static final int PATH_MAX_ATTEMPTS = 3;
    private static final int STARTUP_MINIMUM_ROUND_MTUS = 4;
    private static final int LOSS_BOUND_RELEASE_ROUNDS = 3;
    private static final double LOSS_BOUND_GROWTH = 1.05D;

    private final int mtu;
    private final long sendQuantumMillis;
    private final double minimumCwnd;
    private final double initialCwnd;
    private final double maximumCwnd;
    private final double[] bandwidthFilter = new double[BANDWIDTH_FILTER_ROUNDS];

    private double cwnd;
    private double maxBandwidthBytesPerMillis;
    private double fullBandwidthBytesPerMillis;
    private int fullBandwidthRounds;
    private boolean startup = true;
    private long startupCongestionEvidenceBytes;
    private long startupLostEvidenceBytes;
    private boolean persistentCongestion;
    private double inflightLimit = Double.POSITIVE_INFINITY;
    private int lossFreeRounds;

    private long deliveredBytes;
    private long deliveredTimeMillis;
    private long firstSendTimeMillis;
    private long nextRoundDelivered;
    private long roundCount;
    private int bandwidthFilterIndex;
    private long roundDeliveredBytes;
    private long roundLostBytes;
    private int roundMaxInFlight;
    private double recentLossRate = -1D;
    private double latestSmoothedRtt = -1D;

    private long minimumRttMillis = Long.MAX_VALUE;
    private long minimumRttTimestampMillis = -1L;
    private PathState pathState = PathState.STEADY;
    private long pathSuspicionMinimumRttMillis = Long.MAX_VALUE;
    private long pathSuspicionMaximumRttMillis = -1L;
    private long pathSuspicionDeliveredAtSend = -1L;
    private long pathSuspicionObservedAtMillis = -1L;
    private int pathSuspicionSamples;
    private long pathSuspectRttMillis = -1L;
    private long pathProbeDeadlineMillis = -1L;
    private long pathCooldownUntilMillis = -1L;
    private long pathProbeBoundaryDelivered = -1L;
    private long pathLowFlightSinceMillis = -1L;
    private long pathDrainHoldMillis;
    private double pathPreProbeCwnd;
    private int pathAttempts;
    private long pathStepMinimumRttMillis = Long.MAX_VALUE;
    private long pathStepMaximumRttMillis = -1L;
    private long pathStepDeliveredAtSend = -1L;
    private long pathStepObservedAtMillis = -1L;
    private int pathStepSamples;

    private double pacingTokens;
    private long pacingUpdatedAtMillis = -1L;

    RakModelCongestionController(int mtu) {
        this(mtu, 10L);
    }

    RakModelCongestionController(int mtu, long sendQuantumMillis) {
        this.mtu = mtu;
        this.sendQuantumMillis = Math.max(1L, sendQuantumMillis);
        // RFC 9002's initial window formula: min(10*MDS, max(2*MDS, 14720)).
        this.cwnd = Math.min(10D * mtu, Math.max(2D * mtu, 14_720D));
        this.initialCwnd = this.cwnd;
        this.minimumCwnd = 2D * mtu;
        // A finite implementation safety bound; normal control should remain at the measured BDP well below it.
        this.maximumCwnd = 4D * 1024D * 1024D;
    }

    int transmissionAllowance(long nowMillis, int bytesInFlight) {
        this.advancePathState(nowMillis);
        this.refillPacingTokens(nowMillis);
        int windowAllowance = Math.max(0, (int) (this.cwnd - bytesInFlight));
        return Math.min(windowAllowance, Math.max(0, (int) this.pacingTokens));
    }

    boolean canSend(long nowMillis, int bytesInFlight, int size) {
        return size <= this.transmissionAllowance(nowMillis, bytesInFlight);
    }

    void onPacketSent(RakDatagramPacket datagram, long nowMillis, int bytesInFlight, boolean appLimited) {
        this.refillPacingTokens(nowMillis);
        this.pacingTokens = Math.max(0D, this.pacingTokens - datagram.getSize());

        if (bytesInFlight <= datagram.getSize()) {
            this.firstSendTimeMillis = nowMillis;
            this.deliveredTimeMillis = nowMillis;
        }
        datagram.setModelSendState(this.deliveredBytes, this.deliveredTimeMillis, this.firstSendTimeMillis,
                nowMillis, bytesInFlight, appLimited);
    }

    void onUnreliablePacketSent(int size, long nowMillis) {
        this.onUnreliablePacketSent(size, nowMillis, size, false);
    }

    DatagramSample onUnreliablePacketSent(int size, long nowMillis, int bytesInFlight, boolean appLimited) {
        this.refillPacingTokens(nowMillis);
        this.pacingTokens = Math.max(0D, this.pacingTokens - size);
        if (bytesInFlight <= size) {
            this.firstSendTimeMillis = nowMillis;
            this.deliveredTimeMillis = nowMillis;
        }
        return new DatagramSample(size, this.deliveredBytes, this.deliveredTimeMillis, this.firstSendTimeMillis,
                nowMillis, bytesInFlight, appLimited);
    }

    UnreliableSendState captureUnreliableSendState() {
        return new UnreliableSendState(this.pacingTokens, this.pacingUpdatedAtMillis, this.firstSendTimeMillis,
                this.deliveredTimeMillis);
    }

    void restoreUnreliableSendState(UnreliableSendState state) {
        this.pacingTokens = state.pacingTokens;
        this.pacingUpdatedAtMillis = state.pacingUpdatedAtMillis;
        this.firstSendTimeMillis = state.firstSendTimeMillis;
        this.deliveredTimeMillis = state.deliveredTimeMillis;
    }

    void onAcknowledged(RakDatagramPacket datagram, long nowMillis, long rttSampleMillis,
                        double smoothedRttMillis, int currentBytesInFlight) {
        if (!datagram.isModelSampleValid()) {
            return;
        }
        this.onAcknowledged(datagram.getSize(), datagram.getDeliveredBytesAtSend(),
                datagram.getDeliveredTimeAtSend(), datagram.getFirstSendTime(), datagram.getModelSendTime(),
                datagram.getModelTxInFlight(), datagram.isModelAppLimited(), datagram.getRetransmissionCount() > 0,
                nowMillis, rttSampleMillis, smoothedRttMillis, currentBytesInFlight);
        datagram.clearModelSendState();
    }

    void onUnreliableAcknowledged(DatagramSample sample, long nowMillis, long rttSampleMillis,
                                  double smoothedRttMillis, int currentBytesInFlight) {
        this.onAcknowledged(sample.size, sample.deliveredBytesAtSend, sample.deliveredTimeAtSend,
                sample.firstSendTime, sample.sendTime, sample.txInFlight, sample.appLimited, false,
                nowMillis, rttSampleMillis, smoothedRttMillis, currentBytesInFlight);
    }

    private void onAcknowledged(int acknowledgedBytes, long priorDelivered, long deliveredTimeAtSend,
                                long packetFirstSendTime, long modelSendTime, int txInFlight, boolean appLimited,
                                boolean ambiguousRetransmission, long nowMillis, long rttSampleMillis,
                                double smoothedRttMillis, int currentBytesInFlight) {
        if (this.persistentCongestion) {
            // Persistent congestion discards the old path model. The first new acknowledgement starts a fresh
            // startup, rather than leaving the minimum window as a permanent cap on the replacement model.
            this.inflightLimit = Double.POSITIVE_INFINITY;
            this.persistentCongestion = false;
        }

        if (rttSampleMillis >= 0L) {
            this.updateMinimumRtt(nowMillis, Math.max(1L, rttSampleMillis), txInFlight,
                    currentBytesInFlight, priorDelivered, modelSendTime);
        }
        if (smoothedRttMillis >= 0D) {
            this.latestSmoothedRtt = smoothedRttMillis;
        }

        long ackElapsed = nowMillis - deliveredTimeAtSend;
        long sendElapsed = modelSendTime - packetFirstSendTime;
        this.deliveredBytes += acknowledgedBytes;
        this.deliveredTimeMillis = nowMillis;

        boolean newRound = priorDelivered >= this.nextRoundDelivered;
        long completedRoundDeliveredBytes = 0L;
        if (newRound) {
            completedRoundDeliveredBytes = this.roundDeliveredBytes;
            this.finishRound();
            this.roundCount++;
            this.bandwidthFilterIndex = (this.bandwidthFilterIndex + 1) % BANDWIDTH_FILTER_ROUNDS;
            this.bandwidthFilter[this.bandwidthFilterIndex] = 0D;
            this.nextRoundDelivered = this.deliveredBytes;
        }

        this.roundDeliveredBytes += acknowledgedBytes;
        this.roundMaxInFlight = Math.max(this.roundMaxInFlight, txInFlight);

        long interval = Math.max(ackElapsed, sendElapsed);
        if (!ambiguousRetransmission && interval > 0L && this.minimumRttMillis != Long.MAX_VALUE
                && interval >= this.minimumRttMillis) {
            long delivered = this.deliveredBytes - priorDelivered;
            double sample = (double) delivered / interval;
            if (Double.isFinite(sample) && sample > 0D) {
                if (!appLimited || sample >= this.maxBandwidthBytesPerMillis) {
                    this.bandwidthFilter[this.bandwidthFilterIndex] = Math.max(
                            this.bandwidthFilter[this.bandwidthFilterIndex], sample);
                    this.updateMaximumBandwidth();
                }
            }
        }

        this.updateFullBandwidth(newRound, completedRoundDeliveredBytes);
        this.updateCongestionWindow(acknowledgedBytes);
        this.firstSendTimeMillis = modelSendTime;
    }

    void onLost(RakDatagramPacket datagram, double smoothedRttMillis) {
        this.onLost(datagram.getSize(), datagram.getModelTxInFlight(), smoothedRttMillis);
    }

    void onUnreliableLost(DatagramSample sample, double smoothedRttMillis) {
        this.onLost(sample.size, sample.txInFlight, smoothedRttMillis);
    }

    private void onLost(int size, int txInFlight, double smoothedRttMillis) {
        this.roundLostBytes += size;
        this.roundMaxInFlight = Math.max(this.roundMaxInFlight, txInFlight);
        if (smoothedRttMillis >= 0D) {
            this.latestSmoothedRtt = smoothedRttMillis;
        }
    }

    void onPersistentCongestion() {
        this.cwnd = this.minimumCwnd;
        this.startup = true;
        this.fullBandwidthBytesPerMillis = 0D;
        this.fullBandwidthRounds = 0;
        this.startupCongestionEvidenceBytes = 0L;
        this.startupLostEvidenceBytes = 0L;
        this.maxBandwidthBytesPerMillis = 0D;
        for (int i = 0; i < this.bandwidthFilter.length; i++) {
            this.bandwidthFilter[i] = 0D;
        }
        this.bandwidthFilterIndex = 0;
        this.inflightLimit = this.minimumCwnd;
        this.lossFreeRounds = 0;
        this.pacingTokens = 0D;
        this.resetPathTransition();
        this.persistentCongestion = true;
    }

    private void finishRound() {
        long total = this.roundDeliveredBytes + this.roundLostBytes;
        if (total > 0L) {
            double roundLossRate = (double) this.roundLostBytes / total;
            this.recentLossRate = roundLossRate;
            double controlLossRate = roundLossRate;
            if (this.startup) {
                this.startupCongestionEvidenceBytes = saturatingAdd(
                        this.startupCongestionEvidenceBytes, total);
                this.startupLostEvidenceBytes = saturatingAdd(
                        this.startupLostEvidenceBytes, this.roundLostBytes);
                controlLossRate = (double) this.startupLostEvidenceBytes
                        / this.startupCongestionEvidenceBytes;
            }
            boolean inflatedDelay = !this.startup && this.minimumRttMillis != Long.MAX_VALUE
                    && this.latestSmoothedRtt >= 0D
                    && !this.isPathDelayResponseSuppressed()
                    && this.latestSmoothedRtt >= this.minimumRttMillis * DELAY_INFLATION_THRESHOLD;
            boolean bootstrapEvidence = !this.startup
                    || this.startupCongestionEvidenceBytes >= STARTUP_MINIMUM_ROUND_MTUS * (long) this.mtu;
            boolean congestionSignal = bootstrapEvidence && (controlLossRate >= HARD_LOSS_THRESHOLD
                    || (controlLossRate > LOSS_THRESHOLD && inflatedDelay));
            if (congestionSignal) {
                double lossBound = this.roundMaxInFlight > 0
                        ? Math.min(this.cwnd * LOSS_BETA, this.roundMaxInFlight * LOSS_BETA)
                        : this.cwnd * LOSS_BETA;
                this.cwnd = Math.max(this.minimumCwnd, lossBound);
                this.inflightLimit = this.cwnd;
                this.lossFreeRounds = 0;
                this.startup = false;
            } else if (Double.isFinite(this.inflightLimit)
                    && ++this.lossFreeRounds >= LOSS_BOUND_RELEASE_ROUNDS) {
                this.inflightLimit = Math.min(this.maximumCwnd,
                        Math.max(this.inflightLimit + this.mtu, this.inflightLimit * LOSS_BOUND_GROWTH));
                this.lossFreeRounds = 0;
            }
            if (this.startup && bootstrapEvidence) {
                // Startup can remain app-limited for an arbitrary number of small rounds. Evaluate disjoint,
                // mature evidence buckets so old clean traffic cannot dilute a later sustained loss episode.
                this.startupCongestionEvidenceBytes = 0L;
                this.startupLostEvidenceBytes = 0L;
            }
        }
        this.roundDeliveredBytes = 0L;
        this.roundLostBytes = 0L;
        this.roundMaxInFlight = 0;
    }

    private void updateMaximumBandwidth() {
        double maximum = 0D;
        for (double sample : this.bandwidthFilter) {
            maximum = Math.max(maximum, sample);
        }
        this.maxBandwidthBytesPerMillis = maximum;
    }

    private void updateMinimumRtt(long nowMillis, long rttSampleMillis, int txInFlight,
                                  int currentBytesInFlight, long deliveredAtSend, long sendTimeMillis) {
        this.advancePathState(nowMillis);
        if (this.pathAttempts > 0 && deliveredAtSend <= this.pathProbeBoundaryDelivered) {
            // Keep the completed-attempt boundary through cooldown and retry. Otherwise an ACK that arrives at
            // the timeout instant can advance the state first, then erase that attempt using stale RTT evidence.
            return;
        }
        boolean probing = this.pathState == PathState.DRAIN || this.pathState == PathState.SAMPLE;
        boolean lowFlight = txInFlight <= this.minimumCwnd && currentBytesInFlight <= this.minimumCwnd;
        boolean lowFlightRefresh = this.minimumRttTimestampMillis >= 0L
                && nowMillis - this.minimumRttTimestampMillis >= MINIMUM_RTT_WINDOW_MILLIS
                && lowFlight;
        if (this.minimumRttMillis == Long.MAX_VALUE || rttSampleMillis < this.minimumRttMillis) {
            if (probing) {
                this.restorePathProbeWindow();
            }
            this.minimumRttMillis = rttSampleMillis;
            this.minimumRttTimestampMillis = nowMillis;
            this.resetPathTransition();
            return;
        }

        long pathStepThreshold = Math.max((long) Math.ceil(this.minimumRttMillis * PATH_STEP_MULTIPLIER),
                this.minimumRttMillis + PATH_STEP_ABSOLUTE_DELTA_MILLIS);
        if (rttSampleMillis < pathStepThreshold) {
            if (probing) {
                this.restorePathProbeWindow();
            }
            if (lowFlightRefresh) {
                this.minimumRttMillis = rttSampleMillis;
                this.minimumRttTimestampMillis = nowMillis;
            }
            this.resetPathTransition();
            return;
        }

        if (this.pathState == PathState.COOLDOWN) {
            return;
        }
        if (this.pathState == PathState.STEADY) {
            if (this.pathAttempts >= PATH_MAX_ATTEMPTS) {
                return;
            }
            this.beginPathSuspicion(nowMillis, rttSampleMillis, deliveredAtSend);
            return;
        }
        if (this.pathState == PathState.SUSPECT) {
            if (!this.observePathSuspicion(nowMillis, rttSampleMillis, deliveredAtSend)) {
                this.enterPathCooldown(nowMillis, rttSampleMillis, false);
                return;
            }
            if (this.pathSuspicionSamples >= PATH_SUSPICION_CONFIRMATION_SAMPLES) {
                this.beginPathDrain(nowMillis);
            }
            return;
        }

        this.observePathCandidate(nowMillis, rttSampleMillis, txInFlight, currentBytesInFlight,
                deliveredAtSend, sendTimeMillis);
    }

    private void beginPathSuspicion(long nowMillis, long rttSampleMillis, long deliveredAtSend) {
        this.pathState = PathState.SUSPECT;
        this.pathSuspicionMinimumRttMillis = rttSampleMillis;
        this.pathSuspicionMaximumRttMillis = rttSampleMillis;
        this.pathSuspicionDeliveredAtSend = deliveredAtSend;
        this.pathSuspicionObservedAtMillis = nowMillis;
        this.pathSuspicionSamples = 1;
        this.pathSuspectRttMillis = rttSampleMillis;
        this.pathProbeDeadlineMillis = saturatingAdd(nowMillis, pathProbeTimeoutMillis(rttSampleMillis));
    }

    private boolean observePathSuspicion(long nowMillis, long rttSampleMillis, long deliveredAtSend) {
        long previousDeliveredAtSend = this.pathSuspicionDeliveredAtSend;
        long previousObservedAtMillis = this.pathSuspicionObservedAtMillis;
        long minimum = Math.min(this.pathSuspicionMinimumRttMillis, rttSampleMillis);
        long maximum = Math.max(this.pathSuspicionMaximumRttMillis, rttSampleMillis);
        if (maximum > minimum * PATH_STEP_STABILITY_MULTIPLIER) {
            return false;
        }
        if (deliveredAtSend <= previousDeliveredAtSend || nowMillis <= previousObservedAtMillis) {
            return true;
        }
        this.pathSuspicionMinimumRttMillis = minimum;
        this.pathSuspicionMaximumRttMillis = maximum;
        this.pathSuspicionDeliveredAtSend = deliveredAtSend;
        this.pathSuspicionObservedAtMillis = nowMillis;
        this.pathSuspicionSamples++;
        this.pathSuspectRttMillis = minimum;
        return true;
    }

    private void beginPathDrain(long nowMillis) {
        this.pathState = PathState.DRAIN;
        this.pathAttempts++;
        this.pathPreProbeCwnd = this.cwnd;
        this.pathProbeBoundaryDelivered = this.deliveredBytes;
        this.pathProbeDeadlineMillis = saturatingAdd(nowMillis,
                pathProbeTimeoutMillis(this.pathSuspectRttMillis));
        this.pathDrainHoldMillis = clamp(this.pathSuspectRttMillis,
                PATH_DRAIN_HOLD_MIN_MILLIS, PATH_DRAIN_HOLD_MAX_MILLIS);
        this.pathLowFlightSinceMillis = -1L;
        this.cwnd = this.minimumCwnd;
        this.resetPathSuspicion();
        this.resetPathStepCandidate();
    }

    private void observePathCandidate(long nowMillis, long rttSampleMillis, int txInFlight,
                                      int currentBytesInFlight, long deliveredAtSend, long sendTimeMillis) {
        if (txInFlight > this.minimumCwnd || currentBytesInFlight > this.minimumCwnd) {
            this.pathLowFlightSinceMillis = -1L;
            if (this.pathState == PathState.SAMPLE) {
                this.pathState = PathState.DRAIN;
                this.resetPathStepCandidate();
            }
            return;
        }
        if (this.pathLowFlightSinceMillis < 0L) {
            this.pathLowFlightSinceMillis = nowMillis;
            return;
        }
        if (sendTimeMillis < saturatingAdd(this.pathLowFlightSinceMillis, this.pathDrainHoldMillis)
                || deliveredAtSend <= this.pathProbeBoundaryDelivered) {
            return;
        }
        if (this.pathState == PathState.DRAIN) {
            this.pathState = PathState.SAMPLE;
            this.pathStepMinimumRttMillis = rttSampleMillis;
            this.pathStepMaximumRttMillis = rttSampleMillis;
            this.pathStepDeliveredAtSend = deliveredAtSend;
            this.pathStepObservedAtMillis = nowMillis;
            this.pathStepSamples = 1;
            return;
        }
        if (deliveredAtSend <= this.pathStepDeliveredAtSend || nowMillis <= this.pathStepObservedAtMillis) {
            return;
        }
        this.pathStepMinimumRttMillis = Math.min(this.pathStepMinimumRttMillis, rttSampleMillis);
        this.pathStepMaximumRttMillis = Math.max(this.pathStepMaximumRttMillis, rttSampleMillis);
        this.pathStepDeliveredAtSend = deliveredAtSend;
        this.pathStepObservedAtMillis = nowMillis;
        this.pathStepSamples++;
        if (this.pathStepMaximumRttMillis
                > this.pathStepMinimumRttMillis * PATH_STEP_STABILITY_MULTIPLIER) {
            this.failPathProbe(nowMillis);
            return;
        }
        if (this.pathStepSamples >= PATH_STEP_CONFIRMATION_SAMPLES) {
            this.acceptPathStep(nowMillis);
        }
    }

    private void acceptPathStep(long nowMillis) {
        // Sampling deliberately holds the flight at two MTUs. Restore the pre-probe safe window before restarting
        // discovery, otherwise a successfully identified high-RTT path must bootstrap from the probe floor.
        this.restorePathProbeWindow();
        this.minimumRttMillis = this.pathStepMinimumRttMillis;
        this.minimumRttTimestampMillis = nowMillis;
        // The new propagation delay changes the BDP. Retain the bandwidth seed and any loss cap, but restart
        // full-bandwidth discovery so a clean-handshake sample cannot declare the impaired path full prematurely.
        this.startup = true;
        this.fullBandwidthBytesPerMillis = 0D;
        this.fullBandwidthRounds = 0;
        this.startupCongestionEvidenceBytes = 0L;
        this.startupLostEvidenceBytes = 0L;
        this.resetPathTransition();
    }

    private void advancePathState(long nowMillis) {
        if ((this.pathState == PathState.SUSPECT || this.pathState == PathState.DRAIN
                || this.pathState == PathState.SAMPLE) && nowMillis >= this.pathProbeDeadlineMillis) {
            if (this.pathState == PathState.SUSPECT) {
                this.pathState = PathState.STEADY;
                this.resetPathSuspicion();
                this.pathProbeDeadlineMillis = -1L;
            } else {
                this.failPathProbe(nowMillis);
            }
        } else if (this.pathState == PathState.COOLDOWN && nowMillis >= this.pathCooldownUntilMillis) {
            this.pathState = PathState.STEADY;
            this.pathCooldownUntilMillis = -1L;
        }
    }

    private void failPathProbe(long nowMillis) {
        this.restorePathProbeWindow();
        this.enterPathCooldown(nowMillis, this.pathSuspectRttMillis, true);
    }

    private void restorePathProbeWindow() {
        double safeOldTarget = this.initialCwnd;
        if (this.maxBandwidthBytesPerMillis > 0D && this.minimumRttMillis != Long.MAX_VALUE) {
            safeOldTarget = Math.max(safeOldTarget, Math.min(this.maximumCwnd,
                    this.maxBandwidthBytesPerMillis * Math.max(this.minimumRttMillis, this.sendQuantumMillis)
                            * CWND_GAIN));
        }
        double restored = Math.min(this.pathPreProbeCwnd, safeOldTarget);
        restored = Math.min(restored, this.inflightLimit);
        this.cwnd = Math.max(this.minimumCwnd, restored);
    }

    private void enterPathCooldown(long nowMillis, long rttSampleMillis, boolean completedAttempt) {
        this.pathState = PathState.COOLDOWN;
        this.pathCooldownUntilMillis = saturatingAdd(nowMillis, scaledAndClamped(rttSampleMillis,
                PATH_COOLDOWN_RTT_MULTIPLIER, PATH_COOLDOWN_MIN_MILLIS, PATH_COOLDOWN_MAX_MILLIS));
        this.pathProbeDeadlineMillis = -1L;
        this.pathLowFlightSinceMillis = -1L;
        if (!completedAttempt) {
            this.pathSuspectRttMillis = rttSampleMillis;
        }
        this.resetPathSuspicion();
        this.resetPathStepCandidate();
    }

    private void resetPathSuspicion() {
        this.pathSuspicionMinimumRttMillis = Long.MAX_VALUE;
        this.pathSuspicionMaximumRttMillis = -1L;
        this.pathSuspicionDeliveredAtSend = -1L;
        this.pathSuspicionObservedAtMillis = -1L;
        this.pathSuspicionSamples = 0;
    }

    private void resetPathStepCandidate() {
        this.pathStepMinimumRttMillis = Long.MAX_VALUE;
        this.pathStepMaximumRttMillis = -1L;
        this.pathStepDeliveredAtSend = -1L;
        this.pathStepObservedAtMillis = -1L;
        this.pathStepSamples = 0;
    }

    private void resetPathTransition() {
        this.pathState = PathState.STEADY;
        this.pathSuspectRttMillis = -1L;
        this.pathProbeDeadlineMillis = -1L;
        this.pathCooldownUntilMillis = -1L;
        this.pathProbeBoundaryDelivered = -1L;
        this.pathLowFlightSinceMillis = -1L;
        this.pathDrainHoldMillis = 0L;
        this.pathPreProbeCwnd = 0D;
        this.pathAttempts = 0;
        this.resetPathSuspicion();
        this.resetPathStepCandidate();
    }

    private boolean isPathDelayResponseSuppressed() {
        return this.pathState == PathState.DRAIN || this.pathState == PathState.SAMPLE
                || (this.pathState == PathState.COOLDOWN && this.pathAttempts > 0
                && this.pathAttempts < PATH_MAX_ATTEMPTS);
    }

    private static long pathProbeTimeoutMillis(long rttMillis) {
        return scaledAndClamped(rttMillis, PATH_TIMEOUT_RTT_MULTIPLIER,
                PATH_TIMEOUT_MIN_MILLIS, PATH_TIMEOUT_MAX_MILLIS);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long scaledAndClamped(long value, long multiplier, long minimum, long maximum) {
        if (value >= (maximum + multiplier - 1L) / multiplier) {
            return maximum;
        }
        return clamp(value * multiplier, minimum, maximum);
    }

    private static long saturatingAdd(long value, long positiveIncrement) {
        return value >= Long.MAX_VALUE - positiveIncrement ? Long.MAX_VALUE : value + positiveIncrement;
    }

    private void updateFullBandwidth(boolean newRound, long completedRoundDeliveredBytes) {
        if (!this.startup || !newRound || completedRoundDeliveredBytes < STARTUP_MINIMUM_ROUND_MTUS * (long) this.mtu
                || this.maxBandwidthBytesPerMillis <= 0D) {
            return;
        }
        if (this.fullBandwidthBytesPerMillis == 0D
                || this.maxBandwidthBytesPerMillis >= this.fullBandwidthBytesPerMillis * FULL_BANDWIDTH_GROWTH) {
            this.fullBandwidthBytesPerMillis = this.maxBandwidthBytesPerMillis;
            this.fullBandwidthRounds = 0;
        } else if (++this.fullBandwidthRounds >= FULL_BANDWIDTH_ROUNDS) {
            this.startup = false;
        }
    }

    private void updateCongestionWindow(int acknowledgedBytes) {
        if (this.maxBandwidthBytesPerMillis <= 0D || this.minimumRttMillis == Long.MAX_VALUE) {
            return;
        }
        double target = Math.max(this.minimumCwnd, Math.min(this.maximumCwnd,
                this.maxBandwidthBytesPerMillis * Math.max(this.minimumRttMillis, this.sendQuantumMillis)
                        * CWND_GAIN));
        if (this.pathState == PathState.DRAIN || this.pathState == PathState.SAMPLE) {
            target = this.minimumCwnd;
        }
        target = Math.min(target, this.inflightLimit);
        if (this.cwnd < target) {
            this.cwnd = Math.min(target, this.cwnd + acknowledgedBytes);
        } else if (this.startup) {
            // Early delivery samples cover only a fraction of the initial flight. They must not collapse the
            // standards-sized initial window before a packet-timed round has measured the path.
            this.cwnd = Math.max(this.cwnd, this.minimumCwnd);
        } else if (this.cwnd > target) {
            // Avoid an abrupt model-only flight retirement; converge by at most newly delivered bytes per ACK.
            this.cwnd = Math.max(target, this.cwnd - acknowledgedBytes);
        }
        this.cwnd = Math.max(this.minimumCwnd, Math.min(this.maximumCwnd, this.cwnd));
    }

    private void refillPacingTokens(long nowMillis) {
        double burst = this.maximumBurstBytes();
        if (this.pacingUpdatedAtMillis < 0L) {
            this.pacingUpdatedAtMillis = nowMillis;
            this.pacingTokens = Math.min(burst, 2D * this.mtu);
            return;
        }
        if (nowMillis > this.pacingUpdatedAtMillis) {
            this.pacingTokens = Math.min(burst, this.pacingTokens
                    + (nowMillis - this.pacingUpdatedAtMillis) * this.pacingRateBytesPerMillis());
            this.pacingUpdatedAtMillis = nowMillis;
        } else {
            this.pacingTokens = Math.min(this.pacingTokens, burst);
        }
    }

    private double pacingRateBytesPerMillis() {
        double rate;
        if (this.maxBandwidthBytesPerMillis > 0D) {
            rate = this.maxBandwidthBytesPerMillis
                    * (this.startup ? STARTUP_PACING_GAIN : this.steadyPacingGain());
        } else {
            rate = this.cwnd / INITIAL_RTT_MILLIS * STARTUP_PACING_GAIN;
        }
        if (this.startup || this.pathState != PathState.STEADY) {
            rate = Math.max(rate, this.minimumCwnd / this.sendQuantumMillis);
        }
        return rate;
    }

    private double steadyPacingGain() {
        int phase = (int) (this.roundCount & 7L);
        if (phase == 0) {
            return PROBE_UP_PACING_GAIN;
        }
        if (phase == 1) {
            return PROBE_DOWN_PACING_GAIN;
        }
        return 1D;
    }

    private double maximumBurstBytes() {
        double pacedQuantum = this.pacingRateBytesPerMillis() * this.sendQuantumMillis + this.mtu;
        return Math.max(2D * this.mtu, Math.min(MAX_BURST_DATAGRAMS * (double) this.mtu, pacedQuantum));
    }

    SendState captureSendState(RakDatagramPacket datagram) {
        return new SendState(this.pacingTokens, this.pacingUpdatedAtMillis, this.firstSendTimeMillis,
                datagram.getDeliveredBytesAtSend(), datagram.getDeliveredTimeAtSend(), datagram.getFirstSendTime(),
                datagram.getModelSendTime(), datagram.getModelTxInFlight(), datagram.isModelSampleValid(),
                datagram.isModelAppLimited());
    }

    void restoreSendState(RakDatagramPacket datagram, SendState state) {
        this.pacingTokens = state.pacingTokens;
        this.pacingUpdatedAtMillis = state.pacingUpdatedAtMillis;
        this.firstSendTimeMillis = state.firstSendTimeMillis;
        if (state.modelSampleValid) {
            datagram.setModelSendState(state.deliveredBytesAtSend, state.deliveredTimeAtSend,
                    state.packetFirstSendTime, state.modelSendTime, state.modelTxInFlight, state.modelAppLimited);
        } else {
            datagram.clearModelSendState();
        }
    }

    double getCongestionWindow() {
        return this.cwnd;
    }

    double getMaxBandwidthBytesPerMillis() {
        return this.maxBandwidthBytesPerMillis > 0D ? this.maxBandwidthBytesPerMillis : -1D;
    }

    long getMinimumRttMillis() {
        return this.minimumRttMillis == Long.MAX_VALUE ? -1L : this.minimumRttMillis;
    }

    long getMinimumRttTimestampMillis() {
        return this.minimumRttTimestampMillis;
    }

    double getPacingRateBytesPerMillis() {
        return this.pacingRateBytesPerMillis();
    }

    boolean isStartup() {
        return this.startup;
    }

    boolean isPersistentCongestion() {
        return this.persistentCongestion;
    }

    int getPathAttempts() {
        return this.pathAttempts;
    }

    double getRecentLossRate() {
        return this.recentLossRate;
    }

    long getRoundCount() {
        return this.roundCount;
    }

    private enum PathState {
        STEADY,
        SUSPECT,
        DRAIN,
        SAMPLE,
        COOLDOWN
    }

    static final class SendState {
        private final double pacingTokens;
        private final long pacingUpdatedAtMillis;
        private final long firstSendTimeMillis;
        private final long deliveredBytesAtSend;
        private final long deliveredTimeAtSend;
        private final long packetFirstSendTime;
        private final long modelSendTime;
        private final int modelTxInFlight;
        private final boolean modelSampleValid;
        private final boolean modelAppLimited;

        private SendState(double pacingTokens, long pacingUpdatedAtMillis, long firstSendTimeMillis,
                          long deliveredBytesAtSend, long deliveredTimeAtSend, long packetFirstSendTime,
                          long modelSendTime, int modelTxInFlight, boolean modelSampleValid,
                          boolean modelAppLimited) {
            this.pacingTokens = pacingTokens;
            this.pacingUpdatedAtMillis = pacingUpdatedAtMillis;
            this.firstSendTimeMillis = firstSendTimeMillis;
            this.deliveredBytesAtSend = deliveredBytesAtSend;
            this.deliveredTimeAtSend = deliveredTimeAtSend;
            this.packetFirstSendTime = packetFirstSendTime;
            this.modelSendTime = modelSendTime;
            this.modelTxInFlight = modelTxInFlight;
            this.modelSampleValid = modelSampleValid;
            this.modelAppLimited = modelAppLimited;
        }
    }

    static final class DatagramSample {
        private final int size;
        private final long deliveredBytesAtSend;
        private final long deliveredTimeAtSend;
        private final long firstSendTime;
        private final long sendTime;
        private final int txInFlight;
        private final boolean appLimited;

        private DatagramSample(int size, long deliveredBytesAtSend, long deliveredTimeAtSend,
                               long firstSendTime, long sendTime, int txInFlight, boolean appLimited) {
            this.size = size;
            this.deliveredBytesAtSend = deliveredBytesAtSend;
            this.deliveredTimeAtSend = deliveredTimeAtSend;
            this.firstSendTime = firstSendTime;
            this.sendTime = sendTime;
            this.txInFlight = txInFlight;
            this.appLimited = appLimited;
        }

        int size() {
            return this.size;
        }

        long sendTime() {
            return this.sendTime;
        }
    }

    static final class UnreliableSendState {
        private final double pacingTokens;
        private final long pacingUpdatedAtMillis;
        private final long firstSendTimeMillis;
        private final long deliveredTimeMillis;

        private UnreliableSendState(double pacingTokens, long pacingUpdatedAtMillis, long firstSendTimeMillis,
                                    long deliveredTimeMillis) {
            this.pacingTokens = pacingTokens;
            this.pacingUpdatedAtMillis = pacingUpdatedAtMillis;
            this.firstSendTimeMillis = firstSendTimeMillis;
            this.deliveredTimeMillis = deliveredTimeMillis;
        }
    }
}
