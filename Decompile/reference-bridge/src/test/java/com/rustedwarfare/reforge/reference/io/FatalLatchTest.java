package com.rustedwarfare.reforge.reference.io;

public final class FatalLatchTest {
    private static int checks;
    private static void require(boolean value){checks++;if(!value)throw new AssertionError("check="+checks);}
    public static void main(String[] args) {
        FatalLatch latch=new FatalLatch();require(!latch.failed());require(latch.firstFailure()==null);latch.check();
        Throwable cause=new java.io.IOException("literal");
        FatalLatch.Failure first=latch.trip("missing-path","first",cause);
        require(latch.failed());require(first==latch.firstFailure());require(first.getCause()==cause);require(first.code().equals("missing-path"));
        require(latch.trip("other","later",null)==first);
        try {latch.check();throw new AssertionError("Expected same failure");}catch(FatalLatch.Failure error){require(error==first);}
        new FatalLatch().check();
        System.out.println("PASS FatalLatch checks="+checks);
    }
}
