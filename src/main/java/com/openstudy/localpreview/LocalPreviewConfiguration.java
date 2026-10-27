package com.openstudy.localpreview;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

import com.openstudy.modules.branch.entity.Branch;
import com.openstudy.modules.branch.entity.BranchStatus;
import com.openstudy.modules.branch.repository.BranchRepository;

/**
 * Temporary, explicitly local workspace preview until business APIs are implemented.
 * <p>This local-profile tool is not a business module. Its startup seed has no actor, so it cannot go through
 * BranchService's owner command; it writes the branch aggregate through its repository, as the former SQL wrote
 * the table directly. created_by stays NULL and no weekly hours are stored (shown as closed), as before.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "openstudy.local-preview.enabled", havingValue = "true", matchIfMissing = true)
@Import(LocalPreviewData.class)
public class LocalPreviewConfiguration {
    @Bean
    CommandLineRunner localPreviewBranches(BranchRepository branches, TransactionTemplate transactions) {
        return args -> transactions.executeWithoutResult(status -> {
            if (branches.count() == 0) {
                branches.saveAll(List.of(
                        new Branch("LOCAL-PREVIEW-01", "강남 스터디카페 (샘플)", "서울 강남구 샘플 주소", "02-0000-0001", BranchStatus.ACTIVE, null),
                        new Branch("LOCAL-PREVIEW-02", "성수 스터디카페 (샘플)", "서울 성동구 샘플 주소", "02-0000-0002", BranchStatus.ACTIVE, null)));
            }
        });
    }
}
