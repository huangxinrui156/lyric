package com.lyric.core

import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Sources are handles; the shelf never owns or deletes the user's original document. */
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
) {
    val units: List<ContentUnit>
        get() = content.ifEmpty { listOf(ContentUnit.TextSpan(text)) }
}

@Serializable
data class TocEntry(val title: String, val chapterIndex: Int, val charOffset: Int = 0)

@Serializable
data class Book(
    val title: String,
    val author: String = "佚名",
    val chapters: List<Chapter>,
    val toc: List<TocEntry> = chapters.map { TocEntry(it.title, it.index) },
    val encoding: String = "UTF-8",
    val contentType: ContentType = ContentType.TEXT,
    val suggestedStyle: BookStyle? = null,
) {
    val totalChars: Long
        get() = chapters.sumOf { it.text.length.toLong() }
}

@Serializable
data class BookStyle(
    val serif: Boolean = true,
    val lineHeight: Float? = null,
    val indent: Boolean? = null,
)

/** UTF-16 offset in canonical chapter text, never a page number or a formatted-text offset. */
@Serializable
data class Locator(val chapterIndex: Int = 0, val charOffset: Int = 0, val page: Int = 0)

@Serializable
data class Progress(
    val bookId: String,
    val locator: Locator,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class PageMode(val label: String) {
    COVER("覆盖"),
    SLIDE("平移"),
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
data class ReaderSettings(
    val appTheme: String = "system",
    val accentColor: Int = 0,
    val dailyGoalMin: Int = 20,
    val goalEnabled: Boolean = false,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = false,
    val orientation: String = "system",
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.9f,
    val paragraphSpace: Float = .6f,
    val margin: Float = 28f,
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
)

interface BookParser {
    val extensions: Set<String>

    fun parse(source: ContentSource, encoding: String? = null, chapterRegex: String? = null): Book
}

class ParserRegistry(private val parsers: List<BookParser> = listOf(TxtParser(), EpubParser())) {
    fun parser(extension: String): BookParser =
        parsers.firstOrNull { extension.lowercase() in it.extensions }
            ?: throw IllegalArgumentException("暂不支持 .$extension，仅支持 TXT / EPUB")
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
    val text = chapters[c].text
    var offset = locator.charOffset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
    if (offset > 0 && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset--
    return locator.copy(chapterIndex = c, charOffset = offset, page = locator.page.coerceAtLeast(0))
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
    return ((chapters.take(p.chapterIndex).sumOf { it.text.length.toLong() } + p.charOffset)
            .toDouble() / totalChars)
        .toFloat()
}

fun Book.excerpt(locator: Locator, length: Int = 90): String {
    val p = clamp(locator)
    return if (contentType == ContentType.TEXT)
        chapters[p.chapterIndex].text.drop(p.charOffset).take(length).replace('\n', ' ')
    else "${chapters[p.chapterIndex].title} · 第 ${p.page + 1} 页"
}
