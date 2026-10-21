package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.util.*;

public final class SandboxRecoveryOracleTest {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    private static JsonValue json(String text){return new JsonReader().parse(text);}
    private static byte[] wire(String text){return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private static void reject(Runnable body){try{body.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Bad recovery accepted");}
    public static void main(String[] args){
        var c=SandboxFunctionalOracle.parameters(Map.of("mode","MEMORY","shared","false","exercise","recovery"));
        require(c.exercise.equals("recovery"));
        for(String mode:List.of("NO_FOG","HARDCORE"))reject(()->SandboxFunctionalOracle.parameters(Map.of("mode",mode,"shared","false","exercise","recovery")));
        reject(()->SandboxFunctionalOracle.parameters(Map.of("mode","MEMORY","shared","true","exercise","recovery")));
        String request="{\"kind\":\"load-request\",\"generation\":1,\"mode\":\"MEMORY\",\"shareAlliedVision\":false}\n";
        String error="{\"kind\":\"load-error\",\"generation\":1,\"message\":\"Missing p001-ground.tsx\"}\n";
        for(String bad:List.of("none","duplicate","wrong-generation","wrong-reference","cleanup")){
            var stream=new SandboxFunctionalOracle.Stream();var failures=new SandboxFunctionalOracle.Failures();
            stream.expectLoadFailure(1);
            String payload=switch(bad){case "duplicate"->error+error;case "wrong-generation"->error.replace("generation\":1","generation\":2");
                case "wrong-reference"->error.replace("p001-ground.tsx","something-else");case "cleanup"->error+"{\"kind\":\"cleanup-error\"}\n";default->error;};
            stream.feed(wire(request+payload),1,false);stream.applicationErrors(failures);
            require(bad.equals("none") ? failures.primary==null && stream.expectedLoadFailureObserved() : failures.primary!=null);
        }
        var ordinary=new SandboxFunctionalOracle.Stream();var failures=new SandboxFunctionalOracle.Failures();
        ordinary.feed(wire(request+error),1,false);ordinary.applicationErrors(failures);require(failures.primary!=null);
        var records=new ArrayList<JsonValue>();records.add(json(request));
        require(!SandboxRecoveryOracle.failed(records,1));records.add(json(error));require(SandboxRecoveryOracle.failed(records,1));
        for(String kind:List.of("observation","load-ready")){
            var changed=new ArrayList<>(records);changed.add(json("{kind:"+kind+",generation:1}"));reject(()->SandboxRecoveryOracle.failed(changed,1));
        }
        var duplicate=new ArrayList<>(records);duplicate.add(json(error));reject(()->SandboxRecoveryOracle.failed(duplicate,1));
        var blank=new ArrayList<>(records);blank.set(1,json(error.replace("p001-ground.tsx","other")));reject(()->SandboxRecoveryOracle.failed(blank,1));
        System.out.println("PASS finite recovery configuration, one exact expected load failure, standard errors unchanged, no half-loaded world (constructed host rows)");
    }
}
