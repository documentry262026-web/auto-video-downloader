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
            text = "Auto Video Downloader"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val subtitle = TextView(this).apply {
            text = "Paste YouTube, Instagram, Pinterest links (spaces not required):"
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
            text = "Download Directly to Gallery"
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
            setPadding(0, 4, 0, 14)
        }

        statusLog = TextView(this).apply {
            text = "Ready to download.\n"
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
        runOnUiThread {
            statusLog.append("$msg\n")
        }
    }

    private fun extractGluedLinks(rawText: String): List<String> {
        return rawText
            .split(Regex("(?=https?://)"))
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun cleanPinterestUrl(rawUrl: String): String {
        if (!rawUrl.contains("pinterest") && !rawUrl.contains("pin.it")) return rawUrl
        return try {
            val conn = URL(rawUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.connect()
            val finalUrl = conn.url.toString()
            val pattern = Pattern.compile("/pin/(\\d+)")
            val matcher = pattern.matcher(finalUrl)
            if (matcher.find()) {
                "https://www.pinterest.com/pin/${matcher.group(1)}/"
            } else finalUrl
        } catch (e: Exception) {
            rawUrl
        }
    }

    private fun initializeEngine() {
        scope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                isEngineReady = true
                logMessage("Engine Initialized Successfully.")
            } catch (e: Exception) {
                logMessage("Engine Init Error: ${e.message}")
            }
        }
    }

    private fun updateExtractorEngine() {
        if (!isEngineReady) return
        updateEngineBtn.isEnabled = false
        logMessage("Updating yt-dlp core to latest version...")
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

    private fun startBatchDownload() {
        if (!isEngineReady) {
            Toast.makeText(this, "Engine is loading, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        val rawLinks = extractGluedLinks(urlInput.text.toString())
        if (rawLinks.isEmpty()) {
            Toast.makeText(this, "Please paste at least one link!", Toast.LENGTH_SHORT).show()
            return
        }

        downloadBtn.isEnabled = false
        progressBar.progress = 0
        logMessage("\n--- Found ${rawLinks.size} Links ---")

        scope.launch(Dispatchers.IO) {
            val workDir = File(cacheDir, "temp_stream_work")

            for ((index, rawUrl) in rawLinks.withIndex()) {
                try {
                    if (workDir.exists()) workDir.deleteRecursively()
                    workDir.mkdirs()

                    val targetUrl = cleanPinterestUrl(rawUrl)
                    logMessage("\n[${index + 1}/${rawLinks.size}] Downloading: $targetUrl")

                    val request = YoutubeDLRequest(targetUrl).apply {
                        addOption("-o", "${workDir.absolutePath}/video_%(id)s.%(ext)s")
                        addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                        addOption("--merge-output-format", "mp4")
                        // Browser User-Agent: Instagram block bypass karne ke liye
                        addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        addOption("--no-mtime")
                        addOption("--no-playlist")
                        addOption("--no-check-certificates")
                    }

                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        val p = progress.toInt().coerceIn(0, 100)
                        runOnUiThread { progressBar.progress = p }
                    }

                    val downloadedFile = workDir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
                    if (downloadedFile != null) {
                        val finalName = "Downloader_${System.currentTimeMillis()}.mp4"
                        saveDirectlyToGallery(downloadedFile, finalName)
                        logMessage("--> SUCCESS! Saved directly to Gallery (Movies/AutoDownloader)")
                    } else {
                        logMessage("--> Notice: No video file created (likely an image pin or protected post).")
                    }
                } catch (e: Exception) {
                    val err = e.message ?: ""
                    if (err.contains("No video formats found")) {
                        logMessage("--> Notice: Link contains Photo/Image only, no video found.")
                    } else {
                        logMessage("--> Error: $err")
                    }
                } finally {
                    // Cache instantly wiped so app keeps 0 MB storage
                    if (workDir.exists()) workDir.deleteRecursively()
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

    private fun saveDirectlyToGallery(tempFile: File, displayName: String) {
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
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
        } finally {
            tempFile.delete()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
