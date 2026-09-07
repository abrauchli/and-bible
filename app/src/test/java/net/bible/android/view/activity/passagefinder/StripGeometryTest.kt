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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for the passage finder's strip geometry.
 *
 * The centring tests matter most. The Compose implementation could not place a spine on
 * the viewport centre in one pass: item layout widths grew with proximity to the centre,
 * so a spine's position depended on its width, which depended on its position. It papered
 * over this with a loop that re-measured and re-scrolled up to eight times per open, and
 * still sometimes settled with the wrong book nearest the centre. [LensLane] resolves the
 * whole strip analytically instead, so "centred" must hold exactly on the first pass —
 * that is the property these tests pin down.
 */
class StripGeometryTest {

    /** Viewport centre used throughout; the exact value is irrelevant to the assertions. */
    private val centre = 540f

    private val gap = 6f

    /** Chapter counts roughly shaped like a real Bible: a few long books, many short ones. */
    private val chapterCounts = intArrayOf(
        50, 40, 27, 36, 34, 24, 21, 4, 31, 24, 22, 25, 29, 36, 10, 13, 10, 42, 150, 31,
        12, 8, 66, 52, 5, 48, 12, 14, 3, 9, 1, 4, 7, 3, 3, 3, 2, 14, 4, 28, 16, 24, 21,
        28, 16, 16, 13, 6, 6, 4, 4, 5, 3, 6, 4, 3, 1, 13, 5, 5, 3, 5, 1, 1, 1, 22,
    )

    private fun lensLane(counts: IntArray = chapterCounts): LensLane = LensLane().apply {
        gapPx = gap
        lensRadiusPx = 320f
        lensWidthPx = 108f
        val maxChapters = counts.max()
        setBaseWidths(
            FloatArray(counts.size) { i ->
                // 21px for a one-chapter book up to 42px for the longest, matching the
                // proportional spine widths the view derives from chapter counts.
                21f + 21f * (counts[i].toFloat() / maxChapters)
            },
        )
    }

    // ---- LensLane centring ---------------------------------------------------------

    @Test
    fun `snapping to any book centres it exactly on the first layout pass`() {
        val lane = lensLane()
        for (i in 0 until lane.itemCount) {
            lane.scroll = lane.snapPointFor(i)
            lane.layout(centre)
            val itemCentre = lane.lefts[i] + lane.widths[i] / 2f
            assertEquals(
                "book $i must land on the viewport centre with no iteration",
                centre.toDouble(), itemCentre.toDouble(), 0.01,
            )
        }
    }

    @Test
    fun `the centred book is also the nearest book to the centre`() {
        // The Compose version's failure mode was a neighbouring spine ending up closer to
        // the centre than the intended one, because growth pushed the target aside.
        val lane = lensLane()
        for (i in 0 until lane.itemCount) {
            lane.scroll = lane.snapPointFor(i)
            lane.layout(centre)
            var nearest = 0
            var nearestDistance = Float.MAX_VALUE
            for (j in 0 until lane.itemCount) {
                val d = abs(lane.lefts[j] + lane.widths[j] / 2f - centre)
                if (d < nearestDistance) {
                    nearestDistance = d
                    nearest = j
                }
            }
            assertEquals("book $i should be the closest spine to the centre", i, nearest)
        }
    }

    @Test
    fun `layout is a pure function of scroll`() {
        // No frame-to-frame feedback: laying out twice at the same scroll, and laying out
        // again after a detour, must give byte-identical results. This is what lets the
        // very first frame be correct.
        val lane = lensLane()
        lane.scroll = lane.snapPointFor(30)
        lane.layout(centre)
        val firstLefts = lane.lefts.copyOf()
        val firstWidths = lane.widths.copyOf()

        lane.layout(centre)
        assertTrue("repeated layout must be identical", lane.lefts.contentEquals(firstLefts))

        lane.scroll = lane.snapPointFor(3)
        lane.layout(centre)
        lane.scroll = lane.snapPointFor(30)
        lane.layout(centre)
        assertTrue("layout must not depend on history", lane.lefts.contentEquals(firstLefts))
        assertTrue(lane.widths.contentEquals(firstWidths))
    }

    @Test
    fun `positions stay continuous as the focus crosses a gap between spines`() {
        // Base and screen space are related piecewise, with a stretched item interior and
        // a one-to-one gap. If the gap were magnified along with the item, the strip would
        // visibly jump sideways each time the focus crossed from one spine to the next.
        val lane = lensLane()
        val start = lane.snapPointFor(20)
        val end = lane.snapPointFor(21)
        var previous = Float.NaN
        val steps = 400
        var maxJump = 0f
        for (step in 0..steps) {
            lane.scroll = start + (end - start) * step / steps
            lane.layout(centre)
            val position = lane.lefts[20]
            if (!previous.isNaN()) maxJump = maxOf(maxJump, abs(position - previous))
            previous = position
        }
        // One step covers well under a pixel of scroll; magnification can amplify that a
        // few times over, but nothing close to the gap-sized jump the naive mapping made.
        assertTrue("spine position jumped by $maxJump px between adjacent scroll values", maxJump < 2f)
    }

    @Test
    fun `spines magnify towards the centre and stay at base width far away`() {
        val lane = lensLane()
        lane.scroll = lane.snapPointFor(33)
        lane.layout(centre)
        assertTrue(
            "the centred spine should reach the lens width",
            lane.widths[33] > lane.widths[32],
        )
        assertTrue(lane.widths[32] > lane.widths[31])
        assertEquals(
            "the centred spine should be at full lens width",
            108.0, lane.widths[33].toDouble(), 0.01,
        )
        assertEquals(
            "a spine well outside the lens should keep its base width",
            0.0, lane.proximities[0].toDouble(), 0.0001,
        )
    }

    @Test
    fun `a tighter lens falloff fits more books on screen`() {
        // The user's complaint was that the strip looked linear and showed too few books.
        // Tightening the bell must leave more of them at base width, which is what buys
        // the extra spines — while the centred spine keeps its full size.
        val viewport = 1080f
        fun visibleCount(falloff: Float): Int {
            val lane = lensLane().apply { lensFalloff = falloff }
            lane.scroll = lane.snapPointFor(33)
            lane.layout(centre)
            return lane.visibleRange(viewport).count()
        }
        assertTrue(
            "a tighter bell should fit at least as many books",
            visibleCount(16f) >= visibleCount(4f),
        )

        val tight = lensLane().apply { lensFalloff = 16f }
        tight.scroll = tight.snapPointFor(33)
        tight.layout(centre)
        assertEquals(
            "the centred spine must still reach full lens width",
            108.0, tight.widths[33].toDouble(), 0.01,
        )
    }

    @Test
    fun `size follows the bell while fading stays linear`() {
        // Legibility and size are deliberately decoupled: a spine well out along the lens
        // should still be readable (non-zero proximity) while already back at base width.
        val lane = lensLane()
        lane.scroll = lane.snapPointFor(33)
        lane.layout(centre)
        val far = 33 - 6
        assertTrue("a distant spine should still fade in", lane.proximities[far] > 0f)
        assertTrue(
            "but should have given up nearly all its magnification",
            lane.sizeFactors[far] < lane.proximities[far],
        )
        assertEquals(1f, lane.sizeFactors[33])
        assertEquals(1f, lane.proximities[33])
    }

    @Test
    fun `local scale reflects magnification so drags track the finger`() {
        val lane = lensLane()
        lane.scroll = lane.snapPointFor(33)
        lane.layout(centre)
        assertTrue(
            "a magnified spine must consume more screen pixels per unit of scroll",
            lane.localScale > 2f,
        )
    }

    @Test
    fun `scroll clamps so the first and last books can each reach the centre`() {
        val lane = lensLane()
        assertEquals(lane.snapPointFor(0).toDouble(), lane.minScroll.toDouble(), 0.001)
        assertEquals(
            lane.snapPointFor(lane.itemCount - 1).toDouble(), lane.maxScroll.toDouble(), 0.001,
        )
        assertEquals(lane.minScroll, lane.clampScroll(-10_000f))
        assertEquals(lane.maxScroll, lane.clampScroll(10_000f))
    }

    @Test
    fun `nearestIndex snaps to the closest book`() {
        val lane = lensLane()
        for (i in 0 until lane.itemCount) {
            assertEquals(i, lane.nearestIndex(lane.snapPointFor(i)))
        }
        // Just past a snap point still belongs to that book.
        assertEquals(5, lane.nearestIndex(lane.snapPointFor(5) + 1f))
    }

    @Test
    fun `an empty lane is inert rather than throwing`() {
        val lane = LensLane().apply { setBaseWidths(FloatArray(0)) }
        assertEquals(0, lane.itemCount)
        assertEquals(0, lane.nearestIndex(5f))
        assertEquals(0f, lane.snapPointFor(3))
        lane.layout(centre) // must not throw
        assertTrue(lane.visibleRange(1080f).isEmpty())
    }

    @Test
    fun `a single book lane pins the scroll to that book`() {
        val lane = lensLane(intArrayOf(1))
        assertEquals(1, lane.itemCount)
        assertEquals(lane.minScroll, lane.maxScroll)
        lane.scroll = lane.clampScroll(500f)
        lane.layout(centre)
        assertEquals(centre.toDouble(), (lane.lefts[0] + lane.widths[0] / 2f).toDouble(), 0.01)
    }

    @Test
    fun `visibleRange covers the centre and excludes far-off books`() {
        val lane = lensLane()
        lane.scroll = lane.snapPointFor(33)
        lane.layout(centre)
        val range = lane.visibleRange(1080f)
        assertTrue("the centred spine must be visible", 33 in range)
        assertTrue("distant spines should be culled", range.first > 0 || range.last < lane.itemCount - 1)
    }

    // ---- UniformLane ---------------------------------------------------------------

    @Test
    fun `uniform lane centres the selected cell`() {
        val lane = UniformLane().apply {
            itemCount = 150
            pitchPx = 72f
        }
        for (i in listOf(0, 1, 74, 149)) {
            lane.scroll = lane.snapPointFor(i)
            assertEquals(0f, lane.offsetFromCentre(i))
            assertEquals(1f, lane.proximity(i, 290f))
            assertEquals(i, lane.nearestIndex())
        }
    }

    @Test
    fun `uniform lane proximity falls to zero at the lens radius`() {
        val lane = UniformLane().apply {
            itemCount = 100
            pitchPx = 60f
        }
        lane.scroll = lane.snapPointFor(50)
        // Five cells away is 300px, beyond the 290px radius.
        assertEquals(0f, lane.proximity(55, 290f))
        assertTrue(lane.proximity(52, 290f) > 0f)
        assertTrue(
            "proximity must decrease with distance",
            lane.proximity(51, 290f) > lane.proximity(52, 290f),
        )
    }

    @Test
    fun `uniform lane clamps scroll and index to its bounds`() {
        val lane = UniformLane().apply {
            itemCount = 10
            pitchPx = 50f
        }
        assertEquals(0f, lane.minScroll)
        assertEquals(450f, lane.maxScroll)
        assertEquals(0, lane.nearestIndex(-500f))
        assertEquals(9, lane.nearestIndex(5000f))
    }

    @Test
    fun `shrinking the item count pulls the scroll back into range`() {
        // Happens whenever the user moves from a long chapter to a short one; a stale
        // scroll would otherwise leave the strip parked past its last verse.
        val lane = UniformLane().apply {
            itemCount = 150
            pitchPx = 60f
        }
        lane.scroll = lane.snapPointFor(149)
        lane.itemCount = 10
        assertTrue("scroll must be clamped when the lane shrinks", lane.scroll <= lane.maxScroll)
        assertEquals(9, lane.nearestIndex())
    }

    @Test
    fun `uniform lane visible range grows with the margin`() {
        val lane = UniformLane().apply {
            itemCount = 200
            pitchPx = 60f
        }
        lane.scroll = lane.snapPointFor(100)
        val tight = lane.visibleRange(1080f, 0f)
        val loose = lane.visibleRange(1080f, 200f)
        assertTrue(100 in tight)
        assertTrue(loose.first <= tight.first && loose.last >= tight.last)
        assertNotEquals(tight, loose)
    }

    @Test
    fun `a zero pitch cannot produce a division by zero`() {
        val lane = UniformLane().apply {
            itemCount = 5
            pitchPx = 0f
        }
        assertTrue("pitch must be forced positive", lane.pitchPx > 0f)
        assertEquals(0, lane.nearestIndex(0f))
    }
}
