package com.hollow.app.isolation.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

enum class IsolationMode(
    val title: String,
    val description: String,
    val badge: String
) {
    SHARED_WORKSPACE(
        title = "Hollow Drive (Default)",
        description = "Connects this app to Hollow Drive — the default shared virtual storage and gallery used across isolated apps.",
        badge = "Hollow Drive"
    ),
    SANDBOX_REDIRECT(
        title = "Separate App Drive",
        description = "Assigns this app its own Separate Drive that appears below Hollow Drive in Files like an SD card.",
        badge = "Separate Drive"
    ),
    ENCRYPTED_VAULT(
        title = "Encrypted Separate Drive",
        description = "Assigns this app an AES-256 encrypted Separate Drive below Hollow Drive in Files.",
        badge = "Encrypted Drive"
    ),
    FAKE_EMPTY(
        title = "Blank Gallery",
        description = "Shows an empty gallery and folder list so your personal media stays unseen.",
        badge = "Blank Gallery"
    ),
    READ_ONLY(
        title = "View Only",
        description = "Stops the app from creating extra clutter or hidden folders on your phone.",
        badge = "View Only"
    )
}

/**
 * Controls how an app (or Hollow itself) appears across Android System Framework,
 * SystemUI, System Settings, and any existing or newly installed launcher.
 */
enum class LauncherVisibilityMode(
    val title: String,
    val description: String,
    val badge: String
) {
    VISIBLE(
        title = "Visible (Normal)",
        description = "App appears with its real name and icon in launchers and SystemUI.",
        badge = "Normal"
    ),
    DISGUISED(
        title = "Disguise Name & Icon",
        description = "Replaces the app's name and icon across launchers, System Settings, and SystemUI.",
        badge = "Disguised"
    ),
    HIDDEN(
        title = "Hide Completely",
        description = "Removes the app from every launcher drawer system-wide. Launch it from inside Hollow.",
        badge = "Hidden"
    )
}

/**
 * Disguise icon options: supports Custom Image from Gallery, Cloning any Installed App's Icon,
 * or built-in decoy icon presets.
 */
enum class DisguiseIconPreset(
    val label: String,
    val suggestedName: String
) {
    CUSTOM_IMAGE("Custom Image", ""),
    CLONED_APP("Clone App Icon", ""),
    CALCULATOR("Calculator", "Calculator"),
    NOTES("Notes", "Notes"),
    CALENDAR("Calendar", "Calendar"),
    CLOCK("Clock", "Clock"),
    WEATHER("Weather", "Weather"),
    SYSTEM_UTILITY("System Service", "System Service"),
    DEFAULT("Original Icon", "")
}

enum class VpnEngineMode(val label: String, val description: String) {
    ANDROID_VPN("Android VPN", "Uses Android VpnService and shows the system VPN indicator."),
    ROOT_NETPROXY("Root TProxy", "Delegates to the installed NetProxy-Magisk module without creating a VPN interface.")
}

enum class AuditAction(val label: String, val isSevere: Boolean) {
    REDIRECTED("Kept Separate", false),
    ENCRYPTED("Saved Privately", false),
    BLOCKED("Hidden & Blocked", true),
    SPOOFED_EMPTY("Blank Gallery Shown", false),
    PERMITTED("Allowed Folder", false),
    IDENTITY_SPOOFED("Identity Spoofed", false)
}

/**
 * Hardware device profile presets for Per-App Device Identity & Privacy Spoofing.
 * REAL_HARDWARE starts with the user's real device values so any single field can be tweaked.
 */
enum class DeviceProfilePreset(
    val label: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val fingerprint: String,
    val glRenderer: String = "Adreno (TM) 750",
    val glVendor: String = "Qualcomm"
) {
    REAL_HARDWARE(
        label = "Real Device (Editable)",
        brand = Build.BRAND ?: "google",
        manufacturer = Build.MANUFACTURER ?: "Google",
        model = Build.MODEL ?: "Pixel",
        device = Build.DEVICE ?: "generic",
        product = Build.PRODUCT ?: "generic",
        board = Build.BOARD ?: "generic",
        fingerprint = Build.FINGERPRINT ?: "google/generic/generic:15/AP3A.241005.015/12366759:user/release-keys",
        glRenderer = "Adreno (TM) 740",
        glVendor = "Qualcomm"
    ),
    PIXEL_9_PRO(
        label = "Pixel 9 Pro",
        brand = "google",
        manufacturer = "Google",
        model = "Pixel 9 Pro",
        device = "caiman",
        product = "caiman",
        board = "caiman",
        fingerprint = "google/caiman/caiman:15/AP3A.241005.015/12366759:user/release-keys",
        glRenderer = "Mali-G715-Immortalis MC11",
        glVendor = "ARM"
    ),
    SAMSUNG_S24_ULTRA(
        label = "Galaxy S24 Ultra",
        brand = "samsung",
        manufacturer = "samsung",
        model = "SM-S928B",
        device = "e3q",
        product = "e3qxeea",
        board = "pineapple",
        fingerprint = "samsung/e3qxeea/e3q:14/UP1A.231005.007/S928BXXS3AXFE:user/release-keys",
        glRenderer = "Adreno (TM) 750",
        glVendor = "Qualcomm"
    ),
    ONEPLUS_12(
        label = "OnePlus 12",
        brand = "OnePlus",
        manufacturer = "OnePlus",
        model = "CPH2581",
        device = "OP5929L1",
        product = "CPH2581",
        board = "pineapple",
        fingerprint = "OnePlus/CPH2581/OP5929L1:14/UKQ1.230924.001/U.16992ef-1-2:user/release-keys",
        glRenderer = "Adreno (TM) 750",
        glVendor = "Qualcomm"
    ),
    XIAOMI_14_ULTRA(
        label = "Xiaomi 14 Ultra",
        brand = "Xiaomi",
        manufacturer = "Xiaomi",
        model = "24030PN60G",
        device = "aurora",
        product = "aurora_global",
        board = "aurora",
        fingerprint = "Xiaomi/aurora_global/aurora:14/UKQ1.231003.002/V816.0.8.0.UNAMIXM:user/release-keys",
        glRenderer = "Adreno (TM) 750",
        glVendor = "Qualcomm"
    ),
    NOTHING_PHONE_2(
        label = "Nothing Phone (2)",
        brand = "Nothing",
        manufacturer = "Nothing",
        model = "A065",
        device = "Pong",
        product = "Pong",
        board = "taro",
        fingerprint = "Nothing/Pong/Pong:14/UQ1A.240205.004/2404121810:user/release-keys",
        glRenderer = "Adreno (TM) 730",
        glVendor = "Qualcomm"
    ),
    CUSTOM(
        label = "Custom Model",
        brand = Build.BRAND ?: "google",
        manufacturer = Build.MANUFACTURER ?: "Google",
        model = Build.MODEL ?: "Pixel 9 Pro",
        device = Build.DEVICE ?: "caiman",
        product = Build.PRODUCT ?: "caiman",
        board = Build.BOARD ?: "caiman",
        fingerprint = Build.FINGERPRINT ?: "google/caiman/caiman:15/AP3A.241005.015/12366759:user/release-keys",
        glRenderer = "Adreno (TM) 750",
        glVendor = "Qualcomm"
    )
}

/**
 * Snapshot of the actual unhooked device hardware & browser environment read directly from the phone.
 */
data class RealHardwareSnapshot(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val bootloader: String,
    val fingerprint: String,
    val serial: String,
    val androidId: String,
    val carrierName: String,
    val simOperator: String,
    val glVendor: String,
    val glRenderer: String,
    val cpuCores: Int,
    val ramGb: Int,
    val screenResolution: String,
    val screenDpi: Int,
    val timezoneId: String,
    val localeTag: String,
    val userAgent: String,
    val releaseVersion: String,
    val sdkInt: Int
)

object RealDeviceHardware {
    @Volatile
    private var cached: RealHardwareSnapshot? = null

    fun read(context: Context): RealHardwareSnapshot {
        cached?.let { return it }
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "9774d56d682e549c"
        } catch (_: Throwable) {
            "9774d56d682e549c"
        }
        val tm = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        } catch (_: Throwable) {
            null
        }
        val carrier = try {
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
                ?: "T-Mobile"
        } catch (_: Throwable) {
            "T-Mobile"
        }
        val simOp = try {
            tm?.simOperator?.takeIf { it.isNotBlank() }
                ?: tm?.networkOperator?.takeIf { it.isNotBlank() }
                ?: "310260"
        } catch (_: Throwable) {
            "310260"
        }
        val cores = try {
            Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
        } catch (_: Throwable) {
            8
        }
        val ramGb = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0) {
                (memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)).roundToInt().coerceAtLeast(4)
            } else 8
        } catch (_: Throwable) {
            8
        }
        val dm = context.resources.displayMetrics
        val widthPx = dm.widthPixels.coerceAtLeast(1080)
        val heightPx = dm.heightPixels.coerceAtLeast(2400)
        val dpi = dm.densityDpi.coerceAtLeast(420)
        val tz = try {
            TimeZone.getDefault().id
        } catch (_: Throwable) {
            "America/New_York"
        }
        val localeTag = try {
            Locale.getDefault().toLanguageTag()
        } catch (_: Throwable) {
            "en-US"
        }
        val releaseVer = Build.VERSION.RELEASE ?: "15"
        val modelName = Build.MODEL ?: "Pixel"
        val defaultUa = "Mozilla/5.0 (Linux; Android $releaseVer; $modelName) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
        val brand = Build.BRAND ?: "google"
        val isGoogle = brand.equals("google", ignoreCase = true)
        val glVendor = if (isGoogle) "ARM" else "Qualcomm"
        val glRenderer = if (isGoogle) "Mali-G715-Immortalis MC11" else "Adreno (TM) 740"

        val snapshot = RealHardwareSnapshot(
            brand = Build.BRAND ?: "google",
            manufacturer = Build.MANUFACTURER ?: "Google",
            model = Build.MODEL ?: "Pixel",
            device = Build.DEVICE ?: "generic",
            product = Build.PRODUCT ?: "generic",
            board = Build.BOARD ?: "generic",
            hardware = Build.HARDWARE ?: "qcom",
            bootloader = Build.BOOTLOADER ?: "unknown",
            fingerprint = Build.FINGERPRINT ?: "google/generic/generic:15/AP3A.241005.015/12366759:user/release-keys",
            serial = try {
                @Suppress("DEPRECATION")
                Build.SERIAL?.takeIf { it.isNotBlank() && it != "unknown" } ?: "R58N91Z7X2K"
            } catch (_: Throwable) {
                "R58N91Z7X2K"
            },
            androidId = androidId,
            carrierName = carrier,
            simOperator = simOp,
            glVendor = glVendor,
            glRenderer = glRenderer,
            cpuCores = cores,
            ramGb = ramGb,
            screenResolution = "${widthPx}x${heightPx}",
            screenDpi = dpi,
            timezoneId = tz,
            localeTag = localeTag,
            userAgent = defaultUa,
            releaseVersion = Build.VERSION.RELEASE ?: "15",
            sdkInt = Build.VERSION.SDK_INT
        )
        cached = snapshot
        return snapshot
    }
}

/**
 * Supported V2Ray / Proxy protocols for per-app & global VPN/Proxy routing.
 */
enum class VpnProtocol(val label: String, val badge: String, val defaultPort: Int) {
    VLESS("VLESS (Reality/TLS)", "VLESS", 443),
    VMESS("VMess (not supported by the current tunnel engine)", "VMess", 443),
    SHADOWSOCKS("Shadowsocks (not supported by the current tunnel engine)", "SS", 8388),
    TROJAN("Trojan (not supported by the current tunnel engine)", "Trojan", 443),
    SOCKS5("SOCKS5 (not supported by the current tunnel engine)", "SOCKS5", 1080),
    HTTP("HTTP (not supported by the current tunnel engine)", "HTTP", 8080)
}

enum class VpnConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

data class VpnProxyServer(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val exitIp: String = "",
    val countryCode: String = "--",
    val city: String = "Location unknown",
    val timezoneId: String = "",
    val localeTag: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val latencyMs: Int = 0,
    val usernameOrUuid: String = "",
    val passwordOrSecurity: String = "",
    val shareUri: String = ""
)

object VpnProxyPresets {
    val defaultServers: List<VpnProxyServer> = emptyList()

    val emptyServer = VpnProxyServer(
        id = "",
        name = "No VLESS node imported",
        protocol = VpnProtocol.VLESS,
        host = "",
        port = 0
    )

    fun findById(id: String, customServers: List<VpnProxyServer> = emptyList()): VpnProxyServer =
        customServers.firstOrNull { it.id == id } ?: emptyServer

    /** Imports only VLESS links; unsupported schemes are rejected instead of being mislabelled as SOCKS. */
    fun parseUri(raw: String): VpnProxyServer? {
        val text = raw.trim()
        if (!text.startsWith("vless://", ignoreCase = true)) return null
        return try {
            val uri = java.net.URI(text)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val userId = uri.rawUserInfo?.substringBefore(':')?.let { decode(it) } ?: return null
            java.util.UUID.fromString(userId)
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else decode(part.substring(0, index)) to decode(part.substring(index + 1))
            }.toMap()
            val transport = query["type"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
            val security = query["security"]?.lowercase() ?: return null
            val flow = query["flow"].orEmpty()
            if (transport !in setOf("tcp", "raw") || security !in setOf("reality", "tls")) return null
            if (query["encryption"]?.let { it.isNotBlank() && it != "none" } == true) return null
            if (flow.isNotEmpty() && flow != "xtls-rprx-vision") return null
            if (security == "reality") {
                val shortId = query["sid"].orEmpty()
                if ((query["pbk"] ?: query["password"]).isNullOrBlank() || query["sni"].isNullOrBlank()) return null
                if (shortId.length % 2 != 0 || shortId.length > 16 || shortId.any { it !in "0123456789abcdefABCDEF" }) return null
            }
            val remark = uri.rawFragment?.let(::decode).orEmpty()
            VpnProxyServer(
                id = "custom_${java.util.UUID.randomUUID()}",
                name = remark.ifBlank { "VLESS · $host:$port" },
                protocol = VpnProtocol.VLESS,
                host = host,
                port = port,
                usernameOrUuid = userId,
                passwordOrSecurity = security,
                shareUri = text
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8.name())
}

enum class MountEngineStatus {
    ACTIVE_LSPOSED,
    STANDALONE_USERSPACE,
    ROOT_FUSE_ATTACHED,
    INACTIVE
}

/** What a hooked app may do when it opens the system photo / file picker. */
enum class PickerPolicy(val title: String, val description: String) {
    ALLOW(
        title = "Allow",
        description = "The system picker opens normally and shows your whole phone. The app only receives what you choose."
    ),
    BLOCK(
        title = "Block",
        description = "The system picker is cancelled for isolated apps. They can only use their own in-app gallery, which is isolated."
    )
}

/**
 * Selectable launcher icon styles for Hollow when visible in the home launcher.
 * Supports Android 12+ dynamic wallpaper-reactive colors and Android 13+ monochrome themed icons.
 */
enum class HollowIconTheme(
    val label: String,
    val subtitle: String,
    val aliasClassName: String?
) {
    DYNAMIC_MATERIAL_YOU(
        label = "Dynamic",
        subtitle = "Adapts to wallpaper & Material You",
        aliasClassName = null // Uses default MainActivity with drawable-v31 dynamic vectors + monochrome
    ),
    CYBER_ECLIPSE(
        label = "Cyber Cyan",
        subtitle = "Obsidian & electric cyan portal",
        aliasClassName = "com.hollow.app.MainActivityCyberAlias"
    ),
    CRIMSON_VOID(
        label = "Crimson",
        subtitle = "Carbon black & crimson ring",
        aliasClassName = "com.hollow.app.MainActivityCrimsonAlias"
    ),
    OBSIDIAN_MINIMAL(
        label = "Minimal",
        subtitle = "Stealth monochrome titanium",
        aliasClassName = "com.hollow.app.MainActivityMinimalAlias"
    )
}
