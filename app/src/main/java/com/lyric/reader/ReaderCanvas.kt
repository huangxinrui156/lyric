package com.lyric.reader

import android.animation.ValueAnimator
import android.content.Context
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
    var onPageInfo: (String) -> Unit = {}
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
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pendingKey: Any? = null
    private var layoutKey: Any? = null
    private var animator: ValueAnimator? = null
    private var oldBitmap: Bitmap? = null
    private var animOffset = 0f
    private var direction = 1
    private val selectionPath = Path()
    private val selectionPaint = Paint().apply { color = 0x55B5563A }
    private val paperPaint = Paint()
    private val scroller = OverScroller(context)
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
    private var velocity: VelocityTracker? = null

    init {
        isClickable = true
        isFocusable = true
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
        val oldLoc = this.locator
        val changedBook = this.book !== book
        this.book = book
        this.locator = locator
        this.prefs = settings
        this.ink = ink
        this.paper = paper
        this.highlight = highlight
        contentDescription =
            book.chapters[locator.chapterIndex].text.drop(locator.charOffset).take(800)
        val window = locator.charOffset / Typesetter.WINDOW
        val key =
            listOf(
                book,
                locator.chapterIndex,
                window,
                width,
                height,
                settings.copy(
                    theme = ReaderTheme.PAPER,
                    brightness = .6f,
                    dim = 0f,
                    pageMode = PageMode.NONE,
                    followSystem = false,
                    customBg = 0,
                    customInk = 0,
                ),
            )
        if (width > 0 && height > 0 && key != layoutKey && key != pendingKey) {
            job?.cancel()
            pendingKey = key
            val index = locator.chapterIndex
            val text = book.chapters[index].text
            val textWidth = (width - horizontal * 2).toInt().coerceAtLeast(32)
            val textHeight = bodyHeight
            val font = font(settings.font)
            job =
                scope.launch {
                    val result =
                        withContext(Dispatchers.Default) {
                            Typesetter.layout(
                                text,
                                locator.charOffset,
                                textWidth,
                                textHeight,
                                density,
                                resources.displayMetrics.scaledDensity,
                                effective(settings, book),
                                font,
                            )
                        }
                    typeset = result
                    chapter = index
                    layoutKey = key
                    pendingKey = null
                    syncLocation()
                    invalidate()
                }
        } else if (key == layoutKey && (oldLoc != locator || changedBook)) syncLocation()
        invalidate()
    }

    private fun effective(settings: ReaderSettings, book: Book): ReaderSettings {
        val style = book.suggestedStyle?.takeIf { settings.useBookStyle } ?: return settings
        return settings.copy(
            lineHeight = style.lineHeight ?: settings.lineHeight,
            indent = style.indent ?: settings.indent,
        )
    }

    private fun font(name: String): Typeface =
        when {
            name.startsWith('/') ->
                runCatching { Typeface.createFromFile(File(name)) }.getOrDefault(Typeface.SERIF)
            prefs.useBookStyle && book?.suggestedStyle?.serif == false -> Typeface.SANS_SERIF
            name == "serif" -> serifFont
            else -> Typeface.create(name, Typeface.NORMAL)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
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
        pageInfo()
    }

    private fun pageInfo() {
        val set = typeset ?: return
        onPageInfo(
            if (prefs.pageMode == PageMode.SCROLL) "连续阅读"
            else
                "${page + 1} / ${set.pages.size}${if (set.sourceStart > 0 || set.sourceEnd < (book?.chapters?.getOrNull(chapter)?.text?.length ?: 0)) " · 分段" else " · 本章"}"
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(paper)
        val set = typeset ?: return
        if (chapter != locator.chapterIndex) return
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
        set.layout.draw(canvas)
        canvas.restore()
        if (moving && prefs.pageMode == PageMode.SLIDE)
            oldBitmap?.let { canvas.drawBitmap(it, animOffset - direction * width, 0f, null) }
    }

    private fun maxScroll() = ((typeset?.layout?.height ?: 0) - height).coerceAtLeast(0).toFloat()

    private fun emitScroll() {
        val set = typeset ?: return
        val offset =
            set.layout.getLineStart(set.layout.getLineForVertical(scroll.toInt())) + set.sourceStart
        locator = locator.copy(charOffset = offset)
        page = set.pages.indexOfLast { it.start <= offset - set.sourceStart }.coerceAtLeast(0)
        onLocator(locator)
        pageInfo()
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
        if (pendingKey != null) return
        val next =
            target(step)
                ?: run {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    return
                }
        animator?.cancel()
        oldBitmap?.recycle()
        oldBitmap = null
        if (
            prefs.pageMode in listOf(PageMode.SLIDE, PageMode.COVER) &&
                next.chapterIndex == chapter &&
                next.charOffset in (typeset?.sourceStart ?: 0) until (typeset?.sourceEnd ?: 0) &&
                width > 0 &&
                height > 0
        ) {
            oldBitmap =
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    draw(Canvas(it))
                }
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
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onLocator(next)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                            next?.let(onLocator)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                turn(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                turn(-1)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        job?.cancel()
        scope.cancel()
        animator?.cancel()
        oldBitmap?.recycle()
        oldBitmap = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
