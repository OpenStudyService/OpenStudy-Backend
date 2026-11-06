package com.openstudy.domainlens.api.work;
@FunctionalInterface public interface QueryExecutorDecorator { QueryExecutor decorate(QueryExecutor executor); }
