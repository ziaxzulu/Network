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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class BenchmarkRunResult {
    private final BenchmarkConfig config;
    private final EnvironmentInfo environment;
    private final List<BenchmarkIterationResult> iterations = new ArrayList<>();
    private final String runId;

    public BenchmarkRunResult(BenchmarkConfig config, EnvironmentInfo environment) {
        this.config = config;
        this.environment = environment;
        this.runId = config.runId() != null ? config.runId() : defaultRunId();
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

    public File outputDirectory() {
        return new File(this.config.outputRoot(), this.runId);
    }

    private static String defaultRunId() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
}
