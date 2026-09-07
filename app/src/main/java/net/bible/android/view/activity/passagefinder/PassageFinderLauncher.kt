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

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.bible.android.control.navigation.NavigationControl
import net.bible.android.control.page.PageControl
import net.bible.android.control.passagefinder.PassageFinderDataSource
import net.bible.android.view.activity.page.MainBibleActivity

/**
 * Owns the passage finder overlay: creates its view, wires it to the ViewModel, and
 * routes a confirmed selection into the Bible view.
 *
 * The overlay is a plain [PassageFinderView] added to the DrawerLayout, so it floats
 * above everything including the toolbar and the navigation drawer.
 *
 * The important thing this class does is keep disk work off the tap path. Loading a
 * module's book list means asking JSword whether the module contains each book, which on
 * a cold module is well over a hundred file reads — enough to stall the tap for a visible
 * beat. So [warmUp] primes that list in the background, and [show] never waits for it:
 * on a cache hit the finder opens fully populated, and on a miss it opens on a
 * placeholder and fills in when the load lands.
 */
class PassageFinderLauncher(
    private val activity: MainBibleActivity,
    private val navigationControl: NavigationControl,
    private val pageControl: PageControl,
) {
    private var view: PassageFinderView? = null
    private var stateJob: Job? = null
    private var navigationJob: Job? = null
    private var loadJob: Job? = null

    /**
     * Invoked when the active module turns out to have no books to navigate, which can
     * only be discovered after the list has loaded. The caller should fall back to the
     * legacy passage chooser.
     */
    var onNoBooks: (() -> Unit)? = null

    private val dataSource by lazy { PassageFinderDataSource(navigationControl, pageControl) }

    /**
     * Obtain the ViewModel from the activity's ViewModelStore so its [androidx.lifecycle.viewModelScope]
     * (and the verse-text flow collector started in `init`) are cancelled when the activity is
     * destroyed. Manually instantiating the ViewModel — as an earlier draft did — would skip
     * `onCleared()` and leak the collector across activity recreation.
     *
     * The launcher itself is recreated alongside the activity, so this `lazy` only ever wraps
     * one activity's ViewModelStore.
     */
    private val viewModel: PassageFinderViewModel by lazy {
        ViewModelProvider(
            activity,
            PassageFinderViewModelFactory(dataSource),
        )[PassageFinderViewModel::class.java]
    }

    /**
     * Primes the book list for the active module in the background.
     *
     * Safe and cheap to call repeatedly: the data source caches per module, so this is a
     * no-op once the current module is warm and re-primes automatically after a document
     * switch. Call it when the reader settles, not during startup, so it competes with
     * nothing the user is waiting on.
     */
    fun warmUp() {
        // Build and attach the overlay now, so opening it never has to add a view to the
        // DrawerLayout — that lays the whole hierarchy out again, which is work this
        // widget exists to avoid doing on the path between the gesture and the first frame.
        ensureView()
        if (dataSource.cachedBooks() != null) return
        activity.lifecycleScope.launch {
            try {
                dataSource.loadBooks()
            } catch (e: Exception) {
                // Priming is best-effort; a failure here just means show() pays the cost.
                Log.d(TAG, "Passage finder warm-up failed", e)
            }
        }
    }

    /**
     * Opens the passage finder overlay.
     *
     * @return true if the overlay was shown. False only when the book list is already
     *   known and empty, in which case the caller should use the legacy chooser; when the
     *   list has yet to load this returns true and [onNoBooks] fires later if it turns
     *   out to be empty.
     */
    fun show(): Boolean {
        val cached = dataSource.cachedBooks()
        if (cached != null && cached.books.isEmpty()) return false

        val finder = ensureView()
        loadJob?.cancel()

        if (cached != null) {
            finder.setBooks(cached.books, cached.chapterCounts)
            viewModel.show(cached)
            if (!viewModel.uiState.value.visible) return false
        } else {
            // Nothing loaded yet: put the overlay on screen now with a placeholder and
            // fill it in when the list arrives, rather than making the user wait on disk.
            finder.setBooks(emptyList(), IntArray(0))
            viewModel.showLoading()
            loadJob = activity.lifecycleScope.launch {
                val loaded = try {
                    dataSource.loadBooks()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load books for passage finder", e)
                    null
                }
                if (!finder.isShowing) return@launch
                if (loaded == null || loaded.books.isEmpty()) {
                    hide()
                    onNoBooks?.invoke()
                    return@launch
                }
                finder.setBooks(loaded.books, loaded.chapterCounts)
                viewModel.show(loaded)
            }
        }

        startCollecting(finder)
        finder.show()
        return true
    }

    fun hide() {
        loadJob?.cancel()
        loadJob = null
        navigationJob?.cancel()
        navigationJob = null
        stateJob?.cancel()
        stateJob = null
        // Sync the ViewModel state with the hidden view. In normal flow the view already
        // reports a dismiss before this runs, but hide() can also be called externally
        // (e.g. on back press), so be defensive.
        viewModel.dismiss()
        view?.hide()
    }

    val isVisible: Boolean
        get() = view?.isShowing == true

    /**
     * Tells the widget the reader has scrolled to a new verse.
     *
     * Driven from the activity's existing CurrentVerseChangedEvent handler, which already
     * fires for every scroll the Bible view reports. The widget ignores it once the user
     * has touched a strip.
     */
    fun onCurrentVerseChanged() {
        if (isVisible) viewModel.followCurrentVerse()
    }

    /**
     * Replays a touch from the overlay onto the Bible view underneath.
     *
     * The overlay covers the whole screen, so the reader visible above the strips would
     * otherwise be dead to the touch. Forwarding lets a finger put down there stop the
     * glide and scroll the text in one motion — including flinging on release, since the
     * reader receives a genuine, unbroken gesture rather than a synthesised nudge.
     *
     * Events go to the Bible view's own touch handling and deliberately not through its
     * OnTouchListener: that would run the gesture detector, whose fast-fling shortcut
     * would try to reopen this very widget and whose horizontal swipes would change
     * chapter under the user.
     */
    private fun forwardTouchToReader(event: MotionEvent) {
        val overlay = view ?: return
        val reader = try {
            activity.documentViewManager.documentView
        } catch (e: Exception) {
            // The reader view may not be built yet; nothing to forward to.
            Log.d(TAG, "Could not forward touch to reader", e)
            return
        }
        val overlayLocation = IntArray(2).also { overlay.getLocationInWindow(it) }
        val readerLocation = IntArray(2).also { reader.getLocationInWindow(it) }
        val copy = MotionEvent.obtain(event)
        try {
            copy.offsetLocation(
                (overlayLocation[0] - readerLocation[0]).toFloat(),
                (overlayLocation[1] - readerLocation[1]).toFloat(),
            )
            reader.onTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    /**
     * Halts the reader's fling when the user pins a finger on the widget itself.
     *
     * Only needed for touches the overlay keeps for itself; a touch forwarded to the
     * reader stops the fling on its own, simply by being a real press.
     */
    private fun stopReaderScrolling() {
        try {
            activity.documentViewManager.documentView.stopScrolling()
        } catch (e: Exception) {
            // The reader view may not be built yet; nothing to stop in that case.
            Log.d(TAG, "Could not stop reader scrolling", e)
        }
    }

    /** Mirrors ViewModel state into the view and routes confirmed selections. */
    private fun startCollecting(finder: PassageFinderView) {
        stateJob?.cancel()
        stateJob = activity.lifecycleScope.launch {
            combine(viewModel.uiState, viewModel.previewVerseText) { state, text -> state to text }
                .collect { (state, text) -> finder.render(state, text) }
        }

        navigationJob?.cancel()
        navigationJob = activity.lifecycleScope.launch {
            viewModel.selectionConfirmed.collect { verse ->
                pageControl.currentPageManager.currentBible.setKey(verse)
                hide()
            }
        }
    }

    private fun ensureView(): PassageFinderView {
        view?.let { return it }
        val finder = PassageFinderView(activity).apply {
            visibility = View.INVISIBLE
            onDismiss = { this@PassageFinderLauncher.hide() }
            // Don't hide() here: the selectionConfirmed collector navigates and then hides
            // the view itself. Cancelling navigationJob early would race the emission and
            // silently drop the navigation.
            onConfirm = { viewModel.confirmSelection() }
            onBookSelected = { viewModel.onBookSelected(it) }
            onChapterSelected = { viewModel.onChapterSelected(it) }
            onVerseSelected = { viewModel.onVerseSelected(it) }
            onDrillDown = { viewModel.drillDown() }
            onDrillUp = { viewModel.drillUp() }
            onUserInteracted = {
                viewModel.markInteracted()
                stopReaderScrolling()
            }
            onReaderTouch = { event -> forwardTouchToReader(event) }
        }
        // Append rather than insert at a fixed index — it is raised explicitly below, so
        // the insertion position doesn't matter and appending is robust as the layout
        // evolves.
        activity.binding.drawerLayout.addView(
            finder,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // Raised once, here. Doing it on every open would call requestLayout again and
        // undo the point of attaching the view ahead of time.
        finder.bringToFront()
        view = finder
        return finder
    }

    private companion object {
        const val TAG = "PassageFinderLauncher"
    }
}
