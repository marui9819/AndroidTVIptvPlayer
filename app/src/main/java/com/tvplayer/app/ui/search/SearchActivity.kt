package com.tvplayer.app.ui.search

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.databinding.ActivitySearchBinding
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.viewmodel.SearchViewModel
import com.tvplayer.app.util.PlayerHelper

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var viewModel: SearchViewModel
    private lateinit var searchAdapter: SearchAdapter

    companion object {
        const val TAG = "SearchActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel by viewModels<SearchViewModel>()

        setupUI()
        setupObservers()
        setupKeyboardHandling()
    }

    private fun setupUI() {
        // Setup search field
        binding.searchEditText.requestFocus()
        binding.searchEditText.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            if (query.length >= 2) {
                viewModel.searchChannels(query)
            } else if (query.isEmpty()) {
                viewModel.clearResults()
            }
        }

        // Setup RecyclerView
        searchAdapter = SearchAdapter { channel ->
            onChannelSelected(channel)
        }

        binding.searchResultsRecyclerView.apply {
            adapter = searchAdapter
            layoutManager = LinearLayoutManager(this@SearchActivity)
        }

        // Setup clear button
        binding.clearButton.setOnClickListener {
            binding.searchEditText.setText("")
            viewModel.clearResults()
        }

        // Setup back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Initial state
        binding.noResultsText.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
    }

    private fun setupObservers() {
        viewModel.searchResults.observe(this) { channels ->
            searchAdapter.submitList(channels)

            binding.noResultsText.visibility = if (channels.isEmpty()) {
                binding.searchResultsRecyclerView.visibility = View.GONE
                View.VISIBLE
            } else {
                binding.searchResultsRecyclerView.visibility = View.VISIBLE
                View.GONE
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.searchError.observe(this) { error ->
            error?.let { showToast(it) }
        }
    }

    private fun setupKeyboardHandling() {
        binding.searchEditText.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP -> {
                    val query = binding.searchEditText.text.toString()
                    if (query.isNotEmpty()) {
                        viewModel.searchChannels(query)
                        hideKeyboard()
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP -> {
                    if (binding.searchEditText.hasFocus()) {
                        binding.searchEditText.clearFocus()
                    } else {
                        finish()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onChannelSelected(channel: Channel) {
        PlayerHelper.startChannelPlayback(this, channel)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        binding.searchEditText.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (binding.searchEditText.hasFocus()) {
                    binding.searchEditText.clearFocus()
                } else {
                    finish()
                }
                true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                binding.searchEditText.requestFocus()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}