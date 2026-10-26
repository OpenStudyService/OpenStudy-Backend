package com.openstudy.modules.auth.service;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.employee.service.EmployeeAccountService;

/** Reload, but never save/touch, a stream's persisted session. Heartbeats are not user activity. */
@Service
public class WorkspaceSessionService {
    private final SessionRepository<? extends Session> repository;
    private final SessionBoundaryPolicy policy;
    private final EmployeeAccountService employees;

    public WorkspaceSessionService(SessionRepository<? extends Session> repository, SessionBoundaryPolicy policy,
            EmployeeAccountService employees) {
        this.repository=repository; this.policy=policy; this.employees=employees;
    }

    @Transactional(readOnly=true)
    public boolean active(long actor, String sessionId) {
        var session=repository.findById(sessionId);
        if (session==null || policy.expired(session)
                || !SessionBoundaryPolicy.WORKSPACE.equals(session.getAttribute(SessionBoundaryPolicy.SESSION_MODE))) return false;
        Object stored=session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(stored instanceof SecurityContext context) || context.getAuthentication()==null
                || !context.getAuthentication().isAuthenticated()
                || !Long.toString(actor).equals(context.getAuthentication().getName())) return false;
        return "ACTIVE".equals(employees.byId(actor).status());
    }
}
