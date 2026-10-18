package com.rustedwarfare.reforge.reference.protocol;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Single-threaded host ledger only. Does not execute, publish or persist requests. */
public final class RequestLedger {
    public enum State { IDLE, EXECUTING, PENDING_PUBLICATION, FAILED, CLOSED }
    public static final class ProtocolException extends IllegalStateException {
        private final String code;
        public ProtocolException(String code,String message){super(message);this.code=code;}
        public String code(){return code;}
    }
    public static final class Request {
        private final String sessionId,requestId,operation;
        private final long sequence;
        private final byte[] payload;
        public Request(String sessionId,long sequence,String requestId,String operation,byte[] payload){
            if(!id(sessionId)||!id(requestId)||sequence<=0||sequence==Long.MAX_VALUE||
                !("probe".equals(operation)||"collect".equals(operation))||payload==null)throw failure("invalid-request");
            this.sessionId=sessionId;this.sequence=sequence;this.requestId=requestId;this.operation=operation;this.payload=payload.clone();
        }
        public String sessionId(){return sessionId;} public long sequence(){return sequence;}
        public String requestId(){return requestId;} public String operation(){return operation;}
        public byte[] payload(){return payload.clone();}
        private boolean same(Request other){return sessionId.equals(other.sessionId)&&sequence==other.sequence&&requestId.equals(other.requestId)&&operation.equals(other.operation)&&Arrays.equals(payload,other.payload);}
    }
    public static final class Decision {
        public enum Kind { EXECUTE, IN_FLIGHT, PUBLISH_RETRY, REPLAY }
        private final Kind kind;
        private final byte[] response;
        private Decision(Kind kind,byte[] response){this.kind=kind;this.response=response==null?null:response.clone();}
        public Kind kind(){return kind;}
        public byte[] response(){return response==null?null:response.clone();}
    }
    private final String sessionId;
    private State state=State.IDLE;
    private long next=1;
    private Request current;
    private byte[] response;
    private final Map<String,Request> requests=new HashMap<String,Request>();
    private final Map<String,byte[]> published=new HashMap<String,byte[]>();
    public RequestLedger(String sessionId){if(!id(sessionId))throw failure("invalid-session");this.sessionId=sessionId;}
    public State state(){return state;}public long nextSequence(){return next;}
    public Decision begin(Request request){
        checkActive();
        if(request==null)throw failure("invalid-request");
        if(!sessionId.equals(request.sessionId))throw failure("session-mismatch");
        Request previous=requests.get(request.requestId);
        if(previous!=null){
            if(!previous.same(request))throw failure("request-conflict");
            if(published.containsKey(request.requestId))return new Decision(Decision.Kind.REPLAY,published.get(request.requestId));
            return new Decision(state==State.EXECUTING?Decision.Kind.IN_FLIGHT:Decision.Kind.PUBLISH_RETRY,response);
        }
        if(current!=null)throw failure("request-in-flight");
        if(request.sequence!=next)throw failure("sequence-mismatch");
        current=request;requests.put(request.requestId,request);state=State.EXECUTING;return new Decision(Decision.Kind.EXECUTE,null);
    }
    public void complete(String requestId,byte[] response){
        checkCurrent(requestId,State.EXECUTING);
        if(response==null)throw failure("invalid-response");
        this.response=response.clone();state=State.PENDING_PUBLICATION;
    }
    public void publicationFailed(String requestId) {checkCurrent(requestId,State.PENDING_PUBLICATION);}
    public void confirmPublished(String requestId){
        checkCurrent(requestId,State.PENDING_PUBLICATION);
        published.put(requestId,response.clone());next=current.sequence+1;current=null;response=null;state=State.IDLE;
    }
    public void executionFailed(String requestId,String code){
        checkCurrent(requestId,State.EXECUTING);
        if(code==null||code.trim().isEmpty())throw failure("invalid-request");
        state=State.FAILED;
    }
    public void close(){if(state!=State.FAILED)state=State.CLOSED;}
    private void checkActive(){
        if(state==State.FAILED)throw failure("session-failed");
        if(state==State.CLOSED)throw failure("session-closed");
    }
    private void checkCurrent(String requestId,State expected){
        checkActive();if(state!=expected || current==null || !current.requestId.equals(requestId))throw failure("request-state");
    }
    private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9._-]+");}
    private static ProtocolException failure(String code){return new ProtocolException(code,code);}
}
