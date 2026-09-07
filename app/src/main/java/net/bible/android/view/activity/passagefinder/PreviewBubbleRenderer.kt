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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil

/**
 * Draws the floating bubble showing the current verse selection.
 *
 * The verse text is laid out with [StaticLayout] rather than [Canvas.drawText], which is
 * what makes the bubble safe for AndBible's full range of translations: StaticLayout runs
 * the same line-breaking and bidirectional reordering the platform's TextView uses, so
 * Arabic, Hebrew, and Indic text shape and order correctly, and mixed left-to-right verse
 * references inside right-to-left text land the right way round.
 *
 * Building a layout allocates, so the result is cached and only rebuilt when the text or
 * the available width actually changes. The verse text arrives from a debounced flow, so
 * in practice this is a handful of rebuilds per scroll rather than one per frame.
 *
 * The cache also deliberately outlives the text it was built from: while a verse read is in
 * flight ([PreviewVerseText.Loading]) the bubble draws placeholder dots but keeps sizing
 * itself from the previous layout, so it holds still instead of collapsing and regrowing
 * around every verse tick. Only [PreviewVerseText.None] — nothing to preview at all —
 * discards it.
 */
class PreviewBubbleRenderer(
    private val metrics: PassageFinderMetrics,
    private val painter: PassageFinderPainter,
) {
    private val versePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)

    /**
     * Reused across draws: the no-argument [Paint.getFontMetrics] allocates a fresh
     * instance on every call, which is exactly the steady garbage this class avoids.
     */
    private val fontMetrics = Paint.FontMetrics()

    private var cachedLayout: StaticLayout? = null
    private var cachedText: String? = null
    private var cachedWidth = -1

    /**
     * Draws the bubble centred on [centreX] with its bottom edge at [bottom], and writes
     * the drawn bounds into [outBounds] for hit testing.
     *
     * @param maxWidth widest the bubble may become, including its padding.
     * @param alpha fade level; 0 draws nothing.
     * @param isRtl true when the host view resolved to a right-to-left layout direction,
     *   which mirrors the placeholder dots to the verse block's end edge.
     */
    fun draw(
        canvas: Canvas,
        reference: String,
        verseText: PreviewVerseText,
        centreX: Float,
        bottom: Float,
        maxWidth: Float,
        alpha: Float,
        isRtl: Boolean,
        outBounds: RectF,
    ) {
        if (alpha <= 0.01f) {
            outBounds.setEmpty()
            return
        }

        val innerMaxWidth = (maxWidth - metrics.bubblePaddingHorizontal * 2f).coerceAtLeast(1f)
        val referenceWidth = painter.measureReference(reference)
        val referenceHeight = painter.referenceLineHeight()

        // What the bubble is SIZED from and what it DRAWS part company while a read is in
        // flight: the dots are drawn, but the previous layout still sets the dimensions.
        val sizingLayout = when (verseText) {
            is PreviewVerseText.Ready -> verseLayout(verseText.text, innerMaxWidth.toInt())
            PreviewVerseText.None -> {
                discardLayout()
                null
            }
            // Neither rebuilt nor discarded. The bubble is bottom-anchored, so collapsing to
            // reference-only and regrowing would give two vertical jumps per verse tick and
            // make it visibly breathe while the user scrolls the strip; keeping the last
            // layout's height and widest line is what holds it still.
            PreviewVerseText.Loading -> cachedLayout
        }
        val drawDots = verseText == PreviewVerseText.Loading

        var verseWidth = sizingLayout?.let { widestLine(it) } ?: 0f
        var verseHeight = sizingLayout?.height?.toFloat() ?: 0f
        if (drawDots && sizingLayout == null) {
            // Nothing has ever been previewed (first open), so there is no size to hold.
            // Reserve exactly one verse line, which is what a StaticLayout line comes to
            // here: the layout is built with setIncludePad(false) and no line-spacing
            // multiplier, so its line height is simply descent - ascent.
            versePaint.textSize = metrics.bubbleVerseTextSize
            versePaint.getFontMetrics(fontMetrics)
            verseHeight = fontMetrics.descent - fontMetrics.ascent
            verseWidth = metrics.bubbleLoadingDotsWidth
        }

        val innerWidth = maxOf(referenceWidth, verseWidth).coerceAtMost(innerMaxWidth)
        val bubbleWidth = innerWidth + metrics.bubblePaddingHorizontal * 2f
        val bubbleHeight = referenceHeight + verseHeight + metrics.bubblePaddingVertical * 2f

        outBounds.set(
            centreX - bubbleWidth / 2f,
            bottom - bubbleHeight,
            centreX + bubbleWidth / 2f,
            bottom,
        )
        painter.drawBubbleBackground(canvas, outBounds, alpha)

        val textTop = outBounds.top + metrics.bubblePaddingVertical
        painter.drawBubbleReference(canvas, reference, centreX, textTop, alpha)

        val blockLeft = centreX - innerWidth / 2f
        val blockTop = textTop + referenceHeight
        if (drawDots) {
            drawLoadingDots(canvas, blockLeft, blockLeft + innerWidth, blockTop, isRtl, alpha)
        } else if (sizingLayout != null) {
            canvas.save()
            // StaticLayout draws from its own origin, so translate to the text block's
            // top-left; the layout itself handles alignment within `innerWidth`.
            canvas.translate(blockLeft, blockTop)
            versePaint.alpha = (0.85f * alpha * 255).toInt().coerceIn(0, 255)
            sizingLayout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Draws the three static dots that stand in for verse text still being read.
     *
     * Dots rather than an "…" glyph: at this size an ellipsis sits on the baseline and
     * reads as a sentence that was cut off, not as an answer that is still coming.
     *
     * They are start-aligned with the verse text block rather than centred, so the eye
     * finds them exactly where the first word will appear, [blockLeft] being where the
     * layout draws its first glyph. In a right-to-left layout that edge is [blockRight].
     */
    private fun drawLoadingDots(
        canvas: Canvas,
        blockLeft: Float,
        blockRight: Float,
        blockTop: Float,
        isRtl: Boolean,
        alpha: Float,
    ) {
        versePaint.textSize = metrics.bubbleVerseTextSize
        versePaint.getFontMetrics(fontMetrics)
        // Centre on the first line's x-height midline: its baseline, raised by a fraction
        // of the text size, so the dots sit where the body of the words will sit.
        val baseline = blockTop - fontMetrics.ascent
        val centreY = baseline - X_HEIGHT_MIDLINE_RATIO * metrics.bubbleVerseTextSize

        val rowLeft =
            if (isRtl) blockRight - metrics.bubbleLoadingDotsWidth
            else blockLeft

        // Assigning the colour resets the alpha channel, so the alpha must follow it. No
        // hue at all means light, dark and monochrome/e-ink all get grey-on-panel with no
        // branch, and 0.40 against the verse text's 0.85 keeps the dots legible but
        // clearly subordinate to the reference line above them.
        versePaint.color = Color.WHITE
        versePaint.alpha = (DOT_ALPHA_RATIO * alpha * 255f).toInt().coerceIn(0, 255)

        var dotCentreX = rowLeft + metrics.bubbleLoadingDotRadius
        repeat(metrics.bubbleLoadingDotCount) {
            canvas.drawCircle(dotCentreX, centreY, metrics.bubbleLoadingDotRadius, versePaint)
            dotCentreX += metrics.bubbleLoadingDotPitch
        }
    }

    /** Drops the cached layout; only [PreviewVerseText.None] may do this. */
    private fun discardLayout() {
        cachedLayout = null
        cachedText = null
    }

    /** Builds — or reuses — the verse text layout for the given width. */
    private fun verseLayout(text: String?, width: Int): StaticLayout? {
        if (text.isNullOrBlank() || width <= 0) {
            discardLayout()
            return null
        }
        val cached = cachedLayout
        if (cached != null && cachedText == text && cachedWidth == width) return cached

        versePaint.textSize = metrics.bubbleVerseTextSize
        versePaint.color = Color.WHITE

        val builder = StaticLayout.Builder.obtain(text, 0, text.length, versePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(metrics.bubbleMaxVerseLines)
            .setEllipsize(TextUtils.TruncateAt.END)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Justification matches the Compose bubble. Below API 26 the text simply
            // stays flush-start, which is the platform default anyway.
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        val layout = builder.build()
        cachedLayout = layout
        cachedText = text
        cachedWidth = width
        return layout
    }

    /** Width of the longest laid-out line, so the bubble hugs short verses. */
    private fun widestLine(layout: StaticLayout): Float {
        var widest = 0f
        for (line in 0 until layout.lineCount) {
            widest = maxOf(widest, layout.getLineWidth(line))
        }
        return ceil(widest.toDouble()).toFloat()
    }

    private companion object {
        /** Placeholder dot opacity, as a fraction of the bubble's own fade level. */
        const val DOT_ALPHA_RATIO = 0.40f

        /** Height of the x-height midline above the baseline, as a fraction of text size. */
        const val X_HEIGHT_MIDLINE_RATIO = 0.35f
    }
}
