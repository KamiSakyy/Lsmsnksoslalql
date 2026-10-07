package com.fcmessenger.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fcmessenger.app.data.Repo
import com.fcmessenger.app.data.local.MessageEntity
import com.fcmessenger.app.data.local.Status
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repo: Repo, peerUid: String, onBack: () -> Unit) {
    val messages by repo.messages(peerUid).collectAsState(initial = emptyList())
    val typing by repo.typing.collectAsState()
    val chats by repo.chats().collectAsState(initial = emptyList())
    val peerName = chats.firstOrNull { it.peerUid == peerUid }?.peerName ?: "Чат"
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(peerUid) { repo.markRead(peerUid) }
    LaunchedEffect(messages.size) {
        repo.markRead(peerUid)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(peerName)
                        if (peerUid in typing) {
                            Text("печатает…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        if (it.isNotBlank()) repo.sendTyping(peerUid)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 4
                )
                IconButton(
                    onClick = {
                        val t = input
                        input = ""
                        scope.launch { repo.sendText(peerUid, t) }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                Bubble(m)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun Bubble(m: MessageEntity) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        fmt.format(Date(m.ts)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (m.outgoing) {
                        Text(
                            when (m.status) {
                                Status.SENDING, Status.QUEUED -> " 🕐"
                                Status.SENT -> " ✓"
                                Status.DELIVERED -> " ✓✓"
                                Status.READ -> " ✓✓"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.status == Status.READ) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
