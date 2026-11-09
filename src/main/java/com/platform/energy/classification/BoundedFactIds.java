package com.platform.energy.classification;

import com.platform.framework.exception.BusinessException;
import org.springframework.dao.QueryTimeoutException;

import java.nio.charset.StandardCharsets;

/**
 * Request-owned candidate identities with fixed primitive storage; sorting does not retain per-fact objects.
 * Insertion ordinals preserve the scan order while unsigned UTF-8 order supports deterministic lookup.
 * This ordering is local only: it must not replace a database cursor's collation rules.
 */
final class BoundedFactIds {
    private static final int MAXIMUM_ROWS = 400_000;
    private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
    private final byte[] bytes;
    private final int[] offsets;
    private final int[] order;
    private final int[] scratch;
    private final Runnable checkRemaining;
    private int size;
    private int usedBytes;
    private int comparisons;
    private boolean sorted;

    BoundedFactIds(int maximumRows, int maximumBytes) {
        this(maximumRows, maximumBytes, () -> { });
    }

    BoundedFactIds(int maximumRows, int maximumBytes, Runnable checkRemaining) {
        if (maximumRows <= 0 || maximumRows > MAXIMUM_ROWS || maximumBytes <= 0 || maximumBytes > MAXIMUM_BYTES) {
            throw capacity();
        }
        if (checkRemaining == null) throw integrity();
        this.checkRemaining = checkRemaining;
        checkBudget();
        bytes = new byte[maximumBytes];
        offsets = new int[maximumRows + 1];
        order = new int[maximumRows];
        scratch = new int[maximumRows];
    }

    void add(String id) {
        checkBudget();
        if (sorted || id == null || id.isEmpty()) throw integrity();
        if (size == order.length || id.length() > bytes.length - usedBytes) throw capacity();
        // Reject malformed UTF-16 rather than silently replacing an identity during UTF-8 encoding.
        for (int index = 0; index < id.length(); index++) {
            char value = id.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index == id.length() || !Character.isLowSurrogate(id.charAt(index))) throw integrity();
            } else if (Character.isLowSurrogate(value)) throw integrity();
        }
        byte[] encoded = id.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > bytes.length - usedBytes) throw capacity();
        System.arraycopy(encoded, 0, bytes, usedBytes, encoded.length);
        usedBytes += encoded.length;
        order[size] = size;
        offsets[++size] = usedBytes;
    }

    void sort() {
        checkBudget();
        if (sorted) return;
        // Merge primitive ordinals rather than boxing up to 400,000 indices or materializing ID Strings.
        for (int width = 1; width < size; width *= 2) {
            checkBudget();
            for (int left = 0; left < size; left += width * 2) {
                int middle = Math.min(left + width, size);
                int right = Math.min(left + width * 2, size);
                int first = left, second = middle, output = left;
                while (first < middle && second < right) {
                    scratch[output++] = compare(order[first], order[second]) <= 0 ? order[first++] : order[second++];
                }
                if (first < middle) System.arraycopy(order, first, scratch, output, middle - first);
                else if (second < right) System.arraycopy(order, second, scratch, output, right - second);
            }
            System.arraycopy(scratch, 0, order, 0, size);
        }
        for (int index = 1; index < size; index++) {
            if (compare(order[index - 1], order[index]) == 0) throw integrity();
        }
        checkBudget();
        sorted = true;
    }

    int size() { return size; }

    long encodedBytesUsed() { return usedBytes; }

    String idAtSorted(int index) {
        int ordinal = ordinalAtSorted(index);
        return new String(bytes, offsets[ordinal], offsets[ordinal + 1] - offsets[ordinal], StandardCharsets.UTF_8);
    }

    int ordinalAtSorted(int index) {
        if (!sorted || index < 0 || index >= size) throw integrity();
        return order[index];
    }

    /** Primitive array payload only; excludes the constant object/array headers and caller-owned callback. */
    long allocatedPrimitiveBytes() {
        return bytes.length + (long) Integer.BYTES * (offsets.length + order.length + scratch.length);
    }

    private int compare(int first, int second) {
        if ((comparisons++ & 1023) == 0) checkBudget();
        int startFirst = offsets[first], startSecond = offsets[second];
        int lengthFirst = offsets[first + 1] - startFirst, lengthSecond = offsets[second + 1] - startSecond;
        for (int index = 0; index < Math.min(lengthFirst, lengthSecond); index++) {
            int difference = Byte.toUnsignedInt(bytes[startFirst + index]) - Byte.toUnsignedInt(bytes[startSecond + index]);
            if (difference != 0) return difference;
        }
        return Integer.compare(lengthFirst, lengthSecond);
    }

    private void checkBudget() {
        if (Thread.currentThread().isInterrupted()) throw new QueryTimeoutException("Monthly candidate identity preparation interrupted");
        checkRemaining.run();
    }

    private static BusinessException capacity() {
        return new BusinessException(503, "DAILY_SOURCE_CAPACITY", "Monthly candidate identities exceed bounded capacity");
    }

    private static BusinessException integrity() {
        return new BusinessException(503, "DAILY_SOURCE_INTEGRITY", "Monthly candidate identity integrity check failed");
    }
}
