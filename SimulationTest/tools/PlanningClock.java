import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.lang.management.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** JDK 24+ host measurement agent. Counts outer intervals once, including repeated scans. */
public final class PlanningClock implements ClassFileTransformer {
    static final Map<String, Set<String>> ROOTS = Map.of(
        "SimulationContext", Set.of("requestGroupMove", "submit", "refreshGroupDestinations", "replanForKnowledge",
            "repairInvalidConnections", "recoverStalledMoves", "prepareRouteSuccessors", "scheduleManeuverRepair", "groupStopClearsSlots",
            "routeOccupancy", "continuationFits", "planningTerrainFor", "requestRouteContinuation",
            "updateUnit$maneuverAim", "preparesRouteSuccessor"),
        "PassageAdmission", Set.of("waitingUnits"), "LocalMotionMaintenance", Set.of("advance"),
        "MovePlanning", Set.of("advance", "fork", "enqueue", "repair", "cancel"));
    static final Set<String> installed = new TreeSet<>();
    static final ThreadMXBean clock = ManagementFactory.getThreadMXBean();
    static long owner, startCpu, startWall, totalCpu, totalWall;
    static int depth, step;
    static Set<?> admissionWait = Set.of();
    static final Map<String,Integer> calls = new TreeMap<>();
    static int outerCalls, nestedCalls;
    public static void waitSet(Set<?> units) { admissionWait = units; }
    static BufferedWriter output;
    public static void premain(String path, Instrumentation instrumentation) throws IOException {
        owner = Thread.currentThread().threadId();
        clock.setThreadCpuTimeEnabled(true);
        output = Files.newBufferedWriter(Path.of(path), StandardOpenOption.CREATE_NEW);
        output.write("step\tplanningCpuNs\tplanningWallNs\touterCalls\tnestedCalls\tterrainCalls\tlocalContinuationCalls\tadmissionWaitIds\n");
        instrumentation.addTransformer(new PlanningClock());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {output.close(); System.err.println("planning clock hooks " + installed);}
            catch (IOException e) {throw new UncheckedIOException(e);}
        }));
    }
    public static void enter(String name) {
        calls.merge(name,1,Integer::sum);
        if (depth==0) outerCalls++; else nestedCalls++;
        if (Thread.currentThread().threadId()!=owner) throw new IllegalStateException("unaccounted planning thread");
        if (depth++==0) { startWall=System.nanoTime(); startCpu=clock.getCurrentThreadCpuTime(); }
    }
    public static void leave() {
        if (--depth==0) { totalCpu+=clock.getCurrentThreadCpuTime()-startCpu; totalWall+=System.nanoTime()-startWall; }
    }
    public static void stepEnd() throws IOException {
        if(depth!=0) throw new IllegalStateException("incomplete timed interval");
        if(step==0) for (var entry : ROOTS.entrySet()) for (var name : entry.getValue())
            if (!entry.getKey().equals("LocalMotionMaintenance") && !installed.contains(entry.getKey()+"."+name)) throw new IllegalStateException("missing hook "+name);
        output.write(step++ + "\t" + totalCpu + "\t" + totalWall + "\t" + outerCalls + "\t" + nestedCalls + "\t" + calls.getOrDefault("SimulationContext.planningTerrainFor",0) + "\t" + calls.getOrDefault("LocalMotionMaintenance.advance",0) + "\t" + String.join(",", admissionWait.stream().map(Object::toString).sorted().toList()) + "\n");
        calls.clear(); outerCalls=0; nestedCalls=0; admissionWait=Set.of();
        totalCpu=0; totalWall=0;
    }
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redef, java.security.ProtectionDomain domain, byte[] bytes) {
        String prefix="com/rustedwarfare/reforge/core/sim/";
        if(name==null || !name.startsWith(prefix)) return null;
        var type=name.substring(prefix.length()); var names=ROOTS.get(type);
        if(names==null) return null;
        var cf=ClassFile.of(); var model=cf.parse(bytes);
        var hook=ClassDesc.of("PlanningClock"); var sig=MethodTypeDesc.of(ConstantDescs.CD_void);
        return cf.transformClass(model,(cb,el)->{
            if (!(el instanceof MethodModel method)) {cb.with(el);return;}
            var methodName=method.methodName().stringValue();
            boolean tick=type.equals("SimulationContext") && methodName.equals("advance") && method.methodType().stringValue().startsWith("(L"+prefix+"Step;)");
            if (!tick && !names.contains(methodName)) {cb.with(el);return;}
            installed.add(type+"."+methodName);
            cb.transformMethod(method,MethodTransform.transformingCode(new CodeTransform(){
                @Override public void atStart(CodeBuilder code) {if(!tick) code.ldc(type+"."+methodName).invokestatic(hook,"enter",MethodTypeDesc.of(ConstantDescs.CD_void,ConstantDescs.CD_String));}
                @Override public void accept(CodeBuilder code,CodeElement item) {
                    if(item instanceof ReturnInstruction) {
                        code.invokestatic(hook,tick?"stepEnd":"leave",sig);
                        if (type.equals("PassageAdmission") && methodName.equals("waitingUnits"))
                            code.dup().invokestatic(hook,"waitSet",MethodTypeDesc.of(ConstantDescs.CD_void,ClassDesc.of("java.util.Set")));
                    }
                    code.with(item);
                }
            }));
        });
    }
}
