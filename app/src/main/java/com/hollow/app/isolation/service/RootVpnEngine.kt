package com.hollow.app.isolation.service

import java.util.concurrent.TimeUnit

/**
 * Thin bridge to NetProxy-Magisk. H1 does not bundle a second Xray binary or TProxy rule engine;
 * when the module is installed, its own root service owns Xray, routing, DNS, IPv6, and cleanup.
 */
object RootVpnEngine {
    private const val MODULE_DIR = "/data/adb/modules/netproxy"
    private const val CLI = "$MODULE_DIR/scripts/cli"
    private const val XRAY = "$MODULE_DIR/bin/xray"
    private const val TIMEOUT_SECONDS = 20L

    data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    fun isAvailable(): Boolean = runRoot(
        "test -x '$CLI' && test -x '$XRAY' && echo available"
    ).let { it.exitCode == 0 && it.output.lineSequence().any { line -> line.trim() == "available" } }

    fun isRunning(): Boolean = runRoot(
        "test -x '$XRAY' && (pidof '$XRAY' >/dev/null 2>&1 || pgrep -f '^$XRAY( |$)' >/dev/null 2>&1)"
    ).exitCode == 0

    fun start(): CommandResult = runRoot("sh '$CLI' start")

    fun stop(): CommandResult = runRoot("sh '$CLI' stop")

    fun status(): CommandResult = runRoot("sh '$CLI' status")

    private fun runRoot(command: String): CommandResult {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (failure: Exception) {
            return CommandResult(127, "Root execution unavailable: ${failure.message.orEmpty()}", false)
        }

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
        var exitCode: Int? = null
        while (exitCode == null && System.currentTimeMillis() < deadline) {
            exitCode = runCatching { process.exitValue() }.getOrNull()
            if (exitCode == null) Thread.sleep(50)
        }
        val timedOut = exitCode == null
        if (timedOut) process.destroyForcibly()
        reader.join(1000)
        return CommandResult(exitCode ?: 124, output.toString().trim(), timedOut)
    }
}
