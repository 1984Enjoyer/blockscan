package ru.blockscan.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.domain.StartLimiter

@OptIn(ExperimentalCoroutinesApi::class)
class StartLimiterTest {
    @Test fun firstNPassImmediatelyNextWaitsForWindow() = runTest {
        val l = StartLimiter { testScheduler.currentTime }
        repeat(5) { l.acquire(5) }
        assertEquals(0L, testScheduler.currentTime)
        l.acquire(5)
        assertTrue("шестой запуск должен ждать окно: ${testScheduler.currentTime}", testScheduler.currentTime >= 60_000L)
    }

    @Test fun windowSlides() = runTest {
        val l = StartLimiter { testScheduler.currentTime }
        l.acquire(2); l.acquire(2)
        l.acquire(2)
        val t3 = testScheduler.currentTime
        assertTrue(t3 >= 60_000L)
        l.acquire(2)
        assertTrue("четвёртый запуск не должен ждать ещё окно: ${testScheduler.currentTime}", testScheduler.currentTime < t3 + 1_000L)
    }

    @Test fun neverExceedsLimitInAnyWindow() = runTest {
        val stamps = ArrayList<Long>()
        val l = StartLimiter { testScheduler.currentTime }
        repeat(12) { l.acquire(4); stamps += testScheduler.currentTime }
        for (i in 4 until stamps.size) assertTrue("нарушен лимит: ${stamps[i]} - ${stamps[i - 4]}", stamps[i] - stamps[i - 4] >= 60_000L)
    }
}
