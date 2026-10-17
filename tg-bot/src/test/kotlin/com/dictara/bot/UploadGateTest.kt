package com.dictara.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UploadGateTest {

    /** Waits (up to 5 s) until [condition] holds; the threads of these tests need a moment to line up. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Did not happen in time: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `it needs at least one place`() {
        assertThrows(IllegalArgumentException::class.java) { UploadGate(0) }
        assertThrows(IllegalArgumentException::class.java) { UploadGate(-3) }
    }

    @Test
    fun `work runs and its result comes back`() {
        val gate = UploadGate(2)
        assertEquals("done", gate.run { "done" })
        assertEquals(2, gate.free)
        assertEquals(0, gate.waiting)
    }

    @Test
    fun `the place is given back when the work fails`() {
        val gate = UploadGate(1)

        assertThrows(IllegalStateException::class.java) { gate.run { throw IllegalStateException("gateway down") } }

        assertEquals(1, gate.free)
        assertEquals("next", gate.run { "next" }) // would hang if the place had been kept
    }

    @Test
    fun `the place is given back when the work throws an error that is not an exception`() {
        val gate = UploadGate(1)

        assertThrows(OutOfMemoryError::class.java) { gate.run { throw OutOfMemoryError("Java heap space") } }

        assertEquals(1, gate.free)
    }

    @Test
    fun `never more files at once than places, and all of them get through`() {
        val gate = UploadGate(3)
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(40)
        try {
            val results = (1..40).map { n ->
                pool.submit<Int> {
                    gate.run {
                        peak.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        Thread.sleep(15)
                        inside.decrementAndGet()
                        done.incrementAndGet()
                        n
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals((1..40).toList(), results)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(40, done.get())
        assertEquals(3, peak.get(), "the places were used to the full, and not exceeded")
        assertEquals(3, gate.free)
        assertEquals(0, gate.waiting)
    }

    @Test
    fun `waiting files are counted`() {
        val gate = UploadGate(1)
        val holding = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.submit { gate.run { holding.countDown(); letGo.await(10, TimeUnit.SECONDS) } }
            assertTrue(holding.await(5, TimeUnit.SECONDS))
            repeat(3) { pool.submit { gate.run { } } }

            eventually("three files waiting") { gate.waiting == 3 }
            assertEquals(0, gate.free)

            letGo.countDown()
            eventually("nobody waiting") { gate.waiting == 0 && gate.free == 1 }
        } finally {
            letGo.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `files are let in in the order they came`() {
        val gate = UploadGate(1)
        val holding = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.submit { gate.run { holding.countDown(); letGo.await(10, TimeUnit.SECONDS) } }
            assertTrue(holding.await(5, TimeUnit.SECONDS))
            for (n in 1..6) {
                pool.submit { gate.run { order.add(n) } }
                eventually("file $n is waiting") { gate.waiting == n } // so that they queue one after another
            }

            letGo.countDown()
            eventually("all six have run") { order.size == 6 }
            assertEquals(listOf(1, 2, 3, 4, 5, 6), order)
        } finally {
            letGo.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a file that stops waiting does not take a place with it`() {
        val gate = UploadGate(1)
        val holding = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val ran = AtomicInteger()
        val holder = Executors.newSingleThreadExecutor()
        try {
            holder.submit { gate.run { holding.countDown(); letGo.await(10, TimeUnit.SECONDS) } }
            assertTrue(holding.await(5, TimeUnit.SECONDS))

            val interrupted = AtomicInteger()
            val waiter = Thread {
                try {
                    gate.run { ran.incrementAndGet() }
                } catch (_: InterruptedException) {
                    interrupted.incrementAndGet()
                }
            }
            waiter.start()
            eventually("the waiter is in the queue") { gate.waiting == 1 }
            waiter.interrupt()
            waiter.join(5_000)

            assertEquals(1, interrupted.get(), "an interrupted file leaves with InterruptedException")
            assertEquals(0, ran.get(), "and its work does not run")
            assertEquals(0, gate.waiting)

            letGo.countDown()
            eventually("the place is free again") { gate.free == 1 } // a leaked place would keep it at 0
            assertEquals("after", gate.run { "after" })
        } finally {
            letGo.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `a failing file does not hold up the ones behind it`() {
        val gate = UploadGate(1)
        val failed = AtomicInteger()
        val ok = AtomicInteger()
        val pool = Executors.newFixedThreadPool(10)
        try {
            val futures = (1..10).map { n ->
                pool.submit {
                    try {
                        gate.run { if (n % 2 == 0) throw RuntimeException("boom $n") else ok.incrementAndGet() }
                    } catch (_: RuntimeException) {
                        failed.incrementAndGet()
                    }
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(5, ok.get())
        assertEquals(5, failed.get())
        assertEquals(1, gate.free)
        assertFalse(gate.waiting > 0)
    }
}
