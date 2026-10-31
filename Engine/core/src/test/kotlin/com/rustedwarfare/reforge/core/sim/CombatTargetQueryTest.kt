package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.*

class CombatTargetQueryTest {
    private val scene = CombatTestContent.scenario()
    private val tank = scene.unitDefinitions.getValue("c_tank")
    private val actor = unit("actor",0.0,owner="p1")
    private fun unit(id:String,x:Double,y:Double=0.0,owner:String="p2",definition:UnitDefinition=tank) =
        MutableUnit(id,owner,definition.id,definition,Position(x,y),90.0,objectId=id.hashCode().toLong() and 0x7fffffffL)
    private fun unarmed() = UnitDefinition("unarmed",tank.className,tank.radius,tank.displayRadius,tank.totalFrames,
        tank.imagePath,tank.imageScale,tank.movement,tank.retainedUnconsumed,tank.fogOfWarSightRange,
        durability=DurabilityProfile(210.0,checkNotNull(tank.combat).deadImagePath))
    private fun harmless() = UnitDefinition(tank.id,tank.className,tank.radius,tank.displayRadius,tank.totalFrames,
        tank.imagePath,tank.imageScale,tank.movement,tank.retainedUnconsumed,tank.fogOfWarSightRange,
        checkNotNull(tank.combat).let { it.copy(projectile=it.projectile.copy(directDamage=0.0)) })
    private fun nearest(units:Collection<MutableUnit>,coordinates:CoordinatePolicy=ScreenPlane) =
        CombatTargetQuery.nearestEnemy(actor,units,scene.players,coordinates)
    private fun patrol(units:Collection<MutableUnit>,visible:(String,Position)->Boolean={_,_->true}) =
        CombatTargetQuery.patrolEnemy(actor,units,scene.players,visible)

    @Test
    fun ordinary_armed_priority_and_stable_equal_distance_are_independent_of_ids() {
        val close=unit("near",20.0,definition=unarmed())
        val first=unit("z",90.0);val second=unit("a",90.0)
        assertSame(first,nearest(listOf(close,first,second)))
        assertSame(second,nearest(listOf(close,second,first)))
        assertSame(close,nearest(listOf(close)))
        assertNull(nearest(emptyList()))
    }

    @Test
    fun ordinary_range_and_signed_zero_keep_double_comparison_rules() {
        val range=checkNotNull(tank.combat).attackRange
        val inside=unit("inside",Math.nextDown(range))
        assertSame(inside,nearest(listOf(inside)))
        assertNull(nearest(listOf(unit("edge",range),unit("outside",Math.nextUp(range)))))
        val plus=unit("plus",1.0);val minus=unit("minus",-1.0)
        val signed=object:CoordinatePolicy by ScreenPlane {
            override fun distanceSquared(position:Position,target:Position)=if(target.x<0.0)-0.0 else 0.0
        }
        assertSame(minus,nearest(listOf(plus,minus),signed))
    }

    @Test
    fun eligibility_excludes_same_owner_team_dead_and_healthless_candidates() {
        val players=listOf(Player("p1",PlayerControl.LOCAL,"one"),Player("p2",PlayerControl.NONE,"one"),
            Player("p3",PlayerControl.NONE,"two"))
        val enemy=unit("enemy",90.0,owner="p3")
        val candidates=listOf(actor,unit("own",1.0,owner="p1"),unit("ally",2.0),
            unit("dead",3.0,owner="p3").also { it.health=0.0 },
            unit("healthless",4.0,owner="p3").also { it.health=null },enemy)
        assertSame(enemy,CombatTargetQuery.nearestEnemy(actor,candidates,players,ScreenPlane))
        assertTrue(CombatTargetQuery.isEnemy(actor,enemy,players))
        assertFalse(CombatTargetQuery.isEnemy(actor,candidates[2],players))
    }

    @Test
    fun patrol_armed_preference_falls_back_outside_the_weapon_range() {
        val close=unit("near",50.0,definition=unarmed())
        val armed=unit("armed",100.0)
        assertSame(armed,patrol(listOf(close,armed)))
        armed.position=Position(200.0,0.0)
        assertSame(close,patrol(listOf(close,armed)))
        val zeroDamage=unit("harmless",100.0,definition=harmless())
        assertSame(zeroDamage,nearest(listOf(close,zeroDamage)))
        assertSame(close,patrol(listOf(close,zeroDamage)))
    }

    @Test
    fun patrol_float_threshold_keeps_the_extra_one_and_strict_radial_test() {
        val range=maxOf(checkNotNull(tank.combat).attackRange.toFloat()+110f,190f)
        val onRadius=unit("radius",range.toDouble())
        assertSame(onRadius,patrol(listOf(onRadius)))
        assertNull(patrol(listOf(unit("threshold",range.toDouble(),1.0))))
        assertNull(patrol(listOf(unit("outside",Math.nextUp(range).toDouble()))))
    }

    @Test
    fun patrol_visibility_is_queried_for_the_actor_and_does_not_reveal_the_hidden_preferred_target() {
        val hidden=unit("hidden",100.0);val seen=unit("seen",50.0,definition=unarmed())
        assertSame(seen,patrol(listOf(hidden,seen)) { owner,position ->
            assertEquals("p1",owner);position==seen.position
        })
        assertNull(patrol(listOf(hidden)) { _,_->false })
    }

    @Test
    fun subsequent_queries_read_movement_death_and_appended_births_in_current_order() {
        val first=unit("first",80.0);val second=unit("second",100.0)
        val units=mutableListOf(first,second)
        assertSame(first,nearest(units))
        first.position=Position(500.0,0.0)
        assertSame(second,nearest(units))
        second.health=0.0
        assertNull(nearest(units))
        val bornFirst=unit("produced:p2:z",60.0);val bornSecond=unit("produced:p2:a",60.0)
        units+=bornFirst;units+=bornSecond
        assertSame(bornFirst,nearest(units))
        first.position=Position(60.0,0.0)
        assertSame(first,nearest(units))
        assertNull(actor.combat?.targetId,"A query must not bind a target or publish mutable state")
    }

    @Test
    fun a_failed_query_propagates_and_cannot_poison_the_next_query() {
        val target=unit("target",30.0)
        val failed=object:CoordinatePolicy by ScreenPlane {
            override fun distanceSquared(position:Position,target:Position):Double = error("query-failure")
        }
        assertFailsWith<IllegalStateException> { nearest(listOf(target),failed) }
        assertSame(target,nearest(listOf(target)))
        assertEquals(Position(30.0,0.0),target.position)
    }
}
