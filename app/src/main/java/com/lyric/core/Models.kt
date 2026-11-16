package com.lyric.core

import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Sources are handles; the shelf never owns or deletes the user's original document. */
interface FileContentSource : ContentSource {
    val file: java.io.File
}

interface ContentSource {
    val displayName: String
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    fun open(): InputStream
}

enum class ContentType {
    TEXT,
    COMIC,
}

@Serializable
sealed interface ContentUnit {
    @Serializable data class TextSpan(val text: String) : ContentUnit

    @Serializable
    data class ImagePage(val resource: String, val width: Int?, val height: Int?) : ContentUnit
}

@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val text: String = "",
    val href: String = "",
    val content: List<ContentUnit> = emptyList(),
    val styles: List<TextStyleRange> = emptyList(),
    val anchors: Map<String, Int> = emptyMap(),
    val pending: Boolean = false,
) {
    @kotlinx.serialization.Transient
    var legacyOffsets: List<OffsetMapping> = emptyList()

    val units: List<ContentUnit>
        get() = content.ifEmpty { listOf(ContentUnit.TextSpan(text)) }
}

@Serializable
@androidx.compose.runtime.Immutable
data class TocEntry(val title: String, val chapterIndex: Int, val charOffset: Int = 0, val fragment: String = "")

@Serializable
data class Book(
    val title: String,
    val author: String = "佚名",
    val chapters: List<Chapter>,
    val toc: List<TocEntry> = chapters.map { TocEntry(it.title, it.index) },
    val encoding: String = "UTF-8",
    val contentType: ContentType = ContentType.TEXT,
    val suggestedStyle: BookStyle? = null,
    val resources: Map<String, String> = emptyMap(),
    val embeddedFonts: Map<String, String> = emptyMap(),
    val coverResource: String? = null,
    /** Direction declared by the file (ComicInfo.xml Manga / EPUB page-progression): "rtl" | "ltr" | null. */
    val readingDirection: String? = null,
) {
    @kotlinx.serialization.Transient
    var deferred: DeferredChapters? = null
    @kotlinx.serialization.Transient
    private val textOffsets = LongArray(chapters.size + 1).apply {
        chapters.forEachIndexed { i, chapter -> this[i + 1] = this[i] + chapter.text.length }
    }
    val progressReady: Boolean get() = deferred?.lengthsReady?.value ?: true
    val totalChars: Long get() = if (deferred != null) deferred?.offsets?.last() ?: 0L else textOffsets.last()
    fun chapterLength(chapter: Int): Int = if (progressReady) (textOffset(chapter + 1) - textOffset(chapter)).toInt() else chapters[chapter].text.length
    internal fun textOffset(chapter: Int): Long = if (deferred != null) deferred?.offsets?.get(chapter) ?: 0L else textOffsets[chapter]

}

@Serializable
@androidx.compose.runtime.Immutable
data class BookStyle(
    val serif: Boolean = true,
    val lineHeight: Float? = null,
    val indent: Boolean? = null,
)

/** UTF-16 offset in canonical chapter text, never a page number or a formatted-text offset. */
@Serializable
@androidx.compose.runtime.Immutable
data class Locator(val chapterIndex: Int = 0, val charOffset: Int = 0, val page: Int = 0, val fragment: String = "")

@Serializable
data class Progress(
    val bookId: String,
    val locator: Locator,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class PageMode(val label: String) {
    COVER("覆盖"),
    SLIDE("平移"),
    CURL("仿真翻页"),
    SCROLL("滚动"),
    NONE("无动画"),
}

enum class ReaderTheme(val label: String, val bg: Long, val ink: Long, val meta: Long) {
    PAPER("日间", 0xFFF4EFE6, 0xFF2B2A27, 0xFF9C9384),
    BEIGE("纸张", 0xFFE8E1CF, 0xFF3A362F, 0xFF9C9384),
    MOSS("青苔", 0xFFC9D3C0, 0xFF2F3529, 0xFF7A8470),
    NIGHT("夜间", 0xFF16171A, 0xFFB8B2A6, 0xFF5C5A55),
    GREEN("深绿", 0xFF182820, 0xFFB8C3B1, 0xFF7A897A),
    BLACK("纯黑", 0xFF000000, 0xFFB8B2A6, 0xFF5C5A55),
    CUSTOM("自定", 0xFFF4EFE6, 0xFF2B2A27, 0xFF9C9384),
}

@Serializable
@androidx.compose.runtime.Immutable
data class ReaderSettings(
    val appTheme: String = "system",
    val accentColor: Int = 0,
    val dailyGoalMin: Int = 20,
    val goalEnabled: Boolean = false,
    val volumeKeys: Boolean = true,
    val swapVolumeKeys: Boolean = false,
    val showChapter: Boolean = true,
    val showPage: Boolean = false,
    val showPercent: Boolean = true,
    val showTime: Boolean = false,
    val showBattery: Boolean = false,
    val keepScreenOn: Boolean = false,
    val orientation: String = "system",
    val fontSize: Float = 17f,
    val lineHeight: Float = 1.9f,
    val paragraphSpace: Float = .3f,
    val margin: Float = 20f,
    val indent: Boolean = true,
    val justify: Boolean = true,
    val font: String = "serif",
    val pageMode: PageMode = PageMode.SLIDE,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val followSystem: Boolean = false,
    val brightness: Float = .6f,
    val dim: Float = 0f,
    val tapLeft: Float = .28f,
    val tapRight: Float = .72f,
    val reverseTaps: Boolean = false,
    val useBookStyle: Boolean = false,
    val customBg: Long = 0xFFF4EFE6,
    val customInk: Long = 0xFF2B2A27,
    /** Comic reading direction: "rtl" (default, manga), "ltr" or "vertical" (条漫). Stored per book via SettingsScope.BOOK. */
    val comicDirection: String = "rtl",
    /** null = automatic: two-page spread in landscape only on large screens (smallest width >= 600dp). */
    val comicSpread: Boolean? = null,
    val comicOffset: Boolean = false,
    val comicTrim: Boolean = false,
)

interface BookParser {
    val extensions: Set<String>

    fun parse(source: ContentSource, encoding: String? = null, chapterRegex: String? = null): Book
}

class ParserRegistry(private val parsers: List<BookParser> = listOf(TxtParser(), EpubParser(), ComicParser())) {
    fun parser(extension: String): BookParser =
        parsers.firstOrNull { extension.lowercase() in it.extensions }
            ?: throw IllegalArgumentException("暂不支持 .$extension")
}

interface ReaderEngine {
    val contentType: ContentType
    val currentLocator: StateFlow<Locator>
    val settings: StateFlow<ReaderSettings>

    fun open(book: Book, locator: Locator)

    fun goTo(locator: Locator)

    fun settings(settings: ReaderSettings)
}

fun Book.clamp(locator: Locator): Locator {
    if (chapters.isEmpty()) return Locator()
    val c = locator.chapterIndex.coerceIn(0, chapters.lastIndex)
    if (contentType == ContentType.COMIC)
        return Locator(
            c,
            0,
            locator.page.coerceIn(0, (chapters[c].units.size - 1).coerceAtLeast(0)),
        )
    if (chapters[c].pending) return locator.copy(chapterIndex = c)
    val text = chapters[c].text
    var offset = (chapters[c].anchors[locator.fragment] ?: locator.charOffset).coerceIn(0, (text.length - 1).coerceAtLeast(0))
    if (offset > 0 && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset--
    return locator.copy(chapterIndex = c, charOffset = offset, page = locator.page.coerceAtLeast(0), fragment = "")
}

fun Book.percent(locator: Locator): Float {
    val p = clamp(locator)
    if (contentType == ContentType.COMIC) {
        val pages = chapters.sumOf { it.units.size.toLong() }
        return if (pages == 0L) 0f
        else
            ((chapters.take(p.chapterIndex).sumOf { it.units.size.toLong() } + p.page).toDouble() /
                    pages)
                .toFloat()
    }
    if (totalChars == 0L) return 0f
    return ((textOffset(p.chapterIndex) + p.charOffset)
            .toDouble() / totalChars)
        .toFloat()
}

fun Book.excerpt(locator: Locator, length: Int = 90): String {
    val p = clamp(locator)
    return if (contentType == ContentType.TEXT)
        chapters[p.chapterIndex].text.let { it.substring(p.charOffset, minOf(p.charOffset + length, it.length)).replace('\n', ' ') }
    else "${chapters[p.chapterIndex].title} · 第 ${p.page + 1} 页"
}

/** Ranges always refer to canonical UTF-16 text; ruby readings are metadata, not text. */
@Serializable
@androidx.compose.runtime.Immutable
data class TextStyleRange(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizeEm: Float? = null,
    val align: String? = null,
    val indentEm: Float? = null,
    val fontFamily: String? = null,
    val resource: String? = null,
    val ruby: String? = null,
    val rule: Boolean = false,
    val bookStyle: Boolean = false,
)

/** A monotonic mapping from v0.2 EPUB text to v0.3 canonical text. Kept only while upgrading. */
data class OffsetMapping(val oldStart: Int, val oldEnd: Int, val start: Int, val end: Int)

fun Chapter.upgradeLegacyOffset(offset: Int): Int {
    val segment = legacyOffsets.lastOrNull { it.oldStart <= offset }
    val mapped = if (segment == null) 0 else if (offset <= segment.oldEnd)
        segment.start + (offset - segment.oldStart).coerceAtMost(segment.end - segment.start)
    else {
        val next = legacyOffsets.firstOrNull { it.oldStart > offset }
        (segment.end + offset - segment.oldEnd).coerceAtMost(next?.start ?: text.length)
    }
    return mapped.coerceIn(0, text.lastIndex.coerceAtLeast(0))
}
