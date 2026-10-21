package com.rustedwarfare.reforge.reference.instrumentation;

/** One launch window, independent of framework activity-idle waiting. */
final class LauncherReadiness {
    private boolean requested, closed, introSeen, menuSeen;
    private long dispatchNanos, deadlineNanos, introNanos, menuNanos;

    synchronized void begin(long now, long end) {
        InitialProtocol.require(!requested && !closed && end > now, "launcher-window-invalid");
        requested = true; dispatchNanos = now; deadlineNanos = end;
    }

    private boolean accepting(long now) {
        return requested && !closed && now >= dispatchNanos && now < deadlineNanos;
    }

    synchronized boolean intro(long now) {
        if (!accepting(now) || introSeen) return false;
        introSeen = true; introNanos = now; return true;
    }

    synchronized boolean menu(long now) {
        if (!accepting(now) || !introSeen || now < introNanos || menuSeen) return false;
        menuSeen = true; menuNanos = now; return true;
    }

    synchronized void checkDeadline(long now) {
        InitialProtocol.require(accepting(now), "launcher-timeout");
    }

    synchronized boolean ready() { return !closed && introSeen && menuSeen; }
    synchronized void close() { closed = true; }
    synchronized boolean closedAfterRequest() { return closed && requested; }
    synchronized long dispatchNanos() { return dispatchNanos; }
    synchronized long introNanos() { return introNanos; }
    synchronized long menuNanos() { return menuNanos; }
}
