/*
 * Copyright (c) 2026 Andreas Brauchli and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

package net.bible.android.view.activity.passagefinder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import android.widget.OverScroller
import net.bible.android.activity.R
import net.bible.android.control.passagefinder.PassageFinderDataSource
import net.bible.service.common.CommonUtils
import net.bible.service.device.ScreenSettings
import kotlin.math.abs

/**
 * The passage finder overlay, drawn entirely by hand.
 *
 * This replaces a Jetpack Compose implementation that was visually right but took
 * seconds to appear: Compose was the only user of that runtime in the app, so opening
 * the finder paid for loading and interpreting the whole Compose stack before the first
 * frame. Everything used here — [Canvas], [android.graphics.Paint], [OverScroller],
 * [VelocityTracker] — is framework code that is already loaded and AOT-compiled on every
 * device, so the first frame costs a layout pass and one `onDraw`.
 *
 * The view is a passive renderer: it holds no Bible data of its own and owns no
 * coroutines. [PassageFinderLauncher] pushes state in through [render] and [setBooks],
 * and user intent comes back out through the callbacks. Scroll offsets are the one piece
 * of state that lives here, exactly as they lived in the Compose implementation — hoisting
 * them into the ViewModel would create a write-back loop with the scroll animations.
 *
 * Layout is bottom-anchored and computed fresh in [onDraw] from the animated strip
 * heights; there are no child views, so nothing needs measuring or laying out per frame.
 */
class PassageFinderView(context: Context) : View(context) {

    // ---- Collaborators -------------------------------------------------------------

    private val metrics = PassageFinderMetrics(resources.displayMetrics)
    private val painter = PassageFinderPainter(metrics)
    private val bubble = PreviewBubbleRenderer(metrics, painter)
    private val haptics = HapticController(this)

    private val bookLane = LensLane()
    private val chapterLane = UniformLane()
    private val verseLane = UniformLane()

    // The numeric strips damp their flings by their own peak magnification. Their cells
    // keep a small constant pitch but are drawn up to 2.8x that at the centre, so the
    // numbers stream past far faster than the strip looks like it is moving and an
    // ordinary fling overshoots to the end of a long chapter. Dragging needs no such
    // correction — the finger stays on the content — and the book strip already divides
    // by its live magnification while scrolling.
    private val bookScroll = LaneScroller(bookLane)
    private val chapterScroll = LaneScroller(chapterLane, 1f / metrics.chapterMaxScale)
    private val verseScroll = LaneScroller(verseLane, 1f / metrics.verseMaxScale)

    private val a11y = PassageFinderA11yHelper(this)

    // ---- Callbacks -----------------------------------------------------------------

    /** Invoked when the user taps outside the strips or swipes down past the book level. */
    var onDismiss: (() -> Unit)? = null

    /** Invoked when the user commits the current selection. */
    var onConfirm: (() -> Unit)? = null

    /** Invoked with the newly centred book index once a book scroll settles. */
    var onBookSelected: ((Int) -> Unit)? = null

    /** Invoked with the newly centred chapter once a chapter scroll settles. */
    var onChapterSelected: ((Int) -> Unit)? = null

    /** Invoked with the newly centred verse once a verse scroll settles. */
    var onVerseSelected: ((Int) -> Unit)? = null

    /** Invoked on an upward swipe; drills book → chapter → verse. */
    var onDrillDown: (() -> Unit)? = null

    /** Invoked on a downward swipe. Returns false when already at book level, which dismisses. */
    var onDrillUp: (() -> Boolean)? = null

    /**
     * Receives touches that land clear of the widget, to be replayed on the reader
     * showing through above it. Leave null to keep every touch inside the overlay.
     */
    var onReaderTouch: ((MotionEvent) -> Unit)? = null

    /**
     * Invoked the moment the user puts a finger on the widget itself.
     *
     * Signals that the user has taken over: the widget stops following the reader, and
     * the reader stops gliding underneath. Fired for any touch, not only one landing on
     * a strip — pinning a finger anywhere is the universal gesture for "stop".
     */
    var onUserInteracted: (() -> Unit)? = null

    // ---- State mirrored from the ViewModel -----------------------------------------

    private var books: List<PassageFinderDataSource.BookInfo> = emptyList()
    private var chapterCounts: IntArray = IntArray(0)
    private var state = PassageFinderUiState()
    private var verseText: String? = null

    /** True between the tap and the book list arriving; draws the skeleton. */
    private var loading = false

    // ---- View-local interaction state ----------------------------------------------

    /**
     * Progressive reveal, ported from the Compose widget:
     *  - while the user scrolls books, the chapter and verse strips hide;
     *  - while the user scrolls chapters, the verse strip hides;
     *  - after the book changes, the verse strip stays hidden until a chapter is picked,
     *    so the widget does not pre-commit to verse 1 of the new book.
     */
    private var bookScrolling = false
    private var chapterScrolling = false
    private var verseRevealed = true

    private var isDarkTheme = false
    private var isMonochrome = false
    private var disableAnimations = false

    // ---- Animations ----------------------------------------------------------------

    /** Slide-in progress for the whole stack, 0 fully off the bottom edge to 1 in place. */
    private val showAnim = Tween1D(0f, durationMs = SHOW_DURATION_MS)
    private val chapterHeight = Spring1D(metrics.chapterStripHeight)
    private val chapterAlpha = Spring1D(1f)
    private val verseHeight = Spring1D(metrics.verseStripHeight)
    private val verseAlpha = Spring1D(1f)
    private val bubbleAlpha = Tween1D(0f, durationMs = BUBBLE_FADE_IN_MS)

    /** Selection-border fade for the cell currently under the centre of each strip. */
    private val chapterBorder = Tween1D(1f, durationMs = BORDER_FADE_MS)
    private val verseBorder = Tween1D(1f, durationMs = BORDER_FADE_MS)
    private var chapterBorderIndex = -1
    private var verseBorderIndex = -1

    private var lastFrameNanos = 0L

    /** Seconds elapsed since the previous frame; shared by every animator this frame. */
    private var lastFrameDelta = 0f

    /** Set while the exit animation runs; the view hides itself when it completes. */
    private var dismissing = false

    // ---- Geometry, recomputed per frame --------------------------------------------

    private var contentLeft = 0f
    private var contentRight = 0f
    private val bookRect = RectF()
    private val chapterRect = RectF()
    private val verseRect = RectF()
    private val bubbleRect = RectF()

    /** Vertical offset of the whole stack during the show/hide slide. */
    private var slideOffset = 0f

    // ---- Touch ---------------------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f

    /** True while the current gesture is being handed to the reader behind the overlay. */
    private var readerGesture = false

    /** null until the gesture commits to an axis; then true for vertical. */
    private var lockedVertical: Boolean? = null
    private var cumulativeVertical = 0f
    private var activeScroller: LaneScroller? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.passage_finder_a11y_label)
        ViewCompat.setAccessibilityDelegate(this, a11y)
        // Cell pitch depends only on density, so fix it up front: the lanes clamp their
        // scroll against it whenever an item count arrives, which can precede the first
        // book list.
        chapterLane.pitchPx = metrics.chapterCellSize
        verseLane.pitchPx = metrics.verseCellSize
    }

    // ---- Public API ----------------------------------------------------------------

    /**
     * Installs the book list and its per-book chapter counts.
     *
     * The counts are supplied rather than looked up here because each one reaches through
     * to JSword, which on a cold module means disk reads; the launcher gathers them off
     * the main thread. Passing them in also keeps spine widths stable for the lifetime of
     * the list instead of being re-derived every frame.
     */
    fun setBooks(books: List<PassageFinderDataSource.BookInfo>, chapterCounts: IntArray) {
        require(books.size == chapterCounts.size) {
            "chapterCounts must have one entry per book (${books.size} books, ${chapterCounts.size} counts)"
        }
        this.books = books
        this.chapterCounts = chapterCounts
        loading = books.isEmpty()
        rebuildBookLane()
        invalidate()
    }

    /** Pushes a new ViewModel state and preview text into the view. */
    fun render(newState: PassageFinderUiState, newVerseText: String?) {
        val previous = state
        state = newState
        verseText = newVerseText

        chapterLane.itemCount = newState.chapterCount.coerceAtLeast(1)
        verseLane.itemCount = newState.verseCount.coerceAtLeast(1)

        // Re-center any strip whose selection moved for a reason other than its own
        // settle — a tap, a drill, or a book change resetting chapter and verse to 1.
        if (newState.selectedBookIndex != previous.selectedBookIndex) {
            recenter(bookScroll, newState.selectedBookIndex, animate = previous.visible)
        }
        if (newState.selectedChapter != previous.selectedChapter ||
            newState.selectedBookIndex != previous.selectedBookIndex
        ) {
            recenter(chapterScroll, newState.selectedChapter - 1, animate = previous.visible)
        }
        if (newState.selectedVerse != previous.selectedVerse ||
            newState.selectedChapter != previous.selectedChapter ||
            newState.selectedBookIndex != previous.selectedBookIndex
        ) {
            recenter(verseScroll, newState.selectedVerse - 1, animate = previous.visible)
        }

        bubbleAlpha.durationMs = if (newState.showPreview) BUBBLE_FADE_IN_MS else BUBBLE_FADE_OUT_MS
        invalidate()
    }

    /**
     * Shows the overlay, sliding the stack up from the bottom edge.
     *
     * Reads the theme and animation settings on every open, since the user can change
     * them between openings without the view being recreated.
     */
    fun show() {
        isDarkTheme = ScreenSettings.nightMode
        isMonochrome = CommonUtils.settings.monochromeMode
        disableAnimations = CommonUtils.settings.disableAnimations
        painter.applyTheme(isDarkTheme, isMonochrome)
        applyAnimationSetting()

        dismissing = false
        bookScrolling = false
        chapterScrolling = false
        verseRevealed = true
        chapterHeight.snapTo(metrics.chapterStripHeight)
        chapterAlpha.snapTo(1f)
        verseHeight.snapTo(metrics.verseStripHeight)
        verseAlpha.snapTo(1f)
        bubbleAlpha.snapTo(0f)
        chapterBorderIndex = -1
        verseBorderIndex = -1

        showAnim.snapTo(0f)
        showAnim.animateTo(1f)
        lastFrameNanos = 0L
        visibility = VISIBLE
        invalidate()
    }

    /** Starts the exit animation; the view hides itself once it finishes. */
    fun hide() {
        if (dismissing) return
        dismissing = true
        cancelScrolls()
        showAnim.animateTo(0f)
        if (disableAnimations) finishHide()
        invalidate()
    }

    /** True while the overlay is on screen, including during the exit animation. */
    val isShowing: Boolean
        get() = visibility == VISIBLE && !dismissing

    private fun finishHide() {
        dismissing = false
        // INVISIBLE rather than GONE: a GONE view is skipped during measurement, so every
        // open and close would force a fresh layout pass over the whole DrawerLayout.
        // INVISIBLE keeps the overlay laid out and costs only a skipped draw.
        visibility = INVISIBLE
    }

    private fun applyAnimationSetting() {
        showAnim.snapping = disableAnimations
        chapterHeight.snapping = disableAnimations
        chapterAlpha.snapping = disableAnimations
        verseHeight.snapping = disableAnimations
        verseAlpha.snapping = disableAnimations
        bubbleAlpha.snapping = disableAnimations
        chapterBorder.snapping = disableAnimations
        verseBorder.snapping = disableAnimations
    }

    // ---- Lane setup ----------------------------------------------------------------

    /**
     * Derives spine base widths from chapter counts: a one-chapter book gets the minimum
     * width and the longest book the maximum, so the strip reads like a shelf where thick
     * books are thick.
     */
    private fun rebuildBookLane() {
        val n = books.size
        val maxChapters = (chapterCounts.maxOrNull() ?: 1).coerceAtLeast(1)
        val widths = FloatArray(n)
        for (i in 0 until n) {
            val fraction = (chapterCounts[i].toFloat() / maxChapters).coerceIn(0f, 1f)
            widths[i] = lerp(metrics.spineBaseMinWidth, metrics.spineBaseMaxWidth, fraction)
        }
        bookLane.gapPx = metrics.spineGap
        bookLane.lensRadiusPx = metrics.bookLensRadius
        bookLane.lensWidthPx = metrics.spineLensWidth
        bookLane.lensFalloff = metrics.bookLensFalloff
        bookLane.setBaseWidths(widths)
        bookLane.scroll = bookLane.snapPointFor(state.selectedBookIndex)
        chapterLane.scroll = chapterLane.snapPointFor(state.selectedChapter - 1)
        verseLane.scroll = verseLane.snapPointFor(state.selectedVerse - 1)
    }

    /** Animates a lane onto [index] unless the change came from that lane's own settle. */
    private fun recenter(scroller: LaneScroller, index: Int, animate: Boolean) {
        if (index < 0) return
        if (!scroller.coordinator.shouldRecenter()) return
        val target = scroller.lane.snapPointFor(index)
        if (animate && !disableAnimations) {
            scroller.animateTo(target)
        } else {
            scroller.stop()
            scroller.lane.scroll = target
        }
    }

    private fun cancelScrolls() {
        bookScroll.stop()
        chapterScroll.stop()
        verseScroll.stop()
        bookScrolling = false
        chapterScrolling = false
    }

    // ---- Drawing -------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        // Clamp so a stalled frame cannot make the animations jump.
        lastFrameDelta = if (lastFrameNanos == 0L) 0f else {
            ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_SECONDS)
        }
        lastFrameNanos = now

        // Scroll positions are advanced before layout so the frame drawn is the frame
        // computed — otherwise every strip would render one frame behind the finger.
        var animating = advanceScrollers()
        if (advanceAnimations(lastFrameDelta)) animating = true

        if (dismissing && !showAnim.isAnimating && showAnim.value <= 0f) {
            finishHide()
            return
        }

        layoutStrips()

        canvas.save()
        canvas.translate(0f, slideOffset)

        painter.drawPanel(
            canvas, contentLeft, height - metrics.panelHeight, contentRight, height.toFloat(),
        )

        if (loading) {
            painter.drawSkeleton(canvas, contentLeft, contentRight, bookRect.bottom)
        } else {
            drawBookStrip(canvas)
        }
        drawNumberStrip(canvas, chapterLane, chapterRect, isChapterStrip = true)
        drawNumberStrip(canvas, verseLane, verseRect, isChapterStrip = false)
        drawBubble(canvas)

        canvas.restore()

        if (animating) postInvalidateOnAnimation()
    }

    /** Advances every animator by [dt]; returns true if any still needs frames. */
    private fun advanceAnimations(dt: Float): Boolean {
        var animating = false

        chapterHeight.target = if (bookScrolling) 0f else metrics.chapterStripHeight
        chapterAlpha.target = if (bookScrolling) 0f else 1f
        val hideVerse = bookScrolling || chapterScrolling || !verseRevealed
        verseHeight.target = if (hideVerse) 0f else metrics.verseStripHeight
        verseAlpha.target = if (hideVerse) 0f else 1f

        // The bubble is shown only once a chapter or verse scroll has happened, and is
        // suppressed while the user is picking a book — a book-only preview says nothing.
        bubbleAlpha.animateTo(if (state.showPreview && !bookScrolling) 1f else 0f)

        if (showAnim.advance(dt)) animating = true
        if (chapterHeight.advance(dt)) animating = true
        if (chapterAlpha.advance(dt)) animating = true
        if (verseHeight.advance(dt)) animating = true
        if (verseAlpha.advance(dt)) animating = true
        if (bubbleAlpha.advance(dt)) animating = true
        if (chapterBorder.advance(dt)) animating = true
        if (verseBorder.advance(dt)) animating = true

        return animating
    }

    /** Steps any in-flight fling or snap; returns true if any lane is still moving. */
    private fun advanceScrollers(): Boolean {
        var moving = false
        if (bookScroll.advance()) moving = true
        if (chapterScroll.advance()) moving = true
        if (verseScroll.advance()) moving = true
        return moving
    }

    /**
     * Computes the bottom-anchored stack: book strip at the bottom, then chapter, verse
     * and the bubble above it, separated by a constant gap. Collapsed strips keep their
     * gaps, so the strips above do not shift as one hides.
     */
    private fun layoutStrips() {
        val contentWidth = minOf(width.toFloat(), metrics.maxContentWidth)
        contentLeft = (width - contentWidth) / 2f
        contentRight = contentLeft + contentWidth

        slideOffset = (1f - showAnim.value) * height

        val bookBottom = height - metrics.stackBottomPadding
        bookRect.set(contentLeft, bookBottom - metrics.bookStripHeight, contentRight, bookBottom)

        val chapterBottom = bookRect.top - metrics.stripSpacing
        chapterRect.set(contentLeft, chapterBottom - chapterHeight.value, contentRight, chapterBottom)

        val verseBottom = chapterRect.top - metrics.stripSpacing
        verseRect.set(contentLeft, verseBottom - verseHeight.value, contentRight, verseBottom)
    }

    private fun drawBookStrip(canvas: Canvas) {
        if (books.isEmpty()) return
        bookLane.layout(centreX())
        canvas.save()
        canvas.clipRect(bookRect)
        val range = bookLane.visibleRange(width.toFloat())
        val centred = bookLane.nearestIndex()
        for (i in range) {
            // The centred spine is drawn last so it overlaps its neighbours, matching
            // the z-ordering the Compose version got from `zIndex(proximity)`.
            if (i == centred) continue
            drawSpine(canvas, i)
        }
        if (centred in range) drawSpine(canvas, centred)
        canvas.restore()
    }

    private fun drawSpine(canvas: Canvas, index: Int) {
        val book = books[index]
        val isGroupStart = index > 0 && book.category != books[index - 1].category
        painter.drawBookSpine(
            canvas = canvas,
            book = book,
            left = bookLane.lefts[index],
            width = bookLane.widths[index],
            bottom = bookRect.bottom,
            proximity = bookLane.proximities[index],
            sizeFactor = bookLane.sizeFactors[index],
            isGroupStart = isGroupStart,
            isOpenBook = index == state.openBookIndex,
        )
    }

    private fun drawNumberStrip(
        canvas: Canvas,
        lane: UniformLane,
        rect: RectF,
        isChapterStrip: Boolean,
    ) {
        if (rect.height() <= 0.5f || lane.itemCount <= 0 || books.isEmpty()) return
        val stripAlpha = if (isChapterStrip) chapterAlpha.value else verseAlpha.value
        if (stripAlpha <= 0.01f) return

        val cellSize = if (isChapterStrip) metrics.chapterCellSize else metrics.verseCellSize
        val minScale = if (isChapterStrip) metrics.chapterMinScale else metrics.verseMinScale
        val maxScale = if (isChapterStrip) metrics.chapterMaxScale else metrics.verseMaxScale
        val radius = if (isChapterStrip) metrics.chapterLensRadius else metrics.verseLensRadius
        val textSize = if (isChapterStrip) metrics.chapterTextSize else metrics.verseTextSize
        val selected = if (isChapterStrip) state.selectedChapter else state.selectedVerse

        val centreX = centreX()
        val centreY = rect.centerY()
        val centred = lane.nearestIndex()
        updateBorderAnimation(isChapterStrip, centred)
        val borderAlpha = if (isChapterStrip) chapterBorder.value else verseBorder.value

        canvas.save()
        canvas.clipRect(rect)
        // Cells overflow their pitch when magnified, so extend the range by the widest
        // possible cell to avoid popping at the edges.
        val range = lane.visibleRange(width.toFloat(), cellSize * maxScale * metrics.cellMaxAspect)

        fun drawCell(i: Int) {
            if (i !in range) return
            val isCentred = i == centred
            val isSelected = i + 1 == selected
            val proximity = lane.proximity(i, radius)
            // Quartic falloff steepens the bell curve so the centre cell dominates
            // its immediate neighbours instead of blending into them.
            val sizeProximity = proximity * proximity * proximity * proximity
            painter.drawNumberCell(
                canvas = canvas,
                text = (i + 1).toString(),
                centreX = centreX + lane.offsetFromCentre(i),
                centreY = centreY,
                cellSize = cellSize,
                baseTextSize = textSize,
                scale = lerp(minScale, maxScale, sizeProximity),
                alpha = (0.3f + 0.7f * proximity) * stripAlpha,
                isSelected = isSelected,
                emphasised = isSelected || isCentred,
                borderAlpha = when {
                    isSelected -> 1f
                    isCentred -> borderAlpha
                    else -> 0f
                },
            )
        }

        // Draw outside-in from both sides, so every cell overlaps the one further from
        // the centre and the stack converges on the focused cell. Drawing in plain index
        // order would be right only to the left of centre; to the right each cell would
        // cover its inner neighbour, and the wider three-digit plates make that obvious.
        val selectedIndex = selected - 1
        for (distance in maxOf(centred - range.first, range.last - centred) downTo 1) {
            for (i in intArrayOf(centred - distance, centred + distance)) {
                if (i != selectedIndex) drawCell(i)
            }
        }
        // Committed selection, then the visual centre on top of everything.
        if (selectedIndex != centred) drawCell(selectedIndex)
        drawCell(centred)
        canvas.restore()
    }

    /** Restarts the border fade whenever the centred cell of a strip changes. */
    private fun updateBorderAnimation(isChapterStrip: Boolean, centred: Int) {
        if (isChapterStrip) {
            if (centred != chapterBorderIndex) {
                chapterBorderIndex = centred
                chapterBorder.snapTo(0f)
                chapterBorder.animateTo(1f)
            }
        } else if (centred != verseBorderIndex) {
            verseBorderIndex = centred
            verseBorder.snapTo(0f)
            verseBorder.animateTo(1f)
        }
    }

    private fun drawBubble(canvas: Canvas) {
        val alpha = bubbleAlpha.value
        if (alpha <= 0.01f) {
            bubbleRect.setEmpty()
            return
        }
        val book = books.getOrNull(state.selectedBookIndex) ?: return
        val reference = "${book.shortName} ${state.selectedChapter}:${state.selectedVerse}"
        val maxWidth = minOf(metrics.bubbleMaxWidth, contentRight - contentLeft)
        // The bubble sits a gap above the verse strip, plus its own bottom padding —
        // matching the Compose column's spacing plus the bubble's own bottom padding.
        val bottom = verseRect.top - metrics.stripSpacing * 2f
        bubble.draw(canvas, reference, verseText, centreX(), bottom, maxWidth, alpha, bubbleRect)
    }

    private fun centreX(): Float = (contentLeft + contentRight) / 2f

    // ---- Touch ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        val x = event.x
        // Undo the slide so hit-testing works against the laid-out positions.
        val y = event.y - slideOffset

        var tracker = velocityTracker
        if (tracker == null) {
            tracker = VelocityTracker.obtain()
            velocityTracker = tracker
        }
        tracker.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastX = x
                lockedVertical = null
                cumulativeVertical = 0f
                activeScroller = bandAt(y)
                val onBubble = !bubbleRect.isEmpty && bubbleRect.contains(x, y)
                // Anything clear of the strips and the bubble belongs to the reader
                // showing through above them, so the touch is handed straight to it. That
                // way a finger put down there stops the glide and scrolls the text in one
                // motion, instead of the overlay swallowing it.
                readerGesture = activeScroller == null && !onBubble && onReaderTouch != null
                if (readerGesture) {
                    // The real press is what halts the fling — the same thing that
                    // happens whenever a finger lands on a scrolling page.
                    onReaderTouch?.invoke(event)
                } else {
                    // Grabbing a moving strip stops it, as with any scrollable.
                    activeScroller?.stop()
                    // A finger on the widget itself hands control over: it stops
                    // following the reader, and the reader stops gliding beneath it.
                    onUserInteracted?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (readerGesture) {
                    onReaderTouch?.invoke(event)
                    return true
                }
                val dx = x - lastX
                lastX = x
                if (lockedVertical == null) {
                    val totalX = abs(x - downX)
                    val totalY = abs(y - downY)
                    if (totalX > touchSlop || totalY > touchSlop) {
                        // Locking vertical whenever it dominates makes the upward drill
                        // gesture easy to trigger from within a horizontal strip.
                        lockedVertical = totalY > totalX
                        if (lockedVertical == true) cumulativeVertical = 0f
                    }
                }
                when (lockedVertical) {
                    true -> handleVerticalDrag(y)
                    false -> handleHorizontalDrag(dx)
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (readerGesture) {
                    val moved = abs(x - downX) > touchSlop || abs(y - downY) > touchSlop
                    if (moved) {
                        // A real lift, so the reader flings on from here as usual.
                        onReaderTouch?.invoke(event)
                    } else {
                        // A tap, not a scroll: cancel rather than lift, so the page sees
                        // no click — it must not select a verse on the way out — and then
                        // dismiss, which is what a tap outside the strips has always done.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        onReaderTouch?.invoke(cancel)
                        cancel.recycle()
                        onDismiss?.invoke()
                    }
                    endGesture()
                    return true
                }
                val wasTap = lockedVertical == null &&
                    abs(x - downX) <= touchSlop && abs(y - downY) <= touchSlop
                if (wasTap) {
                    handleTap(x, y)
                } else if (lockedVertical == false) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    flingActiveLane(tracker.xVelocity)
                }
                endGesture()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (readerGesture) {
                    onReaderTouch?.invoke(event)
                    endGesture()
                    return true
                }
                if (lockedVertical == false) activeScroller?.snap()
                endGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun endGesture() {
        velocityTracker?.recycle()
        velocityTracker = null
        activeScroller = null
        readerGesture = false
        lockedVertical = null
        invalidate()
    }

    /**
     * Accumulates vertical travel and fires a level change each time the threshold is
     * crossed, resetting afterwards so one long swipe can drill through several levels.
     */
    private fun handleVerticalDrag(y: Float) {
        cumulativeVertical = y - downY
        if (cumulativeVertical < -metrics.drillThreshold) {
            onDrillDown?.invoke()
            downY = y
            cumulativeVertical = 0f
        } else if (cumulativeVertical > metrics.drillThreshold) {
            val handled = onDrillUp?.invoke() ?: false
            if (!handled) onDismiss?.invoke()
            downY = y
            cumulativeVertical = 0f
        }
    }

    private fun handleHorizontalDrag(dx: Float) {
        val scroller = activeScroller ?: return
        scroller.dragBy(-dx)
        markScrolling(scroller, true)
        onCentreMaybeChanged(scroller)
        invalidate()
    }

    /**
     * Maps a y coordinate onto the strip that owns it, for both drags and taps.
     *
     * Each strip claims a band reaching half way into the gaps on either side, so the
     * stack has no dead rows between the strips — a finger aimed at a spine but landing a
     * few dp high still scrolls the books rather than doing nothing, or worse, dismissing.
     * The book strip's band runs to the bottom edge, absorbing the padding below it.
     */
    private fun bandAt(y: Float): LaneScroller? {
        val margin = metrics.stripSpacing / 2f
        if (y >= bookRect.top - margin) return bookScroll
        if (chapterRect.height() > 0.5f && y >= chapterRect.top - margin) return chapterScroll
        if (verseRect.height() > 0.5f && y >= verseRect.top - margin) return verseScroll
        return null
    }

    private fun flingActiveLane(velocityX: Float) {
        val scroller = activeScroller ?: return
        if (abs(velocityX) >= minFlingVelocity) {
            scroller.fling(-velocityX)
        } else {
            scroller.snap()
        }
        invalidate()
    }

    private fun markScrolling(scroller: LaneScroller, scrolling: Boolean) {
        when (scroller) {
            bookScroll -> bookScrolling = scrolling
            chapterScroll -> chapterScrolling = scrolling
        }
    }

    /**
     * Fires a haptic tick whenever the centred item changes while a strip is moving.
     *
     * The Compose implementation only ticked once, when a scroll finally settled, which
     * left [HapticController]'s throttle — added expressly to keep fast flings from
     * buzzing — with nothing to throttle. Ticking per boundary is what a physical picker
     * does and is what the throttle was written for.
     */
    private fun onCentreMaybeChanged(scroller: LaneScroller) {
        val index = scroller.lane.nearestIndex()
        if (index == scroller.lastHapticIndex) return
        scroller.lastHapticIndex = index
        when (scroller) {
            bookScroll -> haptics.onBookBoundary()
            chapterScroll -> haptics.onChapterBoundary()
            verseScroll -> haptics.onVerseBoundary()
        }
    }

    /**
     * Commits the settled selection back to the state layer.
     *
     * Only ever reached for user-driven scrolls: a programmatic re-center ends without
     * calling this, so a re-center can never be mistaken for the user choosing something.
     */
    private fun onLaneSettled(scroller: LaneScroller) {
        markScrolling(scroller, false)
        val index = scroller.lane.nearestIndex()
        // Suppress the re-center only when this settle will actually move the selection.
        // Marking unconditionally would leave the flag set after a scroll that drifted
        // back onto the item it started on, and the next genuine external change — a tap,
        // say — would then find its re-center already suppressed.
        val changed = when (scroller) {
            bookScroll -> index != state.selectedBookIndex
            chapterScroll -> index + 1 != state.selectedChapter
            else -> index + 1 != state.selectedVerse
        }
        if (changed) scroller.coordinator.markScrollSettled()
        when (scroller) {
            bookScroll -> {
                // A different book means the verse strip stays hidden until the user
                // picks a chapter, rather than pre-committing to verse 1 of the new book.
                if (changed) verseRevealed = false
                onBookSelected?.invoke(index)
            }
            chapterScroll -> {
                verseRevealed = true
                onChapterSelected?.invoke(index + 1)
            }
            verseScroll -> onVerseSelected?.invoke(index + 1)
        }
        invalidate()
    }

    /**
     * Routes a tap.
     *
     * A tap that lands anywhere in a strip's band always picks that strip's nearest item;
     * it can never fall through to dismissing the overlay. That mattered: spines are
     * separated by a 2dp gap, and a tap landing in one used to miss every branch below
     * and close the widget, so repeatedly tapping books shut the finder after a few
     * attempts. The bands also absorb the gaps between strips, so the whole stack is live
     * and only a tap clearly outside it dismisses.
     */
    private fun handleTap(x: Float, y: Float) {
        if (!bubbleRect.isEmpty && bubbleRect.contains(x, y)) {
            onConfirm?.invoke()
            return
        }
        when (bandAt(y)) {
            bookScroll -> {
                if (loading || books.isEmpty()) return
                val index = bookIndexAt(x)
                if (index != state.selectedBookIndex) verseRevealed = false
                bookScroll.animateTo(bookLane.snapPointFor(index))
                onBookSelected?.invoke(index)
                onDrillDown?.invoke()
            }
            chapterScroll -> {
                val index = cellIndexAt(chapterLane, x)
                chapterScroll.animateTo(chapterLane.snapPointFor(index))
                verseRevealed = true
                onChapterSelected?.invoke(index + 1)
                onDrillDown?.invoke()
            }
            verseScroll -> {
                val index = cellIndexAt(verseLane, x)
                val alreadySelected = index + 1 == state.selectedVerse
                verseScroll.animateTo(verseLane.snapPointFor(index))
                onVerseSelected?.invoke(index + 1)
                // Tapping the verse that is already selected commits it.
                if (alreadySelected) onConfirm?.invoke()
            }
            // Clear of the strips — dismiss, as the full-screen target behind the strips
            // did in the Compose implementation.
            else -> onDismiss?.invoke()
        }
    }

    /**
     * The spine nearest [x]. Falls back to the closest spine centre rather than reporting
     * a miss, so the gap between two spines belongs to whichever is nearer.
     */
    private fun bookIndexAt(x: Float): Int {
        val range = bookLane.visibleRange(width.toFloat())
        if (range.isEmpty()) return state.selectedBookIndex
        var nearest = range.first
        var nearestDistance = Float.MAX_VALUE
        for (i in range) {
            val left = bookLane.lefts[i]
            val right = left + bookLane.widths[i]
            if (x in left..right) return i
            val distance = minOf(abs(x - left), abs(x - right))
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = i
            }
        }
        return nearest
    }

    /**
     * The numeric cell nearest [x], clamped to the lane. Cells tile their strip without
     * gaps, so the nearest centre is always the item the user meant.
     */
    private fun cellIndexAt(lane: UniformLane, x: Float): Int =
        lane.nearestIndex(lane.scroll + (x - centreX()))

    // ---- Accessibility -------------------------------------------------------------

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * The currently visible items, as virtual accessibility nodes.
     *
     * Rebuilt per query rather than cached: accessibility services only ask while an
     * assistive technology is actually running, so the allocation never lands on a
     * normal frame.
     */
    internal fun accessibilityNodes(): List<A11yNode> {
        if (!isShowing || books.isEmpty()) return emptyList()
        val nodes = ArrayList<A11yNode>()
        val offset = slideOffset.toInt()

        bookLane.layout(centreX())
        for (i in bookLane.visibleRange(width.toFloat())) {
            val left = bookLane.lefts[i]
            val top = bookRect.bottom - metrics.spineMaxHeight
            nodes.add(
                A11yNode(
                    id = PassageFinderA11yHelper.ID_BOOK_BASE + i,
                    label = books[i].longName,
                    bounds = Rect(
                        left.toInt(), (top + offset).toInt(),
                        (left + bookLane.widths[i]).toInt(), (bookRect.bottom + offset).toInt(),
                    ),
                    target = A11yTarget.Book(i),
                    selected = i == state.selectedBookIndex,
                ),
            )
        }

        addCellNodes(
            nodes, chapterLane, chapterRect, metrics.chapterCellSize, metrics.chapterMaxScale,
            PassageFinderA11yHelper.ID_CHAPTER_BASE, state.selectedChapter, offset,
        ) { number -> context.getString(R.string.passage_finder_a11y_chapter, number) }

        addCellNodes(
            nodes, verseLane, verseRect, metrics.verseCellSize, metrics.verseMaxScale,
            PassageFinderA11yHelper.ID_VERSE_BASE, state.selectedVerse, offset,
        ) { number -> context.getString(R.string.passage_finder_a11y_verse, number) }

        if (!bubbleRect.isEmpty) {
            val book = books.getOrNull(state.selectedBookIndex)
            if (book != null) {
                val reference = "${book.shortName} ${state.selectedChapter}:${state.selectedVerse}"
                nodes.add(
                    A11yNode(
                        id = PassageFinderA11yHelper.ID_CONFIRM,
                        label = context.getString(R.string.passage_finder_a11y_go_to, reference),
                        bounds = Rect(
                            bubbleRect.left.toInt(), (bubbleRect.top + offset).toInt(),
                            bubbleRect.right.toInt(), (bubbleRect.bottom + offset).toInt(),
                        ),
                        target = A11yTarget.Confirm,
                        selected = false,
                    ),
                )
            }
        }
        return nodes
    }

    private inline fun addCellNodes(
        nodes: MutableList<A11yNode>,
        lane: UniformLane,
        rect: RectF,
        cellSize: Float,
        maxScale: Float,
        idBase: Int,
        selectedNumber: Int,
        offset: Int,
        label: (Int) -> String,
    ) {
        if (rect.height() <= 0.5f || lane.itemCount <= 0) return
        // Half the widest a cell can be drawn, so a three-digit plate's node still covers
        // the whole plate rather than just its square core.
        val half = cellSize * maxScale * metrics.cellMaxAspect / 2f
        val centre = centreX()
        for (i in lane.visibleRange(width.toFloat(), half)) {
            val x = centre + lane.offsetFromCentre(i)
            nodes.add(
                A11yNode(
                    id = idBase + i + 1,
                    label = label(i + 1),
                    bounds = Rect(
                        (x - half).toInt(), (rect.top + offset).toInt(),
                        (x + half).toInt(), (rect.bottom + offset).toInt(),
                    ),
                    target = if (idBase == PassageFinderA11yHelper.ID_CHAPTER_BASE) {
                        A11yTarget.Chapter(i + 1)
                    } else {
                        A11yTarget.Verse(i + 1)
                    },
                    selected = i + 1 == selectedNumber,
                ),
            )
        }
    }

    /** Applies an accessibility activation of a virtual node. */
    internal fun onAccessibilityAction(target: A11yTarget) {
        when (target) {
            is A11yTarget.Book -> {
                bookScroll.animateTo(bookLane.snapPointFor(target.index))
                if (target.index != state.selectedBookIndex) verseRevealed = false
                onBookSelected?.invoke(target.index)
                onDrillDown?.invoke()
            }
            is A11yTarget.Chapter -> {
                chapterScroll.animateTo(chapterLane.snapPointFor(target.number - 1))
                verseRevealed = true
                onChapterSelected?.invoke(target.number)
                onDrillDown?.invoke()
            }
            is A11yTarget.Verse -> {
                verseScroll.animateTo(verseLane.snapPointFor(target.number - 1))
                onVerseSelected?.invoke(target.number)
            }
            A11yTarget.Confirm -> onConfirm?.invoke()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelScrolls()
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---- Lane scrolling ------------------------------------------------------------

    /**
     * Fling, snap and programmatic re-centring for one lane.
     *
     * The fling runs in screen pixels — that is the space the user's velocity is measured
     * in — and each frame's screen delta is converted into base space through the lane's
     * local scale. On the book strip that scale varies with magnification, so integrating
     * the conversion frame by frame is what keeps a fling over thick and thin books
     * tracking the finger's initial throw.
     */
    private inner class LaneScroller(
        val lane: StripLane,
        /** Fling velocities are multiplied by this before being handed to the scroller. */
        private val flingVelocityScale: Float = 1f,
    ) {
        val coordinator = ScrollCoordinator()
        private val scroller = OverScroller(context)
        private var lastScrollerX = 0
        private var flinging = false

        private val snapTween = Tween1D(0f, durationMs = SNAP_DURATION_MS)
        private var snapping = false
        private var programmatic = false

        /** Last index that fired a haptic tick, so each boundary ticks once. */
        var lastHapticIndex = -1

        fun dragBy(deltaScreenPx: Float) {
            stopAnimations()
            val scale = lane.localScale.let { if (it > 0.01f) it else 1f }
            lane.scroll = lane.clampScroll(lane.scroll + deltaScreenPx / scale)
        }

        fun fling(velocityScreenPx: Float) {
            stopAnimations()
            lastHapticIndex = lane.nearestIndex()
            lastScrollerX = 0
            scroller.fling(
                0, 0, (velocityScreenPx * flingVelocityScale).toInt(), 0,
                Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, 0, 0,
            )
            flinging = true
        }

        /** Eases to the nearest item and reports the settled selection. */
        fun snap() {
            stopAnimations()
            val target = lane.snapPointFor(lane.nearestIndex())
            if (abs(target - lane.scroll) < 0.5f || disableAnimations) {
                lane.scroll = target
                onLaneSettled(this)
                return
            }
            snapTween.snapping = disableAnimations
            snapTween.snapTo(lane.scroll)
            snapTween.animateTo(target)
            snapping = true
        }

        /** Programmatic move onto an exact position, suppressing the settle callback. */
        fun animateTo(target: Float) {
            stopAnimations()
            if (disableAnimations || abs(target - lane.scroll) < 0.5f) {
                lane.scroll = lane.clampScroll(target)
                return
            }
            coordinator.beginProgrammaticScroll()
            programmatic = true
            snapTween.snapping = false
            snapTween.snapTo(lane.scroll)
            snapTween.animateTo(lane.clampScroll(target))
            snapping = true
        }

        fun stop() {
            stopAnimations()
        }

        private fun stopAnimations() {
            if (flinging) {
                scroller.forceFinished(true)
                flinging = false
            }
            if (snapping) {
                snapping = false
                if (programmatic) {
                    programmatic = false
                    coordinator.endProgrammaticScroll()
                }
            }
        }

        /** Steps the fling or snap one frame. Returns true while still moving. */
        fun advance(): Boolean {
            if (flinging) {
                if (scroller.computeScrollOffset()) {
                    val delta = scroller.currX - lastScrollerX
                    lastScrollerX = scroller.currX
                    val scale = lane.localScale.let { if (it > 0.01f) it else 1f }
                    val next = lane.scroll + delta / scale
                    val clamped = lane.clampScroll(next)
                    lane.scroll = clamped
                    onCentreMaybeChanged(this)
                    if (clamped != next) {
                        // Hit an end — stop rather than grinding against the boundary.
                        scroller.forceFinished(true)
                        flinging = false
                        snap()
                    }
                    return true
                }
                flinging = false
                snap()
                return snapping
            }

            if (snapping) {
                // Snap timing is driven by the same frame clock as the other animators.
                val still = snapTween.advance(lastFrameDelta)
                lane.scroll = snapTween.value
                if (!programmatic) onCentreMaybeChanged(this)
                if (!still) {
                    snapping = false
                    if (programmatic) {
                        programmatic = false
                        coordinator.endProgrammaticScroll()
                    } else {
                        onLaneSettled(this)
                    }
                }
                return still
            }
            return false
        }
    }

    private companion object {
        const val SHOW_DURATION_MS = 300
        const val BUBBLE_FADE_IN_MS = 200
        const val BUBBLE_FADE_OUT_MS = 150
        const val BORDER_FADE_MS = 80
        const val SNAP_DURATION_MS = 180

        /** A frame longer than this is treated as a stall, not as elapsed animation time. */
        const val MAX_FRAME_SECONDS = 0.064f
    }
}
