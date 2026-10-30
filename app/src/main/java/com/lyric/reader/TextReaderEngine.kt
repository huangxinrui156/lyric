package com.lyric.reader

import com.lyric.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TextReaderEngine : ReaderEngine {
    override val contentType = ContentType.TEXT
    private var book: Book? = null
    private val location = MutableStateFlow(Locator())
    override val currentLocator = location.asStateFlow()
    private val prefs = MutableStateFlow(ReaderSettings())
    override val settings = prefs.asStateFlow()

    override fun open(book: Book, locator: Locator) {
        this.book = book
        goTo(locator)
    }

    override fun goTo(locator: Locator) {
        location.value = book?.clamp(locator) ?: locator
    }

    override fun settings(settings: ReaderSettings) {
        prefs.value = settings
    }
}

class EngineRegistry(
    private val factories: Map<ContentType, () -> ReaderEngine> =
        mapOf(ContentType.TEXT to { TextReaderEngine() })
) {
    fun create(type: ContentType) = factories[type]?.invoke() ?: error("该内容类型的阅读引擎尚未安装")
}

/** Routes sessions by content type while keeping the UI's progress and settings streams stable. */
class ReaderSession(private val registry: EngineRegistry = EngineRegistry()) : ReaderEngine {
    private var delegate: ReaderEngine? = null
    override val contentType
        get() = delegate?.contentType ?: ContentType.TEXT

    private val location = MutableStateFlow(Locator())
    override val currentLocator = location.asStateFlow()
    private val prefs = MutableStateFlow(ReaderSettings())
    override val settings = prefs.asStateFlow()

    override fun open(book: Book, locator: Locator) {
        val engine = registry.create(book.contentType)
        engine.settings(prefs.value)
        engine.open(book, locator)
        delegate = engine
        location.value = engine.currentLocator.value
    }

    override fun goTo(locator: Locator) {
        delegate?.goTo(locator)
        location.value = delegate?.currentLocator?.value ?: locator
    }

    override fun settings(settings: ReaderSettings) {
        prefs.value = settings
        delegate?.settings(settings)
    }
}
