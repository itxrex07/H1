package com.hollow.app.isolation.service

import com.hollow.app.isolation.model.VpnProxyPresets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VpnTunnelConfigTest {
    private val validRealityLink =
        "vless://11111111-1111-4111-8111-111111111111@node.example:443" +
            "?security=reality&encryption=none&pbk=publicKeyForTest&fp=chrome" +
            "&type=tcp&flow=xtls-rprx-vision&sni=example.org&sid=deadbeef#test-node"

    @Test
    fun importsVlessRealityTcpWithoutInventingLocationOrLatency() {
        val server = VpnProxyPresets.parseUri(validRealityLink)
        assertNotNull(server)
        assertEquals("node.example", server!!.host)
        assertEquals(443, server.port)
        assertEquals("test-node", server.name)
        assertEquals("", server.exitIp)
        assertEquals("--", server.countryCode)
        assertEquals(0, server.latencyMs)
        assertEquals(validRealityLink, server.shareUri)
    }

    @Test
    fun rejectsUnsupportedProtocolsTransportsSecurityAndMalformedReality() {
        assertNull(VpnProxyPresets.parseUri("vmess://opaque"))
        assertNull(VpnProxyPresets.parseUri(validRealityLink.replace("type=tcp", "type=ws")))
        assertNull(VpnProxyPresets.parseUri(validRealityLink.replace("security=reality", "security=none")))
        assertNull(VpnProxyPresets.parseUri(validRealityLink.replace("sid=deadbeef", "sid=abc")))
        assertNull(VpnProxyPresets.parseUri(validRealityLink.replace("pbk=publicKeyForTest", "pbk=")))
    }

    @Test
    fun generatesVlessRealityOutboundAndTproxyInbound() {
        val server = requireNotNull(VpnProxyPresets.parseUri(validRealityLink))
        val config = JSONObject(XrayConfigBuilder.buildRoot(server, tcpPort = 12345, udpPort = 12346, dnsPort = 1053))

        val inbounds = config.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())

        val tproxy = inbounds.getJSONObject(0)
        assertEquals("hollow-tproxy", tproxy.getString("tag"))
        assertEquals("dokodemo-door", tproxy.getString("protocol"))
        assertEquals(12345, tproxy.getInt("port"))
        assertTrue(tproxy.getJSONObject("settings").getBoolean("followRedirect"))
        assertEquals(
            "tproxy",
            tproxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("tproxy")
        )

        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        val reality = stream.getJSONObject("realitySettings")

        assertEquals("raw", stream.getString("method"))
        assertEquals("reality", stream.getString("security"))
        assertEquals("publicKeyForTest", reality.getString("password"))
        assertEquals("example.org", reality.getString("serverName"))
        assertEquals("deadbeef", reality.getString("shortId"))
        assertEquals(
            "hollow-vless",
            config.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag")
        )
        assertTrue(config.getJSONArray("outbounds").length() >= 2)
    }

    @Test
    fun preservesLiteralPlusInRealityPublicKey() {
        val link = validRealityLink.replace("pbk=publicKeyForTest", "pbk=public+key+for+test")
        val server = requireNotNull(VpnProxyPresets.parseUri(link))
        val config = JSONObject(XrayConfigBuilder.buildRoot(server, 12345, 12346, 1053))
        val reality = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("realitySettings")

        assertEquals("public+key+for+test", reality.getString("password"))
    }

    @Test
    fun rejectsMissingConfigurationInsteadOfBuildingPlaceholderServer() {
        val error = runCatching {
            XrayConfigBuilder.buildRoot(VpnProxyPresets.emptyServer, 12345, 12346, 1053)
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("Import a VLESS share link"))
    }
}
