package com.rustedwarfare.reforge.android;

import java.util.concurrent.atomic.AtomicReference;

/** A real separate thread models the dispatch boundary; no Android/Looper claim. */
public final class SandboxUiCallTest {
    private static final AtomicReference<Throwable> uncaught=new AtomicReference<>();
    private static void dispatch(Runnable operation){
        Thread ui=new Thread(operation,"test-ui-thread");ui.setUncaughtExceptionHandler((thread,error)->uncaught.set(error));ui.start();
        try{ui.join(3000);}catch(InterruptedException error){throw new AssertionError(error);}
        if(ui.isAlive())throw new AssertionError("UI dispatch did not finish");
    }
    private static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Throwable{
        var original=new IllegalStateException("Inactive Activity");
        dispatch(()->{throw original;});require(uncaught.get()==original);uncaught.set(null);
        try{SandboxUiCall.run(SandboxUiCallTest::dispatch,()->{throw original;});throw new AssertionError("Failure lost");}
        catch(IllegalStateException error){require(error==original&&uncaught.get()==null);}
        var fatal=new AssertionError("UI error");
        try{SandboxUiCall.run(SandboxUiCallTest::dispatch,()->{throw fatal;});throw new AssertionError("Error lost");}
        catch(AssertionError error){require(error==fatal&&uncaught.get()==null);}
        var executed=new AtomicReference<String>();SandboxUiCall.run(SandboxUiCallTest::dispatch,()->executed.set(Thread.currentThread().getName()));
        require(executed.get().equals("test-ui-thread"));
        var dispatchFailure=new IllegalArgumentException("dispatch failed");
        try{SandboxUiCall.run(body->{body.run();throw dispatchFailure;},()->{throw original;});throw new AssertionError("Primary lost");}
        catch(IllegalStateException error){require(error==original&&error.getSuppressed().length==1&&error.getSuppressed()[0]==dispatchFailure);}
        try{SandboxUiCall.run(body->{throw dispatchFailure;},()->{});throw new AssertionError("Dispatch failure lost");}
        catch(IllegalArgumentException error){require(error==dispatchFailure);}
        SandboxUiCall.verify(SandboxUiCallTest::dispatch);
        try{SandboxUiCall.verify(body->{throw dispatchFailure;});throw new AssertionError("Selfcheck replaced dispatcher failure");}
        catch(IllegalArgumentException error){require(error==dispatchFailure);}
        try{SandboxUiCall.verify(body->{body.run();throw dispatchFailure;});throw new AssertionError("Selfcheck swallowed secondary failure");}
        catch(IllegalStateException error){require(error.getMessage().equals("TEST UI callback return witness")&&error.getSuppressed().length==1&&error.getSuppressed()[0]==dispatchFailure);}
        try{SandboxUiCall.verify(body->{});throw new AssertionError("Selfcheck accepted missing callback");}
        catch(IllegalStateException error){require(error.getMessage().equals("UI callback failure was lost"));}
        System.out.println("PASS actual cross-thread failure reproduction, exact Throwable return, Error/normal dispatch, secondary preservation and selfcheck consumer boundaries; Android callback verification still required");
    }
}
