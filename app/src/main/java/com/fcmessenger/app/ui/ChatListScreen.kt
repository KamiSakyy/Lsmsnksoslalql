package com.fcmessenger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fcmessenger.app.data.Repo
import com.fcmessenger.app.data.local.ChatEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(repo: Repo, onChat: (String) -> Unit, onAdd: () -> Unit) {
    val chats by repo.chats().collectAsState(initial = emptyList())
    val zero by repo.zeroMode.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Чаты") },
                actions = {
                    Text("0", style = MaterialTheme.typography.labelLarge)
                    Switch(
                        checked = zero,
                        onCheckedChange = { scope.launch { repo.setZeroMode(it) } }
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (zero) {
                Text(
                    "0-режим: отправка только по кешу, лишнего трафика нет.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (chats.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
                    Text("Нажми + и добавь друга по нику", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn {
                    items(chats, key = { it.peerUid }) { c ->
                        ChatRow(c, onClick = { onChat(c.peerUid) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(c: ChatEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.peerName, style = MaterialTheme.typography.titleMedium)
            Text(
                c.lastText.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (c.lastTs > 0) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.lastTs)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (c.unread > 0) {
                BadgedBox(badge = { Badge { Text("$c.unread".take(4)) } }) {}
            }
        }
    }
}
