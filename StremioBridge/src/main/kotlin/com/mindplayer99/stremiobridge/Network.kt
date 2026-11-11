package com.mindplayer99.stremiobridge

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal fun interface Transport {
    suspend fun get(url: String, playback: Boolean): JSONObject
}

internal object Http : Transport {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(28, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    private val slow =
        client
            .newBuilder()
            .readTimeout(105, TimeUnit.SECONDS)
            .callTimeout(115, TimeUnit.SECONDS)
            .build()
    private val ordinary = Semaphore(8)
    private val streaming = Semaphore(8)

    override suspend fun get(url: String, playback: Boolean): JSONObject =
        (if (playback) streaming else ordinary).withPermit {
            suspendCancellableCoroutine { continuation ->
                val endpoint = url.toHttpUrl()
                val request =
                    Request.Builder()
                        .url(endpoint.newBuilder().username("").password("").build())
                        .header("Accept", "application/json")
                        .apply {
                            if (endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty())
                                header(
                                    "Authorization",
                                    Credentials.basic(
                                        endpoint.username,
                                        endpoint.password,
                                        Charsets.UTF_8,
                                    ),
                                )
                        }
                        .build()
                val call = (if (playback) slow else client).newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            continuation.resumeWithException(
                                BridgeFailure("Addon connection failed or timed out.")
                            )
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val json =
                                    response.use {
                                        if (!it.isSuccessful)
                                            throw BridgeFailure(
                                                "Addon returned HTTP " + it.code + "."
                                            )
                                        val source =
                                            it.body?.source()
                                                ?: throw BridgeFailure("Empty addon response.")
                                        if (source.request(12L * 1024 * 1024 + 1))
                                            throw BridgeFailure("Addon response exceeds 12 MiB.")
                                        JSONObject(source.readUtf8())
                                    }
                                continuation.resume(json)
                            } catch (e: Exception) {
                                continuation.resumeWithException(
                                    if (e is BridgeFailure) e
                                    else BridgeFailure("Invalid addon response.")
                                )
                            }
                        }
                    }
                )
            }
        }
}

internal suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

internal suspend fun <T, R> List<T>.independent(
    timeout: Long = 65_000,
    block: suspend (T) -> R,
): List<R> = coroutineScope {
    map { item -> async { withTimeoutOrNull(timeout) { attempt { block(item) } } } }
        .awaitAll()
        .filterNotNull()
}

internal fun now() = System.nanoTime() / 1_000_000

internal class Cache<T : Any>(private val capacity: Int, private val ttl: Long) {
    private val items = LinkedHashMap<String, Pair<Long, T>>(16, .75f, true)
    private val locks = Array(32) { Mutex() }

    private fun get(key: String) =
        synchronized(items) { items[key]?.takeIf { now() - it.first < ttl }?.second }

    suspend fun read(key: String, fetch: suspend () -> T): T {
        get(key)?.let {
            return it
        }
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            get(key)
                ?: fetch().also { value ->
                    currentCoroutineContext().ensureActive()
                    synchronized(items) {
                        items[key] = now() to value
                        if (items.size > capacity) items.remove(items.keys.first())
                    }
                }
        }
    }
}
