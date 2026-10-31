package com.hollow.app.isolation.util

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Typed result wrapper that replaces silent `catch (_: Throwable) {}` blocks
 * with structured error handling and diagnostic logging.
 */
sealed interface HollowResult<out T> {
    data class Success<out T>(val data: T) : HollowResult<T>
    data class Failure(
        val exception: Throwable,
        val message: String = exception.message ?: exception.javaClass.simpleName
    ) : HollowResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data
    fun exceptionOrNull(): Throwable? = (this as? Failure)?.exception
}

inline fun <T> HollowResult<T>.getOrDefault(defaultValue: T): T =
    when (this) {
        is HollowResult.Success -> data
        is HollowResult.Failure -> defaultValue
    }

inline fun <T> HollowResult<T>.getOrElse(onFailure: (HollowResult.Failure) -> T): T =
    when (this) {
        is HollowResult.Success -> data
        is HollowResult.Failure -> onFailure(this)
    }

inline fun <T> HollowResult<T>.onSuccess(action: (T) -> Unit): HollowResult<T> {
    if (this is HollowResult.Success) action(data)
    return this
}

inline fun <T> HollowResult<T>.onFailure(action: (HollowResult.Failure) -> Unit): HollowResult<T> {
    if (this is HollowResult.Failure) action(this)
    return this
}

inline fun <T> runHollowCatching(
    tag: String = "HollowResult",
    block: () -> T
): HollowResult<T> {
    return try {
        HollowResult.Success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        runCatching { Log.w(tag, "Operation failed: ${t.message}", t) }
        HollowResult.Failure(t)
    }
}
