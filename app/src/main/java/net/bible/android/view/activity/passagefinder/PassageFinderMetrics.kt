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

import android.util.DisplayMetrics
import android.util.TypedValue

/**
 * Every dimension of the passage finder, resolved to pixels once per density change.
 *
 * The values are ported unchanged from the Compose implementation's `dp`/`sp` constants
 * so the widget keeps its exact proportions. The three lens radii are the one exception:
 * they were already expressed in raw pixels there (they were compared against `LazyRow`
 * layout offsets, which are pixels), and are kept in raw pixels here for the same reason —
 * converting them to dp would widen the lens on high-density screens and change the look.
 */
class PassageFinderMetrics(private val displayMetrics: DisplayMetrics) {

    /** Converts [value] density-independent pixels to pixels. */
    fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, displayMetrics)

    /**
     * Converts [value] scale-independent pixels to pixels, honouring the font scale.
     *
     * Uses [TypedValue.applyDimension] rather than multiplying by `scaledDensity`, which
     * is deprecated and, from API 34, wrong: font scaling above 130% is non-linear there,
     * so a single density factor over-scales large text.
     */
    fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, displayMetrics)

    // ---- Book strip ----------------------------------------------------------------

    /** Spine width of a one-chapter book, before magnification. */
    val spineBaseMinWidth = dp(7f)

    /** Spine width of the book with the most chapters, before magnification. */
    val spineBaseMaxWidth = dp(14f)

    /** Spine width at the exact centre of the lens. */
    val spineLensWidth = dp(36f)

    /** Spine height at the centre of the lens, and the fixed height of the strip. */
    val spineMaxHeight = dp(123f)

    /** Spine height at the far edges of the lens. */
    val spineMinHeight = dp(86f)

    /** Constant gap between spines; does not magnify. */
    val spineGap = dp(2f)

    val spineCornerRadius = dp(2f)

    /**
     * How far the selected spine rises above the rest of the shelf.
     *
     * The lens is wide enough that a spine one place off centre is within a few percent
     * of the selected one's height, so magnification alone does not say which is picked.
     * Raising only the selected spine clear of its neighbours does, the way a book pulled
     * half out of a shelf stands proud of the row.
     */
    val spineFocusOvershoot = dp(12f)

    /** Height of the bar marking the book currently open in the reader. */
    val openBookMarkerHeight = dp(4f)

    val spineMinTextSize = sp(8f)
    val spineMaxTextSize = sp(16f)

    /** Lens reach for the book strip. Raw pixels — see the class note. */
    val bookLensRadius = 320f

    /**
     * Tightness of the book strip's magnification bell; see [bellFalloff].
     *
     * Raising this narrows the group of enlarged spines and so fits more books on screen;
     * lowering it spreads the magnification across the whole lens, which flattens the
     * curve towards a straight ramp.
     */
    val bookLensFalloff = 10f

    // ---- Chapter strip -------------------------------------------------------------

    /** Layout pitch of a chapter cell. Cells scale about their centre, so this is constant. */
    val chapterCellSize = dp(24f)
    val chapterMinScale = 20f / 24f
    val chapterMaxScale = 56f / 24f
    val chapterTextSize = sp(18f)

    /** Lens reach for the chapter strip. Raw pixels — see the class note. */
    val chapterLensRadius = 290f

    // ---- Verse strip ---------------------------------------------------------------

    /** Layout pitch of a verse cell. */
    val verseCellSize = dp(20f)
    val verseMinScale = 18f / 20f
    val verseMaxScale = 56f / 20f

    /**
     * 15sp rather than 16: the verse strip magnifies more than the chapter strip
     * (2.8x against 2.33x), so equal source sizes rendered unequally — 45sp against 42sp
     * in identically sized boxes. Scaling from 15sp puts both centred cells at 42sp, so
     * the two strips finally read as one control, and gives two-digit verses the same
     * breathing room chapters already had.
     */
    val verseTextSize = sp(15f)

    /** Lens reach for the verse strip. Raw pixels — see the class note. */
    val verseLensRadius = 260f

    // ---- Shared cell chrome --------------------------------------------------------

    val cellCornerRadius = dp(6f)
    val cellBorderHaloWidth = dp(4f)
    val cellBorderCoreWidth = dp(1.5f)

    /** Horizontal breathing room each side of a cell's digits, as a fraction of its height. */
    val cellTextPaddingRatio = 0.06f

    /**
     * Widest a cell may grow, as a multiple of its height, before its text shrinks instead.
     *
     * Cells widen to fit their digits rather than squeezing the glyphs, since shrinking
     * text is the worst thing to do to the number the eye is actually fixated on. The cap
     * exists only so an extreme system font scale cannot produce a cell wide enough to
     * swallow its neighbours; past it, text scaling takes over.
     */
    val cellMaxAspect = 1.5f

    // ---- Strip stack ---------------------------------------------------------------

    val bookStripHeight = spineMaxHeight
    val chapterStripHeight = dp(80f)
    val verseStripHeight = dp(60f)

    /** Vertical gap between strip levels. */
    val stripSpacing = dp(23f)

    /** Gap below the whole stack. */
    val stackBottomPadding = dp(16f)

    /** Width the panel and strips are capped at on wide screens. */
    val maxContentWidth = dp(480f)

    /** Height of the gradient panel behind the strips. */
    val panelHeight = dp(420f)

    /** Cumulative vertical travel that triggers a level change. */
    val drillThreshold = dp(50f)

    // ---- Preview bubble ------------------------------------------------------------

    val bubbleMaxWidth = dp(340f)
    val bubbleCornerRadius = dp(24f)
    val bubblePaddingHorizontal = dp(20f)
    val bubblePaddingVertical = dp(12f)
    val bubbleElevation = dp(8f)
    val bubbleReferenceTextSize = sp(18f)
    val bubbleVerseTextSize = sp(14f)

    /** Verse text is clipped past this many lines, matching the Compose `maxLines`. */
    val bubbleMaxVerseLines = 5

    // ---- Skeleton ------------------------------------------------------------------

    /** Placeholder spine width used before the book list has loaded. */
    val skeletonSpineWidth = dp(10f)
}
