package dev.aperso.composite.core

import androidx.compose.runtime.Composable
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics

/**
 * A HUD element backed by a Compose UI.
 *
 * Register with:
 * ```
 * HudElementRegistry.addLast(
 *     Identifier.fromNamespaceAndPath("modid", "my_hud"),
 *     composeHud
 * )
 * ```
 */
open class ComposeHud(content: @Composable () -> Unit) : HudElement {
    val gui = ComposeGui(content)

    override fun render(graphics: GuiGraphics, deltaTracker: DeltaTracker) {
        gui.init()
        gui.render(graphics, 0, 0, deltaTracker.getGameTimeDeltaPartialTick(true))
    }
}