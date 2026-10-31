package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ToolCategory
import com.example.planner.KnowledgeBasePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app_name and verify G6 knowledge base planner from assets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Oxide Companion", appName)

        val planner = KnowledgeBasePlanner(context)
        val decision = planner.evaluateUpgradeOrCraft(
            inventoryCounts = mapOf("wood" to 150, "metal_fragments" to 120),
            currentToolTierByCategory = mapOf(
                ToolCategory.WOODCUTTING to 1,
                ToolCategory.MINING to 1
            ),
            availableWorkbenchLevel = 1
        )
        assertTrue(decision.shouldCraft)
        assertEquals("metal_hatchet", decision.targetTool?.id)
    }
}
