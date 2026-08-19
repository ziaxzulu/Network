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
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.RakSlidingWindow;
import org.cloudburstmc.netty.channel.raknet.RakState;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakRecoveryMode;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.util.FastBinaryMinHeap;
import org.cloudburstmc.netty.util.IntRange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

public class RakSessionCodecReliabilityBoundaryTests {
    private static final int MTU = 1_200;
    private static final int RELIABLE_PAYLOAD = 0x21;
    private static final int UNRELIABLE_PAYLOAD = 0x22;

    @Test
    public void mixedReliabilityUsesHomogeneousDatagramsAndRetainsOnlyReliableTraffic() throws Exception {
        for (RakRecoveryMode mode : RakRecoveryMode.values()) {
            Harness harness = harness(mode);
            try {
                harness.enqueue(RELIABLE_PAYLOAD, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL);
                harness.enqueue(UNRELIABLE_PAYLOAD, RakReliability.UNRELIABLE, RakPriority.HIGH);
                harness.enqueue(RELIABLE_PAYLOAD + 2, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL);

                List<RakDatagramPacket> datagrams = harness.sendOriginals();
                Assertions.assertEquals(2, datagrams.size(), mode + " physical datagram count");
                RakDatagramPacket telemetry = datagrams.get(0);
                RakDatagramPacket workload = datagrams.get(1);
                Assertions.assertEquals(Collections.singletonList(UNRELIABLE_PAYLOAD), payloadIds(telemetry));
                Assertions.assertTrue(telemetry.getPackets().stream()
                        .noneMatch(packet -> packet.getReliability().isReliable()));
                Assertions.assertEquals(Arrays.asList(RELIABLE_PAYLOAD, RELIABLE_PAYLOAD + 2), payloadIds(workload),
                        "same-class reliable traffic still coalesces in scheduler order");
                Assertions.assertTrue(workload.getPackets().stream()
                        .allMatch(packet -> packet.getReliability().isReliable()));
                Assertions.assertFalse(harness.sent.containsKey(telemetry.getSequenceIndex()),
                        "unreliable datagram is never retained for recovery");
                Assertions.assertSame(workload, harness.sent.get(workload.getSequenceIndex()),
                        "reliable datagram is retained for recovery");
                harness.releaseOutbound(datagrams);
            } finally {
                harness.closeAndAssertPayloadsReleased();
            }
        }
    }

    @Test
    public void nackAndTimeoutRecoveryNeverRetransmitUnreliablePayload() throws Exception {
        for (RakRecoveryMode mode : RakRecoveryMode.values()) {
            assertRecoveryPayload(mode, RecoveryTrigger.NACK);
            assertRecoveryPayload(mode, RecoveryTrigger.TIMEOUT);
        }
    }

    private static void assertRecoveryPayload(RakRecoveryMode mode, RecoveryTrigger trigger) throws Exception {
        Harness harness = harness(mode);
        try {
            harness.enqueue(RELIABLE_PAYLOAD, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL);
            harness.enqueue(UNRELIABLE_PAYLOAD, RakReliability.UNRELIABLE, RakPriority.HIGH);
            List<RakDatagramPacket> originals = harness.sendOriginals();
            RakDatagramPacket telemetry = originals.get(0);
            RakDatagramPacket workload = originals.get(1);
            int telemetrySequence = telemetry.getSequenceIndex();
            int workloadSequence = workload.getSequenceIndex();
            long timeoutAt = workload.getNextSend();
            harness.releaseOutbound(originals);

            harness.nack(telemetrySequence);
            Assertions.assertTrue(harness.readOutbound().isEmpty(),
                    mode + " ignored NACK for unretained unreliable datagram");

            if (trigger == RecoveryTrigger.NACK) {
                harness.nack(workloadSequence);
                if (mode == RakRecoveryMode.BOUNDED) {
                    harness.sendBoundedRecovery(0L);
                }
            } else if (mode == RakRecoveryMode.BOUNDED) {
                harness.sendBoundedRecovery(timeoutAt);
            } else {
                harness.sendLegacyTimeoutRecovery(timeoutAt);
            }

            List<RakDatagramPacket> retransmissions = harness.readOutbound();
            Assertions.assertEquals(1, retransmissions.size(), mode + " " + trigger + " retransmission count");
            Assertions.assertEquals(Collections.singletonList(RELIABLE_PAYLOAD), payloadIds(retransmissions.get(0)),
                    mode + " " + trigger + " retransmission payload");
            Assertions.assertTrue(retransmissions.get(0).getPackets().stream()
                    .allMatch(packet -> packet.getReliability().isReliable()));
            harness.releaseOutbound(retransmissions);
        } finally {
            harness.closeAndAssertPayloadsReleased();
        }
    }

    private static List<Integer> payloadIds(RakDatagramPacket datagram) {
        List<Integer> ids = new ArrayList<>();
        for (EncapsulatedPacket packet : datagram.getPackets()) {
            ids.add((int) packet.getBuffer().getUnsignedByte(packet.getBuffer().readerIndex()));
        }
        return ids;
    }

    private static Harness harness(RakRecoveryMode mode) throws Exception {
        AtomicLong clock = new AtomicLong();
        ChannelDuplexHandler outboundHandler = new ChannelDuplexHandler();
        EmbeddedChannel embeddedChannel = new EmbeddedChannel();
        embeddedChannel.pipeline().addLast(RakSessionCodec.NAME, outboundHandler);
        ChannelHandlerContext context = embeddedChannel.pipeline().context(outboundHandler);

        RakChannelConfig config = (RakChannelConfig) Proxy.newProxyInstance(
                RakChannelConfig.class.getClassLoader(), new Class<?>[]{RakChannelConfig.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMtu")) {
                        return MTU;
                    }
                    if (method.getName().equals("getRecoveryMode")) {
                        return mode;
                    }
                    if (method.getName().equals("getMetrics")) {
                        return null;
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
        RakSlidingWindow window = new RakSlidingWindow(MTU, mode);
        IntObjectMap<RakDatagramPacket> sent = new IntObjectHashMap<>();
        Queue<IntRange> incomingNaks = new ArrayDeque<>();
        set(codec, "recoveryMode", mode);
        set(codec, "slidingWindow", window);
        set(codec, "boundedRecovery", mode == RakRecoveryMode.BOUNDED
                ? new RakBoundedRecovery(clock::get, () -> 0L) : null);
        set(codec, "sentDatagrams", sent);
        set(codec, "pendingRetransmissions", new ArrayDeque<>());
        set(codec, "incomingAcks", new ArrayDeque<>());
        set(codec, "incomingNaks", incomingNaks);
        set(codec, "outgoingAcks", new ArrayDeque<>());
        set(codec, "outgoingNaks", new ArrayDeque<>());
        set(codec, "outgoingPackets", new FastBinaryMinHeap<EncapsulatedPacket>(8));
        set(codec, "outgoingPacketNextWeights", new long[4]);
        set(codec, "orderWriteIndex", new int[16]);
        set(codec, "state", RakState.CONNECTED);
        invoke(codec, "initHeapWeights");
        return new Harness(codec, window, sent, incomingNaks, embeddedChannel, context, clock);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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

    private enum RecoveryTrigger {
        NACK,
        TIMEOUT
    }

    private static final class Harness {
        private final RakSessionCodec codec;
        private final RakSlidingWindow window;
        private final IntObjectMap<RakDatagramPacket> sent;
        private final Queue<IntRange> incomingNaks;
        private final EmbeddedChannel channel;
        private final ChannelHandlerContext context;
        private final AtomicLong clock;
        private final List<ByteBuf> payloads = new ArrayList<>();
        private boolean closed;

        private Harness(RakSessionCodec codec, RakSlidingWindow window, IntObjectMap<RakDatagramPacket> sent,
                        Queue<IntRange> incomingNaks, EmbeddedChannel channel, ChannelHandlerContext context,
                        AtomicLong clock) {
            this.codec = codec;
            this.window = window;
            this.sent = sent;
            this.incomingNaks = incomingNaks;
            this.channel = channel;
            this.context = context;
            this.clock = clock;
        }

        private void enqueue(int id, RakReliability reliability, RakPriority priority) throws Exception {
            ByteBuf payload = Unpooled.buffer(64).writeByte(id).writeZero(63);
            this.payloads.add(payload);
            RakMessage message = new RakMessage(payload, reliability, priority);
            try {
                invoke(this.codec, "send", new Class<?>[]{ChannelHandlerContext.class, RakMessage.class},
                        this.context, message);
            } finally {
                message.release();
            }
        }

        private List<RakDatagramPacket> sendOriginals() throws Exception {
            invoke(this.codec, "sendDatagrams", new Class<?>[]{ChannelHandlerContext.class, long.class, int.class},
                    this.context, this.clock.get(), MTU);
            return this.readOutbound();
        }

        private void nack(int sequenceIndex) throws Exception {
            this.incomingNaks.add(new IntRange(sequenceIndex));
            invoke(this.codec, "handleIncomingAcknowledge",
                    new Class<?>[]{ChannelHandlerContext.class, long.class, Queue.class, boolean.class},
                    this.context, this.clock.get(), this.incomingNaks, true);
        }

        private void sendBoundedRecovery(long time) throws Exception {
            this.clock.set(time);
            invoke(this.codec, "sendBoundedRecovery",
                    new Class<?>[]{ChannelHandlerContext.class, long.class, int.class}, this.context, time, MTU);
        }

        private void sendLegacyTimeoutRecovery(long time) throws Exception {
            this.clock.set(time);
            invoke(this.codec, "sendStaleDatagrams", new Class<?>[]{ChannelHandlerContext.class, long.class},
                    this.context, time);
        }

        private List<RakDatagramPacket> readOutbound() {
            this.channel.flushOutbound();
            List<RakDatagramPacket> datagrams = new ArrayList<>();
            RakDatagramPacket datagram;
            while ((datagram = this.channel.readOutbound()) != null) {
                datagrams.add(datagram);
            }
            return datagrams;
        }

        private void releaseOutbound(List<RakDatagramPacket> datagrams) {
            datagrams.forEach(RakDatagramPacket::release);
        }

        private void closeAndAssertPayloadsReleased() throws Exception {
            if (!this.closed) {
                invoke(this.codec, "releaseSessionResources");
                this.channel.finishAndReleaseAll();
                this.closed = true;
            }
            Assertions.assertEquals(0, this.window.getBytesInFlight());
            Assertions.assertEquals(0, this.window.getUnackedBytes());
            for (ByteBuf payload : this.payloads) {
                Assertions.assertEquals(0, payload.refCnt(), "payload was not reclaimed");
            }
        }
    }
}
