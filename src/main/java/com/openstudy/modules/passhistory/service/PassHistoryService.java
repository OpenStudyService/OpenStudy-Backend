package com.openstudy.modules.passhistory.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.passhistory.dto.response.CustomerPassHistory;
import com.openstudy.modules.passhistory.dto.response.PassHistoryEvent;
import com.openstudy.modules.passhistory.dto.response.PassLedgerCheck;
import com.openstudy.modules.payment.dto.response.PaymentTrail;
import com.openstudy.modules.payment.service.PaymentService;
import com.openstudy.modules.seat.dto.response.ActorSummary;
import com.openstudy.modules.seat.dto.response.SeatUsage;
import com.openstudy.modules.seat.service.SeatSessionService;
import com.openstudy.modules.ticket.dto.PassLedger;
import com.openstudy.modules.ticket.service.PassService;

/**
 * A customer's purchases, refunds, stays and adjustments (FR-MEM-07). Two gates apply: the customer must be in the general
 * lookup scope (M4-D05) and only passes bought in the requested scope are included. Such a pass may have been used anywhere
 * (M5-D01), so a stay outside the scope still shows its branch, time and deltas to keep the ledger explainable, and nothing
 * else (2026-10-30 decision). The single read-only transaction gives every part the same snapshot: a check-out or adjustment
 * committing meanwhile cannot split a balance from its ledger.
 */
@Service
@RequiredArgsConstructor
public class PassHistoryService {
    private static final String PURCHASE = "구매";
    private static final String USE = "이용";
    private static final String ADJUST = "조정";
    private static final String REFUND = "환불";
    /** Same-instant events read in the order they must have happened. */
    private static final List<String> ORDER = List.of(PURCHASE, USE, ADJUST, REFUND);
    private final CustomerService customers;
    private final PassService passes;
    private final SeatSessionService sessions;
    private final PaymentService payments;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustomerPassHistory history(long actor, long id, String scope, String from, String to) {
        var range = TimeRange.parse(from, to);
        var state = policy.current(actor);
        var customer = customers.requireVisible(state, id, scope);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var ledgers = passes.ledgers(id, selected, clock.instant());
        var trails = payments.trails(ledgers.stream().map(PassLedger::paymentId).toList());
        Map<Long, List<SeatUsage>> stays = sessions.usage(ledgers.stream().map(PassLedger::id).toList()).stream()
                .collect(Collectors.groupingBy(SeatUsage::passId));
        var names = new Names(employees.names(actors(ledgers, trails, stays, selected)));
        var events = new ArrayList<PassHistoryEvent>();
        for (var pass : ledgers) {
            var trail = trails.get(pass.paymentId());
            events.add(purchase(pass, trail, names));
            Map<Long, PassLedger.Entry> uses = new HashMap<>();
            for (var entry : pass.entries()) {
                if (entry.adjustment()) events.add(adjustment(pass, entry, names));
                else uses.put(entry.seatSessionId(), entry);
            }
            for (var stay : stays.getOrDefault(pass.id(), List.of())) {
                var use = uses.get(stay.id());
                events.add(selected.contains(stay.branchId()) ? stay(pass, stay, use, names) : foreignStay(pass, stay, use));
            }
            if (trail.refund() != null) events.add(refund(pass, trail, names));
        }
        var shown = events.stream().filter(event -> range.contains(event.at()))
                .sorted(Comparator.comparing(PassHistoryEvent::at).thenComparingInt(event -> ORDER.indexOf(event.type()))).toList();
        return new CustomerPassHistory(customer, range.from(), range.to(), shown, ledgers.stream().map(PassHistoryService::check).toList());
    }

    /** Only names that can be shown are looked up: a foreign stay's handler never enters this request's result. */
    private static Set<Long> actors(List<PassLedger> ledgers, Map<Long, PaymentTrail> trails, Map<Long, List<SeatUsage>> stays,
            Set<Long> selected) {
        Set<Long> ids = new HashSet<>();
        for (var pass : ledgers) {
            var trail = trails.get(pass.paymentId());
            ids.add(trail.soldBy());
            if (trail.refund() != null) ids.add(trail.refund().refundedBy());
            pass.entries().stream().filter(PassLedger.Entry::adjustment).map(PassLedger.Entry::actorId).forEach(ids::add);
            stays.getOrDefault(pass.id(), List.of()).stream().filter(stay -> selected.contains(stay.branchId()))
                    .map(SeatUsage::actorId).forEach(ids::add);
        }
        ids.remove(null);
        return ids;
    }

    private static PassHistoryEvent purchase(PassLedger pass, PaymentTrail trail, Names names) {
        return new PassHistoryEvent(trail.approvedAt(), PURCHASE, id(pass.id()), pass.productName(), 0, 0, null, id(pass.purchaseBranchId()),
                id(pass.paymentId()), null, null, names.actor(trail.soldBy()));
    }

    private static PassHistoryEvent refund(PassLedger pass, PaymentTrail trail, Names names) {
        var refund = trail.refund();
        return new PassHistoryEvent(refund.refundedAt(), REFUND, id(pass.id()), pass.productName(), 0, 0, null, id(refund.branchId()),
                id(pass.paymentId()), null, refund.reason(), names.actor(refund.refundedBy()));
    }

    /** Adjustments happen only at the purchase branch (M5-D04), which is inside the scope for every pass here. */
    private static PassHistoryEvent adjustment(PassLedger pass, PassLedger.Entry entry, Names names) {
        return new PassHistoryEvent(entry.at(), ADJUST, id(pass.id()), pass.productName(), entry.deltaMinutes(), entry.deltaKrw(), null,
                id(pass.purchaseBranchId()), null, null, entry.reason(), names.actor(entry.actorId()));
    }

    /** {@code use} is null when nothing was deducted: a period pass, a zero-minute stay, an expired or empty balance. */
    private static PassHistoryEvent stay(PassLedger pass, SeatUsage stay, PassLedger.Entry use, Names names) {
        return new PassHistoryEvent(stay.checkedOutAt(), USE, id(pass.id()), pass.productName(), use == null ? 0 : use.deltaMinutes(),
                use == null ? 0 : use.deltaKrw(), stay.usedMinutes(), id(stay.branchId()), null, id(stay.id()), stay.reason(),
                names.actor(stay.actorId()));
    }

    /** Branch, time and deltas, and nothing else: no minutes used, no seat session reference, no handler, no reason. */
    private static PassHistoryEvent foreignStay(PassLedger pass, SeatUsage stay, PassLedger.Entry use) {
        return new PassHistoryEvent(stay.checkedOutAt(), USE, id(pass.id()), pass.productName(), use == null ? 0 : use.deltaMinutes(),
                use == null ? 0 : use.deltaKrw(), null, id(stay.branchId()), null, null, null, null);
    }

    private static PassLedgerCheck check(PassLedger pass) {
        return new PassLedgerCheck(id(pass.id()), pass.kind(), pass.productName(), pass.status(), id(pass.purchaseBranchId()),
                pass.issuedMinutes(), pass.issuedCreditKrw(), pass.ledgerMinutes(), pass.ledgerKrw(), pass.remainingMinutes(),
                pass.remainingCreditKrw());
    }

    private static String id(long value) { return Long.toString(value); }

    private record Names(Map<Long, String> names) {
        ActorSummary actor(Long id) { return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, "")); }
    }
}
