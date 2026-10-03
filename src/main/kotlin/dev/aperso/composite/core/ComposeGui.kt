package dev.aperso.composite.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.input.BackspaceCommand
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.aperso.composite.i18n.LocalLocale
import dev.aperso.composite.skia.GlStateGuard
import dev.aperso.composite.skia.LocalSkiaSurface
import dev.aperso.composite.skia.SkiaContext
import dev.aperso.composite.skia.SkiaSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.jetbrains.skiko.currentNanoTime
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.pow

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
open class ComposeGui(
    val content: @Composable () -> Unit,
) : PlatformContext by PlatformContext.Empty {
    private val minecraft = Minecraft.getInstance()
    private val surface = SkiaSurface()
    private val scene = CanvasLayersComposeScene(platformContext = this)

    private val clipboard = object : Clipboard {
        override val nativeClipboard = Any()

        override suspend fun getClipEntry(): ClipEntry? {
            val text = minecraft?.keyboardHandler?.clipboard ?: return null
            return ClipEntry(StringSelection(text))
        }

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            val transferable = clipEntry?.nativeClipEntry as? Transferable
            if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    val text = withContext(Dispatchers.IO) {
                        transferable.getTransferData(DataFlavor.stringFlavor)
                    } as? String
                    if (text != null) {
                        minecraft?.keyboardHandler?.clipboard = text
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private var scale = Float.NaN

    // Multiplicateur de distance par cran de molette. Monte-le pour defiler plus loin, baisse-le pour plus fin.
    private val scrollSpeed = 1.0f

    // Scroll a inertie douce : on accumule le delta puis on l'etale sur plusieurs frames (defilement
    // fluide) -> on VOIT tout le contenu passer, rien n'est saute. Coupe pendant un appui (cf. pressed).
    private var lastScrollTime = currentNanoTime()
    private var scrollX = 0f
    private var scrollY = 0f
    private var pressed = false

    /** Derniere position envoyee a Compose. */
    private var lastPointer: Offset? = null

    init {
        scene.setContent {
            CompositionLocalProvider(
                LocalSkiaSurface provides surface,
                LocalClipboard provides clipboard,
                LocalLocale provides (minecraft?.options?.languageCode ?: "en_us")
            ) {
                content()
            }
        }
    }

    private var onEditCommand: ((List<EditCommand>) -> Unit)? = null

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        try {
            onEditCommand = request.onEditCommand
            awaitCancellation()
        } finally {
            onEditCommand = null
        }
    }

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        val shape = when (pointerIcon) {
            PointerIcon.Hand -> GLFW.GLFW_HAND_CURSOR
            PointerIcon.Text -> GLFW.GLFW_IBEAM_CURSOR
            PointerIcon.Crosshair -> GLFW.GLFW_CROSSHAIR_CURSOR
            else -> GLFW.GLFW_ARROW_CURSOR
        }
        minecraft?.window?.let {
            GLFW.glfwSetCursor(it.window, StandardCursors.get(shape))
        }
    }

    /** Appele par [CharInput] quand cette scene est celle qui recoit la saisie. */
    internal fun onChar(codepoint: Int) {
        onEditCommand?.invoke(listOf(CommitTextCommand(Char(codepoint).toString(), 1)))
    }

    open fun init() {
        val window = minecraft.window
        surface.resize(window.width, window.height)
        scale = window.guiScale.toFloat()
        scene.size = IntSize(window.width, window.height)
        scene.density = Density(scale * 0.5f, 1.0f)
        CharInput.register(this)
    }

    private var closed = false

    open fun onClose() {
        if (closed) return
        closed = true
        scene.close()
        // Liberation GPU differee au prochain tick : onClose peut etre declenche depuis le rendu
        // de la scene (un effet qui ferme l'ecran), donc a l'interieur de SkiaContext.run, ou
        // sous Windows les FBO de Minecraft et de Skia ne sont pas dans le meme contexte. Au
        // prochain tick on est toujours hors rendu, dans le contexte de Minecraft.
        minecraft.tell { surface.close() }
        SkiaContext.run {
            SkiaContext.directContext.resetGLAll()
            SkiaContext.directContext.flush()
        }
        GlStateGuard.restoreAfterSkia()
        // La cible principale garde le filtre que Skia lui a laisse tant qu'on ne la force pas.
        val main = minecraft.mainRenderTarget
        main.filterMode = -1
        main.setFilterMode(9728) // GL_NEAREST
        CharInput.unregister(this)
        // Sans ca, une main de survol restait affichee sur l'ecran suivant, vanilla compris.
        GLFW.glfwSetCursor(minecraft.window.window, 0L)
    }

    open fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (closed) return
        val pointer = Offset(mouseX * scale, mouseY * scale)
        // Un Move a position inchangee ne coute qu'un hit-test pour rien. Si la mise en page
        // bouge sous un pointeur immobile, Compose renvoie de lui-meme la position apres le
        // layout (SyntheticEventSender) : le survol reste juste.
        if (pointer != lastPointer) {
            lastPointer = pointer
            scene.sendPointerEvent(PointerEventType.Move, pointer)
        }
        val currentTime = currentNanoTime()
        val deltaT = (currentTime - lastScrollTime).shr(16) * 0.001f
        lastScrollTime = currentTime
        // Inertie douce, mais JAMAIS pendant un appui : un event Scroll entre Press et Release
        // serait interprete comme un scroll par le parent et annulerait le clic de l'enfant.
        if (!pressed && (abs(scrollX) > 0.01f || abs(scrollY) > 0.01f)) {
            val decayX = scrollX - scrollX * 0.3f.pow(deltaT)
            val decayY = scrollY - scrollY * 0.3f.pow(deltaT)
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                Offset(mouseX * scale, mouseY * scale),
                Offset(decayX * scale, decayY * scale)
            )
            scrollX -= decayX
            scrollY -= decayY
            if (abs(scrollX) < 0.01f) scrollX = 0f
            if (abs(scrollY) < 0.01f) scrollY = 0f
        }
        surface.render(guiGraphics) {
            scene.render(it, currentTime)
        }
    }

    open fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // Stoppe net l'inertie en cours pour qu'aucun event Scroll ne vienne annuler ce clic.
        scrollX = 0f
        scrollY = 0f
        pressed = true
        scene.sendPointerEvent(
            PointerEventType.Press,
            Offset((mouseX * scale).toFloat(), (mouseY * scale).toFloat()),
            button = PointerButton(button)
        )
        return true
    }

    open fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        pressed = false
        scene.sendPointerEvent(
            PointerEventType.Release,
            Offset((mouseX * scale).toFloat(), (mouseY * scale).toFloat()),
            button = PointerButton(button)
        )
        return true
    }

    open fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // On accumule ; render() etale l'envoi sur plusieurs frames pour un defilement fluide.
        this.scrollX += scrollX.toFloat() * scrollSpeed
        this.scrollY -= scrollY.toFloat() * scrollSpeed
        return true
    }

    private fun keyEvent(type: KeyEventType, keyCode: Int, modifiers: Int): KeyEvent {
        return KeyEvent(
            Key(
                when (keyCode) {
                    GLFW.GLFW_KEY_UP -> java.awt.event.KeyEvent.VK_UP
                    GLFW.GLFW_KEY_LEFT -> java.awt.event.KeyEvent.VK_LEFT
                    GLFW.GLFW_KEY_DOWN -> java.awt.event.KeyEvent.VK_DOWN
                    GLFW.GLFW_KEY_RIGHT -> java.awt.event.KeyEvent.VK_RIGHT
                    else -> keyCode
                }
            ),
            type,
            isCtrlPressed = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0,
            isMetaPressed = (modifiers and GLFW.GLFW_MOD_SUPER) != 0,
            isAltPressed = (modifiers and GLFW.GLFW_MOD_ALT) != 0,
            isShiftPressed = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0
        )
    }

    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val result = scene.sendKeyEvent(keyEvent(KeyEventType.KeyDown, keyCode, modifiers))
        return if (result) {
            true
        } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            onEditCommand?.invoke(listOf(BackspaceCommand()))
            true
        } else {
            false
        }
    }

    open fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        return scene.sendKeyEvent(keyEvent(KeyEventType.KeyUp, keyCode, modifiers))
    }
}

/**
 * Un curseur GLFW standard se cree une fois. glfwCreateStandardCursor a chaque changement de
 * survol en allouait un nouveau, jamais detruit. Ils restent valables pour toute fenetre et
 * vivent autant que le jeu.
 */
private object StandardCursors {
    private val cache = HashMap<Int, Long>()

    fun get(shape: Int): Long = cache.getOrPut(shape) { GLFW.glfwCreateStandardCursor(shape) }
}
