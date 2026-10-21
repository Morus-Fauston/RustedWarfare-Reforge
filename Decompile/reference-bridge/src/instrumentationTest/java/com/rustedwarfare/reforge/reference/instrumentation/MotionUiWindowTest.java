package com.rustedwarfare.reforge.reference.instrumentation;

public final class MotionUiWindowTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void closeDuringExternalAction(final boolean resume)throws Throwable {
        final MotionUiWindow window=new MotionUiWindow();window.begin(1,10);
        if(resume)window.dispatch(2,new MotionUiWindow.Action(){public void run(){}});
        final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),closed=new java.util.concurrent.CountDownLatch(1);
        final Throwable[] error={null};final boolean[] lateAccepted={true};
        final MotionUiWindow.Action action=new MotionUiWindow.Action(){public void run()throws Throwable{entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}};
        Thread external=new Thread(new Runnable(){public void run(){try{if(resume)lateAccepted[0]=window.resume(3,action);else window.dispatch(3,action);}catch(Throwable e){error[0]=e;}}});
        Thread closer=new Thread(new Runnable(){public void run(){window.close();closed.countDown();}});
        external.start();check(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));closer.start();boolean returned;
        try{returned=closed.await(500,java.util.concurrent.TimeUnit.MILLISECONDS);}
        finally{release.countDown();external.join(2000);closer.join(2000);}
        check(returned && !external.isAlive() && !closer.isAlive() && error[0]==null);
        if(resume)check(!lateAccepted[0]);else{check(window.awaitingCreation());window.created();check(!window.awaitingCreation());}
    }
    public static void main(String[] args)throws Throwable{
        final int[] calls={0};MotionUiWindow.Action action=new MotionUiWindow.Action(){public void run(){calls[0]++;}};
        MotionUiWindow canceled=new MotionUiWindow();canceled.begin(1,10);canceled.close();check(!canceled.dispatch(2,action));check(calls[0]==0 && !canceled.awaitingCreation());
        MotionUiWindow expired=new MotionUiWindow();expired.begin(1,10);check(!expired.dispatch(10,action));check(calls[0]==0);
        MotionUiWindow late=new MotionUiWindow();late.begin(1,10);check(late.dispatch(2,action));check(late.awaitingCreation());late.close();late.created();check(!late.awaitingCreation());
        check(!late.resume(11,action));check(calls[0]==2); // stop ran despite close/deadline
        try{late.resume(12,action);throw new AssertionError("duplicate accepted");}catch(IllegalArgumentException expected){}
        check(calls[0]==3); // duplicate resume also stopped before rejection
        MotionUiWindow good=new MotionUiWindow();good.begin(1,10);check(good.dispatch(2,action));good.created();check(good.resume(3,action));good.close();
        MotionUiWindow thrown=new MotionUiWindow();thrown.begin(1,10);thrown.dispatch(2,action);
        final Throwable first=new Exception("stop-first");
        try{thrown.resume(3,new MotionUiWindow.Action(){public void run()throws Throwable{throw first;}});throw new AssertionError();}catch(Throwable actual){check(actual==first);}
        closeDuringExternalAction(false);closeDuringExternalAction(true);
        System.out.println("motion UI window: delayed/expired/late/duplicate/stop-first/blocked-action-close passed");
    }
}
