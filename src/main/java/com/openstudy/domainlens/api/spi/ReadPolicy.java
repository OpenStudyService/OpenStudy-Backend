package com.openstudy.domainlens.api.spi;
public interface ReadPolicy {
    String id();
    boolean allows(UserContext user,String sourceId);
}
