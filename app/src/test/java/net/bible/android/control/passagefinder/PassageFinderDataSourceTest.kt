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

package net.bible.android.control.passagefinder

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.bible.android.control.navigation.NavigationControl
import net.bible.android.control.page.CurrentPageManager
import net.bible.android.control.page.PageControl
import org.crosswire.jsword.book.basic.AbstractPassageBook
import org.crosswire.jsword.versification.BibleBook
import org.crosswire.jsword.versification.system.Versifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PassageFinderDataSource]'s per-module book cache.
 *
 * The cache is the interesting part to test: loading a module's book list is slow enough
 * (over a hundred file reads on a cold module) that the user can switch translation while
 * it runs, and a mis-keyed entry is never corrected because nothing invalidates the cache
 * except a key mismatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PassageFinderDataSourceTest {

    private val v11n = Versifications.instance().getVersification("KJV")

    private lateinit var navigationControl: NavigationControl
    private lateinit var pageControl: PageControl
    private lateinit var dataSource: PassageFinderDataSource

    /** The module the reader currently has open; reassign to simulate a translation switch. */
    private var currentInitials: String? = "KJV"

    @Before
    fun setUp() {
        val document: AbstractPassageBook = mock()
        whenever(document.initials).thenAnswer {
            // A null module stands for "no document open", which currentDocumentKey()
            // reaches by way of an exception from the page manager.
            currentInitials ?: throw IllegalStateException("no current document")
        }
        val pageManager: CurrentPageManager = mock()
        whenever(pageManager.currentPassageDocument).thenReturn(document)

        navigationControl = mock()
        pageControl = mock()
        whenever(pageControl.currentPageManager).thenReturn(pageManager)
        whenever(navigationControl.versification).thenReturn(v11n)
        whenever(navigationControl.getAllDocumentBooksExcludingIntros())
            .thenReturn(listOf(BibleBook.GEN, BibleBook.EXOD, BibleBook.MATT))

        dataSource = PassageFinderDataSource(navigationControl, pageControl)
    }

    @Test
    fun `loadBooks caches the list for the module it was loaded from`() = runTest {
        val loaded = dataSource.loadBooks()

        assertEquals(3, loaded.books.size)
        assertEquals(BibleBook.GEN, loaded.books[0].book)
        // KJV chapter counts, resolved from the versification rather than stubbed.
        assertEquals(50, loaded.chapterCounts[0])
        assertEquals(loaded, dataSource.cachedBooks())
    }

    @Test
    fun `cachedBooks misses for a module the cache was not built from`() = runTest {
        dataSource.loadBooks()

        currentInitials = "ESV"

        assertNull(dataSource.cachedBooks())
    }

    /**
     * The regression this cache-keying exists to prevent: a translation switch landing
     * while the load is still reading off disk must not file the old module's books under
     * the new module's name.
     */
    @Test
    fun `loadBooks does not cache a list the document changed out from under`() = runTest {
        whenever(navigationControl.getAllDocumentBooksExcludingIntros()).thenAnswer {
            // Stand in for the user switching translation part-way through the disk reads.
            currentInitials = "ESV"
            listOf(BibleBook.GEN, BibleBook.EXOD, BibleBook.MATT)
        }

        val loaded = dataSource.loadBooks()

        // The caller still gets the list it asked for...
        assertNotNull(loaded)
        assertEquals(3, loaded.books.size)
        // ...but nothing was cached, so ESV cannot inherit KJV's books.
        assertNull(dataSource.cachedBooks())

        // And switching back must not resurrect a stale entry either.
        currentInitials = "KJV"
        assertNull(dataSource.cachedBooks())
    }

    @Test
    fun `loadBooks does not cache when no document is open`() = runTest {
        currentInitials = null

        val loaded = dataSource.loadBooks()

        assertEquals(3, loaded.books.size)
        currentInitials = "KJV"
        assertNull(dataSource.cachedBooks())
    }

    /**
     * The warm-up on resume and a tap that misses the cache both call [loadBooks], and the
     * call is slow by nature, so the window for a second caller to arrive mid-scan is wide.
     * Letting both through would run the whole file scan twice for the same answer.
     */
    @Test
    fun `concurrent loads scan the module only once`() = runTest {
        val scans = AtomicInteger(0)
        whenever(navigationControl.getAllDocumentBooksExcludingIntros()).thenAnswer {
            scans.incrementAndGet()
            // Hold the scan open long enough that the second caller is genuinely inside
            // loadBooks() while the first is still working — without this the first can
            // finish before the second starts and the test would pass without proving
            // anything about concurrency.
            Thread.sleep(150)
            listOf(BibleBook.GEN, BibleBook.EXOD, BibleBook.MATT)
        }

        val first = async { dataSource.loadBooks() }
        val second = async { dataSource.loadBooks() }
        val a = first.await()
        val b = second.await()

        assertEquals(1, scans.get())
        // The waiter gets the same cached instance rather than a second equal copy.
        assertSame(a, b)
    }

    @Test
    fun `loadBooks reuses the cached list instead of reloading`() = runTest {
        val first = dataSource.loadBooks()
        val second = dataSource.loadBooks()

        // Same instance: the second call short-circuits on the cache rather than
        // repeating the disk work.
        assertEquals(first, second)
    }
}
