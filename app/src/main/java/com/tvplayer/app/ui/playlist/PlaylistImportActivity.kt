package com.tvplayer.app.ui.playlist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvplayer.app.databinding.ActivityPlaylistImportBinding
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.viewmodel.MainViewModel
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.TvFocusStyler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistImportBinding
    private lateinit var preferences: PreferencesHelper
    private val viewModel: MainViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelection(it) }
    }

    companion object {
        const val TAG = "PlaylistImportActivity"
        const val EXTRA_PLAYLIST_URL = "playlist_url"
        const val EXTRA_PLAYLIST_NAME = "playlist_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = PreferencesHelper(this)

        setupUI()
        handleIntentData()
    }

    private fun setupUI() {
        // File import button
        binding.fileImportButton.setOnClickListener {
            // ACTION_GET_CONTENT grants a read URI permission to this app, so
            // no broad storage permission prompt is needed on Android 7 TV.
            filePickerLauncher.launch("*/*")
        }

        // URL import button
        binding.urlImportButton.setOnClickListener {
            handleUrlImport()
        }

        // Scan QR code button
        binding.scanButton.setOnClickListener {
            showToast("二维码扫描功能开发中")
        }

        // Back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Progress indicator
        binding.progressBar.visibility = View.GONE
        TvFocusStyler.apply(binding.root)
        binding.fileImportButton.requestFocus()
    }

    private fun handleIntentData() {
        intent.getStringExtra(EXTRA_PLAYLIST_URL)?.let { url ->
            binding.urlEditText.setText(url)
            intent.getStringExtra(EXTRA_PLAYLIST_NAME)?.let { name ->
                binding.nameEditText.setText(name)
            }
        }
    }

    private fun handleFileSelection(uri: Uri) {
        val requestedName = binding.nameEditText.text.toString().trim()
        lifecycleScope.launch {
            try {
                val playlist = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val content = inputStream.bufferedReader().use { it.readText() }
                        val playlistType = when {
                            content.trim().startsWith("#EXTM3U") -> "m3u"
                            content.trim().startsWith("[") || content.trim().startsWith("{") -> "json"
                            Regex("https?://\\S+").containsMatchIn(content) -> "text"
                            else -> "m3u"
                        }
                        val playlistName = requestedName.ifBlank { getFileName(uri) ?: "导入的播放列表" }
                        Playlist.create(
                            name = playlistName,
                            url = content,
                            type = playlistType
                        )
                    } ?: throw IllegalStateException("无法打开文件")
                }
                savePlaylist(playlist)
            } catch (e: Exception) {
                showToast("读取文件失败: ${e.message}")
            }
        }
    }

    private fun handleUrlImport() {
        val url = binding.urlEditText.text.toString().trim()
        val name = binding.nameEditText.text.toString().trim()

        if (url.isBlank()) {
            showToast("请输入播放列表URL")
            return
        }

        if (!isValidUrl(url)) {
            showToast("请输入有效的URL")
            return
        }

        val playlist = Playlist.createRemote(
            name = name.ifBlank { extractNameFromUrl(url) },
            url = url,
            description = "远程播放列表"
        )

        savePlaylist(playlist)
    }

    private fun savePlaylist(playlist: Playlist) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.urlImportButton.isEnabled = false
                binding.fileImportButton.isEnabled = false

                val playlistId = viewModel.addPlaylist(playlist)
                if (playlistId > 0) {
                    // Refresh playlist channels
                    viewModel.refreshPlaylist(playlistId)

                    showToast("播放列表导入成功")
                    finish()
                } else {
                    showToast("保存播放列表失败")
                }
            } catch (e: Exception) {
                showToast("导入失败: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.urlImportButton.isEnabled = true
                binding.fileImportButton.isEnabled = true
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https")
        } catch (e: Exception) {
            false
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.lastPathSegment?.substringBeforeLast(".")
                ?: "远程播放列表"
        } catch (e: Exception) {
            "远程播放列表"
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (binding.progressBar.visibility == View.VISIBLE) {
            // Don't allow back press during operation
            return
        }
        super.onBackPressed()
    }
}
