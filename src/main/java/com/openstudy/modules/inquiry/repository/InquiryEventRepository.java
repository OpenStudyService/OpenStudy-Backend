package com.openstudy.modules.inquiry.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.inquiry.entity.InquiryEvent;

public interface InquiryEventRepository extends JpaRepository<InquiryEvent, Long> {
    long countByInquiryId(long inquiryId);
}
