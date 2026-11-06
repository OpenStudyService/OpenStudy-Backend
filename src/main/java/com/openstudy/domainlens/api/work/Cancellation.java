package com.openstudy.domainlens.api.work;
import com.openstudy.domainlens.api.error.*;
import java.util.concurrent.atomic.*;
public final class Cancellation {
    private final AtomicBoolean canceled=new AtomicBoolean();private final AtomicReference<Runnable> callback=new AtomicReference<>();
    public void cancel(){canceled.set(true);var action=callback.get();if(action!=null)action.run();}
    public void check(){if(canceled.get())throw new EngineException(EngineErrorCode.CANCELED,"Execution canceled");}
    public AutoCloseable register(Runnable action){callback.set(action);if(canceled.get())action.run();return ()->callback.compareAndSet(action,null);}
}
