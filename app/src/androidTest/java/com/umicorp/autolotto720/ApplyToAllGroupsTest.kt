package com.umicorp.autolotto720

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.umicorp.autolotto720.data.FallbackPolicy
import com.umicorp.autolotto720.data.Slot720
import com.umicorp.autolotto720.ui.screen.PurchaseSetupScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ApplyToAllGroupsTest {
    @get:Rule val screen = createAndroidComposeRule<ComponentActivity>()

    @Test fun applyAndUndoPersistAcrossFreshLoads() {
        val activity = screen.activity
        val app = activity.appContainer
        val digits = listOf(0, 1, 2, 3, 4, 5)
        val previous = listOf(Slot720.Manual(1, digits)) + List(4) { Slot720.Unset }
        runBlocking {
            app.ensureHydrated()
            assertTrue(app.saveNumberConfig(previous, FallbackPolicy.GIVE_UP, false))
        }
        fun freshConfig() = runBlocking {
            AppContainer(activity).also { it.ensureHydrated() }.numberConfig.value
        }
        fun clickText(id: Int) = screen.onNodeWithText(activity.getString(id)).performClick()

        screen.setContent { PurchaseSetupScreen() }
        screen.onNode(hasText("A") and hasClickAction()).performClick()
        screen.onNodeWithText(activity.getString(R.string.fallbackKeepGroup)).performScrollTo().performClick()
        screen.onNodeWithText(activity.getString(R.string.buttonSaveGames, 1)).assertExists()
        screen.onNodeWithText(activity.getString(R.string.applyToAllGroups)).performScrollTo().performClick()
        clickText(R.string.buttonOverwrite)
        val undo = hasText(activity.getString(R.string.snackbarUndoAction))
        screen.waitUntil(5_000) { screen.onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
        screen.onNodeWithText(activity.getString(R.string.buttonSaveDone)).assertExists()
        val applied = freshConfig()
        assertEquals((1..5).map { Slot720.Manual(it, digits) }, applied.slots)
        assertEquals(FallbackPolicy.KEEP_GROUP_RANDOM, applied.fallback)

        clickText(R.string.snackbarUndoAction)
        screen.waitUntil(5_000) { app.numberConfig.value.revision > applied.revision }
        val restored = freshConfig()
        assertEquals(previous, restored.slots)
        assertEquals(FallbackPolicy.GIVE_UP, restored.fallback)
        screen.onNodeWithText(activity.getString(R.string.buttonSaveGames, 1)).assertExists()
    }
}
