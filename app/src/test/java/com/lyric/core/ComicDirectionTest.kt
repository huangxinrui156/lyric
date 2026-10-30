package com.lyric.core

import com.lyric.ui.ComicDirections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicDirectionTest {
    @Test fun comicInfoManga() {
        assertEquals("rtl", ComicParser.direction("<ComicInfo><Manga>YesAndRightToLeft</Manga></ComicInfo>"))
        assertEquals("ltr", ComicParser.direction("<ComicInfo><Manga>No</Manga></ComicInfo>"))
        assertNull(ComicParser.direction("<ComicInfo><Manga>Unknown</Manga></ComicInfo>"))
        assertNull(ComicParser.direction("<ComicInfo/>"))
    }
    @Test fun cycle() {
        assertEquals("ltr", ComicDirections.next("rtl"))
        assertEquals("vertical", ComicDirections.next("ltr"))
        assertEquals("rtl", ComicDirections.next("vertical"))
    }
}
