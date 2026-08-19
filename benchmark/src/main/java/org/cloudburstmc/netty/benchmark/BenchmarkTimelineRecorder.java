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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class BenchmarkTimelineRecorder implements AutoCloseable {
    private final BenchmarkRunResult result;
    private final BenchmarkConfig config;
    private final String caseName;
    private final int configuredPeers;
    private final int configuredAffectedPeers;
    private final Supplier<List<PeerStats.TimelineSnapshot>> snapshots;
    private final Capabilities capabilities;
    private final boolean benchmarkManagedBlackholeCounters;
    private final String benchmarkManagedBlackholeCounterStatus;
    private final ScheduledExecutorService executor;

    private String phase = "initializing";
    private Integer measurementWindow;
    private boolean started;
    private boolean closed;
    private long allQueueHighWater;
    private long healthyQueueHighWater;
    private long affectedQueueHighWater;
    private long allBytesInFlightHighWater;
    private long healthyBytesInFlightHighWater;
    private long affectedBytesInFlightHighWater;

    BenchmarkTimelineRecorder(BenchmarkRunResult result, String caseName, int configuredPeers,
                              int configuredAffectedPeers,
                              Supplier<List<PeerStats.TimelineSnapshot>> snapshots,
                              Capabilities capabilities) {
        this(result, caseName, configuredPeers, configuredAffectedPeers, snapshots, capabilities, true);
    }

    BenchmarkTimelineRecorder(BenchmarkRunResult result, String caseName, int configuredPeers,
                              int configuredAffectedPeers,
                              Supplier<List<PeerStats.TimelineSnapshot>> snapshots,
                              Capabilities capabilities, boolean automaticSampling) {
        this.result = result;
        this.config = result.config();
        this.caseName = caseName;
        this.configuredPeers = configuredPeers;
        this.configuredAffectedPeers = Math.min(configuredPeers, Math.max(0, configuredAffectedPeers));
        this.snapshots = snapshots;
        this.capabilities = capabilities;
        if (config.externalBlackholeAtEpochMillis() > 0L) {
            this.benchmarkManagedBlackholeCounterStatus = "unavailable-for-external-qdisc";
        } else if (config.disappearingClients() <= 0) {
            this.benchmarkManagedBlackholeCounterStatus = "unavailable-not-configured";
        } else if (capabilities == Capabilities.SERVER) {
            this.benchmarkManagedBlackholeCounterStatus = "unavailable-on-server-worker";
        } else {
            this.benchmarkManagedBlackholeCounterStatus = "available";
        }
        this.benchmarkManagedBlackholeCounters = "available".equals(this.benchmarkManagedBlackholeCounterStatus);
        this.executor = automaticSampling ? Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "raknet-benchmark-timeline");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    synchronized void start() {
        if (this.started) {
            return;
        }
        this.started = true;
        addConfiguredEvent("external-impairment-scheduled", this.config.externalImpairmentAtEpochMillis());
        addConfiguredEvent("external-blackhole-scheduled", this.config.externalBlackholeAtEpochMillis());
        addConfiguredEvent("external-recovery-scheduled", this.config.externalRecoveryAtEpochMillis());
        long epochMillis = System.currentTimeMillis();
        long monotonicNanos = System.nanoTime();
        addActualEvent("timeline-started", "benchmark", epochMillis, monotonicNanos);
        captureAt(epochMillis, monotonicNanos);
        if (this.executor != null) {
            long interval = this.config.timelineSampleIntervalMillis();
            this.executor.scheduleAtFixedRate(this::safeCapture, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    synchronized void markPhase(String phase, Integer measurementWindow) {
        this.phase = phase;
        this.measurementWindow = measurementWindow;
        if (!this.started || this.closed) {
            return;
        }
        long epochMillis = System.currentTimeMillis();
        long monotonicNanos = System.nanoTime();
        addActualEvent("phase-" + phase, "benchmark", epochMillis, monotonicNanos);
        captureAt(epochMillis, monotonicNanos);
    }

    synchronized void recordEvent(String eventName, String source) {
        if (!this.started || this.closed) {
            return;
        }
        long epochMillis = System.currentTimeMillis();
        long monotonicNanos = System.nanoTime();
        addActualEvent(eventName, source, epochMillis, monotonicNanos);
        captureAt(epochMillis, monotonicNanos);
    }

    synchronized BenchmarkTimeline.Sample captureAt(long epochMillis, long monotonicNanos) {
        List<PeerStats.TimelineSnapshot> peerSnapshots = this.snapshots.get();
        MutableCohort all = new MutableCohort("all", this.configuredPeers);
        MutableCohort healthy = new MutableCohort("healthy", this.configuredPeers - this.configuredAffectedPeers);
        MutableCohort affected = new MutableCohort("affected", this.configuredAffectedPeers);
        for (PeerStats.TimelineSnapshot peer : peerSnapshots) {
            all.add(peer);
            (peer.impaired() ? affected : healthy).add(peer);
        }

        this.allQueueHighWater = Math.max(this.allQueueHighWater, all.currentQueuedBytes);
        this.healthyQueueHighWater = Math.max(this.healthyQueueHighWater, healthy.currentQueuedBytes);
        this.affectedQueueHighWater = Math.max(this.affectedQueueHighWater, affected.currentQueuedBytes);
        this.allBytesInFlightHighWater = Math.max(this.allBytesInFlightHighWater, all.currentBytesInFlight);
        this.healthyBytesInFlightHighWater = Math.max(this.healthyBytesInFlightHighWater, healthy.currentBytesInFlight);
        this.affectedBytesInFlightHighWater = Math.max(this.affectedBytesInFlightHighWater, affected.currentBytesInFlight);

        BenchmarkTimeline.Sample sample = new BenchmarkTimeline.Sample(
                BenchmarkTimeline.SCHEMA_VERSION,
                "sample",
                this.result.nextTimelineSequence(),
                this.result.runId(),
                this.config.scenario().cliName(),
                this.config.role().name().toLowerCase(),
                this.caseName,
                this.phase,
                this.measurementWindow,
                this.config.measurementWindowSemantics(),
                epochMillis,
                monotonicElapsedMillis(monotonicNanos),
                configuredEpoch(this.config.startAtEpochMillis()),
                relativeMillis(epochMillis, this.config.startAtEpochMillis()),
                configuredEpoch(this.config.externalImpairmentAtEpochMillis()),
                relativeMillis(epochMillis, this.config.externalImpairmentAtEpochMillis()),
                configuredEpoch(this.config.externalBlackholeAtEpochMillis()),
                relativeMillis(epochMillis, this.config.externalBlackholeAtEpochMillis()),
                configuredEpoch(this.config.externalRecoveryAtEpochMillis()),
                relativeMillis(epochMillis, this.config.externalRecoveryAtEpochMillis()),
                this.capabilities.availability(this.benchmarkManagedBlackholeCounterStatus),
                BenchmarkRuntimeMetrics.capture(),
                all.toSnapshot(this.capabilities, this.benchmarkManagedBlackholeCounters,
                        this.allQueueHighWater, this.allBytesInFlightHighWater),
                healthy.toSnapshot(this.capabilities, this.benchmarkManagedBlackholeCounters,
                        this.healthyQueueHighWater, this.healthyBytesInFlightHighWater),
                affected.toSnapshot(this.capabilities, this.benchmarkManagedBlackholeCounters,
                        this.affectedQueueHighWater, this.affectedBytesInFlightHighWater)
        );
        this.result.addTimelineRecord(sample);
        return sample;
    }

    private void safeCapture() {
        try {
            synchronized (this) {
                if (!this.closed) {
                    captureAt(System.currentTimeMillis(), System.nanoTime());
                }
            }
        } catch (Throwable error) {
            System.err.println("Unable to capture RakNet benchmark timeline sample: " + error);
        }
    }

    private void addConfiguredEvent(String eventName, long eventEpochMillis) {
        if (eventEpochMillis <= 0L) {
            return;
        }
        this.result.addTimelineRecord(new BenchmarkTimeline.Event(
                BenchmarkTimeline.SCHEMA_VERSION,
                "event",
                this.result.nextTimelineSequence(),
                this.result.runId(),
                this.config.scenario().cliName(),
                this.config.role().name().toLowerCase(),
                this.caseName,
                "configured",
                null,
                this.config.measurementWindowSemantics(),
                eventName,
                "worker-configuration",
                eventEpochMillis,
                null,
                relativeMillis(eventEpochMillis, this.config.startAtEpochMillis()),
                relativeMillis(eventEpochMillis, this.config.externalImpairmentAtEpochMillis()),
                relativeMillis(eventEpochMillis, this.config.externalBlackholeAtEpochMillis()),
                relativeMillis(eventEpochMillis, this.config.externalRecoveryAtEpochMillis())
        ));
    }

    private void addActualEvent(String eventName, String source, long epochMillis, long monotonicNanos) {
        this.result.addTimelineRecord(new BenchmarkTimeline.Event(
                BenchmarkTimeline.SCHEMA_VERSION,
                "event",
                this.result.nextTimelineSequence(),
                this.result.runId(),
                this.config.scenario().cliName(),
                this.config.role().name().toLowerCase(),
                this.caseName,
                this.phase,
                this.measurementWindow,
                this.config.measurementWindowSemantics(),
                eventName,
                source,
                epochMillis,
                monotonicElapsedMillis(monotonicNanos),
                relativeMillis(epochMillis, this.config.startAtEpochMillis()),
                relativeMillis(epochMillis, this.config.externalImpairmentAtEpochMillis()),
                relativeMillis(epochMillis, this.config.externalBlackholeAtEpochMillis()),
                relativeMillis(epochMillis, this.config.externalRecoveryAtEpochMillis())
        ));
    }

    private long monotonicElapsedMillis(long monotonicNanos) {
        return TimeUnit.NANOSECONDS.toMillis(monotonicNanos - this.result.timelineOriginNanos());
    }

    private static Long configuredEpoch(long epochMillis) {
        return epochMillis > 0L ? epochMillis : null;
    }

    private static Long relativeMillis(long epochMillis, long eventEpochMillis) {
        return eventEpochMillis > 0L ? epochMillis - eventEpochMillis : null;
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        if (this.started) {
            this.phase = "stopped";
            this.measurementWindow = null;
            long epochMillis = System.currentTimeMillis();
            long monotonicNanos = System.nanoTime();
            addActualEvent("timeline-stopped", "benchmark", epochMillis, monotonicNanos);
            captureAt(epochMillis, monotonicNanos);
        }
        this.closed = true;
    }

    enum Capabilities {
        LOCAL(true, true, true),
        SERVER(true, false, true),
        RECEIVER(false, true, false);

        private final boolean usefulSend;
        private final boolean usefulDelivery;
        private final boolean transport;

        Capabilities(boolean usefulSend, boolean usefulDelivery, boolean transport) {
            this.usefulSend = usefulSend;
            this.usefulDelivery = usefulDelivery;
            this.transport = transport;
        }

        BenchmarkTimeline.MetricAvailability availability(String benchmarkManagedBlackholeCounterStatus) {
            boolean benchmarkManagedBlackholeCounters = "available".equals(benchmarkManagedBlackholeCounterStatus);
            List<String> unavailable = new ArrayList<>();
            if (!this.usefulSend) {
                unavailable.add("cohort.usefulSentMessages");
                unavailable.add("cohort.usefulSentBytes");
                unavailable.add("cohort.logicalPacketsSent");
            }
            if (!this.usefulDelivery) {
                unavailable.add("cohort.usefulReceivedMessages");
                unavailable.add("cohort.usefulReceivedBytes");
                unavailable.add("cohort.logicalPacketsReceived");
            }
            if (!this.transport) {
                Collections.addAll(unavailable,
                        "cohort.serverBytesOut", "cohort.serverDatagramsOut", "cohort.retransmittedDatagrams",
                        "cohort.retransmittedBytes", "cohort.staleDatagrams", "cohort.nackIn", "cohort.nackOut",
                        "cohort.currentQueuedBytes", "cohort.currentBytesInFlight", "cohort.recoveryState");
            }
            if (!benchmarkManagedBlackholeCounters) {
                unavailable.add("cohort.blackholedDatagramsIn");
                unavailable.add("cohort.blackholedDatagramsOut");
            }
            return new BenchmarkTimeline.MetricAvailability(
                    this.usefulSend ? "available" : "unavailable-on-receiver-worker",
                    this.usefulDelivery ? "available" : "unavailable-on-server-worker",
                    this.transport ? "available" : "unavailable-on-receiver-worker",
                    this.transport ? "available" : "unavailable-on-receiver-worker",
                    this.transport ? "available" : "unavailable-on-receiver-worker",
                    benchmarkManagedBlackholeCounterStatus,
                    Collections.unmodifiableList(unavailable)
            );
        }
    }

    private static final class MutableCohort {
        private final String name;
        private final int configuredPeers;
        private int observedPeers;
        private int openPeers;
        private int activePeers;
        private int disconnectedPeers;
        private long disconnectEvents;
        private long usefulSentMessages;
        private long usefulSentBytes;
        private long logicalPacketsSent;
        private long usefulReceivedMessages;
        private long usefulReceivedBytes;
        private long logicalPacketsReceived;
        private long serverBytesOut;
        private long serverDatagramsOut;
        private long staleDatagrams;
        private long nackIn;
        private long nackOut;
        private long blackholedDatagramsIn;
        private long blackholedDatagramsOut;
        private long originalDatagrams;
        private long originalDatagramBytes;
        private long nackRetransmittedDatagrams;
        private long nackRetransmittedBytes;
        private long timeoutRetransmittedDatagrams;
        private long timeoutRetransmittedBytes;
        private long acknowledgementProgressEvents;
        private long acknowledgementProgressBytes;
        private long currentQueuedBytes;
        private long maxPeerQueuedBytes;
        private long currentBytesInFlight;
        private long maxPeerBytesInFlight;
        private int retransmittedDatagramsInFlight;
        private int maxRetransmissionAttempt;
        private long recoveryObservedAtMillis = -1L;
        private long oldestLastAckProgressAtMillis = -1L;
        private long earliestRecoveryStartedAtMillis = -1L;
        private double congestionWindow;
        private double slowStartThreshold;
        private double maxSmoothedRtt = -1.0D;
        private double maxRttVariance = -1.0D;
        private long maxRetransmissionTimeout = -1L;
        private int recoveryStatePeers;

        private MutableCohort(String name, int configuredPeers) {
            this.name = name;
            this.configuredPeers = configuredPeers;
        }

        private void add(PeerStats.TimelineSnapshot peer) {
            this.observedPeers++;
            if (peer.channelOpen()) {
                this.openPeers++;
            }
            if (peer.channelActive()) {
                this.activePeers++;
            }
            if (peer.disconnected()) {
                this.disconnectedPeers++;
            }
            this.disconnectEvents += peer.disconnectEvents();
            this.usefulSentMessages += peer.usefulSentMessages();
            this.usefulSentBytes += peer.usefulSentBytes();
            this.logicalPacketsSent += peer.logicalPacketsSent();
            this.usefulReceivedMessages += peer.usefulReceivedMessages();
            this.usefulReceivedBytes += peer.usefulReceivedBytes();
            this.logicalPacketsReceived += peer.logicalPacketsReceived();
            this.serverBytesOut += peer.serverBytesOut();
            this.serverDatagramsOut += peer.serverDatagramsOut();
            this.staleDatagrams += peer.staleDatagrams();
            this.nackIn += peer.nackIn();
            this.nackOut += peer.nackOut();
            this.blackholedDatagramsIn += peer.blackholedDatagramsIn();
            this.blackholedDatagramsOut += peer.blackholedDatagramsOut();
            this.originalDatagrams += peer.originalDatagrams();
            this.originalDatagramBytes += peer.originalDatagramBytes();
            this.nackRetransmittedDatagrams += peer.nackRetransmittedDatagrams();
            this.nackRetransmittedBytes += peer.nackRetransmittedBytes();
            this.timeoutRetransmittedDatagrams += peer.timeoutRetransmittedDatagrams();
            this.timeoutRetransmittedBytes += peer.timeoutRetransmittedBytes();
            this.acknowledgementProgressEvents += peer.acknowledgementProgressEvents();
            this.acknowledgementProgressBytes += peer.acknowledgementProgressBytes();
            this.currentQueuedBytes += peer.currentQueuedBytes();
            this.maxPeerQueuedBytes = Math.max(this.maxPeerQueuedBytes, peer.maxQueuedBytes());
            this.currentBytesInFlight += peer.bytesInFlight();
            this.maxPeerBytesInFlight = Math.max(this.maxPeerBytesInFlight, peer.maxBytesInFlight());
            this.retransmittedDatagramsInFlight += peer.retransmittedDatagramsInFlight();
            this.maxRetransmissionAttempt = Math.max(this.maxRetransmissionAttempt, peer.maxRetransmissionAttempt());
            this.recoveryObservedAtMillis = Math.max(this.recoveryObservedAtMillis, peer.recoveryObservedAtMillis());
            this.oldestLastAckProgressAtMillis = minimumEpoch(this.oldestLastAckProgressAtMillis,
                    peer.lastAckProgressAtMillis());
            this.earliestRecoveryStartedAtMillis = minimumEpoch(this.earliestRecoveryStartedAtMillis,
                    peer.recoveryStartedAtMillis());
            if (peer.recoveryObservedAtMillis() >= 0L) {
                this.recoveryStatePeers++;
                this.congestionWindow += Math.max(0.0D, peer.congestionWindow());
                this.slowStartThreshold += Math.max(0.0D, peer.slowStartThreshold());
                this.maxSmoothedRtt = Math.max(this.maxSmoothedRtt, peer.smoothedRtt());
                this.maxRttVariance = Math.max(this.maxRttVariance, peer.rttVariance());
                this.maxRetransmissionTimeout = Math.max(this.maxRetransmissionTimeout, peer.retransmissionTimeout());
            }
        }

        private BenchmarkTimeline.Cohort toSnapshot(Capabilities capabilities,
                                                    boolean benchmarkManagedBlackholeCounters,
                                                    long queueHighWater, long bytesInFlightHighWater) {
            boolean transport = capabilities.transport;
            long retransmittedDatagrams = this.nackRetransmittedDatagrams + this.timeoutRetransmittedDatagrams;
            long retransmittedBytes = this.nackRetransmittedBytes + this.timeoutRetransmittedBytes;
            boolean recoveryObserved = transport && this.recoveryStatePeers > 0;
            return new BenchmarkTimeline.Cohort(
                    this.name,
                    this.configuredPeers,
                    this.observedPeers,
                    this.openPeers,
                    this.activePeers,
                    this.disconnectedPeers,
                    this.disconnectEvents,
                    capabilities.usefulSend ? this.usefulSentMessages : null,
                    capabilities.usefulSend ? this.usefulSentBytes : null,
                    capabilities.usefulSend ? this.logicalPacketsSent : null,
                    capabilities.usefulDelivery ? this.usefulReceivedMessages : null,
                    capabilities.usefulDelivery ? this.usefulReceivedBytes : null,
                    capabilities.usefulDelivery ? this.logicalPacketsReceived : null,
                    transport ? this.serverBytesOut : null,
                    transport ? this.serverDatagramsOut : null,
                    transport ? this.originalDatagrams : null,
                    transport ? this.originalDatagramBytes : null,
                    transport ? this.nackRetransmittedDatagrams : null,
                    transport ? this.nackRetransmittedBytes : null,
                    transport ? this.timeoutRetransmittedDatagrams : null,
                    transport ? this.timeoutRetransmittedBytes : null,
                    transport ? retransmittedDatagrams : null,
                    transport ? retransmittedBytes : null,
                    transport ? this.staleDatagrams : null,
                    transport ? this.nackIn : null,
                    transport ? this.nackOut : null,
                    benchmarkManagedBlackholeCounters ? this.blackholedDatagramsIn : null,
                    benchmarkManagedBlackholeCounters ? this.blackholedDatagramsOut : null,
                    transport ? this.currentQueuedBytes : null,
                    transport ? queueHighWater : null,
                    transport ? this.maxPeerQueuedBytes : null,
                    transport ? this.currentBytesInFlight : null,
                    transport ? bytesInFlightHighWater : null,
                    transport ? this.maxPeerBytesInFlight : null,
                    transport ? this.retransmittedDatagramsInFlight : null,
                    transport ? this.maxRetransmissionAttempt : null,
                    transport ? this.acknowledgementProgressEvents : null,
                    transport ? this.acknowledgementProgressBytes : null,
                    recoveryObserved ? this.recoveryObservedAtMillis : null,
                    recoveryObserved && this.oldestLastAckProgressAtMillis >= 0L
                            ? this.oldestLastAckProgressAtMillis : null,
                    recoveryObserved && this.earliestRecoveryStartedAtMillis >= 0L
                            ? this.earliestRecoveryStartedAtMillis : null,
                    recoveryObserved ? this.congestionWindow : null,
                    recoveryObserved ? this.slowStartThreshold : null,
                    recoveryObserved && this.maxSmoothedRtt >= 0.0D ? this.maxSmoothedRtt : null,
                    recoveryObserved && this.maxRttVariance >= 0.0D ? this.maxRttVariance : null,
                    recoveryObserved && this.maxRetransmissionTimeout >= 0L ? this.maxRetransmissionTimeout : null
            );
        }

        private static long minimumEpoch(long current, long candidate) {
            if (candidate < 0L) {
                return current;
            }
            return current < 0L ? candidate : Math.min(current, candidate);
        }
    }
}
