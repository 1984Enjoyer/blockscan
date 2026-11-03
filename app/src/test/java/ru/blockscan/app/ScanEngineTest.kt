package ru.blockscan.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.blockscan.app.core.TargetSet
import ru.blockscan.app.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScanEngineTest {
    private val targets = listOf("8.8.8.1", "8.8.8.2", "8.8.8.3", "8.8.8.4")
    private fun set(list: List<String> = targets) = TargetSet(list.size.toLong(), "тест") { list.asSequence() }

    private class Env(val store: MemStore, val api: FakeApi, val engine: ScanEngine)

    private fun TestScope.env(
        api: FakeApi = FakeApi(),
        settings: AppSettings = AppSettings(requestDelayMs = 300, probeStartsPerMinute = 5, retries = 1, probeTimeoutSec = 30),
        store: MemStore = MemStore(),
    ): Env {
        val clock = { testScheduler.currentTime }
        val checker = IpChecker(api, StartLimiter(clock), { settings }, clock)
        return Env(store, api, ScanEngine(checker, store, store, { settings }, CoroutineScope(this.coroutineContext + SupervisorJob()), clock))
    }

    @Test fun processesQueueSequentiallyInOrderAndFinishes() = runTest {
        val e = env()
        val id = e.engine.createJob("8.8.8.0/29", set())
        e.engine.start(id)
        advanceUntilIdle()
        assertEquals(targets, e.api.checkTargets)
        assertEquals(JobState.FINISHED, e.store.getJob(id)!!.state)
        val c = e.store.counts(id)
        assertEquals(4, c.finished); assertEquals(0, c.queued); assertEquals(0, c.running)
        assertEquals(4, c.notBlocked)
        assertEquals(RunStatus.FINISHED, e.engine.state.value.status)
        assertEquals("Завершено", e.store.history.single().completion)
        // каждая запись содержит исходные ответы
        e.store.queryItems(id, ItemQuery()).forEach {
            assertNotNull(it.record!!.staticRaw); assertEquals(1, it.record!!.probeRaw.size)
        }
    }

    @Test fun pauseFinishesInFlightThenResumeContinues() = runTest {
        val e = env()
        val id = e.engine.createJob("x", set())
        e.engine.start(id)
        advanceTimeBy(350); runCurrent()
        e.engine.pause(id)
        advanceUntilIdle()
        val mid = e.store.counts(id)
        assertEquals(JobState.PAUSED, e.store.getJob(id)!!.state)
        assertEquals(RunStatus.PAUSED, e.engine.state.value.status)
        assertEquals(0, mid.running)
        assertTrue("после паузы остались необработанные адреса: $mid", mid.queued in 1..3)
        assertTrue(mid.finished >= 1)
        val checkedBefore = e.api.checkTargets.size
        advanceTimeBy(5 * 60_000L)
        assertEquals("во время паузы запросов быть не должно", checkedBefore, e.api.checkTargets.size)

        e.engine.resume(id)
        advanceUntilIdle()
        assertEquals(JobState.FINISHED, e.store.getJob(id)!!.state)
        assertEquals(4, e.store.counts(id).finished)
        assertEquals(targets.toSet(), e.api.checkTargets.toSet())
        assertEquals("ни один адрес не проверяется дважды", 4, e.api.checkTargets.size)
    }

    @Test fun stopCancelsAndReturnsInFlightToQueue() = runTest {
        val e = env()
        val id = e.engine.createJob("x", set())
        e.engine.start(id)
        advanceTimeBy(350); runCurrent()
        e.engine.stop(id)
        advanceUntilIdle()
        assertEquals(JobState.STOPPED, e.store.getJob(id)!!.state)
        assertEquals(RunStatus.STOPPED, e.engine.state.value.status)
        assertEquals(0, e.store.counts(id).running)
        assertTrue(e.store.counts(id).queued >= 1)
        val before = e.api.checkTargets.size
        e.engine.start(id) // остановленное задание не перезапускается
        advanceUntilIdle()
        assertEquals(before, e.api.checkTargets.size)
    }

    @Test fun interruptedJobIsRecoveredAndContinuesWithoutRedoingFinishedItems() = runTest {
        val store = MemStore()
        val first = env(store = store)
        val id = first.engine.createJob("x", set())
        // имитация: 1-й адрес завершён, 2-й был в работе, когда процесс умер
        val done = store.claimNext(id)!!
        store.saveItem(id, done, ItemState.FINISHED, rec(DynamicStatus.DONE, fixtureResultRaws(), online = 37).copy(target = done))
        store.claimNext(id)
        assertEquals(1, store.counts(id).running)

        val api2 = FakeApi()
        val second = env(api = api2, store = store)
        second.engine.recoverInterrupted()
        assertEquals(JobState.PAUSED, store.getJob(id)!!.state)
        assertEquals(0, store.counts(id).running)
        assertEquals(3, store.counts(id).queued)
        assertEquals(1, store.counts(id).finished)

        second.engine.start(id)
        advanceUntilIdle()
        assertEquals(JobState.FINISHED, store.getJob(id)!!.state)
        assertEquals(targets.drop(1), api2.checkTargets)
        assertEquals(4, store.counts(id).finished)
    }

    @Test fun oneFailingAddressDoesNotStallTheQueue() = runTest {
        val api = FakeApi(onCheck = {
            if (it == "8.8.8.2") throw ApiException(ApiException.Kind.NETWORK, "down") else Fixtures.checkJson(it)
        })
        val e = env(api = api)
        val id = e.engine.createJob("x", set())
        e.engine.start(id)
        advanceUntilIdle()
        val c = e.store.counts(id)
        assertEquals(4, c.finished); assertEquals(1, c.errors); assertEquals(3, c.notBlocked)
        assertEquals(JobState.FINISHED, e.store.getJob(id)!!.state)
        assertEquals(Overall.ERROR, e.store.getItem(id, "8.8.8.2")!!.overall)
        assertEquals(2, api.checkTargets.count { it == "8.8.8.2" }) // 1 + retries(1)
    }

    @Test fun concurrencyStillRespectsProbeStartLimit() = runTest {
        val e = env(settings = AppSettings(requestDelayMs = 300, concurrency = 2, probeStartsPerMinute = 2, retries = 0, probeTimeoutSec = 30))
        val id = e.engine.createJob("x", set())
        e.engine.start(id)
        advanceUntilIdle()
        assertEquals(4, e.store.counts(id).finished)
        // 4 запуска при лимите 2/мин: минимум одно окно ожидания
        assertTrue("слишком быстро: ${testScheduler.currentTime}", testScheduler.currentTime >= 60_000L)
    }

    @Test fun privateRangeProducesOnlyStaticResults() = runTest {
        val e = env()
        val list = listOf("192.168.1.0", "192.168.1.1", "192.168.1.2")
        val id = e.engine.createJob("192.168.1.0/30", set(list))
        e.engine.start(id)
        advanceUntilIdle()
        assertTrue(e.api.probeIds.isEmpty())
        e.store.queryItems(id, ItemQuery()).forEach { assertEquals(DynamicStatus.UNAVAILABLE, it.record!!.dyn) }
        assertEquals(0, e.store.counts(id).let { it.queued + it.running })
    }

    @Test fun filtersAndSearchWork() = runTest {
        val api = FakeApi(onCheck = { if (it == "8.8.8.3") Fixtures.checkJson(it, listed = true) else Fixtures.checkJson(it) },
            onProbe = { flow<ProbeEvent> { } })
        val e = env(api = api, settings = AppSettings(requestDelayMs = 300, retries = 0, probeTimeoutSec = 20, probeStartsPerMinute = 5))
        val id = e.engine.createJob("x", set())
        e.engine.start(id)
        advanceUntilIdle()
        assertEquals(4, e.store.queryItems(id, ItemQuery(filter = ItemFilter.ERRORS)).size)
        assertEquals(listOf("8.8.8.3"), e.store.queryItems(id, ItemQuery(search = "8.8.8.3")).map { it.target })
        assertEquals(0, e.store.queryItems(id, ItemQuery(filter = ItemFilter.BLOCKED)).size)
    }
}
