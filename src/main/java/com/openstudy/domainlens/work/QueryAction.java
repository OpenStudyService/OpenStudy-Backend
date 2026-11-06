package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.work.WorkStore;
import com.openstudy.domainlens.api.spi.UserContext;
import java.util.Map;
/** Consumer-registered plan transformations. Every resulting IR still enters the common pipeline. */
@FunctionalInterface
public interface QueryAction {
 RequestService.Job prepare(String action,WorkStore.Execution execution,WorkStore.Dataset dataset,Map<String,Object> parameters,UserContext user);
}
