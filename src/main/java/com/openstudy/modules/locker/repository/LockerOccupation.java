package com.openstudy.modules.locker.repository;

/**
 * The one definition of what occupies a locker today. The list filter, the occupancy counts and the mapping behind every
 * response are built from these fragments, so they cannot disagree. {@code :today} is the Asia/Seoul date from the
 * service; the database clock is UTC and is never asked.
 */
final class LockerOccupation {
    private static final String ENTITY = "com.openstudy.modules.locker.entity.";
    static final String ACTIVE = ENTITY + "LockerAssignmentStatus.ACTIVE";
    static final String RETURNED = ENTITY + "LockerAssignmentStatus.RETURNED";
    static final String AVAILABLE = "l.status = " + ENTITY + "LockerState.AVAILABLE";
    static final String BLOCKED = "l.status = " + ENTITY + "LockerState.BLOCKED";
    /** Started and neither returned nor cancelled. A period that ended without a return still counts: the things are inside. */
    static final String STARTED = "a.status = " + ACTIVE + " AND a.startDate <= :today";
    static final String OCCUPIED = "EXISTS (SELECT 1 FROM LockerAssignment a WHERE a.lockerId = l.id AND " + STARTED + ")";
    static final String OVERDUE = "EXISTS (SELECT 1 FROM LockerAssignment a WHERE a.lockerId = l.id AND " + STARTED + " AND a.endDate <= :today)";

    private LockerOccupation() {}
}
