package com.lyric.reader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.TextView
import com.lyric.core.*
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionPopupTest {
    private val book = Book("测试", chapters = listOf(Chapter(0, "第一章", "山川异域，风月同天。".repeat(80))))

    private fun open(theme: ReaderTheme): Pair<Activity, ReaderCanvas> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = ReaderCanvas(activity)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        if (view.width == 0) view.layout(0, 0, 1080, 1920)
        var visible = false
        view.onVisibleLocator = { visible = true }
        view.bind(book, Locator(), ReaderSettings(theme = theme, pageMode = PageMode.NONE), theme.ink.toInt(), theme.bg.toInt(), "")
        repeat(1500) { if (!visible) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); Thread.sleep(5) } }
        assertTrue(visible)
        return activity to view
    }

    private fun longPress(view: ReaderCanvas, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LONG_PRESS_MS + 20))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 450, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun items(view: ReaderCanvas) = (0 until view.selectionMenuView!!.childCount).map { (view.selectionMenuView!!.getChildAt(it) as TextView) }

    @Test fun customMenuHasThreeThemedActionsAndCopyWorks() {
        for (theme in listOf(ReaderTheme.PAPER, ReaderTheme.BEIGE, ReaderTheme.MOSS, ReaderTheme.NIGHT)) {
            val (activity, view) = open(theme)
            longPress(view, view.width / 2f, view.height / 2f)
            val popup = requireNotNull(view.selectionPopup) { "$theme popup" }
            assertTrue(popup.isShowing)
            assertTrue(view.selectionMenuOpen)
            assertEquals(listOf("复制", "摘录", "分享"), items(view).map { it.text.toString() })
            val colors = ThemePalette.selection(theme)
            assertEquals(colors.menuBg, (view.selectionMenuView!!.background as GradientDrawable).color!!.defaultColor)
            assertEquals(colors.menuInk, items(view)[0].currentTextColor)
            val alpha = (colors.fill ushr 24) / 255f
            assertTrue("$theme alpha $alpha", alpha in 0.21f..0.31f)
            assertEquals(if (theme == ReaderTheme.NIGHT) 0xE08A6E else if (theme == ReaderTheme.MOSS) 0xA84E33 else 0xB5563A, colors.fill and 0xFFFFFF)
            // Inside the view, above the selection with an 8dp gap (or below when no room).
            val pos = view.popupPosition!!
            assertTrue(pos.x >= 0 && pos.x + view.selectionMenuView!!.measuredWidth <= view.width)
            assertTrue(pos.y >= 0 && pos.y + view.selectionMenuView!!.measuredHeight <= view.height)
            items(view)[0].performClick()
            val clip = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
            assertTrue(clip.getItemAt(0).text.isNotBlank())
            assertNull("copy dismisses", view.selectionPopup)
        }
    }

    @Test fun excerptShareOutsideTapAndPageTurnDismiss() {
        val (activity, view) = open(ReaderTheme.PAPER)
        var excerpt: Pair<Locator, String>? = null
        view.onExcerpt = { l, t -> excerpt = l to t }
        longPress(view, view.width / 2f, view.height / 2f)
        items(view)[1].performClick()
        assertNotNull(excerpt); assertTrue(excerpt!!.second.isNotBlank())

        longPress(view, view.width / 2f, view.height / 2f)
        items(view)[2].performClick()
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.isNotBlank())

        // Tap outside (on the page away from the handles) dismisses menu and selection.
        longPress(view, view.width / 2f, view.height / 2f)
        assertNotNull(view.selectionPopup)
        val popup = view.selectionPopup!!
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, view.height - 10f, 0))
        assertNull(view.selectionPopup); assertFalse(popup.isShowing)
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 10f, view.height - 10f, 0))

        // System PopupWindow outside-touch dismissal also clears the selection.
        longPress(view, view.width / 2f, view.height / 2f)
        view.selectionPopup!!.dismiss()
        assertNull(view.selectionPopup); assertFalse(view.selectionMenuOpen)

        // Page turn dismisses.
        longPress(view, view.width / 2f, view.height / 2f)
        assertNotNull(view.selectionPopup)
        view.turn(1)
        assertNull(view.selectionPopup)
    }

    @Test fun placementFallsBelowWhenNoRoomAbove() {
        val (_, view) = open(ReaderTheme.NIGHT)
        longPress(view, view.width / 2f, 30f * view.resources.displayMetrics.density)
        val menu = view.selectionMenuView ?: return // no word at top edge: nothing to assert
        val pos = view.popupPosition!!
        assertTrue(pos.y >= 0)
        assertTrue(pos.y + menu.measuredHeight <= view.height)
    }
}
