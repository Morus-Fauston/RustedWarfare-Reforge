package com.rustedwarfare.reforge.reference.motion;

/** Self-owned callback fixtures; this is not original Android simulation. */
public final class StepTraceTest {
    static final Object ENGINE = new Object();
    static class Worker extends Thread { }
    static final class View implements StepTrace.View {
        int reads; boolean fail;
        public void capture(long[] row, int offset) {
            if (fail) throw new IllegalStateException("fixture-read");
            row[offset] = ++reads;
        }
    }
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static StepTrace trace(View v, boolean on, int cap, int fault) {
        return new StepTrace(ENGINE, Thread.currentThread().getClass(), v, on, cap, 1, fault);
    }
    static void begin(StepTrace t) { synchronized (ENGINE) { t.begin(); } }
    static void seal(StepTrace t) { synchronized (ENGINE) { t.seal(Thread.currentThread()); } }
    public static void main(String[] args) throws Exception {
        View v = new View(); StepTrace t = trace(v, true, 2, 0); begin(t);
        t.before(ENGINE, 1f); t.after(ENGINE, 2f);
        check(t.failure() == null && t.entered() == 1 && t.exited() == 1);
        check(t.boundThread() == Thread.currentThread());
        boolean refused = false; try { t.rows(); } catch (IllegalStateException e) { refused = true; } check(refused);
        // seal must reject a still-live actual callback thread.
        seal(t); check(t.failure() != null);

        v = new View(); t = trace(v, true, 1, 0); begin(t);
        t.before(ENGINE, 1f); t.after(ENGINE, 2f); t.before(ENGINE, 3f);
        check(t.failure() != null && t.entered() == 1 && t.exited() == 1 && v.reads == 2);

        v = new View(); t = trace(v, true, 2, 0); begin(t); t.after(ENGINE, 1f);
        Throwable first = t.failure(); t.before(new Object(), 1f); check(first != null && t.failure() == first);
        v = new View(); t = trace(v, true, 2, 0); begin(t); t.before(ENGINE, 1f); t.before(ENGINE, 1f);
        check(t.failure() != null && t.entered() == 1 && t.exited() == 0);
        v = new View(); t = trace(v, true, 2, 0); begin(t); t.before(new Object(), 1f); check(t.failure() != null && t.entered() == 0);
        v = new View(); t = trace(v, false, 3, 0); begin(t);
        for (int i=0;i<3;i++) { t.before(ENGINE, 1f); t.after(ENGINE, 2f); }
        check(t.failure() == null && t.entered() == 3 && t.exited() == 3 && v.reads == 2);
        v = new View(); t = trace(v, true, 3, 2); begin(t);
        for (int i=0;i<2;i++) { t.before(ENGINE, 1f); t.after(ENGINE, 2f); }
        check(t.failure() != null && t.exited() == 2);
        v = new View(); v.fail = true; t = trace(v, true, 2, 0); begin(t); t.before(ENGINE, 1f);
        check(t.failure() != null && t.entered() == 1 && t.exited() == 0);
        // Real host thread, first callback precedes worker inventory, no engine lock in callback.
        final View good = new View(); final StepTrace[] slot = new StepTrace[1];
        Thread worker = new Worker() { public void run() { slot[0].before(ENGINE, 1f); slot[0].after(ENGINE, 2f); } };
        slot[0] = new StepTrace(ENGINE, worker.getClass(), good, true, 2, 1, 0); begin(slot[0]);
        synchronized (ENGINE) { worker.start(); worker.join(2000); check(!worker.isAlive()); }
        synchronized (ENGINE) { slot[0].seal(worker); }
        check(slot[0].failure() == null && slot[0].boundThread() == worker);
        long[][] rows = slot[0].rows(); check(rows.length == 1 && rows[0][2] == Float.floatToRawIntBits(1f)
            && rows[0][6] == Float.floatToRawIntBits(2f) && rows[0][3] == 1 && rows[0][7] == 2);
        rows[0][3] = 999; check(slot[0].rows()[0][3] == 1);
        // A phase with no callbacks cannot pass just because a thread is no longer alive.
        v = new View(); t = trace(v, true, 2, 0); begin(t);
        synchronized(ENGINE) { t.seal(new Thread()); } check(t.failure() != null && t.rows().length == 0);
        // Native exception before normal return leaves its entrance, never a synthesized exit.
        final StepTrace[] partial=new StepTrace[1];
        Thread interrupted=new Worker(){public void run(){partial[0].before(ENGINE,3f);}};
        partial[0]=new StepTrace(ENGINE,interrupted.getClass(),new View(),true,2,1,0); begin(partial[0]);
        interrupted.start(); interrupted.join(2000);
        synchronized(ENGINE){partial[0].seal(interrupted);}
        check(partial[0].failure()!=null && partial[0].entered()==1 && partial[0].exited()==0 && partial[0].rows().length==1);
        // Wrong native thread object/class is rejected, not silently dropped.
        v=new View(); t=new StepTrace(ENGINE,Worker.class,v,true,2,1,0);begin(t);t.before(ENGINE,1f);
        check(t.failure()!=null && t.entered()==0);
        // Worker inventory must read the published Thread without waiting on capture's lock.
        final java.util.concurrent.CountDownLatch inside=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),read=new java.util.concurrent.CountDownLatch(1);
        final StepTrace[] blocking=new StepTrace[1];
        Thread capture=new Worker(){public void run(){blocking[0].before(ENGINE,1f);blocking[0].after(ENGINE,2f);}};
        blocking[0]=new StepTrace(ENGINE,capture.getClass(),new StepTrace.View(){public void capture(long[] r,int o)throws Exception{inside.countDown();release.await();}},true,2,1,0);
        begin(blocking[0]);capture.start();check(inside.await(2,java.util.concurrent.TimeUnit.SECONDS));
        Thread reader=new Thread(){public void run(){if(blocking[0].boundThread()==capture)read.countDown();}};reader.start();
        boolean noWait;try{noWait=read.await(2,java.util.concurrent.TimeUnit.SECONDS);}finally{release.countDown();}
        capture.join(2000);reader.join(2000);check(noWait && !capture.isAlive() && !reader.isAlive());
        System.out.println("PASS: pairing, deltas, overflow, first error, off, fault, read failure, first-thread race, monitor independence, seal/export");
    }
}
