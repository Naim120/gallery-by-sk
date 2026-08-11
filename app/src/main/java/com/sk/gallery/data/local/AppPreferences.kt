package com.sk.gallery.data.local

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gallery_by_sk_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PRIVATE = "private_items"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_ALBUM_GRID_COLUMNS = "album_grid_columns"
        private const val KEY_SORT_BY = "sort_by"
        private const val KEY_CUSTOM_ALBUMS = "custom_albums"
        private const val KEY_ALBUM_ALIASES = "album_aliases"
        private const val KEY_VAULT_PIN = "vault_pin"
        private const val KEY_VAULT_BIOMETRICS = "vault_biometrics"
    }

    fun getFavorites(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun toggleFavorite(hashId: String): Boolean {
        val current = getFavorites().toMutableSet()
        val isFav = if (current.contains(hashId)) {
            current.remove(hashId)
            false
        } else {
            current.add(hashId)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return isFav
    }

    fun isFavorite(hashId: String): Boolean {
        return getFavorites().contains(hashId)
    }

    fun getPrivateItems(): Set<String> {
        return prefs.getStringSet(KEY_PRIVATE, emptySet()) ?: emptySet()
    }

    fun togglePrivate(hashId: String): Boolean {
        val current = getPrivateItems().toMutableSet()
        val isPriv = if (current.contains(hashId)) {
            current.remove(hashId)
            false
        } else {
            current.add(hashId)
            true
        }
        prefs.edit().putStringSet(KEY_PRIVATE, current).apply()
        return isPriv
    }

    fun isPrivate(hashId: String): Boolean {
        return getPrivateItems().contains(hashId)
    }

    fun getGridColumns(): Int {
        return prefs.getInt(KEY_GRID_COLUMNS, 3)
    }

    fun setGridColumns(columns: Int) {
        prefs.edit().putInt(KEY_GRID_COLUMNS, columns).apply()
    }

    fun getAlbumGridColumns(): Int {
        return prefs.getInt(KEY_ALBUM_GRID_COLUMNS, 3)
    }

    fun setAlbumGridColumns(columns: Int) {
        prefs.edit().putInt(KEY_ALBUM_GRID_COLUMNS, columns).apply()
    }

    fun getSortBy(): String {
        return prefs.getString(KEY_SORT_BY, "date_desc") ?: "date_desc"
    }

    fun setSortBy(sortBy: String) {
        prefs.edit().putString(KEY_SORT_BY, sortBy).apply()
    }

    fun getCustomAlbums(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_ALBUMS, emptySet()) ?: emptySet()
    }

    fun addCustomAlbum(relativePath: String) {
        val current = getCustomAlbums().toMutableSet()
        current.add(relativePath)
        prefs.edit().putStringSet(KEY_CUSTOM_ALBUMS, current).apply()
    }

    fun removeCustomAlbum(relativePath: String) {
        val current = getCustomAlbums().toMutableSet()
        current.remove(relativePath)
        prefs.edit().putStringSet(KEY_CUSTOM_ALBUMS, current).apply()
        
        val aliases = getAlbumAliases().toMutableMap()
        if (aliases.containsKey(relativePath)) {
            aliases.remove(relativePath)
            saveAlbumAliases(aliases)
        }
    }

    fun getAlbumAliases(): Map<String, String> {
        val jsonString = prefs.getString(KEY_ALBUM_ALIASES, "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val jsonObject = org.json.JSONObject(jsonString)
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonObject.getString(key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun setAlbumAlias(relativePath: String, alias: String) {
        val current = getAlbumAliases().toMutableMap()
        current[relativePath] = alias
        saveAlbumAliases(current)
    }

    private fun saveAlbumAliases(map: Map<String, String>) {
        try {
            val jsonObject = org.json.JSONObject()
            for ((key, value) in map) {
                jsonObject.put(key, value)
            }
            prefs.edit().putString(KEY_ALBUM_ALIASES, jsonObject.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getVaultPin(): String? {
        return prefs.getString(KEY_VAULT_PIN, null)
    }

    fun setVaultPin(pin: String) {
        prefs.edit().putString(KEY_VAULT_PIN, pin).apply()
    }

    fun isVaultBiometricsEnabled(): Boolean {
        return prefs.getBoolean(KEY_VAULT_BIOMETRICS, false)
    }

    fun setVaultBiometricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VAULT_BIOMETRICS, enabled).apply()
    }

    fun getCloudPassphrase(): String? {
        return prefs.getString("cloud_passphrase", null)
    }

    fun setCloudPassphrase(phrase: String) {
        prefs.edit().putString("cloud_passphrase", phrase).apply()
    }

    fun isPrivateSafeExportPaused(): Boolean {
        return prefs.getBoolean("private_safe_export_paused", false)
    }

    fun setPrivateSafeExportPaused(paused: Boolean) {
        prefs.edit().putBoolean("private_safe_export_paused", paused).apply()
    }

    fun getPrivateSafeExportProgress(): Int {
        return prefs.getInt("private_safe_export_progress", 0)
    }

    fun setPrivateSafeExportProgress(progress: Int) {
        prefs.edit().putInt("private_safe_export_progress", progress).apply()
    }

    fun getPrivateSafeExportStatus(): String {
        return prefs.getString("private_safe_export_status", "") ?: ""
    }

    fun setPrivateSafeExportStatus(status: String) {
        prefs.edit().putString("private_safe_export_status", status).apply()
    }

    fun isPrivateSafeImportPaused(): Boolean {
        return prefs.getBoolean("private_safe_import_paused", false)
    }

    fun setPrivateSafeImportPaused(paused: Boolean) {
        prefs.edit().putBoolean("private_safe_import_paused", paused).apply()
    }

    fun getPrivateSafeImportProgress(): Int {
        return prefs.getInt("private_safe_import_progress", 0)
    }

    fun setPrivateSafeImportProgress(progress: Int) {
        prefs.edit().putInt("private_safe_import_progress", progress).apply()
    }

    fun getPrivateSafeImportStatus(): String {
        return prefs.getString("private_safe_import_status", "") ?: ""
    }

    fun setPrivateSafeImportStatus(status: String) {
        prefs.edit().putString("private_safe_import_status", status).apply()
    }

    fun getPrivateSafeImportFolder(): String {
        return prefs.getString("private_safe_import_folder", "") ?: ""
    }

    fun setPrivateSafeImportFolder(folderId: String) {
        prefs.edit().putString("private_safe_import_folder", folderId).apply()
    }

    fun getImportedVaults(): List<ImportedVault> {
        val json = prefs.getString("private_safe_imported_vaults_list", null) ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<ImportedVault>>() {}.type
        return com.google.gson.Gson().fromJson(json, type) ?: emptyList()
    }

    fun addImportedVault(deviceId: String): String {
        val list = getImportedVaults().toMutableList()
        val existing = list.find { it.deviceId == deviceId }
        if (existing != null) {
            return existing.displayName
        }
        val nextIndex = list.size + 1
        val displayName = "Vault $nextIndex"
        list.add(ImportedVault(deviceId, displayName))
        val json = com.google.gson.Gson().toJson(list)
        prefs.edit().putString("private_safe_imported_vaults_list", json).apply()
        return displayName
    }

    fun isVaultImported(deviceId: String): Boolean {
        return getImportedVaults().any { it.deviceId == deviceId }
    }

    fun getPrivateSafeLastExport(): String? {
        return prefs.getString("private_safe_last_export", null)
    }

    fun setPrivateSafeLastExport(info: String) {
        prefs.edit().putString("private_safe_last_export", info).apply()
    }

    fun getPrivateSafeLastImport(): String? {
        return prefs.getString("private_safe_last_import", null)
    }

    fun setPrivateSafeLastImport(info: String) {
        prefs.edit().putString("private_safe_last_import", info).apply()
    }
}

data class ImportedVault(val deviceId: String, val displayName: String)
