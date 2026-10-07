package com.fcmessenger.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fcmessenger.app.data.Profile
import com.fcmessenger.app.ui.AddContactScreen
import com.fcmessenger.app.ui.AuthScreen
import com.fcmessenger.app.ui.ChatListScreen
import com.fcmessenger.app.ui.ChatScreen
import com.fcmessenger.app.ui.FCMTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        val repo = (application as App).container.repo
        setContent {
            FCMTheme {
                val nav = rememberNavController()
                var boot: Profile? by remember { mutableStateOf(null) }
                var ready by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    boot = repo.profile.first()
                    ready = true
                }
                // открытие чата из уведомления
                LaunchedEffect(Unit) {
                    repo.openChat.collect { peer ->
                        nav.navigate("chat/$peer")
                    }
                }

                if (!ready) {
                    Loading()
                } else {
                    NavHost(
                        navController = nav,
                        startDestination = if (boot == null) "auth" else "chats"
                    ) {
                        composable("auth") {
                            AuthScreen(repo) {
                                nav.navigate("chats") { popUpTo("auth") { inclusive = true } }
                            }
                        }
                        composable("chats") {
                            ChatListScreen(
                                repo,
                                onChat = { nav.navigate("chat/$it") },
                                onAdd = { nav.navigate("add") }
                            )
                        }
                        composable("chat/{peer}") { backStack ->
                            val peer = backStack.arguments?.getString("peer") ?: return@composable
                            ChatScreen(repo, peer, onBack = { nav.popBackStack() })
                        }
                        composable("add") {
                            AddContactScreen(
                                repo,
                                onOpened = { nav.navigate("chat/$it") { popUpTo("chats") } },
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "open_chat") {
            intent.getStringExtra("peer")?.let {
                (application as App).container.repo.requestOpen(it)
            }
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
    }
}
