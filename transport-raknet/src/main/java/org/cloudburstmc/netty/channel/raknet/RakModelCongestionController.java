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
    private static final long PACING_QUANTUM_MILLIS = 10L;
    private static final long MINIMUM_RTT_WINDOW_MILLIS = 10_000L;
    private static final double PATH_STEP_MULTIPLIER = 4.0D;
    private static final long PATH_STEP_ABSOLUTE_DELTA_MILLIS = 50L;
    private static final int PATH_SUSPICION_CONFIRMATION_SAMPLES = 2;
    private static final long PATH_SUSPICION_MAX_ROUNDS = 2L;
    private static final long PATH_SUSPICION_COOLDOWN_ROUNDS = 8L;
    private static final long PATH_PROBE_MAX_ROUNDS = 4L;
    private static final int PATH_STEP_CONFIRMATION_SAMPLES = 2;
    private static final double PATH_STEP_STABILITY_MULTIPLIER = 1.25D;
    private static final int LOSS_BOUND_RELEASE_ROUNDS = 3;
    private static final double LOSS_BOUND_GROWTH = 1.05D;

    private final int mtu;
    private final double minimumCwnd;
    private final double maximumCwnd;
    private final double[] bandwidthFilter = new double[BANDWIDTH_FILTER_ROUNDS];

    private double cwnd;
    private double maxBandwidthBytesPerMillis;
    private double fullBandwidthBytesPerMillis;
    private int fullBandwidthRounds;
    private boolean startup = true;
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
    private boolean pathProbeActive;
    private long pathSuspicionMinimumRttMillis = Long.MAX_VALUE;
    private long pathSuspicionMaximumRttMillis = -1L;
    private long pathSuspicionDeliveredAtSend = -1L;
    private long pathSuspicionObservedAtMillis = -1L;
    private long pathSuspicionStartedRound = -1L;
    private long pathSuspicionCooldownUntilRound = -1L;
    private int pathSuspicionSamples;
    private long pathProbeStartedRound = -1L;
    private long pathStepStartedAtMillis = -1L;
    private long pathStepMinimumRttMillis = Long.MAX_VALUE;
    private long pathStepMaximumRttMillis = -1L;
    private int pathStepSamples;

    private double pacingTokens;
    private long pacingUpdatedAtMillis = -1L;

    RakModelCongestionController(int mtu) {
        this.mtu = mtu;
        // RFC 9002's initial window formula: min(10*MDS, max(2*MDS, 14720)).
        this.cwnd = Math.min(10D * mtu, Math.max(2D * mtu, 14_720D));
        this.minimumCwnd = 2D * mtu;
        // A finite implementation safety bound; normal control should remain at the measured BDP well below it.
        this.maximumCwnd = 4D * 1024D * 1024D;
    }

    int transmissionAllowance(long nowMillis, int bytesInFlight) {
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
            int intervalInFlight = Math.max(txInFlight, currentBytesInFlight);
            this.updateMinimumRtt(nowMillis, Math.max(1L, rttSampleMillis), intervalInFlight,
                    priorDelivered);
        }
        if (smoothedRttMillis >= 0D) {
            this.latestSmoothedRtt = smoothedRttMillis;
        }

        long ackElapsed = nowMillis - deliveredTimeAtSend;
        long sendElapsed = modelSendTime - packetFirstSendTime;
        this.deliveredBytes += acknowledgedBytes;
        this.deliveredTimeMillis = nowMillis;

        boolean newRound = priorDelivered >= this.nextRoundDelivered;
        if (newRound) {
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

        this.updateFullBandwidth(newRound);
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
        this.maxBandwidthBytesPerMillis = 0D;
        for (int i = 0; i < this.bandwidthFilter.length; i++) {
            this.bandwidthFilter[i] = 0D;
        }
        this.bandwidthFilterIndex = 0;
        this.inflightLimit = this.minimumCwnd;
        this.lossFreeRounds = 0;
        this.pacingTokens = 0D;
        this.pathProbeActive = false;
        this.pathProbeStartedRound = -1L;
        this.pathSuspicionCooldownUntilRound = -1L;
        this.resetPathSuspicion();
        this.resetPathStepCandidate();
        this.persistentCongestion = true;
    }

    private void finishRound() {
        long total = this.roundDeliveredBytes + this.roundLostBytes;
        if (total > 0L) {
            double lossRate = (double) this.roundLostBytes / total;
            this.recentLossRate = lossRate;
            boolean inflatedDelay = this.minimumRttMillis != Long.MAX_VALUE && this.latestSmoothedRtt >= 0D
                    && !this.pathProbeActive && !this.isPathSuspicionProtected()
                    && this.latestSmoothedRtt >= this.minimumRttMillis * DELAY_INFLATION_THRESHOLD;
            boolean congestionSignal = lossRate >= HARD_LOSS_THRESHOLD
                    || (lossRate > LOSS_THRESHOLD && inflatedDelay);
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

    private void updateMinimumRtt(long nowMillis, long rttSampleMillis, int sampleInFlight,
                                  long deliveredAtSend) {
        boolean lowFlightRefresh = this.minimumRttTimestampMillis >= 0L
                && nowMillis - this.minimumRttTimestampMillis >= MINIMUM_RTT_WINDOW_MILLIS
                && sampleInFlight <= this.minimumCwnd;
        if (this.minimumRttMillis == Long.MAX_VALUE || rttSampleMillis < this.minimumRttMillis
                || lowFlightRefresh) {
            this.minimumRttMillis = rttSampleMillis;
            this.minimumRttTimestampMillis = nowMillis;
            this.pathProbeActive = false;
            this.pathProbeStartedRound = -1L;
            this.pathSuspicionCooldownUntilRound = -1L;
            this.resetPathSuspicion();
            this.resetPathStepCandidate();
            return;
        }

        long pathStepThreshold = Math.max((long) Math.ceil(this.minimumRttMillis * PATH_STEP_MULTIPLIER),
                this.minimumRttMillis + PATH_STEP_ABSOLUTE_DELTA_MILLIS);
        if (rttSampleMillis < pathStepThreshold) {
            this.pathProbeActive = false;
            this.pathProbeStartedRound = -1L;
            this.pathSuspicionCooldownUntilRound = -1L;
            this.resetPathSuspicion();
            this.resetPathStepCandidate();
            return;
        }

        // An upward path step and a standing queue are indistinguishable at full flight. Drain this sender to the
        // minimum window, suppress delay-qualified (but never hard) loss response during that bounded probe, and
        // accept the higher baseline only from an interval that stayed low-flight at both send and ACK time.
        if (!this.pathProbeActive) {
            if (!this.observePathSuspicion(nowMillis, rttSampleMillis, deliveredAtSend)) {
                return;
            }
            boolean laterFlightConfirmed = this.pathSuspicionDeliveredAtSend >= 0L
                    && deliveredAtSend > this.pathSuspicionDeliveredAtSend;
            boolean laterObservationConfirmed = nowMillis > this.pathSuspicionObservedAtMillis;
            if (this.pathSuspicionSamples < PATH_SUSPICION_CONFIRMATION_SAMPLES
                    || !laterFlightConfirmed || !laterObservationConfirmed) {
                return;
            }
            this.pathProbeActive = true;
            this.pathProbeStartedRound = this.roundCount;
            this.cwnd = this.minimumCwnd;
            this.resetPathSuspicion();
        } else if (this.roundCount - this.pathProbeStartedRound > PATH_PROBE_MAX_ROUNDS) {
            this.failPathTransition();
            return;
        }
        if (sampleInFlight > this.minimumCwnd) {
            this.resetPathStepCandidate();
            return;
        }

        if (this.pathStepStartedAtMillis == -1L) {
            this.pathStepStartedAtMillis = nowMillis;
            this.pathStepMinimumRttMillis = rttSampleMillis;
            this.pathStepMaximumRttMillis = rttSampleMillis;
            this.pathStepSamples = 1;
            return;
        }

        this.pathStepMinimumRttMillis = Math.min(this.pathStepMinimumRttMillis, rttSampleMillis);
        this.pathStepMaximumRttMillis = Math.max(this.pathStepMaximumRttMillis, rttSampleMillis);
        this.pathStepSamples++;
        if (this.pathStepSamples >= PATH_STEP_CONFIRMATION_SAMPLES
                && this.pathStepMaximumRttMillis
                <= this.pathStepMinimumRttMillis * PATH_STEP_STABILITY_MULTIPLIER) {
            this.minimumRttMillis = this.pathStepMinimumRttMillis;
            this.minimumRttTimestampMillis = nowMillis;
            this.pathProbeActive = false;
            this.pathProbeStartedRound = -1L;
            this.pathSuspicionCooldownUntilRound = -1L;
            this.resetPathSuspicion();
            this.resetPathStepCandidate();
        }
    }

    private boolean observePathSuspicion(long nowMillis, long rttSampleMillis, long deliveredAtSend) {
        if (this.roundCount < this.pathSuspicionCooldownUntilRound) {
            return false;
        }
        if (this.pathSuspicionSamples == 0) {
            this.pathSuspicionMinimumRttMillis = rttSampleMillis;
            this.pathSuspicionMaximumRttMillis = rttSampleMillis;
            this.pathSuspicionDeliveredAtSend = deliveredAtSend;
            this.pathSuspicionObservedAtMillis = nowMillis;
            this.pathSuspicionStartedRound = this.roundCount;
            this.pathSuspicionSamples = 1;
            return true;
        }
        if (this.roundCount - this.pathSuspicionStartedRound > PATH_SUSPICION_MAX_ROUNDS) {
            this.failPathTransition();
            return false;
        }
        long minimum = Math.min(this.pathSuspicionMinimumRttMillis, rttSampleMillis);
        long maximum = Math.max(this.pathSuspicionMaximumRttMillis, rttSampleMillis);
        if (maximum > minimum * PATH_STEP_STABILITY_MULTIPLIER) {
            this.failPathTransition();
            return false;
        }
        this.pathSuspicionMinimumRttMillis = minimum;
        this.pathSuspicionMaximumRttMillis = maximum;
        this.pathSuspicionSamples++;
        return true;
    }

    private boolean isPathSuspicionProtected() {
        return this.pathSuspicionSamples > 0
                && this.roundCount - this.pathSuspicionStartedRound <= PATH_SUSPICION_MAX_ROUNDS;
    }

    private void failPathTransition() {
        this.pathProbeActive = false;
        this.pathProbeStartedRound = -1L;
        this.resetPathSuspicion();
        this.resetPathStepCandidate();
        this.pathSuspicionCooldownUntilRound = this.roundCount + PATH_SUSPICION_COOLDOWN_ROUNDS;
    }

    private void resetPathSuspicion() {
        this.pathSuspicionMinimumRttMillis = Long.MAX_VALUE;
        this.pathSuspicionMaximumRttMillis = -1L;
        this.pathSuspicionDeliveredAtSend = -1L;
        this.pathSuspicionObservedAtMillis = -1L;
        this.pathSuspicionStartedRound = -1L;
        this.pathSuspicionSamples = 0;
    }

    private void resetPathStepCandidate() {
        this.pathStepStartedAtMillis = -1L;
        this.pathStepMinimumRttMillis = Long.MAX_VALUE;
        this.pathStepMaximumRttMillis = -1L;
        this.pathStepSamples = 0;
    }

    private void updateFullBandwidth(boolean newRound) {
        if (!this.startup || !newRound || this.maxBandwidthBytesPerMillis <= 0D) {
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
                this.maxBandwidthBytesPerMillis * this.minimumRttMillis * CWND_GAIN));
        if (this.pathProbeActive) {
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
        if (this.maxBandwidthBytesPerMillis > 0D) {
            return this.maxBandwidthBytesPerMillis
                    * (this.startup ? STARTUP_PACING_GAIN : this.steadyPacingGain());
        }
        return this.cwnd / INITIAL_RTT_MILLIS * STARTUP_PACING_GAIN;
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
        double pacedQuantum = this.pacingRateBytesPerMillis() * PACING_QUANTUM_MILLIS + this.mtu;
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

    double getRecentLossRate() {
        return this.recentLossRate;
    }

    long getRoundCount() {
        return this.roundCount;
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
