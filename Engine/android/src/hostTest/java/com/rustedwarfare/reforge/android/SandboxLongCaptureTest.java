package com.rustedwarfare.reforge.android;

import java.nio.charset.StandardCharsets;

/** Actual TEST stream boundaries on constructed wire records; not an Android execution. */
public final class SandboxLongCaptureTest {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Long capture accepted an invalid boundary");
    }
    private static String step(int index) {
        return "{\"kind\":\"observation\",\"generation\":1,\"sequence\":" + index + ",\"phase\":\"step\",\"sample\":{"
            + "\"worldId\":\"w\",\"scenarioId\":\"p002-knowledge\",\"playerId\":\"p1\",\"stepIndex\":" + index
            + ",\"simulationTime\":" + (index + 1) + "}}\n";
    }
    private static byte[] steps(int from, int end) {
        StringBuilder wire = new StringBuilder();
        for (int index = from; index < end; index++) wire.append(step(index));
        return wire.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void firstErrorPriority() {
        for(boolean applicationFirst:new boolean[]{true,false}) {
            var stream=SandboxFunctionalOracle.Stream.forLongTrace();
            String app="{\"kind\":\"load-error\",\"sequence\":0}\n",malformed="{}\n";
            byte[] wire=((applicationFirst?app+malformed:malformed+app)+step(0)).getBytes(StandardCharsets.UTF_8);
            IllegalArgumentException first=null;
            try{stream.feed(wire,0,false);}catch(IllegalArgumentException expected){first=expected;}
            require(first!=null&&first.getMessage().startsWith(applicationFirst?"Application load-error":"Malformed complete JSONL"),
                "Same-batch later error replaced the first cause");
            require(first.getSuppressed().length>=1,"Later distinct cause was not preserved");
            require(stream.records.size()==2&&stream.steps==1,"First error prevented complete batch capture");
            var failures=new SandboxFunctionalOracle.Failures();failures.business(first);stream.applicationErrors(failures);
            require(failures.primary==first,"Failure aggregation replaced its primary");
            try{stream.checkAwait(true,60001);throw new AssertionError("Late time error accepted");}
            catch(IllegalArgumentException expected){require(expected==first,"Later feed replaced first cause");}
        }
        System.out.println("PASS first application/parse cause survives same-batch ordering and subsequent time failure");
    }
    public static void main(String[] args) {
        firstErrorPriority();
        var rowFault=SandboxFunctionalOracle.Stream.forLongTrace();rowFault.feed(steps(0,2001),1000,false);
        var badRow=new IllegalArgumentException("Bad business row at tick1499");
        try{rowFault.failBusinessAt(badRow,rowFault.records.get(1499));throw new AssertionError("501 same-batch tail ticks accepted");}
        catch(IllegalArgumentException expected){require(expected==badRow&&badRow.getSuppressed().length==1
            &&badRow.getSuppressed()[0].getMessage().contains("tail step budget"),"Tail failure replaced/lost the first business error");}
        require(rowFault.businessEndSteps()==1500,"Business error boundary was moved to the end of the chunk");
        var exactly500=SandboxFunctionalOracle.Stream.forLongTrace();exactly500.feed(steps(0,2000),1000,false);
        exactly500.failBusinessAt(new IllegalArgumentException("First bad row"),exactly500.records.get(1499));
        require(exactly500.businessEndSteps()==1500,"Exact500 failed-row tail moved");rejected(exactly500::complete);
        var stream = SandboxFunctionalOracle.Stream.forLongTrace();
        stream.feed(steps(0,2501),1000,false);
        require(stream.steps == 2501 && stream.records.size() == 2501, "Lost terminal-chunk tail");
        stream.endBusinessAt(stream.records.get(2499),1);
        stream.checkAwait(true,60000);
        rejected(()->stream.checkInput(60000));
        require(stream.businessEndSteps()==2500 && stream.terminalSequence()==2499, "Wrong terminal boundary");
        // A denied extra input is itself a real failure; later records cannot green it.
        rejected(stream::complete);

        var last = SandboxFunctionalOracle.Stream.forLongTrace();
        last.feed(steps(0,3000),60000,false);
        last.endBusinessAt(last.records.get(2499),1);last.checkAwait(true,60000);last.complete();
        require(last.steps==3000 && last.businessEndSteps()==2500, "Last eligible tick + exactly500 tail did not pass");
        var late = SandboxFunctionalOracle.Stream.forLongTrace();late.feed(steps(0,2501),1000,false);
        rejected(()->late.endBusinessAt(late.records.get(2500),1));rejected(late::complete);
        var exhausted = SandboxFunctionalOracle.Stream.forLongTrace();exhausted.feed(steps(0,2500),1000,false);
        rejected(()->exhausted.checkAwait(false,1000));rejected(exhausted::complete);
        var input = SandboxFunctionalOracle.Stream.forLongTrace();input.feed(steps(0,2499),1000,false);input.checkInput(1000);
        input.feed(steps(2499,2500),1000,false);rejected(()->input.checkInput(1000));

        var early = SandboxFunctionalOracle.Stream.forLongTrace();early.feed(steps(0,2000),1000,false);
        early.endBusinessAt(early.records.get(1499),1);early.complete();
        var extra = SandboxFunctionalOracle.Stream.forLongTrace();extra.feed(steps(0,2001),1000,false);
        rejected(()->extra.endBusinessAt(extra.records.get(1499),1));rejected(extra::complete);
        var overflow = SandboxFunctionalOracle.Stream.forLongTrace();rejected(()->overflow.feed(steps(0,3001),1000,false));

        var clock = SandboxFunctionalOracle.Stream.forLongTrace();clock.feed(steps(0,10),1000,false);
        clock.endBusinessAt(clock.records.get(9),1);rejected(()->clock.checkAwait(true,60001));rejected(clock::complete);
        var closing = SandboxFunctionalOracle.Stream.forLongTrace();closing.feed(steps(0,10),60000,false);
        closing.endBusinessAt(closing.records.get(9),1);closing.feed(steps(10,11),3000,true);closing.complete();
        rejected(()->closing.feed(new byte[0],3001,true));
        var noBoundary = SandboxFunctionalOracle.Stream.forLongTrace();noBoundary.feed(steps(0,10),1000,false);
        rejected(noBoundary::complete);

        var app = SandboxFunctionalOracle.Stream.forLongTrace();
        app.feed((new String(steps(0,10),StandardCharsets.UTF_8)+"{\"kind\":\"load-error\",\"sequence\":10}\n"+step(10))
            .getBytes(StandardCharsets.UTF_8),1000,false);
        app.endBusinessAt(app.records.get(9),1);rejected(()->app.checkAwait(true,1000));rejected(app::complete);
        var syntax = SandboxFunctionalOracle.Stream.forLongTrace();
        rejected(()->syntax.feed((new String(steps(0,10),StandardCharsets.UTF_8)+"{}\n"+step(10)).getBytes(StandardCharsets.UTF_8),1000,false));
        require(syntax.steps==11, "Malformed row prevented whole-chunk capture");
        syntax.endBusinessAt(syntax.records.get(9),1);rejected(()->syntax.checkAwait(true,1000));rejected(syntax::complete);
        var wrongGeneration = SandboxFunctionalOracle.Stream.forLongTrace();wrongGeneration.feed(steps(0,10),1000,false);
        rejected(()->wrongGeneration.endBusinessAt(wrongGeneration.records.get(9),2));
        var fake = new com.badlogic.gdx.utils.JsonReader().parse(step(9));
        rejected(()->wrongGeneration.endBusinessAt(fake,1));

        var failure = SandboxFunctionalOracle.Stream.forLongTrace();failure.feed(steps(0,1500),1000,false);
        failure.failBusiness(new IllegalArgumentException("first business error"));failure.feed(steps(1500,2000),3000,true);
        rejected(failure::complete);require(failure.businessEndSteps()==1500, "Failure boundary moved");
        rejected(()->failure.feed(steps(2000,2001),3000,true));
        var bytes = SandboxFunctionalOracle.Stream.forLongTrace();bytes.feed(steps(0,1),1000,false);bytes.endBusinessAt(bytes.records.get(0),1);
        byte[] padding=("{\"kind\":\"padding\",\"text\":\""+"x".repeat(12*1024*1024)+"\"}\n").getBytes(StandardCharsets.UTF_8);
        rejected(()->bytes.feed(padding,1000,false));rejected(()->bytes.checkAwait(true,1000));
        var finalBytes = SandboxFunctionalOracle.Stream.forLongTrace();
        rejected(()->finalBytes.feed(new byte[16*1024*1024+1],0,true));
        var standard = new SandboxFunctionalOracle.Stream();rejected(()->standard.feed(steps(0,501),1000,false));
        rejected(()->new SandboxFunctionalOracle.Stream().feed(new byte[0],15001,false));
        System.out.println("PASS long actual-step/terminal/tail limits, no extra input, independent time/bytes/late errors and unchanged standard policy");
    }
}
