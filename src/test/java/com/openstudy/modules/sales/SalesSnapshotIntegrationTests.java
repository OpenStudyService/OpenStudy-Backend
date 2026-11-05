package com.openstudy.modules.sales;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Issue #87, one read per response (FR-AGG-09 asOf): a sale committed by another connection after the totals were read
 * but before the ledger page is read stays out of that response, so its totals, page, meta.total and sourceRowCount
 * still agree. The next request sees the sale.
 */
class SalesSnapshotIntegrationTests extends SalesIntegrationSupport {
    @Test void aSaleCommittedDuringTheRequestStaysOutOfThatResponse() throws Exception {
        workedExample();
        var inserted = new AtomicBoolean();
        doAnswer(invocation -> {
            Object totals = invocation.callRealMethod();
            if (inserted.compareAndSet(false, true)) {
                // Another thread, so JdbcTemplate takes another pooled connection in auto-commit (not this request's
                // read-only transaction): the row is committed before the ledger page is read.
                java.util.concurrent.CompletableFuture.runAsync(() -> jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,"
                        + "issued_pass_quantity,approved_at,created_by) VALUES(?,?,'LOCKER','CASH',7000,'APPROVED',?,0,?,?)",
                        customer, first, "TEST-LATE-" + UUID.randomUUID(), utc(seoul("2026-11-20T12:00")), seller)).get();
            }
            return totals;
        }).when(sources).totals(any());
        try (var s = login(reader)) {
            String base = "/api/sales/ledger?" + branchIds(first) + "&" + november() + "&pageSize=100";
            var during = ok(s, base);
            assertThat(inserted.get()).isTrue();
            assertThat(during.get("items")).hasSize(6);
            assertThat(during.at("/meta/total").asLong()).isEqualTo(6);
            assertThat(during.at("/aggregation/sourceRowCount").asLong()).isEqualTo(6);
            amounts(during.get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            long sum = 0;
            for (var item : during.get("items")) sum += item.get("amountKrw").asLong();
            assertThat(sum).isEqualTo(37_000);

            var after = ok(s, base);
            assertThat(after.get("items")).hasSize(7);
            amounts(after.get("totals"), 60_000, 16_000, 44_000, 5, 2, "hasData");
        }
    }
}
