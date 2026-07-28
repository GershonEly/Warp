package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import dev.ely.warp.ui.theme.WarpMono
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
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            "AI keys",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "Warp ships with no API key. Bring your own — each is encrypted by " +
                "the Android Keystore and never leaves this phone. Add as many " +
                "as you like, then pick a model in the Chat tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(20.dp))

        registry.providers
            .filter { it.requiresKey }
            .forEach { provider ->
                ProviderKeyCard(provider)
                Spacer(Modifier.size(14.dp))
            }

        Spacer(Modifier.size(6.dp))
        Text(
            "The 🎭 Mock AI needs no key and always works — it replays scripted " +
                "answers so the whole app can be used before any key exists.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(16.dp))
        Text(
            "Keys are encrypted with a key generated inside the Android Keystore, " +
                "which cannot be read out of the device. They are never written to " +
                "a log, never sent anywhere except their own provider, and are not " +
                "in the repository.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                provider.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (savedKey != null) "key saved" else "no key",
                style = MaterialTheme.typography.labelMedium,
                color = if (savedKey != null) WarpSuccess else WarpWarning,
            )
        }

        savedKey?.let { masked ->
            Spacer(Modifier.size(8.dp))
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
            // A key on screen is a key over someone's shoulder.
            visualTransformation = PasswordVisualTransformation(),
        )

        Spacer(Modifier.size(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is TestState.Ok -> {
                Spacer(Modifier.size(12.dp))
                Text(
                    "✓ ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarpSuccess,
                )
            }

            is TestState.Failed -> {
                Spacer(Modifier.size(12.dp))
                Text(
                    "✕ ${state.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
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
