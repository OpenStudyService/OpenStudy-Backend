package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.spi.UserContext;
import java.time.Duration;
/** Must reauthorize the complete server plan and return current trusted partition/version values. */
@FunctionalInterface public interface QueryCachePolicy {
 record Context(String tenant,String dataVersion,Duration ttl){public Context{if(tenant==null||tenant.isBlank()||dataVersion==null||dataVersion.isBlank()||ttl==null||ttl.isNegative()||ttl.isZero()||ttl.compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("Invalid cache context");}}
 Context authorize(ExecutionPlan plan,UserContext user);
}
