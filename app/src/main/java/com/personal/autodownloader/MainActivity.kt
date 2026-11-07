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

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var urlInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var updateEngineBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLog: TextView
    private var isEngineReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUserInterface()
        initializeEngine()
    }

    private fun setupUserInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "Multi-Platform Auto Downloader"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "Paste YouTube, Instagram, Pinterest, Bilibili links (even with no spaces):"
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 8, 0, 24)
        }

        urlInput = EditText(this).apply {
            hint = "https://instagram.com/...https://pin.it/...https://youtube.com/..."
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            minLines = 5
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(32, 32, 32, 32)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 16)
        }

        downloadBtn = Button(this).apply {
            text = "Auto Detect & Save to Gallery"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener { startBatchDownload() }
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
            setPadding(0, 8, 0, 16)
        }

        statusLog = TextView(this).apply {
            text = "Initializing built-in extractor engine...\n"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.parseColor("#181818"))
            setPadding(24, 24, 24, 24)
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
        runOnUiThread {
            statusLog.append("$msg\n")
        }
    }

    // Splits links even when jammed together without spaces
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
                logMessage("Engine Ready! Supports 1000+ platforms.")
                logMessage("Zero-storage cache mode active.")
            } catch (e: Exception) {
                logMessage("Init Error: ${e.message}")
            }
        }
    }

    private fun updateExtractorEngine() {
        if (!isEngineReady) return
        updateEngineBtn.isEnabled = false
        logMessage("Checking for latest platform extractors...")
        scope.launch(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel._NIGHTLY
                )
                logMessage("Engine update status: $status")
            } catch (e: Exception) {
                logMessage("Update failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { updateEngineBtn.isEnabled = true }
            }
        }
    }

    private fun startBatchDownload() {
        if (!isEngineReady) {
            Toast.makeText(this, "Engine still initializing, wait a second...", Toast.LENGTH_SHORT).show()
            return
        }

        val links = extractGluedLinks(urlInput.text.toString())
        if (links.isEmpty()) {
            Toast.makeText(this, "Paste at least one valid http/https link!", Toast.LENGTH_SHORT).show()
            return
        }

        downloadBtn.isEnabled = false
        progressBar.progress = 0
        logMessage("\n--- Auto-Detected ${links.size} Link(s) ---")
        links.forEachIndexed { i, link -> logMessage("[${i + 1}] $link") }

        scope.launch(Dispatchers.IO) {
            val workDir = File(cacheDir, "temp_video_work")

            for ((index, url) in links.withIndex()) {
                try {
                    if (workDir.exists()) workDir.deleteRecursively()
                    workDir.mkdirs()

                    logMessage("\nDownloading (${index + 1}/${links.size}):\n$url")

                    val request = YoutubeDLRequest(url).apply {
                        addOption("-o", "${workDir.absolutePath}/video_${System.currentTimeMillis()}.%(ext)s")
                        addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                        addOption("--merge-output-format", "mp4")
                        addOption("--no-mtime")
                        addOption("--no-playlist")
                    }

                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        val p = progress.toInt().coerceIn(0, 100)
                        runOnUiThread { progressBar.progress = p }
                    }

                    val downloadedFile = workDir.listFiles()?.firstOrNull { it.isFile }
                    if (downloadedFile != null && downloadedFile.exists()) {
                        val savedName = "Video_${System.currentTimeMillis()}.mp4"
                        moveToGalleryAndDeleteTemp(downloadedFile, savedName)
                        logMessage("SUCCESS -> Saved to Gallery: Movies/AutoDownloader/$savedName")
                    } else {
                        logMessage("FAILED -> No output file created for link #${index + 1}")
                    }
                } catch (e: Exception) {
                    logMessage("ERROR on link #${index + 1}: ${e.message}")
                } finally {
                    // Strictly wipe app cache after every single video so app takes 0 MB of video storage
                    if (workDir.exists()) workDir.deleteRecursively()
                }
            }

            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                downloadBtn.isEnabled = true
                urlInput.setText("")
                logMessage("\n=== All Downloads Finished & Temp Cache Wiped (0 MB used) ===")
            }
        }
    }

    private fun moveToGalleryAndDeleteTemp(tempFile: File, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoDownloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values) ?: throw Exception("MediaStore URI creation failed")

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        } finally {
            // Immediately delete temporary file
            tempFile.delete()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
