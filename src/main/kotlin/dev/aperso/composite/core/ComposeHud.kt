package dev.aperso.composite.core

import androidx.compose.runtime.Composable
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics

open class ComposeHud(content: @Composable () -> Unit): HudRenderCallback {
    val gui = ComposeGui(content)
    private var initialized = false

    override fun onHudRender(graphics: GuiGraphics, deltaTracker: DeltaTracker) {
        if (!initialized) {
            gui.init()
            initialized = true
        }
        gui.render(graphics, 0, 0, deltaTracker.realtimeDeltaTicks)
    }
}
