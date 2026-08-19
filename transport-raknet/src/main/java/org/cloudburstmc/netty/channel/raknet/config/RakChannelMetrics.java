/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet.config;

import org.cloudburstmc.netty.channel.raknet.RakState;

public interface RakChannelMetrics {

    default void bytesIn(int count) {
    }

    default void bytesOut(int count) {
    }

    default void rakDatagramsIn(int count) {
    }

    default void rakDatagramsOut(int count) {
    }

    default void encapsulatedIn(int count) {
    }

    default void encapsulatedOut(int count) {
    }

    default void rakStaleDatagrams(int count) {
    }

    default void ackIn(int count) {
    }

    default void ackOut(int count) {
    }

    default void nackOut(int count) {
    }

    default void nackIn(int count) {
    }

    default void stateChange(RakState state) {
    }

    default void queuedPacketBytes(int count) {
    }

    /**
     * Invoked for every RakNet data datagram write. Retransmission attempts start at {@code 1}; an original
     * transmission has attempt {@code 0}. The send type is a bounded dimension suitable for counters; implementations
     * can also sum {@code bytes} and histogram retransmission attempts without reconstructing them from packet events.
     *
     * @param sendType why the datagram was written
     * @param bytes encoded datagram size in bytes
     * @param retransmissionAttempt zero for an original transmission, otherwise the one-based attempt number
     * @param bytesInFlight currently tracked reliable datagram bytes, including a newly tracked original send
     */
    default void rakDatagramSent(RakDatagramSendType sendType, int bytes, int retransmissionAttempt,
                                 int bytesInFlight) {
    }

    /**
     * Invoked when an acknowledgement releases a tracked reliable datagram.
     *
     * @param bytes acknowledged datagram size in bytes
     * @param retransmissionAttempt zero when the datagram was never retransmitted, otherwise its final attempt number
     * @param observedAtMillis wall-clock time of the acknowledgement, in milliseconds since the Unix epoch
     * @param previousAckProgressAtMillis the previous progress timestamp, or {@code -1} when unavailable
     * @param recoveryStartedAtMillis the start of the current observed recovery epoch, or {@code -1} when the
     *                                sender has no observed recovery epoch active
     */
    default void rakAcknowledgementProgress(int bytes, int retransmissionAttempt, long observedAtMillis,
                                            long previousAckProgressAtMillis, long recoveryStartedAtMillis) {
    }

    /**
     * Reports a point-in-time view of sender recovery state. RTT values are {@code -1} until sampled and recovery
     * timestamps are {@code -1} when unavailable or inactive. Periodic snapshots are limited to at most one every
     * 100 milliseconds per session; recovery transitions and the terminal snapshot are reported immediately.
     *
     * @param observedAtMillis wall-clock observation time, in milliseconds since the Unix epoch
     * @param bytesInFlight currently tracked reliable datagram bytes
     * @param congestionWindow congestion window in bytes
     * @param slowStartThreshold slow-start threshold in bytes
     * @param smoothedRtt smoothed round-trip time in milliseconds, or {@code -1} until sampled
     * @param rttVariance round-trip time variation in milliseconds, or {@code -1} until sampled
     * @param retransmissionTimeout current retransmission timeout in milliseconds
     * @param retransmittedDatagramsInFlight tracked datagrams retransmitted at least once and not yet acknowledged
     * @param lastAckProgressAtMillis most recent ACK progress time in milliseconds since the Unix epoch
     * @param recoveryStartedAtMillis active recovery epoch start in milliseconds since the Unix epoch, or {@code -1}
     */
    default void rakRecoveryState(long observedAtMillis, int bytesInFlight, double congestionWindow,
                                  double slowStartThreshold, double smoothedRtt, double rttVariance,
                                  long retransmissionTimeout, int retransmittedDatagramsInFlight,
                                  long lastAckProgressAtMillis, long recoveryStartedAtMillis) {
    }

    /**
     * Invoked after the terminal recovery snapshot when the session closes. Gauge exporters should remove any
     * channel-local state retained for this session.
     *
     * @param observedAtMillis wall-clock close time, in milliseconds since the Unix epoch
     */
    default void rakRecoveryStateClosed(long observedAtMillis) {
    }
}
