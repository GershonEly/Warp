package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.AiException
import dev.ely.warp.ai.AiProvider
import dev.ely.warp.ai.KeyVault
import dev.ely.warp.ai.ProviderRegistry
import dev.ely.warp.ui.theme.HairlineWidth
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpRadius
import dev.ely.warp.ui.theme.WarpSpace
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keys, one card per provider.
 *
 * Only keys live here. Which model to use — and how hard it should think — is
 * chosen in the chat's own picker, because that is a per-conversation decision
 * rather than a setting.
 *
 * Keys are stored per provider, so several can be saved at once and switching
 * between them costs nothing.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val registry = remember { ProviderRegistry(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WarpSpace.screen, vertical = WarpSpace.screen),
    ) {
        Text(
            "AI keys",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.size(WarpSpace.tiny))
        Text(
            "Warp ships with no API key. Bring your own — each is encrypted by " +
                "the Android Keystore and never leaves this phone. Add as many " +
                "as you like, then pick a model in the chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(WarpSpace.section))

        registry.providers
            .filter { it.requiresKey }
            .forEach { provider ->
                ProviderKeyCard(provider)
                Spacer(Modifier.size(WarpSpace.medium))
            }

        Spacer(Modifier.size(WarpSpace.large))
        Footnote(
            "The Mock AI needs no key and always works — it replays scripted " +
                "answers so the whole app can be used before any key exists."
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Footnote(
            "Keys are encrypted with a key generated inside the Android Keystore, " +
                "which cannot be read out of the device. They are never written to " +
                "a log, never sent anywhere except their own provider, and are not " +
                "in the repository."
        )
        Spacer(Modifier.size(WarpSpace.section))
    }
}

@Composable
private fun ProviderKeyCard(provider: AiProvider) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf(KeyVault.masked(context, provider.id)) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(WarpRadius.medium),
            )
            .border(
                HairlineWidth,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(WarpRadius.medium),
            )
            .padding(WarpSpace.card),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // A dot and a word. A coloured pill for every provider turned the
            // screen into a traffic light.
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (savedKey != null) WarpSuccess else WarpWarning,
                        CircleShape,
                    )
            )
            Spacer(Modifier.size(WarpSpace.small))
            Text(
                if (savedKey != null) "key saved" else "no key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        savedKey?.let { masked ->
            Spacer(Modifier.size(WarpSpace.small))
            Ltr {
                Text(
                    masked,
                    style = WarpMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it; test = TestState.Idle },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (savedKey == null) "Paste key" else "Replace key") },
            singleLine = true,
            shape = RoundedCornerShape(WarpRadius.small),
            // A key on screen is a key over someone's shoulder.
            visualTransformation = PasswordVisualTransformation(),
        )

        Spacer(Modifier.size(WarpSpace.medium))

        Row(horizontalArrangement = Arrangement.spacedBy(WarpSpace.small)) {
            Button(
                enabled = keyInput.isNotBlank(),
                onClick = {
                    KeyVault.save(context, provider.id, keyInput.trim())
                    savedKey = KeyVault.masked(context, provider.id)
                    keyInput = ""
                    test = TestState.Idle
                },
            ) { Text("Save") }

            OutlinedButton(
                enabled = savedKey != null && test !is TestState.Testing,
                onClick = {
                    test = TestState.Testing
                    scope.launch { test = runTest(provider) }
                },
            ) { Text("Test") }

            if (savedKey != null) {
                OutlinedButton(onClick = {
                    KeyVault.delete(context, provider.id)
                    savedKey = null
                    test = TestState.Idle
                }) { Text("Remove") }
            }
        }

        when (val state = test) {
            is TestState.Idle -> Unit

            is TestState.Testing -> {
                Spacer(Modifier.size(WarpSpace.medium))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The mark again, not a spinner — every wait in Warp is
                    // signed by the same object turning.
                    ThinkingMark(size = 16.dp)
                    Spacer(Modifier.size(WarpSpace.medium))
                    Text(
                        "Checking…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is TestState.Ok -> {
                Spacer(Modifier.size(WarpSpace.medium))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = WarpSuccess,
                    )
                    Spacer(Modifier.size(WarpSpace.small))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarpSuccess,
                    )
                }
            }

            is TestState.Failed -> {
                Spacer(Modifier.size(WarpSpace.medium))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(WarpSpace.small))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The small print at the foot of the screen.
 *
 * Indented behind a hairline rather than set in yet another shade of grey —
 * a rule says "aside" at a glance, where a fourth grey only says "low
 * contrast".
 */
@Composable
private fun Footnote(text: String) {
    // IntrinsicSize.Min lets the Row measure to the text's own height first, so
    // the rule can then fill it. Without it, fillMaxHeight would take the whole
    // screen's height constraint and draw a line down the page.
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(HairlineWidth)
                .fillMaxHeight()
                // `outline`, not `outlineVariant`: a rule this short at 8% alpha
                // is not visible at all.
                .background(MaterialTheme.colorScheme.outline)
        )
        Spacer(Modifier.size(WarpSpace.medium))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val message: String) : TestState
    data class Failed(val message: String) : TestState
}

private suspend fun runTest(provider: AiProvider): TestState = withContext(Dispatchers.IO) {
    provider.testConnection().fold(
        onSuccess = { TestState.Ok(it) },
        onFailure = { error ->
            // AiError messages are already written for a person; anything else
            // at least names its type so the failure is diagnosable.
            val message = (error as? AiException)?.error?.message
                ?: "${error.javaClass.simpleName}: ${error.message}"
            TestState.Failed(message)
        },
    )
}
