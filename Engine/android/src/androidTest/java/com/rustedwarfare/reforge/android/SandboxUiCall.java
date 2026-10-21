package com.rustedwarfare.reforge.android;

import java.util.concurrent.atomic.AtomicReference;

/** Catch inside the dispatched UI callback; rethrow on the waiting instrumentation thread. */
public final class SandboxUiCall {
    private SandboxUiCall() { }
    public interface Dispatcher { void dispatch(Runnable operation); }
    public static void verify(Dispatcher dispatcher) throws Throwable {
        IllegalStateException witness=new IllegalStateException("TEST UI callback return witness");
        boolean returned=false;
        try { run(dispatcher,()->{throw witness;}); }
        catch(Throwable failure) {
            if(failure!=witness || failure.getSuppressed().length!=0)throw failure;
            returned=true;
        }
        if(!returned)throw new IllegalStateException("UI callback failure was lost");
    }
    public static void run(Dispatcher dispatcher,Runnable operation) throws Throwable {
        AtomicReference<Throwable> observed=new AtomicReference<>();
        try {
            dispatcher.dispatch(()->{
                try { operation.run(); }
                catch(Throwable failure) { observed.set(failure); }
            });
        } catch(Throwable dispatchFailure) {
            Throwable first=observed.get();
            if(first!=null && first!=dispatchFailure) { first.addSuppressed(dispatchFailure);throw first; }
            throw dispatchFailure;
        }
        Throwable failure=observed.get();
        if(failure!=null)throw failure;
    }
}
