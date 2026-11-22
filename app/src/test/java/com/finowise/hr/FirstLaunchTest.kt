package com.finowise.hr

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A fresh install with no project baked in asks how the firm will use the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FirstLaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun choosesAMode() {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/00-first-launch.png")
        rule.onNode(androidx.compose.ui.test.hasText("Share with my team (recommended)")).assertExists()
    }
}
