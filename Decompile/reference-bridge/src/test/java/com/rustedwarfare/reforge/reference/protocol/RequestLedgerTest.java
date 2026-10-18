package com.rustedwarfare.reforge.reference.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class RequestLedgerTest {
    private static int checks;
    private static void equal(Object expected,Object actual){checks++;if(!expected.equals(actual))throw new AssertionError("expected="+expected+" actual="+actual);}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static RequestLedger.Request request(long sequence,String id,String payload){return new RequestLedger.Request("s-1",sequence,id,"probe",bytes(payload));}
    private interface Checked {void run();}
    private static void denied(String code,Checked call){checks++;try{call.run();}catch(RequestLedger.ProtocolException e){equal(code,e.code());return;}throw new AssertionError("Expected "+code);}
    public static void main(String[] args) {
        RequestLedger ledger=new RequestLedger("s-1");RequestLedger.Request a=request(1,"a","A");
        equal(1L,ledger.nextSequence());equal(RequestLedger.State.IDLE,ledger.state());
        equal(RequestLedger.Decision.Kind.EXECUTE,ledger.begin(a).kind());
        equal(RequestLedger.State.EXECUTING,ledger.state());
        equal(RequestLedger.Decision.Kind.IN_FLIGHT,ledger.begin(a).kind());
        ledger.complete("a",bytes("RA"));
        equal(RequestLedger.State.PENDING_PUBLICATION,ledger.state());
        equal(RequestLedger.Decision.Kind.PUBLISH_RETRY,ledger.begin(a).kind());equal("RA",new String(ledger.begin(a).response(),StandardCharsets.UTF_8));
        ledger.publicationFailed("a");equal(1L,ledger.nextSequence());
        equal(RequestLedger.Decision.Kind.PUBLISH_RETRY,ledger.begin(a).kind());
        ledger.confirmPublished("a");equal(2L,ledger.nextSequence());equal(RequestLedger.State.IDLE,ledger.state());
        equal(RequestLedger.Decision.Kind.REPLAY,ledger.begin(a).kind());
        RequestLedger.Request b=request(2,"b","B");equal(RequestLedger.Decision.Kind.EXECUTE,ledger.begin(b).kind());
        ledger.complete("b",new byte[0]);ledger.confirmPublished("b");equal(3L,ledger.nextSequence());
        equal(RequestLedger.Decision.Kind.REPLAY,ledger.begin(a).kind());equal("RA",new String(ledger.begin(a).response(),StandardCharsets.UTF_8));
        RequestLedger ordered=new RequestLedger("s-1");
        denied("sequence-mismatch",()->ordered.begin(request(2,"x","X")));
        ordered.begin(a);
        denied("request-conflict",()->ordered.begin(request(1,"a","A!")));
        denied("request-conflict",()->ordered.begin(request(2,"a","A")));
        denied("request-conflict",()->ordered.begin(new RequestLedger.Request("s-1",1,"a","collect",bytes("A"))));
        denied("session-mismatch",()->ordered.begin(new RequestLedger.Request("old",1,"a","probe",bytes("changed"))));
        denied("request-in-flight",()->ordered.begin(request(2,"b","B")));
        denied("request-in-flight",()->ordered.begin(request(1,"x","X")));
        denied("invalid-request",()->ordered.begin(null));
        denied("request-state",()->ordered.complete("wrong",bytes("RA")));
        denied("invalid-response",()->ordered.complete("a",null));equal(RequestLedger.State.EXECUTING,ordered.state());
        ordered.complete("a",bytes("RA"));
        denied("request-state",()->ordered.complete("a",bytes("again")));
        denied("request-in-flight",()->ordered.begin(request(2,"b","B")));
        ordered.confirmPublished("a");
        denied("request-state",()->ordered.confirmPublished("a"));
        denied("request-state",()->ordered.publicationFailed("a"));
        denied("sequence-mismatch",()->ordered.begin(request(1,"x","X")));
        equal(RequestLedger.Decision.Kind.REPLAY,ordered.begin(a).kind());
        RequestLedger failed=new RequestLedger("s-1");failed.begin(a);
        denied("request-state",()->failed.executionFailed("wrong",null));
        denied("invalid-request",()->failed.executionFailed("a",null));
        denied("invalid-request",()->failed.executionFailed("a"," "));equal(RequestLedger.State.EXECUTING,failed.state());
        failed.executionFailed("a","probe-failed");failed.close();equal(RequestLedger.State.FAILED,failed.state());equal(1L,failed.nextSequence());
        denied("session-failed",()->failed.begin(null));denied("session-failed",()->failed.complete(null,null));
        denied("session-failed",()->failed.confirmPublished(null));denied("session-failed",()->failed.publicationFailed(null));
        denied("session-failed",()->failed.executionFailed(null,null));
        RequestLedger closed=new RequestLedger("s-1");closed.close();closed.close();equal(RequestLedger.State.CLOSED,closed.state());
        denied("session-closed",()->closed.begin(null));denied("session-closed",()->closed.complete(null,null));
        RequestLedger restarted=new RequestLedger("s-2");denied("session-mismatch",()->restarted.begin(a));
        denied("invalid-session",()->new RequestLedger(" "));denied("invalid-session",()->new RequestLedger(null));
        for(long seq:new long[]{0,-1,Long.MAX_VALUE})denied("invalid-request",()->request(seq,"a","A"));
        equal(Long.MAX_VALUE-1,request(Long.MAX_VALUE-1,"a","A").sequence());
        denied("invalid-request",()->new RequestLedger.Request("s-1",1,"bad id","probe",bytes("A")));
        denied("invalid-request",()->new RequestLedger.Request("s-1",1,"a","unknown",bytes("A")));
        denied("invalid-request",()->new RequestLedger.Request("s-1",1,"a","probe",null));
        byte[] payload=bytes("A");RequestLedger.Request immutable=new RequestLedger.Request("s-1",1,"a","probe",payload);payload[0]='X';
        byte[] returned=immutable.payload();returned[0]='Z';equal("A",new String(immutable.payload(),StandardCharsets.UTF_8));
        equal("s-1",immutable.sessionId());equal(1L,immutable.sequence());equal("a",immutable.requestId());equal("probe",immutable.operation());
        RequestLedger copy=new RequestLedger("s-1");RequestLedger.Decision executing=copy.begin(immutable);if(executing.response()!=null)throw new AssertionError("EXECUTE response");
        if(copy.begin(immutable).response()!=null)throw new AssertionError("IN_FLIGHT response");
        byte[] response=bytes("RA");copy.complete("a",response);response[0]='X';
        byte[] decision=copy.begin(a).response();decision[0]='Z';equal("RA",new String(copy.begin(a).response(),StandardCharsets.UTF_8));
        copy.confirmPublished("a");equal("RA",new String(copy.begin(a).response(),StandardCharsets.UTF_8));
        System.out.println("PASS RequestLedger checks="+checks);
    }
}
