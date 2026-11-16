package com.lyric

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyric.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubDeviceTest {
    @Test fun conventionalEpubOnAndroidRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val source = object : ContentSource {
            override val displayName = "epub3-basic.epub"
            override fun open() = context.assets.open("epub/$displayName")
        }
        val book = EpubParser().parse(source)
        assertEquals("Basic", book.title)
        assertEquals("Leaf", book.toc.single().title)
    }
}
