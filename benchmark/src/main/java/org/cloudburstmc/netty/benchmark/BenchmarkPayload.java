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
import io.netty.buffer.ByteBufAllocator;

public final class BenchmarkPayload {
    public static final byte BULK = (byte) 0x80;
    public static final byte PROBE = (byte) 0x81;
    public static final byte PROBE_ACK = (byte) 0x82;
    public static final byte BATCH = (byte) 0x83;
    public static final int MIN_BULK_PAYLOAD_SIZE = 17;
    public static final int MIN_BATCH_HEADER_SIZE = 21;

    private BenchmarkPayload() {
    }

    public static ByteBuf bulk(ByteBufAllocator allocator, int size, long sequence) {
        ByteBuf buffer = allocator.ioBuffer(size, size);
        buffer.writeByte(BULK);
        buffer.writeLong(sequence);
        buffer.writeLong(System.nanoTime());
        if (size > MIN_BULK_PAYLOAD_SIZE) {
            buffer.writeZero(size - MIN_BULK_PAYLOAD_SIZE);
        }
        return buffer;
    }

    public static ByteBuf batch(ByteBufAllocator allocator, int size, long sequence, int logicalPackets) {
        int packetCount = Math.max(1, logicalPackets);
        int minSize = minBatchPayloadSize(packetCount);
        if (size < minSize) {
            throw new IllegalArgumentException("Batch payload size " + size + " is smaller than minimum " + minSize);
        }

        ByteBuf buffer = allocator.ioBuffer(size, size);
        buffer.writeByte(BATCH);
        buffer.writeLong(sequence);
        buffer.writeLong(System.nanoTime());
        buffer.writeInt(packetCount);

        int remaining = size - MIN_BATCH_HEADER_SIZE - (packetCount * Integer.BYTES);
        for (int i = 0; i < packetCount; i++) {
            int logicalSize = remaining / (packetCount - i);
            buffer.writeInt(logicalSize);
            if (logicalSize > 0) {
                buffer.writeZero(logicalSize);
            }
            remaining -= logicalSize;
        }
        return buffer;
    }

    public static ByteBuf probe(ByteBufAllocator allocator, long sequence, long sentNanos) {
        ByteBuf buffer = allocator.ioBuffer(MIN_BULK_PAYLOAD_SIZE, MIN_BULK_PAYLOAD_SIZE);
        buffer.writeByte(PROBE);
        buffer.writeLong(sequence);
        buffer.writeLong(sentNanos);
        return buffer;
    }

    public static ByteBuf probeAck(ByteBufAllocator allocator, long sequence, long sentNanos) {
        ByteBuf buffer = allocator.ioBuffer(MIN_BULK_PAYLOAD_SIZE, MIN_BULK_PAYLOAD_SIZE);
        buffer.writeByte(PROBE_ACK);
        buffer.writeLong(sequence);
        buffer.writeLong(sentNanos);
        return buffer;
    }

    public static byte type(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return 0;
        }
        return buffer.getByte(buffer.readerIndex());
    }

    public static long sequence(ByteBuf buffer) {
        return buffer.getLong(buffer.readerIndex() + 1);
    }

    public static long timestampNanos(ByteBuf buffer) {
        return buffer.getLong(buffer.readerIndex() + 9);
    }

    public static int logicalPackets(ByteBuf buffer) {
        if (type(buffer) != BATCH || buffer.readableBytes() < MIN_BATCH_HEADER_SIZE) {
            return 1;
        }
        return Math.max(1, buffer.getInt(buffer.readerIndex() + MIN_BULK_PAYLOAD_SIZE));
    }

    public static int minBatchPayloadSize(int logicalPackets) {
        return MIN_BATCH_HEADER_SIZE + (Math.max(1, logicalPackets) * Integer.BYTES);
    }
}
