package com.lyric.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** One durable writer, independent of the screen's lifetime. Rapid scroll events coalesce,
 * but never postpone a write; an older transaction can never overwrite a newer location. */
class ProgressWriter<T>(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO, private val write: suspend (T) -> Unit) {
    private data class Request<T>(val sequence: Long, val value: T)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var sequence = 0L
    private var latest: Request<T>? = null
    private data class Completion(val sequence: Long, val failure: Throwable? = null)
    private val completed = MutableStateFlow(Completion(0L))
    init {
        scope.launch(dispatcher) {
            for (signal in wake) {
                val request = synchronized(this@ProgressWriter) { latest } ?: continue
                val failure = try { write(request.value); null } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    e
                }
                completed.value = Completion(request.sequence, failure)
            }
        }
    }
    @Synchronized fun submit(value: T): Long {
        val request = Request(++sequence, value)
        latest = request
        check(wake.trySend(Unit).isSuccess)
        return request.sequence
    }
    fun close() { wake.close() }
    suspend fun flush(value: T) {
        await(submit(value))
    }
    suspend fun await(target: Long) {
        completed.first { it.sequence >= target }.failure?.let { throw it }
    }
}
