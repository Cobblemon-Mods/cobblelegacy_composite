package dev.aperso.composite.diag

import dev.aperso.composite.Composite
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33
import org.lwjgl.system.MemoryStack

/**
 * Sonde de diagnostic pour le rejeu des appels Minecraft differes.
 *
 * Skia et le rejeu partagent le meme contexte GL hors Windows ; quand les sprites ne sortent
 * pas, il faut savoir *ou* la chaine casse. Active par /composite diag, cette sonde dessine
 * quatre marqueurs a la suite, chacun exercant une couche differente :
 *
 *  1. carre magenta  -> GuiGraphics.fill avant le rejeu (chemin 2D le plus simple)
 *  2. epee diamant   -> renderFakeItem apres le rejeu (chemin ItemRenderer / atlas / entite)
 *  3. bloc de terre  -> blit d'une texture Minecraft (chemin texture + shader position_tex)
 *  4. carre cyan     -> GuiGraphics.fill apres tout le reste
 *  5. pomme doree    -> meme item, mais via un GuiGraphics construit a la main (le pattern
 *                       copie dans GTS, Pokematos, Breeding, Jobs, Crate, Monture...)
 *
 * Lecture du resultat en jeu, ecran Compose ouvert :
 *  - rien du tout          -> le rejeu entier n'atteint pas l'ecran (FBO, viewport, colorMask)
 *  - magenta + cyan seuls  -> le 2D passe, c'est le rendu d'items/textures qui casse
 *  - 1 a 4 mais pas 5      -> le rejeu va bien ; c'est le GuiGraphics detache qui ne sort pas
 *  - les cinq marqueurs    -> le rejeu va bien : le probleme est dans les coordonnees ou le
 *                             scissor calcules par les composants appelants
 */
object RenderDiagnostics {

    @Volatile
    var enabled = false
        private set

    private var frame = 0

    private val dirt = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/dirt.png")

    fun toggle(): Boolean {
        enabled = !enabled
        frame = 0
        return enabled
    }

    fun beforeReplay(guiGraphics: GuiGraphics, pending: Int) {
        if (!enabled) return
        drainErrors("before-replay")
        guiGraphics.fill(8, 8, 40, 40, 0xFFFF00FF.toInt())
        guiGraphics.flush()
        if (frame % 60 == 0) log("before replay, $pending pending call(s)")
    }

    fun afterReplay(guiGraphics: GuiGraphics, replayed: Int) {
        if (!enabled) return
        drainErrors("after-replay-calls")

        runCatching {
            guiGraphics.renderFakeItem(ItemStack(Items.DIAMOND_SWORD), 48, 8)
            guiGraphics.flush()
        }.onFailure { Composite.logger.warn("[diag] renderFakeItem threw", it) }
        drainErrors("after-renderFakeItem")

        runCatching {
            guiGraphics.blit(dirt, 88, 8, 0f, 0f, 16, 16, 16, 16)
            guiGraphics.flush()
        }.onFailure { Composite.logger.warn("[diag] blit threw", it) }
        drainErrors("after-blit")

        guiGraphics.fill(136, 8, 168, 40, 0xFF00FFFF.toInt())
        guiGraphics.flush()
        drainErrors("after-fill")

        // Reproduit exactement le pattern des mods appelants : un GuiGraphics detache,
        // construit a la main sur le bufferSource partage, au lieu de celui du rejeu.
        runCatching {
            val minecraft = Minecraft.getInstance()
            val detached = GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())
            detached.pose().mulPose(guiGraphics.pose().last().pose())
            detached.renderItem(ItemStack(Items.GOLDEN_APPLE), 176, 8)
            detached.flush()
        }.onFailure { Composite.logger.warn("[diag] detached GuiGraphics threw", it) }
        drainErrors("after-detached-GuiGraphics")

        if (frame % 60 == 0) log("after replay, $replayed call(s) replayed")
        frame++
    }

    private fun drainErrors(stage: String) {
        var error = GL11.glGetError()
        var guard = 0
        while (error != GL11.GL_NO_ERROR && guard++ < 16) {
            Composite.logger.warn("[diag] GL error 0x{} at {}", Integer.toHexString(error), stage)
            error = GL11.glGetError()
        }
    }

    private fun log(stage: String) {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val main = minecraft.mainRenderTarget

        val scissorBox = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox)
        val viewport = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)

        val colorMask = MemoryStack.stackPush().use { stack ->
            val buffer = stack.malloc(4)
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, buffer)
            (0 until 4).map { buffer.get(it).toInt() != 0 }
        }

        Composite.logger.info(
            """
            [diag] $stage
              window        = ${window.width}x${window.height} guiScale=${window.guiScale} scaled=${window.guiScaledWidth}x${window.guiScaledHeight}
              drawFbo       = ${GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)} (mainRenderTarget=${main.frameBufferId}, view=${main.viewWidth}x${main.viewHeight})
              viewport      = ${viewport.joinToString()}
              scissor       = enabled=${GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)} box=${scissorBox.joinToString()}
              depth         = test=${GL11.glIsEnabled(GL11.GL_DEPTH_TEST)} func=${GL11.glGetInteger(GL11.GL_DEPTH_FUNC)} mask=${GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)}
              blend/cull    = blend=${GL11.glIsEnabled(GL11.GL_BLEND)} cull=${GL11.glIsEnabled(GL11.GL_CULL_FACE)} stencil=${GL11.glIsEnabled(GL11.GL_STENCIL_TEST)}
              colorMask     = ${colorMask.joinToString()}
              program       = ${GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)} vao=${GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)}
              texture unit  = active=${GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0} bound2d=${GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)} sampler=${GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)}
            """.trimIndent()
        )
    }
}
