package dev.ely.warp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ely.warp.ai.AiException
import dev.ely.warp.ai.AiProvider
import dev.ely.warp.ai.KeyVault
import dev.ely.warp.ai.Naming
import dev.ely.warp.ai.ProviderRegistry
import dev.ely.warp.data.Appearance
import dev.ely.warp.debug.DebugBridge
import dev.ely.warp.debug.DebugServer
import dev.ely.warp.data.Identity
import dev.ely.warp.data.WebSearch
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

        Spacer(Modifier.size(WarpSpace.section))
        YourNameSection()

        Spacer(Modifier.size(WarpSpace.section))
        NamingSection(registry)

        Spacer(Modifier.size(WarpSpace.section))
        ThemeSection()

        Spacer(Modifier.size(WarpSpace.section))
        WebSearchSection(registry.choice.providerId)

        Spacer(Modifier.size(WarpSpace.section))
        AmbientSection()

        if (DebugServer.IS_SUPPORTED) {
            Spacer(Modifier.size(WarpSpace.section))
            DebugSection()
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

/**
 * What Warp should call you.
 *
 * One field, and it is the only place a name exists. It used to be a string
 * literal in the drawer, which meant Warp greeted everyone who sideloaded it as
 * its author — a bug you cannot see while you are the author.
 *
 * Empty is allowed and does not mean broken. Warp does not invent a name and
 * does not fall back to "User": a greeting that calls you something you never
 * chose is worse than a greeting with no name in it, and the avatar wears the
 * mark instead, which is always true.
 *
 * Saved as you type rather than behind a button. There is nothing to validate,
 * nothing to fail, and a Save button next to one text field is a button whose
 * only job is to be forgotten.
 */
@Composable
private fun YourNameSection() {
    val context = LocalContext.current
    val identity = remember { Identity.get(context) }
    val stored by identity.name.collectAsState()
    // The field's own state, seeded from storage. Editing writes through to
    // Identity — which is what makes the avatar change while you type.
    var name by remember { mutableStateOf(stored.orEmpty()) }

    Text("Your name", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(WarpSpace.tiny))
    Text(
        "Used to greet you, and for the letter in your avatar. It stays on this " +
            "phone. Leave it empty and Warp will not use a name at all.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.size(WarpSpace.medium))

    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it.take(Identity.MAX).replace("\n", "")
            identity.setName(name)
        },
        singleLine = true,
        placeholder = { Text("Your name") },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The key that opens the debug surface.
 *
 * Absent from release builds entirely — not greyed out, not hidden behind a
 * gesture. `IS_SUPPORTED` is false there because the server is not compiled into
 * that build, so this section would be a control for something that does not
 * exist.
 *
 * **Empty means closed**, and that is where a fresh install starts. There is no
 * default key and no weak one. The surface cannot be left open by forgetting to
 * shut it, because it was never open.
 */
@Composable
private fun DebugSection() {
    val context = LocalContext.current
    val key by DebugBridge.key.collectAsState()
    var typed by remember { mutableStateOf(key.orEmpty()) }

    Text("Debug surface", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(WarpSpace.tiny))
    Text(
        "Lets a computer on the USB cable drive this app and read what happened — " +
            "for testing. It stays shut until you set a key here, and it only " +
            "ever listens on this phone, never on a network.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.size(WarpSpace.medium))

    val mistaken = DebugBridge.looksLikeApiKey(typed)

    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it.trim()
            DebugBridge.setKey(context, typed)
        },
        singleLine = true,
        isError = mistaken,
        placeholder = { Text("Key — empty keeps it shut") },
        modifier = Modifier.fillMaxWidth(),
    )

    if (mistaken) {
        Spacer(Modifier.size(WarpSpace.small))
        Text(
            "That looks like an API key. It belongs in AI keys above, where it is " +
                "encrypted by the Keystore — this field is stored in plain text, so " +
                "it will not be saved here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.size(WarpSpace.small))
    Footnote(
        if (key == null) {
            "Closed."
        } else {
            val port = DebugServer.PORT
            "Open on port $port. On the computer:\n" +
                "adb forward tcp:$port tcp:$port\n" +
                "curl -H \"X-Warp-Key: $key\" localhost:$port/state"
        }
    )
}

/**
 * Whether the model may search the web.
 *
 * **Off by default, and for the same reason naming is:** it spends the account
 * holder's money, and *"it only costs a little"* is a judgement only they get to
 * make. So the price is on the screen rather than in a bill — half a cent a
 * search, on the OpenRouter key already paying for the conversation.
 *
 * Only OpenRouter runs a search of its own, so the row says so instead of
 * offering a switch that would change nothing. And when it is off the model is
 * *told* it is off: the failure this replaces is a model answering "I have no
 * internet access" — true, unhelpful, and said three times in one session while
 * somebody kept asking for it.
 */
@Composable
private fun WebSearchSection(providerId: String) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(WebSearch.isOn(context)) }
    val supported = providerId == "openrouter"

    Text("Web search", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(WarpSpace.tiny))
    Text(
        if (supported) {
            "Lets the model look something up when it is not sure. About half a " +
                "cent a search, on the same OpenRouter key as the conversation — " +
                "no second account. Reading a page you paste the address of is " +
                "always free and needs none of this."
        } else {
            "Only available on OpenRouter, which runs the search itself and bills " +
                "it to the key you already have. Reading a page you paste the " +
                "address of works on every provider and costs nothing extra."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.size(WarpSpace.medium))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (!supported) Modifier
                else Modifier.clickable { on = !on; WebSearch.set(context, on) }
            )
            .padding(WarpSpace.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Let the model search",
            style = MaterialTheme.typography.bodyLarge,
            color = if (supported) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on && supported,
            enabled = supported,
            onCheckedChange = { on = it; WebSearch.set(context, it) },
        )
    }
}

/**
 * Dark, light, or the phone's choice.
 *
 * Warp followed the system and nothing else, which turned the whole app white
 * one morning because the phone had changed — and nobody had asked for that.
 * Following is a good default and a bad rule: people have an opinion about this
 * one, and it is not always their phone's.
 *
 * Three rows rather than a switch, because "off" is not the opposite of "dark"
 * — the third option is the one that was the only option, and it has to stay
 * reachable.
 */
@Composable
private fun ThemeSection() {
    val context = LocalContext.current
    val appearance = remember { Appearance.get(context) }
    val choice by appearance.theme.collectAsState()

    Text("Theme", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(WarpSpace.tiny))
    Text(
        "Warp follows your phone unless you tell it otherwise.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.size(WarpSpace.medium))

    Appearance.Theme.entries.forEach { option ->
        val picked = option == choice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = WarpSpace.tiny)
                .clip(RoundedCornerShape(WarpRadius.small))
                .background(
                    if (picked) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .clickable { appearance.setTheme(option) }
                .padding(WarpSpace.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (picked) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Chosen",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * The ambient wash, and the switch that turns it off.
 *
 * On by default, unlike naming — and the difference matters. Naming spends the
 * person's money, so it asks first. This spends nothing and is visible the
 * moment the app opens, which makes it a default someone can disagree with by
 * *looking* at it rather than by reading a bill.
 *
 * It is a switch at all because atmosphere is the one thing in this design that
 * could reasonably annoy somebody, and anything that cannot be turned off had
 * better be something nobody wants to turn off.
 */
@Composable
private fun AmbientSection() {
    val context = LocalContext.current
    val appearance = remember { Appearance.get(context) }
    val on by appearance.ambient.collectAsState()

    Text("Atmosphere", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(WarpSpace.tiny))
    Text(
        "A soft light behind the composer that deepens the harder the model is " +
            "asked to think. It costs nothing and sends nothing — it is only how " +
            "the screen is lit.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.size(WarpSpace.medium))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { appearance.setAmbient(!on) }
            .padding(WarpSpace.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Ambient light",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // The whole row is the target, not just the switch. A 32dp control at
        // the far edge of a phone is the hardest thing on the screen to hit.
        Switch(checked = on, onCheckedChange = { appearance.setAmbient(it) })
    }
}

/**
 * Who, if anyone, may name conversations.
 *
 * It lives in Settings and not in the chat's model picker because it is not a
 * per-conversation decision — it is a standing answer to "may Warp spend my
 * credits on this", and a question about money asked once is a setting.
 *
 * **Off is the default**, and the copy says why in the person's terms rather
 * than the app's. Warp is BYOK: the key is theirs and so is the balance, and a
 * default that quietly spends it to make a drawer read better has made a
 * judgement only the account holder is entitled to make.
 */
@Composable
private fun NamingSection(registry: ProviderRegistry) {
    var naming by remember { mutableStateOf(registry.naming) }
    var picking by remember { mutableStateOf(false) }

    fun choose(value: Naming) {
        naming = value
        registry.naming = value
    }

    Text("Naming conversations", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.size(WarpSpace.tiny))
    Text(
        "Every conversation is named from your first message, free and instantly. " +
            "A model can write a better name instead — but that is one paid call " +
            "per conversation, on your key, so it is off until you say otherwise.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.size(WarpSpace.medium))

    NamingOption(
        title = "Off",
        subtitle = "Use your first message. Costs nothing, never fails.",
        selected = naming == Naming.Off,
        onClick = { choose(Naming.Off) },
    )
    Spacer(Modifier.size(WarpSpace.small))
    NamingOption(
        title = "Automatic",
        subtitle = "The cheapest model from whichever AI the conversation used.",
        selected = naming == Naming.Automatic,
        onClick = { choose(Naming.Automatic) },
    )
    Spacer(Modifier.size(WarpSpace.small))
    NamingOption(
        title = (naming as? Naming.Specific)?.modelName ?: "Choose a model",
        subtitle = "Always this one, whatever the conversation is using.",
        selected = naming is Naming.Specific,
        onClick = { picking = true },
    )

    if (picking) {
        // The chat's own picker, reused rather than rebuilt. A second list of
        // models is a second list to keep in step, and the day they disagree is
        // the day somebody cannot find a model they know Warp supports.
        ModelPickerDialog(
            registry = registry,
            current = registry.choice,
            onPick = { picked ->
                choose(
                    Naming.Specific(
                        providerId = picked.providerId,
                        modelId = picked.modelId,
                        modelName = picked.modelName,
                    )
                )
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun NamingOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WarpRadius.small))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(WarpSpace.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
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
