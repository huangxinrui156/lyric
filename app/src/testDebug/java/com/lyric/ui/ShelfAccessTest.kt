package com.lyric.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.data.FILE_ACCESS_EXPIRED_MESSAGE
import com.lyric.data.ShelfBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfAccessTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun verify(grid: Boolean) {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelf(grid = grid)
            app.repository.dao.insert(ShelfBook("expired", "file:///missing-original.txt", "missing.txt", "权限恢复测试", "作者", "txt",
                totalChars = 100, chapterCount = 1, encoding = "UTF-8", invalid = true, lastRead = Long.MAX_VALUE))
        }
        lateinit var vm: ShelfViewModel
        var opened = false
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = { opened = true }) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.books.value.any { it.id == "expired" } && vm.section.value != null && vm.grid.value == grid
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("权限恢复测试"))
        compose.onNodeWithText("文件失效", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("权限恢复测试").performClick()
        compose.onNodeWithText(FILE_ACCESS_EXPIRED_MESSAGE).assertIsDisplayed()
        assertFalse("Expired entries should show the recovery hint on the shelf", opened)
    }

    @Test fun expiredGridBookShowsRecoveryHintOnTap() = verify(grid = true)
    @Test fun expiredListBookShowsRecoveryHintOnTap() = verify(grid = false)
}
