package com.openstudy.modules.customerhistory.dto.response;

import java.util.List;
import com.openstudy.modules.locker.dto.response.LockerAssignmentView;
import com.openstudy.modules.reservation.dto.response.RoomReservationView;

/** Contract schema {@code SpaceHistory}: the rows of the requested scope, unpaged. */
public record SpaceHistory(String customerId, List<RoomReservationView> reservations, List<LockerAssignmentView> assignments) {}
