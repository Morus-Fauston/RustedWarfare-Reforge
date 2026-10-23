package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.Position
import java.math.BigInteger

/** Independent exact center geometry; callers separately assess terrain writeback and source qualification. */
internal object MotionCenterGeometry {
    fun centerPath(points:List<Position>,walls:List<IntArray>,width:Int,height:Int,tileWidth:Int,tileHeight:Int,radius:Double,
                   closeOuterEdges:Boolean=false):Boolean {
        if(points.isEmpty() || width<=0 || height<=0 || tileWidth<=0 || tileHeight<=0 || !radius.isFinite() || radius<0)return false
        val worldWidth=width.toDouble()*tileWidth;val worldHeight=height.toDouble()*tileHeight
        if(points.any { !it.x.isFinite() || !it.y.isFinite() || it.x<radius || it.y<radius || it.x>worldWidth-radius || it.y>worldHeight-radius })return false
        if(walls.any { it.size!=2 || it[0] !in 0 until width || it[1] !in 0 until height })return false
        if(walls.isEmpty())return true
        // Partition each segment at rectangle boundary crossings. Exact rational
        // arithmetic interprets the actual binary64 endpoints without introducing
        // an epsilon or rounding a midpoint onto an excluded edge. This differs
        // from the production slab clipping algorithm and is outside advance timing.
        val segments=if(points.size==1)listOf(points.single() to points.single())else points.zipWithNext()
        for((a,b)in segments) {
            val ax=R.of(a.x);val ay=R.of(a.y);val dx=R.of(b.x)-ax;val dy=R.of(b.y)-ay
            for(wall in walls) {
                // H must retain integer cell edges even beyond 2^53. Products of
                // two positive Ints fit Long. Keep the older planning mode unchanged.
                fun edge(index:Long,tile:Int)=if(closeOuterEdges) R.of(BigInteger.valueOf(index*tile.toLong()),BigInteger.ONE)
                    else R.of(index.toDouble()*tile)
                val left=edge(wall[0].toLong(),tileWidth);val right=edge(wall[0].toLong()+1L,tileWidth)
                val top=edge(wall[1].toLong(),tileHeight);val bottom=edge(wall[1].toLong()+1L,tileHeight)
                val cuts=sortedSetOf(R.ZERO,R.ONE)
                if(dx!=R.ZERO)for(edge in listOf(left,right)) { val t=(edge-ax)/dx;if(t>=R.ZERO && t<=R.ONE)cuts+=t }
                if(dy!=R.ZERO)for(edge in listOf(top,bottom)) { val t=(edge-ay)/dy;if(t>=R.ZERO && t<=R.ONE)cuts+=t }
                val samples=cuts.toList()+cuts.zipWithNext().map { (l,h)->(l+h)/R.TWO }
                if(samples.any { t->val x=ax+dx*t;val y=ay+dy*t
                        x>=left && (x<right || closeOuterEdges && wall[0]==width-1 && x==right) &&
                        y>=top && (y<bottom || closeOuterEdges && wall[1]==height-1 && y==bottom) })return false
            }
        }
        return true
    }
    @ConsistentCopyVisibility
    private data class R private constructor(val numerator:BigInteger,val denominator:BigInteger):Comparable<R> {
        override fun compareTo(other:R)=(numerator*other.denominator).compareTo(other.numerator*denominator)
        operator fun plus(other:R)=of(numerator*other.denominator+other.numerator*denominator,denominator*other.denominator)
        operator fun minus(other:R)=of(numerator*other.denominator-other.numerator*denominator,denominator*other.denominator)
        operator fun times(other:R)=of(numerator*other.numerator,denominator*other.denominator)
        operator fun div(other:R)=of(numerator*other.denominator,denominator*other.numerator)
        companion object {
            val ZERO=R(BigInteger.ZERO,BigInteger.ONE);val ONE=R(BigInteger.ONE,BigInteger.ONE);val TWO=R(BigInteger.TWO,BigInteger.ONE)
            fun of(n:BigInteger,d:BigInteger):R {
                require(d.signum()!=0);if(n.signum()==0)return ZERO
                val sign=if(d.signum()<0)BigInteger.valueOf(-1)else BigInteger.ONE;val gcd=n.gcd(d)
                return R(n/gcd*sign,d/gcd*sign)
            }
            fun of(value:Double):R {
                require(value.isFinite());val bits=value.toBits();val exponent=((bits ushr 52)and 2047).toInt()
                var n=BigInteger.valueOf((bits and 0x000fffffffffffffL)or if(exponent==0)0L else(1L shl 52))
                if(bits<0)n=n.negate();val shift=if(exponent==0)-1074 else exponent-1075
                return if(shift>=0)of(n.shiftLeft(shift),BigInteger.ONE)else of(n,BigInteger.ONE.shiftLeft(-shift))
            }
        }
    }
}
