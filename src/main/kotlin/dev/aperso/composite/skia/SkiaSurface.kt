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
    // Creee au premier resize, DANS SkiaContext.run : sous Windows son FBO appartient ainsi au
    // contexte de Skia, celui qui y dessine. Un FBO ne se partage pas entre contextes, d'ou
    // l'importance de ne jamais y toucher (creation comme destruction) ailleurs que dans run.
    private val textureHolder = lazy { TextureTarget(854, 480, false, false) }
    private val texture: TextureTarget get() = textureHolder.value
    private var target: BackendRenderTarget? = null
    private var surface: Surface? = null

    /** FBO cote Minecraft qui ecrit dans la meme texture ; cree dans le contexte de Minecraft. */
    private var buffer: Int = 0

    private fun ensureBuffer() {
        if (buffer == 0) {
            buffer = GL30.glGenFramebuffers()
        }
    }

    fun resize(width: Int, height: Int) {
        // surface != null court-circuite : tant qu'elle n'existe pas, texture n'est pas touchee
        // ici, et sa creation a lieu dans run ci-dessous (cf. textureHolder).
        if (surface != null && texture.width == width && texture.height == height) return
        ensureBuffer()
        SkiaContext.run {
            surface?.close()
            target?.close()
            surface = null
            target = null

            texture.resize(width, height, false)

            val newTarget = BackendRenderTarget.makeGL(
                width,
                height,
                0,
                8,
                texture.frameBufferId,
                GL30.GL_RGBA8
            )
            target = newTarget
            surface = Surface.makeFromBackendRenderTarget(
                SkiaContext.directContext,
                newTarget,
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
        val surface = surface
        if (surface == null) {
            // Surface liberee (ecran ferme) ou pas encore dimensionnee : rien a composer.
            recordedCalls.clear()
            return
        }
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

    /**
     * Libere tout ce que la surface possede cote GPU.
     *
     * Rien ne le faisait : chaque ecran Compose ouvert laissait derriere lui une texture de la
     * taille de la fenetre (~15 Mo en 2560x1440, ~33 Mo en 4K), deux FBO et une surface Skia,
     * jamais recuperes -- les objets OpenGL ne sont pas ramasses par le GC.
     *
     * Idempotent. Un resize ulterieur recree tout.
     */
    fun close() {
        recordedCalls.clear()
        if (buffer != 0) {
            // Cree par ensureBuffer(), hors run : il appartient au contexte de Minecraft.
            GL30.glDeleteFramebuffers(buffer)
            buffer = 0
        }

        val oldSurface = surface
        val oldTarget = target
        surface = null
        target = null
        if (oldSurface == null && oldTarget == null && !textureHolder.isInitialized()) return

        SkiaContext.run {
            oldSurface?.close()
            oldTarget?.close()
            // Detruit la texture et SON FBO, ne dans ce contexte : le supprimer depuis celui de
            // Minecraft effacerait sous Windows un autre FBO portant le meme numero.
            if (textureHolder.isInitialized()) texture.destroyBuffers()
        }
        // destroyBuffers() passe par GlStateManager depuis le contexte de Skia : meme
        // resynchronisation que dans resize().
        GlStateGuard.restore()
    }
}

val LocalSkiaSurface = staticCompositionLocalOf<SkiaSurface> { error("No SkiaSurface provided") }
