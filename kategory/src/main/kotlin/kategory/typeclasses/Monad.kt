package kategory

import kotlinx.coroutines.experimental.runBlocking
import java.io.Serializable
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn

interface Monad<F> : Applicative<F>, Typeclass {

    fun <A, B> flatMap(fa: HK<F, A>, f: (A) -> HK<F, B>): HK<F, B>

    override fun <A, B> ap(fa: HK<F, A>, ff: HK<F, (A) -> B>): HK<F, B> = flatMap(ff, { f -> map(fa, f) })

    fun <A> flatten(ffa: HK<F, HK<F, A>>): HK<F, A> = flatMap(ffa, { it })

    fun <A, B> tailRecM(a: A, f: (A) -> HK<F, Either<A, B>>): HK<F, B>
}

inline fun <reified F, A, B> HK<F, A>.flatMap(FT: Monad<F> = monad(), noinline f: (A) -> HK<F, B>): HK<F, B> = FT.flatMap(this, f)

inline fun <reified F, A> HK<F, HK<F, A>>.flatten(FT: Monad<F> = monad()): HK<F, A> = FT.flatten(this)

@RestrictsSuspension
open class MonadContinuation<F, A>(val M: Monad<F>, override val context: CoroutineContext = EmptyCoroutineContext) : Serializable, Continuation<HK<F, A>> {

    override fun resume(value: HK<F, A>) {
        returnedMonad = value
    }

    override fun resumeWithException(exception: Throwable) {
        throw exception
    }

    protected lateinit var returnedMonad: HK<F, A>

    internal fun returnedMonad(): HK<F, A> = returnedMonad

    operator suspend fun <B> HK<F, B>.not(): B = bind { this }

    suspend fun <B> HK<F, B>.bind(): B = bind { this }

    suspend fun <B> bind(m: () -> HK<F, B>): B = suspendCoroutineOrReturn { c ->
        val labelHere = c.stackLabels // save the whole coroutine stack labels
        returnedMonad = M.flatMap(m(), { x ->
            c.stackLabels = labelHere
            c.resume(x)
            returnedMonad
        })
        COROUTINE_SUSPENDED
    }

    suspend fun <B> bindInContext(coroutineContext: CoroutineContext, m: suspend () -> HK<F, B>): B = suspendCoroutineOrReturn { c ->
        val labelHere = c.stackLabels // save the whole coroutine stack labels
        val result = runBlocking(coroutineContext) { m() }
        returnedMonad = M.flatMap(result, { x ->
            c.stackLabels = labelHere
            c.resume(x)
            returnedMonad
        })
        COROUTINE_SUSPENDED
    }

    infix fun <B> yields(b: B) = yields { b }

    infix fun <B> yields(b: () -> B) = M.pure(b())
}

/**
 * Entry point for monad bindings which enables for comprehension. The underlying impl is based on coroutines.
 * A coroutine is initiated and inside `MonadContinuation` suspended yielding to `flatMap` once all the flatMap binds are completed
 * the underlying monad is returned from the act of executing the coroutine
 */
fun <F, B> Monad<F>.binding(coroutineContext: CoroutineContext = EmptyCoroutineContext, c: suspend MonadContinuation<F, *>.() -> HK<F, B>): HK<F, B> {
    val continuation = MonadContinuation<F, B>(this, coroutineContext)
    c.startCoroutine(continuation, continuation)
    return continuation.returnedMonad()
}

@RestrictsSuspension
open class StackSafeMonadContinuation<F, A>(val M: Monad<F>, override val context: CoroutineContext = EmptyCoroutineContext) : Serializable, Continuation<Free<F, A>> {

    override fun resume(value: Free<F, A>) {
        returnedMonad = value
    }

    override fun resumeWithException(exception: Throwable) {
        throw exception
    }

    protected lateinit var returnedMonad: Free<F, A>

    internal fun returnedMonad(): Free<F, A> = returnedMonad

    operator suspend fun <B> HK<F, B>.not(): B = this.bind()

    suspend fun <B> HK<F, B>.bind(): B = bind { Free.liftF(this) }

    suspend fun <B> Free<F, B>.bind(): B = bind { this }

    suspend fun <B> bind(m: () -> Free<F, B>): B = suspendCoroutineOrReturn { c ->
        val labelHere = c.stackLabels // save the whole coroutine stack labels
        val freeResult = m()
        returnedMonad = freeResult.flatMap { z ->
            c.stackLabels = labelHere
            c.resume(z)
            returnedMonad
        }
        COROUTINE_SUSPENDED
    }

    suspend fun <B> bindInContext(coroutineContext: CoroutineContext, m: suspend () -> Free<F, B>): B = suspendCoroutineOrReturn { c ->
        val labelHere = c.stackLabels // save the whole coroutine stack labels
        val freeResult = runBlocking(coroutineContext) { m() }
        returnedMonad = freeResult.flatMap { z ->
            c.stackLabels = labelHere
            c.resume(z)
            returnedMonad
        }
        COROUTINE_SUSPENDED
    }

    infix fun <B> yields(b: B) = yields { b }

    infix fun <B> yields(b: () -> B) = Free.liftF(M.pure(b()))
}

/**
 * Entry point for monad bindings which enables for comprehension. The underlying impl is based on coroutines.
 * A coroutine is initiated and inside `MonadContinuation` suspended yielding to `flatMap` once all the flatMap binds are completed
 * the underlying monad is returned from the act of executing the coroutine
 *
 * This combinator ultimately returns computations lifting to Free to automatically for comprehend in a stack-safe way
 * over any stack-unsafe monads
 */
fun <F, B> Monad<F>.bindingStackSafe(coroutineContext: CoroutineContext = EmptyCoroutineContext, c: suspend StackSafeMonadContinuation<F, *>.() -> Free<F, B>): Free<F, B> {
    val continuation = StackSafeMonadContinuation<F, B>(this, coroutineContext)
    c.startCoroutine(continuation, continuation)
    return continuation.returnedMonad()
}

inline fun <reified F> monad(): Monad<F> = instance(InstanceParametrizedType(Monad::class.java, listOf(F::class.java)))
