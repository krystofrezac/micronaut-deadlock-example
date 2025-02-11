package com.example

import com.zaxxer.hikari.HikariConfig
import io.micronaut.aop.InterceptedMethod
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.aop.kotlin.KotlinInterceptedMethod
import io.micronaut.context.annotation.Requires
import io.micronaut.core.convert.ConversionService
import io.micronaut.data.annotation.Repository
import io.micronaut.data.repository.CrudRepository
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.Micronaut.run
import io.micronaut.transaction.annotation.Transactional
import io.micronaut.transaction.interceptor.TransactionalInterceptor
import jakarta.inject.Singleton
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    run(*args)
}

@Controller
class SomeController(
    private val someService: SomeService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Get
    suspend fun process(): String =
        coroutineScope {
            // We want to change dispatcher to DefaultDispatcher and configure coroutine contexts
            async(Dispatchers.Default) {
                logger.info("Processing")
                someService.saveInTransaction()
                logger.info("Finished")

                // Returning non-empty body so that the client it tests work
                "a"
            }.await()
        }
}

@Singleton
open class SomeService(
    private val someRepository: SomeRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    open suspend fun saveInTransaction() {
        someRepository.save(SomeEntity())

        // To simulate call to suspend function
        delay(1.seconds)

        // There needs to be something blocking code after suspended function call
        logger.debug("after delay")
    }
}

@Entity
@Table(name = "something")
class SomeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    var id: Long = 0,
)

@Repository
interface SomeRepository : CrudRepository<SomeEntity, Long>

/**
 * Allows us to use [Transactional] on suspended functions without coroutine deadlocks
 *
 * How it works:
 * - Creates new coroutine on IO dispatcher to offload blocking operations
 * - Proceeds with the intercepted function
 * - [TransactionalInterceptor] intercepts the method and tries to get connection from hikari (this
 *   is a blocking operation)
 * - Functions continues as usual and after first suspend returns to the previous dispatcher's pool
 */
@Requires(
    property = "feature-toggles.persistence.enable-anti-deadlock-transactions",
    value = "true",
    defaultValue = "false",
    bean = HikariConfig::class,
)
@Singleton
@InterceptorBean(Transactional::class)
class AntiDeadlockTransactionalInterceptor(
    private val transactionalInterceptor: TransactionalInterceptor,
    private val conversionService: ConversionService,
) : MethodInterceptor<Any, Any> {
    override fun getOrder(): Int = transactionalInterceptor.order - 1

    @OptIn(DelicateCoroutinesApi::class)
    override fun intercept(context: MethodInvocationContext<Any, Any>): Any? {
        val interceptedMethod = getKotlinMethodIfSuspended(context) ?: return context.proceed()

        val coroutineContext = interceptedMethod.coroutineContext + blockingDispatcher
        return interceptedMethod.handleResult(
            GlobalScope
                // Coroutine context of intercepted method(interceptedMethod.coroutineContext) is
                // untouched, so after first suspend it will return to original thread pool
                .async(coroutineContext) {
                    interceptedMethod.interceptResultAsCompletionStage().await()
                }.asCompletableFuture(),
        )
    }

    private fun getKotlinMethodIfSuspended(context: MethodInvocationContext<Any, Any>): KotlinInterceptedMethod? {
        val interceptedMethod = InterceptedMethod.of(context, conversionService)
        val kotlinInterceptedMethod = interceptedMethod as? KotlinInterceptedMethod

        if (interceptedMethod.resultType() != InterceptedMethod.ResultType.COMPLETION_STAGE ||
            kotlinInterceptedMethod == null
        ) {
            return null
        }

        return kotlinInterceptedMethod
    }

    companion object {
        private val blockingDispatcher = Dispatchers.IO
    }
}
