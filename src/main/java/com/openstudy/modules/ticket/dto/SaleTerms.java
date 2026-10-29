package com.openstudy.modules.ticket.dto;

/** A sellable product as locked for one purchase: the price and terms to charge and the revision to reference. */
public record SaleTerms(long productId, long revisionId, ProductSnapshot snapshot) {}
