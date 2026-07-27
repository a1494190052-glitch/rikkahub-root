package me.rerere.rikkahub.plugin.scanner

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.plugin.model.PluginManifest
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Plugin scanner: discovers, validates, and imports plugins.
 * Plugins are stored in context.getExternalFilesDir(null)/plugins/
 */
class PluginScanner(
    private val context: Context,
    private val json: Json,
) {
    companion object {
        private const val TAG = "PluginScanner"
        private const val PLUGINS_DIR = "plugins"
        private const val MANIFEST_FILE = "manifest.json"
    }

    /**
     * Get the plugins directory, creating it if necessary.
     */
    fun getPluginsDir(): File {
        val dir = File(context.getExternalFilesDir(null), PLUGINS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Scan all plugin directories and parse their manifests.
     * Returns a list of (directory, manifest) pairs for valid plugins.
     */
    fun scanPlugins(): List<Pair<File, PluginManifest>> {
        val pluginsDir = getPluginsDir()
        val results = mutableListOf<Pair<File, PluginManifest>>()

        pluginsDir.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            try {
                val manifestFile = File(pluginDir, MANIFEST_FILE)
                if (!manifestFile.exists()) {
                    Log.w(TAG, "No manifest.json in ${pluginDir.name}")
                    return@forEach
                }

                val manifestText = manifestFile.readText()
                val manifest = json.decodeFromString<PluginManifest>(manifestText)

                // Validate manifest
                val validationError = validateManifest(manifest, pluginDir)
                if (validationError != null) {
                    Log.w(TAG, "Invalid plugin ${pluginDir.name}: $validationError")
                    return@forEach
                }

                results.add(pluginDir to manifest)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning plugin dir: ${pluginDir.name}", e)
            }
        }

        Log.i(TAG, "Scanned ${results.size} valid plugins")
        return results
    }

    /**
     * Preview a plugin from a zip file without installing it.
     */
    fun previewPlugin(zipFile: File): PluginManifest? {
        return try {
            ZipFile(zipFile).use { zip ->
                // Find manifest.json in the zip (may be at root or in a subdirectory)
                val manifestEntry = zip.entries().asSequence().find { entry ->
                    !entry.isDirectory && (
                        entry.name == MANIFEST_FILE ||
                        entry.name.endsWith("/$MANIFEST_FILE") &&
                        entry.name.count { it == '/' } <= 1
                    )
                } ?: return null

                val manifestText = zip.getInputStream(manifestEntry).bufferedReader().readText()
                json.decodeFromString<PluginManifest>(manifestText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error previewing plugin zip", e)
            null
        }
    }

    /**
     * Import a plugin from a zip file.
     * Extracts to plugins directory, validates, and returns the manifest.
     */
    fun importPlugin(zipFile: File): Result<PluginManifest> {
        return try {
            // First preview to get the manifest and validate
            val manifest = previewPlugin(zipFile)
                ?: return Result.failure(IllegalArgumentException("No valid manifest.json found in zip"))

            // Validate ID
            if (manifest.id.isBlank()) {
                return Result.failure(IllegalArgumentException("Plugin ID cannot be blank"))
            }
            if (!manifest.id.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                return Result.failure(IllegalArgumentException("Plugin ID must be alphanumeric with dashes/underscores"))
            }

            val targetDir = File(getPluginsDir(), manifest.id)

            // Extract zip
            extractZip(zipFile, targetDir)

            // Verify entry file exists after extraction
            val entryFile = File(targetDir, manifest.entry)
            if (!entryFile.exists()) {
                targetDir.deleteRecursively()
                return Result.failure(IllegalArgumentException("Entry file not found: ${manifest.entry}"))
            }

            // Compute and store SHA-256 checksum of the entry file
            val checksum = computeSha256(entryFile)
            File(targetDir, ".checksum").writeText(checksum)

            Log.i(TAG, "Imported plugin: ${manifest.id} v${manifest.version}")
            Result.success(manifest)
        } catch (e: Exception) {
            Log.e(TAG, "Error importing plugin", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a plugin by ID.
     */
    fun deletePlugin(pluginId: String): Boolean {
        val pluginDir = File(getPluginsDir(), pluginId)
        return if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        } else {
            false
        }
    }

    /**
     * Verify plugin integrity using stored SHA-256 checksum.
     */
    fun verifyIntegrity(pluginDir: File): Boolean {
        val checksumFile = File(pluginDir, ".checksum")
        if (!checksumFile.exists()) return true // No checksum stored, skip verification

        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.exists()) return false

        val manifest = try {
            json.decodeFromString<PluginManifest>(manifestFile.readText())
        } catch (e: Exception) {
            return false
        }

        val entryFile = File(pluginDir, manifest.entry)
        if (!entryFile.exists()) return false

        val storedChecksum = checksumFile.readText().trim()
        val currentChecksum = computeSha256(entryFile)
        return storedChecksum == currentChecksum
    }

    /**
     * Validate a manifest and its plugin directory.
     * Returns null if valid, or an error message string.
     */
    private fun validateManifest(manifest: PluginManifest, pluginDir: File): String? {
        if (manifest.id.isBlank()) return "Plugin ID is blank"
        if (manifest.name.isBlank()) return "Plugin name is blank"
        if (!manifest.id.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return "Plugin ID contains invalid characters"
        }

        // Check entry file exists
        val entryFile = File(pluginDir, manifest.entry)
        if (!entryFile.exists()) return "Entry file not found: ${manifest.entry}"

        // Validate tool definitions
        for (tool in manifest.tools) {
            if (tool.name.isBlank()) return "Tool name cannot be blank"
            if (!tool.name.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                return "Tool name contains invalid characters: ${tool.name}"
            }
        }

        // Check for duplicate tool names
        val toolNames = manifest.tools.map { it.name }
        if (toolNames.size != toolNames.toSet().size) {
            return "Duplicate tool names found"
        }

        return null
    }

    /**
     * Extract a zip file to a target directory.
     * Handles the case where the zip has a single root directory.
     */
    private fun extractZip(zipFile: File, targetDir: File) {
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().toList()

            // Detect if there's a single root directory
            val rootPrefix = detectRootPrefix(entries.map { it.name })

            for (entry in entries) {
                val relativePath = if (rootPrefix != null && entry.name.startsWith(rootPrefix)) {
                    entry.name.removePrefix(rootPrefix)
                } else {
                    entry.name
                }

                if (relativePath.isBlank() || relativePath == "/") continue

                // Security: prevent path traversal
                val normalizedPath = relativePath.replace("\\", "/")
                if (normalizedPath.contains("..")) {
                    Log.w(TAG, "Skipping suspicious path: $normalizedPath")
                    continue
                }

                val outFile = File(targetDir, normalizedPath)

                // Ensure the output file is within target directory
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    Log.w(TAG, "Path traversal detected: $normalizedPath")
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    /**
     * Detect if all entries share a common root directory prefix.
     */
    private fun detectRootPrefix(names: List<String>): String? {
        val nonEmpty = names.filter { it.isNotBlank() && it != "/" }
        if (nonEmpty.isEmpty()) return null

        val firstSlash = nonEmpty.first().indexOf('/')
        if (firstSlash <= 0) return null

        val prefix = nonEmpty.first().substring(0, firstSlash + 1)
        return if (nonEmpty.all { it.startsWith(prefix) }) prefix else null
    }

    /**
     * Compute SHA-256 checksum of a file.
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
