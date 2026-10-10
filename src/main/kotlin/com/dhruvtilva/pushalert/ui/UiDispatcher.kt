package com.dhruvtilva.pushalert.ui

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Runs coroutines on the main thread. Replaces kotlinx-coroutines-android, whose bytecode uses
 * invokedynamic call sites that the `dx` dexer used by this project's build handles poorly.
 */
object UiDispatcher : CoroutineDispatcher() {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() !== Looper.getMainLooper()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}
