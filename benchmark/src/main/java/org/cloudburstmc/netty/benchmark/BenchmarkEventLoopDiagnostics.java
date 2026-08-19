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

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead, non-blocking diagnostics for the server's shared Netty event loops.
 * At most one scheduling probe may be outstanding per event loop, so a stalled loop
 * cannot cause the telemetry itself to build an unbounded task backlog.
 */
final class BenchmarkEventLoopDiagnostics {
    private final List<LoopState> loops;
    private final boolean available;
    private final AtomicLong maxLagNanos = new AtomicLong(-1L);

    BenchmarkEventLoopDiagnostics(EventLoopGroup group) {
        List<LoopState> discovered = new ArrayList<>();
        boolean supported = true;
        for (EventExecutor executor : group) {
            if (executor instanceof SingleThreadEventExecutor singleThread) {
                discovered.add(new LoopState(singleThread));
            } else {
                supported = false;
            }
        }
        this.loops = List.copyOf(discovered);
        this.available = supported && !discovered.isEmpty();
    }

    BenchmarkTimeline.EventLoopMetrics capture() {
        if (!this.available) {
            return new BenchmarkTimeline.EventLoopMetrics(
                    "unavailable-unsupported-event-executor", this.loops.size(),
                    null, null, null, null, null, null);
        }

        long totalPending = 0L;
        long maxPending = 0L;
        long completed = 0L;
        int outstanding = 0;
        long latestMaxLag = -1L;
        for (LoopState loop : this.loops) {
            long pending = loop.executor.pendingTasks();
            totalPending += pending;
            maxPending = Math.max(maxPending, pending);
            completed += loop.completedProbes.get();
            latestMaxLag = Math.max(latestMaxLag, loop.latestLagNanos.get());
            if (loop.outstanding.get()) {
                outstanding++;
            }
        }

        long submittedAt = System.nanoTime();
        for (LoopState loop : this.loops) {
            if (!loop.outstanding.compareAndSet(false, true)) {
                continue;
            }
            try {
                loop.executor.execute(() -> {
                    long lag = Math.max(0L, System.nanoTime() - submittedAt);
                    loop.latestLagNanos.set(lag);
                    loop.completedProbes.incrementAndGet();
                    updateMaximum(this.maxLagNanos, lag);
                    loop.outstanding.set(false);
                });
            } catch (RuntimeException error) {
                loop.outstanding.set(false);
            }
        }

        long highWater = this.maxLagNanos.get();
        return new BenchmarkTimeline.EventLoopMetrics(
                "available",
                this.loops.size(),
                totalPending,
                maxPending,
                completed,
                outstanding,
                millis(latestMaxLag),
                millis(highWater)
        );
    }

    private static Double millis(long nanos) {
        return nanos < 0L ? null : nanos / (double) TimeUnit.MILLISECONDS.toNanos(1L);
    }

    private static void updateMaximum(AtomicLong maximum, long candidate) {
        long current;
        do {
            current = maximum.get();
            if (current >= candidate) {
                return;
            }
        } while (!maximum.compareAndSet(current, candidate));
    }

    private static final class LoopState {
        private final SingleThreadEventExecutor executor;
        private final AtomicBoolean outstanding = new AtomicBoolean();
        private final AtomicLong latestLagNanos = new AtomicLong(-1L);
        private final AtomicLong completedProbes = new AtomicLong();

        private LoopState(SingleThreadEventExecutor executor) {
            this.executor = executor;
        }
    }
}
