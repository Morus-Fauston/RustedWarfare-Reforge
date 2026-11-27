package com.rustedwarfare.reforge.client

import java.util.ArrayDeque

/**
 * Out-game route destinations managed by [AppShell] (Phase VIII Spec §2.1 & UI Supplement §2).
 */
enum class OutGameRoute {
    MAIN_MENU,
    SKIRMISH_LOBBY,
    TEST_AND_SANDBOX,
    SETTINGS,
    MAP_PICKER,
    LOADING,
}

/**
 * Outcome of handling an out-game Back / Escape key press (UI Supplement §2).
 */
enum class BackKeyAction {
    NONE,
    CLOSED_MODAL,
    CANCELLED_LOADING,
    POPPED_ROUTE,
    PROMPTED_EXIT_CONFIRM,
    EXIT_APP,
}

/**
 * Lightweight out-game page stack & modal coordinator (UI-P004-001).
 *
 * Responsibilities:
 * - Maintains deterministic route history stack (`MAIN_MENU -> SKIRMISH_LOBBY / TEST_AND_SANDBOX / SETTINGS -> MAP_PICKER`).
 * - Coordinates modal interception (`more-settings`, `ui-scale-confirm`, `exit-confirm`) so system Back / Esc
 *   always dismisses open modals before popping routes.
 * - Preserves caller route across `MAP_PICKER`, `LOADING`, and active matches (`enterMatch` / `returnToLobby`).
 * - Enforces two-step confirmation before exiting the application on `MAIN_MENU` Back key.
 */
class AppShell(
    initialRoute: OutGameRoute = OutGameRoute.MAIN_MENU,
    private val onRouteChanged: (OutGameRoute) -> Unit = {},
    private val onCancelLoading: () -> Unit = {},
    private val onRequestExitApp: () -> Unit = {},
) {
    private data class ModalEntry(
        val id: String,
        val onDismiss: () -> Unit,
    )

    private val routeStack = ArrayList<OutGameRoute>().apply {
        if (initialRoute != OutGameRoute.MAIN_MENU && initialRoute != OutGameRoute.LOADING) {
            add(OutGameRoute.MAIN_MENU)
        }
        add(initialRoute)
    }

    private val modalStack = ArrayDeque<ModalEntry>()

    var inMatch: Boolean = false
        private set

    /** Remembers which lobby/sandbox page launched the current loading or active match. */
    var preMatchRoute: OutGameRoute =
        if (initialRoute == OutGameRoute.TEST_AND_SANDBOX) OutGameRoute.TEST_AND_SANDBOX else OutGameRoute.SKIRMISH_LOBBY
        private set

    val currentRoute: OutGameRoute
        get() = routeStack.last()

    val stack: List<OutGameRoute>
        get() = routeStack.toList()

    val activeModalId: String?
        get() = modalStack.peekLast()?.id

    val hasActiveModal: Boolean
        get() = modalStack.isNotEmpty()

    val exitConfirmVisible: Boolean
        get() = modalStack.any { it.id == MODAL_EXIT_CONFIRM }

    /** Pushes [route] onto the out-game page stack if not already at top. */
    fun push(route: OutGameRoute) {
        dismissExitConfirmSilently()
        if (route == OutGameRoute.SKIRMISH_LOBBY || route == OutGameRoute.TEST_AND_SANDBOX) {
            preMatchRoute = route
        }
        if (routeStack.lastOrNull() != route) {
            routeStack.add(route)
            onRouteChanged(currentRoute)
        }
    }

    /** Replaces the current top route without growing stack depth. */
    fun replace(route: OutGameRoute) {
        dismissExitConfirmSilently()
        if (route == OutGameRoute.SKIRMISH_LOBBY || route == OutGameRoute.TEST_AND_SANDBOX) {
            preMatchRoute = route
        }
        if (routeStack.isEmpty()) {
            routeStack.add(route)
        } else {
            routeStack[routeStack.lastIndex] = route
        }
        onRouteChanged(currentRoute)
    }

    /** Resets stack to a clean root (or `[MAIN_MENU, route]` when [route] is a sub-page). */
    fun resetTo(route: OutGameRoute) {
        modalStack.clear()
        inMatch = false
        routeStack.clear()
        if (route != OutGameRoute.MAIN_MENU && route != OutGameRoute.LOADING) {
            routeStack.add(OutGameRoute.MAIN_MENU)
        }
        routeStack.add(route)
        if (route == OutGameRoute.SKIRMISH_LOBBY || route == OutGameRoute.TEST_AND_SANDBOX) {
            preMatchRoute = route
        }
        onRouteChanged(currentRoute)
    }

    /**
     * Navigates to [route] without duplicating ancestor routes in the stack:
     * - If [route] is already in [routeStack], pops everything above its last occurrence.
     * - Otherwise pushes [route] onto [routeStack].
     */
    fun popToOrPush(route: OutGameRoute) {
        dismissExitConfirmSilently()
        if (route == OutGameRoute.SKIRMISH_LOBBY || route == OutGameRoute.TEST_AND_SANDBOX) {
            preMatchRoute = route
        }
        val existingIdx = routeStack.lastIndexOf(route)
        if (existingIdx >= 0) {
            if (existingIdx < routeStack.lastIndex) {
                while (routeStack.lastIndex > existingIdx) {
                    routeStack.removeAt(routeStack.lastIndex)
                }
                onRouteChanged(currentRoute)
            }
            return
        }
        routeStack.add(route)
        onRouteChanged(currentRoute)
    }

    /** Pops the current sub-page if deeper than root; returns true if popped. */
    fun pop(): Boolean {
        dismissExitConfirmSilently()
        if (routeStack.size <= 1) return false
        routeStack.removeAt(routeStack.lastIndex)
        onRouteChanged(currentRoute)
        return true
    }

    /** Transitions into `LOADING` while recording the launching route for safe cancellation/return. */
    fun beginLoading(sourceRoute: OutGameRoute? = null) {
        modalStack.clear()
        val origin = sourceRoute ?: currentRoute.takeIf { it != OutGameRoute.LOADING } ?: preMatchRoute
        if (origin == OutGameRoute.SKIRMISH_LOBBY || origin == OutGameRoute.TEST_AND_SANDBOX || origin == OutGameRoute.MAIN_MENU) {
            preMatchRoute = if (origin == OutGameRoute.MAIN_MENU) OutGameRoute.SKIRMISH_LOBBY else origin
        }
        if (routeStack.lastOrNull() != OutGameRoute.LOADING) {
            routeStack.add(OutGameRoute.LOADING)
        }
        onRouteChanged(currentRoute)
    }

    /** Cancels loading and restores the launching page (`SKIRMISH_LOBBY` or `TEST_AND_SANDBOX`). */
    fun cancelLoading() {
        modalStack.clear()
        while (routeStack.lastOrNull() == OutGameRoute.LOADING && routeStack.size > 1) {
            routeStack.removeAt(routeStack.lastIndex)
        }
        if (routeStack.lastOrNull() == OutGameRoute.LOADING) {
            routeStack.clear()
            routeStack.add(OutGameRoute.MAIN_MENU)
            routeStack.add(preMatchRoute)
        }
        onRouteChanged(currentRoute)
    }

    /** Marks transition from `LOADING` into active gameplay (`inMatch = true`). */
    fun enterMatch() {
        modalStack.clear()
        while (routeStack.lastOrNull() == OutGameRoute.LOADING && routeStack.size > 1) {
            routeStack.removeAt(routeStack.lastIndex)
        }
        inMatch = true
    }

    /** Exits active match back to [targetRoute] (defaults to [preMatchRoute], keeping `MAIN_MENU` underneath). */
    fun returnToLobby(targetRoute: OutGameRoute? = null) {
        modalStack.clear()
        inMatch = false
        val destination = targetRoute ?: preMatchRoute
        routeStack.clear()
        if (destination != OutGameRoute.MAIN_MENU) {
            routeStack.add(OutGameRoute.MAIN_MENU)
        }
        routeStack.add(destination)
        if (destination == OutGameRoute.SKIRMISH_LOBBY || destination == OutGameRoute.TEST_AND_SANDBOX) {
            preMatchRoute = destination
        }
        onRouteChanged(currentRoute)
    }

    /** Registers an active modal layer (e.g. `more-settings`, `ui-scale-confirm`) at the top of the modal stack. */
    fun openModal(id: String, onDismiss: () -> Unit = {}) {
        require(id.isNotBlank()) { "Modal id must not be blank" }
        modalStack.removeAll { it.id == id }
        modalStack.addLast(ModalEntry(id, onDismiss))
    }

    /** Dismisses the specified modal (or the topmost modal if [id] is null) and invokes its callback. */
    fun closeModal(id: String? = null): Boolean {
        val target = if (id == null) {
            modalStack.pollLast()
        } else {
            val found = modalStack.lastOrNull { it.id == id }
            if (found != null) modalStack.remove(found)
            found
        } ?: return false
        target.onDismiss()
        return true
    }

    /** Confirms main-menu exit after two-step confirmation prompt. */
    fun confirmExit(): BackKeyAction {
        modalStack.removeAll { it.id == MODAL_EXIT_CONFIRM }
        onRequestExitApp()
        return BackKeyAction.EXIT_APP
    }

    /**
     * Handles Android system Back / PC Escape while out-of-game (UI Supplement §2):
     * 1. Active modal (`more-settings`, `ui-scale-confirm`, `exit-confirm`) -> closes modal first.
     * 2. `LOADING` -> cancels loading and returns to launching page.
     * 3. Sub-page (`SKIRMISH_LOBBY`, `TEST_AND_SANDBOX`, `SETTINGS`, `MAP_PICKER`) -> pops back.
     * 4. `MAIN_MENU` -> opens secondary exit confirmation prompt (`PROMPTED_EXIT_CONFIRM`), or on desktop
     *    when [requireMainMenuExitConfirm] is false returns `NONE`.
     */
    fun handleBackKey(requireMainMenuExitConfirm: Boolean = true): BackKeyAction {
        if (inMatch) return BackKeyAction.NONE
        if (modalStack.isNotEmpty()) {
            closeModal()
            return BackKeyAction.CLOSED_MODAL
        }
        if (currentRoute == OutGameRoute.LOADING) {
            cancelLoading()
            onCancelLoading()
            return BackKeyAction.CANCELLED_LOADING
        }
        if (pop()) {
            return BackKeyAction.POPPED_ROUTE
        }
        if (currentRoute == OutGameRoute.MAIN_MENU && requireMainMenuExitConfirm) {
            openModal(MODAL_EXIT_CONFIRM)
            return BackKeyAction.PROMPTED_EXIT_CONFIRM
        }
        return BackKeyAction.NONE
    }

    private fun dismissExitConfirmSilently() {
        modalStack.removeAll { it.id == MODAL_EXIT_CONFIRM }
    }

    companion object {
        const val MODAL_MORE_SETTINGS: String = "more-settings"
        const val MODAL_UI_SCALE_CONFIRM: String = "ui-scale-confirm"
        const val MODAL_EXIT_CONFIRM: String = "exit-confirm"
    }
}
