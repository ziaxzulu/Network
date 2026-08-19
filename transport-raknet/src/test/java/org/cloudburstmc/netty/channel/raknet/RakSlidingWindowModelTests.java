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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.netty.channel.raknet.config.RakRecoveryMode;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

public class RakSlidingWindowModelTests {
    private static final int MTU = 1_200;

    @Test
    public void ackCompressedFlightCannotSampleFasterThanItWasSent() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> flight = new ArrayList<>();
        int flightBytes = 0;
        try {
            for (int i = 0; i < 10; i++) {
                RakDatagramPacket datagram = datagram(1_000);
                datagram.setSequenceIndex(i);
                datagram.setSendOrdinal(i);
                datagram.setSendTime(100L + i);
                window.onReliableSend(datagram);
                flight.add(datagram);
                flightBytes += datagram.getSize();
            }

            for (int i = 0; i < flight.size(); i++) {
                window.onAck(300L, flight.get(i), i + 1L);
            }

            double maximumPossibleRate = (double) flightBytes / (300L - 109L);
            Assertions.assertTrue(window.getModelBandwidthBytesPerMillis() > 0D);
            Assertions.assertTrue(window.getModelBandwidthBytesPerMillis() <= maximumPossibleRate,
                    "one-timestamp ACK compression must be capped by the flight's send/delivery interval");
            Assertions.assertEquals(191L, window.getModelMinimumRttMillis());
        } finally {
            for (RakDatagramPacket datagram : flight) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void twoHundredRoundFivePercentLossWithRetriesDoesNotDecayOrDiverge() {
        double sum = 0D;
        double sumSquares = 0D;
        for (int lossPhase = 0; lossPhase < 5; lossPhase++) {
            SimulationResult result = simulatePeriodicLoss(lossPhase);
            Assertions.assertTrue(result.measuredMbps >= 3.5D,
                    () -> "model prototype did not fill the target BDP: " + result.measuredMbps + " Mbps");
            Assertions.assertTrue(result.finalCwnd >= 93_000D,
                    "the model must learn at least the target path BDP instead of a static floor");
            Assertions.assertTrue(result.maxBytesSentInTick <= 8 * MTU,
                    "event-loop delay must not release an unbounded catch-up burst");
            Assertions.assertTrue(result.rounds >= 200L,
                    "the retrying simulation must outlive the ten-round bandwidth filter many times");
            Assertions.assertTrue(result.retransmissions > 0,
                    "the shared pacer must carry actual physical retry traffic");
            Assertions.assertTrue(result.maxPendingRetries < 100,
                    "bounded recovery must keep up with deterministic five-percent loss");
            sum += result.measuredMbps;
            sumSquares += result.measuredMbps * result.measuredMbps;
        }
        double fairness = sum * sum / (5D * sumSquares);
        Assertions.assertTrue(fairness >= 0.99D,
                () -> "loss phase must not lock otherwise identical peers into divergent windows: " + fairness);
    }

    @Test
    public void longMixedReliableUnreliableTrafficDoesNotUnderestimateSharedPacer() {
        double sum = 0D;
        double sumSquares = 0D;
        int retransmissions = 0;
        for (int lossPhase = 0; lossPhase < 5; lossPhase++) {
            SimulationResult result = simulateMixedTraffic(lossPhase);
            Assertions.assertTrue(result.measuredMbps >= 3.5D,
                    () -> "mixed traffic decayed below target: " + result.measuredMbps + " Mbps");
            Assertions.assertTrue(result.finalCwnd >= 93_000D);
            Assertions.assertTrue(result.rounds >= 200L);
            Assertions.assertTrue(result.maxPendingRetries < 100);
            Assertions.assertTrue(result.maxBytesSentInTick <= 8 * MTU);
            sum += result.measuredMbps;
            sumSquares += result.measuredMbps * result.measuredMbps;
            retransmissions += result.retransmissions;
        }
        Assertions.assertTrue(retransmissions > 0,
                "at least one loss phase must exercise reliable retries beside unreliable samples");
        double fairness = sum * sum / (5D * sumSquares);
        Assertions.assertTrue(fairness >= 0.99D,
                () -> "mixed traffic loss phase must not create controller divergence: " + fairness);
    }

    private static SimulationResult simulateMixedTraffic(int lossPhase) {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        PriorityQueue<MixedDelivery> deliveries = new PriorityQueue<>(Comparator.comparingLong(value -> value.at));
        Queue<RakDatagramPacket> pendingRetries = new ArrayDeque<>();
        long offeredCredit = 0L;
        long deliveredMeasurementBytes = 0L;
        long sendOrdinal = 0L;
        int physicalDatagrams = 0;
        int logicalDatagrams = 0;
        int retransmissions = 0;
        int maxPendingRetries = 0;
        int maxBytesSentInTick = 0;
        final int payloadBytes = 1_000;
        final int unreliableDatagramBytes = 1_011;
        final int pathRttMillis = 213;
        final long offeredBytesPerSecond = 625_000L;
        final long measurementStartMillis = 5_000L;
        final long measurementEndMillis = 50_000L;

        try {
            for (long now = 0L; now <= measurementEndMillis + pathRttMillis; now++) {
                while (!deliveries.isEmpty() && deliveries.peek().at <= now) {
                    MixedDelivery delivery = deliveries.poll();
                    if (delivery.lost) {
                        if (delivery.datagram != null) {
                            window.onBoundedLoss(delivery.datagram, sendOrdinal - 1L);
                            pendingRetries.offer(delivery.datagram);
                        } else {
                            window.onUnreliableLoss(delivery.sample);
                        }
                    } else {
                        if (delivery.datagram != null) {
                            window.onAck(now, delivery.datagram, sendOrdinal);
                            releaseIfNeeded(delivery.datagram);
                        } else {
                            window.onUnreliableAck(delivery.sample, now);
                        }
                        if (now >= measurementStartMillis && now < measurementEndMillis) {
                            deliveredMeasurementBytes += payloadBytes;
                        }
                    }
                }
                if (now >= measurementEndMillis) {
                    continue;
                }

                offeredCredit += offeredBytesPerSecond;
                int bytesSentInTick = 0;
                if (now % 10L != 0L) {
                    continue;
                }
                int retriesThisFlush = 0;
                while (retriesThisFlush < 2 && !pendingRetries.isEmpty()) {
                    RakDatagramPacket datagram = pendingRetries.peek();
                    if (!window.canSendBoundedRecovery(datagram.getSize(), now)) {
                        break;
                    }
                    pendingRetries.poll();
                    window.onBoundedRetransmit(datagram, false, now, pendingRetries.isEmpty());
                    datagram.setSendTime(now);
                    datagram.setSendOrdinal(sendOrdinal++);
                    datagram.markRetransmitted();
                    bytesSentInTick += datagram.getSize();
                    retransmissions++;
                    physicalDatagrams++;
                    deliveries.offer(MixedDelivery.reliable(now + pathRttMillis, datagram,
                            (physicalDatagrams + lossPhase) % 20 == 0));
                    retriesThisFlush++;
                }
                maxPendingRetries = Math.max(maxPendingRetries, pendingRetries.size());
                if (!pendingRetries.isEmpty()) {
                    maxBytesSentInTick = Math.max(maxBytesSentInTick, bytesSentInTick);
                    continue;
                }

                while (offeredCredit >= payloadBytes * 1_000L) {
                    boolean unreliable = (logicalDatagrams++ & 1) != 0;
                    if (unreliable) {
                        if (window.getTransmissionBandwidth(now) < unreliableDatagramBytes) {
                            logicalDatagrams--;
                            break;
                        }
                        RakSlidingWindow.ModelDatagramSample sample = window.onUnreliableSendTracked(
                                unreliableDatagramBytes, now, false);
                        physicalDatagrams++;
                        deliveries.offer(MixedDelivery.unreliable(now + pathRttMillis, sample,
                                (physicalDatagrams + lossPhase) % 20 == 0));
                        bytesSentInTick += unreliableDatagramBytes;
                    } else {
                        RakDatagramPacket datagram = datagram(payloadBytes);
                        int size = datagram.getSize();
                        if (window.getTransmissionBandwidth(now) < size) {
                            datagram.release();
                            logicalDatagrams--;
                            break;
                        }
                        datagram.setSequenceIndex((int) sendOrdinal);
                        datagram.setSendOrdinal(sendOrdinal++);
                        datagram.setSendTime(now);
                        window.onReliableSend(datagram);
                        physicalDatagrams++;
                        deliveries.offer(MixedDelivery.reliable(now + pathRttMillis, datagram,
                                (physicalDatagrams + lossPhase) % 20 == 0));
                        bytesSentInTick += size;
                    }
                    offeredCredit -= payloadBytes * 1_000L;
                }
                maxBytesSentInTick = Math.max(maxBytesSentInTick, bytesSentInTick);
            }

            double measuredMbps = deliveredMeasurementBytes * 8D
                    / ((measurementEndMillis - measurementStartMillis) * 1_000D);
            return new SimulationResult(measuredMbps, window.getCongestionWindow(), maxBytesSentInTick,
                    window.getModelRoundCount(), retransmissions, maxPendingRetries);
        } finally {
            while (!deliveries.isEmpty()) {
                MixedDelivery delivery = deliveries.poll();
                if (delivery.datagram != null) {
                    releaseIfNeeded(delivery.datagram);
                } else {
                    window.onUnreliableLoss(delivery.sample);
                }
            }
            while (!pendingRetries.isEmpty()) {
                releaseIfNeeded(pendingRetries.poll());
            }
            window.close();
        }
    }

    @Test
    public void cleanHandshakeToImpairedPathProbeAvoidsRepeatedFalseLossCollapse() {
        SimulationResult result = simulatePeriodicLoss(0, true);
        Assertions.assertTrue(result.measuredMbps >= 3.5D,
                () -> "clean-handshake RTT poisoned impaired-path control: " + result.measuredMbps + " Mbps");
        Assertions.assertTrue(result.finalCwnd >= 93_000D);
        Assertions.assertTrue(result.retransmissions > 0);
        Assertions.assertTrue(result.rounds >= 200L);
    }

    @Test
    public void probeCycleRediscoversCapacityAndIdleRestartKeepsBoundedBurst() {
        CapacityStepResult result = simulateCapacityStepAndIdleRestart();
        Assertions.assertTrue(result.stepUpMbps >= 3.5D,
                () -> "steady probing failed to rediscover added capacity: " + result.stepUpMbps + " Mbps");
        Assertions.assertTrue(result.restartMbps >= 3.5D,
                () -> "an app-limited idle period left a stale low-rate restart: " + result.restartMbps + " Mbps");
        Assertions.assertTrue(result.maxBytesSentInTick <= 8 * MTU,
                "capacity growth and idle credit remain within one bounded pacing quantum");
        Assertions.assertTrue(result.finalBandwidthBytesPerMillis <= 1_250D,
                "idle/app-limited sampling must not inflate the five-megabit path model");
    }

    private static CapacityStepResult simulateCapacityStepAndIdleRestart() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        PriorityQueue<Delivery> deliveries = new PriorityQueue<>(Comparator.comparingLong(value -> value.at));
        long offeredCredit = 0L;
        long nextLinkAvailable = 0L;
        long sendOrdinal = 0L;
        long stepDelivered = 0L;
        long restartDelivered = 0L;
        int maxBytesSentInTick = 0;
        final int payloadBytes = 1_000;
        final long lowRateBytesPerSecond = 125_000L;
        final long highRateBytesPerSecond = 625_000L;
        final long capacityStepAt = 12_000L;
        final long idleAt = 30_000L;
        final long restartAt = 35_000L;
        final long endAt = 42_000L;
        final long stepMeasureAt = 25_000L;
        final long restartMeasureAt = 37_000L;

        try {
            for (long now = 0L; now <= endAt + 500L; now++) {
                while (!deliveries.isEmpty() && deliveries.peek().at <= now) {
                    Delivery delivery = deliveries.poll();
                    window.onAck(now, delivery.datagram, sendOrdinal);
                    if (now >= stepMeasureAt && now < idleAt) {
                        stepDelivered += payloadBytes;
                    }
                    if (now >= restartMeasureAt && now < endAt) {
                        restartDelivered += payloadBytes;
                    }
                    releaseIfNeeded(delivery.datagram);
                }
                if (now >= endAt) {
                    continue;
                }

                long offeredRate = now < capacityStepAt ? lowRateBytesPerSecond
                        : now < idleAt ? highRateBytesPerSecond
                        : now < restartAt ? 0L : highRateBytesPerSecond;
                double capacityBytesPerMillis = now < capacityStepAt ? 125D : 625D;
                offeredCredit += offeredRate;
                int bytesSentInTick = 0;
                if (now % 10L != 0L) {
                    continue;
                }
                while (offeredCredit >= payloadBytes * 1_000L) {
                    RakDatagramPacket datagram = datagram(payloadBytes);
                    int datagramSize = datagram.getSize();
                    if (window.getTransmissionBandwidth(now) < datagramSize) {
                        datagram.release();
                        break;
                    }
                    offeredCredit -= payloadBytes * 1_000L;
                    datagram.setSequenceIndex((int) sendOrdinal);
                    datagram.setSendOrdinal(sendOrdinal++);
                    datagram.setSendTime(now);
                    boolean appLimited = offeredCredit < payloadBytes * 1_000L;
                    window.onReliableSend(datagram, appLimited);
                    bytesSentInTick += datagramSize;

                    long serviceStart = Math.max(now, nextLinkAvailable);
                    long serializationMillis = Math.max(1L,
                            (long) Math.ceil(datagramSize / capacityBytesPerMillis));
                    nextLinkAvailable = serviceStart + serializationMillis;
                    deliveries.offer(new Delivery(nextLinkAvailable + 100L, datagram, false));
                }
                maxBytesSentInTick = Math.max(maxBytesSentInTick, bytesSentInTick);
            }

            return new CapacityStepResult(
                    stepDelivered * 8D / ((idleAt - stepMeasureAt) * 1_000D),
                    restartDelivered * 8D / ((endAt - restartMeasureAt) * 1_000D),
                    maxBytesSentInTick, window.getModelBandwidthBytesPerMillis());
        } finally {
            while (!deliveries.isEmpty()) {
                releaseIfNeeded(deliveries.poll().datagram);
            }
            window.close();
        }
    }

    private static SimulationResult simulatePeriodicLoss(int lossPhase) {
        return simulatePeriodicLoss(lossPhase, false);
    }

    private static SimulationResult simulatePeriodicLoss(int lossPhase, boolean cleanHandshake) {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        PriorityQueue<Delivery> deliveries = new PriorityQueue<>(Comparator.comparingLong(value -> value.at));
        Queue<RakDatagramPacket> pendingRetries = new ArrayDeque<>();
        long offeredCredit = 0L;
        long deliveredMeasurementBytes = 0L;
        long sendOrdinal = 0L;
        int sentDatagrams = 0;
        int retransmissions = 0;
        int maxPendingRetries = 0;
        int maxBytesSentInTick = 0;
        final int payloadBytes = 1_000;
        final int pathRttMillis = 213;
        final long offeredBytesPerSecond = 625_000L;
        final long measurementStartMillis = 5_000L;
        final long measurementEndMillis = 50_000L;

        try {
            if (cleanHandshake) {
                RakDatagramPacket handshake = datagram(payloadBytes);
                handshake.setSequenceIndex(0);
                handshake.setSendOrdinal(sendOrdinal++);
                handshake.setSendTime(0L);
                window.onReliableSend(handshake, true);
                window.onAck(5L, handshake, sendOrdinal);
                handshake.release();
                Assertions.assertEquals(5L, window.getModelMinimumRttMillis());
            }
            long simulationStart = cleanHandshake ? 10L : 0L;
            for (long now = simulationStart; now <= measurementEndMillis + pathRttMillis; now++) {
                while (!deliveries.isEmpty() && deliveries.peek().at <= now) {
                    Delivery delivery = deliveries.poll();
                    if (delivery.lost) {
                        window.onBoundedLoss(delivery.datagram, sendOrdinal - 1L);
                        pendingRetries.offer(delivery.datagram);
                    } else {
                        window.onAck(now, delivery.datagram, sendOrdinal);
                        if (now >= measurementStartMillis && now < measurementEndMillis) {
                            deliveredMeasurementBytes += payloadBytes;
                        }
                        releaseIfNeeded(delivery.datagram);
                    }
                }

                if (now >= measurementEndMillis) {
                    continue;
                }
                offeredCredit += offeredBytesPerSecond;
                int bytesSentInTick = 0;
                if (now % 10L == 0L) {
                    int retriesThisFlush = 0;
                    while (retriesThisFlush < 2 && !pendingRetries.isEmpty()) {
                        RakDatagramPacket datagram = pendingRetries.peek();
                        if (!window.canSendBoundedRecovery(datagram.getSize(), now)) {
                            break;
                        }
                        pendingRetries.poll();
                        window.onBoundedRetransmit(datagram, false, now, pendingRetries.isEmpty());
                        datagram.setSendTime(now);
                        datagram.setSendOrdinal(sendOrdinal++);
                        datagram.markRetransmitted();
                        bytesSentInTick += datagram.getSize();
                        retransmissions++;
                        sentDatagrams++;
                        deliveries.offer(new Delivery(now + pathRttMillis, datagram,
                                (sentDatagrams + lossPhase) % 20 == 0));
                        retriesThisFlush++;
                    }
                }
                maxPendingRetries = Math.max(maxPendingRetries, pendingRetries.size());
                if (!pendingRetries.isEmpty()) {
                    maxBytesSentInTick = Math.max(maxBytesSentInTick, bytesSentInTick);
                    continue;
                }
                if (now % 10L != 0L) {
                    continue;
                }
                while (offeredCredit >= payloadBytes * 1_000L) {
                    int allowance = window.getTransmissionBandwidth(now);
                    RakDatagramPacket datagram = datagram(payloadBytes);
                    int datagramSize = datagram.getSize();
                    if (allowance < datagramSize) {
                        datagram.release();
                        break;
                    }

                    datagram.setSequenceIndex((int) sendOrdinal);
                    datagram.setSendOrdinal(sendOrdinal++);
                    datagram.setSendTime(now);
                    window.onReliableSend(datagram);
                    offeredCredit -= payloadBytes * 1_000L;
                    bytesSentInTick += datagramSize;
                    sentDatagrams++;
                    deliveries.offer(new Delivery(now + pathRttMillis, datagram,
                            (sentDatagrams + lossPhase) % 20 == 0));
                }
                maxBytesSentInTick = Math.max(maxBytesSentInTick, bytesSentInTick);
            }

            double measuredMbps = deliveredMeasurementBytes * 8D
                    / ((measurementEndMillis - measurementStartMillis) * 1_000D);
            return new SimulationResult(measuredMbps, window.getCongestionWindow(), maxBytesSentInTick,
                    window.getModelRoundCount(), retransmissions, maxPendingRetries);
        } finally {
            while (!deliveries.isEmpty()) {
                releaseIfNeeded(deliveries.poll().datagram);
            }
            while (!pendingRetries.isEmpty()) {
                releaseIfNeeded(pendingRetries.poll());
            }
            window.close();
        }
    }

    @Test
    public void persistentNoProgressCollapsesOnlyTheModelWindow() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        Assertions.assertEquals(10D * MTU, window.getCongestionWindow());
        window.onPersistentCongestion();
        Assertions.assertEquals(2D * MTU, window.getCongestionWindow());
    }

    @Test
    public void transientCongestionBoundReleasesDuringNonCongestiveFivePercentLoss() {
        RakModelCongestionController controller = new RakModelCongestionController(MTU);
        long now = 0L;
        for (int round = 0; round < 8; round++) {
            now = completeModelRound(controller, now, 125, 0, 213L, 213D);
        }
        double learnedCwnd = controller.getCongestionWindow();
        Assertions.assertTrue(learnedCwnd >= 93_000D);

        now = completeModelRound(controller, now, 125, 38, 320L, 320D);
        now = completeModelRound(controller, now, 125, 0, 213L, 213D);
        double boundedCwnd = controller.getCongestionWindow();
        Assertions.assertTrue(boundedCwnd < learnedCwnd,
                "hard transient loss must install a durable in-flight bound");

        for (int round = 0; round < 24; round++) {
            now = completeModelRound(controller, now, 125, 6, 213L, 213D);
        }
        completeModelRound(controller, now, 125, 6, 213L, 213D);
        Assertions.assertTrue(controller.getCongestionWindow() > boundedCwnd,
                "ordinary random loss without delay inflation must release, not deadlock, the loss bound");
        Assertions.assertTrue(controller.getCongestionWindow() >= 93_000D);
    }

    @Test
    public void unstableHighQueueDelayCannotSuppressModerateLossIndefinitely() {
        RakModelCongestionController controller = new RakModelCongestionController(MTU);
        long now = 0L;
        for (int round = 0; round < 8; round++) {
            now = completeModelRound(controller, now, 125, 0, 5L, 5D);
        }
        double learnedCwnd = controller.getCongestionWindow();

        now = completeModelRound(controller, now, 125, 6, 100L, 100D);
        now = completeModelRound(controller, now, 125, 6, 300L, 300D);
        completeModelRound(controller, now, 125, 6, 120L, 120D);

        Assertions.assertEquals(5L, controller.getMinimumRttMillis(),
                "unstable busy-flight queue delay is never promoted to propagation RTT");
        Assertions.assertTrue(controller.getCongestionWindow() < learnedCwnd,
                "failed path suspicion must leave cooldown and restore delay-qualified loss response");
    }

    private static long completeModelRound(RakModelCongestionController controller, long sendAt, int packets,
                                           int lostPackets, long rttMillis, double smoothedRttMillis) {
        List<RakDatagramPacket> flight = new ArrayList<>(packets);
        int inFlight = 0;
        for (int i = 0; i < packets; i++) {
            RakDatagramPacket datagram = datagram(1_000);
            datagram.setSendTime(sendAt);
            inFlight += datagram.getSize();
            controller.onPacketSent(datagram, sendAt, inFlight, false);
            flight.add(datagram);
        }
        int currentInFlight = inFlight;
        long ackAt = sendAt + rttMillis;
        for (int i = 0; i < flight.size(); i++) {
            RakDatagramPacket datagram = flight.get(i);
            currentInFlight -= datagram.getSize();
            if (i < lostPackets) {
                controller.onLost(datagram, smoothedRttMillis);
            } else {
                controller.onAcknowledged(datagram, ackAt, rttMillis, smoothedRttMillis, currentInFlight);
            }
            datagram.release();
        }
        return ackAt + 1L;
    }

    @Test
    public void minimumRttAdaptsFromCleanHandshakeToStablePathStepAndBack() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            acknowledgeOne(window, packets, 0, 100L, 105L);
            Assertions.assertEquals(5L, window.getModelMinimumRttMillis());

            long sendAt = 1_000L;
            for (int i = 1; i <= 4; i++) {
                acknowledgeOne(window, packets, i, sendAt, sendAt + 200L);
                sendAt += 220L;
            }
            Assertions.assertEquals(200L, window.getModelMinimumRttMillis(),
                    "a sustained propagation-delay step must replace the clean pre-impairment handshake minimum");

            acknowledgeOne(window, packets, 5, 2_000L, 2_005L);
            Assertions.assertEquals(5L, window.getModelMinimumRttMillis(),
                    "path recovery is accepted immediately when lower-delay evidence returns");
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void risingQueueDelayIsNotAcceptedAsAPropagationPathStep() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            acknowledgeOne(window, packets, 0, 100L, 200L);
            long[] risingRtts = {210L, 240L, 280L, 320L, 360L};
            long sendAt = 500L;
            for (int i = 0; i < risingRtts.length; i++) {
                acknowledgeOne(window, packets, i + 1, sendAt, sendAt + risingRtts[i]);
                sendAt += 400L;
            }
            Assertions.assertEquals(100L, window.getModelMinimumRttMillis(),
                    "a rising delay trend is queue evidence, not a stable propagation-delay step");
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void stableDeepQueueDuringAFullFlightCannotReplaceMinimumRtt() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            acknowledgeOne(window, packets, 0, 100L, 105L);
            Assertions.assertEquals(5L, window.getModelMinimumRttMillis());

            for (int i = 1; i <= 10; i++) {
                RakDatagramPacket datagram = datagram(1_000);
                packets.add(datagram);
                datagram.setSequenceIndex(i);
                datagram.setSendOrdinal(i);
                datagram.setSendTime(1_000L);
                window.onReliableSend(datagram);
            }
            for (int i = 1; i <= 10; i++) {
                window.onAck(1_200L, packets.get(i), i + 1L);
            }

            for (int i = 11; i <= 20; i++) {
                RakDatagramPacket datagram = datagram(1_000);
                packets.add(datagram);
                datagram.setSequenceIndex(i);
                datagram.setSendOrdinal(i);
                datagram.setSendTime(1_500L);
                window.onReliableSend(datagram);
            }
            for (int i = 11; i <= 20; i++) {
                window.onAck(1_700L, packets.get(i), i + 1L);
            }

            Assertions.assertEquals(5L, window.getModelMinimumRttMillis(),
                    "early packets in a later full flight are not evidence of a drained path");
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void oneAckBatchDelaySpikeCannotTriggerPathProbeOrCollapseWindow() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            acknowledgeOne(window, packets, 0, 0L, 5L);
            double initialCwnd = window.getCongestionWindow();

            for (int i = 1; i <= 10; i++) {
                RakDatagramPacket datagram = datagram(1_000);
                packets.add(datagram);
                datagram.setSequenceIndex(i);
                datagram.setSendOrdinal(i);
                datagram.setSendTime(100L);
                window.onReliableSend(datagram);
            }
            for (int i = 1; i <= 10; i++) {
                window.onAck(200L, packets.get(i), i + 1L);
            }

            Assertions.assertEquals(5L, window.getModelMinimumRttMillis());
            Assertions.assertEquals(initialCwnd, window.getCongestionWindow(),
                    "many delayed ACKs from one flight are one scheduler-stall observation, not a path step");

            acknowledgeOne(window, packets, 11, 300L, 305L);
            Assertions.assertEquals(5L, window.getModelMinimumRttMillis());
            Assertions.assertEquals(initialCwnd, window.getCongestionWindow());
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void oneAckBatchWithDifferentSendSnapshotsCannotTriggerPathProbe() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            acknowledgeOne(window, packets, 0, 0L, 5L);
            double initialCwnd = window.getCongestionWindow();

            RakDatagramPacket heldA = datagram(1_000);
            packets.add(heldA);
            heldA.setSequenceIndex(1);
            heldA.setSendOrdinal(1L);
            heldA.setSendTime(100L);
            window.onReliableSend(heldA);

            acknowledgeOne(window, packets, 2, 101L, 106L);

            RakDatagramPacket heldB = datagram(1_000);
            packets.add(heldB);
            heldB.setSequenceIndex(3);
            heldB.setSendOrdinal(3L);
            heldB.setSendTime(110L);
            window.onReliableSend(heldB);
            Assertions.assertNotEquals(heldA.getDeliveredBytesAtSend(), heldB.getDeliveredBytesAtSend(),
                    "the regression requires packets sent across delivery progress");

            window.onAck(300L, heldA, 4L);
            window.onAck(300L, heldB, 4L);

            Assertions.assertEquals(5L, window.getModelMinimumRttMillis());
            Assertions.assertEquals(initialCwnd, window.getCongestionWindow(),
                    "different send snapshots in one stalled ACK observation are not distinct path evidence");
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void retransmittedAcknowledgementCannotInflateDeliveryRateOrReceiveDoubleCredit() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                acknowledgeOne(window, packets, i, 100L + i, 300L);
            }
            double rateBeforeRetry = window.getModelBandwidthBytesPerMillis();
            int unackedBeforeRetry = window.getUnackedBytes();

            RakDatagramPacket retry = datagram(1_000);
            packets.add(retry);
            retry.setSequenceIndex(10);
            retry.setSendOrdinal(10L);
            retry.setSendTime(400L);
            window.onReliableSend(retry);
            window.onBoundedLoss(retry, 10L);
            window.onBoundedRetransmit(retry, false, 450L);
            retry.setSendTime(450L);
            retry.markRetransmitted();
            window.onAck(451L, retry, 11L);

            Assertions.assertEquals(rateBeforeRetry, window.getModelBandwidthBytesPerMillis(),
                    "an ambiguous one-millisecond retry ACK is not a delivery-rate sample");
            Assertions.assertEquals(unackedBeforeRetry, window.getUnackedBytes());
            window.onAck(452L, retry, 12L);
            Assertions.assertEquals(unackedBeforeRetry, window.getUnackedBytes(),
                    "a duplicate/late ACK cannot credit the same logical datagram twice");
            Assertions.assertEquals(rateBeforeRetry, window.getModelBandwidthBytesPerMillis());
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    @Test
    public void idlePacerCannotAccumulateAnUnboundedCatchUpBurst() {
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.MODEL_BASED);
        List<RakDatagramPacket> packets = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                acknowledgeOne(window, packets, i, 100L + i, 300L);
            }
            int allowanceAfterLongIdle = window.getTransmissionBandwidth(1_000_000L);
            Assertions.assertTrue(allowanceAfterLongIdle <= 8 * MTU,
                    "idle time is capped to one bounded event-loop pacing quantum");
        } finally {
            for (RakDatagramPacket datagram : packets) {
                releaseIfNeeded(datagram);
            }
            window.close();
        }
    }

    private static void acknowledgeOne(RakSlidingWindow window, List<RakDatagramPacket> packets, int index,
                                       long sendAt, long ackAt) {
        RakDatagramPacket datagram = datagram(1_000);
        packets.add(datagram);
        datagram.setSequenceIndex(index);
        datagram.setSendOrdinal(index);
        datagram.setSendTime(sendAt);
        window.onReliableSend(datagram);
        window.onAck(ackAt, datagram, index + 1L);
    }

    private static RakDatagramPacket datagram(int payloadBytes) {
        EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
        packet.setReliability(RakReliability.RELIABLE);
        packet.setBuffer(Unpooled.buffer(payloadBytes).writeZero(payloadBytes));

        RakDatagramPacket datagram = RakDatagramPacket.newInstance();
        if (!datagram.tryAddPacket(packet, MTU)) {
            packet.release();
            datagram.release();
            throw new AssertionError("test datagram does not fit MTU");
        }
        return datagram;
    }

    private static void releaseIfNeeded(RakDatagramPacket datagram) {
        if (datagram.refCnt() > 0) {
            datagram.release();
        }
    }

    private static final class Delivery {
        private final long at;
        private final RakDatagramPacket datagram;
        private final boolean lost;

        private Delivery(long at, RakDatagramPacket datagram, boolean lost) {
            this.at = at;
            this.datagram = datagram;
            this.lost = lost;
        }
    }

    private static final class MixedDelivery {
        private final long at;
        private final RakDatagramPacket datagram;
        private final RakSlidingWindow.ModelDatagramSample sample;
        private final boolean lost;

        private MixedDelivery(long at, RakDatagramPacket datagram,
                              RakSlidingWindow.ModelDatagramSample sample, boolean lost) {
            this.at = at;
            this.datagram = datagram;
            this.sample = sample;
            this.lost = lost;
        }

        private static MixedDelivery reliable(long at, RakDatagramPacket datagram, boolean lost) {
            return new MixedDelivery(at, datagram, null, lost);
        }

        private static MixedDelivery unreliable(long at, RakSlidingWindow.ModelDatagramSample sample,
                                                boolean lost) {
            return new MixedDelivery(at, null, sample, lost);
        }
    }

    private static final class SimulationResult {
        private final double measuredMbps;
        private final double finalCwnd;
        private final int maxBytesSentInTick;
        private final long rounds;
        private final int retransmissions;
        private final int maxPendingRetries;

        private SimulationResult(double measuredMbps, double finalCwnd, int maxBytesSentInTick, long rounds,
                                 int retransmissions, int maxPendingRetries) {
            this.measuredMbps = measuredMbps;
            this.finalCwnd = finalCwnd;
            this.maxBytesSentInTick = maxBytesSentInTick;
            this.rounds = rounds;
            this.retransmissions = retransmissions;
            this.maxPendingRetries = maxPendingRetries;
        }
    }

    private static final class CapacityStepResult {
        private final double stepUpMbps;
        private final double restartMbps;
        private final int maxBytesSentInTick;
        private final double finalBandwidthBytesPerMillis;

        private CapacityStepResult(double stepUpMbps, double restartMbps, int maxBytesSentInTick,
                                   double finalBandwidthBytesPerMillis) {
            this.stepUpMbps = stepUpMbps;
            this.restartMbps = restartMbps;
            this.maxBytesSentInTick = maxBytesSentInTick;
            this.finalBandwidthBytesPerMillis = finalBandwidthBytesPerMillis;
        }
    }
}
