package com.hollow.app.isolation.service

import android.content.Context
import android.os.Build
import com.hollow.app.isolation.model.VpnProxyServer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * H1-owned root transparent proxy engine.
 *
 * Android VPN mode continues to use HollowVpnService and the existing libv2ray AAR. Root mode
 * downloads one pinned Android Xray executable on first use, runs it independently through su,
 * and owns all TPROXY/REDIRECT and policy-routing rules.
 */
object RootVpnEngine {
    private const val ROOT_DIR = "/data/local/tmp/hollow-root-vpn"
    private const val ROOT_BINARY = "$ROOT_DIR/xray"
    private const val ROOT_CONFIG = "$ROOT_DIR/config.json"
    private const val ROOT_PID = "$ROOT_DIR/xray.pid"
    private const val ROOT_LOG = "$ROOT_DIR/xray.log"
    private const val TCP_PORT = 12345
    private const val UDP_PORT = 12345
    private const val DNS_PORT = 1053
    private const val MARK = 20
    private const val ROUTE_TABLE = 2025
    private const val TIMEOUT_SECONDS = 25L
    private const val XRAY_VERSION = "v26.10.29"

    private data class BinarySpec(val url: String, val sha256: String)

    data class CommandResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    fun isAvailable(): Boolean = runRoot("id -u").let { result ->
        result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "0" }
    }

    fun isRunning(): Boolean = runRoot(
        "test -s '$ROOT_PID' && kill -0 \$(cat '$ROOT_PID') 2>/dev/null"
    ).exitCode == 0

    fun start(context: Context, server: VpnProxyServer): CommandResult {
        if (!isAvailable()) return CommandResult(127, "Root (su) is unavailable", false)
        val binary = try {
            ensureXrayBinary(context)
        } catch (failure: Exception) {
            return CommandResult(1, "Could not prepare H1 Xray: ${failure.message.orEmpty()}", false)
        }

        val config = File(context.filesDir, "hollow-root-xray.json")
        try {
            config.writeText(XrayConfigBuilder.buildRoot(server, TCP_PORT, UDP_PORT, DNS_PORT))
        } catch (failure: Exception) {
            return CommandResult(2, "Invalid root Xray configuration: ${failure.message.orEmpty()}", false)
        }

        val install = runRoot(
            "mkdir -p '$ROOT_DIR' && " +
                "cp '${shellQuotePath(binary)}' '$ROOT_BINARY' && chmod 700 '$ROOT_BINARY' && " +
                "cp '${shellQuotePath(config)}' '$ROOT_CONFIG' && chmod 600 '$ROOT_CONFIG' && " +
                "rm -f '$ROOT_PID' && " +
                "nohup '$ROOT_BINARY' run -c '$ROOT_CONFIG' >'$ROOT_LOG' 2>&1 & echo \$! >'$ROOT_PID' && " +
                "sleep 1 && kill -0 \$(cat '$ROOT_PID') 2>/dev/null"
        )
        if (install.exitCode != 0) return install

        val rules = runRoot(cleanupRules() + "; " + installRules())
        if (rules.exitCode != 0) {
            stop()
            return rules
        }
        return CommandResult(0, "H1 Xray root TPROXY started", false)
    }

    fun stop(): CommandResult = runRoot(
        cleanupRules() + "; " +
            "if test -s '$ROOT_PID'; then kill \$(cat '$ROOT_PID') 2>/dev/null || true; fi; " +
            "rm -rf '$ROOT_DIR'"
    )

    fun status(): CommandResult = runRoot(
        "if test -s '$ROOT_PID' && kill -0 \$(cat '$ROOT_PID') 2>/dev/null; then " +
            "echo 'H1 root TPROXY running'; else echo 'H1 root TPROXY stopped'; exit 1; fi"
    )

    private fun installRules(): String = """
        iptables -t mangle -N HOLLOW_TPROXY 2>/dev/null || true
        iptables -t mangle -F HOLLOW_TPROXY
        iptables -t mangle -A HOLLOW_TPROXY -m owner --uid-owner 0 -j RETURN
        iptables -t mangle -A HOLLOW_TPROXY -d 127.0.0.0/8 -j RETURN
        iptables -t mangle -A HOLLOW_TPROXY -d 10.0.0.0/8 -j RETURN
        iptables -t mangle -A HOLLOW_TPROXY -d 172.16.0.0/12 -j RETURN
        iptables -t mangle -A HOLLOW_TPROXY -d 192.168.0.0/16 -j RETURN
        iptables -t mangle -A HOLLOW_TPROXY -d 100.64.0.0/10 -j RETURN
        iptables -t mangle -A HOLLOW_TPROXY -p tcp -j TPROXY --on-port $TCP_PORT --tproxy-mark $MARK
        iptables -t mangle -A HOLLOW_TPROXY -p udp -j TPROXY --on-port $UDP_PORT --tproxy-mark $MARK
        iptables -t mangle -I PREROUTING 1 -j HOLLOW_TPROXY
        iptables -t nat -N HOLLOW_OUTPUT 2>/dev/null || true
        iptables -t nat -F HOLLOW_OUTPUT
        iptables -t nat -A HOLLOW_OUTPUT -m owner --uid-owner 0 -j RETURN
        iptables -t nat -A HOLLOW_OUTPUT -d 127.0.0.0/8 -j RETURN
        iptables -t nat -A HOLLOW_OUTPUT -d 10.0.0.0/8 -j RETURN
        iptables -t nat -A HOLLOW_OUTPUT -d 172.16.0.0/12 -j RETURN
        iptables -t nat -A HOLLOW_OUTPUT -d 192.168.0.0/16 -j RETURN
        iptables -t nat -A HOLLOW_OUTPUT -p tcp -j REDIRECT --to-ports $TCP_PORT
        iptables -t nat -A HOLLOW_OUTPUT -p udp -j REDIRECT --to-ports $UDP_PORT
        iptables -t nat -I OUTPUT 1 -j HOLLOW_OUTPUT
        iptables -t nat -N HOLLOW_DNS 2>/dev/null || true
        iptables -t nat -F HOLLOW_DNS
        iptables -t nat -A HOLLOW_DNS -m owner --uid-owner 0 -j RETURN
        iptables -t nat -A HOLLOW_DNS -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT
        iptables -t nat -A HOLLOW_DNS -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT
        iptables -t nat -I OUTPUT 1 -j HOLLOW_DNS
        ip rule add fwmark $MARK table $ROUTE_TABLE 2>/dev/null || true
        ip route add local 0.0.0.0/0 dev lo table $ROUTE_TABLE 2>/dev/null || true
    """.trimIndent()

    private fun cleanupRules(): String = """
        iptables -t mangle -D PREROUTING -j HOLLOW_TPROXY 2>/dev/null || true
        iptables -t mangle -F HOLLOW_TPROXY 2>/dev/null || true
        iptables -t mangle -X HOLLOW_TPROXY 2>/dev/null || true
        iptables -t nat -D OUTPUT -j HOLLOW_DNS 2>/dev/null || true
        iptables -t nat -F HOLLOW_DNS 2>/dev/null || true
        iptables -t nat -X HOLLOW_DNS 2>/dev/null || true
        iptables -t nat -D OUTPUT -j HOLLOW_OUTPUT 2>/dev/null || true
        iptables -t nat -F HOLLOW_OUTPUT 2>/dev/null || true
        iptables -t nat -X HOLLOW_OUTPUT 2>/dev/null || true
        ip rule del fwmark $MARK table $ROUTE_TABLE 2>/dev/null || true
        ip route del local 0.0.0.0/0 dev lo table $ROUTE_TABLE 2>/dev/null || true
    """.trimIndent()

    private fun ensureXrayBinary(context: Context): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: error("H1 root Xray currently supports arm64-v8a and x86_64")
        val cache = File(context.filesDir, "root-xray/$XRAY_VERSION/$abi/xray")
        if (cache.isFile && sha256(cache) == specFor(abi).sha256) return cache
        cache.parentFile?.mkdirs()
        val zipFile = File(cache.parentFile, "xray.zip")
        download(specFor(abi).url, zipFile)
        check(sha256(zipFile) == specFor(abi).sha256) { "Xray download checksum verification failed" }
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "xray") {
                    cache.outputStream().use { output -> zip.copyTo(output) }
                    break
                }
                entry = zip.nextEntry
            }
        }
        check(cache.isFile) { "The Xray release did not contain an executable" }
        cache.setExecutable(true)
        zipFile.delete()
        return cache
    }

    private fun specFor(abi: String): BinarySpec = when (abi) {
        "arm64-v8a" -> BinarySpec(
            "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-android-arm64-v8a.zip",
            "1be0ea7dc4fd967b7dd784d9d8281aab19da3bfc5d78c2da7dcb7d690e25299f"
        )
        else -> BinarySpec(
            "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-android-amd64.zip",
            "7fda59f39c06308c82c3c873590febcfd8acb2db29431eea1459d86408d44618"
        )
    }

    private fun download(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
        connection.disconnect()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun shellQuotePath(file: File): String = file.absolutePath.replace("'", "'\\''")

    private fun runRoot(command: String): CommandResult {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (failure: Exception) {
            return CommandResult(127, "Root execution unavailable: ${failure.message.orEmpty()}", false)
        }
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
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
