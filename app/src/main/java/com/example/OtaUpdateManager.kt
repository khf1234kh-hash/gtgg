package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object OtaUpdateManager {

  private const val TAG = "OtaUpdateManager"
  private const val PREFS_NAME = "ota_update_preferences"
  private const val KEY_CURRENT_VERSION = "ota_current_version"
  const val DEFAULT_VERSION = "1.0.0"

  // Directory names inside internal storage (filesDir)
  private const val DIR_WWW = "www"
  private const val DIR_STAGING = "www_staging"
  private const val DIR_BACKUP = "www_backup"

  private fun getPrefs(context: Context): SharedPreferences {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  fun getCurrentVersion(context: Context): String {
    return getPrefs(context).getString(KEY_CURRENT_VERSION, DEFAULT_VERSION) ?: DEFAULT_VERSION
  }

  fun setCurrentVersion(context: Context, version: String) {
    getPrefs(context).edit().putString(KEY_CURRENT_VERSION, version).apply()
  }

  /**
   * Returns the entry point file URL for the WebView:
   * If an OTA update has been downloaded to internal storage (`files/www/index.html`),
   * it returns `file:///data/user/0/.../files/www/index.html`.
   * Otherwise, it defaults to the built-in APK asset `file:///android_asset/index.html`.
   */
  fun getAppEntryUrl(context: Context): String {
    val otaIndexFile = File(context.filesDir, "$DIR_WWW/index.html")
    return if (otaIndexFile.exists() && otaIndexFile.length() > 0) {
      "file://${otaIndexFile.absolutePath}"
    } else {
      "file:///android_asset/index.html"
    }
  }

  /**
   * Checks if remote version is strictly newer than current version.
   * Handles semantic versions like "1.0.1" vs "1.0.0" as well as numeric strings.
   */
  fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
    return try {
      val remoteParts = remoteVersion.trim().split(".").map { it.toIntOrNull() ?: 0 }
      val currentParts = currentVersion.trim().split(".").map { it.toIntOrNull() ?: 0 }

      val maxLen = maxOf(remoteParts.size, currentParts.size)
      for (i in 0 until maxLen) {
        val r = remoteParts.getOrElse(i) { 0 }
        val c = currentParts.getOrElse(i) { 0 }
        if (r > c) return true
        if (r < c) return false
      }
      false
    } catch (e: Exception) {
      Log.w(TAG, "Failed to compare versions '$remoteVersion' and '$currentVersion': ${e.message}")
      false
    }
  }

  /**
   * Asynchronously checks the server for updates.
   * If a newer version is found, downloads and extracts the zip,
   * updates the local version, and executes [onResult] on the main thread.
   */
  fun checkForUpdates(
    context: Context,
    serverUrl: String,
    onResult: (updated: Boolean, newVersion: String, message: String) -> Unit
  ) {
    if (serverUrl.isBlank() || !serverUrl.startsWith("http")) {
      Log.d(TAG, "Invalid or placeholder serverUrl: $serverUrl")
      onResult(false, getCurrentVersion(context), "Invalid server URL")
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      try {
        Log.d(TAG, "Checking for OTA updates from: $serverUrl")
        val currentVersion = getCurrentVersion(context)

        // 1. Fetch version.json from the server
        val url = URL(serverUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
          connectTimeout = 6000
          readTimeout = 6000
          requestMethod = "GET"
          setRequestProperty("Accept", "application/json")
          setRequestProperty("User-Agent", "IslamicApp-OTA/${currentVersion}")
          instanceFollowRedirects = true
        }

        if (conn.responseCode !in 200..299) {
          Log.d(TAG, "Server returned HTTP ${conn.responseCode}")
          conn.disconnect()
          withContext(Dispatchers.Main) {
            onResult(false, currentVersion, "HTTP status ${conn.responseCode}")
          }
          return@launch
        }

        val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(jsonString)
        val remoteVersion = json.optString("version", "").trim()
        val downloadUrl = json.optString("downloadUrl", "").trim()

        if (remoteVersion.isEmpty() || downloadUrl.isEmpty()) {
          Log.d(TAG, "version.json missing 'version' or 'downloadUrl'")
          withContext(Dispatchers.Main) {
            onResult(false, currentVersion, "Invalid version.json structure")
          }
          return@launch
        }

        // 2. Compare versions
        if (!isNewerVersion(remoteVersion, currentVersion)) {
          Log.d(TAG, "App is already up to date (current: $currentVersion, remote: $remoteVersion)")
          withContext(Dispatchers.Main) {
            onResult(false, currentVersion, "App is up to date")
          }
          return@launch
        }

        Log.i(TAG, "New OTA version available: $remoteVersion (current: $currentVersion). Downloading...")

        // 3. Download the update ZIP
        val tempZipFile = File(context.cacheDir, "ota_update_${System.currentTimeMillis()}.zip")
        val downloadConn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
          connectTimeout = 10000
          readTimeout = 20000
          requestMethod = "GET"
          instanceFollowRedirects = true
        }

        if (downloadConn.responseCode !in 200..299) {
          Log.w(TAG, "Failed to download update zip: HTTP ${downloadConn.responseCode}")
          downloadConn.disconnect()
          withContext(Dispatchers.Main) {
            onResult(false, currentVersion, "Failed to download zip")
          }
          return@launch
        }

        downloadConn.inputStream.use { input ->
          FileOutputStream(tempZipFile).use { output ->
            input.copyTo(output)
          }
        }
        downloadConn.disconnect()

        if (tempZipFile.length() < 100) {
          Log.w(TAG, "Downloaded zip file is empty or corrupted")
          tempZipFile.delete()
          withContext(Dispatchers.Main) {
            onResult(false, currentVersion, "Empty zip file")
          }
          return@launch
        }

        // 4. Extract into staging directory
        val stagingDir = File(context.filesDir, DIR_STAGING)
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val extractedSuccessfully = unzipSafe(tempZipFile, stagingDir)
        tempZipFile.delete()

        if (!extractedSuccessfully) {
          Log.w(TAG, "Unzip failed or contained unsafe paths")
          stagingDir.deleteRecursively()
          withContext(Dispatchers.Main) {
            onResult(false, currentVersion, "Failed to extract update zip")
          }
          return@launch
        }

        // Verify that index.html exists in staging (either directly or inside a single root folder)
        var targetWwwDir = stagingDir
        val directIndex = File(stagingDir, "index.html")
        if (!directIndex.exists()) {
          // Check if the zip was packaged with a root folder e.g. "www/" or "dist/"
          val subFolders = stagingDir.listFiles { f -> f.isDirectory } ?: emptyArray()
          val foundDir = subFolders.firstOrNull { File(it, "index.html").exists() }
          if (foundDir != null) {
            targetWwwDir = foundDir
          } else {
            Log.w(TAG, "index.html not found in update package!")
            stagingDir.deleteRecursively()
            withContext(Dispatchers.Main) {
              onResult(false, currentVersion, "index.html not found in update package")
            }
            return@launch
          }
        }

        // 5. Atomically replace www with staging
        val wwwDir = File(context.filesDir, DIR_WWW)
        val backupDir = File(context.filesDir, DIR_BACKUP)
        backupDir.deleteRecursively()

        if (wwwDir.exists()) {
          wwwDir.renameTo(backupDir)
        }

        val moveSuccess = targetWwwDir.renameTo(wwwDir)
        if (!moveSuccess) {
          // If renaming failed, fallback to copy
          targetWwwDir.copyRecursively(wwwDir, overwrite = true)
        }

        stagingDir.deleteRecursively()
        backupDir.deleteRecursively()

        // 6. Update local version stored in SharedPreferences
        setCurrentVersion(context, remoteVersion)
        Log.i(TAG, "Successfully updated to OTA version $remoteVersion!")

        withContext(Dispatchers.Main) {
          onResult(true, remoteVersion, "Update installed successfully")
        }

      } catch (e: Exception) {
        // Safe fail: never crash or bother the user
        Log.d(TAG, "OTA update check error (safe fallback): ${e.message}")
        withContext(Dispatchers.Main) {
          onResult(false, getCurrentVersion(context), "Error: ${e.message}")
        }
      }
    }
  }

  /**
   * Safely unpacks a zip archive while preventing Zip-Slip directory traversal vulnerabilities.
   */
  private fun unzipSafe(zipFile: File, destinationDir: File): Boolean {
    return try {
      val canonicalDestPath = destinationDir.canonicalPath
      ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
          val destFile = File(destinationDir, entry.name)
          val canonicalEntryPath = destFile.canonicalPath

          // Security check against Zip-Slip traversal
          if (!canonicalEntryPath.startsWith(canonicalDestPath + File.separator) &&
              canonicalEntryPath != canonicalDestPath) {
            Log.e(TAG, "Security alert: Zip entry '${entry.name}' attempts to break out of destination directory!")
            return false
          }

          if (entry.isDirectory) {
            destFile.mkdirs()
          } else {
            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { fos ->
              zis.copyTo(fos)
            }
          }
          zis.closeEntry()
          entry = zis.nextEntry
        }
      }
      true
    } catch (e: Exception) {
      Log.e(TAG, "Error unzipping update: ${e.message}", e)
      false
    }
  }
}
