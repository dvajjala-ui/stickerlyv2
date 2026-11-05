package app.stickerdock

import app.stickerdock.discover.FeedPager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedPagerTest {
    private val all = (1..25).map { "s$it" }
    private val calls = mutableListOf<Pair<Int, Int>>()

    private fun pager(source: List<String> = all, failAt: Int? = null) = FeedPager<String>(10, { it }) { offset, limit ->
        calls += offset to limit
        if (offset == failAt) error("offline")
        source.drop(offset).take(limit)
    }

    @Test fun appendsPagesUntilAShortPage() = runBlocking {
        val p = pager()
        p.refresh()
        assertEquals(all.take(10), p.state.value.items)
        assertFalse(p.state.value.end)
        p.loadMore(); p.loadMore()
        assertEquals(all, p.state.value.items)
        assertTrue(p.state.value.end, "5 < 10 means no more pages")
        p.loadMore()
        assertEquals(listOf(0 to 10, 10 to 10, 20 to 10), calls, "no request after the end")
    }

    @Test fun exactMultipleEndsOnAnEmptyPage() = runBlocking {
        val p = pager(all.take(20))
        p.refresh(); p.loadMore()
        assertFalse(p.state.value.end)
        p.loadMore()
        assertTrue(p.state.value.end)
        assertEquals(20, p.state.value.items.size)
    }

    @Test fun dropsItemsAlreadyShown() = runBlocking {
        // A new sticker published between page loads shifts the offsets: page 2 repeats the last item of page 1.
        val shifting = mutableListOf<String>().apply { addAll(all) }
        val p = FeedPager<String>(10, { it }) { offset, limit ->
            if (offset == 10) shifting.add(0, "new")
            shifting.drop(offset).take(limit)
        }
        p.refresh(); p.loadMore()
        val items = p.state.value.items
        assertEquals(items.distinct(), items)
        assertEquals(19, items.size)
    }

    @Test fun errorKeepsItemsAndRetryContinues() = runBlocking {
        var failing = true
        val p = FeedPager<String>(10, { it }) { offset, limit ->
            if (offset == 10 && failing) error("offline")
            all.drop(offset).take(limit)
        }
        p.refresh(); p.loadMore()
        assertEquals(10, p.state.value.items.size)
        assertNotNull(p.state.value.error)
        p.loadMore()
        assertEquals(10, p.state.value.items.size, "no automatic retry loop while an error is shown")
        failing = false
        p.retry()
        assertNull(p.state.value.error)
        assertEquals(20, p.state.value.items.size)
    }

    @Test fun firstPageErrorThenRetry() = runBlocking {
        var failing = true
        val p = FeedPager<String>(10, { it }) { offset, limit -> if (failing) error("offline") else all.drop(offset).take(limit) }
        p.refresh()
        assertTrue(p.state.value.loaded)
        assertTrue(p.state.value.items.isEmpty())
        assertNotNull(p.state.value.error)
        failing = false
        p.retry()
        assertEquals(10, p.state.value.items.size)
    }

    @Test fun refreshReplacesItemsAndIgnoresAStalePage() = runBlocking(Dispatchers.Default) {
        val gate = CompletableDeferred<Unit>()
        var version = "a"
        val p = FeedPager<String>(10, { it }) { offset, limit ->
            if (offset == 10 && version == "a") gate.await()
            (1..25).map { "$version$it" }.drop(offset).take(limit)
        }
        p.refresh()
        assertEquals("a1", p.state.value.items.first())
        val slow = launch { p.loadMore() }   // waits on the gate while holding the pager
        yield()
        version = "b"
        val refreshing = launch { p.refresh() }
        gate.complete(Unit)
        slow.join(); refreshing.join()
        assertEquals((1..10).map { "b$it" }, p.state.value.items, "refresh wins over a page from the old list")
        assertFalse(p.state.value.loading)
        p.loadMore()
        assertEquals((1..20).map { "b$it" }, p.state.value.items)
    }
}
