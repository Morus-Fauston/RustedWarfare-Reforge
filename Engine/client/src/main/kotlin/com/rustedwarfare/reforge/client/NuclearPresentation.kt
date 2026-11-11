package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.util.Collections

internal enum class NuclearEffectKind { TRAIL, LAUNCH, EXPLOSION }
internal data class NuclearEffectDrawing(val kind: NuclearEffectKind, val center: Position,
    val height: Double, val radius: Float, val alpha: Float)

/** Retains only effects witnessed in this player's published view and simulation timeline. */
internal class NuclearPresentation {
    private data class Particle(val kind: NuclearEffectKind, val center: Position, val height: Double, val born: Double)
    private var identity: Pair<String,String>?=null
    private var previousTime: Double?=null
    private var sample: String?=null
    private val emitters=mutableMapOf<String,Double>()
    private val particles=mutableListOf<Particle>()

    fun project(view: PlayerObservation): List<NuclearEffectDrawing> {
        val now=view.simulationTime
        val key=view.worldId to view.playerId
        if (identity!=key || previousTime?.let { now<it } == true) {
            identity=key;sample=null;emitters.clear();particles.clear()
        }
        previousTime=now
        fun lifetime(kind: NuclearEffectKind)=when(kind) {
            NuclearEffectKind.TRAIL->30.0;NuclearEffectKind.LAUNCH->20.0;NuclearEffectKind.EXPLOSION->120.0
        }
        particles.removeAll { now-it.born>=lifetime(it.kind) }
        if (sample!=view.sampleId) {
            sample=view.sampleId
            val missiles=view.projectiles.filter { it.appearance==ProjectileAppearance.NUCLEAR && it.areaRadius==null }
            emitters.keys.retainAll(missiles.map { it.token }.toSet())
            for (shot in missiles) if (emitters[shot.token]?.let { now-it>=3.0 } != false) {
                particles+=Particle(NuclearEffectKind.TRAIL,shot.position,shot.height,now)
                emitters[shot.token]=now
            }
            for (event in view.projectileEvents) if (event.appearance==ProjectileAppearance.NUCLEAR) {
                val kind=when(event.kind) { "born"->NuclearEffectKind.LAUNCH;"impact"->NuclearEffectKind.EXPLOSION;else->null }
                kind?.let { particles+=Particle(it,event.position,event.height,now) }
            }
        }
        return Collections.unmodifiableList(particles.map {
            val age=now-it.born;val progress=age/lifetime(it.kind)
            NuclearEffectDrawing(it.kind,it.center,it.height,when(it.kind) {
                NuclearEffectKind.TRAIL->(2+4*progress).toFloat()
                NuclearEffectKind.LAUNCH->(8+24*progress).toFloat()
                NuclearEffectKind.EXPLOSION->(18+120*progress).toFloat()
            },(1-progress).toFloat())
        })
    }
}
