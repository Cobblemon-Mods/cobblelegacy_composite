package dev.aperso.composite.skia

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
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

    fun record(call: GuiGraphics.() -> Unit) {
        recordedCalls.push(call)
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
        
        main.bindWrite(true)
        RenderSystem.enableBlend()
        texture.blitToScreen(main.width, main.height, false)
        
        while (true) {
            val call = recordedCalls.poll() ?: break
            call.invoke(guiGraphics)
        }
    }
}

val LocalSkiaSurface = staticCompositionLocalOf<SkiaSurface> { error("No SkiaSurface provided") }
