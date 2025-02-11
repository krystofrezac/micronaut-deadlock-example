package com.example

import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@MicronautTest
class DemoTest {
    @Inject
    @Client("/")
    lateinit var client: HttpClient

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `doesn't work`(): Unit =
        runBlocking {
            val sizeOfDefaultDispatcher = max(Runtime.getRuntime().availableProcessors(), 2)
            val criticalConcurrency = sizeOfDefaultDispatcher + 1
            test(criticalConcurrency)
        }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun works(): Unit =
        runBlocking {
            val sizeOfDefaultDispatcher = max(Runtime.getRuntime().availableProcessors(), 2)
            test(sizeOfDefaultDispatcher)
        }

    private suspend fun test(concurrency: Int) =
        coroutineScope {
            val duration =
                measureTime {
                    (1..concurrency)
                        .map {
                            async {
                                client.retrieve<Unit, String>(HttpRequest.GET("/"), String::class.java).awaitSingle()
                            }
                        }.awaitAll()
                }

            val expectedDuration = concurrency.seconds
            val tolerance = 10.seconds
            assert(duration < (expectedDuration + tolerance))
        }
}
