package com.lyric.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lyric.data.DailyStat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class ReadingCalendarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchesMonthAndYearAndKeepsDailyDetails() {
        val today = LocalDate.of(2026, 11, 18)
        val january = LocalDate.of(2026, 1, 2)
        var selected: LocalDate? = null
        val stats = listOf(DailyStat(today.toString(), 900, 10), DailyStat(january.toString(), 3600, 10))
        compose.setContent {
            LyricTheme {
                Box(Modifier.fillMaxSize()) { ReadingCalendar(stats, today) { selected = it } }
            }
        }

        compose.onNodeWithText("月").assertIsSelected()
        compose.onNodeWithText("2026 年 11 月").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一个月").assertIsNotEnabled()
        compose.onNodeWithContentDescription("2026-11-18 阅读 15 分钟").performClick()
        compose.runOnIdle { assertEquals(today, selected) }

        compose.onNodeWithContentDescription("上一个月").performClick()
        compose.onNodeWithText("2026 年 10 月").assertIsDisplayed()
        compose.onNodeWithText("年").performClick().assertIsSelected()
        compose.onNodeWithText("2026 年").assertIsDisplayed()
        compose.onNodeWithContentDescription("2026-01-02 阅读 60 分钟").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription("2026-01-02 阅读 60 分钟").performClick()
        compose.runOnIdle { assertEquals(january, selected) }

        compose.onNodeWithContentDescription("上一年").performClick()
        compose.onNodeWithText("2025 年").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一年").performClick()
        compose.onNodeWithText("月").performClick()
        compose.onNodeWithText("2026 年 10 月").assertIsDisplayed()
    }
}
