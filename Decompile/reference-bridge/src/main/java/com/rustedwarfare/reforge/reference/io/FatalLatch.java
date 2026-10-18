package com.rustedwarfare.reforge.reference.io;

/** One controlled session's first failure; not an atomic filesystem or durable boundary. */
public final class FatalLatch {
    public static final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code,String message,Throwable cause) {super(message,cause);this.code=code;}
        public String code() {return code;}
    }
    private Failure first;
    public synchronized boolean failed() {return first!=null;}
    public synchronized Failure firstFailure() {return first;}
    public synchronized void check() {if(first!=null)throw first;}
    public synchronized Failure trip(String code,String message,Throwable cause) {
        if(first==null)first=new Failure(code,message,cause);
        return first;
    }
}
