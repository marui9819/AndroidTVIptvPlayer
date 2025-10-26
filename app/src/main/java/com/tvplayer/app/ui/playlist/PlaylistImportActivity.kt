package com.tvplayer.app.ui.playlist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tvplayer.app.databinding.ActivityPlaylistImportBinding
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.viewmodel.MainViewModel
import com.tvplayer.app.util.PreferencesHelper
import kotlinx.coroutines.launch

class PlaylistImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistImportBinding
    private lateinit var preferences: PreferencesHelper

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelection(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, proceed with file picker
            filePickerLauncher.launch("*/*")
        } else {
            showToast("需要存储权限来导入播放列表")
        }
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
            checkAndRequestStoragePermission()
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
    }

    private fun handleIntentData() {
        intent.getStringExtra(EXTRA_PLAYLIST_URL)?.let { url ->
            binding.urlEditText.setText(url)
            intent.getStringExtra(EXTRA_PLAYLIST_NAME)?.let { name ->
                binding.nameEditText.setText(name)
            }
        }
    }

    private fun checkAndRequestStoragePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                filePickerLauncher.launch("*/*")
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun handleFileSelection(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val content = inputStream.bufferedReader().use { it.readText() }

                // Determine playlist type
                val playlistType = when {
                    content.trim().startsWith("#EXTM3U") -> {
                        Playlist.PlaylistType.M3U
                    }
                    content.trim().startsWith("[") || content.trim().startsWith("{") -> {
                        Playlist.PlaylistType.JSON
                    }
                    else -> {
                        // Try to detect URL lists
                        val urlPattern = Regex("https?://\\S+")
                        if (urlPattern.containsMatchIn(content)) {
                            Playlist.PlaylistType.TEXT_URLS
                        } else {
                            Playlist.PlaylistType.M3U // Default to M3U
                        }
                    }
                }

                // Extract playlist name from file or content
                val fileName = getFileName(uri)
                val playlistName = binding.nameEditText.text.toString().takeIf { it.isNotBlank() }
                    ?: fileName ?: "导入的播放列表"

                // Create playlist
                val playlist = Playlist.create(
                    name = playlistName,
                    url = content,
                    type = playlistType
                )

                // Save playlist
                savePlaylist(playlist)
            }
        } catch (e: Exception) {
            showToast("读取文件失败: ${e.message}")
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

                val viewModel: MainViewModel by lazy {
                    androidx.activity.viewModels()
                }

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