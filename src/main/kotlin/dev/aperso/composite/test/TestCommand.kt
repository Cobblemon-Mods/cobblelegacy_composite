package dev.aperso.composite.test

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.aperso.composite.core.ComposeScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

abstract class TestCommand(private val name: String) {
    abstract val content: @Composable () -> Unit

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name).executes {
            Minecraft.getInstance().tell {
                Minecraft.getInstance().setScreen(ComposeScreen(
                    title = Component.literal("Test: $name"),
                    content = content
                ))
            }
            1
        }
    }
}
