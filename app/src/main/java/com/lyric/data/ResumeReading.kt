package com.lyric.data

import com.lyric.core.Locator

/** Open with a requested jump, otherwise the last saved position — exact chapter, offset and page. */
fun resumeLocator(requested: Locator?, saved: ProgressRow?): Locator =
    requested ?: saved?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: Locator()
