package dev.aperso.composite.skia

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import java.util.ArrayDeque
import java.util.Deque

/**
 * AbstractTexture wrapper that exposes a GpuTexture managed by SkiaSurface.
 * Does NOT close the underlying textures - SkiaSurface manages their lifecycle.
 */
private class SkiaBackedTexture : AbstractTexture() {
    fun update(tex: GpuTexture?, view: GpuTextureView?) {
        this.texture = tex
        this.textureView = view
    }

    fun clearRefs() {
        this.texture = null
        this.textureView = null
    }

    override fun close() {
        // Don't close GpuTexture/View - SkiaSurface manages lifecycle
        clearRefs()
    }
}

class SkiaSurface {
    companion object {
        private val TEXTURE_ID = Identifier.fromNamespaceAndPath("composite", "skia_surface")
    }

    private var skiaFbo: Int = 0
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0

    private var gpuTexture: GpuTexture? = null
    private var gpuTextureView: GpuTextureView? = null

    private var backendTarget: BackendRenderTarget? = null
    private var skiaSurface: Surface? = null

    private val skiaTexture = SkiaBackedTexture()
    private var textureRegistered = false

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (currentWidth == width && currentHeight == height) return

        // 1. Clear references in the AbstractTexture wrapper FIRST
        //    so the render pipeline won't access stale textures
        skiaTexture.clearRefs()

        // 2. Close Skia resources (must be done before GL texture is freed)
        skiaSurface?.let { surface ->
            SkiaContext.run {
                surface.close()
            }
        }
        skiaSurface = null
        backendTarget?.let { target ->
            SkiaContext.run {
                target.close()
            }
        }
        backendTarget = null

        // 3. Close old MC textures
        gpuTextureView?.close()
        gpuTextureView = null
        gpuTexture?.close()
        gpuTexture = null

        currentWidth = width
        currentHeight = height

        // 4. Create new GpuTexture through MC's device
        val device = RenderSystem.getDevice()
        val usage = GpuTexture.USAGE_COPY_DST or
                GpuTexture.USAGE_COPY_SRC or
                GpuTexture.USAGE_TEXTURE_BINDING or
                GpuTexture.USAGE_RENDER_ATTACHMENT
        gpuTexture = device.createTexture(
            { "Composite Skia Surface" }, usage,
            TextureFormat.RGBA8, width, height, 1, 1
        )
        gpuTextureView = device.createTextureView(gpuTexture!!)

        // 5. Create FBO for Skia rendering
        val glId = (gpuTexture as GlTexture).glId()
        if (skiaFbo == 0) skiaFbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, skiaFbo)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, glId, 0
        )
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)

        // 6. Create Skia surface
        SkiaContext.run {
            val context = SkiaContext.directContext
            val bt = BackendRenderTarget.makeGL(
                width, height, 0, 8, skiaFbo, GL30.GL_RGBA8
            )
            backendTarget = bt
            skiaSurface = Surface.makeFromBackendRenderTarget(
                context, bt,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB
            ) ?: throw RuntimeException("Failed to create Skia surface")
        }

        // 7. Update wrapper with new textures
        skiaTexture.update(gpuTexture!!, gpuTextureView!!)
    }

    private val recordedCalls: Deque<GuiGraphics.() -> Unit> = ArrayDeque()

    fun record(call: GuiGraphics.() -> Unit) {
        recordedCalls.push(call)
    }

    fun render(guiGraphics: GuiGraphics, render: (Canvas) -> Unit) {
        val surface = skiaSurface ?: return
        if (gpuTexture == null) return

        // Register texture with MC's TextureManager (once)
        if (!textureRegistered) {
            Minecraft.getInstance().textureManager.register(TEXTURE_ID, skiaTexture)
            textureRegistered = true
        }

        // 1. Render Compose UI via Skia
        SkiaContext.run {
            surface.canvas.clear(0x00000000.toInt())
            render(surface.canvas.asComposeCanvas())
            SkiaContext.directContext.resetGLAll()
            SkiaContext.directContext.flush()
        }

        // 2. Submit Skia texture to GuiRenderState via blit
        val guiW = guiGraphics.guiWidth()
        val guiH = guiGraphics.guiHeight()
        // blit(location, x0, y0, x1, y1, u0, u1, v0, v1)
        // Skia BOTTOM_LEFT → flip V: v0=1, v1=0
        guiGraphics.blit(TEXTURE_ID, 0, 0, guiW, guiH, 0f, 1f, 1f, 0f)

        // 3. Replay recorded GuiGraphics calls
        while (true) {
            val call = recordedCalls.poll() ?: break
            call.invoke(guiGraphics)
        }
    }
}

val LocalSkiaSurface = staticCompositionLocalOf<SkiaSurface> { error("No SkiaSurface provided") }