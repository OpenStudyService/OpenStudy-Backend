package com.openstudy.modules.workspace.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="openstudy.workspace.scheduling.enabled",havingValue="true",matchIfMissing=true)
public class WorkspaceScheduling {
    private final WorkspaceEventService events;
    public WorkspaceScheduling(WorkspaceEventService events) { this.events=events; }
    @Scheduled(fixedDelayString="${openstudy.workspace.validation-millis:500}")
    public void dispatch() { events.tick(); }
}
