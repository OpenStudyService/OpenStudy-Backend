package com.openstudy.modules.payment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    /**
     * Row lock for a refund, taken after the idempotency lock and before the pass lock (contract transaction table).
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM payments WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /** Which reservation paid with which payment. */
    interface RoomReservationRef {
        long getPaymentId();
        long getReservationId();
    }

    /**
     * JPA-01 native exception: the reservation module depends on this one, so its entity cannot be named here and the
     * dependency stays one way. uk_room_reservations_2 keeps it to one reservation per payment.
     */
    @Query(value = "SELECT payment_id AS paymentId, id AS reservationId FROM room_reservations WHERE payment_id IN (:ids)", nativeQuery = true)
    List<RoomReservationRef> roomReservations(@Param("ids") Collection<Long> ids);

    /** Which locker assignment a payment bought or extended. */
    interface LockerAssignmentRef {
        long getPaymentId();
        long getAssignmentId();
    }

    /**
     * JPA-01 native exception, for the same reason as above. uk_locker_assignment_charges_1 keeps it to one charge per
     * payment, and an extension names the assignment it extended.
     */
    @Query(value = "SELECT payment_id AS paymentId, assignment_id AS assignmentId FROM locker_assignment_charges WHERE payment_id IN (:ids)", nativeQuery = true)
    List<LockerAssignmentRef> lockerAssignments(@Param("ids") Collection<Long> ids);
}
