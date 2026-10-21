package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.awt.geom.Line2D
import kotlinx.serialization.json.*
import kotlin.math.*

/** Full-frequency swept circle checks. Map-edge tangency is permitted; obstacle contact is not. */
internal object MotionGeometry {
    fun failure(case: JsonObject,trace: MotionTrace): MotionEvaluation? {
        val rectangles=(case["blockedRects"] as? JsonArray)?.map { it.jsonArray }.orEmpty()+
            listOfNotNull(case["blockedRect"] as? JsonArray)
        fun failed(category: String,point: MotionPoint,previous: MotionPoint)=MotionEvaluation(2,category,
            obj("field" to s(category),"role" to s(trace.role),"q" to n(point.q),"sampleIndex" to n(point.index),
                "raw" to point.raw,"previousRaw" to previous.raw,"context" to trace.context(point.index)),
            obj("checkedAtFullFrequency" to b(true)))
        for((index,point) in trace.points.withIndex()) {
            val previous=trace.points[max(0,index-1)]
            val entry=point.intervalStart(previous)
            for((a,z)in listOf(previous to entry,entry to point)) {
                if(z.x<11 || z.x>589 || z.y<11 || z.y>389)return failed("map-boundary",z,a)
                for(rectangle in rectangles) {
                    val r=rectangle.map { it.jsonPrimitive.double }
                    if(squaredDistance(a,z,r)<=121.0)return failed("swept-obstacle",z,a)
                }
            }
        }
        return null
    }
    private fun squaredDistance(a: MotionPoint,z: MotionPoint,r: List<Double>): Double {
        val(l,t,right,b)=r
        fun pointRect(x: Double,y: Double): Double {
            val dx=max(max(l-x,0.0),x-right);val dy=max(max(t-y,0.0),y-b)
            return dx*dx+dy*dy
        }
        val corners=listOf(l to t,right to t,right to b,l to b)
        for(i in corners.indices) {
            val(x,y)=corners[i];val(nx,ny)=corners[(i+1)%4]
            if(Line2D.linesIntersect(a.x,a.y,z.x,z.y,x,y,nx,ny))return 0.0
        }
        return min(min(pointRect(a.x,a.y),pointRect(z.x,z.y)),corners.minOf { (x,y)->
            Line2D.ptSegDistSq(a.x,a.y,z.x,z.y,x,y)
        })
    }
}
