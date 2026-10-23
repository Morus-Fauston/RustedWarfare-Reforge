package com.rustedwarfare.reforge.testing.cross

import kotlin.math.*

/** Fixed causal numerical sampling over all ordered qualified raw points.
 * Record completeness and nonnumeric gates remain separate obligations. */
internal object MotionV2Sampling {
    data class Point(val q: Double, val phase: Long, val input: Long, val x: Double, val y: Double, val heading: Double)
    data class Selected(val requestedQ: Double, val core: Point?, val original: Point?,
                        val positionError: Double?, val headingError: Double?, val speedError: Double?, val unavailable: List<String>)
    data class Metric(val expectedCount: Int, val value: MotionV2Math.NumericResult?) {
        val status get() = value?.status ?: MotionV2Math.Status.INSUFFICIENT
    }
    data class Result(val points: List<Selected>, val position: Metric, val heading: Metric,
                      val speed: Metric?, val notApplicableWindows: List<Double>)
    private fun validate(points: List<Point>) {
        require(points.isNotEmpty() && points.first().q == 0.0)
        require(points.all { p->listOf(p.q,p.x,p.y,p.heading).all { it.isFinite() } &&
            p.q >= 0 && p.phase >= 0 && p.input >= -1 && p.heading >= 0 && p.heading < 360 })
        require(points.zipWithNext().all { (a,b)->a.q <= b.q && a.phase <= b.phase && a.input <= b.input }) {
            "Reordered raw time, phase or input identity"
        }
    }
    fun select(points: List<Point>, q: Double, maximumAge: Double): Point? {
        validate(points);require(q.isFinite() && maximumAge.isFinite() && maximumAge >= 0)
        val point=points.lastOrNull { it.q <= q } ?: return null
        return point.takeIf { q-it.q <= maximumAge }
    }
    fun evaluate(core: List<Point>, original: List<Point>, originalInputQ: List<Double>, horizon: Double,
                 stationary: Boolean = false): Result {
        validate(core);validate(original);require(horizon.isFinite() && horizon > 0 && horizon <= 600)
        require(originalInputQ.all { it.isFinite() && it >= 0 && it <= horizon })
        require(originalInputQ.zipWithNext().all { (a,b)->a <= b })
        val requested=sortedSetOf(0.0,horizon)
        var q=6.0
        while(q<horizon) { requested+=q;q+=6 }
        for(input in originalInputQ)for(offset in listOf(3.0,6.0,9.0,12.0,18.0))
            if(input+offset<=horizon)requested+=input+offset
        val positions=mutableListOf<Double>();val headings=mutableListOf<Double>();val speeds=mutableListOf<Double>()
        val rows=mutableListOf<Selected>();val notApplicable=mutableListOf<Double>();var expectedSpeed=0
        for(time in requested) {
            val a=select(core,time,1.0);val e=select(original,time,1.2)
            val missing=mutableListOf<String>()
            if(a==null)missing+="core-point-missing-or-stale"
            if(e==null)missing+="original-point-missing-or-stale"
            if(a!=null && e!=null && a.input!=e.input)missing+="input-mismatch"
            var position:Double?=null;var heading:Double?=null;var speed:Double?=null
            if(missing.isEmpty()) {
                check(a!=null && e!=null)
                position=hypot(a.x-e.x,a.y-e.y);positions+=position
                if(!stationary || time==0.0) {
                    heading=abs(((a.heading-e.heading+180.0)%360.0+360.0)%360.0-180.0);headings+=heading
                }
            }
            if(!stationary && time>=6.0) {
                val c0=select(core,time-6.0,1.0);val o0=select(original,time-6.0,1.2)
                if(a==null || e==null || a.input!=e.input || c0==null || o0==null) {
                    expectedSpeed++;missing+="speed-window-point-missing-stale-or-mismatched"
                } else if(a.input!=c0.input || e.input!=o0.input || a.phase!=c0.phase || e.phase!=o0.phase) {
                    notApplicable+=time
                } else {
                    expectedSpeed++
                    if(a.q<=c0.q || e.q<=o0.q)missing+="speed-window-nonpositive-q"
                    else {
                        val cv=hypot(a.x-c0.x,a.y-c0.y)/(a.q-c0.q)
                        val ov=hypot(e.x-o0.x,e.y-o0.y)/(e.q-o0.q)
                        speed=abs(cv-ov);speeds+=speed
                    }
                }
            }
            rows+=Selected(time,a,e,position,heading,speed,missing.toList())
        }
        fun metric(errors: List<Double>,count: Int,rmse: Double,worst: Double)=
            Metric(count,if(count==0)null else MotionV2Math.numeric(errors,count,rmse,worst))
        return Result(rows.toList(),metric(positions,requested.size,2.0,4.0),
            metric(headings,if(stationary)1 else requested.size,5.0,10.0),
            if(stationary)null else metric(speeds,expectedSpeed,.08,.2),notApplicable.toList())
    }
}
