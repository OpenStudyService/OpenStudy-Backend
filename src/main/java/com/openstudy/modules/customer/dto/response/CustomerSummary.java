package com.openstudy.modules.customer.dto.response;

/** Contract schema {@code CustomerSummary}: the minimum to identify a customer on payment and pass views. */
public record CustomerSummary(String id, String memberNo, String name) {}
