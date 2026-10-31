package com.hollow.app.isolation.service

import com.hollow.app.isolation.model.VpnProxyServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Creates the Xray-core config from the imported share link; no sample endpoint is supplied. */
internal object XrayConfigBuilder {
    fun build(server: VpnProxyServer): String {
        require(server.protocol.name == "VLESS") { "Only VLESS nodes are supported by this tunnel engine." }
        val shareUri = server.shareUri.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Import a VLESS share link before connecting.")
        val uri = URI(shareUri)
        val host = uri.host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("The VLESS link has no valid server host.")
        val port = uri.port.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("The VLESS link has no valid server port.")
        val uuid = uri.rawUserInfo?.substringBefore(':')?.let(::decode)
            ?: throw IllegalArgumentException("The VLESS link has no UUID.")
        UUID.fromString(uuid)

        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator < 1) null else decode(entry.substring(0, separator)) to decode(entry.substring(separator + 1))
        }.toMap()
        val method = when (query["type"]?.lowercase()) {
            null, "", "tcp", "raw" -> "raw"
            else -> throw IllegalArgumentException("This build supports VLESS TCP/RAW only; this link uses another transport.")
        }
        val security = query["security"]?.lowercase()
            ?: throw IllegalArgumentException("The VLESS link must specify TLS or Reality security.")
        require(security == "reality" || security == "tls") {
            "This build supports VLESS with Reality or TLS security only."
        }
        val flow = query["flow"].orEmpty()
        require(flow.isEmpty() || flow == "xtls-rprx-vision") {
            "The VLESS flow in this link is not supported."
        }

        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", query["encryption"]?.ifBlank { "none" } ?: "none")
        if (flow.isNotBlank()) user.put("flow", flow)
        val vnext = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("users", JSONArray().put(user))
        val outbound = JSONObject()
            .put("tag", "hollow-vless")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))

        val streamSettings = JSONObject()
            .put("method", method)
            .put("security", security)
        if (security == "reality") {
            val publicKey = query["pbk"] ?: query["password"]
            require(!publicKey.isNullOrBlank()) { "The Reality public key is missing from this link." }
            val serverName = query["sni"]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("The Reality server name is missing from this link.")
            val shortId = query["sid"].orEmpty()
            require(shortId.length % 2 == 0 && shortId.length <= 16 && shortId.all { it in "0123456789abcdefABCDEF" }) {
                "The Reality short ID must contain an even number of hexadecimal characters, up to 16."
            }
            val reality = JSONObject()
                .put("show", false)
                .put("fingerprint", query["fp"]?.ifBlank { "chrome" } ?: "chrome")
                .put("serverName", serverName)
                .put("password", publicKey)
                .put("shortId", shortId)
                .put("spiderX", query["spx"]?.ifBlank { "/" } ?: "/")
            streamSettings.put("realitySettings", reality)
        } else {
            val tls = JSONObject()
                .put("serverName", query["sni"]?.ifBlank { host } ?: host)
                .put("allowInsecure", false)
            query["fp"]?.takeIf(String::isNotBlank)?.let { tls.put("fingerprint", it) }
            streamSettings.put("tlsSettings", tls)
        }
        outbound.put("streamSettings", streamSettings)

        val tunInbound = JSONObject()
            .put("tag", "hollow-tun")
            .put("protocol", "tun")
            // An explicit name is required on Android. Without it Xray calls GetAvailableTunName(), which
            // lists system interfaces via netlink; Android blocks that for apps, so config parsing fails with
            // "fail to get system interface information". The real TUN fd comes from VpnService.
            .put("settings", JSONObject().put("name", "xray0").put("mtu", 1500))
        val directOutbound = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")
        val route = JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray().put("hollow-tun"))
            .put("outboundTag", "hollow-vless")

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .put("inbounds", JSONArray().put(tunInbound))
            .put("outbounds", JSONArray().put(outbound).put(directOutbound))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(route)))
            .toString()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}
