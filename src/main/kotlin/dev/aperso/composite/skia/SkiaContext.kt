package dev.aperso.composite.skia

import dev.aperso.composite.Composite
import org.jetbrains.skia.DirectContext
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.Platform
import java.lang.reflect.Method

/**
 * Heberge le [DirectContext] de Skia et, quand c'est possible, un contexte OpenGL dedie.
 *
 * Skia et Minecraft ne peuvent pas partager le meme contexte sans se marcher dessus : Skia
 * repositionne librement samplers, scissor, VAO, programme, blend..., et GlStateManager met en
 * cache la quasi-totalite de ce qu'il ecrit, donc il ne detecte jamais ces changements.
 *
 * La parade est un second contexte qui *partage les objets* (textures, buffers) avec celui de
 * Minecraft mais possede son propre etat. Les FBO et les VAO ne sont pas partages entre
 * contextes : c'est pourquoi [SkiaSurface] cree le FBO cible de Skia dans [run] et garde son
 * propre FBO cote Minecraft.
 *
 * - Windows : wglCreateContext + wglShareLists. Actif par defaut, eprouve.
 * - macOS   : CGLCreateContext avec le contexte courant comme share group. Desactive par
 *             defaut, activable avec -Dcomposite.macos.isolateContext=true ; sinon on retombe
 *             sur [GlStateGuard] apres chaque rendu.
 * - Linux   : pas de contexte dedie, [GlStateGuard] fait le menage.
 */
object SkiaContext {
    private val platform = Platform.get()
    private val isWindows = platform == Platform.WINDOWS
    private val isMacos = platform == Platform.MACOSX

    private val macosIsolationRequested =
        System.getProperty("composite.macos.isolateContext", "false").toBoolean()

    private var wglContext: Long = 0
    private var cglContext: Long = 0
    private var stopped = false

    /**
     * Vrai quand Skia tourne dans son propre contexte et ne peut donc pas polluer l'etat GL de
     * Minecraft. Quand c'est faux, [GlStateGuard] doit nettoyer apres chaque rendu.
     */
    val isIsolated: Boolean
        get() = wglContext != 0L || cglContext != 0L

    fun initialize() {
        if (isWindows) {
            initializeWindows()
        } else if (isMacos && macosIsolationRequested) {
            initializeMacos()
        }
        // Le DirectContext doit naitre dans le contexte ou Skia dessinera.
        run(Runnable { directContext })
        Composite.logger.info(
            "Skia context initialized (platform={}, isolated={})", platform, isIsolated
        )
    }

    private val directContextHolder = lazy { DirectContext.makeGL() }

    val directContext: DirectContext by directContextHolder

    fun run(runnable: Runnable) {
        if (stopped) return
        when {
            wglContext != 0L -> runWindows(runnable)
            cglContext != 0L -> runMacos(runnable)
            else -> runnable.run()
        }
    }

    /**
     * Libere le contexte dedie tant que la fenetre et le pilote de Minecraft sont encore vivants.
     *
     * Sinon il survit jusqu'a la sortie du processus : opengl32.dll le nettoie alors pendant le
     * dechargement des DLL, dans un pilote (NVIDIA ici) deja a moitie demonte, et la JVM plante a
     * chaque fermeture du jeu (hs_err_pid*.log sur le thread "VM Thread").
     */
    fun shutdown() {
        if (stopped) return
        stopped = true
        // Les objets GPU de Skia vivent dans le contexte dedie : apres abandon(), Skia ne fait plus
        // aucun appel GL, meme quand les surfaces encore ouvertes seront liberees.
        if (directContextHolder.isInitialized()) directContext.abandon()
        if (wglContext != 0L) shutdownWindows()
        if (cglContext != 0L) shutdownMacos()
    }

    // ---------------------------------------------------------------- Windows (WGL)

    private var wglGetCurrentDC: Method? = null
    private var wglGetCurrentContext: Method? = null
    private var wglMakeCurrent: Method? = null

    private fun initializeWindows() {
        if (wglContext != 0L) return
        try {
            val wgl = Class.forName("org.lwjgl.opengl.WGL")
            val getCurrentDC = wgl.getMethod("wglGetCurrentDC")
            val createContext = wgl.getMethod("wglCreateContext", Long::class.java)
            val shareLists = wgl.getMethod("wglShareLists", Long::class.java, Long::class.java)
            val getCurrentContext = wgl.getMethod("wglGetCurrentContext")

            val dc = getCurrentDC.invoke(null) as Long
            val context = createContext.invoke(null, dc) as Long
            if (context == 0L) return
            val currentCtx = getCurrentContext.invoke(null) as Long
            shareLists.invoke(null, currentCtx, context)

            wglGetCurrentDC = getCurrentDC
            wglGetCurrentContext = getCurrentContext
            wglMakeCurrent = wgl.getMethod("wglMakeCurrent", Long::class.java, Long::class.java)
            wglContext = context
        } catch (exception: Throwable) {
            Composite.logger.warn("No dedicated WGL context, falling back to GlStateGuard", exception)
            wglContext = 0
        }
    }

    private fun runWindows(runnable: Runnable) {
        val getCurrentContext = wglGetCurrentContext
        val getCurrentDC = wglGetCurrentDC
        val makeCurrent = wglMakeCurrent
        if (getCurrentContext == null || getCurrentDC == null || makeCurrent == null) {
            runnable.run()
            return
        }
        val oldContext = getCurrentContext.invoke(null) as Long
        val dc = getCurrentDC.invoke(null) as Long
        makeCurrent.invoke(null, dc, wglContext)
        try {
            runnable.run()
        } finally {
            makeCurrent.invoke(null, getCurrentDC.invoke(null) as Long, oldContext)
        }
    }

    private fun shutdownWindows() {
        try {
            // Jamais courant a ce stade : runWindows restaure toujours le contexte precedent.
            Class.forName("org.lwjgl.opengl.WGL")
                .getMethod("wglDeleteContext", Long::class.java)
                .invoke(null, wglContext)
        } catch (exception: Throwable) {
            Composite.logger.warn("Failed to delete the dedicated WGL context", exception)
        }
        wglContext = 0
    }

    // ------------------------------------------------------------------ macOS (CGL)

    private var cglGetCurrentContext: Method? = null
    private var cglSetCurrentContext: Method? = null

    private fun initializeMacos() {
        if (cglContext != 0L) return
        try {
            val cgl = Class.forName("org.lwjgl.opengl.CGL")
            val getCurrentContext = cgl.getMethod("CGLGetCurrentContext")
            val getPixelFormat = cgl.getMethod("CGLGetPixelFormat", Long::class.java)
            val createContext = cgl.getMethod(
                "nCGLCreateContext", Long::class.java, Long::class.java, Long::class.java
            )

            val current = getCurrentContext.invoke(null) as Long
            if (current == 0L) return
            val pixelFormat = getPixelFormat.invoke(null, current) as Long
            if (pixelFormat == 0L) return

            val context = MemoryStack.stackPush().use { stack ->
                val out = stack.mallocPointer(1)
                // share = contexte de Minecraft : textures et buffers restent communs.
                val error = createContext.invoke(
                    null, pixelFormat, current, MemoryUtil.memAddress(out)
                ) as Int
                if (error != 0) {
                    Composite.logger.warn("CGLCreateContext failed with error {}", error)
                    0L
                } else {
                    out.get(0)
                }
            }
            if (context == 0L) return

            cglGetCurrentContext = getCurrentContext
            cglSetCurrentContext = cgl.getMethod("CGLSetCurrentContext", Long::class.java)
            cglContext = context
        } catch (exception: Throwable) {
            Composite.logger.warn("No dedicated CGL context, falling back to GlStateGuard", exception)
            cglContext = 0
        }
    }

    private fun runMacos(runnable: Runnable) {
        val getCurrentContext = cglGetCurrentContext
        val setCurrentContext = cglSetCurrentContext
        if (getCurrentContext == null || setCurrentContext == null) {
            runnable.run()
            return
        }
        val oldContext = getCurrentContext.invoke(null) as Long
        setCurrentContext.invoke(null, cglContext)
        try {
            runnable.run()
        } finally {
            setCurrentContext.invoke(null, oldContext)
        }
    }

    private fun shutdownMacos() {
        try {
            Class.forName("org.lwjgl.opengl.CGL")
                .getMethod("CGLDestroyContext", Long::class.java)
                .invoke(null, cglContext)
        } catch (exception: Throwable) {
            Composite.logger.warn("Failed to destroy the dedicated CGL context", exception)
        }
        cglContext = 0
    }
}
