package com.jarvis.app.system

/**
 * Ordered teardown of resources that must not depend on each other's proper
 * shutdown. Each named step runs at most once, in the order given, and never
 * throws out: a failing step is logged and the remaining steps still run, so
 * one crashing dispose cannot leave the rest of the natives undisposed.
 * Pure Kotlin so order and one-shot behaviour stay unit-testable without
 * Android or WebRTC-native objects behind the steps.
 */
internal class TeardownSequence(
    private val label: String,
    private val log: (String) -> Unit,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val completed = HashSet<String>()
    private var startedAt = 0L
    private var finished = false

    /**
     * Runs the steps once overall. A repeat call only logs nothing and runs no
     * step, which guards accidental double teardown from a re-entrant caller.
     */
    fun run(vararg steps: Pair<String, () -> Unit>) {
        if (finished) return
        finished = true
        startedAt = nowMillis()
        log("$label teardown begin")
        for ((name, block) in steps) {
            if (!completed.add(name)) continue
            try {
                block()
                log("$label teardown step=$name")
            } catch (error: Throwable) {
                log("$label teardown step=$name failed=${error.javaClass.simpleName}")
            }
        }
        log("$label teardown done ms=${nowMillis() - startedAt}")
    }
}
