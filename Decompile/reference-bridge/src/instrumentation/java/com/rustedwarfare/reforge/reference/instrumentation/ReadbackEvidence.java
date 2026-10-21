package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReadbackEvidence {
    interface Read { void run() throws Throwable; }
    private ReadbackEvidence() { }
    static Map<String, Object> map() { return new LinkedHashMap<String, Object>(); }

    static Throwable capture(Map<String, Object> partial, Read read) {
        partial.put("complete", false);
        try { read.run(); partial.put("complete", true); return null; }
        catch (Throwable error) {
            Map<String, Object> failure = map(); failure.put("type", error.getClass().getName()); failure.put("message", error.getMessage());
            partial.put("readFailure", failure); return error;
        }
    }

    static boolean same(Object first, Object second) { return first == second; }

    static boolean isDexEntry(String name) { return name.matches("classes[0-9]*\\.dex"); }

    static Map<String, Object> relations(Object object, Object[] slots, Object bp) {
        if (object == null) return null;
        Map<String, Object> r = map(); List<Object> matching = new ArrayList<Object>(), bySlot = new ArrayList<Object>();
        for (int i = 0; i < slots.length; i++) { boolean equal = object == slots[i]; bySlot.add(equal); if (equal) matching.add(i); }
        r.put("isBp", object == bp); r.put("sameAsPlayerSlot", bySlot); r.put("matchingSlots", matching); return r;
    }
}
