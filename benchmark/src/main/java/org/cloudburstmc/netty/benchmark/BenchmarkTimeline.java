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

import java.util.List;

public final class BenchmarkTimeline {
    public static final int SCHEMA_VERSION = 1;

    private BenchmarkTimeline() {
    }

    public sealed interface Record permits Event, Sample {
        int schemaVersion();

        String recordType();

        long sequence();

        long epochMillis();
    }

    public record Event(
            int schemaVersion,
            String recordType,
            long sequence,
            String runId,
            String scenario,
            String role,
            String recoveryMode,
            String caseName,
            String phase,
            Integer measurementWindow,
            String measurementWindowSemantics,
            String eventName,
            String eventSource,
            long epochMillis,
            Long monotonicElapsedMillis,
            Long coordinatedStartRelativeMillis,
            Long externalImpairmentRelativeMillis,
            Long externalBlackholeRelativeMillis,
            Long externalRecoveryRelativeMillis,
            ResourceSafetyPolicy resourceSafetyPolicy,
            ResourceSafetyAbort resourceSafetyAbort
    ) implements Record {
    }

    public record Sample(
            int schemaVersion,
            String recordType,
            long sequence,
            String runId,
            String scenario,
            String role,
            String recoveryMode,
            String caseName,
            String phase,
            Integer measurementWindow,
            String measurementWindowSemantics,
            long epochMillis,
            long monotonicElapsedMillis,
            Long coordinatedStartAtEpochMillis,
            Long coordinatedStartRelativeMillis,
            Long externalImpairmentAtEpochMillis,
            Long externalImpairmentRelativeMillis,
            Long externalBlackholeAtEpochMillis,
            Long externalBlackholeRelativeMillis,
            Long externalRecoveryAtEpochMillis,
            Long externalRecoveryRelativeMillis,
            ResourceSafetyPolicy resourceSafetyPolicy,
            MetricAvailability metricAvailability,
            RuntimeMetrics runtime,
            Cohort all,
            Cohort healthy,
            Cohort affected
    ) implements Record {
    }

    public record MetricAvailability(
            String usefulSendCounters,
            String usefulDeliveryCounters,
            String transportRecoveryCounters,
            String nackCounters,
            String queueCounters,
            String benchmarkManagedBlackholeCounters,
            List<String> unavailableFields
    ) {
    }

    public record RuntimeMetrics(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            Long directBufferPoolCount,
            Long directBufferPoolMemoryUsedBytes,
            Long directBufferPoolTotalCapacityBytes,
            Long nettyPooledDirectMemoryUsedBytes,
            Long residentSetSizeBytes,
            Long processCpuTimeNanos,
            Double processCpuLoad,
            EventLoopMetrics sharedEventLoops,
            List<String> unavailableFields
    ) {
    }

    public record EventLoopMetrics(
            String status,
            int eventLoopCount,
            Long totalPendingTasks,
            Long maxPendingTasks,
            Long completedSchedulingProbes,
            Integer outstandingSchedulingProbes,
            Double latestMaxSchedulingLagMillis,
            Double maxSchedulingLagMillis
    ) {
    }

    public record ResourceSafetyPolicy(
            long maxAggregateQueuedBytes,
            long maxDirectMemoryUsedBytes,
            String enforcementStatus
    ) {
    }

    public record ResourceSafetyAbort(
            List<String> reasons,
            long maxAggregateQueuedBytes,
            long maxDirectMemoryUsedBytes,
            long observedAggregateQueuedBytes,
            long observedHealthyQueuedBytes,
            long observedAffectedQueuedBytes,
            Long observedDirectMemoryUsedBytes,
            String observedDirectMemoryMetric,
            long observedAtEpochMillis,
            long observedAtMonotonicElapsedMillis
    ) {
    }

    public record Cohort(
            String name,
            int configuredPeers,
            int observedPeers,
            int openPeers,
            int activePeers,
            int disconnectedPeers,
            long disconnectEvents,
            Long usefulSentMessages,
            Long usefulSentBytes,
            Long logicalPacketsSent,
            Long usefulReceivedMessages,
            Long usefulReceivedBytes,
            Long logicalPacketsReceived,
            Long serverBytesOut,
            Long serverDatagramsOut,
            Long originalDatagrams,
            Long originalDatagramBytes,
            Long nackRetransmittedDatagrams,
            Long nackRetransmittedBytes,
            Long timeoutRetransmittedDatagrams,
            Long timeoutRetransmittedBytes,
            Long retransmittedDatagrams,
            Long retransmittedBytes,
            Long staleDatagrams,
            Long nackIn,
            Long nackOut,
            Long blackholedDatagramsIn,
            Long blackholedDatagramsOut,
            Long currentQueuedBytes,
            Long sampledQueuedBytesHighWater,
            Long maxPeerQueuedBytes,
            Long currentBytesInFlight,
            Long sampledBytesInFlightHighWater,
            Long maxPeerBytesInFlight,
            Integer retransmittedDatagramsInFlight,
            Integer maxRetransmissionAttempt,
            Long acknowledgementProgressEvents,
            Long acknowledgementProgressBytes,
            Long recoveryObservedAtEpochMillis,
            Long oldestLastAckProgressAtEpochMillis,
            Long earliestRecoveryStartedAtEpochMillis,
            Double totalCongestionWindowBytes,
            Double totalSlowStartThresholdBytes,
            Double maxSmoothedRttMillis,
            Double maxRttVarianceMillis,
            Long maxRetransmissionTimeoutMillis
    ) {
    }
}
