package dev.aperso.composite.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import net.minecraft.network.chat.Component

@Composable
fun translate(key: String, vararg args: Any): String {
    val locale = LocalLocale.current
    return remember(key, args.toList(), locale) {
        translateDirect(key, *args)
    }
}

@Composable
fun translateOrNull(key: String, vararg args: Any): String? {
    val locale = LocalLocale.current
    return remember(key, args.toList(), locale) {
        try {
            val component = createTranslatableComponent(key, *args)
            val result = component.string
            if (result != key) result else null
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun translateAnnotated(key: String, vararg args: Any): AnnotatedString {
    val locale = LocalLocale.current
    return remember(key, args.toList(), locale) {
        translateAnnotatedDirect(key, *args)
    }
}

fun translateDirect(key: String, vararg args: Any): String {
    val component = createTranslatableComponent(key, *args)
    return component.string
}

fun translateAnnotatedDirect(key: String, vararg args: Any): AnnotatedString {
    val component = createTranslatableComponent(key, *args)
    return component.toAnnotatedString()
}

private fun createTranslatableComponent(key: String, vararg args: Any): Component {
    val mcArgs = args.map { arg ->
        when (arg) {
            is Component -> arg
            is String -> Component.literal(arg)
            is Number -> Component.literal(arg.toString())
            else -> Component.literal(arg.toString())
        }
    }.toTypedArray()

    return Component.translatable(key, *mcArgs)
}