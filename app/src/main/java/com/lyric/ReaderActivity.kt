package com.lyric

import android.view.KeyEvent
import androidx.activity.ComponentActivity

/** ReaderScreen installs this callback only while it is visible. All other screens defer to Android. */
open class ReaderActivity : ComponentActivity() {
    var readerVolumeHandler: ((KeyEvent) -> Boolean)? = null
    private fun readerKey(event: KeyEvent) =
        (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            readerVolumeHandler?.invoke(event) == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (readerKey(event)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (readerKey(event)) true else super.onKeyUp(keyCode, event)
}
