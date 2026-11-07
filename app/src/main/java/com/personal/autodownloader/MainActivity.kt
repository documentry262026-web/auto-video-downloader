package com.personal.autodownloader

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var urlInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var updateEngineBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLog: TextView
    private var isEngineReady = false

    private val standardUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUserInterface()
        initializeEngine()
    }

    private fun setupUserInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 40)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "Auto Media Downloader"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "YouTube, Instagram & Pinterest (Photos + Videos) without spaces:"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 6, 0, 20)
        }

        urlInput = EditText(this).apply {
            hint = "Paste your jammed links here..."
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            minLines = 4
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setPadding(28, 28, 28, 28)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 16)
        }

        downloadBtn = Button(this).apply {
            text = "Auto Save to Gallery"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener { startBatchProcessing() }
        }

        updateEngineBtn = Button(this).apply {
            text = "Update Core"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { updateExtractorEngine() }
        }

        buttonRow.addView(downloadBtn)
        buttonRow.addView(updateEngineBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            setPadding(0, 4, 0, 14)
        }

        statusLog = TextView(this).apply {
            text = "System Ready.\n"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(20, 20, 20, 20)
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(urlInput)
        root.addView(buttonRow)
        root.addView(progressBar)
        root.addView(statusLog)

        setContentView(root)
    }

    private fun logMessage(msg: String) {
        runOnUiThread { statusLog.append("$msg\n") }
    }

    // Splits links even if stuck together with no spaces
    private fun extractGluedLinks(rawText: String): List<String> {
        return rawText
            .split(Regex("(?=https?://)"))
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun initializeEngine() {
        scope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                isEngineReady = true
                logMessage("Engine Initialized.")
            } catch (e: Exception) {
                logMessage("Engine Init Note: ${e.message}")
            }
        }
    }

    private fun updateExtractorEngine() {
        if (!isEngineReady) return
        updateEngineBtn.isEnabled = false
        logMessage("Updating yt-dlp core...")
        scope.launch(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel._NIGHTLY
                )
                logMessage("Update Finished: $status")
            } catch (e: Exception) {
                logMessage("Update Failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { updateEngineBtn.isEnabled = true }
            }
        }
    }

    private fun startBatchProcessing() {
        val rawLinks = extractGluedLinks(urlInput.text.toString())
        if (rawLinks.isEmpty()) {
            Toast.makeText(this, "Please paste at least one link!", Toast.LENGTH_SHORT).show()
            return
        }

        downloadBtn.isEnabled = false
        progressBar.progress = 0
        logMessage("\n--- Found ${rawLinks.size} Links ---")

        scope.launch(Dispatchers.IO) {
            for ((index, link) in rawLinks.withIndex()) {
                logMessage("\n[${index + 1}/${rawLinks.size}] Processing: $link")

                if (link.contains("pinterest") || link.contains("pin.it")) {
                    handlePinterestMedia(link)
                } else {
                    // YouTube, Instagram, and other platforms via yt-dlp
                    handleYoutubeDLP(link)
                }
            }

            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                downloadBtn.isEnabled = true
                urlInput.setText("")
                logMessage("\n=== All Tasks Finished (App Cache: 0 MB) ===")
            }
        }
    }

    // Handles Pinterest Video (MP4) OR Pinterest Photo (Original/HD JPG/PNG)
    private fun handlePinterestMedia(rawUrl: String) {
        try {
            logMessage("--> Checking Pinterest Pin media...")
            val conn = URL(rawUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", standardUserAgent)
            conn.connect()
            val html = conn.inputStream.bufferedReader().use { it.readText() }

            // 1. Check for Video
            val vMatcher = Pattern.compile("https://v1\\.pinimg\\.com/videos/[^\\s\"'\\\\]+\\.mp4").matcher(html)
            if (vMatcher.find()) {
                val videoUrl = vMatcher.group(0).replace("\\u0026", "&")
                logMessage("--> Video Pin Detected! Saving MP4...")
                streamDirectToGallery(videoUrl, "Pinterest_Video_${System.currentTimeMillis()}.mp4", "video/mp4", "Movies/AutoDownloader")
                return
            }

            // 2. Check for HD Original Photo
            val origMatcher = Pattern.compile("https://i\\.pinimg\\.com/originals/[^\\s\"'\\\\]+\\.(jpg|png|webp)").matcher(html)
            if (origMatcher.find()) {
                val imgUrl = origMatcher.group(0).replace("\\u0026", "&")
                val ext = if (imgUrl.contains(".png")) "png" else "jpg"
                logMessage("--> Photo Pin Detected! Saving HD Image...")
                streamDirectToGallery(imgUrl, "Pinterest_Photo_${System.currentTimeMillis()}.$ext", "image/jpeg", "Pictures/AutoDownloader")
                return
            }

            // 3. Fallback 736x Photo
            val fallbackMatcher = Pattern.compile("https://i\\.pinimg\\.com/736x/[^\\s\"'\\\\]+\\.(jpg|png|webp)").matcher(html)
            if (fallbackMatcher.find()) {
                val imgUrl = fallbackMatcher.group(0).replace("\\u0026", "&")
                val ext = if (imgUrl.contains(".png")) "png" else "jpg"
                logMessage("--> Photo Pin Detected! Saving Image...")
                streamDirectToGallery(imgUrl, "Pinterest_Photo_${System.currentTimeMillis()}.$ext", "image/jpeg", "Pictures/AutoDownloader")
                return
            }

            logMessage("--> Notice: Pin does not contain direct media stream.")
        } catch (e: Exception) {
            logMessage("--> Pinterest Error: ${e.message}")
        }
    }

    // Handles YouTube (Shorts/Videos) and Instagram (Reels) with live progress
    private fun handleYoutubeDLP(url: String) {
        val workDir = File(cacheDir, "temp_worker")
        try {
            if (workDir.exists()) workDir.deleteRecursively()
            workDir.mkdirs()

            val request = YoutubeDLRequest(url).apply {
                addOption("-o", "${workDir.absolutePath}/media_%(id)s.%(ext)s")
                addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                addOption("--merge-output-format", "mp4")
                addOption("--user-agent", standardUserAgent)
                addOption("--no-mtime")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
            }

            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                val p = progress.toInt().coerceIn(0, 100)
                runOnUiThread { progressBar.progress = p }
            }

            val outputFile = workDir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
            if (outputFile != null) {
                val displayName = "Media_${System.currentTimeMillis()}.mp4"
                saveLocalFileToGallery(outputFile, displayName)
                logMessage("--> SUCCESS! Saved directly to Gallery (Movies/AutoDownloader)")
            } else {
                logMessage("--> Notice: No output file generated.")
            }
        } catch (e: Exception) {
            logMessage("--> Download Error: ${e.message}")
        } finally {
            if (workDir.exists()) workDir.deleteRecursively()
        }
    }

    // Direct streaming to MediaStore without creating cache files
    private fun streamDirectToGallery(streamUrl: String, displayName: String, mimeType: String, relativeFolder: String) {
        val isVideo = mimeType.startsWith("video")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(collection, values) ?: return

        try {
            val conn = URL(streamUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", standardUserAgent)
            conn.connect()

            conn.inputStream.use { input ->
                contentResolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            logMessage("--> 100% SUCCESS! Saved to Gallery ($relativeFolder)")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            logMessage("--> Stream Save Error: ${e.message}")
        }
    }

    private fun saveLocalFileToGallery(tempFile: File, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoDownloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: return

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } finally {
            tempFile.delete()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
