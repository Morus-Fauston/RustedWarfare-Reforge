package com.rustedwarfare.reforge.reference.io;

/** One controlled session's first failure. Not a concurrent or durable safety boundary. */
public final class FatalLatch {
    public static final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code,String message,Throwable cause) {super(message,cause);this.code=code;}
        public String code() {return code;}
    }
    private Failure first;
    public boolean failed() {return first!=null;}
    public Failure firstFailure() {return first;}
    public void check() {if(first!=null)throw first;}
    public Failure trip(String code,String message,Throwable cause) {
        if(first==null)first=new Failure(code,message,cause);
        return first;
    }
}
