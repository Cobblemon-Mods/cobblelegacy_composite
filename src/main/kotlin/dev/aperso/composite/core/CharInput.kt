package dev.aperso.composite.core

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWCharCallback

/**
 * Saisie de texte des scenes Compose : un seul callback GLFW de caracteres pour tout le mod.
 *
 * Chaque ComposeGui en installait un a chaque init(). LWJGL retient un callback par une
 * reference globale JNI jusqu'a son free(), qui n'etait jamais appele ; or celui-ci capturait le
 * ComposeGui. Chaque ecran ouvert restait donc en memoire pour toujours -- scene Compose, etat,
 * surface Skia compris -- meme apres un GC. Le liberer a la fermeture ne suffit pas : quand un
 * HUD et un ecran se chevauchent, l'un peut restaurer un callback que l'autre vient de liberer.
 *
 * Ici le callback natif est cree une fois pour la vie du jeu. Les scenes s'inscrivent a leur
 * init() et se desinscrivent a leur fermeture ; la derniere inscrite recoit les caracteres,
 * comme avant ou le dernier init() installait son callback par-dessus les autres.
 *
 * Minecraft n'est pas concerne : il ecoute glfwSetCharModsCallback, un canal distinct.
 *
 * Tout se passe sur le thread de rendu, comme les evenements GLFW.
 */
internal object CharInput {
    private val targets = ArrayList<ComposeGui>()

    private val callback: GLFWCharCallback by lazy {
        GLFWCharCallback.create { _, codepoint -> targets.lastOrNull()?.onChar(codepoint) }
    }

    /** Callback en place avant le notre (un autre mod), remis quand plus aucune scene n'ecoute. */
    private var previous: GLFWCharCallback? = null
    private var installed = false

    fun register(gui: ComposeGui) {
        targets.remove(gui)
        targets.add(gui)
        if (!installed) {
            previous = GLFW.glfwSetCharCallback(window(), callback)
            installed = true
        }
    }

    fun unregister(gui: ComposeGui) {
        targets.remove(gui)
        if (installed && targets.isEmpty()) {
            GLFW.glfwSetCharCallback(window(), previous)
            previous = null
            installed = false
        }
    }

    private fun window(): Long = Minecraft.getInstance().window.window
}
