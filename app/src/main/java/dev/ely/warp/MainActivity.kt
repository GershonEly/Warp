package dev.ely.warp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ely.warp.diag.DeviceProbe
import dev.ely.warp.ui.BuildScreen
import dev.ely.warp.ui.theme.WarpMono
import dev.ely.warp.ui.theme.WarpSuccess
import dev.ely.warp.ui.theme.WarpTheme
import dev.ely.warp.ui.theme.WarpWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WarpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WarpApp()
                }
            }
        }
    }
}

/** Two tabs for now: the device checks, and the build screen. */
@Composable
private fun WarpApp() {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold { inner ->
        Column(modifier = Modifier.padding(inner)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Device") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Build") },
                )
            }

            when (tab) {
                0 -> PreflightScreen()
                else -> BuildScreen()
            }
        }
    }
}

@Composable
private fun PreflightScreen() {
    val context = LocalContext.current
    var checks by remember { mutableStateOf<List<DeviceProbe.Check>?>(null) }

    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { DeviceProbe.runAll(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "⚡ Warp",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Step 0 · pre-flight checks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(24.dp))

        when (val result = checks) {
            null -> Text(
                text = "Running checks…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                result.forEach { check ->
                    CheckRow(check)
                    Spacer(Modifier.size(12.dp))
                }

                Spacer(Modifier.size(12.dp))
                Verdict(result)
            }
        }
    }
}

@Composable
private fun CheckRow(check: DeviceProbe.Check) {
    val tint = when (check.status) {
        DeviceProbe.Status.PASS -> WarpSuccess
        DeviceProbe.Status.WARN -> WarpWarning
        DeviceProbe.Status.FAIL -> MaterialTheme.colorScheme.error
        DeviceProbe.Status.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glyph = when (check.status) {
        DeviceProbe.Status.PASS -> "✓"
        DeviceProbe.Status.WARN -> "!"
        DeviceProbe.Status.FAIL -> "✕"
        DeviceProbe.Status.INFO -> "·"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .size(20.dp)
                    .background(tint, CircleShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = glyph,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = check.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.size(8.dp))
        Text(
            text = check.value,
            style = WarpMono,
            color = tint,
            fontWeight = FontWeight.Medium,
        )

        check.detail?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Verdict(checks: List<DeviceProbe.Check>) {
    val execCheck = checks.firstOrNull { it.label == "Execute from app storage" }
    val canExec = execCheck?.status == DeviceProbe.Status.PASS
    val anyFail = checks.any { it.status == DeviceProbe.Status.FAIL }

    val (headline, body, tint) = when {
        canExec && !anyFail -> Triple(
            "Ready to build",
            "This device can run Warp's compiler toolchain. " +
                "The core assumption behind the whole project is confirmed.",
            WarpSuccess,
        )
        canExec -> Triple(
            "Mostly ready",
            "Execution works, but something else needs attention above.",
            WarpWarning,
        )
        else -> Triple(
            "Blocked",
            "This device refuses to execute binaries from app storage. " +
                "Warp needs the jniLibs fallback described in the plan.",
            MaterialTheme.colorScheme.error,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
