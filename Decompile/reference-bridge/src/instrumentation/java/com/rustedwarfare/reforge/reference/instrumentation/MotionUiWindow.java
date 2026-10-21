package com.rustedwarfare.reforge.reference.instrumentation;

/** One fixed InGame dispatch; closing cancels delayed dispatch, never skips stopping a late resume. */
final class MotionUiWindow {
    interface Action { void run() throws Throwable; }
    private boolean begun, closed, dispatched, dispatchInFlight, created, resumed;
    private long end;
    synchronized void begin(long now,long deadline) {
        InitialProtocol.require(!begun && now<deadline,"motion-ui-window-begin");begun=true;end=deadline;
    }
    boolean dispatch(long now,Action action) throws Throwable {
        synchronized(this){
            if(closed || now>=end)return false;
            InitialProtocol.require(begun && !dispatched,"motion-ui-window-dispatch");
            dispatched=true;dispatchInFlight=true;
        }
        try{action.run();return true;}
        finally{synchronized(this){dispatchInFlight=false;}}
    }
    synchronized void created() { InitialProtocol.require(dispatched,"motion-ui-unrequested-create");created=true; }
    boolean resume(long now,Action stop) throws Throwable {
        // Original onResume already called A; stop even after close, timeout or duplicate resume.
        stop.run();
        synchronized(this){
            InitialProtocol.require(dispatched && !resumed,"motion-ui-repeated-resume");resumed=true;
            return !closed && now<end;
        }
    }
    synchronized void close(){closed=true;}
    synchronized boolean awaitingCreation(){return dispatchInFlight || dispatched && !created;}
}
