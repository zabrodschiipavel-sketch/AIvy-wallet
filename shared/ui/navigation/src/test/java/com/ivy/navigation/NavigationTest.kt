package com.ivy.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test
    fun `equal routes have independent entries and back restores the first entry`() {
        val navigation = Navigation()
        navigation.navigateTo(SettingsScreen)
        val first = navigation.currentEntry
        navigation.navigateTo(SettingsScreen)

        assertNotEquals(first?.id, navigation.currentEntry?.id)
        assertEquals(2, navigation.entries.size)
        assertTrue(navigation.back())
        assertSame(first, navigation.currentEntry)
        assertFalse(navigation.back())
    }

    @Test
    fun `reset then navigate replaces the current route and drops history`() {
        val navigation = Navigation()
        navigation.navigateTo(MainScreen)
        navigation.navigateTo(SettingsScreen)
        navigation.resetBackStack()

        assertTrue(navigation.backStackEmpty())
        assertEquals(SettingsScreen, navigation.currentScreen)
        navigation.navigateTo(OnboardingScreen)
        assertEquals(listOf(OnboardingScreen), navigation.entries.map { it.screen })
        assertFalse(navigation.back())
    }

    @Test
    fun `back handlers belong to entries even when route values are equal`() {
        val navigation = Navigation()
        var firstCalls = 0
        var secondCalls = 0
        navigation.navigateTo(SettingsScreen)
        navigation.onBackPressed[SettingsScreen] = {
            firstCalls++
            true
        }
        navigation.navigateTo(SettingsScreen)
        navigation.onBackPressed[SettingsScreen] = {
            secondCalls++
            true
        }

        assertTrue(navigation.onBackPressed())
        assertEquals(1, secondCalls)
        assertEquals(0, firstCalls)
        navigation.back()
        assertTrue(navigation.onBackPressed())
        assertEquals(1, firstCalls)
    }
}
