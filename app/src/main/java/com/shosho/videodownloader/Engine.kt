package com.shosho.videodownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream

/**
 * Bundled yt-dlp + Python 3.14 + QuickJS + ffmpeg, extracted from ytdlnis's
 * open-source release builds (github.com/deniscerri/ytdlnis, GPL-3.0). Same
 * engine and the same architecture proven on Shane's phone via adb before
 * this was wired into the app: real Python running `python -m yt_dlp`, with
 * QuickJS as the --js-runtimes solver for YouTube's challenge when needed.
 */
object Engine {
    private const val TAG = "Engine"
    private const val FILE_MARKER = "SHOSHO_FILE:"
    private const val PROGRESS_MARKER = "SHOSHO_PROGRESS:"

    data class Progress(val percent: Int?, val statusLine: String)

    class DownloadResult(
        val savedFileName: String?,
        val savedUri: Uri?,
        val log: String,
        val success: Boolean,
    )

    private fun nativeLibDir(context: Context) = context.applicationInfo.nativeLibraryDir
    private fun pythonHome(context: Context) = File(context.filesDir, "python/usr")
    private fun ffmpegRoot(context: Context) = File(context.filesDir, "ffmpeg/usr")
    private fun siteDir(context: Context) = File(context.filesDir, "site")
    private fun pythonExe(context: Context) = File(nativeLibDir(context), "libpython.so")
    private fun qjsExe(context: Context) = File(nativeLibDir(context), "libqjs.so")
    private fun ffmpegExe(context: Context) = File(nativeLibDir(context), "libffmpeg.so")

    /** One-time extraction of the zipped Python/ffmpeg runtimes and the yt_dlp site packages. */
    @Synchronized
    fun ensureReady(context: Context) {
        val markerFile = File(context.filesDir, "engine_ready_v2")
        if (markerFile.exists()) return

        unzip(File(nativeLibDir(context), "libpython.zip.so"), File(context.filesDir, "python"))
        unzip(File(nativeLibDir(context), "libffmpeg.zip.so"), File(context.filesDir, "ffmpeg"))

        val siteZip = File(context.cacheDir, "site.zip")
        context.assets.open("site.zip").use { input ->
            FileOutputStream(siteZip).use { out -> input.copyTo(out) }
        }
        unzip(siteZip, siteDir(context))
        siteZip.delete()

        markerFile.writeText("ok")
    }

    /**
     * Extracts a zip using commons-compress, not java.util.zip: these bundled
     * runtimes contain real Unix symlinks (e.g. libz.so.1 -> libz.so.1.3.1),
     * which java.util.zip silently turns into tiny text files containing the
     * link target instead of following them - the extracted .so then fails to
     * dlopen with "too small to be an ELF executable".
     */
    private fun unzip(zipFile: File, destDir: File) {
        if (!zipFile.exists()) throw IllegalStateException("Missing bundled runtime: ${zipFile.name}")
        destDir.mkdirs()
        ZipFile.builder().setFile(zipFile).get().use { zf ->
            val entries = zf.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(destDir, entry.name)
                when {
                    entry.isDirectory -> outFile.mkdirs()
                    entry.isUnixSymlink -> {
                        outFile.parentFile?.mkdirs()
                        val target = zf.getInputStream(entry).bufferedReader().readText()
                        outFile.delete()
                        try {
                            Os.symlink(target, outFile.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "symlink failed for ${entry.name} -> $target: ${e.message}")
                        }
                    }
                    else -> {
                        outFile.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { out -> input.copyTo(out) }
                        }
                        outFile.setExecutable(true, false)
                        outFile.setReadable(true, false)
                    }
                }
            }
        }
    }

    private fun env(context: Context): Map<String, String> {
        val nativeDir = nativeLibDir(context)
        return mapOf(
            "LD_LIBRARY_PATH" to "$nativeDir:${pythonHome(context)}/lib:${ffmpegRoot(context)}/lib",
            "PYTHONHOME" to pythonHome(context).absolutePath,
            "PYTHONPATH" to siteDir(context).absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "HOME" to context.filesDir.absolutePath,
            "SSL_CERT_FILE" to File(siteDir(context), "certifi/cacert.pem").absolutePath,
        )
    }

    private fun buildCommand(
        context: Context,
        url: String,
        heightCap: Int?,
        workDir: File,
        customName: String?,
    ): List<String> {
        val selector = SiteRules.formatSelectorForUrl(url, heightCap)
        // "%(title,description,uploader)s" tries each field in turn and uses
        // the first one that isn't empty/NA - TikTok and Instagram sometimes
        // leave title as just the numeric id when a post has no caption, so
        // falling back to the description or uploader name beats "08745".
        val nameTemplate = if (!customName.isNullOrBlank()) {
            // %% escapes a literal percent sign inside yt-dlp's -o syntax.
            customName.replace("%", "%%")
        } else {
            "%(title,description,uploader).140B"
        }
        return listOf(
            pythonExe(context).absolutePath, "-m", "yt_dlp",
            "--ignore-config", "--no-playlist", "--no-simulate", "--newline",
            "--progress", "--no-colors", "--no-overwrites", "--socket-timeout", "20",
            "--retries", "3", "--windows-filenames",
            "-f", selector,
            "--merge-output-format", "mp4/mkv",
            "-P", workDir.absolutePath,
            "-o", "$nameTemplate [%(id)s].%(ext)s",
            "--print", "after_move:$FILE_MARKER%(filepath)j",
            "--progress-template", "download:$PROGRESS_MARKER%(progress._percent_str)s",
            "--ffmpeg-location", ffmpegExe(context).absolutePath,
            "--js-runtimes", "quickjs:${qjsExe(context).absolutePath}",
            "--", url,
        )
    }

    /** Runs on the calling thread - call from a background thread, not the UI thread. */
    fun download(
        context: Context,
        url: String,
        heightCap: Int?,
        customName: String? = null,
        onProgress: (Progress) -> Unit,
        onLog: (String) -> Unit,
    ): DownloadResult {
        ensureReady(context)
        val workDir = File(context.cacheDir, "dl").apply { mkdirs() }
        workDir.listFiles()?.forEach { it.delete() }

        val command = buildCommand(context, url, heightCap, workDir, customName)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(env(context)) }
            .start()

        val log = StringBuilder()
        var savedPath: String? = null
        process.inputStream.bufferedReader().forEachLine { line ->
            log.appendLine(line)
            Log.d(TAG, line)
            when {
                line.startsWith(FILE_MARKER) -> {
                    // yt-dlp prints this path as a JSON string literal
                    // (--print after_move:...%(filepath)j) so escapes like
                    // emoji surrogate pairs decode correctly - a naive quote
                    // trim leaves "🏀" as literal text instead of
                    // the real character, pointing at a file that doesn't
                    // exist and silently failing the save-to-Downloads step.
                    val raw = line.removePrefix(FILE_MARKER).trim()
                    savedPath = try {
                        org.json.JSONArray("[$raw]").getString(0)
                    } catch (e: org.json.JSONException) {
                        raw.trim('"')
                    }
                }
                line.startsWith(PROGRESS_MARKER) -> {
                    val pct = Regex("""(\d+(?:\.\d+)?)%""").find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    onProgress(Progress(pct?.toInt(), line.removePrefix(PROGRESS_MARKER)))
                }
                line.isNotBlank() -> onLog(line)
            }
        }
        val exitCode = process.waitFor()
        Log.i(TAG, "yt-dlp exit=$exitCode saved=$savedPath")

        if (exitCode != 0 || savedPath == null) {
            return DownloadResult(null, null, log.toString(), success = false)
        }

        val downloadedFile = File(savedPath!!)
        val finalFile = if (downloadedFile.extension.equals("mkv", ignoreCase = true)) {
            onLog("Converting MKV to MP4…")
            convertToMp4(context, downloadedFile) ?: downloadedFile
        } else {
            downloadedFile
        }
        val savedUri = saveToDownloads(context, finalFile)
        return DownloadResult(finalFile.name, savedUri, log.toString(), success = savedUri != null)
    }

    /**
     * Mirrors the Mac app's _conversion_command(): a fast "-c copy" remux
     * first (works when the codecs are already MP4-compatible), falling
     * back to a real re-encode (libx264/aac, which our bundled ffmpeg
     * includes) when the remux fails.
     */
    private fun convertToMp4(context: Context, source: File): File? {
        val target = File(source.parentFile, source.nameWithoutExtension + ".mp4")
        if (runFfmpegConversion(context, source, target, copy = true)) return target
        if (runFfmpegConversion(context, source, target, copy = false)) return target
        target.delete()
        return null
    }

    private fun runFfmpegConversion(context: Context, source: File, target: File, copy: Boolean): Boolean {
        val command = mutableListOf(
            ffmpegExe(context).absolutePath, "-hide_banner", "-loglevel", "error",
            "-nostdin", "-y", "-i", source.absolutePath, "-map", "0:v:0", "-map", "0:a:0?",
        )
        if (copy) {
            command += listOf("-c", "copy")
        } else {
            command += listOf(
                "-c:v", "libx264", "-preset", "fast", "-crf", "18",
                "-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
                "-c:a", "aac", "-b:a", "192k",
            )
        }
        command += listOf("-movflags", "+faststart", target.absolutePath)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(env(context)) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) Log.w(TAG, "ffmpeg conversion (copy=$copy) failed: $output")
        return exitCode == 0 && target.exists() && target.length() > 0
    }

    /** Copies the downloaded file into the public Downloads collection, returning a shareable Uri. */
    private fun saveToDownloads(context: Context, file: File): Uri? {
        if (!file.exists()) return null
        val extension = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/$extension"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val target = File(downloadsDir, file.name)
            file.copyTo(target, overwrite = true)
            file.delete()
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }
    }
}
