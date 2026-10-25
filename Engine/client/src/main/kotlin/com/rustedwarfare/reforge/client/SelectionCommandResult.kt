package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import java.util.Collections

data class SelectionMemberResult(val unitId: String, val actionId: String, val result: ActionResult)

class SelectionCommandResult(members: List<SelectionMemberResult>, skippedIds: List<String>) {
    val members: List<SelectionMemberResult> = Collections.unmodifiableList(ArrayList(members))
    val skippedIds: List<String> = Collections.unmodifiableList(ArrayList(skippedIds))
    val accepted: Int get() = members.count { it.result is ActionResult.Accepted }
    val rejected: Int get() = members.size - accepted
}
