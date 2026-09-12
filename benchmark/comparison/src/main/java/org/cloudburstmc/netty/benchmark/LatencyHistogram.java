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

public final class LatencyHistogram {
    private final List<Long> samplesNanos = new ArrayList<>();

    public synchronized void record(long nanos) {
        if (nanos >= 0) {
            this.samplesNanos.add(nanos);
        }
    }

    public synchronized void clear() {
        this.samplesNanos.clear();
    }

    public synchronized Snapshot snapshot() {
        List<Long> copy = new ArrayList<>(this.samplesNanos);
        Collections.sort(copy);
        return new Snapshot(copy);
    }

    public static final class Snapshot {
        private final List<Long> sortedNanos;

        private Snapshot(List<Long> sortedNanos) {
            this.sortedNanos = sortedNanos;
        }

        public int count() {
            return this.sortedNanos.size();
        }

        public double percentileMillis(double percentile) {
            if (this.sortedNanos.isEmpty()) {
                return 0.0D;
            }
            double bounded = Math.max(0.0D, Math.min(100.0D, percentile));
            int index = (int) Math.ceil((bounded / 100.0D) * this.sortedNanos.size()) - 1;
            index = Math.max(0, Math.min(this.sortedNanos.size() - 1, index));
            return nanosToMillis(this.sortedNanos.get(index));
        }

        public double maxMillis() {
            if (this.sortedNanos.isEmpty()) {
                return 0.0D;
            }
            return nanosToMillis(this.sortedNanos.get(this.sortedNanos.size() - 1));
        }

        public List<Long> sortedNanos() {
            return Collections.unmodifiableList(this.sortedNanos);
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0D;
        }
    }
}
