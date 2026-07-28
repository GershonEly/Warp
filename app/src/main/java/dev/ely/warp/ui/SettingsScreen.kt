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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import dev.ely.warp.ai.AiProvider
import dev.ely.warp.ai.AiException
import dev.ely.warp.ai.Effort
import dev.ely.warp.ai.KeyVault
import dev.ely.warp.ai.ProviderRegistry
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a provider, paste a key, test it.
 *
 * This is the screen the whole harness was built for: everything else already
 * works against the mock, and this is where a real model gets plugged in.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { ProviderRegistry(context) }

    var selected by remember { mutableStateOf(registry.selected) }
    var effort by remember { mutableStateOf(registry.effort) }
    var keyInput by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf(KeyVault.masked(context, selected.id)) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            "AI Provider",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "Warp ships with no API key. Bring your own — it is encrypted by " +
                "the Android Keystore and never leaves this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(20.dp))

        // ── provider picker ──────────────────────────────────────────────
        SettingsCard(title = "Provider") {
            registry.providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = provider.id == selected.id,
                            onClick = {
                                selected = provider
                                registry.selected = provider
                                savedKey = KeyVault.masked(context, provider.id)
                                keyInput = ""
                                test = TestState.Idle
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = provider.id == selected.id, onClick = null)
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (provider.requiresKey) provider.displayName
                            else "🎭 ${provider.displayName}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (provider.requiresKey) {
                                if (registry.hasKey(provider)) "key saved" else "needs a key"
                            } else "no key needed · scripted answers",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (provider.requiresKey && !registry.hasKey(provider)) {
                                WarpWarning
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.size(16.dp))

        // ── key ──────────────────────────────────────────────────────────
        if (selected.requiresKey) {
            SettingsCard(title = "API key") {
                savedKey?.let { masked ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Saved:", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.size(8.dp))
                        Ltr {
                            Text(
                                masked,
                                style = WarpMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                }

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it; test = TestState.Idle },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (savedKey == null) "Paste your key" else "Replace key") },
                    singleLine = true,
                    // Keys are secrets; don't render them on screen.
                    visualTransformation = PasswordVisualTransformation(),
                )

                Spacer(Modifier.size(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = keyInput.isNotBlank(),
                        onClick = {
                            KeyVault.save(context, selected.id, keyInput.trim())
                            savedKey = KeyVault.masked(context, selected.id)
                            keyInput = ""
                            test = TestState.Idle
                        },
                    ) { Text("Save") }

                    OutlinedButton(
                        enabled = savedKey != null && test !is TestState.Testing,
                        onClick = {
                            test = TestState.Testing
                            scope.launch {
                                test = runTest(selected)
                            }
                        },
                    ) { Text("Test connection") }

                    if (savedKey != null) {
                        OutlinedButton(onClick = {
                            KeyVault.delete(context, selected.id)
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

            Spacer(Modifier.size(16.dp))
        }

        // ── effort ───────────────────────────────────────────────────────
        SettingsCard(title = "How hard to think") {
            Text(
                "Higher settings give better answers on hard problems, and cost more.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Effort.entries.forEach { level ->
                    FilterChip(
                        selected = level == effort,
                        onClick = { effort = level; registry.effort = level },
                        label = { Text(level.label) },
                    )
                }
            }
        }

        Spacer(Modifier.size(24.dp))

        Text(
            "Your key is encrypted with a key held in the Android Keystore, which " +
                "cannot be read out of the device. It is never written to a log, " +
                "never sent anywhere except the provider, and is not in the repo.",
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
            // AiException carries a message already written for a person;
            // anything else gets its class name so it is at least diagnosable.
            val message = (error as? AiException)?.error?.message
                ?: "${error.javaClass.simpleName}: ${error.message}"
            TestState.Failed(message)
        },
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        content()
    }
}
