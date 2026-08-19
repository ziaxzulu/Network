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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class BenchmarkRunResult {
    private static final ObjectMapper TIMELINE_JSON = new ObjectMapper();
    private final BenchmarkConfig config;
    private final EnvironmentInfo environment;
    private final List<BenchmarkIterationResult> iterations = new ArrayList<>();
    private final List<BenchmarkTimeline.Record> timelineRecords = new ArrayList<>();
    private final BenchmarkTimelineSummary timelineSummary = new BenchmarkTimelineSummary();
    private final AtomicLong timelineSequence = new AtomicLong();
    private final String runId;
    private final long timelineOriginEpochMillis;
    private final long timelineOriginNanos;
    private BufferedWriter timelineWriter;
    private boolean timelineStreamOpened;
    private final AtomicReference<BenchmarkTimeline.ResourceSafetyAbort> resourceSafetyAbort =
            new AtomicReference<>();

    public BenchmarkRunResult(BenchmarkConfig config, EnvironmentInfo environment) {
        this(config, environment, System.currentTimeMillis(), System.nanoTime());
    }

    BenchmarkRunResult(BenchmarkConfig config, EnvironmentInfo environment, long timelineOriginEpochMillis,
                       long timelineOriginNanos) {
        this.config = config;
        this.environment = environment;
        this.runId = config.runId() != null ? config.runId() : defaultRunId();
        this.timelineOriginEpochMillis = timelineOriginEpochMillis;
        this.timelineOriginNanos = timelineOriginNanos;
    }

    public BenchmarkConfig config() {
        return this.config;
    }

    public EnvironmentInfo environment() {
        return this.environment;
    }

    public String runId() {
        return this.runId;
    }

    public void add(BenchmarkIterationResult iteration) {
        this.iterations.add(iteration);
    }

    public List<BenchmarkIterationResult> iterations() {
        return Collections.unmodifiableList(this.iterations);
    }

    public synchronized void addTimelineRecord(BenchmarkTimeline.Record record) {
        this.timelineSummary.accept(record);
        if (!this.timelineStreamOpened) {
            this.timelineRecords.add(record);
        }
        if (this.timelineWriter != null) {
            try {
                this.timelineWriter.write(TIMELINE_JSON.writeValueAsString(record));
                this.timelineWriter.write('\n');
                this.timelineWriter.flush();
            } catch (IOException error) {
                throw new UncheckedIOException("Unable to stream benchmark timeline", error);
            }
        }
    }

    public synchronized List<BenchmarkTimeline.Record> timelineRecords() {
        return Collections.unmodifiableList(new ArrayList<>(this.timelineRecords));
    }

    BenchmarkTimelineSummary.Snapshot timelineSummary() {
        return this.timelineSummary.snapshot();
    }

    synchronized boolean timelineWasStreamed() {
        return this.timelineStreamOpened;
    }

    boolean recordResourceSafetyAbort(BenchmarkTimeline.ResourceSafetyAbort abort) {
        return this.resourceSafetyAbort.compareAndSet(null, abort);
    }

    BenchmarkTimeline.ResourceSafetyAbort resourceSafetyAbort() {
        return this.resourceSafetyAbort.get();
    }

    void throwIfResourceSafetyAborted() {
        BenchmarkTimeline.ResourceSafetyAbort abort = this.resourceSafetyAbort.get();
        if (abort != null) {
            throw new BenchmarkResourceSafetyException(this, abort);
        }
    }

    long nextTimelineSequence() {
        return this.timelineSequence.getAndIncrement();
    }

    long timelineOriginEpochMillis() {
        return this.timelineOriginEpochMillis;
    }

    long timelineOriginNanos() {
        return this.timelineOriginNanos;
    }

    synchronized void openTimelineStream() throws IOException {
        if (this.timelineWriter != null) {
            return;
        }
        File directory = outputDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create benchmark output directory: " + directory);
        }
        File timeline = new File(directory, "timeline.jsonl");
        this.timelineWriter = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(timeline), StandardCharsets.UTF_8));
        this.timelineStreamOpened = true;
    }

    synchronized void closeTimelineStream() throws IOException {
        if (this.timelineWriter == null) {
            return;
        }
        try {
            this.timelineWriter.close();
        } finally {
            this.timelineWriter = null;
        }
    }

    public File outputDirectory() {
        return new File(this.config.outputRoot(), this.runId);
    }

    private static String defaultRunId() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
}
