package dev.ely.warp.build

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import com.android.apksig.ApkSigner as ApksigSigner

/**
 * Signs the APKs Warp builds.
 *
 * Android refuses to install an unsigned APK, so this is the last required
 * stage of a build.
 *
 * Unlike the compiler stages this runs **inside Warp**, on ART: `apksig` is a
 * plain Java library bundled into the app, so no separate process is needed.
 * Signing is cheap — it is hashing, not compiling.
 *
 * The debug key is generated on the device on first use, with the bundled
 * JDK's `keytool`. It never leaves the phone and is not in the repo.
 */
class ApkSigner(
    private val toolchain: Toolchain,
    private val keystoreFile: File,
) {

    sealed interface Outcome {
        data class Success(val signedApk: File, val durationMs: Long) : Outcome
        data class Failure(val message: String, val detail: String = "") : Outcome
    }

    /**
     * Sign [input], writing to [output].
     *
     * @param minSdk must match what the APK was built for; apksig uses it to
     *   decide which signature schemes to apply.
     */
    suspend fun sign(input: File, output: File, minSdk: Int): Outcome =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()

            if (!input.isFile) {
                return@withContext Outcome.Failure("APK to sign was not found: ${input.name}")
            }

            when (val keys = loadOrCreateDebugKey()) {
                is KeyResult.Failure -> Outcome.Failure(keys.message, keys.detail)
                is KeyResult.Success -> runCatching {
                    val config = ApksigSigner.SignerConfig.Builder(
                        "WarpDebug", keys.privateKey, listOf(keys.certificate),
                    ).build()

                    if (output.exists()) output.delete()

                    ApksigSigner.Builder(listOf(config))
                        .setInputApk(input)
                        .setOutputApk(output)
                        .setMinSdkVersion(minSdk)
                        // v1 keeps older devices happy; v2 is what modern
                        // Android verifies. Both is the safe combination.
                        .setV1SigningEnabled(true)
                        .setV2SigningEnabled(true)
                        .build()
                        .sign()

                    val elapsed = System.currentTimeMillis() - started
                    Log.i(TAG, "signed ${output.name} (${output.length() / 1024} KB) in ${elapsed}ms")
                    Outcome.Success(output, elapsed)
                }.getOrElse {
                    Log.e(TAG, "signing failed", it)
                    Outcome.Failure(
                        "Could not sign the APK.",
                        "${it.javaClass.simpleName}: ${it.message}",
                    )
                }
            }
        }

    // ── debug key ────────────────────────────────────────────────────────

    private sealed interface KeyResult {
        data class Success(val privateKey: PrivateKey, val certificate: X509Certificate) : KeyResult
        data class Failure(val message: String, val detail: String = "") : KeyResult
    }

    private suspend fun loadOrCreateDebugKey(): KeyResult {
        if (!keystoreFile.isFile) {
            generateDebugKeystore()?.let { return it }
        }
        return runCatching {
            val store = KeyStore.getInstance("PKCS12")
            keystoreFile.inputStream().use { store.load(it, STORE_PASSWORD) }
            val key = store.getKey(KEY_ALIAS, STORE_PASSWORD) as? PrivateKey
                ?: return KeyResult.Failure("The debug keystore has no usable private key.")
            val cert = store.getCertificate(KEY_ALIAS) as? X509Certificate
                ?: return KeyResult.Failure("The debug keystore has no certificate.")
            KeyResult.Success(key, cert)
        }.getOrElse {
            KeyResult.Failure(
                "Could not read the debug keystore.",
                "${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    /**
     * Create the debug keystore with the bundled JDK's `keytool`.
     *
     * Android's own crypto APIs cannot mint a self-signed certificate without
     * pulling in something like BouncyCastle, and we already ship a JDK, so
     * using `keytool` avoids an extra dependency.
     *
     * @return null when the keystore was created, or the failure to report.
     */
    private suspend fun generateDebugKeystore(): KeyResult.Failure? {
        val keytool = File(toolchain.javaHome, "bin/keytool")
        if (!keytool.isFile) {
            return KeyResult.Failure(
                "keytool is missing from the bundled JDK, so no debug key can be created."
            )
        }
        Log.i(TAG, "no debug keystore yet — creating one")
        keystoreFile.parentFile?.mkdirs()

        val tmp = File(keystoreFile.parentFile, "keytool-tmp").apply { mkdirs() }
        val result = ProcessRunner.run(
            command = listOf(
                keytool.absolutePath,
                "-genkeypair",
                "-keystore", keystoreFile.absolutePath,
                "-storetype", "PKCS12",
                "-storepass", String(STORE_PASSWORD),
                "-keypass", String(STORE_PASSWORD),
                "-alias", KEY_ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                // ~27 years. A debug key that expires mid-project is a
                // genuinely confusing failure, so give it plenty of room.
                "-validity", "10000",
                "-dname", "CN=Warp Debug, OU=Warp, O=Warp, C=US",
            ),
            workingDir = keystoreFile.parentFile ?: tmp,
            env = toolchain.env(tmpDir = tmp, home = keystoreFile.parentFile ?: tmp),
            timeout = 2,
            timeoutUnit = TimeUnit.MINUTES,
        )

        // On success we return null and let the caller load the keystore
        // through the normal path, rather than duplicating that logic here.
        return if (result.ok && keystoreFile.isFile) {
            Log.i(TAG, "created debug keystore at $keystoreFile")
            null
        } else {
            KeyResult.Failure(
                "keytool could not create the debug keystore.",
                result.output.ifBlank { "exit code ${result.exitCode}" },
            )
        }
    }

    companion object {
        private const val TAG = "WarpSigner"
        private const val KEY_ALIAS = "warpdebug"
        private val STORE_PASSWORD = "android".toCharArray()
    }
}
