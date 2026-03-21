package dev.aperso.composite.skia

import org.jetbrains.skia.DirectContext

object SkiaContext {
    fun initialize() {
        // Force lazy initialization of the DirectContext on the render thread
        directContext
    }

    val directContext: DirectContext by lazy {
        DirectContext.makeGL()
    }

    fun run(runnable: Runnable) {
        runnable.run()
    }
}
