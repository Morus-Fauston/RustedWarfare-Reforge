package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OvertakingTest {
    @Test
    fun two_hundred_known_units_do_not_prevent_a_clear_side_lane_from_being_published() = WaterTestScene.withMap { source ->
        val width=160
        val height=100
        val map=MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),BlockingGrid(width,height,BooleanArray(width*height)))
        val base=scene(source).copy(map=map)
        val scenario=base.copy(units=base.units+(0 until 198).map { index ->
            UnitSpawn("distant-$index","p1","heavy",Position(1000.0+index%18*20.0,1000.0+index/18*20.0),0.0)
        })
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            for((id,x) in listOf("leader" to 360.0,"follower" to 340.0))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,Position(x,200.0),context.world)))
            var passed: Long?=null
            var publicationSteps=0
            for(tick in 0L..600L) {
                val frame=context.advance(Step(tick,1.0,"sim-tick"))
                val follower=frame.units.single { it.unitId=="follower" }
                val leader=frame.units.single { it.unitId=="leader" }
                if(follower.position.x>leader.position.x+12.0 && passed==null) passed=tick
                assertTrue(ScreenPlane.distanceSquared(follower.position,leader.position)>=144.0-1e-7)
                for(sample in context.drainPlanningWorkDiagnostics(context.world)) {
                    assertTrue(sample.used in 0..512)
                    assertEquals(sample.used,sample.slices.sumOf { it.workUnits })
                    if(sample.slices.any { it.publicationWorkUnits>0 }) publicationSteps++
                }
            }
            println("known-200 passed=$passed; publicationSteps=$publicationSteps")
            assertTrue(publicationSteps>0)
            assertTrue(passed!=null,"Far-away known units must not prevent publication of this clear passing lane")
        } finally { context.close() }
    }

    @Test
    fun ordinary_accelerated_turning_can_leave_a_moving_convoy_while_the_rear_body_pushes() = WaterTestScene.withMap { source ->
        acceleratedConvoy(source,1.0,false)
    }

    @Test
    fun a_pushed_circle_with_finite_braking_keeps_its_body_directed_motion_near_side_waypoints() = WaterTestScene.withMap { source ->
        acceleratedConvoy(source,.02,true,1.0,true)
    }

    private fun acceleratedConvoy(source: MapDefinition,deceleration: Double,checkDirection: Boolean,scale: Double=1.0,crossing: Boolean=false) {
        fun native(id: String,speed: Double,mass: Double): UnitDefinition {
            val base=unit(id,speed,mass)
            return UnitDefinition(id,base.className,base.radius,20.0,base.totalFrames,base.imagePath,base.imageScale,
                base.movement.copy(maxTurnSpeed=4.1,turnAcceleration=.25,moveDecelerationSpeed=deceleration),emptySet(),
                movementCollision=com.rustedwarfare.reforge.core.content.MovementCollisionShape.Circle(20.0),mass=mass)
        }
        val fast=native("fast",1.1*scale,1000.0)
        val heavy=native("heavy",.4*scale,30000.0)
        val crossingBody=native("crossing",2.0,30000.0)
        val width=160
        val height=100
        val map=MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),BlockingGrid(width,height,BooleanArray(width*height)))
        val initialized=SimulationContext.initialize(scene(source).copy(map=map,
            unitDefinitions=listOf(fast,heavy,crossingBody).associateBy { it.id },units=listOf(
                UnitSpawn("leader","p1","heavy",Position(190.0,200.0),90.0),
                UnitSpawn("follower","p1","fast",Position(150.0,200.0),90.0),
                UnitSpawn("pusher","p1","heavy",Position(110.0,200.0),90.0))+
                if(crossing) listOf(UnitSpawn("crossing","p1","crossing",Position(264.0,20.0),180.0)) else emptyList()))
        val context=assertIs<InitResult.Accepted>(initialized).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            for((id,x) in listOf("leader" to 900.0,"follower" to 880.0,"pusher" to 860.0))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,Position(x,200.0),context.world)))
            var pushed=false
            var passed=false
            var firstPass: Long?=null
            for(tick in 0L..700L) {
                if(crossing && tick==200L) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("cross-side","p1","crossing",Position(264.0,800.0),context.world)))
                val frame=context.advance(Step(tick,1.0,"sim-tick"))
                val follower=frame.units.single { it.unitId=="follower" }
                val leader=frame.units.single { it.unitId=="leader" }
                val motion=context.drainMotionDiagnostics(context.world)
                if(checkDirection) for(sample in motion) {
                    val dx=sample.candidate.x-sample.positionBefore.x
                    val dy=sample.candidate.y-sample.positionBefore.y
                    val forward=ScreenPlane.displacement(sample.integratedHeading,1.0)
                    assertTrue(dx*forward.x+dy*forward.y>=-1e-9,
                        "The candidate must not move behind its body at step $tick: $sample")
                    assertTrue(kotlin.math.abs(dx*forward.y-dy*forward.x)<=1e-9,
                        "The candidate must follow its integrated body heading at step $tick: $sample")
                }
                for(sample in motion) if(sample.unitId=="follower" &&
                    ScreenPlane.distanceSquared(Position(0.0,0.0),sample.pushedDisplacement)>1e-12) pushed=true
                if(follower.position.x>leader.position.x+40.0 && leader.moveTarget!=null) {
                    passed=true
                    if(firstPass==null) firstPass=tick
                }
                for(a in frame.units.indices) for(b in a+1 until frame.units.size)
                    assertTrue(ScreenPlane.distanceSquared(frame.units[a].position,frame.units[b].position)>=1600.0-1e-7)
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used in 0..512 })
            }
            println("accelerated-push pushed=$pushed; passed=$passed; firstPass=$firstPass")
            assertTrue(pushed,"The rear heavy body must actually displace the turning follower")
            assertTrue(passed,"Complete the pass while the original leader is still moving")
        } finally { context.close() }
    }

    @Test
    fun a_vessel_entering_and_stopping_on_the_side_leg_during_publication_is_rechecked_before_adoption() = WaterTestScene.withMap { source ->
        val width=160
        val height=100
        val map=MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),BlockingGrid(width,height,BooleanArray(width*height)))
        val base=scene(source).copy(map=map)
        val intruder=unit("intruder",1.0,30000.0)
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions=base.unitDefinitions+(intruder.id to intruder),
            units=base.units+UnitSpawn("intruder","p1",intruder.id,Position(200.0,299.0),0.0)))).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            for((id,target) in listOf("intruder" to Position(200.0,180.0),"leader" to Position(360.0,200.0),"follower" to Position(340.0,200.0)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,target,context.world)))
            var stopped: Long?=null
            var rejected=false
            var passed=false
            var stopPosition: Position?=null
            for(tick in 0L..700L) {
                if(tick==160L) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("leave-side","p1","intruder",Position(200.0,350.0),context.world)))
                val frame=context.advance(Step(tick,1.0,"sim-tick"))
                val follower=frame.units.single { it.unitId=="follower" }
                val leader=frame.units.single { it.unitId=="leader" }
                val other=frame.units.single { it.unitId=="intruder" }
                val work=context.drainPlanningWorkDiagnostics(context.world)
                if(stopped==null && work.any { sample -> sample.slices.any {
                    "follower" in it.unitIds && it.publicationWorkUnits>0 && !it.completed
                } }) {
                    stopped=tick
                    stopPosition=other.position
                    assertIs<ActionResult.Accepted>(context.submit(StopAction("occupy-side","p1","intruder",context.world)))
                }
                val paths=context.drainPathDiagnostics(context.world)
                if(stopped!=null && tick>stopped && tick<160L) {
                    if(paths.any { it.unitId=="follower" && it.reason==com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR }) {
                        rejected=rejected || follower.pathPoints.size==1
                    }
                    var from=follower.position
                    for(to in follower.pathPoints) {
                        val dx=to.x-from.x
                        val dy=to.y-from.y
                        val lengthSquared=dx*dx+dy*dy
                        val fraction=if(lengthSquared==0.0) 0.0 else
                            (((other.position.x-from.x)*dx+(other.position.y-from.y)*dy)/lengthSquared).coerceIn(0.0,1.0)
                        val nearest=Position(from.x+fraction*dx,from.y+fraction*dy)
                        assertTrue(ScreenPlane.distanceSquared(nearest,other.position)>=144.0-1e-7,
                            "Do not adopt a side connector occupied after its frozen check at step $tick")
                        from=to
                    }
                }
                if(follower.position.x>leader.position.x+12.0) passed=true
                assertTrue(work.all { it.used in 0..512 })
            }
            println("changed-side stopped=$stopped; position=$stopPosition; rejected=$rejected; passed=$passed")
            assertTrue(stopped!=null)
            assertTrue(rejected,"The snapshot checked while moving must be rejected after the occupant stops on the side leg")
            assertTrue(passed,"After the occupant leaves, the original follower order must still pass")
        } finally { context.close() }
    }

    @Test
    fun a_long_bent_route_is_checked_over_multiple_steps_before_the_follower_passes() = WaterTestScene.withMap { source ->
        val width=60
        val height=40
        val walls=listOf(12,22,32,42)
        val map=MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),
            BlockingGrid(width,height,BooleanArray(width*height) { cell ->
                val wall=walls.indexOf(cell%width)
                wall>=0 && if(wall%2==0) cell/width<35 else cell/width>=5
            }))
        val start=Position(150.0,200.0)
        val goal=Position(1050.0,200.0)
        val base=scene(source).copy(map=map)
        val probe=assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units=listOf(
            UnitSpawn("follower","p1","fast",start,90.0))))).context
        val first=try {
            assertIs<ActionResult.Accepted>(probe.submit(MoveAction("probe","p1","follower",goal,probe.world)))
            probe.advanceToMoveOutcome(0L,"follower").units.single().pathPoints.first()
        } finally { probe.close() }
        val distance=kotlin.math.sqrt(ScreenPlane.distanceSquared(start,first))
        val direction=Position((first.x-start.x)/distance,(first.y-start.y)/distance)
        val heading=ScreenPlane.desired(start,first)
        val ahead=Position(start.x+12.0*direction.x,start.y+12.0*direction.y)
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units=listOf(
            UnitSpawn("follower","p1","fast",start,heading),UnitSpawn("leader","p1","heavy",ahead,heading))))).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            for(id in listOf("leader","follower")) assertIs<ActionResult.Accepted>(
                context.submit(MoveAction("go-$id","p1",id,goal,context.world)))
            var longest=0
            var passed=false
            var incomplete=0
            var completed=0
            for(tick in 0L..1800L) {
                val frame=context.advance(Step(tick,1.0,"sim-tick"))
                val follower=frame.units.single { it.unitId=="follower" }
                val leader=frame.units.single { it.unitId=="leader" }
                longest=maxOf(longest,follower.pathPoints.size)
                if((follower.position.x-leader.position.x)*direction.x+(follower.position.y-leader.position.y)*direction.y>12.0) passed=true
                for(sample in context.drainPlanningWorkDiagnostics(context.world)) {
                    assertTrue(sample.used in 0..512)
                    for(slice in sample.slices) if(slice.publicationWorkUnits>0) {
                        if(!slice.completed) incomplete++ else if(!slice.restarted) completed++
                    }
                }
                assertTrue(ScreenPlane.distanceSquared(follower.position,leader.position)>=144.0-1e-7)
            }
            println("long-route longest=$longest; incomplete=$incomplete; completed=$completed; passed=$passed")
            assertTrue(longest>=15,"Use a genuinely long multi-bend route")
            assertTrue(incomplete>0 && completed>0)
            assertTrue(passed)
        } finally { context.close() }
    }

    @Test
    fun crowded_publication_checks_resume_within_the_shared_allowance_and_replay_identically() = WaterTestScene.withMap { source ->
        val base = scene(source)
        val pebble = unit("pebble", 0.0, 30000.0).let { original ->
            UnitDefinition(original.id, original.className, .1, .1, original.totalFrames, original.imagePath,
                original.imageScale, original.movement, emptySet(), mass = original.mass)
        }
        val scenario = base.copy(unitDefinitions = base.unitDefinitions + (pebble.id to pebble),
            units = base.units + (0 until 80).map { index ->
                UnitSpawn("pebble-$index", "p1", pebble.id,
                    Position(130.0 + index % 10 * .5, 210.0 + index / 10 * .5), 0.0)
            })
        val contexts = (0..1).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context }
        try {
            for (context in contexts) {
                context.enablePlanningWorkDiagnostics(context.world)
                for ((id, x) in listOf("leader" to 360.0, "follower" to 340.0))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id", "p1", id, Position(x,200.0), context.world)))
            }
            val publication = mutableListOf<com.rustedwarfare.reforge.core.path.PlanningWorkSlice>()
            var passed = false
            for (tick in 0L..600L) {
                val frames = contexts.map { it.advance(Step(tick,1.0,"sim-tick")) }
                assertEquals(frames[0].units, frames[1].units)
                assertEquals(frames[0].events, frames[1].events)
                val work = contexts.map { it.drainPlanningWorkDiagnostics(it.world) }
                assertEquals(work[0],work[1])
                for (sample in work[0]) {
                    assertTrue(sample.used in 0..512)
                    assertEquals(sample.used,sample.slices.sumOf { it.workUnits })
                    for (slice in sample.slices) {
                        assertTrue(slice.publicationWorkUnits in 0..slice.workUnits)
                        if (slice.publicationWorkUnits>0) publication += slice
                    }
                }
                val follower = frames[0].units.single { it.unitId=="follower" }
                val leader = frames[0].units.single { it.unitId=="leader" }
                if (follower.position.x > leader.position.x+12.0) passed=true
                assertTrue(ScreenPlane.distanceSquared(follower.position,leader.position)>=144.0-1e-7)
            }
            println("crowded publication=$publication; passed=$passed")
            assertTrue(publication.any { !it.completed }, "Actual per-occupant and per-cell publication checks must resume across steps")
            assertTrue(publication.any { it.completed && !it.restarted })
            assertTrue(passed,"The budgeted publication must still produce a useful overtake")
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun a_follower_passes_on_the_current_leg_when_the_final_target_is_across_a_wall() = WaterTestScene.withMap { source ->
        val width = 40
        val height = 30
        val map = MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),
            BlockingGrid(width,height,BooleanArray(width*height) { it%width==12 && it/width<15 }))
        val start = Position(300.0,200.0)
        val goal = Position(180.0,200.0)
        val base = scene(source).copy(map=map)
        val opening = base.copy(units=listOf(UnitSpawn("follower","p1","fast",start,180.0)))
        val probe = assertIs<InitResult.Accepted>(SimulationContext.initialize(opening)).context
        val first = try {
            assertIs<ActionResult.Accepted>(probe.submit(MoveAction("probe","p1","follower",goal,probe.world)))
            probe.advanceToMoveOutcome(0L,"follower").units.single().pathPoints.first()
        } finally { probe.close() }
        val length = kotlin.math.sqrt(ScreenPlane.distanceSquared(start,first))
        val direction = Position((first.x-start.x)/length,(first.y-start.y)/length)
        val ahead = Position(start.x+12.0*direction.x,start.y+12.0*direction.y)
        val heading = ScreenPlane.desired(start,first)
        val scenario = base.copy(units=listOf(UnitSpawn("leader","p1","heavy",ahead,heading),
            UnitSpawn("follower","p1","fast",start,heading)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            for(id in listOf("leader","follower")) assertIs<ActionResult.Accepted>(
                context.submit(MoveAction("go-$id","p1",id,goal,context.world)))
            var limited = false
            var passed: Long? = null
            for(tick in 0L..500L) {
                val frame = context.advance(Step(tick,1.0,"sim-tick"))
                val follower = frame.units.single { it.unitId=="follower" }
                val leader = frame.units.single { it.unitId=="leader" }
                if(context.drainMotionDiagnostics(context.world).any { it.unitId=="follower" && it.hardContactLimitedTicks>=60 }) limited=true
                val relative = (follower.position.x-leader.position.x)*direction.x+(follower.position.y-leader.position.y)*direction.y
                if(tick%100==0L) println("bent step=$tick; relative=$relative; follower=$follower; leader=${leader.position}")
                if(relative>12 && passed==null) passed=tick
                assertTrue(ScreenPlane.distanceSquared(follower.position,leader.position)>=144.0-1e-7)
            }
            println("bent first=$first; direction=$direction; limited=$limited; passed=$passed")
            println("bent searches=${context.drainPathDiagnostics(context.world)}")
            assertTrue(limited,"The first leg must really be limited by the moving leader")
            assertTrue(passed!=null,"Use the current leg, rather than the final target's ray, to pass")
        } finally { context.close() }
    }

    @Test
    fun a_real_later_projectile_overflow_restores_the_moving_bypass_and_established_contact_history() = WaterTestScene.withMap { source ->
        val width = 160
        val height = 100
        val map = MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),BlockingGrid(width,height,BooleanArray(width*height)))
        val base = scene(source)
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val shooter = UnitDefinition("shooter",tank.className,tank.radius,tank.displayRadius,tank.totalFrames,tank.imagePath,
            tank.imageScale,tank.movement.copy(moveSpeed=1.0,moveAccelerationSpeed=1.0,moveDecelerationSpeed=1.0,
                maxTurnSpeed=90.0,turnAcceleration=0.0),emptySet(),15,tank.combat)
        val victim = UnitDefinition("victim",tank.className,tank.radius,tank.displayRadius,tank.totalFrames,tank.imagePath,
            tank.imageScale,shooter.movement,emptySet(),15,checkNotNull(tank.combat).copy(attackRange=1.0))
        val targetX = 800.0 + checkNotNull(shooter.combat).attackRange + 69.5
        val scenario = base.copy(map=map,players=base.players.map { it.copy(control=PlayerControl.LOCAL) },
            unitDefinitions=base.unitDefinitions+mapOf(shooter.id to shooter,victim.id to victim),
            units=base.units+listOf(UnitSpawn("victim","p2",victim.id,Position(targetX,1000.0),270.0),
                UnitSpawn("z-shooter","p1",shooter.id,Position(800.0,1000.0),90.0),
                UnitSpawn("victim2","p2",victim.id,Position(targetX,1500.0),270.0),
                UnitSpawn("z-shooter2","p1",shooter.id,Position(800.0,1500.0),90.0)),
            combatContract=CombatContract(12345,-1,mapOf("leader" to 8L,"follower" to 9L,"victim" to 10L,
                "z-shooter" to 11L,"victim2" to 12L,"z-shooter2" to 13L),Long.MAX_VALUE-1))
        val contexts = listOf(0,1).map { assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context }
        try {
            for(context in contexts) {
                context.enableMotionDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                for((id,target) in listOf("leader" to Position(360.0,200.0),"follower" to Position(340.0,200.0)))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,target,context.world)))
                for((id,y) in listOf("victim" to 1000.0,"victim2" to 1500.0))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p2",id,Position(800.0,y),context.world)))
                for(tick in 0L..68L) context.advance(Step(tick,1.0,"sim-tick"))
                assertTrue(context.drainMotionDiagnostics(context.world).last { it.unitId=="follower" }.hardContactLimitedTicks>=60)
                val beforeFailureWork = context.drainPlanningWorkDiagnostics(context.world)
                println("before-overflow work=${beforeFailureWork.filter { it.used>0 }}")
                assertTrue(beforeFailureWork.last().slices.any { it.publicationWorkUnits>0 && !it.completed },
                    "The real overflow must interrupt an already-started publication")
                assertTrue(beforeFailureWork.any { sample -> sample.slices.any {
                    it.reason==com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR && !it.completed
                } })
            }
            val failed = contexts.first()
            val before = failed.observe()
            // The targets update before the shooters. The second real shot
            // exhausts the final usable ID after the moving repair has advanced.
            assertFailsWith<ArithmeticException> { failed.advance(Step(69,1.0,"sim-tick")) }
            assertSame(before,failed.observe())
            assertTrue(failed.drainMotionDiagnostics(failed.world).isEmpty())
            assertTrue(failed.drainPlanningWorkDiagnostics(failed.world).isEmpty())
            for(context in contexts) for(id in listOf("victim","victim2"))
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-$id","p2",id,context.world)))
            var published = false
            var resumedPublication = false
            for(tick in 69L..250L) {
                val frames=contexts.map { it.advance(Step(tick,1.0,"sim-tick")) }
                assertEquals(frames[0].units,frames[1].units)
                assertEquals(frames[0].events,frames[1].events)
                val work = contexts.map { it.drainPlanningWorkDiagnostics(it.world) }
                assertEquals(work[0],work[1])
                if(work[0].any { sample -> sample.slices.any { it.publicationWorkUnits>0 && !it.completed } }) resumedPublication=true
                val motion=contexts.map { context -> context.drainMotionDiagnostics(context.world).map { it.unitId to it.hardContactLimitedTicks } }
                assertEquals(motion[0],motion[1])
                val follower = frames[0].units.single { it.unitId=="follower" }
                if(!published && follower.pathPoints.size>1) {
                    published=true
                    assertEquals(0L,motion[0].single { it.first=="follower" }.second)
                } else if(!published) assertTrue(motion[0].single { it.first=="follower" }.second>=60)
            }
            assertTrue(published,"Retry must eventually publish the useful bypass and release contact history")
            assertTrue(resumedPublication,"Retry must retain and advance the publication cursor across steps")
        } finally { contexts.forEach { it.close() } }
    }

    @Test
    fun stop_and_replacement_clear_established_contact_history_and_cancel_the_moving_bypass() = WaterTestScene.withMap { source ->
        val width = 160
        val height = 100
        val map = MapDefinition(width,height,20,20,emptyList(),IntArray(width*height),BlockingGrid(width,height,BooleanArray(width*height)))
        for (stop in listOf(true,false)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(source).copy(map=map))).context
            try {
                context.enableMotionDiagnostics(context.world)
                context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                for ((id,x) in listOf("leader" to 360.0,"follower" to 340.0))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,Position(x,200.0),context.world)))
                for(tick in 0L..62L) context.advance(Step(tick,1.0,"sim-tick"))
                assertTrue(context.drainMotionDiagnostics(context.world).any { it.unitId=="follower" && it.hardContactLimitedTicks>=60 })
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).any { sample -> sample.slices.any {
                    it.reason==com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR && !it.completed
                } })
                val before = context.observe().units.single { it.unitId=="follower" }.position
                if(stop) assertIs<ActionResult.Accepted>(context.submit(StopAction("halt","p1","follower",context.world)))
                else assertIs<ActionResult.Accepted>(context.submit(MoveAction("replace","p1","follower",Position(100.0,200.0),context.world)))
                for(tick in 63L..160L) {
                    val frame = context.advance(Step(tick,1.0,"sim-tick"))
                    assertEquals(0L,context.drainMotionDiagnostics(context.world).single { it.unitId=="follower" }.hardContactLimitedTicks)
                    if(stop) assertEquals(before,frame.units.single { it.unitId=="follower" }.position)
                }
                assertTrue(context.drainPathDiagnostics(context.world).none {
                    it.actionId=="go-follower" && it.reason==com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR
                })
                if(!stop) assertTrue(context.observe().units.single { it.unitId=="follower" }.position.x<before.x)
            } finally { context.close() }
        }
    }

    @Test
    fun a_follower_uses_the_open_side_when_the_first_fast_unit_occupies_the_other_departure() = WaterTestScene.withMap { map ->
        val scenario = scene(map).copy(units = listOf(
            UnitSpawn("leader","p1","heavy",Position(162.0,200.0),90.0),
            UnitSpawn("first","p1","fast",Position(158.0,218.0),90.0),
            UnitSpawn("second","p1","fast",Position(150.0,200.0),90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            for ((id,x) in listOf("leader" to 360.0,"second" to 340.0))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,Position(x,200.0),context.world)))
            val sides = mutableSetOf<Int>()
            val passed = mutableMapOf<String,Long>()
            val trail = mutableListOf<String>()
            for(tick in 0L..600L) {
                if(tick==100L) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("go-first","p1","first",Position(340.0,218.0),context.world)))
                val frame = context.advance(Step(tick,1.0,"sim-tick"))
                val leader = frame.units.single { it.unitId=="leader" }
                val second = frame.units.single { it.unitId=="second" }
                for(unit in frame.units.filter { it.unitId!="leader" })
                    if(unit.position.x > leader.position.x+12.0) passed.putIfAbsent(unit.unitId,tick)
                if("second" !in passed && kotlin.math.abs(second.position.y-200.0)>4.0)
                    sides += if(second.position.y>200.0) 1 else -1
                for(a in frame.units.indices) for(b in a+1 until frame.units.size)
                    assertTrue(ScreenPlane.distanceSquared(frame.units[a].position,frame.units[b].position)>=144.0-1e-7)
                if(tick%40==0L) trail += "$tick:${frame.units.map { it.unitId to it.position }}"
            }
            println("occupied-side passed=$passed; sides=$sides; trail=$trail")
            assertEquals(setOf(-1),sides,"The lower departure is occupied; the upper departure is open")
            assertEquals(setOf("first","second"),passed.keys)
            assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used in 0..512 })
        } finally { context.close() }
    }

    @Test
    fun two_faster_units_can_pass_the_same_side_in_sequence_without_switching_sides() = WaterTestScene.withMap { source ->
        for (width in listOf(20, 160)) {
        val height = if (width == 20) 20 else 100
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height) { it / width < 10 }))
        val base = scene(source)
        val scenario = base.copy(map = map, units = listOf(
            UnitSpawn("leader", "p1", "heavy", Position(162.0, 210.0), 90.0),
            UnitSpawn("first", "p1", "fast", Position(150.0, 210.0), 90.0),
            UnitSpawn("second", "p1", "fast", Position(138.0, 210.0), 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            for ((id,x) in listOf("leader" to 360.0, "first" to 340.0, "second" to 320.0))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id", "p1", id, Position(x,210.0),context.world)))
            val passed = mutableMapOf<String,Long>()
            val sides = mutableMapOf<String,MutableSet<Int>>()
            val trail = mutableListOf<String>()
            for (tick in 0L..600L) {
                val frame = context.advance(Step(tick,1.0,"sim-tick"))
                val leader = frame.units.single { it.unitId == "leader" }
                for (unit in frame.units.filter { it.unitId != "leader" }) {
                    if (unit.position.x > leader.position.x + 12.0) passed.putIfAbsent(unit.unitId,tick)
                    if (kotlin.math.abs(unit.position.y - 210.0) > 4.0)
                        sides.getOrPut(unit.unitId) { mutableSetOf() } += if (unit.position.y > 210.0) 1 else -1
                    assertTrue(unit.position.y >= 206.0)
                }
                for (a in frame.units.indices) for (b in a+1 until frame.units.size)
                    assertTrue(ScreenPlane.distanceSquared(frame.units[a].position,frame.units[b].position) >= 144.0 - 1e-7)
                val motion = context.drainMotionDiagnostics(context.world)
                if (tick % 40 == 0L) trail += "$tick:${frame.units.map { it.unitId to it.position }}:${motion.map { it.unitId to it.hardContactLimitedTicks }}"
                if (tick == 120L || tick == 400L) {
                    val waiting = frame.units.single { it.unitId == "second" }
                    val sample = motion.single { it.unitId == "second" }
                    println("same-side waiting width=$width; step=$tick; unit=$waiting; motion=$sample")
                }
            }
            println("same-side width=$width; passed=$passed; sides=$sides; trail=$trail")
            println("same-side searches=${context.drainPathDiagnostics(context.world)}")
            assertEquals(setOf("first","second"),passed.keys)
            assertEquals(mapOf("first" to setOf(1),"second" to setOf(1)),sides)
            assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used in 0..512 })
        } finally { context.close() }
        }
    }

    @Test
    fun a_narrow_corridor_keeps_a_moving_follower_behind_the_heavy_leader() = WaterTestScene.withMap { source ->
        val map = MapDefinition(1,30,20,20,emptyList(),IntArray(30),BlockingGrid(1,30,BooleanArray(30)))
        val scenario = scene(source).copy(map=map,units=listOf(
            UnitSpawn("leader","p1","heavy",Position(10.0,388.0),0.0),
            UnitSpawn("follower","p1","fast",Position(10.0,400.0),0.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            for ((id,y) in listOf("leader" to 40.0,"follower" to 60.0))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id","p1",id,Position(10.0,y),context.world)))
            var following = false
            for(tick in 0L..500L) {
                val frame = context.advance(Step(tick,1.0,"sim-tick"))
                val follower = frame.units.single { it.unitId=="follower" }
                val leader = frame.units.single { it.unitId=="leader" }
                assertEquals(10.0,follower.position.x,1e-8)
                assertTrue(follower.position.y - leader.position.y >= 12.0 - 1e-8)
                if(context.drainMotionDiagnostics(context.world).single { it.unitId=="follower" }.hardContactLimitedTicks>=60)
                    following = true
                assertTrue(frame.events.none { it.kind in setOf("movement-cancelled","path-unreachable") })
            }
            assertTrue(following)
            assertTrue(context.observe().units.single { it.unitId=="follower" }.position.y < 310.0)
            assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used in 0..512 })
        } finally { context.close() }
    }

    @Test
    fun following_is_kept_when_the_only_bypass_is_a_clearly_slower_trip_around_the_walls() = WaterTestScene.withMap { source ->
        val width = 20
        val height = 20
        val walls = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x in 4..14 && y in setOf(9, 11)
        }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, walls))
        val base = scene(source)
        val leader = unit("heavy", .6, 30000.0)
        val scene = base.copy(map = map, unitDefinitions = base.unitDefinitions + (leader.id to leader),
            units = base.units.map { it.copy(position = Position(it.position.x, 210.0)) })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePathDiagnostics(context.world)
            for ((id, x) in listOf("leader" to 360.0, "follower" to 340.0)) assertIs<ActionResult.Accepted>(
                context.submit(MoveAction("go-$id", "p1", id, Position(x, 210.0), context.world)))
            var previousX = 150.0
            val trail = mutableListOf<String>()
            var backedUp = false
            for (tick in 0L..250L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                val follower = frame.units.single { it.unitId == "follower" }
                if (follower.position.x < previousX - 1e-7 || kotlin.math.abs(follower.position.y - 210.0) > 1e-7) backedUp = true
                previousX = follower.position.x
                if (tick % 20 == 0L) trail += "$tick:${follower.position}:${follower.pathPoints}"
            }
            println("long-detour backedUp=$backedUp; trail=$trail; searches=${context.drainPathDiagnostics(context.world)}")
            assertTrue(!backedUp, "Keep advancing behind the .6 leader instead of taking a much longer reverse detour at .7")
            assertTrue(previousX > 280.0)
        } finally { context.close() }
    }

    @Test
    fun a_faster_unit_overtakes_a_heavy_leader_without_waiting_for_both_to_stop() = WaterTestScene.withMap { map ->
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(map))).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            for ((id, x) in listOf("leader" to 360.0, "follower" to 340.0)) {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go-$id", "p1", id, Position(x, 200.0), context.world)))
            }
            var firstLimited: Long? = null
            var passed: Long? = null
            var movingLimited = false
            val trail = mutableListOf<String>()
            for (tick in 0L..500L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                val leader = frame.units.single { it.unitId == "leader" }
                val follower = frame.units.single { it.unitId == "follower" }
                val motion = context.drainMotionDiagnostics(context.world).single { it.unitId == "follower" }
                if (motion.hardContactLimitedTicks > 0) {
                    if (firstLimited == null) firstLimited = tick
                    if (motion.hardContactLimitedTicks >= 60 && follower.position.x > 150.0) movingLimited = true
                }
                if (follower.position.x > leader.position.x + 12.0 && passed == null) passed = tick
                if (tick % 20 == 0L) trail += "$tick:${leader.position}:${follower.position}:${motion.hardContactLimitedTicks}"
                assertTrue(ScreenPlane.distanceSquared(leader.position, follower.position) >= 144.0 - 1e-7)
                assertTrue(frame.events.none { it.kind in setOf("movement-cancelled", "path-unreachable") })
                follower.moveTarget?.let { assertEquals(Position(340.0, 200.0), it) }
            }
            println("moving-follow firstLimited=$firstLimited; passed=$passed; trail=$trail")
            println("moving-follow searches=${context.drainPathDiagnostics(context.world)}")
            val work = context.drainPlanningWorkDiagnostics(context.world)
            println("moving-follow work=${work.filter { it.used > 0 }}")
            assertTrue(movingLimited, "The fixture must really follow while hard-contact limited")
            assertTrue(passed != null, "A legal wide-road overtake must actually put the follower ahead")
            assertTrue(work.all { it.limit == 512 && it.used in 0..512 && it.slices.sumOf { slice -> slice.workUnits } == it.used })
        } finally { context.close() }
    }

    private fun scene(map: MapDefinition): Scenario {
        val follower = unit("fast", .7, 1000.0)
        val leader = unit("heavy", .2, 30000.0)
        return WaterTestScene.scenario(map, MovementType.LAND, Position(150.0, 200.0)).copy(
            unitDefinitions = listOf(follower, leader).associateBy { it.id }, units = listOf(
                UnitSpawn("leader", "p1", leader.id, Position(162.0, 200.0), 90.0),
                UnitSpawn("follower", "p1", follower.id, Position(150.0, 200.0), 90.0)))
    }

    private fun unit(id: String, speed: Double, mass: Double): UnitDefinition {
        val base = WaterTestScene.unit(MovementType.LAND)
        return UnitDefinition(id, base.className, base.radius, base.displayRadius, base.totalFrames, base.imagePath,
            base.imageScale, base.movement.copy(moveSpeed = speed, moveAccelerationSpeed = 1.0,
                moveDecelerationSpeed = 1.0, maxTurnSpeed = 90.0, turnAcceleration = 0.0), emptySet(), mass = mass)
    }
}
