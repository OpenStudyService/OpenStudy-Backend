package com.openstudy.modules.workspace.service;

import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.modules.auth.service.WorkspaceSessionService;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;

@Service
public class WorkspaceAccessService {
    public record Access(AuthorizationState state, Set<Long> branches, String fingerprint, boolean permitted) {}
    private final WorkspaceSessionService sessions;
    private final AuthorizationService authorization;
    public WorkspaceAccessService(WorkspaceSessionService sessions, AuthorizationService authorization) {
        this.sessions=sessions; this.authorization=authorization;
    }

    @Transactional(readOnly=true)
    public Access current(long actor,String sessionId,String scope) {
        if (!sessions.active(actor,sessionId)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var state=authorization.current(actor);
        Set<Long> branches=Set.of();
        boolean permitted=true;
        try { branches=authorization.scope(state,scope,BranchAction.READ); }
        catch (AccessDeniedException exception) { permitted=false; }
        StringBuilder key=new StringBuilder().append(state.owner()).append(':').append(state.status());
        state.memberships().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry->{
            var membership=entry.getValue();
            key.append('|').append(entry.getKey()).append(':').append(membership.role()).append(':')
                    .append(membership.status()).append(':').append(new TreeSet<>(membership.grants()));
        });
        state.branches().entrySet().stream().filter(entry->authorization.permits(state,entry.getKey(),BranchAction.READ))
                .sorted(java.util.Map.Entry.comparingByKey()).forEach(entry->key.append('|').append(entry.getKey()).append(':').append(entry.getValue()));
        return new Access(state,branches,key.toString(),permitted);
    }
}
