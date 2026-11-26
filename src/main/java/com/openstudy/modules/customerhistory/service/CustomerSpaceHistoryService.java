package com.openstudy.modules.customerhistory.service;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.customerhistory.dto.response.SpaceHistory;
import com.openstudy.modules.locker.dto.response.LockerAssignmentView;
import com.openstudy.modules.locker.service.LockerAssignmentService;
import com.openstudy.modules.reservation.dto.response.RoomReservationView;
import com.openstudy.modules.reservation.service.RoomReservationService;

/**
 * A customer's room reservations and locker assignments on the customer detail (FR-MEM-03). Two gates apply: the customer
 * must be in the general lookup scope (M4-D05, so an exact match alone is a 404) and only rows of the requested scope's
 * branches are shown. The rows are the ledger's own views, and the single read-only transaction gives both the same
 * snapshot.
 */
@Service
@RequiredArgsConstructor
public class CustomerSpaceHistoryService {

    private final CustomerService customerService;
    private final RoomReservationService roomReservationService;
    private final LockerAssignmentService lockerAssignmentService;
    private final AuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public SpaceHistory getHistory(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        CustomerSummary customer = customerService.requireVisible(state, id, scope);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        List<RoomReservationView> reservations = roomReservationService.ofCustomer(state, id, selected);
        List<LockerAssignmentView> assignments = lockerAssignmentService.ofCustomer(state, id, selected);

        return new SpaceHistory(customer.id(), reservations, assignments);
    }
}
