package com.akh.lexdesk.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API key, model and standing instructions, encrypted with the Android Keystore. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = openPrefs(context)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: MODELS.first().first
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var standingInstructions: String
        get() = prefs.getString(KEY_INSTRUCTIONS, null) ?: DEFAULT_INSTRUCTIONS
        set(value) = prefs.edit().putString(KEY_INSTRUCTIONS, value).apply()

    /** One of [MODE_AUTO], [MODE_GEMINI], [MODE_ON_DEVICE], [MODE_CLAUDE]. */
    var engineMode: String
        get() = prefs.getString(KEY_ENGINE_MODE, null) ?: MODE_AUTO
        set(value) = prefs.edit().putString(KEY_ENGINE_MODE, value).apply()

    var geminiKey: String
        get() = prefs.getString(KEY_GEMINI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, null)?.takeIf { it.isNotBlank() } ?: GEMINI_MODELS.first().first
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value.trim()).apply()

    var onDeviceModel: String
        get() = prefs.getString(KEY_ONDEVICE_MODEL, null) ?: "e2b"
        set(value) = prefs.edit().putString(KEY_ONDEVICE_MODEL, value).apply()

    var onDeviceGpu: Boolean
        // CPU by default: the GPU path is unstable for Gemma 4 on current LiteRT-LM releases.
        get() = prefs.getBoolean(KEY_ONDEVICE_GPU_V2, false)
        set(value) = prefs.edit().putBoolean(KEY_ONDEVICE_GPU_V2, value).apply()

    var downloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_DOWNLOAD_ID, value).apply()

    var downloadModel: String
        get() = prefs.getString(KEY_DOWNLOAD_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_MODEL, value).apply()

    private fun openPrefs(context: Context): SharedPreferences {
        fun create(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // Keystore mismatch (for example after reinstalling): reset and start clean.
            context.deleteSharedPreferences(PREFS_NAME)
            create()
        }
    }

    companion object {
        private const val PREFS_NAME = "lexdesk_secure"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_INSTRUCTIONS = "standing_instructions"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_GEMINI = "gemini_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_ONDEVICE_MODEL = "ondevice_model"
        private const val KEY_ONDEVICE_GPU_V2 = "ondevice_gpu_v2"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOAD_MODEL = "download_model"

        const val MODE_AUTO = "auto"
        const val MODE_GEMINI = "gemini"
        const val MODE_ON_DEVICE = "ondevice"
        const val MODE_CLAUDE = "claude"

        val ENGINE_MODES = listOf(
            MODE_AUTO to "Auto: Gemini when online, on-device when offline (recommended)",
            MODE_GEMINI to "Gemini only (free, cloud)",
            MODE_ON_DEVICE to "On-device only (offline, fully private)",
            MODE_CLAUDE to "Claude (paid, optional)",
        )

        /** Gemini model ID to label. The first entry is the default. */
        val GEMINI_MODELS = listOf(
            "gemini-3.8-flash" to "Gemini 3.8 Flash (best free option)",
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite (faster, lighter)",
        )

        /** Model ID to label. The first entry is the default. */
        val MODELS = listOf(
            "claude-sonnet-5-5" to "Sonnet 5.5 (balanced, recommended)",
            "claude-opus-5-5" to "Opus 5.5 (most capable, higher cost)",
            "claude-haiku-5-5" to "Haiku 5.5 (fastest, lowest cost)",
        )

        val DEFAULT_INSTRUCTIONS = """
            - Communicate in precise legal drafting register.
            - Preserve all proper names, dates, document numbers, survey numbers and reference numbers verbatim.
            - When I supply exact wording for an insertion, reproduce it verbatim.
            - Grammar and sentence formation may be corrected, but do not paraphrase substance.
            - Proactively flag inconsistencies, chronological anomalies, EC gaps, GPA chain risks and jurisdictional issues.
            - Treat Karnataka revenue and registration terminology, CPC, NI Act, RERA, KMC Act, BBMP, BDA, BMRDA, IBC, NCLT and PTCL Act vocabulary as standard; do not explain it.
            - Never use em dashes or en dashes in drafted documents; use commas or restructure the sentence.
            - Reference number formats: AKH/LSR/PS/[serial]/[year] for scrutiny reports, AKH/LN/__/[year] for legal notices, AKH/PN/[serial]/[year] for public notices.
        """.trimIndent()
    }
}
