package com.homeai.assistant.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * ServiceLifecycleOwner – provides a [LifecycleOwner] backed by a
 * [LifecycleRegistry] that can be used with CameraX inside a Service.
 *
 * The lifecycle is immediately moved to RESUMED on construction so CameraX
 * can bind to it right away.
 */
class ServiceLifecycleOwner : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this).also { reg ->
        reg.currentState = Lifecycle.State.CREATED
        reg.currentState = Lifecycle.State.STARTED
        reg.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
