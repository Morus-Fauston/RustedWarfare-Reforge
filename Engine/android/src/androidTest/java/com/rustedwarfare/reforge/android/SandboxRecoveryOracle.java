package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import java.util.List;

/** One deliberately missing required reference, before any world exists. */
public final class SandboxRecoveryOracle {
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException("Recovery: "+message);}
    public static boolean failed(List<JsonValue> records,long generation){
        require(generation==1,"not fresh first generation");
        int requests=0,errors=0;
        for(JsonValue row:records){
            if(row.getLong("generation",-1)!=generation)continue;
            String kind=row.getString("kind");
            if(kind.equals("load-request")){
                require(++requests==1&&errors==0,"duplicate/reordered request");
                SandboxFunctionalOracle.configuration(row,"MEMORY",false);
            }
            if(kind.equals("load-error"))require(requests==1&&++errors==1&&row.getString("message","").contains("p001-ground.tsx"),"unexpected load failure");
            require(!List.of("load-ready","observation","action","selection","closed").contains(kind),"half-loaded first world");
        }
        return requests==1&&errors==1;
    }
}
