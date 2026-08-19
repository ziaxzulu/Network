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

/**
 * Constant-space timeline summary used by long-running and failure-prone runs.
 */
final class BenchmarkTimelineSummary {
    private int eventCount;
    private int sampleCount;
    private Long firstSampleEpochMillis;
    private Long lastSampleEpochMillis;
    private Long maxSampledAllCurrentQueuedBytes;
    private Long maxSampledHealthyCurrentQueuedBytes;
    private Long maxSampledAffectedCurrentQueuedBytes;
    private Long maxAllSampledQueuedBytesHighWater;
    private Long maxHeapUsedBytes;
    private Long maxDirectBufferPoolMemoryUsedBytes;
    private Long maxNettyPooledDirectMemoryUsedBytes;
    private Long maxResidentSetSizeBytes;
    private Double maxProcessCpuLoad;
    private BenchmarkTimeline.Sample lastSample;

    synchronized void accept(BenchmarkTimeline.Record record) {
        if (record instanceof BenchmarkTimeline.Event) {
            this.eventCount++;
            return;
        }
        BenchmarkTimeline.Sample sample = (BenchmarkTimeline.Sample) record;
        this.sampleCount++;
        this.firstSampleEpochMillis = minimum(this.firstSampleEpochMillis, sample.epochMillis());
        this.lastSampleEpochMillis = maximum(this.lastSampleEpochMillis, sample.epochMillis());
        this.maxSampledAllCurrentQueuedBytes = maximum(
                this.maxSampledAllCurrentQueuedBytes, sample.all().currentQueuedBytes());
        this.maxSampledHealthyCurrentQueuedBytes = maximum(
                this.maxSampledHealthyCurrentQueuedBytes, sample.healthy().currentQueuedBytes());
        this.maxSampledAffectedCurrentQueuedBytes = maximum(
                this.maxSampledAffectedCurrentQueuedBytes, sample.affected().currentQueuedBytes());
        this.maxAllSampledQueuedBytesHighWater = maximum(
                this.maxAllSampledQueuedBytesHighWater, sample.all().sampledQueuedBytesHighWater());
        this.maxHeapUsedBytes = maximum(this.maxHeapUsedBytes, sample.runtime().heapUsedBytes());
        this.maxDirectBufferPoolMemoryUsedBytes = maximum(
                this.maxDirectBufferPoolMemoryUsedBytes, sample.runtime().directBufferPoolMemoryUsedBytes());
        this.maxNettyPooledDirectMemoryUsedBytes = maximum(
                this.maxNettyPooledDirectMemoryUsedBytes, sample.runtime().nettyPooledDirectMemoryUsedBytes());
        this.maxResidentSetSizeBytes = maximum(
                this.maxResidentSetSizeBytes, sample.runtime().residentSetSizeBytes());
        this.maxProcessCpuLoad = maximum(this.maxProcessCpuLoad, sample.runtime().processCpuLoad());
        if (this.lastSample == null || sample.sequence() > this.lastSample.sequence()) {
            this.lastSample = sample;
        }
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                this.eventCount,
                this.sampleCount,
                this.firstSampleEpochMillis,
                this.lastSampleEpochMillis,
                this.maxSampledAllCurrentQueuedBytes,
                this.maxSampledHealthyCurrentQueuedBytes,
                this.maxSampledAffectedCurrentQueuedBytes,
                this.maxAllSampledQueuedBytesHighWater,
                this.maxHeapUsedBytes,
                this.maxDirectBufferPoolMemoryUsedBytes,
                this.maxNettyPooledDirectMemoryUsedBytes,
                this.maxResidentSetSizeBytes,
                this.maxProcessCpuLoad,
                this.lastSample == null ? null : this.lastSample.all(),
                this.lastSample == null ? null : this.lastSample.healthy(),
                this.lastSample == null ? null : this.lastSample.affected()
        );
    }

    private static Long minimum(Long current, long candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Long maximum(Long current, Long candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.max(current, candidate);
    }

    private static Double maximum(Double current, Double candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Math.max(current, candidate);
    }

    record Snapshot(
            int eventCount,
            int sampleCount,
            Long firstSampleEpochMillis,
            Long lastSampleEpochMillis,
            Long maxSampledAllCurrentQueuedBytes,
            Long maxSampledHealthyCurrentQueuedBytes,
            Long maxSampledAffectedCurrentQueuedBytes,
            Long maxAllSampledQueuedBytesHighWater,
            Long maxHeapUsedBytes,
            Long maxDirectBufferPoolMemoryUsedBytes,
            Long maxNettyPooledDirectMemoryUsedBytes,
            Long maxResidentSetSizeBytes,
            Double maxProcessCpuLoad,
            BenchmarkTimeline.Cohort finalAll,
            BenchmarkTimeline.Cohort finalHealthy,
            BenchmarkTimeline.Cohort finalAffected
    ) {
    }
}
