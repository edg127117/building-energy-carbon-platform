package com.platform.iot.daikin.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DaikinCatalogReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC);
    private final DaikinDevicePageDecoder decoder = new DaikinDevicePageDecoder(
            DaikinDevicePageDecoder.FieldPolicy.unconfirmed());
    private final DaikinCatalogReader reader = new DaikinCatalogReader(decoder, clock, 3, 10);

    @Test
    void readsCompleteCatalogInPageOrderWithoutTransportOrDatabase() {
        List<Integer> requests = new ArrayList<>();
        var result = reader.read("test-source", DaikinDeviceKey.Kind.INDOOR, page -> {
            requests.add(page);
            return page(page, 2, 2, Integer.toString(page));
        });
        assertThat(requests).containsExactly(1, 2);
        assertThat(result).extracting(item -> item.key().unitId()).containsExactly("1", "2");
        assertThat(result).allMatch(item -> item.observedAt().equals(clock.instant()));
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotPublishPartialResultAfterLaterPageFails() {
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.OUTDOOR, page -> {
            if (page == 2) throw new IllegalStateException("test transport failure");
            return page(1, 2, 2, "1");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsChangingTotalsDuplicateIdsAndWrongPageNumber() {
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.INDOOR,
                page -> page(page, page == 1 ? 2 : 3, 2, Integer.toString(page))))
                .hasMessage("DAIKIN_CATALOG_INCOMPLETE_OR_UNSTABLE");
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.INDOOR,
                page -> page(page, 2, 2, "duplicate")))
                .hasMessage("DAIKIN_CATALOG_INCOMPLETE_OR_UNSTABLE");
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.INDOOR,
                page -> page(1, 2, 2, Integer.toString(page))))
                .hasMessage("DAIKIN_CATALOG_INCOMPLETE_OR_UNSTABLE");
    }

    @Test
    void rejectsCountMismatchAndLimitsBeforeFetchingMorePages() {
        AtomicInteger requests = new AtomicInteger();
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.INDOOR, page -> {
            requests.incrementAndGet();
            return page(1, 4, 4, "1");
        })).hasMessage("DAIKIN_CATALOG_INCOMPLETE_OR_UNSTABLE");
        assertThat(requests).hasValue(1);
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.INDOOR,
                page -> page(1, 1, 2, "1")))
                .hasMessage("DAIKIN_CATALOG_INCOMPLETE_OR_UNSTABLE");
    }

    @Test
    void lostLeaseStopsBeforeNextPageAndDoesNotDeliverPartialCatalog() {
        var requests = new ArrayList<Integer>();
        AtomicInteger leaseChecks = new AtomicInteger();
        assertThatThrownBy(() -> reader.read("test-source", DaikinDeviceKey.Kind.INDOOR, current -> {
            requests.add(current);
            return page(current, 3, 3, Integer.toString(current));
        }, () -> {
            if (leaseChecks.incrementAndGet() == 2) throw new IllegalStateException("lease lost");
        })).isInstanceOf(IllegalStateException.class).hasMessage("lease lost");
        assertThat(requests).containsExactly(1);
        assertThat(leaseChecks).hasValue(2);
    }

    @Test
    void invalidLocalIdentityNeverTriggersFetch() {
        AtomicInteger requests = new AtomicInteger();
        assertThatThrownBy(() -> reader.read(" ", DaikinDeviceKey.Kind.INDOOR, page -> {
            requests.incrementAndGet();
            return page(1, 1, 1, "1");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }

    private JsonNode page(int current, int pages, int count, String unitId) {
        ObjectNode result = mapper.createObjectNode().put("code", "10000");
        ObjectNode data = result.putObject("data").put("curPage", current)
                .put("totalPages", pages).put("totalCount", count);
        data.putArray("sites").addObject().put("siteId", "site-1")
                .putArray("controlers").addObject().put("lcNo", "lc-1")
                .putArray("units").addObject().put("unitId", unitId);
        return result;
    }
}
