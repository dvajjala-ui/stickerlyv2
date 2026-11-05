package app.stickerdock.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offset paging for Discover lists. [loadMore] appends the next page until one comes back short;
 * [refresh] starts over but keeps showing the old items until the first page arrives (pull to refresh).
 * Pages are fetched one at a time, items with an id already shown are dropped, and a page that finishes
 * after a refresh started is ignored.
 */
class FeedPager<T>(
    private val pageSize: Int,
    private val id: (T) -> String,
    private val load: suspend (offset: Int, limit: Int) -> List<T>,
) {
    data class State<T>(
        val items: List<T> = emptyList(),
        val loading: Boolean = false,
        val end: Boolean = false,
        val error: Throwable? = null,
        /** True once the first page has arrived (or failed), so "nothing here" can be told apart from "loading". */
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = _state
    private val mutex = Mutex()
    private var generation = 0

    suspend fun refresh() {
        val gen = ++generation
        mutex.withLock {
            if (gen != generation) return
            fetch(gen, before = emptyList())
        }
    }

    /** Appends the next page. Does nothing at the end, before the first page, or after an error (see [retry]). */
    suspend fun loadMore() {
        val gen = generation
        if (_state.value.end || _state.value.error != null) return
        mutex.withLock {
            val current = _state.value
            if (gen != generation || current.end || current.error != null || !current.loaded) return
            fetch(gen, before = current.items)
        }
    }

    /** Retries the page that failed (first page or a later one). */
    suspend fun retry() {
        if (_state.value.items.isEmpty()) refresh() else {
            _state.value = _state.value.copy(error = null)
            loadMore()
        }
    }

    private suspend fun fetch(gen: Int, before: List<T>) {
        _state.value = _state.value.copy(loading = true, error = null)
        val page = try {
            load(before.size, pageSize)
        } catch (e: CancellationException) {
            if (gen == generation) _state.value = _state.value.copy(loading = false)
            throw e
        } catch (e: Throwable) {
            if (gen == generation) _state.value = _state.value.copy(loading = false, error = e, loaded = true)
            return
        }
        if (gen != generation) return
        val seen = before.mapTo(HashSet()) { id(it) }
        _state.value = State(before + page.filter { seen.add(id(it)) }, loading = false, end = page.size < pageSize, loaded = true)
    }
}
