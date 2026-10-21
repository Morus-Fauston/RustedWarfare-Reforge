package com.rustedwarfare.reforge.android;

/** TEST-only linearized permission to resume after the intentional background hold. */
public final class SandboxResumePermission {
    private long paused, requested, resumed;
    private int state;
    private void require(boolean condition, String message) {
        if (!condition) { state=-1; throw new IllegalArgumentException(message); }
    }
    public synchronized void pause(long now) {
        require(state==0,"Duplicate or invalid business pause");
        paused=now; state=1;
    }
    public synchronized void arm(long now) {
        require(state==1 && now>=paused && now-paused>=1_000_000_000L,
            "Foreground request before a full intentional background hold");
        requested=now; state=2;
    }
    public synchronized void resume(long now) {
        require(state==2 && now>=requested,"Resume before permission, duplicate or reordered callback");
        resumed=now; state=3;
    }
    public synchronized long pauseNanos() { return paused; }
    public synchronized long requestNanos() { return requested; }
    public synchronized long resumeNanos() { return resumed; }
    public synchronized int state() { return state; }
}
