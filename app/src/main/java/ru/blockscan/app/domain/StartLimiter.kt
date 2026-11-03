package ru.blockscan.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Скользящее окно: не более N запусков динамической проверки за окно. Общее для всех воркеров. */
class StartLimiter(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val mutex = Mutex()
    private val stamps = ArrayDeque<Long>()

    suspend fun acquire(maxPerWindow: Int, windowMs: Long = 60_000L) {
        while (true) {
            val wait = mutex.withLock {
                val t = now()
                while (stamps.isNotEmpty() && t - stamps.first() >= windowMs) stamps.removeFirst()
                if (stamps.size < maxPerWindow) {
                    stamps.addLast(t)
                    0L
                } else {
                    stamps.first() + windowMs - t + 50L
                }
            }
            if (wait <= 0L) return
            delay(wait)
        }
    }
}
