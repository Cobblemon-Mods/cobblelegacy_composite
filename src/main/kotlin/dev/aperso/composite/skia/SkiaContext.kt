package dev.aperso.composite.skia

import org.jetbrains.skia.DirectContext
import org.lwjgl.system.Platform

object SkiaContext {
    private var wglContext: Long = 0
    private val isWindows = Platform.get() == Platform.WINDOWS

    fun initialize() {
        if (isWindows) {
            initializeWindows()
        }
        // Force lazy initialization of the DirectContext on the render thread
        directContext
    }

    private fun initializeWindows() {
        if (wglContext != 0L) return
        try {
            val wgl = Class.forName("org.lwjgl.opengl.WGL")
            val getCurrentDC = wgl.getMethod("wglGetCurrentDC")
            val createContext = wgl.getMethod("wglCreateContext", Long::class.java)
            val shareLists = wgl.getMethod("wglShareLists", Long::class.java, Long::class.java)
            val getCurrentContext = wgl.getMethod("wglGetCurrentContext")

            val dc = getCurrentDC.invoke(null) as Long
            wglContext = createContext.invoke(null, dc) as Long
            val currentCtx = getCurrentContext.invoke(null) as Long
            shareLists.invoke(null, currentCtx, wglContext)
        } catch (_: Exception) {
            // Fallback: no separate context
            wglContext = 0
        }
    }

    val directContext: DirectContext by lazy {
        DirectContext.makeGL()
    }

    fun run(runnable: Runnable) {
        if (isWindows && wglContext != 0L) {
            runWindows(runnable)
        } else {
            runnable.run()
        }
    }

    private fun runWindows(runnable: Runnable) {
        try {
            val wgl = Class.forName("org.lwjgl.opengl.WGL")
            val getCurrentDC = wgl.getMethod("wglGetCurrentDC")
            val getCurrentContext = wgl.getMethod("wglGetCurrentContext")
            val makeCurrent = wgl.getMethod("wglMakeCurrent", Long::class.java, Long::class.java)

            val oldContext = getCurrentContext.invoke(null) as Long
            val dc = getCurrentDC.invoke(null) as Long
            makeCurrent.invoke(null, dc, wglContext)
            try {
                runnable.run()
            } finally {
                val dc2 = getCurrentDC.invoke(null) as Long
                makeCurrent.invoke(null, dc2, oldContext)
            }
        } catch (_: Exception) {
            runnable.run()
        }
    }
}
