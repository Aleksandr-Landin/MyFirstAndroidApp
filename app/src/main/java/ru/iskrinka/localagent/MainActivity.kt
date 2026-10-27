package ru.iskrinka.localagent

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

// 1. Модели данных (Сообщение чата)
data class Message(val text: String, val isUser: Boolean)

// 2. Заглушка для движка
class LlmEngine {
    fun generate(prompt: String): String {
        return "Модель пока не подключена. Но файл ты загрузил!"
    }
}

class MainActivity : ComponentActivity() {
    private val llmEngine = LlmEngine()
    
    private val messages = mutableStateListOf<Message>()

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadFileFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ChatScreen(
                    messages = messages,
                    onSend = { input ->
                        messages.add(Message(input, isUser = true))
                        messages.add(Message("Заглушка: Жду подключения мозга...", isUser = false))
                    },
                    onAttach = { pickFileLauncher.launch("text/*") }
                )
            }
        }
    }

    private fun loadFileFromUri(uri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line).append('\n')
                    }
                    val fileContent = stringBuilder.toString()
                    
                    withContext(Dispatchers.Main) {
                        if (fileContent.isNotEmpty()) {
                            messages.add(Message("📎 [ФАЙЛ ЗАГРУЖЕН]\n(Объем: ${fileContent.length} символов)", isUser = true))
                            messages.add(Message("Файл принят! Я готов его проанализировать (когда подключим движок).", isUser = false))
                        } else {
                            messages.add(Message("❌ Файл пуст.", isUser = false))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FileLoader", "Ошибка чтения", e)
                withContext(Dispatchers.Main) {
                    messages.add(Message("❌ Ошибка чтения файла: ${e.message}", isUser = false))
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<Message>,
    onSend: (String) -> Unit,
    onAttach: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(8.dp),
            reverseLayout = true
        ) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (!msg.isUser) Text("🤖", modifier = Modifier.padding(end = 8.dp).align(Alignment.Bottom))
                    
                    Surface(
                        modifier = Modifier.widthIn(max = 280.dp).padding(4.dp),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp
                    ) {
                        Text(msg.text, modifier = Modifier.padding(12.dp))
                    }
                    
                    if (msg.isUser) Text("👤", modifier = Modifier.padding(start = 8.dp).align(Alignment.Bottom))
                }
            }
        }
        
        Row(
            Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAttach) {
                Text("📎", fontSize = 24.sp)
            }
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение...") },
                maxLines = 4
            )
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    onSend(input)
                    input = ""
                }
            }) {
                Text("➤", fontSize = 24.sp)
            }
        }
    }
}
