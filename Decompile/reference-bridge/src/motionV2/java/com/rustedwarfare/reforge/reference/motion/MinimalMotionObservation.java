package com.rustedwarfare.reforge.reference.motion;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Modifier;

/** O.v2 read-only diagnostics for the sole controlled unit. No IO or gameplay writes. */
public final class MinimalMotionObservation {
    public static final String[] FIELDS={"chBits","cgBits","ay","movementRequested","cnBits","activeWaypointToken",
        "normalBeforeCount","normalAfterCount","normalWaypointToken","normalTargetXBits","normalTargetYBits","normalKindOrdinal",
        "collisionCount","collisionStartXBits","collisionStartYBits","collisionCandidateXBits","collisionCandidateYBits",
        "collisionFinalXBits","collisionFinalYBits","collisionDeltaBits","collisionCnBits",
        "propulsionCount","localTargetXBits","localTargetYBits","localB","localC","propulsionP4","localAy","collisionAfterCount",
        "normalAaBits","normalXBits","normalYBits","propulsionChBits","propulsionCgBits","propulsionRequested","propulsionABits",
        "propulsionCommandToken","propulsionCommandXBits","propulsionCommandYBits","propulsionCommandKindOrdinal","collisionMovementTypeOrdinal","normalThresholdBits"};
    private static Object engine,unit,pathEngine,movementType,normalOld,moveEnum,mapObject;
    private static Field x,y,ch,cg,ay,requested,cn,waypointX,waypointY,waypointKind,count,aa,aB,mapField;
    private static Field pathEngineField,localX,localY,localB,localC,mapR,mapS,mapN,mapO;
    private static Method waypoint,blocked,movement;
    private static Class<?> localType;
    private static Map<String,Object> initialGeometry;
    private static final IdentityHashMap<Object,Integer> tokens=new IdentityHashMap<Object,Integer>();
    private static long[] call=new long[FIELDS.length];
    private static int nextToken=1;
    private static volatile Throwable first;
    private static boolean enabled,inCall,configured;
    private static Thread thread;
    private static boolean[] initialMask;
    private MinimalMotionObservation() { }
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
    private static Field declared(Class<?> owner,String name,Class<?> type) throws Exception {
        Field f=owner.getDeclaredField(name);require(f.getType()==type,"o2-field:"+name);f.setAccessible(true);return f;
    }
    private static Field publicField(Class<?> owner,String name,Class<?> type) throws Exception {
        Field f=owner.getField(name);require(f.getType()==type && Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers()),"o2-public-field:"+name);return f;
    }
    private static long bits(Field f,Object value) throws Exception {return Float.floatToRawIntBits(f.getFloat(value));}
    private static int token(Object value) {
        if(value==null)return 0;Integer result=tokens.get(value);
        if(result==null){result=nextToken++;tokens.put(value,result);}return result;
    }
    public static synchronized void configure(Object actualEngine,Object actualUnit,boolean collect) throws Exception {
        require(!configured && actualEngine!=null && actualUnit!=null && Thread.holdsLock(actualEngine),"o2-configure");
        ClassLoader loader=MinimalMotionObservation.class.getClassLoader();
        Class<?> bp=Class.forName("com.corrodinggames.rts.game.units.bp",false,loader);
        Class<?> ce=Class.forName("com.corrodinggames.rts.game.units.ce",false,loader);
        Class<?> en=Class.forName("com.corrodinggames.rts.game.units.en",false,loader);
        Class<?> eo=Class.forName("com.corrodinggames.rts.game.units.eo",false,loader);
        Class<?> k=Class.forName("com.corrodinggames.rts.gameFramework.k",false,loader);
        Class<?> kk=Class.forName("com.corrodinggames.rts.gameFramework.k.k",false,loader);
        Class<?> cgType=Class.forName("com.corrodinggames.rts.game.units.cg",false,loader);
        require(bp.isInstance(actualUnit) && k.isInstance(actualEngine),"o2-objects");
        engine=actualEngine;unit=actualUnit;enabled=collect;
        x=publicField(bp,"eq",float.class);y=publicField(bp,"er",float.class);ch=publicField(ce,"ch",float.class);cg=publicField(ce,"cg",float.class);cn=publicField(ce,"cn",float.class);
        ay=declared(bp,"ay",int.class);requested=declared(bp,"i",boolean.class);count=publicField(bp,"O",int.class);aa=publicField(bp,"aa",float.class);aB=publicField(bp,"aB",float.class);
        waypoint=bp.getMethod("ap");require(waypoint.getReturnType()==en,"o2-waypoint-return");
        waypointX=publicField(en,"e",float.class);waypointY=publicField(en,"f",float.class);waypointKind=publicField(en,"a",eo);
        moveEnum=eo.getField("a").get(null);Class<?> mapType=Class.forName("com.corrodinggames.rts.game.b.b",false,loader);
        mapField=publicField(k,"bI",mapType);mapObject=mapField.get(engine);require(mapObject!=null,"o2-map-null");
        mapR=publicField(mapType,"r",float.class);mapS=publicField(mapType,"s",float.class);mapN=publicField(mapType,"n",int.class);mapO=publicField(mapType,"o",int.class);
        initialGeometry=geometry();
        localType=Class.forName("com.corrodinggames.rts.game.units.bv",false,loader);
        localX=declared(localType,"e",float.class);localY=declared(localType,"f",float.class);localB=declared(localType,"b",boolean.class);localC=declared(localType,"c",boolean.class);
        movement=ce.getMethod("g");require(movement.getReturnType()==cgType,"o2-movement-return");
        movementType=movement.invoke(unit);require(movementType!=null,"o2-movement-null");
        pathEngineField=publicField(k,"bR",kk);pathEngine=pathEngineField.get(engine);require(kk.isInstance(pathEngine),"o2-path-engine");
        blocked=kk.getMethod("a",cgType,int.class,int.class);require(blocked.getReturnType()==boolean.class,"o2-mask-return");
        initialMask=mask();configured=true;
    }
    private static boolean[] mask() throws Exception {
        boolean[] result=new boolean[600];
        for(int row=0;row<20;row++)for(int col=0;col<30;col++)result[row*30+col]=(Boolean)blocked.invoke(pathEngine,movementType,col,row);
        return result;
    }
    public static void beginCall(Object actualEngine) {
        if(!configured || !enabled)return;
        try {
            require(first==null && !inCall && actualEngine==engine,"o2-call-order");
            if(thread==null)thread=Thread.currentThread();require(thread==Thread.currentThread(),"o2-thread");
            call=new long[FIELDS.length];normalOld=null;inCall=true;
        }catch(Throwable error){latch(error);}
    }
    public static synchronized void beginPhase() {
        require(configured && Thread.holdsLock(engine) && !inCall,"o2-phase-order");
        thread=null;call=new long[FIELDS.length];normalOld=null;
    }
    public static void endCall() {
        if(!configured || !enabled)return;
        try {
            require(inCall && thread==Thread.currentThread(),"o2-call-exit");
            require(call[6]==call[7] && call[6]<=1,"o2-normal-unpaired");
            require(call[12]==call[28] && call[12]<=1,"o2-collision-unpaired");
        }catch(Throwable error){latch(error);}finally{inCall=false;}
    }
    private static boolean active(Object actual) {
        if(!configured || !enabled || first!=null)return false;
        require(actual==unit && inCall && thread==Thread.currentThread(),"o2-hook-context");return true;
    }
    public static void beforeNormalClear(Object actual) {
        try {
            if(!active(actual))return;
            require(++call[6]==1 && call[7]==0,"o2-normal-count");normalOld=waypoint.invoke(unit);
            require(normalOld!=null && count.getInt(unit)==1,"o2-normal-single-move");
            call[8]=token(normalOld);call[9]=bits(waypointX,normalOld);call[10]=bits(waypointY,normalOld);
            Object kind=waypointKind.get(normalOld);call[11]=((Enum<?>)kind).ordinal();require(kind==moveEnum,"o2-normal-not-move");
            call[29]=bits(aa,unit);call[30]=bits(x,unit);call[31]=bits(y,unit);
            float age=aa.getFloat(unit);float threshold=age>340f?36f:age>240f?16f:7f;
            call[41]=Float.floatToRawIntBits(threshold);
        }catch(Throwable error){latch(error);}
    }
    public static void afterNormalClear(Object actual) {
        try {
            if(!active(actual))return;
            require(call[6]==1 && ++call[7]==1 && normalOld!=null,"o2-normal-pair");
            require(waypoint.invoke(unit)==null && count.getInt(unit)==0,"o2-normal-not-cleared");
        }catch(Throwable error){latch(error);}
    }
    public static void collisionBefore(Object actual,float delta,Object actualEngine,float candidateX,float candidateY) {
        try {
            if(!active(actual))return;require(actualEngine==engine && ++call[12]==1,"o2-collision-count");
            call[13]=bits(x,unit);call[14]=bits(y,unit);call[15]=Float.floatToRawIntBits(candidateX);call[16]=Float.floatToRawIntBits(candidateY);
            call[19]=Float.floatToRawIntBits(delta);call[20]=bits(cn,unit);
            require(mapField.get(engine)==mapObject && pathEngineField.get(engine)==pathEngine && geometry().equals(initialGeometry),"o2-map-replaced");
            Object actualMovement=movement.invoke(unit);require(actualMovement==movementType,"o2-movement-changed");
            call[40]=((Enum<?>)actualMovement).ordinal();
        }catch(Throwable error){latch(error);}
    }
    public static void collisionAfter(Object actual) {
        try {if(!active(actual))return;require(call[12]==1 && ++call[28]==1,"o2-collision-pair");call[17]=bits(x,unit);call[18]=bits(y,unit);}
        catch(Throwable error){latch(error);}
    }
    public static void propulsion(Object actual,float delta,Object local,Object command,boolean p4) {
        try {
            if(!active(actual))return;require(++call[21]==1 && local!=null,"o2-propulsion-count");
            require(local.getClass()==localType,"o2-local-type");
            call[22]=bits(localX,local);call[23]=bits(localY,local);
            call[24]=localB.getBoolean(local)?1:0;call[25]=localC.getBoolean(local)?1:0;
            call[26]=p4?1:0;call[27]=ay.getInt(unit);
            call[32]=bits(ch,unit);call[33]=bits(cg,unit);call[34]=requested.getBoolean(unit)?1:0;call[35]=bits(aB,unit);
            call[36]=token(command);call[37]=command==null?0:bits(waypointX,command);call[38]=command==null?0:bits(waypointY,command);
            call[39]=command==null?-1:((Enum<?>)waypointKind.get(command)).ordinal();
        }catch(Throwable error){latch(error);}
    }
    public static void capture(long[] row,int offset) throws Exception {
        require(configured,"o2-not-configured");
        call[0]=bits(ch,unit);call[1]=bits(cg,unit);call[2]=ay.getInt(unit);call[3]=requested.getBoolean(unit)?1:0;call[4]=bits(cn,unit);
        call[5]=token(waypoint.invoke(unit));System.arraycopy(call,0,row,offset,call.length);
    }
    private static synchronized void latch(Throwable error) {if(first==null)first=error;}
    public static Throwable failure() {return first;}
    public static synchronized Map<String,Object> finish() throws Exception {
        require(configured && Thread.holdsLock(engine) && !inCall,"o2-finish");
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",1);result.put("movementType",((Enum<?>)movementType).name());result.put("movementTypeOrdinal",((Enum<?>)movementType).ordinal());
        boolean[] finalMask=mask();require(java.util.Arrays.equals(initialMask,finalMask),"o2-mask-changed");
        result.put("initialMask",maskList(initialMask));result.put("finalMask",maskList(finalMask));result.put("widthTiles",30);result.put("heightTiles",20);
        require(mapField.get(engine)==mapObject && pathEngineField.get(engine)==pathEngine && movement.invoke(unit)==movementType,"o2-final-objects");
        Map<String,Object> finalGeometry=geometry();require(finalGeometry.equals(initialGeometry),"o2-final-geometry-drift");
        result.put("initialMapGeometry",initialGeometry);result.put("finalMapGeometry",finalGeometry);
        result.put("firstError",first==null?null:first.getClass().getName()+":"+first.getMessage());return result;
    }
    private static Map<String,Object> geometry() throws Exception {
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("r",Float.floatToRawIntBits(mapR.getFloat(mapObject)));
        result.put("s",Float.floatToRawIntBits(mapS.getFloat(mapObject)));result.put("n",mapN.getInt(mapObject));result.put("o",mapO.getInt(mapObject));return result;
    }
    private static List<Boolean> maskList(boolean[] values) {
        List<Boolean> result=new ArrayList<Boolean>();for(boolean value:values)result.add(value);return result;
    }
}
