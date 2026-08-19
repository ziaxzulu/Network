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

package org.cloudburstmc.netty.handler.codec.raknet.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.RakSlidingWindow;
import org.cloudburstmc.netty.channel.raknet.RakState;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelMetrics;
import org.cloudburstmc.netty.channel.raknet.config.RakDatagramSendType;
import org.cloudburstmc.netty.channel.raknet.config.RakRecoveryMode;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.util.FastBinaryMinHeap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_DISCONNECTION_NOTIFICATION;

public class RakSessionCodecBoundedRecoveryTests {
    private static final int MTU = 1_200;

    @Test
    public void boundsNacksPromotesOnePtoAndReclaimsAcknowledgedDatagram() throws Exception {
        AtomicLong clock = new AtomicLong();
        Harness harness = harness(clock, null);
        TestDatagram first = datagram(100, 0);
        TestDatagram second = datagram(100, 1);
        TestDatagram third = datagram(100, 2);
        try {
            harness.add(first.packet);
            harness.add(second.packet);
            harness.add(third.packet);
            harness.arm();

            invokeNack(harness.codec, first.packet, 0L);
            invokeNack(harness.codec, second.packet, 0L);
            invokeNack(harness.codec, third.packet, 0L);
            Assertions.assertEquals(3, harness.pending.size());

            Assertions.assertEquals(2, invokeRecovery(harness.codec, harness.context, 0L));
            harness.channel.flushOutbound();
            releaseOutbound(harness.channel, 2);
            Assertions.assertEquals(1, harness.pending.size(), "one NACK remains after the two-datagram flush cap");

            clock.set(1_000L);
            Assertions.assertEquals(1, invokeRecovery(harness.codec, harness.context, 1_000L));
            harness.channel.flushOutbound();
            releaseOutbound(harness.channel, 1);
            Assertions.assertTrue(harness.pending.isEmpty(), "PTO promotes and sends one pending recovery");
            Assertions.assertFalse(third.packet.isRetransmissionPending(),
                    "the timeout-selected pending NACK is removed exactly once after handoff");

            int acknowledgedSequence = third.packet.getSequenceIndex();
            Assertions.assertSame(third.packet, harness.sent.remove(acknowledgedSequence));
            invokeAck(harness.codec, third.packet, 1_010L);
            Assertions.assertEquals(0, third.payload.refCnt(), "ACK releases the map-owned logical datagram");
            Assertions.assertFalse(harness.sent.containsKey(acknowledgedSequence));
            Assertions.assertEquals(first.packet.getSize() + second.packet.getSize(),
                    harness.window.getUnackedBytes());

            harness.closeCodec();
            Assertions.assertEquals(0, first.payload.refCnt());
            Assertions.assertEquals(0, second.payload.refCnt());
            Assertions.assertEquals(0, harness.window.getBytesInFlight());
            Assertions.assertEquals(0, harness.window.getUnackedBytes());
        } finally {
            harness.close();
            releaseIfNeeded(first.payload);
            releaseIfNeeded(second.payload);
            releaseIfNeeded(third.payload);
        }
    }

    @Test
    public void laterAcknowledgementsCannotPostponeLostRetransmissionPto() throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingMetrics metrics = new RecordingMetrics();
        Harness harness = harness(clock, metrics);
        TestDatagram first = datagram(100, 0);
        TestDatagram second = datagram(100, 1);
        TestDatagram third = datagram(100, 2);
        TestDatagram later = datagram(100, 10);
        try {
            harness.add(first.packet);
            harness.add(second.packet);
            harness.add(third.packet);
            harness.arm();

            invokeNack(harness.codec, first.packet, 0L);
            Assertions.assertEquals(1, invokeRecovery(harness.codec, harness.context, 0L));
            harness.channel.flushOutbound();
            releaseOutbound(harness.channel, 1);
            Assertions.assertEquals(1_000L, first.packet.getNextSend());

            clock.set(400L);
            acknowledge(harness, second.packet, 400L);
            clock.set(800L);
            acknowledge(harness, third.packet, 800L);

            harness.add(later.packet);
            harness.schedule(later.packet);
            clock.set(999L);
            acknowledge(harness, later.packet, 999L);
            Assertions.assertEquals(1_000L, harness.recovery.getNextProbeAtMillis(),
                    "ACKs for later datagrams retain the lost retransmission's attempt deadline");
            Assertions.assertEquals(0, invokeRecovery(harness.codec, harness.context, 999L));

            clock.set(1_000L);
            Assertions.assertEquals(1, invokeRecovery(harness.codec, harness.context, 1_000L));
            harness.channel.flushOutbound();
            releaseOutbound(harness.channel, 1);
            Assertions.assertEquals(Arrays.asList(RakDatagramSendType.NACK_RETRANSMISSION,
                    RakDatagramSendType.TIMEOUT_RETRANSMISSION), metrics.sendTypes);
            Assertions.assertEquals(1, harness.recovery.getPtoBackoff());

            acknowledge(harness, first.packet, 1_010L);
            Assertions.assertEquals(0, first.payload.refCnt());
            Assertions.assertEquals(0, harness.window.getUnackedBytes());
            Assertions.assertEquals(-1L, harness.recovery.getNextProbeAtMillis());
        } finally {
            harness.close();
            releaseIfNeeded(first.payload);
            releaseIfNeeded(second.payload);
            releaseIfNeeded(third.payload);
            releaseIfNeeded(later.payload);
        }
    }

    @Test
    public void sendCallbackFailureRestoresMapQueueAndFlightAccounting() throws Exception {
        AtomicLong clock = new AtomicLong();
        RakChannelMetrics throwingMetrics = new RakChannelMetrics() {
            @Override
            public void rakDatagramSent(RakDatagramSendType sendType, int bytes, int retransmissionAttempt,
                                        int bytesInFlight) {
                throw new IllegalStateException("metrics failure");
            }
        };
        Harness harness = harness(clock, throwingMetrics);
        TestDatagram datagram = datagram(100, 0);
        try {
            harness.add(datagram.packet);
            harness.arm();
            invokeNack(harness.codec, datagram.packet, 0L);
            long oldSendTime = datagram.packet.getSendTime();
            long oldDeadline = datagram.packet.getNextSend();
            long oldOrdinal = datagram.packet.getSendOrdinal();

            InvocationTargetException failure = Assertions.assertThrows(InvocationTargetException.class,
                    () -> recoveryMethod().invoke(harness.codec, harness.context, 0L, MTU));
            Assertions.assertEquals("metrics failure", failure.getCause().getMessage());
            Assertions.assertSame(datagram.packet, harness.sent.get(0));
            Assertions.assertEquals(1, harness.pending.size());
            Assertions.assertTrue(datagram.packet.isRetransmissionPending());
            Assertions.assertEquals(0, harness.window.getBytesInFlight());
            Assertions.assertEquals(datagram.packet.getSize(), harness.window.getUnackedBytes());
            Assertions.assertEquals(oldSendTime, datagram.packet.getSendTime());
            Assertions.assertEquals(oldDeadline, datagram.packet.getNextSend());
            Assertions.assertEquals(oldOrdinal, datagram.packet.getSendOrdinal());
            Assertions.assertEquals(0, datagram.packet.getRetransmissionCount());
            Object recoveryMetrics = get(harness.codec, "recoveryMetrics");
            Assertions.assertEquals(0, get(recoveryMetrics, "retransmittedDatagramsInFlight"));
            Assertions.assertEquals(-1L, get(recoveryMetrics, "recoveryStartedAtMillis"));
            Assertions.assertEquals(-1L, get(recoveryMetrics, "lastStateReportAtMillis"));
            Assertions.assertEquals(1, datagram.packet.refCnt(), "failed send retains exactly the map-owned reference");
        } finally {
            harness.close();
            releaseIfNeeded(datagram.payload);
        }
    }

    @Test
    public void deferredPtoDoesNotOccupyNackFifoOrBlockNewTraffic() throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingMetrics metrics = new RecordingMetrics();
        Harness harness = harness(clock, metrics);
        TestDatagram occupyingProbe = datagram(800, 0);
        TestDatagram timeoutCandidate = datagram(1_100, 0);
        TestDatagram pendingNack = datagram(100, 2);
        TestDatagram otherExpired = datagram(100, 20);
        ByteBuf applicationPayload = Unpooled.buffer(50).writeByte(0x42).writeZero(49);
        try {
            harness.add(occupyingProbe.packet);
            timeoutCandidate.packet.setSequenceIndex(1);
            timeoutCandidate.packet.setSendOrdinal(1L);
            harness.add(timeoutCandidate.packet);
            harness.add(pendingNack.packet);
            harness.add(otherExpired.packet);
            harness.arm();

            harness.window.onBoundedLoss(occupyingProbe.packet, 1L);
            harness.window.onBoundedRetransmit(occupyingProbe.packet, true);
            occupyingProbe.packet.setNextSend(2_000L);
            harness.recovery.refreshProbeDeadline(harness.window, harness.sent.values());
            invokeNack(harness.codec, pendingNack.packet, 0L);

            ChannelPromise applicationPromise = harness.context.newPromise();
            harness.codec.write(harness.context,
                    new RakMessage(applicationPayload, RakReliability.RELIABLE, RakPriority.HIGH),
                    applicationPromise);
            Assertions.assertTrue(applicationPromise.isSuccess());

            clock.set(1_000L);
            invokeInternalFlush(harness.codec, harness.context);
            harness.channel.flushOutbound();
            releaseOutbound(harness.channel, 2);

            Assertions.assertEquals(Arrays.asList(RakDatagramSendType.NACK_RETRANSMISSION,
                    RakDatagramSendType.ORIGINAL), metrics.sendTypes,
                    "a deferred timeout leaves both the NACK scheduler and new-send admission live");
            Assertions.assertTrue(harness.pending.isEmpty());
            Assertions.assertFalse(timeoutCandidate.packet.isRetransmissionPending(),
                    "timeout selection is ephemeral and never enters the NACK FIFO");
            Assertions.assertFalse(timeoutCandidate.packet.isInFlight());
            Assertions.assertTrue(otherExpired.packet.isInFlight(),
                    "one due PTO declares only its selected oldest attempt lost");
            Assertions.assertEquals(0, harness.recovery.getPtoBackoff());
            Assertions.assertTrue(harness.recovery.getNextProbeAtMillis() > 1_000L);
        } finally {
            harness.close();
            releaseIfNeeded(occupyingProbe.payload);
            releaseIfNeeded(timeoutCandidate.payload);
            releaseIfNeeded(pendingNack.payload);
            releaseIfNeeded(otherExpired.payload);
            releaseIfNeeded(applicationPayload);
        }
    }

    @Test
    public void terminalDisconnectIsHandedOffBeforeCloseWhileOrdinaryImmediateWaitsForCwnd() throws Exception {
        AtomicLong clock = new AtomicLong();
        Harness harness = harness(clock, null);
        TestDatagram occupyingWindow = datagram(1_100, 0);
        try {
            harness.add(occupyingWindow.packet);
            harness.arm();

            clock.set(1L);
            ByteBuf applicationPayload = Unpooled.buffer(100).writeByte(0x42).writeZero(99);
            ChannelPromise applicationPromise = harness.context.newPromise();
            harness.codec.write(harness.context,
                    new RakMessage(applicationPayload, RakReliability.RELIABLE, RakPriority.IMMEDIATE),
                    applicationPromise);
            Assertions.assertTrue(applicationPromise.isSuccess());
            Assertions.assertNull(harness.channel.readOutbound(),
                    "ordinary reliable-immediate traffic remains behind occupied cwnd");
            Assertions.assertTrue((Integer) get(harness.codec, "queuedBytes") > 0);

            clock.set(2L);
            Method disconnect = RakSessionCodec.class.getDeclaredMethod("disconnect0", RakDisconnectReason.class);
            disconnect.setAccessible(true);
            ChannelPromise disconnectPromise = (ChannelPromise) disconnect.invoke(
                    harness.codec, RakDisconnectReason.DISCONNECTED);

            Assertions.assertTrue(disconnectPromise.isSuccess());
            RakDatagramPacket outbound = harness.channel.readOutbound();
            Assertions.assertNotNull(outbound,
                    "terminal notification is handed to the outbound channel before the close listener runs");
            Assertions.assertEquals(ID_DISCONNECTION_NOTIFICATION,
                    outbound.getPackets().get(0).getBuffer().getUnsignedByte(
                            outbound.getPackets().get(0).getBuffer().readerIndex()));
            Assertions.assertFalse(harness.channel.isOpen(), "disconnect promise listener closes after handoff");
            outbound.release();
        } finally {
            harness.close();
            releaseIfNeeded(occupyingWindow.payload);
        }
    }

    private static Harness harness(AtomicLong clock, RakChannelMetrics metrics) throws Exception {
        ChannelDuplexHandler outboundHandler = new ChannelDuplexHandler();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel();
        embeddedChannel.pipeline().addLast(RakSessionCodec.NAME, outboundHandler);
        ChannelHandlerContext embeddedContext = embeddedChannel.pipeline().context(outboundHandler);

        RakChannelConfig config = (RakChannelConfig) Proxy.newProxyInstance(
                RakChannelConfig.class.getClassLoader(), new Class<?>[]{RakChannelConfig.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMetrics")) {
                        return metrics;
                    }
                    if (method.getName().equals("getRecoveryMode")) {
                        return RakRecoveryMode.BOUNDED;
                    }
                    if (method.getName().equals("getMtu")) {
                        return MTU;
                    }
                    return defaultValue(method.getReturnType());
                });
        RakChannel channel = (RakChannel) Proxy.newProxyInstance(
                RakChannel.class.getClassLoader(), new Class<?>[]{RakChannel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("config")) {
                        return config;
                    }
                    if (method.getName().equals("parent")) {
                        return embeddedChannel;
                    }
                    if (method.getName().equals("pipeline") || method.getName().equals("rakPipeline")) {
                        return embeddedChannel.pipeline();
                    }
                    if (method.getName().equals("remoteAddress") || method.getName().equals("localAddress")) {
                        return new InetSocketAddress("127.0.0.1", 19132);
                    }
                    return defaultValue(method.getReturnType());
                });

        RakSessionCodec codec = new RakSessionCodec(channel, clock::get);
        RakSlidingWindow window = new RakSlidingWindow(MTU, RakRecoveryMode.BOUNDED);
        RakBoundedRecovery recovery = new RakBoundedRecovery(clock::get, () -> 0L);
        IntObjectMap<RakDatagramPacket> sent = new IntObjectHashMap<>();
        Queue<Object> pending = new ArrayDeque<>();

        set(codec, "recoveryMode", RakRecoveryMode.BOUNDED);
        set(codec, "slidingWindow", window);
        set(codec, "boundedRecovery", recovery);
        set(codec, "sentDatagrams", sent);
        set(codec, "pendingRetransmissions", pending);
        set(codec, "incomingAcks", new ArrayDeque<>());
        set(codec, "incomingNaks", new ArrayDeque<>());
        set(codec, "outgoingAcks", new ArrayDeque<>());
        set(codec, "outgoingNaks", new ArrayDeque<>());
        set(codec, "datagramWriteIndex", 3);
        set(codec, "datagramSendOrdinal", 3L);
        set(codec, "outgoingPackets", new FastBinaryMinHeap<EncapsulatedPacket>(8));
        set(codec, "outgoingPacketNextWeights", new long[4]);
        set(codec, "orderWriteIndex", new int[16]);
        set(codec, "state", RakState.CONNECTED);
        Method initHeapWeights = RakSessionCodec.class.getDeclaredMethod("initHeapWeights");
        initHeapWeights.setAccessible(true);
        initHeapWeights.invoke(codec);

        return new Harness(codec, window, recovery, sent, pending, embeddedChannel, embeddedContext);
    }

    private static void invokeNack(RakSessionCodec codec, RakDatagramPacket packet, long time) throws Exception {
        Method method = RakSessionCodec.class.getDeclaredMethod("onIncomingNack",
                ChannelHandlerContext.class, RakDatagramPacket.class, long.class);
        method.setAccessible(true);
        method.invoke(codec, null, packet, time);
    }

    private static int invokeRecovery(RakSessionCodec codec, ChannelHandlerContext context, long time)
            throws Exception {
        return (Integer) recoveryMethod().invoke(codec, context, time, MTU);
    }

    private static Method recoveryMethod() throws NoSuchMethodException {
        Method method = RakSessionCodec.class.getDeclaredMethod("sendBoundedRecovery",
                ChannelHandlerContext.class, long.class, int.class);
        method.setAccessible(true);
        return method;
    }

    private static void invokeAck(RakSessionCodec codec, RakDatagramPacket packet, long time) throws Exception {
        Method method = RakSessionCodec.class.getDeclaredMethod("onIncomingAck", RakDatagramPacket.class, long.class);
        method.setAccessible(true);
        method.invoke(codec, packet, time);
    }

    private static void acknowledge(Harness harness, RakDatagramPacket packet, long time) throws Exception {
        Assertions.assertSame(packet, harness.sent.remove(packet.getSequenceIndex()));
        invokeAck(harness.codec, packet, time);
    }

    private static void invokeInternalFlush(RakSessionCodec codec, ChannelHandlerContext context) throws Exception {
        Method method = RakSessionCodec.class.getDeclaredMethod("internalFlush", ChannelHandlerContext.class);
        method.setAccessible(true);
        method.invoke(codec, context);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return '\0';
    }

    private static TestDatagram datagram(int payloadBytes, int sequenceIndex) {
        ByteBuf payload = Unpooled.buffer(payloadBytes).writeZero(payloadBytes);
        EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
        packet.setReliability(RakReliability.RELIABLE);
        packet.setBuffer(payload);

        RakDatagramPacket datagram = RakDatagramPacket.newInstance();
        Assertions.assertTrue(datagram.tryAddPacket(packet, MTU));
        datagram.setSequenceIndex(sequenceIndex);
        datagram.setSendOrdinal(sequenceIndex);
        datagram.setSendTime(0L);
        return new TestDatagram(datagram, payload);
    }

    private static void releaseOutbound(EmbeddedChannel channel, int expected) {
        for (int i = 0; i < expected; i++) {
            RakDatagramPacket outbound = channel.readOutbound();
            Assertions.assertNotNull(outbound);
            outbound.release();
        }
        Assertions.assertNull(channel.readOutbound());
    }

    private static void releaseIfNeeded(ByteBuf payload) {
        if (payload.refCnt() > 0) {
            payload.release(payload.refCnt());
        }
    }

    private static final class RecordingMetrics implements RakChannelMetrics {
        private final List<RakDatagramSendType> sendTypes = new ArrayList<>();

        @Override
        public void rakDatagramSent(RakDatagramSendType sendType, int bytes, int retransmissionAttempt,
                                    int bytesInFlight) {
            this.sendTypes.add(sendType);
        }
    }

    private static final class TestDatagram {
        private final RakDatagramPacket packet;
        private final ByteBuf payload;

        private TestDatagram(RakDatagramPacket packet, ByteBuf payload) {
            this.packet = packet;
            this.payload = payload;
        }
    }

    private static final class Harness {
        private final RakSessionCodec codec;
        private final RakSlidingWindow window;
        private final RakBoundedRecovery recovery;
        private final IntObjectMap<RakDatagramPacket> sent;
        private final Queue<Object> pending;
        private final EmbeddedChannel channel;
        private final ChannelHandlerContext context;
        private boolean codecClosed;

        private Harness(RakSessionCodec codec, RakSlidingWindow window, RakBoundedRecovery recovery,
                        IntObjectMap<RakDatagramPacket> sent, Queue<Object> pending,
                        EmbeddedChannel channel, ChannelHandlerContext context) {
            this.codec = codec;
            this.window = window;
            this.recovery = recovery;
            this.sent = sent;
            this.pending = pending;
            this.channel = channel;
            this.context = context;
        }

        private void add(RakDatagramPacket packet) {
            this.sent.put(packet.getSequenceIndex(), packet);
            this.window.onReliableSend(packet);
        }

        private void arm() {
            for (RakDatagramPacket packet : this.sent.values()) {
                this.schedule(packet);
            }
        }

        private void schedule(RakDatagramPacket packet) {
            this.recovery.onReliableSend(this.window, packet);
        }

        private void closeCodec() throws Exception {
            if (this.codecClosed) {
                return;
            }
            Method method = RakSessionCodec.class.getDeclaredMethod("releaseSessionResources");
            method.setAccessible(true);
            method.invoke(this.codec);
            this.codecClosed = true;
        }

        private void close() throws Exception {
            this.closeCodec();
            this.channel.finishAndReleaseAll();
        }
    }
}
