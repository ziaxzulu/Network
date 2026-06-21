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
    public static final int MIN_BULK_PAYLOAD_SIZE = 17;

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
}
