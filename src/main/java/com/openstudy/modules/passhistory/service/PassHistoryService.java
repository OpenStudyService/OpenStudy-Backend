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
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
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

    private final CustomerService customerService;
    private final PassService passService;
    private final SeatSessionService seatSessionService;
    private final PaymentService paymentService;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustomerPassHistory findHistory(long actor, long id, String scope, String from, String to) {
        TimeRange range = TimeRange.parse(from, to);
        AuthorizationState state = authorizationService.current(actor);
        CustomerSummary customer = customerService.requireVisible(state, id, scope);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        List<PassLedger> ledgers = passService.ledgers(id, selected, clock.instant());
        List<Long> paymentIds = ledgers.stream()
                .map(PassLedger::paymentId)
                .toList();
        Map<Long, PaymentTrail> trails = paymentService.trails(paymentIds);
        List<Long> passIds = ledgers.stream()
                .map(PassLedger::id)
                .toList();
        Map<Long, List<SeatUsage>> stays = seatSessionService.usage(passIds)
                .stream()
                .collect(Collectors.groupingBy(SeatUsage::passId));
        Names names = new Names(employeeAccountService.names(findActorIds(ledgers, trails, stays, selected)));

        List<PassHistoryEvent> events = new ArrayList<>();

        for (PassLedger pass : ledgers) {
            events.addAll(toEvents(pass, trails.get(pass.paymentId()), stays, selected, names));
        }

        List<PassHistoryEvent> shown = events.stream()
                .filter(event -> range.contains(event.at()))
                .sorted(Comparator.comparing(PassHistoryEvent::at)
                        .thenComparingInt(event -> ORDER.indexOf(event.type())))
                .toList();
        List<PassLedgerCheck> checks = ledgers.stream()
                .map(PassHistoryService::toCheck)
                .toList();

        return new CustomerPassHistory(customer, range.from(), range.to(), shown, checks);
    }

    /** One pass's events in the order they were collected: purchase, adjustments, stays, then refund. */
    private static List<PassHistoryEvent> toEvents(
            PassLedger pass,
            PaymentTrail trail,
            Map<Long, List<SeatUsage>> stays,
            Set<Long> selected,
            Names names
    ) {
        List<PassHistoryEvent> events = new ArrayList<>();
        events.add(toPurchase(pass, trail, names));

        Map<Long, PassLedger.Entry> uses = new HashMap<>();

        for (PassLedger.Entry entry : pass.entries()) {
            if (entry.adjustment()) {
                events.add(toAdjustment(pass, entry, names));
            } else {
                uses.put(entry.seatSessionId(), entry);
            }
        }

        for (SeatUsage stay : stays.getOrDefault(pass.id(), List.of())) {
            PassLedger.Entry use = uses.get(stay.id());
            boolean inScope = selected.contains(stay.branchId());
            events.add(inScope ? toStay(pass, stay, use, names) : toForeignStay(pass, stay, use));
        }

        if (trail.refund() != null) {
            events.add(toRefund(pass, trail, names));
        }

        return events;
    }

    /** Only names that can be shown are looked up: a foreign stay's handler never enters this request's result. */
    private static Set<Long> findActorIds(
            List<PassLedger> ledgers,
            Map<Long, PaymentTrail> trails,
            Map<Long, List<SeatUsage>> stays,
            Set<Long> selected
    ) {
        Set<Long> ids = new HashSet<>();

        for (PassLedger pass : ledgers) {
            PaymentTrail trail = trails.get(pass.paymentId());
            ids.add(trail.soldBy());

            if (trail.refund() != null) {
                ids.add(trail.refund().refundedBy());
            }

            pass.entries()
                    .stream()
                    .filter(PassLedger.Entry::adjustment)
                    .map(PassLedger.Entry::actorId)
                    .forEach(ids::add);
            stays.getOrDefault(pass.id(), List.of())
                    .stream()
                    .filter(stay -> selected.contains(stay.branchId()))
                    .map(SeatUsage::actorId)
                    .forEach(ids::add);
        }

        ids.remove(null);
        return ids;
    }

    private static PassHistoryEvent toPurchase(PassLedger pass, PaymentTrail trail, Names names) {
        return new PassHistoryEvent(
                trail.approvedAt(),
                PURCHASE,
                toId(pass.id()),
                pass.productName(),
                0,
                0,
                null,
                toId(pass.purchaseBranchId()),
                toId(pass.paymentId()),
                null,
                null,
                names.toActor(trail.soldBy())
        );
    }

    private static PassHistoryEvent toRefund(PassLedger pass, PaymentTrail trail, Names names) {
        PaymentTrail.RefundTrail refund = trail.refund();

        return new PassHistoryEvent(
                refund.refundedAt(),
                REFUND,
                toId(pass.id()),
                pass.productName(),
                0,
                0,
                null,
                toId(refund.branchId()),
                toId(pass.paymentId()),
                null,
                refund.reason(),
                names.toActor(refund.refundedBy())
        );
    }

    /** Adjustments happen only at the purchase branch (M5-D04), which is inside the scope for every pass here. */
    private static PassHistoryEvent toAdjustment(PassLedger pass, PassLedger.Entry entry, Names names) {
        return new PassHistoryEvent(
                entry.at(),
                ADJUST,
                toId(pass.id()),
                pass.productName(),
                entry.deltaMinutes(),
                entry.deltaKrw(),
                null,
                toId(pass.purchaseBranchId()),
                null,
                null,
                entry.reason(),
                names.toActor(entry.actorId())
        );
    }

    /** {@code use} is null when nothing was deducted: a period pass, a zero-minute stay, an expired or empty balance. */
    private static PassHistoryEvent toStay(PassLedger pass, SeatUsage stay, PassLedger.Entry use, Names names) {
        return new PassHistoryEvent(
                stay.checkedOutAt(),
                USE,
                toId(pass.id()),
                pass.productName(),
                use == null ? 0 : use.deltaMinutes(),
                use == null ? 0 : use.deltaKrw(),
                stay.usedMinutes(),
                toId(stay.branchId()),
                null,
                toId(stay.id()),
                stay.reason(),
                names.toActor(stay.actorId())
        );
    }

    /** Branch, time and deltas, and nothing else: no minutes used, no seat session reference, no handler, no reason. */
    private static PassHistoryEvent toForeignStay(PassLedger pass, SeatUsage stay, PassLedger.Entry use) {
        return new PassHistoryEvent(
                stay.checkedOutAt(),
                USE,
                toId(pass.id()),
                pass.productName(),
                use == null ? 0 : use.deltaMinutes(),
                use == null ? 0 : use.deltaKrw(),
                null,
                toId(stay.branchId()),
                null,
                null,
                null,
                null
        );
    }

    private static PassLedgerCheck toCheck(PassLedger pass) {
        return new PassLedgerCheck(
                toId(pass.id()),
                pass.kind(),
                pass.productName(),
                pass.status(),
                toId(pass.purchaseBranchId()),
                pass.issuedMinutes(),
                pass.issuedCreditKrw(),
                pass.ledgerMinutes(),
                pass.ledgerKrw(),
                pass.remainingMinutes(),
                pass.remainingCreditKrw()
        );
    }

    private static String toId(long value) {
        return Long.toString(value);
    }

    private record Names(Map<Long, String> names) {

        ActorSummary toActor(Long id) {
            return id == null ? null : new ActorSummary(id.toString(), names.getOrDefault(id, ""));
        }
    }
}
