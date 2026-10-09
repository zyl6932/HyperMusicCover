package com.os4.musiccover

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NotificationSinkTest {
    class CombinedFlow(@JvmField var sources: Array<Flow<Any>>)

    private fun source(values: Array<Any>): CombinedFlow =
        CombinedFlow(values.map { MutableStateFlow(it) as Flow<Any> }.toTypedArray())

    @Test fun combineEntryAndArrayFactoryUseTheSameEightSources() {
        val factory = source(arrayOf<Any>(32, 80, 120, 1800, 1700, true, true))
        val original = factory.sources
        val sink = NotificationSink()
        val inputs = sink.attachToCombine(factory, original, 1)
        assertSame(factory.sources, inputs)
        assertEquals(8, inputs.size)
        original.indices.forEach { assertSame(original[it], inputs[it]) }
        assertSame(inputs, sink.attachToCombine(factory, inputs, 0))
        sink.refresh(0)
        assertEquals(0, (inputs[7] as MutableStateFlow<*>).value)
    }

    @Test fun policyAloneTriggersRealCombineAndRestoresSystemEnrollment() = runBlocking {
        val system = arrayOf<Any>(32, 80, 120, 1800, 1700, true, true)
        val combined = source(system)
        val original = combined.sources
        val sink = NotificationSink()
        assertTrue(sink.attach(combined, 0))
        assertFalse(sink.attach(combined, 0))
        assertEquals(8, combined.sources.size)
        original.indices.forEach { assertSame(original[it], combined.sources[it]) }
        val emissions = Channel<Array<Any?>>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(combined.sources.asIterable()) { values ->
                NotificationSink.withAvoidance(values, 0)
            }.collect { emissions.send(it) }
        }
        try {
            suspend fun next() = withTimeout(2000) { emissions.receive() }
            assertArrayEquals(system, next().take(7).toTypedArray())
            assertTrue(sink.refresh(1))
            assertArrayEquals(arrayOf<Any>(32, 80, 120, 1800, 1700, false, false), next().take(7).toTypedArray())
            assertTrue(sink.refresh(2))
            assertArrayEquals(system, next().take(7).toTypedArray())
            assertTrue(sink.refresh(0))
            assertArrayEquals(system, next().take(7).toTypedArray())
            original.indices.forEach { assertEquals(system[it], (original[it] as MutableStateFlow).value) }
        } finally {
            job.cancel()
            emissions.close()
        }
    }

    @Test fun initialAutomaticPolicyAndManualRestoreUseTheSameFlow() = runBlocking {
        val combined = source(arrayOf<Any>(40, 90, 150, 2100, 2000, false, true))
        val sink = NotificationSink()
        assertTrue(sink.attach(combined, 1))
        val emissions = Channel<Array<Any?>>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(combined.sources.asIterable()) { values ->
                // A stale global fallback cannot override the policy carried by this emission.
                NotificationSink.withAvoidance(values, 2)
            }.collect { emissions.send(it) }
        }
        try {
            assertArrayEquals(arrayOf<Any>(40, 90, 150, 2100, 2000, false, false),
                withTimeout(2000) { emissions.receive() }.take(7).toTypedArray())
            sink.refresh(0)
            assertArrayEquals(arrayOf<Any>(40, 90, 150, 2100, 2000, false, true),
                withTimeout(2000) { emissions.receive() }.take(7).toTypedArray())
        } finally {
            job.cancel()
            emissions.close()
        }
    }

    @Test fun unknownCombineShapeIsLeftAlone() {
        val sink = NotificationSink()
        assertFalse(sink.refresh(1))
        val unknown = source(arrayOf<Any>(0, true, false))
        val original = unknown.sources
        assertFalse(sink.attach(unknown, 1))
        assertSame(original, unknown.sources)
        assertArrayEquals(arrayOf<Any>(0, true, false),
            NotificationSink.withAvoidance(arrayOf<Any>(0, true, false), 1))
    }
}
