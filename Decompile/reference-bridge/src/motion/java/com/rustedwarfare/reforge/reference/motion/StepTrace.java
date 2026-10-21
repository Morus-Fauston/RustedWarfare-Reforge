package com.rustedwarfare.reforge.reference.motion;

/** Fixed phase recorder. Callbacks never lock the engine or perform IO. */
public final class StepTrace {
    public interface View { void capture(long[] row, int offset) throws Exception; }
    private final Object engine;
    private final Class<?> threadType;
    private final View view;
    private final boolean enabled;
    private final int capacity, stride, faultAfter;
    private final long[][] data;
    private volatile Throwable first;
    private volatile Thread thread;
    private int entered, exited;
    private boolean begun, sealed, inFlight;
    private long firstEntryNanos, lastExitNanos;

    public StepTrace(Object engine, Class<?> threadType, View view, boolean enabled,
            int capacity, int width, int faultAfter) {
        if (engine == null || threadType == null || !Thread.class.isAssignableFrom(threadType)
                || view == null || capacity < 1 || capacity > 512 || width < 1 || width > 64
                || faultAfter < 0 || faultAfter > capacity) throw new IllegalArgumentException("trace-config");
        this.engine=engine; this.threadType=threadType; this.view=view; this.enabled=enabled;
        this.capacity=capacity; this.stride=3+width; this.faultAfter=faultAfter;
        data=new long[enabled ? capacity : 1][stride*2];
    }
    public synchronized void begin() {
        if (!Thread.holdsLock(engine) || begun || sealed) throw new IllegalStateException("trace-begin-state");
        begun=true;
    }
    private void require(boolean okay, String message) { if (!okay) throw new IllegalStateException(message); }
    private void identity(Object actual) {
        require(begun && !sealed, "trace-not-active");
        require(actual == engine, "trace-engine");
        Thread current=Thread.currentThread(); require(current.getClass() == threadType, "trace-thread-class");
        if (thread == null) thread=current;
        require(thread == current, "trace-thread-object");
    }
    private void latch(Throwable error) { if (first == null) first=error; }
    public synchronized void before(Object actual, float argument) {
        if (first != null) return;
        try {
            identity(actual); require(!inFlight, "trace-reentry"); require(entered < capacity, "trace-overflow");
            inFlight=true; int index=entered++; long now=System.nanoTime();
            if (index == 0) firstEntryNanos=now;
            if (enabled || index == 0) {
                long[] row=data[enabled ? index : 0];
                row[0]=now; row[1]=thread.getId(); row[2]=Float.floatToRawIntBits(argument);
                view.capture(row,3);
            }
        } catch (Throwable error) { latch(error); }
    }
    public synchronized void after(Object actual, float argument) {
        if (first != null) return;
        try {
            identity(actual); require(inFlight && entered == exited+1, "trace-unpaired-exit");
            int index=exited; long now=System.nanoTime();
            if (enabled || index == 0) {
                long[] row=data[enabled ? index : 0];
                row[stride]=now; row[stride+1]=thread.getId(); row[stride+2]=Float.floatToRawIntBits(argument);
                view.capture(row,stride+3);
            }
            lastExitNanos=now; exited++; inFlight=false;
            if (faultAfter != 0 && exited == faultAfter) throw new IllegalStateException("injected-observer-failure");
        } catch (Throwable error) { latch(error); }
    }
    /** Called only after normal engine B/join. Never call B while holding this object's monitor. */
    public synchronized void seal(Thread observed) {
        if (!Thread.holdsLock(engine) || !begun || sealed) throw new IllegalStateException("trace-seal-state");
        sealed=true;
        try {
            require(observed != null && observed == thread && !observed.isAlive(), "trace-thread-not-joined");
            require(entered > 0 && entered == exited && !inFlight, "trace-incomplete");
        } catch (Throwable error) { latch(error); }
    }
    /** Returns partial data too; callers must separately check first error/counts/termination. */
    public synchronized long[][] rows() {
        if (!sealed) throw new IllegalStateException("trace-export-before-seal");
        int count=enabled ? entered : Math.min(entered,1); long[][] result=new long[count][];
        for (int i=0;i<count;i++) result[i]=data[i].clone();
        return result;
    }
    public Throwable failure() { return first; }
    public Thread boundThread() { return thread; }
    public synchronized int entered() { return entered; }
    public synchronized int exited() { return exited; }
    public synchronized long firstEntryNanos() { return firstEntryNanos; }
    public synchronized long lastExitNanos() { return lastExitNanos; }
}
