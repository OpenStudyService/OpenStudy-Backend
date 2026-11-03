package com.openstudy.modules.customerhistory.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.customerhistory.dto.response.SpaceHistory;
import com.openstudy.modules.locker.service.LockerAssignmentService;
import com.openstudy.modules.reservation.service.RoomReservationService;

/**
 * A customer's room reservations and locker assignments on the customer detail (FR-MEM-03). Two gates apply: the customer
 * must be in the general lookup scope (M4-D05, so an exact match alone is a 404) and only rows of the requested scope's
 * branches are shown. The rows are the ledger's own views, and the single read-only transaction gives both the same snapshot.
 */
@Service
@RequiredArgsConstructor
public class CustomerSpaceHistoryService {
    private final CustomerService customers;
    private final RoomReservationService reservations;
    private final LockerAssignmentService assignments;
    private final AuthorizationService policy;

    @Transactional(readOnly = true)
    public SpaceHistory history(long actor, long id, String scope) {
        var state = policy.current(actor);
        var customer = customers.requireVisible(state, id, scope);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        return new SpaceHistory(customer.id(), reservations.ofCustomer(state, id, selected), assignments.ofCustomer(state, id, selected));
    }
}
