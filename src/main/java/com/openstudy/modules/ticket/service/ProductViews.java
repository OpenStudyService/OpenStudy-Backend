package com.openstudy.modules.ticket.service;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.entity.CategoryStatus;
import com.openstudy.modules.ticket.entity.Product;
import com.openstudy.modules.ticket.entity.ProductKind;
import com.openstudy.modules.ticket.entity.SaleStatus;

/** Entity ↔ service value mapping for the ticket module. Entities never leave the service layer (M1-04). */
final class ProductViews {
    private ProductViews() {}

    static ProductRecord record(Product p) {
        ProductTerms terms = switch (p.getKind()) {
            case TIME -> ProductTerms.Time.of(p.getIncludedMinutes(), p.getValidDays());
            case PERIOD -> ProductTerms.Period.of(p.getPeriodDays());
            case STORED_VALUE -> ProductTerms.StoredValue.of(p.getCreditKrw(), p.getHourlyRateKrw(), p.getValidDays());
        };
        return new ProductRecord(p.getId(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt(), p.getCode(), p.getName(),
                p.getPriceKrw(), terms, p.getSaleStatus() == SaleStatus.ON_SALE, category(p.getCategory()), p.getBranchIds());
    }

    static ProductCategory category(com.openstudy.modules.ticket.entity.ProductCategory c) {
        return new ProductCategory(Long.toString(c.getId()), ApiVersion.fromDatabase(c.getVersion()),
                c.getCreatedAt().toString(), c.getUpdatedAt().toString(), c.getCode(), c.getName(), apiKind(c.getKind()),
                c.getStatus() == CategoryStatus.ACTIVE ? "활성" : "비활성");
    }

    /** The product kind never changes (Q9), so only the term columns of that kind are set. */
    static Product.Terms columns(ProductTerms terms) {
        return switch (terms) {
            case ProductTerms.Time time -> new Product.Terms(time.includedMinutes(), null, null, null, time.validDays());
            case ProductTerms.Period period -> new Product.Terms(null, period.periodDays(), null, null, null);
            case ProductTerms.StoredValue stored -> new Product.Terms(null, null, stored.creditKrw(), stored.hourlyRateKrw(),
                    stored.validDays());
        };
    }

    static String apiKind(ProductKind kind) {
        return switch (kind) {
            case TIME -> ProductTerms.TIME;
            case PERIOD -> ProductTerms.PERIOD;
            case STORED_VALUE -> ProductTerms.STORED_VALUE;
        };
    }

    static ProductKind kind(String apiKind) {
        return switch (apiKind) {
            case ProductTerms.TIME -> ProductKind.TIME;
            case ProductTerms.PERIOD -> ProductKind.PERIOD;
            default -> ProductKind.STORED_VALUE;
        };
    }
}
