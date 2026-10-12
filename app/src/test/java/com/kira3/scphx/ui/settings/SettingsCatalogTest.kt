package com.kira3.scphx.ui.settings

import com.kira3.scphx.emucore.settings.EmulatorSettings
import com.kira3.scphx.emucore.settings.SettingsValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    private val defaults = SettingsValues(EmulatorSettings())
    private val settings = SettingsCategory.entries.flatMap { c -> c.sections.flatMap { it.settings } }

    @Test
    fun everyPathExistsAndTypesMatch() {
        for (setting in settings) {
            val path = setting.path ?: continue
            val value = defaults[path]
            when (setting) {
                is Toggle -> {
                    assertEquals(path, value.javaClass, setting.on.javaClass)
                    assertEquals(path, value.javaClass, setting.off.javaClass)
                }
                is Choice -> setting.options.forEach { assertEquals(path, value.javaClass, it.value.javaClass) }
                is Slider -> assertTrue(path, value is Int)
                is NumberInput -> assertTrue(path, value is Number)
                else -> Unit
            }
        }
    }

    @Test
    fun defaultsAreAmongTheOptions() {
        for (setting in settings.filterIsInstance<Choice>()) {
            val path = setting.path!!
            assertTrue(path, setting.options.any { it.value == defaults[path] })
        }
    }

    @Test
    fun noPathIsShownTwice() {
        val paths = settings.mapNotNull { it.path }
        assertEquals(paths.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }
}
