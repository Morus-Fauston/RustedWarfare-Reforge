import com.rustedwarfare.reforge.client.TestScenarioChoice;
import com.rustedwarfare.reforge.core.sim.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Host-side observation of a single ordinary move. All coordinates are engine world units. */
class GroupReplay {
    static void row(Writer out, Object... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i != 0) out.write('\t');
            if (fields[i] != null) out.write(fields[i].toString());
        }
        out.write('\n');
    }

    static Object x(Position p) { return p == null ? null : p.getX(); }
    static Object y(Position p) { return p == null ? null : p.getY(); }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("scene output command clickX clickY steps");
        var scene = TestScenarioChoice.Companion.open(Path.of(args[0]), null).getScenario();
        var out = Path.of(args[1]);
        var command = args[2];
        var click = new Position(Double.parseDouble(args[3]), Double.parseDouble(args[4]));
        int steps = Integer.parseInt(args[5]);
        if (steps < 1) throw new IllegalArgumentException("positive step count required");
        var members = scene.getUnits().stream().filter(u -> u.getOwnerPlayerId().equals("p1"))
                .map(UnitSpawn::getUnitId).toList();
        var clock = ManagementFactory.getThreadMXBean();
        if (!clock.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("thread CPU timing unavailable");
        clock.setThreadCpuTimeEnabled(true);
        var initialized = SimulationContext.Companion.initialize(scene);
        if (!(initialized instanceof InitResult.Accepted accepted)) throw new IllegalArgumentException(initialized.toString());
        var context = accepted.getContext();
        try (var trace = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(out.resolve("trace.tsv.gz"), StandardOpenOption.CREATE_NEW)), StandardCharsets.UTF_8));
             var timings = Files.newBufferedWriter(out.resolve("steps.tsv"), StandardOpenOption.CREATE_NEW)) {
            row(trace,"step","unit","type","command","origin","x","y","heading","speed","angular","activeTargetX","activeTargetY","beforeX","beforeY","beforeHeading","ownEndX","ownEndY","ownEndHeading","pushX","pushY","aimX","aimY","steering","limited","published","arrived","arrivalOriginalTargetX","arrivalOriginalTargetY");
            row(timings,"step","advanceThreadCpuNs","advanceWallNs","work","budget","activeSlices","publicationWork","restarts","orderCpuNs");
            context.enableMotionDiagnostics(context.getWorld());
            context.enablePlanningWorkDiagnostics(context.getWorld());
            long orderStart = clock.getCurrentThreadCpuTime();
            var group = context.requestGroupMove("p1", members, click);
            if (members.size() > 1 && group == null) throw new IllegalStateException("group rejected");
            for (String id : members) {
                var action = new MoveAction(command + "-" + id, "p1", id, click, context.getWorld(), null);
                var result = group == null ? context.submit(action) : context.submitGroupMove(action, group);
                if (!(result instanceof ActionResult.Accepted)) throw new IllegalStateException(result.toString());
            }
            long orderCpu = clock.getCurrentThreadCpuTime() - orderStart;
            for (int i = 0; i < steps; i++) {
                var step = new Step(i, 1.0, "sim-tick");
                long wall = System.nanoTime(), cpu = clock.getCurrentThreadCpuTime();
                var frame = context.advance(step);
                long elapsedCpu = clock.getCurrentThreadCpuTime() - cpu, elapsedWall = System.nanoTime() - wall;
                var samples = new HashMap<String, MotionStepSample>();
                for (var m : context.drainMotionDiagnostics(context.getWorld())) samples.put(m.getUnitId(), m);
                var published = new HashSet<String>();
                var arrived = new HashSet<String>();
                for (var ev : frame.getEvents()) {
                    if (ev.getKind().equals("movement-started")) published.add(ev.getUnitId());
                    if (ev.getKind().equals("movement-arrived")) arrived.add(ev.getUnitId());
                }
                for (var u : frame.getUnits()) {
                    String id = u.getUnitId();
                    if (!members.contains(id)) continue;
                    var m = samples.get(id);
                    var before = m == null ? null : m.getPositionBefore();
                    var own = m == null ? null : m.getFinalPosition();
                    var push = m == null ? null : m.getPushedDisplacement();
                    var aim = m == null ? null : m.getSteeringAim();
                    var arrivalTarget = u.getMoveArrival() == null ? null : u.getMoveArrival().getOriginalTarget();
                    row(trace,i,id,u.getTypeId(),command,m == null ? null : m.getOriginMoveId(),x(u.getPosition()),y(u.getPosition()),u.getHeading(),u.getSpeed(),u.getAngularVelocity(),x(u.getMoveTarget()),y(u.getMoveTarget()),x(before),y(before),m == null ? null : m.getHeadingBefore(),x(own),y(own),m == null ? null : m.getFinalHeading(),x(push),y(push),x(aim),y(aim),m == null ? null : m.getSteeringInvoked(),m == null ? null : m.getUnitContactLimited(),published.contains(id),arrived.contains(id),x(arrivalTarget),y(arrivalTarget));
                }
                var work = context.drainPlanningWorkDiagnostics(context.getWorld());
                if (work.size()!=1 || work.get(0).getUsed()>work.get(0).getLimit()) throw new IllegalStateException("budget sample");
                var sample = work.get(0);
                var slices = sample.getSlices();
                row(timings,i,elapsedCpu,elapsedWall,sample.getUsed(),sample.getLimit(),slices.size(),
                    slices.stream().mapToInt(s -> s.getPublicationWorkUnits()).sum(),
                    slices.stream().filter(s -> s.getRestarted()).count(),i == 0 ? orderCpu : 0);
            }
        } finally { context.close(); }
    }
}
