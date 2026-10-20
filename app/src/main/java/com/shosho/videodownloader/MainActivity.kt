package com.shosho.videodownloader

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shosho.videodownloader.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private data class QualityOption(val label: String, val height: Int?)

    private val qualityOptions = listOf(
        QualityOption("Best available", null),
        QualityOption("Up to 1080p", 1080),
        QualityOption("Up to 720p", 720),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.qualitySpinner.adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            qualityOptions.map { it.label },
        )

        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.downloadButton.setOnClickListener { onDownloadClicked() }
        binding.openDownloadsButton.setOnClickListener { openDownloadsFolder() }
        binding.shareButton.setOnClickListener { shareLastDownload() }
        binding.updateButton.setOnClickListener { onUpdateClicked() }

        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = SiteRules.extractUrl(sharedText) ?: return
        binding.urlInput.setText(url)
    }

    private fun onUpdateClicked() {
        if (downloading) return
        binding.updateButton.isEnabled = false
        binding.statusText.text = "Checking for updates…"
        thread {
            val result = Engine.updateYtDlp(applicationContext) { message ->
                runOnUiThread { binding.statusText.text = message }
            }
            runOnUiThread {
                binding.statusText.text = if (result.version != null) {
                    "Downloader ${result.version} installed. Ready for your next download."
                } else {
                    "Update failed: ${result.error}"
                }
                binding.updateButton.isEnabled = true
            }
        }
    }

    private var lastDownloadUri: Uri? = null

    private fun shareLastDownload() {
        val uri = lastDownloadUri ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share video"))
    }

    private fun openDownloadsFolder() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "No Downloads app found on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
        binding.urlInput.setText(text)
    }

    private var downloading = false

    private fun onDownloadClicked() {
        if (downloading) return
        val raw = binding.urlInput.text?.toString().orEmpty()
        val url = try {
            SiteRules.validateUrl(raw)
        } catch (e: SiteRules.InvalidUrlException) {
            binding.statusText.text = e.message
            return
        }
        val selectedHeight = qualityOptions[binding.qualitySpinner.selectedItemPosition].height
        val customName = binding.customNameInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        downloading = true
        binding.downloadButton.isEnabled = false
        binding.progressBar.progress = 0
        binding.logText.text = ""
        binding.statusText.text = "Preparing…"

        thread {
            try {
                val result = Engine.download(
                    context = applicationContext,
                    url = url,
                    heightCap = selectedHeight,
                    customName = customName,
                    onProgress = { progress ->
                        runOnUiThread {
                            progress.percent?.let { binding.progressBar.progress = it }
                            binding.statusText.text = "Downloading…${progress.statusLine}"
                        }
                    },
                    onLog = { line ->
                        runOnUiThread { binding.logText.append("$line\n") }
                    },
                )
                runOnUiThread {
                    if (result.success && result.savedUri != null) {
                        binding.statusText.text = "Saved to Downloads: ${result.savedFileName}"
                        binding.progressBar.progress = 100
                        lastDownloadUri = result.savedUri
                        binding.shareButton.isEnabled = true
                        binding.customNameInput.text?.clear()
                    } else {
                        binding.statusText.text = "Download failed. See log below."
                        binding.logText.append("\n${result.log}\n")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.statusText.text = "Error: ${e.message}"
                }
            } finally {
                runOnUiThread {
                    downloading = false
                    binding.downloadButton.isEnabled = true
                }
            }
        }
    }
}
