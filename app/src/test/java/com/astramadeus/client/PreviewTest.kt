package com.astramadeus.client

import app.cash.paparazzi.Paparazzi
import com.astramadeus.client.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test

class PreviewTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun renderMyScreen() {
        paparazzi.snapshot {
            SettingsScreen(
                serviceEnabled = false,
                onOpenAccessibilitySettings = {}
            )
        }
    }
}
