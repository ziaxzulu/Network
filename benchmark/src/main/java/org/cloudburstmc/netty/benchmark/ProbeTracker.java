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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns probe attribution for one benchmark server session.
 *
 * <p>Sequence numbers are never reset. Only probes registered in the currently active measurement window can
 * contribute an acknowledgement or RTT sample. Warmup probes are still sent, but are deliberately unregistered.
 * A late, duplicate, or foreign acknowledgement observed during a later window is explicit spillover evidence and
 * never contaminates that window's RTT histogram.</p>
 */
final class ProbeTracker {
    private final AtomicLong nextSequence = new AtomicLong();
    private final Map<Long, PendingProbe> outstanding = new HashMap<>();
    private LatencyHistogram histogram;
    private boolean measurementActive;

    long nextSequence() {
        return this.nextSequence.getAndIncrement();
    }

    synchronized void beginMeasurement() {
        if (this.measurementActive) {
            throw new IllegalStateException("Probe measurement window is already active");
        }
        this.outstanding.clear();
        this.histogram = new LatencyHistogram();
        this.measurementActive = true;
    }

    synchronized void registerSent(long sequence, PeerStats peer, long sentNanos) {
        if (!this.measurementActive) {
            return;
        }
        PendingProbe previous = this.outstanding.put(sequence, new PendingProbe(peer, sentNanos));
        if (previous != null) {
            throw new IllegalStateException("Duplicate probe sequence " + sequence);
        }
        peer.addProbeSent();
    }

    synchronized void acknowledge(PeerStats peer, long sequence, long observedNanos) {
        if (!this.measurementActive) {
            return;
        }
        PendingProbe pending = this.outstanding.get(sequence);
        if (pending == null || pending.peer != peer) {
            peer.addProbeAckSpillover();
            return;
        }
        this.outstanding.remove(sequence);
        this.histogram.record(Math.max(0L, observedNanos - pending.sentNanos));
        peer.addProbeAcked();
    }

    synchronized LatencyHistogram.Snapshot closeAndSnapshot() {
        if (!this.measurementActive) {
            throw new IllegalStateException("Probe measurement window is not active");
        }
        this.measurementActive = false;
        this.outstanding.clear();
        LatencyHistogram.Snapshot snapshot = this.histogram.snapshot();
        this.histogram = null;
        return snapshot;
    }

    private record PendingProbe(PeerStats peer, long sentNanos) {
    }
}
