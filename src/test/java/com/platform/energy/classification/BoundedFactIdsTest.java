package com.platform.energy.classification;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class BoundedFactIdsTest {
    @Test void sortsUnsignedUtf8AndPreservesInsertionOrdinalsAndExactIdentities() {
        var original = List.of("z", "\u7535", "aa", "a", "\ud83d\ude00", "\ue000", "\u00e9", "e\u0301", "A", "\u0080");
        var ids = new BoundedFactIds(original.size(), 256);
        original.forEach(ids::add);
        ids.sort();
        var expected = new ArrayList<>(original);
        expected.sort((left, right) -> Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)));
        assertThat(ids.size()).isEqualTo(original.size());
        for (int index = 0; index < expected.size(); index++) {
            assertThat(ids.idAtSorted(index)).isEqualTo(expected.get(index));
            assertThat(ids.ordinalAtSorted(index)).isEqualTo(original.indexOf(expected.get(index)));
        }
        ids.sort();
        assertThat(ids.idAtSorted(0)).isEqualTo("A");
    }

    @Test void emptySingletonAndNonPowerOfTwoInputsSortCorrectly() {
        var empty = new BoundedFactIds(1, 1);
        empty.sort();
        assertThat(empty.size()).isZero();
        assertIntegrity(() -> empty.idAtSorted(0));
        var singleton = new BoundedFactIds(1, 1);
        singleton.add("a");
        singleton.sort();
        assertThat(singleton.idAtSorted(0)).isEqualTo("a");
        assertThat(singleton.ordinalAtSorted(0)).isZero();
        var ids = new BoundedFactIds(257, 2048);
        var expected = new ArrayList<String>();
        for (int index = 0; index < 257; index++) expected.add("id-" + index);
        var shuffled = new ArrayList<>(expected);
        Collections.shuffle(shuffled, new Random(37));
        shuffled.forEach(ids::add);
        ids.sort();
        expected.sort(Comparator.naturalOrder());
        for (int index = 0; index < expected.size(); index++) {
            assertThat(ids.idAtSorted(index)).isEqualTo(expected.get(index));
            assertThat(ids.ordinalAtSorted(index)).isEqualTo(shuffled.indexOf(expected.get(index)));
        }
    }

    @Test void rowLimitAndUtf8ByteLimitAreExactAndFailedAddsDoNotChangeSnapshot() {
        var rowLimited = new BoundedFactIds(1, 32);
        rowLimited.add("a");
        assertCapacity(() -> rowLimited.add("b"));
        rowLimited.sort();
        assertThat(rowLimited.size()).isEqualTo(1);
        assertThat(rowLimited.idAtSorted(0)).isEqualTo("a");
        var byteLimited = new BoundedFactIds(3, 4);
        assertThat(byteLimited.encodedBytesUsed()).isZero();
        byteLimited.add("\u7535");
        assertThat(byteLimited.encodedBytesUsed()).isEqualTo(3);
        assertCapacity(() -> byteLimited.add("\u00e9"));
        assertThat(byteLimited.encodedBytesUsed()).isEqualTo(3);
        byteLimited.add("a");
        assertCapacity(() -> byteLimited.add("b"));
        byteLimited.sort();
        assertThat(byteLimited.size()).isEqualTo(2);
        assertThat(byteLimited.encodedBytesUsed()).isEqualTo(4);
        assertThat(byteLimited.idAtSorted(0)).isEqualTo("a");
        assertThat(byteLimited.idAtSorted(1)).isEqualTo("\u7535");
    }

    @Test void validatesCapacityBeforeAllocating() {
        for (int rows : new int[]{-1, 0, 400_001, Integer.MAX_VALUE}) assertCapacity(() -> new BoundedFactIds(rows, 1));
        for (int bytes : new int[]{-1, 0, 16 * 1024 * 1024 + 1, Integer.MAX_VALUE}) assertCapacity(() -> new BoundedFactIds(1, bytes));
        assertIntegrity(() -> new BoundedFactIds(1, 1, null));
    }

    @Test void rejectsInvalidIdentitiesUnsortedAccessAndMutationAfterSorting() {
        var ids = new BoundedFactIds(5, 64);
        assertIntegrity(() -> ids.add(null));
        assertIntegrity(() -> ids.add(""));
        assertIntegrity(() -> ids.add("\ud800"));
        assertIntegrity(() -> ids.add("\udc00"));
        assertThat(ids.size()).isZero();
        ids.add("a");
        assertIntegrity(() -> ids.idAtSorted(0));
        assertIntegrity(() -> ids.ordinalAtSorted(0));
        ids.sort();
        assertIntegrity(() -> ids.add("b"));
        assertIntegrity(() -> ids.idAtSorted(-1));
        assertIntegrity(() -> ids.ordinalAtSorted(1));
        assertThat(ids.size()).isEqualTo(1);
    }

    @Test void duplicateDetectionFailsClosedIncludingNonAsciiIdentity() {
        for (String duplicate : List.of("fact", "\u7535\ud83d\ude00")) {
            var ids = new BoundedFactIds(3, 128);
            ids.add(duplicate);
            ids.add("other");
            ids.add(duplicate);
            assertIntegrity(ids::sort);
            assertIntegrity(() -> ids.idAtSorted(0));
        }
    }

    @Test void checksLiveBudgetDuringMergeWorkAndDoesNotExposePartialSort() {
        var sorting = new AtomicBoolean();
        var checks = new AtomicInteger();
        var failure = new QueryTimeoutException("budget expired");
        var ids = new BoundedFactIds(4096, 32768, () -> {
            if (sorting.get() && checks.incrementAndGet() == 4) throw failure;
        });
        for (int index = 4095; index >= 0; index--) ids.add("id-" + index);
        sorting.set(true);
        assertThatThrownBy(ids::sort).isSameAs(failure);
        assertThat(checks.get()).isEqualTo(4);
        assertIntegrity(() -> ids.idAtSorted(0));
    }

    @Test void addChecksBudgetBeforeMutatingAndSortHonorsThreadInterruption() {
        var expired = new AtomicBoolean();
        var ids = new BoundedFactIds(2, 8, () -> {
            if (expired.get()) throw new QueryTimeoutException("budget expired");
        });
        ids.add("b");
        expired.set(true);
        assertThatThrownBy(() -> ids.add("a")).isInstanceOf(QueryTimeoutException.class);
        assertThat(ids.size()).isEqualTo(1);
        expired.set(false);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(ids::sort).isInstanceOf(QueryTimeoutException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        ids.sort();
        assertThat(ids.idAtSorted(0)).isEqualTo("b");
    }

    @Test void primitiveAllocationIsFixedAcrossAddAndSortAndCappedAtMaximum() {
        var ids = new BoundedFactIds(7, 32);
        long expected = 32L + Integer.BYTES * (8L + 7 + 7);
        assertThat(ids.allocatedPrimitiveBytes()).isEqualTo(expected);
        ids.add("z");
        ids.add("a");
        ids.sort();
        assertThat(ids.allocatedPrimitiveBytes()).isEqualTo(expected);
        var maximum = new BoundedFactIds(400_000, 16 * 1024 * 1024);
        assertThat(maximum.allocatedPrimitiveBytes()).isEqualTo(21_577_220L);
    }

    private static void assertCapacity(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).satisfies(error -> {
            assertThat(((BusinessException) error).getCode()).isEqualTo(503);
            assertThat(((BusinessException) error).getErrorCode()).isEqualTo("DAILY_SOURCE_CAPACITY");
        });
    }

    private static void assertIntegrity(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).satisfies(error -> {
            assertThat(((BusinessException) error).getCode()).isEqualTo(503);
            assertThat(((BusinessException) error).getErrorCode()).isEqualTo("DAILY_SOURCE_INTEGRITY");
        });
    }
}
