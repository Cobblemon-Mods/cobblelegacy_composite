package dev.aperso.composite.skia

import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33

/**
 * Remet le contexte OpenGL de Minecraft dans un etat coherent apres un rendu Skia.
 *
 * Skia parle a OpenGL en direct. Quand il partage le contexte de Minecraft (tout sauf Windows,
 * qui obtient un contexte dedie avec share-lists dans [SkiaContext]), il laisse le contexte dans
 * *son* etat -- et GlStateManager, qui met en cache presque tout ce qu'il ecrit, n'en sait rien.
 *
 * Deux consequences observees sur macOS :
 *
 *  - Skia lie des **sampler objects** (glBindSampler) et ne les delie jamais. Un sampler lie
 *    ecrase le GL_TEXTURE_MIN/MAG_FILTER de la texture : le GL_NEAREST de Minecraft est ignore
 *    et tout le jeu devient flou. Le vanilla n'appelle jamais glBindSampler, donc rien ne les
 *    nettoie : le flou survit a la fermeture de l'ecran.
 *  - Skia laisse GL_SCISSOR_TEST actif avec sa propre boite de decoupe alors que GlStateManager
 *    croit toujours le scissor desactive. Les appels Minecraft differes (items, textures) sont
 *    alors decoupes hors champ -> sprites invisibles.
 *
 * Tout passe volontairement par l'API publique de GlStateManager : ecrire une valeur puis une
 * autre garantit au moins un vrai appel GL, ce qui resynchronise le cache et le driver sans
 * toucher a des champs prives par leur nom (ils sont obfusques en intermediary a l'execution).
 */
object GlStateGuard {

    private val samplerObjectsSupported: Boolean by lazy {
        try {
            val caps = GL.getCapabilities()
            caps.OpenGL33 || caps.GL_ARB_sampler_objects
        } catch (_: Throwable) {
            false
        }
    }

    /** Texture bidon, jamais echantillonnee, servant a forcer un vrai glBindTexture. */
    private var probeTexture = 0

    private fun probeTexture(): Int {
        if (probeTexture == 0) probeTexture = GlStateManager._genTexture()
        return probeTexture
    }

    /**
     * A appeler juste apres chaque bloc de rendu Skia execute dans le contexte de Minecraft.
     * No-op quand [SkiaContext] a reussi a isoler Skia dans son propre contexte.
     */
    fun restoreAfterSkia() {
        if (SkiaContext.isIsolated) return
        restore()
    }

    fun restore() {
        restoreUnmanagedState()
        restoreTextureUnits()
        resyncCachedState()
    }

    /**
     * Etat que Minecraft ne pilote jamais : lui seul ne le remettra pas d'aplomb, c'est donc a
     * nous de le faire.
     */
    private fun restoreUnmanagedState() {
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB)
        GL11.glDisable(GL11.GL_DITHER)

        GlStateManager._glBindVertexArray(0)
        GlStateManager._glUseProgram(0)
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)

        // Skia positionne ROW_LENGTH/ALIGNMENT quand il televerse ses atlas.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
    }

    /**
     * Delie les sampler objects de Skia (la cause du flou) et resynchronise le cache de liaison
     * de textures de GlStateManager unite par unite.
     */
    private fun restoreTextureUnits() {
        val probe = probeTexture()

        // Force un vrai glActiveTexture quoi qu'en dise le cache.
        GlStateManager._activeTexture(GL13.GL_TEXTURE1)
        GlStateManager._activeTexture(GL13.GL_TEXTURE0)

        for (unit in 0 until GlStateManager.TEXTURE_COUNT) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit)
            if (samplerObjectsSupported) GL33.glBindSampler(unit, 0)
            // Deux liaisons differentes : l'une des deux passe forcement le cache.
            GlStateManager._bindTexture(probe)
            GlStateManager._bindTexture(0)
        }

        GlStateManager._activeTexture(GL13.GL_TEXTURE0)
    }

    /**
     * Resynchronise tout l'etat que GlStateManager met en cache. Chaque valeur est ecrite deux
     * fois avec deux valeurs distinctes : le cache laisse forcement passer la seconde, donc GL
     * et cache finissent d'accord. On termine sur les valeurs par defaut du vanilla ; Minecraft
     * reactive ensuite ce dont il a besoin, et ses appels prendront enfin effet.
     */
    private fun resyncCachedState() {
        GlStateManager._enableScissorTest();   GlStateManager._disableScissorTest()
        GlStateManager._enableBlend();         GlStateManager._disableBlend()
        GlStateManager._enableDepthTest();     GlStateManager._disableDepthTest()
        GlStateManager._enableCull();          GlStateManager._disableCull()
        GlStateManager._enablePolygonOffset(); GlStateManager._disablePolygonOffset()
        GlStateManager._enableColorLogicOp();  GlStateManager._disableColorLogicOp()

        GlStateManager._depthMask(false)
        GlStateManager._depthMask(true)

        GlStateManager._depthFunc(GL11.GL_ALWAYS)
        GlStateManager._depthFunc(GL11.GL_LEQUAL)

        GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO)
        GlStateManager._blendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ZERO
        )
        GlStateManager._blendEquation(GL14.GL_FUNC_ADD)

        GlStateManager._colorMask(false, false, false, false)
        GlStateManager._colorMask(true, true, true, true)
    }
}
