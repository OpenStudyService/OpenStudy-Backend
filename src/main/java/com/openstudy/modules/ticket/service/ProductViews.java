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

    private static final String ACTIVE_LABEL = "활성";
    private static final String INACTIVE_LABEL = "비활성";

    private ProductViews() {
    }

    static ProductRecord toRecord(Product product) {
        ProductTerms terms = switch (product.getKind()) {
            case TIME -> ProductTerms.Time.of(product.getIncludedMinutes(), product.getValidDays());
            case PERIOD -> ProductTerms.Period.of(product.getPeriodDays());
            case STORED_VALUE -> ProductTerms.StoredValue.of(
                    product.getCreditKrw(),
                    product.getHourlyRateKrw(),
                    product.getValidDays()
            );
        };
        ProductRecord.Sales sales = new ProductRecord.Sales(
                product.getSalesStartsAt(),
                product.getSalesEndsAt(),
                product.getSalesLimit()
        );

        return new ProductRecord(
                product.getId(),
                product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getCode(),
                product.getName(),
                product.getPriceKrw(),
                terms,
                product.getSaleStatus() == SaleStatus.ON_SALE,
                toCategory(product.getCategory()),
                product.getBranchIds(),
                sales
        );
    }

    static ProductCategory toCategory(com.openstudy.modules.ticket.entity.ProductCategory category) {
        return new ProductCategory(
                Long.toString(category.getId()),
                ApiVersion.fromDatabase(category.getVersion()),
                category.getCreatedAt().toString(),
                category.getUpdatedAt().toString(),
                category.getCode(),
                category.getName(),
                toApiKind(category.getKind()),
                category.getStatus() == CategoryStatus.ACTIVE ? ACTIVE_LABEL : INACTIVE_LABEL
        );
    }

    /** The product kind never changes (Q9), so only the term columns of that kind are set. */
    static Product.Terms toColumns(ProductTerms terms) {
        return switch (terms) {
            case ProductTerms.Time time -> Product.Terms.builder()
                    .includedMinutes(time.includedMinutes())
                    .validDays(time.validDays())
                    .build();
            case ProductTerms.Period period -> Product.Terms.builder()
                    .periodDays(period.periodDays())
                    .build();
            case ProductTerms.StoredValue stored -> Product.Terms.builder()
                    .creditKrw(stored.creditKrw())
                    .hourlyRateKrw(stored.hourlyRateKrw())
                    .validDays(stored.validDays())
                    .build();
        };
    }

    static String toApiKind(ProductKind kind) {
        return switch (kind) {
            case TIME -> ProductTerms.TIME;
            case PERIOD -> ProductTerms.PERIOD;
            case STORED_VALUE -> ProductTerms.STORED_VALUE;
        };
    }

    static ProductKind toKind(String apiKind) {
        return switch (apiKind) {
            case ProductTerms.TIME -> ProductKind.TIME;
            case ProductTerms.PERIOD -> ProductKind.PERIOD;
            default -> ProductKind.STORED_VALUE;
        };
    }
}
