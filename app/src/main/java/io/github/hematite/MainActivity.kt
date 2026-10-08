package io.github.hematite

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.github.hematite.ui.AppScreen
import io.github.hematite.ui.HematiteTheme
import io.github.hematite.ui.NotesViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: NotesViewModel by viewModels()
    private var ticker: Job? = null
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { viewModel.sync.request() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HematiteTheme { AppScreen(viewModel) } }
    }

    override fun onStart() {
        super.onStart()
        // Like the desktop: check for changes on return, every 30 seconds, and when the network returns.
        viewModel.populate()
        viewModel.sync.request()
        ticker = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                viewModel.sync.request()
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }

    override fun onStop() {
        viewModel.flush()
        ticker?.cancel()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        super.onStop()
    }
}
