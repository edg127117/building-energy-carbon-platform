package com.platform.carbon;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** MySQL COMPRESS: little-endian original length, zlib stream, optional terminal dot. */
final class MysqlCompressedText {
    private MysqlCompressedText() { }

    static String decode(byte[] input, int maximumBytes) {
        if (input == null) return null;
        if (input.length == 0) return "";
        if (maximumBytes < 0 || input.length < 5) throw invalid();
        long expected = Integer.toUnsignedLong(ByteBuffer.wrap(input, 0, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        if (expected > maximumBytes) throw invalid();
        // The header is untrusted. Allocate incrementally, and bound actual expansion independently.
        var output = new ByteArrayOutputStream((int) Math.min(expected, 8192));
        var inflater = new Inflater();
        try {
            inflater.setInput(input, 4, input.length - 4);
            byte[] block = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(block);
                if ((long) output.size() + count > expected) throw invalid();
                output.write(block, 0, count);
                if (count == 0 && !inflater.finished()) throw invalid();
            }
            int remaining = inflater.getRemaining();
            // The guard protects the final ZLIB BYTE, not a space in the uncompressed text.
            // Actual MySQL COMPRESS(UNHEX('1F')) ends 20 2E although its input is not a space.
            boolean mysqlCompressedStreamGuard = remaining == 1 && input[input.length - 1] == '.'
                    && input[input.length - 2] == 0x20;
            if (remaining != 0 && !mysqlCompressedStreamGuard) throw invalid();
            if (output.size() != expected) throw invalid();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (DataFormatException | CharacterCodingException exception) {
            throw invalid();
        } finally {
            inflater.end();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid MySQL compressed evidence");
    }
}
