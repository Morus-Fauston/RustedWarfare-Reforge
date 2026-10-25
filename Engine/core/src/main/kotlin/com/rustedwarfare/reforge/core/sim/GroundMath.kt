package com.rustedwarfare.reforge.core.sim

/** The finite artillery Float lookup operations; ordinary geometry never calls these tables. */
internal object GroundMath {
    data class Point(val x: Float, val y: Float)
    private val atan = Array(8) { FloatArray(1025) }
    private val sine = FloatArray(8192)
    private val cosine = FloatArray(8192)

    init {
        val halfPi = Float.fromBits(0x3fc90fdb)
        val pi = Float.fromBits(0x40490fdb)
        for (i in 0..1024) {
            val ratio = i.toFloat() / 1024f
            val a = (StrictMath.atan(ratio.toDouble()) * Double.fromBits(0x400921fb60000000L) / Math.PI).toFloat()
            atan[0][i] = a
            atan[1][i] = halfPi - a
            atan[2][i] = -a
            atan[3][i] = a - halfPi
            atan[4][i] = pi - a
            atan[5][i] = a + halfPi
            atan[6][i] = a - pi
            atan[7][i] = -halfPi - a
        }
        for (i in 0..8191) {
            val angle = ((i.toFloat() + .5f) / 8192f) * Float.fromBits(0x40c90fdb)
            sine[i] = StrictMath.sin(angle.toDouble()).toFloat()
            cosine[i] = StrictMath.cos(angle.toDouble()).toFloat()
        }
    }

    private fun cmpl(x: Float, y: Float): Int = if (x.isNaN() || y.isNaN()) -1 else if (x > y) 1 else if (x == y) 0 else -1
    private fun cmpg(x: Float, y: Float): Int = if (x.isNaN() || y.isNaN()) 1 else if (x > y) 1 else if (x == y) 0 else -1
    private fun index(ratio: Float) = (ratio.toDouble() + .5).toInt()
    fun sin(angle: Float): Float = sine[(Float.fromBits(0x41b60b61) * angle).toInt() and 8191]
    fun cos(angle: Float): Float = cosine[(Float.fromBits(0x41b60b61) * angle).toInt() and 8191]
    fun square(x: Float, y: Float, u: Float, v: Float): Float {
        val dx = x - u
        val dy = y - v
        return dx * dx + dy * dy
    }
    fun sqrt(value: Float): Float = StrictMath.sqrt(value.toDouble()).toFloat()
    fun angle(x: Float, y: Float, u: Float, v: Float): Float = radians(v - y, u - x) * Float.fromBits(0x42652ee1)

    private fun radians(y: Float, x: Float): Float = try {
        if (cmpl(x, 0f) >= 0) {
            if (cmpl(y, 0f) >= 0) {
                if (cmpl(x, y) >= 0) atan[0][index(1024f * y / x)] else atan[1][index(1024f * x / y)]
            } else if (cmpl(x, -y) >= 0) atan[2][index(-1024f * y / x)] else atan[3][index(-1024f * x / y)]
        } else if (cmpl(y, 0f) >= 0) {
            if (cmpl(-x, y) >= 0) atan[4][index(-1024f * y / x)] else atan[5][index(-1024f * x / y)]
        } else if (cmpg(x, y) <= 0) atan[6][index(1024f * y / x)] else atan[7][index(1024f * x / y)]
    } catch (_: ArrayIndexOutOfBoundsException) {
        StrictMath.atan2(y.toDouble(), x.toDouble()).toFloat()
    }

    fun predict(originX: Float, originY: Float, target: MutableUnit, speed: Float, life: Float, range: Float): Point {
        val x = target.position.x.toFloat()
        val y = target.position.y.toFloat()
        val velocity = target.speed.toFloat()
        val heading = target.heading.toFloat() - 90f
        fun position(time: Float): Point {
            if (velocity == 0f) return Point(x, y)
            val travel = velocity * time
            return Point(x + cos(heading) * travel, sin(heading) * travel + y)
        }
        var time = 0f
        if (speed.toDouble() > .1) {
            val inverse = 1f / speed
            repeat(3) {
                val p = position(time)
                time = sqrt(square(originX, originY, p.x, p.y)) * inverse
            }
        }
        var result = position(if (cmpl(time, life) > 0) life else time)
        if (cmpl(range, 0f) >= 0 && cmpg(range * range, square(originX, originY, result.x, result.y)) < 0) {
            val angle = angle(originX, originY, result.x, result.y)
            result = Point(cos(angle) * range + originX, sin(angle) * range + originY)
        }
        return result
    }
}
