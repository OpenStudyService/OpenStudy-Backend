package com.openstudy.modules.ticket.entity;

/** Stored pass status. NOT_STARTED stays in the V1 enum for old rows; new issues never use it (M4-D01). */
public enum PassStatus { ACTIVE, EXHAUSTED, EXPIRED, REFUNDED, NOT_STARTED }
