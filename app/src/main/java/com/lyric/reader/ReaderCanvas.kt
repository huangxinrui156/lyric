package com.lyric.reader

import android.animation.ValueAnimator
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.view.*
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import com.lyric.R
import com.lyric.core.*
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.*

/** Draws canonical text directly; formatting spans never alter stored character offsets. */
class ReaderCanvas(context: Context) : View(context) {
    var onLocator: (Locator) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onContentReady: () -> Unit = {}
    var onWindowReady: () -> Unit = {}
    private var contentReported = false
    private var describedLocator: Locator? = null
    var onEndReached: () -> Unit = {}
    var onReadingActivity: () -> Unit = {}
    var onPageInfo: (String) -> Unit = {}
    var onVisibleLocator: (Locator) -> Unit = {}
    var onCurl: ((Int, Boolean, Float, Float) -> Unit)? = null
    var onCurlMove: (Float, Float) -> Unit = { _, _ -> }
    var onCurlRelease: (Float, Float) -> Unit = { _, _ -> }
    private var curlOrigin: Locator? = null
    private var curlDestination: Locator? = null
    private var curlDragging = false
    private var curlRestored: (() -> Unit)? = null
    var onCurlReady: () -> Unit = {}
    var onCurlCancel: () -> Unit = {}
    var curlRunning = false
    private var curlAwaitingDraw = false
    private var book: Book? = null
    private var locator = Locator()
    private var prefs = ReaderSettings()
    private var ink = 0xFF2B2A27.toInt()
    private var paper = 0xFFF4EFE6.toInt()
    private var highlight = ""
    private val serifFont by lazy {
        ResourcesCompat.getFont(context, R.font.noto_serif_sc) ?: Typeface.SERIF
    }
    private var typeset: TypesetChapter? = null
    private var chapter = -1
    private var page = 0
    private var scroll = 0f
    private var reportedScroll = 0f
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var paginator: PrePaginator? = null
    private var paginationKey: Any? = null
    var controlsOpen = false
    var onExcerpt: (Locator, String) -> Unit = { _, _ -> }
    /** (chapter, start, end, text, withNote) — chapter-relative offsets survive re-pagination. */
    var onHighlight: (Int, Int, Int, String, Boolean) -> Unit = { _, _, _, _, _ -> }
    private var highlightsByChapter: Map<Int, List<com.lyric.data.HighlightRow>> = emptyMap()
    var highlights: List<com.lyric.data.HighlightRow> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                highlightsByChapter = value.groupBy { it.chapterIndex }
                invalidate()
            }
        }
    var onImage: (String) -> Unit = {}
    private var pendingKey: Any? = null
    private var layoutKey: Any? = null
    private var animator: ValueAnimator? = null
    private var oldBitmap: Bitmap? = null
    private var animOffset = 0f
    private var direction = 1
    private val selectionPath = Path()
    private val selectionPaint = Paint()
    private val paperPaint = Paint()
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)
    private var selection: TextSelection? = null
    val selectionMenuOpen: Boolean get() = selectionPopup != null
    private var draggingHandle = 0
    private val startHandle = PointF()
    private val endHandle = PointF()
    private val handler = Handler(Looper.getMainLooper())
    private var longPressed = false
    private var pressWord: TextSelection? = null
    internal val selectedRange: TextSelection? get() = selection
    internal val endHandlePoint: PointF get() = endHandle
    private var magnifier: android.widget.Magnifier? = null
    var onHighlightTap: (com.lyric.data.HighlightRow) -> Unit = {}
    /** Active (tapped) highlight: handles adjust its range; menu = colours | 笔记 | 删除. */
    internal var editing: com.lyric.data.HighlightRow? = null
        private set
    /** (id, action, a, b): "color"(index) · "note" · "delete" · "range"(start, end chapter-relative). */
    var onHighlightEdit: (Long, String, Int, Int) -> Unit = { _, _, _, _ -> }
    private val longPress = Runnable { beginSelection(downX, downY) }
    private var edgeStep = 0
    private val edgeTurn = object : Runnable {
        override fun run() {
            if (draggingHandle == 0 || edgeStep == 0) return
            if (prefs.pageMode == PageMode.SCROLL && (if (edgeStep > 0) scroll < maxScroll() else scroll > 0)) {
                scroll = (scroll + edgeStep * prefs.fontSize * resources.displayMetrics.scaledDensity * 2).coerceIn(0f, maxScroll())
                emitScroll()
                extendSelection(lastX, lastY)
            } else {
                turn(edgeStep)
                // bind/syncLocation moves the dragged boundary to the new visible edge.
            }
            invalidate()
            handler.postDelayed(this, EDGE_TURN_MS)
        }
    }
    private var lastX = 0f
    private val density
        get() = resources.displayMetrics.density

    private val horizontal
        get() = prefs.margin * density

    private val bodyHeight
        get() = height.coerceAtLeast(1)

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var checkCompletionAfterNavigation = false
    private var velocity: VelocityTracker? = null

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "正文，轻点中央显示菜单，轻点左右翻页，上下滑动滚动正文"
    }

    fun bind(
        book: Book,
        locator: Locator,
        settings: ReaderSettings,
        ink: Int,
        paper: Int,
        highlight: String,
    ) {
        // AndroidView can update for controls/footer changes without changing its content.
        if (this.book === book && this.locator == locator && prefs == settings &&
            this.ink == ink && this.paper == paper && this.highlight == highlight &&
            !curlAwaitingDraw && !checkCompletionAfterNavigation &&
            (layoutKey != null || pendingKey != null)) return
        if (curlOrigin != null && (this.book !== book || (locator != curlOrigin && locator != curlDestination))) abortCurl()
        if (this.book !== book) { clearSelection(); contentReported = false }
        val oldLoc = this.locator
        val oldMode = this.prefs.pageMode
        val changedBook = this.book !== book
        this.book = book
        // While previewing, Compose still owns the committed origin. Do not let its
        // header/footer recompositions reset the uncommitted destination page.
        val locator = if (curlOrigin == locator) curlDestination ?: locator else locator
        this.locator = locator
        this.prefs = settings
        this.ink = ink
        this.paper = paper
        this.highlight = highlight
        if (oldLoc != locator || changedBook) {
            describedLocator = null
            contentDescription = preview(book.chapters[locator.chapterIndex].text, locator.charOffset, 800)
        }
        val window = locator.charOffset / Typesetter.WINDOW
        val key =
            listOf(
                System.identityHashCode(book),
                locator.chapterIndex,
                window,
                width,
                height,
                listOf(settings.fontSize, settings.lineHeight, settings.paragraphSpace, settings.margin,
                    settings.indent, settings.justify, settings.font, settings.useBookStyle,
                    resources.displayMetrics.scaledDensity),
            )
        if (width > 0 && height > 0 && key != layoutKey && key != pendingKey) {
            job?.cancel()
            pendingKey = key
            val index = locator.chapterIndex
            val textWidth = (width - horizontal * 2).toInt().coerceAtLeast(32)
            val textHeight = bodyHeight
            val geometry = listOf(key[0], key.drop(3))
            if (geometry != paginationKey) {
                paginator?.close()
                paginationKey = geometry
                val assets = BookAssets(context, book)
                // Resolved once, lazily on the worker (imported fonts may require disk IO).
                val layoutSettings = effective(settings, book)
                val layoutFont by lazy { font(settings.font, settings, book) }
                val scaledDensity = resources.displayMetrics.scaledDensity
                val displayDensity = density
                paginator = PrePaginator(book, scope, { c, offset ->
                    Typesetter.layout(book.chapters[c].text, offset, textWidth, textHeight,
                        displayDensity, scaledDensity, layoutSettings, layoutFont,
                        book.chapters[c], assets)
                }, { c -> if (c == chapter) pageInfo() })
            }
            val worker = paginator!!
            val anchor = locator
            job = scope.launch {
                val result = worker.get(index, anchor.charOffset)
                if (pendingKey != key) return@launch
                typeset = result
                chapter = index
                layoutKey = key
                pendingKey = null
                syncLocation()
                onWindowReady()
                worker.prioritize(this@ReaderCanvas.locator)
                invalidate()
            }
        } else if (key == layoutKey && (oldLoc != locator || changedBook || oldMode != settings.pageMode || checkCompletionAfterNavigation)) syncLocation()
        invalidate()
    }

    private fun updateContentDescription() {
        contentDescription = "Lyric正文:" + preview(
            book?.chapters?.getOrNull(locator.chapterIndex)?.text.orEmpty(), locator.charOffset, 200)
    }

    private fun preview(text: String, offset: Int, length: Int): String {
        val start = offset.coerceIn(0, text.length)
        return text.substring(start, minOf(start + length, text.length))
    }

    private fun effective(settings: ReaderSettings, book: Book): ReaderSettings {
        val style = book.suggestedStyle?.takeIf { settings.useBookStyle } ?: return settings
        return settings.copy(
            lineHeight = style.lineHeight ?: settings.lineHeight,
            indent = style.indent ?: settings.indent,
        )
    }

    private fun font(name: String, settings: ReaderSettings = prefs, book: Book? = this.book): Typeface =
        when {
            name.startsWith('/') ->
                runCatching { Typeface.createFromFile(File(name)) }.getOrDefault(Typeface.SERIF)
            settings.useBookStyle && book?.suggestedStyle?.serif == false -> Typeface.SANS_SERIF
            name == "serif" -> serifFont
            else -> Typeface.create(name, Typeface.NORMAL)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelAnimation()
        layoutKey = null
        pendingKey = null
        book?.let { bind(it, locator, prefs, ink, paper, highlight) }
    }

    private fun syncLocation() {
        val set = typeset ?: return
        val local =
            (locator.charOffset - set.sourceStart).coerceIn(
                0,
                set.layout.text.length.coerceAtLeast(1) - 1,
            )
        page = set.pages.indexOfLast { it.start <= local }.coerceAtLeast(0)
        scroll =
            set.layout
                .getLineTop(set.layout.getLineForOffset(local))
                .toFloat()
                .coerceIn(0f, maxScroll())
        reportedScroll = scroll
        if (checkCompletionAfterNavigation) {
            val b = book
            if (b != null && chapter == b.chapters.lastIndex &&
                set.sourceEnd == b.chapters[chapter].text.length && page == set.pages.lastIndex) onEndReached()
            checkCompletionAfterNavigation = false
        }
        if (draggingHandle != 0 && edgeStep != 0) {
            val p = set.pages[page]
            val offset = if (edgeStep > 0) p.end else p.start
            moveBoundary(TextSelection.absolute(book ?: return, chapter, set.sourceStart + offset))
        }
        reportVisibleLocation()
        pageInfo()
    }

    private fun reportVisibleLocation() {
        val set = typeset ?: return
        val offset = if (prefs.pageMode == PageMode.SCROLL)
            set.layout.getLineStart(set.layout.getLineForVertical(scroll.toInt()))
        else set.pages[page].start
        onVisibleLocator(Locator(chapter, set.sourceStart + offset, page))
    }

    private fun navigate(next: Locator) {
        val b = book ?: return
        val at = b.clamp(next)
        // Advance locally in the same event, including repeated key/touch events before Compose
        // rebinds. Compose and the durable writer receive the exact same canonical anchor.
        onLocator(at)
        bind(b, at, prefs, ink, paper, highlight)
    }

    private fun cancelAnimation() {
        animator?.cancel()
        oldBitmap?.recycle()
        oldBitmap = null
        animOffset = 0f
        abortCurl()
    }

    private fun pageInfo() {
        val set = typeset ?: return
        onPageInfo(
            "${page + 1} / ${set.pages.size}${if (set.sourceStart > 0 || set.sourceEnd < (book?.chapters?.getOrNull(chapter)?.text?.length ?: 0)) " · 分段" else " · 本章"}"
        )
        val current = layoutKey
        val atChapter = chapter
        val atPage = page
        val worker = paginator
        if (worker != null) scope.launch {
            worker.pagePosition(atChapter, set.sourceStart, atPage)?.let { (number, total) ->
                if (current == layoutKey && atChapter == chapter && atPage == page)
                    onPageInfo("$number / $total · 本章")
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(paper)
        val set = typeset
        if (set == null || chapter != locator.chapterIndex || pendingKey != null) {
            drawSkeleton(canvas)
            return
        }
        val currentTop =
            if (prefs.pageMode == PageMode.SCROLL) scroll
            else set.pages.getOrNull(page)?.top?.toFloat() ?: 0f
        val pageHeight =
            if (prefs.pageMode == PageMode.SCROLL) height.toFloat()
            else
                (set.pages[page].bottom - set.pages[page].top)
                    .toFloat()
                    .coerceAtMost(height.toFloat())
        val moving = oldBitmap != null && animOffset != 0f
        if (moving && prefs.pageMode == PageMode.COVER)
            oldBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.save()
        if (moving) canvas.translate(animOffset, 0f)
        paperPaint.color = paper
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
        canvas.clipRect(horizontal, 0f, width - horizontal, pageHeight)
        canvas.translate(horizontal, -currentTop)
        set.layout.paint.color = ink
        if (highlight.isNotBlank()) {
            val visibleStart =
                if (prefs.pageMode == PageMode.SCROLL)
                    set.layout.getLineStart(set.layout.getLineForVertical(scroll.toInt()))
                else set.pages[page].start
            val visibleEnd =
                if (prefs.pageMode == PageMode.SCROLL)
                    set.layout.getLineEnd(set.layout.getLineForVertical((scroll + height).toInt()))
                else set.pages[page].end
            selectionPaint.color = palette().fill
            var index = set.plainText.indexOf(highlight, visibleStart, true)
            while (index in visibleStart until visibleEnd) {
                selectionPath.reset()
                set.layout.getSelectionPath(
                    index,
                    minOf(index + highlight.length, set.layout.text.length),
                    selectionPath,
                )
                canvas.drawPath(selectionPath, selectionPaint)
                index = set.plainText.indexOf(highlight, index + highlight.length, true)
            }
        }
        if (highlights.isNotEmpty()) {
            val night = com.lyric.core.ThemePalette.isNight(prefs.theme, prefs.customBg)
            val b = book
            for (h in highlightsByChapter[chapter].orEmpty()) {
                val active = editing?.id == h.id
                // While editing, the live (dragged) range replaces the stored one.
                var hs = h.start
                var he = h.end
                if (active && b != null && selection != null) {
                    val base = TextSelection.absolute(b, chapter, 0)
                    hs = selection!!.start - base
                    he = selection!!.end - base
                }
                drawMarker(canvas, set, hs - set.sourceStart, he - set.sourceStart, h, night, active)
            }
        }
        if (editing == null) selection?.let { selected ->
            val b = book ?: return@let
            val base = TextSelection.absolute(b, chapter, set.sourceStart)
            val a = (selected.start - base).coerceIn(0, set.layout.text.length)
            val z = (selected.end - base).coerceIn(0, set.layout.text.length)
            if (a < z) {
                selectionPaint.color = palette().fill
                selectionPath.reset()
                set.layout.getSelectionPath(a, z, selectionPath)
                canvas.drawPath(selectionPath, selectionPaint)
            }
        }
        set.layout.draw(canvas)
        if (!contentReported) {
            contentReported = true
            onContentReady()
        }
        if (describedLocator != locator) {
            updateContentDescription()
            describedLocator = locator
        }
        canvas.restore()
        if (curlAwaitingDraw) {
            curlAwaitingDraw = false
            handler.post {
                val restored = curlRestored
                if (restored != null) {
                    curlRestored = null
                    curlRunning = false
                    restored()
                } else if (curlRunning) onCurlReady()
            }
        }
        if (selection != null) drawHandles(canvas, currentTop)
        if (moving && prefs.pageMode == PageMode.SLIDE)
            oldBitmap?.let { canvas.drawBitmap(it, animOffset - direction * width, 0f, null) }
    }

    private fun drawSkeleton(canvas: Canvas) {
        val paint = skeletonPaint.apply { color = ink; alpha = 18 }
        val line = prefs.fontSize * resources.displayMetrics.scaledDensity
        var y = line
        var row = 0
        while (y < height - line) {
            val right = width - horizontal - if (row % 4 == 3) width * .18f else 0f
            canvas.drawRoundRect(horizontal, y, right, y + line * .38f, 3f, 3f, paint)
            y += line * prefs.lineHeight
            row++
        }
    }

    private fun maxScroll() = ((typeset?.layout?.height ?: 0) - height).coerceAtLeast(0).toFloat()

    private fun emitScroll() {
        val set = typeset ?: return
        if (reportedScroll != scroll) onReadingActivity()
        reportedScroll = scroll
        val offset =
            set.layout.getLineStart(set.layout.getLineForVertical(scroll.toInt())) + set.sourceStart
        val previousPage = page
        val changed = locator.charOffset != offset
        if (changed) {
            locator = locator.copy(charOffset = offset)
        }
        page = set.pages.indexOfLast { it.start <= offset - set.sourceStart }.coerceAtLeast(0)
        if (changed) {
            onLocator(locator)
            reportVisibleLocation()
        }
        val b = book
        if (b != null && chapter == b.chapters.lastIndex && set.sourceEnd == b.chapters[chapter].text.length &&
            scroll >= maxScroll() && maxScroll() > 0) onEndReached()
        if (previousPage != page) pageInfo()
    }

    private fun target(step: Int): Locator? {
        val b = book ?: return null
        val set = typeset ?: return null
        val next = page + step
        if (next in set.pages.indices)
            return locator.copy(charOffset = set.sourceStart + set.pages[next].start)
        if (step > 0 && set.sourceEnd < b.chapters[chapter].text.length)
            return locator.copy(charOffset = set.sourceEnd)
        if (step < 0 && set.sourceStart > 0) return locator.copy(charOffset = set.sourceStart - 1)
        val nextChapter = chapter + step
        if (nextChapter !in b.chapters.indices) return null
        return Locator(
            nextChapter,
            if (step > 0) 0 else (b.chapters[nextChapter].text.length - 1).coerceAtLeast(0),
        )
    }

    fun turn(step: Int) {
        if (pendingKey != null || curlRunning) return
        // A page turn dismisses the selection menu (except the edge-turn while dragging a handle).
        if (selection != null && draggingHandle == 0) clearSelection()
        val next =
            target(step)
                ?: run {
                    if (step > 0) onEndReached()
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    return
                }
        cancelAnimation()
        if (prefs.pageMode == PageMode.CURL && onCurl != null && width > 0 && height > 0) {
            startCurl(step, false, if (step > 0) width * .95f else width * .05f, height * .9f)
            return
        }
        if (
            prefs.pageMode in listOf(PageMode.SLIDE, PageMode.COVER) &&
                next.chapterIndex == chapter &&
                next.charOffset in (typeset?.sourceStart ?: 0) until (typeset?.sourceEnd ?: 0) &&
                width > 0 &&
                height > 0
        ) {
            oldBitmap = pageSnapshot(width, height) { draw(it) }
            if (oldBitmap != null) {
                direction = step
                animOffset = step * width.toFloat()
                animator =
                    ValueAnimator.ofFloat(animOffset, 0f).apply {
                        duration = 280
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        addUpdateListener {
                            animOffset = it.animatedValue as Float
                            invalidate()
                            if (it.animatedFraction == 1f) {
                                oldBitmap?.recycle()
                                oldBitmap = null
                            }
                        }
                        start()
                    }
            }
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        checkCompletionAfterNavigation = true
        navigate(next)
        val set = typeset
        val b = book
        if (b != null && set != null && chapter == b.chapters.lastIndex &&
            next.chapterIndex == chapter && set.sourceEnd == b.chapters[chapter].text.length &&
            next.charOffset >= set.sourceStart + set.pages.last().start) onEndReached()
    }

    private fun startCurl(step: Int, dragging: Boolean, x: Float, y: Float): Boolean {
        if (pendingKey != null || curlRunning || onCurl == null) return false
        // Never curl while a selection or long-press is in progress (selection owns the gesture).
        if (selection != null || longPressed || draggingHandle != 0) return false
        val next = target(step) ?: return false
        curlOrigin = locator
        curlDestination = next
        curlDragging = dragging
        curlRunning = true
        handler.removeCallbacks(longPress)
        onCurl?.invoke(step, dragging, x, y)
        return true
    }

    /** Called after the entire old surface has been captured, without committing progress. */
    fun prepareCurlTarget() {
        val next = curlDestination ?: return
        val b = book ?: return
        curlAwaitingDraw = true
        bind(b, next, prefs, ink, paper, highlight)
    }

    fun finishCurl(commit: Boolean, restored: () -> Unit) {
        val original = curlOrigin ?: run { restored(); return }
        val destination = curlDestination ?: original
        curlOrigin = null
        curlDestination = null
        curlDragging = false
        if (commit) {
            curlRunning = false
            checkCompletionAfterNavigation = true
            navigate(destination)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            restored()
        } else {
            curlRestored = restored
            curlAwaitingDraw = true
            book?.let { bind(it, original, prefs, ink, paper, highlight) }
        }
    }

    /** Snapshot allocation failed: complete this turn without an overlay. */
    fun finishCurlWithoutAnimation() {
        curlAwaitingDraw = false
        finishCurl(true) { onCurlCancel() }
    }

    fun abortCurl() {
        val original = curlOrigin
        curlOrigin = null
        curlDestination = null
        curlDragging = false
        curlAwaitingDraw = false
        curlRestored = null
        curlRunning = false
        if (original != null) book?.let { bind(it, original, prefs, ink, paper, highlight) }
        onCurlCancel()
    }

    private fun visibleTop(): Float = if (prefs.pageMode == PageMode.SCROLL) scroll
        else typeset?.pages?.getOrNull(page)?.top?.toFloat() ?: 0f

    private fun offsetAt(x: Float, y: Float): Int? {
        val set = typeset ?: return null
        if (pendingKey != null) return null
        val line = set.layout.getLineForVertical((y + visibleTop()).toInt())
        var offset = set.layout.getOffsetForHorizontal(line, x - horizontal) + set.sourceStart
        val text = book?.chapters?.getOrNull(chapter)?.text ?: return null
        if (offset in 1 until text.length && text[offset].isLowSurrogate()) offset--
        return offset.coerceIn(0, text.length)
    }

    internal fun beginEdit(h: com.lyric.data.HighlightRow) {
        val b = book ?: return
        val base = TextSelection.absolute(b, h.chapterIndex, 0)
        editing = h
        selection = TextSelection(base + h.start, base + h.end)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        showSelectionMenu()
        invalidate()
    }

    private fun highlightAt(x: Float, y: Float): com.lyric.data.HighlightRow? {
        if (highlights.isEmpty()) return null
        val offset = offsetAt(x, y) ?: return null
        return highlights.firstOrNull { it.chapterIndex == chapter && offset >= it.start && offset < it.end }
    }

    private fun imageAt(x: Float, y: Float): String? {
        val set = typeset ?: return null
        val text = set.layout.text as? android.text.Spanned ?: return null
        val line = set.layout.getLineForVertical((y + visibleTop()).toInt())
        for (image in text.getSpans(set.layout.getLineStart(line), set.layout.getLineEnd(line), BookImageSpan::class.java)) {
            val a = text.getSpanStart(image)
            val b = text.getSpanEnd(image)
            val left = horizontal + set.layout.getPrimaryHorizontal(a)
            val right = left + image.getSize(set.layout.paint, text, a, b, null)
            if (x in left..right) return image.resource
        }
        return null
    }

    private fun beginSelection(x: Float, y: Float) {
        val b = book ?: return
        val offset = offsetAt(x, y) ?: return
        selection = TextSelection.word(b.chapters[chapter].text, offset,
            TextSelection.absolute(b, chapter, 0)) ?: return
        longPressed = true
        moved = true
        // 0.5.2: keep the finger down and drag to extend from the long-pressed word.
        pressWord = selection
        draggingHandle = 2
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** design §6 custom popup: 复制 / 摘录 / 分享 on the theme's menu colours (replaces system ActionMode). */
    internal var selectionPopup: android.widget.PopupWindow? = null
        private set
    internal var selectionMenuView: android.widget.LinearLayout? = null
        private set
    internal var popupPosition: android.graphics.Point? = null
        private set

    internal fun selectionAction(id: Int) {
        val selected = selection ?: return
        val b = book ?: return
        val text = selected.text(b)
        when (id) {
            1 -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(b.title, text))
            2, 4 -> {
                val at = selected.locator(b)
                val len = b.chapters[at.chapterIndex].text.length
                val end = (at.charOffset + text.length).coerceAtMost(len)
                onHighlight(at.chapterIndex, at.charOffset, end,
                    b.chapters[at.chapterIndex].text.substring(at.charOffset, end), id == 4)
            }
            3 -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "分享摘录").apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
        clearSelection()
    }

    private fun hideSelectionMenu() {
        selectionPopup?.setOnDismissListener(null)
        selectionPopup?.dismiss()
        selectionPopup = null; selectionMenuView = null; popupPosition = null
    }

    private fun showSelectionMenu() {
        hideSelectionMenu()
        val colors = palette()
        val d = density
        val menu = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * d; setColor(colors.menuBg)
            }
            elevation = 14 * d
            val edit = editing
            if (edit != null) {
                val night = com.lyric.core.ThemePalette.isNight(prefs.theme, prefs.customBg)
                for (c in 0 until 4) addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(com.lyric.core.ThemePalette.markerLine(c))
                        if (c == edit.color) setStroke((2 * d).toInt(), colors.menuInk)
                    }
                    contentDescription = "颜色 ${c + 1}"
                    layoutParams = android.widget.LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL; marginEnd = (12 * d).toInt()
                    }
                    setOnClickListener {
                        onHighlightEdit(edit.id, "color", c, 0)
                        editing = edit.copy(color = c); showSelectionMenu(); invalidate()
                    }
                })
                addView(android.widget.TextView(context).apply { text = "|"; setTextColor(colors.menuInk); alpha = .4f; gravity = android.view.Gravity.CENTER; minHeight = (32 * d).toInt() })
                listOf("note" to "笔记", "delete" to "删除").forEach { (action, label) ->
                    addView(android.widget.TextView(context).apply {
                        text = label; textSize = 13f; setTextColor(colors.menuInk)
                        minHeight = (32 * d).toInt(); gravity = android.view.Gravity.CENTER
                        setPadding((10 * d).toInt(), 0, if (action == "delete") 0 else (2 * d).toInt(), 0)
                        contentDescription = label
                        setOnClickListener { onHighlightEdit(edit.id, action, 0, 0); clearSelection() }
                    })
                }
            } else
            listOf(1 to "复制", 2 to "划线", 4 to "笔记", 3 to "分享").forEachIndexed { i, (id, label) ->
                addView(android.widget.TextView(context).apply {
                    text = label; textSize = 13f; setTextColor(colors.menuInk)
                    minHeight = (32 * d).toInt(); gravity = android.view.Gravity.CENTER
                    setPadding(if (i == 0) 0 else (9 * d).toInt(), 0, if (i == 3) 0 else (9 * d).toInt(), 0)
                    contentDescription = label
                    setOnClickListener { selectionAction(id) }
                })
            }
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        }
        val pos = popupPlacement(menu.measuredWidth, menu.measuredHeight)
        selectionMenuView = menu
        popupPosition = pos
        val popup = android.widget.PopupWindow(menu, menu.measuredWidth, menu.measuredHeight, false).apply {
            isOutsideTouchable = false
            isTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            elevation = 14 * d
            // Tap outside (anywhere, including the page) dismisses the menu and the selection.
            setOnDismissListener { selectionPopup = null }
        }
        selectionPopup = popup
        if (windowToken != null) {
            val origin = IntArray(2).also { getLocationInWindow(it) }
            popup.showAtLocation(this, android.view.Gravity.NO_GRAVITY, origin[0] + pos.x, origin[1] + pos.y)
            menu.alpha = 0f; menu.scaleX = .96f; menu.scaleY = .96f
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
    }

    /** 8dp above the selection's top line, or below its bottom when there is no room; inside insets. */
    internal fun popupPlacement(w: Int, h: Int): android.graphics.Point {
        val d = density
        val insets = rootWindowInsets?.let {
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, this).getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        } ?: androidx.core.graphics.Insets.NONE
        val lineHeight = typeset?.layout?.let { it.height.toFloat() / it.lineCount.coerceAtLeast(1) } ?: (prefs.fontSize * 2 * d)
        val top = minOf(startHandle.y, endHandle.y) - lineHeight
        val bottom = maxOf(startHandle.y, endHandle.y) + 20 * d // below the 18dp handle bulbs
        val gap = 8 * d
        val minY = insets.top + gap
        val y = if (top - gap - h >= minY) top - gap - h else (bottom + gap).coerceAtMost(height - insets.bottom - h - gap)
        val cx = (startHandle.x + endHandle.x) / 2
        val x = (cx - w / 2).coerceIn(insets.left + gap, (width - insets.right - w - gap).coerceAtLeast(insets.left + gap))
        return android.graphics.Point(x.toInt(), y.toInt())
    }

    private fun clearSelection() {
        hideSelectionMenu()
        selection = null
        editing = null
        pressWord = null
        dismissMagnifier()
        draggingHandle = 0
        edgeStep = 0
        handler.removeCallbacks(edgeTurn)
        invalidate()
    }

    private fun moveBoundary(absolute: Int) {
        val old = selection ?: return
        pressWord?.let { w ->
            selection = if (absolute < w.start) TextSelection(absolute, w.end) else TextSelection(w.start, maxOf(absolute, w.end))
            return
        }
        selection = if (draggingHandle == 1) {
            if (absolute < old.end) TextSelection(absolute, old.end) else {
                draggingHandle = 2; TextSelection(old.end, absolute)
            }
        } else {
            if (absolute > old.start) TextSelection(old.start, absolute) else {
                draggingHandle = 1; TextSelection(absolute, old.start)
            }
        }
    }

    private fun extendSelection(x: Float, y: Float) {
        val b = book ?: return
        val offset = offsetAt(x, y) ?: return
        moveBoundary(TextSelection.absolute(b, chapter, offset))
        showMagnifier(x, y)
        invalidate()
    }

    /** design: 140x48dp, 12dp corners, 1.5x, 48dp above the finger (API 29+; older devices skip it). */
    private fun showMagnifier(x: Float, y: Float) {
        if (android.os.Build.VERSION.SDK_INT < 29 || android.os.Build.FINGERPRINT == "robolectric") return
        val d = density
        val m = magnifier ?: android.widget.Magnifier.Builder(this)
            .setSize((140 * d).toInt(), (48 * d).toInt()).setCornerRadius(12 * d).setInitialZoom(1.5f)
            .setDefaultSourceToMagnifierOffset(0, -(48 * d).toInt()).build().also { magnifier = it }
        runCatching { m.show(x, y) }
    }

    private fun dismissMagnifier() {
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { magnifier?.dismiss() }
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPath = Path()
    private val markerRect = android.graphics.RectF()
    private val markerRandom = java.util.Random()

    /**
     * 美术设计 0.5.3 highlighter: per-line band, 38% of line height, bottom at baseline+0.12em,
     * rounded ends (r = band/2) reaching 1.5dp past the text, top edge wobbling ±0.6dp every 8–12dp
     * (seeded by highlight id so it is stable across relayout), straight bottom; drawn under text.
     */
    private fun drawMarker(canvas: Canvas, set: TypesetChapter, from: Int, to: Int, h: com.lyric.data.HighlightRow, night: Boolean, active: Boolean) {
        val layout = set.layout
        val a = from.coerceIn(0, layout.text.length)
        val z = to.coerceIn(0, layout.text.length)
        if (a >= z) return
        val d = density
        val em = layout.paint.textSize
        val band = layout.paint.fontSpacing * .38f
        val r = band / 2
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = com.lyric.core.ThemePalette.markerFill(h.color, night, active)
        val first = layout.getLineForOffset(a)
        val last = layout.getLineForOffset((z - 1).coerceAtLeast(a))
        var endX = 0f; var endTop = 0f
        for (line in first..last) {
            val ls = maxOf(a, layout.getLineStart(line))
            var le = minOf(z, layout.getLineEnd(line))
            while (le > ls && layout.text[le - 1].let { it == '\n' || it == ' ' }) le--
            if (le <= ls) continue
            val x0 = minOf(layout.getPrimaryHorizontal(ls), if (le == layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(le))
            val x1 = maxOf(layout.getPrimaryHorizontal(ls), if (z >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(le))
            val left = x0 - 1.5f * d; val right = x1 + 1.5f * d
            val bottom = layout.getLineBaseline(line) + .12f * em
            val top = bottom - band
            val rr = minOf(r, (right - left) / 2)
            val rnd = markerRandom.apply { setSeed(h.id * 1_000_003L + line) }
            markerPath.reset()
            markerPath.moveTo(left + rr, top)
            var x = left + rr
            while (true) {
                val nx = x + (8 + rnd.nextFloat() * 4) * d
                if (nx >= right - rr) break
                val mid = (x + nx) / 2
                markerPath.quadTo(mid, top + (rnd.nextFloat() * 2 - 1) * .6f * d, nx, top + (rnd.nextFloat() * 2 - 1) * .6f * d)
                x = nx
            }
            markerPath.lineTo(right - rr, top)
            markerRect.set(right - 2 * rr, top, right, bottom); markerPath.arcTo(markerRect, -90f, 180f)
            markerPath.lineTo(left + rr, bottom)
            markerRect.set(left, top, left + 2 * rr, bottom); markerPath.arcTo(markerRect, 90f, 180f)
            markerPath.close()
            canvas.drawPath(markerPath, markerPaint)
            endX = right; endTop = top
        }
        if (h.note.isNotBlank() && endX > 0f && z == to.coerceIn(0, layout.text.length)) {
            markerPaint.color = com.lyric.core.ThemePalette.markerLine(h.color)
            canvas.drawCircle(endX + 2 * d, endTop - 1 * d, 2 * d, markerPaint)
        }
    }

    private fun drawHandles(canvas: Canvas, top: Float) {
        val set = typeset ?: return
        val b = book ?: return
        val selected = selection ?: return
        val base = TextSelection.absolute(b, chapter, set.sourceStart)
        val visibleStart = if (prefs.pageMode == PageMode.SCROLL)
            set.layout.getLineStart(set.layout.getLineForVertical(top.toInt())) else set.pages[page].start
        val visibleEnd = if (prefs.pageMode == PageMode.SCROLL)
            set.layout.getLineEnd(set.layout.getLineForVertical((top + height - 1).toInt())) else set.pages[page].end
        fun handle(absolute: Int, point: PointF) {
            val local = (absolute - base).coerceIn(visibleStart, visibleEnd.coerceAtMost(set.layout.text.length))
            val atLineEnd = absolute == selected.end && local > 0 && set.plainText.getOrNull(local - 1) == '\n'
            val line = set.layout.getLineForOffset(if (atLineEnd) local - 1 else local)
            val x = if (atLineEnd) set.layout.getLineRight(line) else set.layout.getPrimaryHorizontal(local)
            point.set(horizontal + x,
                (set.layout.getLineBottom(line) - top).coerceIn(12 * density, height - 12 * density))
            val paint = handlePaint.apply { color = palette().handle }
            drawHandle(canvas, point, absolute == selected.start, paint)
        }
        handle(selected.start, startHandle)
        handle(selected.end, endHandle)
    }

    /** design §6: 12dp teardrop; the point sits on the text edge, the bulb hangs below. */
    private fun drawHandle(canvas: Canvas, point: PointF, start: Boolean, paint: Paint) {
        val r = 9 * density
        val cx = point.x + if (start) -r else r
        val cy = point.y + r
        val drop = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            // Square corner pointing at the text boundary.
            addRect(if (start) cx else point.x, point.y, if (start) point.x else cx, cy, Path.Direction.CW)
        }
        canvas.drawPath(drop, paint)
    }

    private fun palette() = com.lyric.core.ThemePalette.selection(prefs.theme, prefs.customBg, prefs.customInk)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (controlsOpen) return false
        if (curlRunning) {
            if (curlDragging) {
                velocity?.addMovement(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE) onCurlMove(event.x, event.y)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    velocity?.computeCurrentVelocity(1000)
                    onCurlMove(event.x, event.y)
                    onCurlRelease(event.x, if (event.actionMasked == MotionEvent.ACTION_CANCEL) Float.NaN else velocity?.xVelocity ?: 0f)
                    curlDragging = false
                    velocity?.recycle(); velocity = null
                }
            }
            return true
        }
        lastX = event.x
        lastY = if (event.actionMasked == MotionEvent.ACTION_DOWN) event.y else lastY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                if (selection != null) {
                    // 48dp transparent hit target around the 12dp handle.
                    fun near(point: PointF) = abs(event.x - point.x) < 24 * density && abs(event.y - point.y - 9 * density) < 24 * density
                    draggingHandle = when { near(startHandle) -> 1; near(endHandle) -> 2; else -> 0 }
                    if (draggingHandle != 0) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        hideSelectionMenu()
                        return true
                    }
                    clearSelection()
                }
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 12 * density || abs(event.y - downY) > 12 * density)
                    handler.removeCallbacks(longPress)
                if (draggingHandle != 0) {
                    lastY = event.y
                    extendSelection(event.x, event.y)
                    val step = when {
                        event.y < 28 * density || (prefs.pageMode != PageMode.SCROLL && event.x < 20 * density) -> -1
                        event.y > height - 28 * density || (prefs.pageMode != PageMode.SCROLL && event.x > width - 20 * density) -> 1
                        else -> 0
                    }
                    if (step != edgeStep) {
                        edgeStep = step
                        handler.removeCallbacks(edgeTurn)
                        if (step != 0) handler.postDelayed(edgeTurn, EDGE_TURN_MS)
                    }
                    return true
                }
                if (longPressed) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(edgeTurn)
                edgeStep = 0
                if (draggingHandle != 0 || longPressed) {
                    draggingHandle = 0
                    pressWord = null
                    dismissMagnifier()
                    val h = editing; val b = book; val sel = selection
                    if (h != null && b != null && sel != null) {
                        val base = TextSelection.absolute(b, h.chapterIndex, 0)
                        val len = b.chapters[h.chapterIndex].text.length
                        val ns = (sel.start - base).coerceIn(0, len); val ne = (sel.end - base).coerceIn(0, len)
                        if (ne > ns && (ns != h.start || ne != h.end)) {
                            editing = h.copy(start = ns, end = ne, text = b.chapters[h.chapterIndex].text.substring(ns, ne))
                            onHighlightEdit(h.id, "range", ns, ne)
                        }
                    }
                    if (selection != null) showSelectionMenu()
                    invalidate()
                    return true
                }
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP && selection == null) {
                    highlightAt(event.x, event.y)?.let { beginEdit(it); return true }
                }
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    val image = imageAt(event.x, event.y)
                    if (image != null) { onImage(image); return true }
                }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x
                downY = event.y
                lastY = event.y
                moved = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                velocity?.addMovement(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (abs(event.x - downX) > 12 * density || abs(event.y - downY) > 12 * density)
                    moved = true
                if (prefs.pageMode == PageMode.CURL && moved && abs(event.x - downX) > abs(event.y - downY)) {
                    if (startCurl(if (event.x < downX) 1 else -1, true, event.x, event.y)) return true
                }
                if (prefs.pageMode == PageMode.SCROLL && moved) {
                    scroll = (scroll + lastY - event.y).coerceIn(0f, maxScroll())
                    emitScroll()
                    invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    performClick()
                    val x = event.x / width
                    when {
                        x < prefs.tapLeft -> turn(if (prefs.reverseTaps) 1 else -1)
                        x > prefs.tapRight -> turn(if (prefs.reverseTaps) -1 else 1)
                        else -> onMenu()
                    }
                } else if (prefs.pageMode == PageMode.SCROLL) {
                    val drag = event.y - downY
                    if (
                        (scroll <= 0 && drag > 70 * density) ||
                            (scroll >= maxScroll() && drag < -70 * density)
                    ) {
                        val step = if (drag > 0) -1 else 1
                        val b = book
                        val set = typeset
                        if (b != null && set != null) {
                            val next =
                                if (step > 0 && set.sourceEnd < b.chapters[chapter].text.length)
                                    locator.copy(charOffset = set.sourceEnd)
                                else if (step < 0 && set.sourceStart > 0)
                                    locator.copy(charOffset = set.sourceStart - 1)
                                else
                                    (chapter + step)
                                        .takeIf { it in b.chapters.indices }
                                        ?.let {
                                            Locator(
                                                it,
                                                if (step > 0) 0
                                                else b.chapters[it].text.lastIndex.coerceAtLeast(0),
                                            )
                                        }
                            if (next != null) {
                                checkCompletionAfterNavigation = true
                                navigate(next)
                            } else if (step > 0) onEndReached()
                        }
                    } else {
                        velocity?.computeCurrentVelocity(1000)
                        scroller.fling(
                            0,
                            scroll.toInt(),
                            0,
                            -(velocity?.yVelocity?.toInt() ?: 0),
                            0,
                            0,
                            0,
                            maxScroll().toInt(),
                        )
                        postInvalidateOnAnimation()
                    }
                } else if (abs(event.x - downX) > 40 * density) turn(if (event.x < downX) 1 else -1)
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat()
            emitScroll()
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val step = VolumeNavigation.step(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN,
                prefs.volumeKeys, prefs.swapVolumeKeys, controlsOpen || selectionPopup != null)
                ?: return super.onKeyDown(keyCode, event)
            if (event?.repeatCount == 0) turn(step)
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                turn(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_PAGE_UP -> {
                turn(-1)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDetachedFromWindow() {
        clearSelection()
        cancelAnimation()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
        job?.cancel()
        paginator?.close()
        paginator = null
        paginationKey = null
        pendingKey = null
        layoutKey = null
        scope.cancel()
        animator?.cancel()
        oldBitmap?.recycle()
        oldBitmap = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (width > 0 && height > 0) book?.let { bind(it, locator, prefs, ink, paper, highlight) }
    }
}

/** v0.4 P0 gesture timings (spec: selection ≤400ms; handle at page edge turns after 600ms). */
const val LONG_PRESS_MS = 400L
const val EDGE_TURN_MS = 600L
