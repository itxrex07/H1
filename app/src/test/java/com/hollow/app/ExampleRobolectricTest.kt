package com.hollow.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hollow.app.isolation.data.entity.AppIsolationConfig
import com.hollow.app.isolation.model.AppSettings
import com.hollow.app.isolation.model.DisguiseIconPreset
import com.hollow.app.isolation.model.IsolationRuleManager
import com.hollow.app.isolation.model.LauncherVisibilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Hollow", appName)
    }

    @Test
    fun `stealth table serializes and parses independent hide and disguise rules`() {
        val configs = listOf(
            AppIsolationConfig(
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                isIsolated = false,
                isHiddenOrDisguised = true,
                launcherVisibilityMode = LauncherVisibilityMode.DISGUISED,
                customLauncherLabel = "Daily Notes",
                disguiseIconPreset = DisguiseIconPreset.NOTES,
                hideInSystemUiRecents = true
            ),
            AppIsolationConfig(
                packageName = "com.snapchat.android",
                appName = "Snapchat",
                isIsolated = true,
                isHiddenOrDisguised = true,
                launcherVisibilityMode = LauncherVisibilityMode.HIDDEN
            )
        )
        val settings = AppSettings(
            hollowVisibilityMode = LauncherVisibilityMode.DISGUISED,
            hollowCustomName = "Calc Pro",
            hollowIconPreset = DisguiseIconPreset.CALCULATOR,
            systemUiTriggerEnabled = true
        )

        IsolationRuleManager.publishStealthTable(configs, settings)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = IsolationRuleManager.getReadableSharedPrefs(context)
            .getString(IsolationRuleManager.KEY_STEALTH_TABLE, "")
        val parsed = IsolationRuleManager.parseStealthTable(raw)

        val whatsappRule = parsed["com.whatsapp"]!!
        assertEquals(LauncherVisibilityMode.DISGUISED, whatsappRule.visibilityMode)
        assertEquals("Daily Notes", whatsappRule.customLabel)
        assertEquals(DisguiseIconPreset.NOTES, whatsappRule.iconPreset)
        assertTrue(whatsappRule.hideInRecents)

        val snapchatRule = parsed["com.snapchat.android"]!!
        assertEquals(LauncherVisibilityMode.HIDDEN, snapchatRule.visibilityMode)

        val hollowRule = parsed[IsolationRuleManager.MODULE_PKG]!!
        assertEquals(LauncherVisibilityMode.DISGUISED, hollowRule.visibilityMode)
        assertEquals("Calc Pro", hollowRule.customLabel)
        assertFalse(configs[0].isIsolated)
    }
}
