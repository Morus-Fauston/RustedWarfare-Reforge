package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.testing.MotionCenterGeometry
import kotlin.math.abs
import kotlin.math.floor

/** Consumes paired typed calls and explicit truth only; never grants source admission. */
internal object MotionV2CoreTerrain {
    data class Cell(val x: Int, val y: Int)
    data class Grid(val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int, val walls: Set<Cell>)
    enum class Branch { NOT_INVOKED, DIRECT, EDGE_CORRECTION, SUPPRESS_BOTH_SIDES, SUPPRESS_NO_EDGE, MULTI_CLEAR, MULTI_BLOCKED }
    data class Result(val expectedResolved: Position?, val branch: Branch?, val issues: List<MotionV2CoreAssessment.Issue>)

    private fun same(a: Position?, b: Position?) = if(a==null || b==null) a==b else
        a.x.toBits()==b.x.toBits() && a.y.toBits()==b.y.toBits()
    private fun issue(domain: String, status: MotionV2Findings.Status, code: String) =
        MotionV2CoreAssessment.Issue(domain,status,code,null)
    private fun missing(code: String) = Result(null,null,listOf(issue("coverage",MotionV2Findings.Status.INSUFFICIENT,code)))
    private fun corrupt(code: String) = Result(null,null,listOf(issue("source",MotionV2Findings.Status.EXECUTION_FAILED,code)))
    private fun computed(sample: MotionStepSample, expected: Position?, branch: Branch) = Result(expected,branch,
        if(same(expected,sample.terrainResolved)) emptyList() else
            listOf(issue("behavior",MotionV2Findings.Status.FAIL,"TERRAIN_RESOLVED")))

    fun assess(sample: MotionStepSample, grid: Grid?, radius: Double?): Result {
        if(listOfNotNull(sample.positionBefore,sample.candidate,sample.terrainResolved).any { !it.x.isFinite() || !it.y.isFinite() })
            return corrupt("TERRAIN_NONFINITE")
        if(grid!=null && (grid.width<=0 || grid.height<=0 || grid.tileWidth<=0 || grid.tileHeight<=0 ||
                grid.walls.any { it.x !in 0 until grid.width || it.y !in 0 until grid.height })) return corrupt("TERRAIN_GRID")
        if(radius!=null && (!radius.isFinite() || radius<0)) return corrupt("TERRAIN_RADIUS")
        if(grid==null || radius==null) return missing("TERRAIN_CONTEXT_MISSING")
        val worldWidth=grid.width.toDouble()*grid.tileWidth
        val worldHeight=grid.height.toDouble()*grid.tileHeight
        val start=sample.positionBefore; val candidate=sample.candidate
        if(candidate.x<radius || candidate.y<radius || candidate.x>worldWidth-radius || candidate.y>worldHeight-radius)
            return computed(sample,null,Branch.NOT_INVOKED)
        if(start.x<0 || start.y<0 || start.x>worldWidth || start.y>worldHeight) return missing("TERRAIN_START_OUTSIDE_DOMAIN")
        fun cell(coordinate: Double, tile: Int, count: Int) =
            if(coordinate==count.toDouble()*tile) count-1 else floor(coordinate/tile).toInt()
        val old=Cell(cell(start.x,grid.tileWidth,grid.width),cell(start.y,grid.tileHeight,grid.height))
        val next=Cell(cell(candidate.x,grid.tileWidth,grid.width),cell(candidate.y,grid.tileHeight,grid.height))
        if(abs(next.x.toLong()-old.x)>1L || abs(next.y.toLong()-old.y)>1L) {
            // H uses exact boundary cuts and interval samples over all truth walls,
            // not the production slab or its bounding-box traversal. C stays below.
            val clear=MotionCenterGeometry.centerPath(listOf(start,candidate),grid.walls.map { intArrayOf(it.x,it.y) },
                grid.width,grid.height,grid.tileWidth,grid.tileHeight,0.0,closeOuterEdges=true)
            return computed(sample,if(clear) candidate else start,if(clear) Branch.MULTI_CLEAR else Branch.MULTI_BLOCKED)
        }
        fun blocked(c: Cell) = c.x !in 0 until grid.width || c.y !in 0 until grid.height || c in grid.walls
        if(old==next || !blocked(next)) return computed(sample,candidate,Branch.DIRECT)
        val a=Position(start.x/grid.tileWidth,start.y/grid.tileHeight)
        val b=Position(candidate.x/grid.tileWidth,candidate.y/grid.tileHeight)
        val candidates=mutableListOf<Cell>()
        if(old.x!=next.x && old.y!=next.y) {
            val sideA=Cell(old.x,next.y); val sideB=Cell(next.x,old.y)
            if(blocked(sideA) && blocked(sideB)) return computed(sample,start,Branch.SUPPRESS_BOTH_SIDES)
            if(blocked(sideA)) candidates+=sideA
            if(blocked(sideB)) candidates+=sideB
        }
        candidates+=next
        val selected=candidates.firstNotNullOfOrNull { c -> correction(a,b,c,::blocked) }
        return if(selected==null) computed(sample,start,Branch.SUPPRESS_NO_EDGE) else
            computed(sample,Position(selected.x*grid.tileWidth,selected.y*grid.tileHeight),Branch.EDGE_CORRECTION)
    }

    private data class Edge(val from: Position, val to: Position, val neighbor: Cell, val corrected: Position)
    private fun correction(a: Position, b: Position, cell: Cell, blocked: (Cell)->Boolean): Position? {
        val l=cell.x.toDouble(); val t=cell.y.toDouble(); val r=l+1.0; val h=t+1.0
        // First acceptable X edge has priority over Y. Unlike the production
        // last-write program, this is an ordered edge table with one selection.
        val x=if(a.x<b.x) Edge(Position(l,t),Position(l,h),Cell(cell.x-1,cell.y),Position(l-.01,b.y)) else
            Edge(Position(r,t),Position(r,h),Cell(cell.x+1,cell.y),Position(r+.01,b.y))
        val y=if(a.y<b.y) Edge(Position(l,t),Position(r,t),Cell(cell.x,cell.y-1),Position(b.x,t-.01)) else
            Edge(Position(l,h),Position(r,h),Cell(cell.x,cell.y+1),Position(b.x,h+.01))
        return listOf(x,y).firstOrNull { !blocked(it.neighbor) && intersects(a,b,it.from,it.to) }?.corrected
    }
    private fun intersects(a: Position, b: Position, c: Position, d: Position): Boolean {
        // Preserve the specified binary64 program order, rather than replacing
        // it with exact geometry or cross multiplication at this C seam.
        val denominator=(d.y-c.y)*(b.x-a.x)-(d.x-c.x)*(b.y-a.y)
        if(denominator==0.0) return false
        val u=((d.x-c.x)*(a.y-c.y)-(d.y-c.y)*(a.x-c.x))/denominator
        val v=((b.x-a.x)*(a.y-c.y)-(b.y-a.y)*(a.x-c.x))/denominator
        return u>=0.0 && u<=1.0 && v>=0.0 && v<=1.0
    }
}
