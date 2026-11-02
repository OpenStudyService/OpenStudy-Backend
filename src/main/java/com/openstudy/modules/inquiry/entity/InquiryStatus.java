package com.openstudy.modules.inquiry.entity;

/** RECEIVED and IN_PROGRESS are the unresolved states; ANSWERED is reached only by answering (M7-D02). */
public enum InquiryStatus {
    RECEIVED, IN_PROGRESS, ANSWERED
}
