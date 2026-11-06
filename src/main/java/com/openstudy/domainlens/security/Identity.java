package com.openstudy.domainlens.security;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import java.util.*;
import org.jspecify.annotations.Nullable;
/** Only consumer-authenticated context supplies identity. Request IDs are never authority. */
public final class Identity {
    private Identity() {}
    public record Principal(String subjectId,Set<String> roles,String roleKey,Map<String,Object> scope) implements UserContext, com.openstudy.domainlens.api.spi.PolicyBinding {
        public com.openstudy.domainlens.api.spi.PolicyBinding policyBinding(){return this;}
        public Principal {roles=Set.copyOf(roles);scope=Map.copyOf(scope);}
    }
    public static Principal require(@Nullable UserContext context) {
        if(context==null || !context.authenticated() || context.subjectId()==null || context.subjectId().isBlank()) denied();
        if(context.policyBinding()==null)denied();
        return new Principal(context.subjectId(),context.roles(),context.policyBinding().roleKey(),context.policyBinding().scope());
    }
    public static void owner(UserContext context,String owner) {if(!require(context).subjectId().equals(owner))denied();}
    private static void denied(){throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Authenticated ownership required");}
}
