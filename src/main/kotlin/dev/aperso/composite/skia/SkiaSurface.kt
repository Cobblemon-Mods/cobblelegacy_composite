package dev.aperso.composite.skia

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import dev.aperso.composite.diag.RenderDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.ArrayListDeque
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.opengl.GL30
import java.util.Deque

class SkiaSurface {
    private val texture: TextureTarget by lazy { TextureTarget(854, 480, false, false) }
    private lateinit var target: BackendRenderTarget
    private lateinit var surface: Surface
    private var buffer: Int = 0

    private fun ensureBuffer() {
        if (buffer == 0) {
            buffer = GL30.glGenFramebuffers()
        }
    }

    fun resize(width: Int, height: Int) {
        if (this::surface.isInitialized && texture.width == width && texture.height == height) return
        ensureBuffer()
        SkiaContext.run {
            if (this::surface.isInitialized) surface.close()
            if (this::target.isInitialized) target.close()

            texture.resize(width, height, false)

            val context = SkiaContext.directContext
            target = BackendRenderTarget.makeGL(
                width,
                height,
                0,
                8,
                texture.frameBufferId,
                GL30.GL_RGBA8
            )
            surface = Surface.makeFromBackendRenderTarget(
                context,
                target,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB
            ) ?: throw RuntimeException("Failed to create Skia surface")
        }
        // texture.resize() est passe par GlStateManager depuis l'autre contexte : ses caches
        // decrivent desormais un etat qui n'existe pas cote Minecraft.
        GlStateGuard.restore()

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, buffer)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL30.GL_TEXTURE_2D,
            texture.colorTextureId,
            0
        )
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    private val recordedCalls: Deque<GuiGraphics.() -> Unit> = ArrayListDeque()

    /**
     * Differe un appel de rendu Minecraft natif (item, texture) : Skia ne sait pas les dessiner,
     * ils sont donc rejoues sur la cible principale une fois la surface Compose composee.
     */
    fun record(call: GuiGraphics.() -> Unit) {
        recordedCalls.addLast(call)
    }

    fun render(guiGraphics: GuiGraphics, render: (Canvas) -> Unit) {
        ensureBuffer()
        val main = Minecraft.getInstance().mainRenderTarget
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, buffer)
        main.blitToScreen(main.width, main.height, true)

        SkiaContext.run {
            render(surface.canvas.asComposeCanvas())
            SkiaContext.directContext.resetGLAll()
            SkiaContext.directContext.flush()
        }
        // Doit venir immediatement apres Skia : tant que ce n'est pas fait, tout appel de rendu
        // Minecraft (y compris le blit ci-dessous) travaille sur un etat GL incoherent.
        GlStateGuard.restoreAfterSkia()

        main.bindWrite(true)
        RenderSystem.enableBlend()
        texture.blitToScreen(main.width, main.height, false)
        main.bindWrite(true)
        // Contrat du rejeu : aucun scissor herite. Les composants appelants sondent parfois
        // GL_SCISSOR_TEST pour decider s'ils doivent poser leur propre decoupe ; un scissor
        // laisse actif par Skia leur fait alors hériter d'un clip arbitraire et leurs dessins
        // sont rognes hors ecran. enable puis disable resynchronise cache et GL a coup sur.
        RenderSystem.enableScissor(0, 0, main.viewWidth, main.viewHeight)
        RenderSystem.disableScissor()

        RenderDiagnostics.beforeReplay(guiGraphics, recordedCalls.size)
        var replayed = 0
        while (true) {
            val call = recordedCalls.pollFirst() ?: break
            call.invoke(guiGraphics)
            replayed++
        }
        RenderDiagnostics.afterReplay(guiGraphics, replayed)
    }
}

val LocalSkiaSurface = staticCompositionLocalOf<SkiaSurface> { error("No SkiaSurface provided") }
