package com.openstudy.domainlens.api.error;

public final class EngineException extends RuntimeException {
    private final EngineErrorCode code;
    private final java.util.List<com.openstudy.domainlens.api.prompt.ModelUsage> usage;
    public EngineException(EngineErrorCode code,String message){this(code,message,java.util.List.of());}
    public EngineException(EngineErrorCode code,String message,java.util.List<com.openstudy.domainlens.api.prompt.ModelUsage> usage){super(message);this.code=code;this.usage=java.util.List.copyOf(usage);}
    public java.util.List<com.openstudy.domainlens.api.prompt.ModelUsage> usage(){return usage;}
    public EngineErrorCode code() { return code; }
}
