package org.cloudburstmc.netty.benchmark;

import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonEvidenceTest {
    @Test void noProbeSamplesAreUnavailableRatherThanZeroLatency() {
        assertNull(ComparisonMain.percentile(new LatencyHistogram(), 99));
    }

    @Test void transportPayloadValidationCatchesCorruptionAndTruncation() {
        var payload = BenchmarkPayload.bulk(UnpooledByteBufAllocator.DEFAULT, 262144, 0);
        try {
            ComparisonMain.validatePayload(payload, 0, 262144);
            payload.setByte(20000, 1);
            assertThrows(IllegalStateException.class, () -> ComparisonMain.validatePayload(payload, 0, 262144));
            payload.setByte(20000, 0);
            payload.writerIndex(262143);
            assertThrows(IllegalStateException.class, () -> ComparisonMain.validatePayload(payload, 0, 262144));
        } finally { payload.release(); }
    }

    @Test void aCorruptedBatchLengthCannotCountAsDeliveredData() {
        var batch = BenchmarkPayload.batch(UnpooledByteBufAllocator.DEFAULT, 512, 1, 8);
        try {
            ComparisonMain.validatePayload(batch, 1, 512);
            batch.setInt(BenchmarkPayload.MIN_BATCH_HEADER_SIZE, Integer.MAX_VALUE);
            assertThrows(IllegalStateException.class, () -> ComparisonMain.validatePayload(batch, 1, 512));
        } finally { batch.release(); }
    }
}
