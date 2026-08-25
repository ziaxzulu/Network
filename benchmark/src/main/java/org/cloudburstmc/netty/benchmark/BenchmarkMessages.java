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

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.util.Objects;

/**
 * Defines the transport semantics of benchmark workload and telemetry messages.
 *
 * <p>Probe traffic uses the same reliable-ordered recovery path as latency-sensitive game traffic and the regular
 * high-priority scheduler rather than an immediate bypass. Its RTT therefore includes ordering and loss recovery,
 * while weighted priority scheduling keeps probes timely without starving the normal-priority workload.</p>
 */
final class BenchmarkMessages {
    static final RakReliability PROBE_RELIABILITY = RakReliability.RELIABLE_ORDERED;
    static final RakPriority PROBE_PRIORITY = RakPriority.HIGH;

    private BenchmarkMessages() {
    }

    static RakMessage bulk(ByteBuf payload, RakReliability workloadReliability) {
        return workload(payload, workloadReliability);
    }

    static RakMessage batch(ByteBuf payload, RakReliability workloadReliability) {
        return workload(payload, workloadReliability);
    }

    static RakMessage probe(ByteBuf payload) {
        return telemetry(payload);
    }

    static RakMessage probeAck(ByteBuf payload) {
        return telemetry(payload);
    }

    static String probeSemantics() {
        return PROBE_RELIABILITY.name() + "/" + PROBE_PRIORITY.name()
                + " through the weighted scheduler; RTT includes ordering and loss recovery";
    }

    private static RakMessage workload(ByteBuf payload, RakReliability workloadReliability) {
        return new RakMessage(payload, Objects.requireNonNull(workloadReliability, "workloadReliability"),
                RakPriority.NORMAL);
    }

    private static RakMessage telemetry(ByteBuf payload) {
        return new RakMessage(payload, PROBE_RELIABILITY, PROBE_PRIORITY);
    }
}
