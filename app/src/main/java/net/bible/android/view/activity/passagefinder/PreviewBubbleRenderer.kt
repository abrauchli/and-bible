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
 * Building a layout allocates, so the result is cached and only rebuilt when the text,
 * the available width, or the text size actually changes. The verse text arrives from a
 * debounced flow, so in practice this is a handful of rebuilds per scroll rather than one
 * per frame.
 */
class PreviewBubbleRenderer(
    private val metrics: PassageFinderMetrics,
    private val painter: PassageFinderPainter,
) {
    private val versePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)

    private var cachedLayout: StaticLayout? = null
    private var cachedText: String? = null
    private var cachedWidth = -1

    /**
     * Draws the bubble centred on [centreX] with its bottom edge at [bottom], and writes
     * the drawn bounds into [outBounds] for hit testing.
     *
     * @param maxWidth widest the bubble may become, including its padding.
     * @param alpha fade level; 0 draws nothing.
     */
    fun draw(
        canvas: Canvas,
        reference: String,
        verseText: String?,
        centreX: Float,
        bottom: Float,
        maxWidth: Float,
        alpha: Float,
        outBounds: RectF,
    ) {
        if (alpha <= 0.01f) {
            outBounds.setEmpty()
            return
        }

        val innerMaxWidth = (maxWidth - metrics.bubblePaddingHorizontal * 2f).coerceAtLeast(1f)
        val referenceWidth = painter.measureReference(reference)
        val referenceHeight = painter.referenceLineHeight()

        val layout = verseLayout(verseText, innerMaxWidth.toInt())
        val verseWidth = layout?.let { widestLine(it) } ?: 0f
        val verseHeight = layout?.height?.toFloat() ?: 0f

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

        if (layout != null) {
            canvas.save()
            // StaticLayout draws from its own origin, so translate to the text block's
            // top-left; the layout itself handles alignment within `innerWidth`.
            canvas.translate(centreX - innerWidth / 2f, textTop + referenceHeight)
            versePaint.alpha = (0.85f * alpha * 255).toInt().coerceIn(0, 255)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Builds — or reuses — the verse text layout for the given width. */
    private fun verseLayout(text: String?, width: Int): StaticLayout? {
        if (text.isNullOrBlank() || width <= 0) {
            cachedLayout = null
            cachedText = null
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
}
