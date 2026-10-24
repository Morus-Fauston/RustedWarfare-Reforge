package com.rustedwarfare.reforge.reference.motion;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.LinkedHashMap;

/** Synthetic reflection fixtures only. No original game classes are loaded or simulated. */
public final class LocalPathClearLatchTest {
    public enum Kind { MOVE }
    public enum Movement { OTHER, LAND }
    public static final class Waypoint { public float e=410,f=200;public Kind a=Kind.MOVE; }
    public static final class Unit {
        public long ej=55;public float eq=347,er=210,ch=1,cg,aa,aB;public int O=1,ay=1;public boolean i=true;
        private boolean s;public Unit af;public Waypoint command=new Waypoint();
        public Waypoint ap(){return command;}public Movement g(){return Movement.LAND;}
    }
    public static final class Local {public float e=350,f=210;public boolean b=true,c;}
    public static final class Grid {public float r=.05f,s=.05f;public int n=20,o=20;}
    public static final class PathEngine {public boolean a(Movement type,int x,int y){return false;}}
    public static final class Engine {public Grid bI=new Grid();public PathEngine bR=new PathEngine();}
    private static void check(boolean good){if(!good)throw new AssertionError();}
    private static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void set(String name,Object value)throws Exception {field(MinimalMotionObservation.class,name).set(null,value);}
    private static void bind(Engine engine,Unit unit)throws Exception {
        set("engine",engine);set("unit",unit);set("configured",true);set("enabled",true);set("moveEnum",Kind.MOVE);
        for(String[] pair:new String[][]{{"x","eq"},{"y","er"},{"ch","ch"},{"cg","cg"},{"ay","ay"},{"requested","i"},
            {"count","O"},{"aa","aa"},{"aB","aB"},{"unitEj","ej"},{"pathS","s"},{"followAf","af"}})set(pair[0],field(Unit.class,pair[1]));
        set("waypoint",Unit.class.getMethod("ap"));set("waypointX",field(Waypoint.class,"e"));set("waypointY",field(Waypoint.class,"f"));set("waypointKind",field(Waypoint.class,"a"));
        set("localType",Local.class);set("localX",field(Local.class,"e"));set("localY",field(Local.class,"f"));set("localB",field(Local.class,"b"));set("localC",field(Local.class,"c"));
        set("mapObject",engine.bI);set("mapField",field(Engine.class,"bI"));set("pathEngine",engine.bR);set("pathEngineField",field(Engine.class,"bR"));
        set("movementType",Movement.LAND);set("movement",Unit.class.getMethod("g"));set("blocked",PathEngine.class.getMethod("a",Movement.class,int.class,int.class));
        for(String[] pair:new String[][]{{"mapR","r"},{"mapS","s"},{"mapN","n"},{"mapO","o"}})set(pair[0],field(Grid.class,pair[1]));
        set("initialMask",new boolean[600]);Map<String,Object> geometry=new LinkedHashMap<String,Object>();
        geometry.put("r",Float.floatToRawIntBits(.05f));geometry.put("s",Float.floatToRawIntBits(.05f));geometry.put("n",20);geometry.put("o",20);set("initialGeometry",geometry);
    }
    public static void main(String[] args)throws Exception {
        check(args.length==1 && (args[0].equals("events") || args[0].equals("calls")));
        Engine engine=new Engine();Unit unit=new Unit();bind(engine,unit);
        synchronized(engine) {
            MinimalMotionObservation.beginPhase(0);
            int count=args[0].equals("events")?17:513;
            for(int i=0;i<count;i++) {
                MinimalMotionObservation.beginCall(engine,0,i);
                if(args[0].equals("events")) {
                    unit.command=new Waypoint();unit.O=1;
                    MinimalMotionObservation.propulsion(unit,1f,new Local(),unit.command,true);
                    MinimalMotionObservation.beforeLocalPathClear(unit);
                    // Explicit synthetic clear effect, not execution of original cV.
                    unit.command=null;unit.O=0;MinimalMotionObservation.afterLocalPathClear(unit);
                }
                MinimalMotionObservation.endCall();
                if(i<count-1)check(MinimalMotionObservation.failure()==null);
            }
            Throwable first=MinimalMotionObservation.failure();check(first!=null && first==NativeMotionTrace.failure());
            check(first.getMessage().equals(args[0].equals("events")?"local-clear-overflow":"local-clear-call"));
            MinimalMotionObservation.beforeLocalPathClear(new Object());check(MinimalMotionObservation.failure()==first);
            if(args[0].equals("events")) {
                boolean rejected=false;try{MinimalMotionObservation.finish();}catch(IllegalStateException expected){rejected=true;}check(rejected);
            } else {
                Map<String,Object> report=MinimalMotionObservation.finish();check(report.get("firstError")!=null);
                @SuppressWarnings("unchecked") Map<String,Object> side=(Map<String,Object>)report.get("localPathClear");
                check(side.get("firstError")!=null && Integer.valueOf(512).equals(side.get("completedCallCount")));
            }
            check(MinimalMotionObservation.failure()==first && NativeMotionTrace.failure()==first);
        }
        System.out.println("{\"kind\":\"synthetic-local-clear-latch\",\"boundary\":\""+args[0]+"\",\"firstErrorPreserved\":true,\"partialCannotBeClaimedClean\":true}");
    }
}
