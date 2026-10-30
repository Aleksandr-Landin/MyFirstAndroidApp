package ru.iskrinka.localagent

import android.content.Context
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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class Message(val text: String, val isUser: Boolean)

class LlmEngine(private val context: Context) {
    private var llmInference: LlmInference? = null

    fun loadModel(modelPath: String) {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setTemperature(0.7f)
                .setTopK(40)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Log.d("LlmEngine", "Модель успешно загружена!")
        } catch (e: Exception) {
            Log.e("LlmEngine", "Ошибка загрузки модели: ${e.message}", e)
            llmInference = null
        }
    }

    fun generate(prompt: String): String {
        val engine = llmInference ?: return "Ошибка: Модель не загружена."
        return try {
            engine.generateResponse(prompt)
        } catch (e: Exception) {
            Log.e("LlmEngine", "Ошибка генерации", e)
            "Произошла ошибка при генерации: ${e.message}"
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var llmEngine: LlmEngine
    private val messages = mutableStateListOf<Message>()
    private var modelPath by mutableStateOf<String?>(null)
    private var isModelLoaded by mutableStateOf(false)

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadTextFileFromUri(it) }
    }

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { copyModelToInternalStorage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        llmEngine = LlmEngine(applicationContext)
        
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        modelPath = prefs.getString("model_path", null)

        setContent {
            MaterialTheme {
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        currentModelPath = modelPath,
                        isModelLoaded = isModelLoaded,
                        onSelectModel = { pickModelLauncher.launch("*/*") },
                        onBack = { showSettings = false }
                    )
                } else {
                    ChatScreen(
                        messages = messages,
                        onOpenSettings = { showSettings = true },
                        onSend = { input ->
                            messages.add(Message(input, isUser = true))
                            
                            if (modelPath != null && isModelLoaded) {
                                messages.add(Message("Думаю...", isUser = false))
                                val index = messages.size - 1
                                
                                GlobalScope.launch(Dispatchers.IO) {
                                    val response = llmEngine.generate(input)
                                    withContext(Dispatchers.Main) {
                                        messages[index] = Message(response, isUser = false)
                                    }
                                }
                            } else {
                                messages.add(Message("⚠️ Сначала выбери модель в настройках и подожди загрузки.", isUser = false))
                            }
                        },
                        onAttach = { pickFileLauncher.launch("text/*") }
                    )
                }
            }
        }
    }

    private fun copyModelToInternalStorage(uri: Uri) {
        isModelLoaded = false
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val modelFile = File(filesDir, "current_model.gguf")
                inputStream?.use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("model_path", modelFile.absolutePath).apply()
                    modelPath = modelFile.absolutePath
                    messages.add(Message("⏳ Файл скопирован. Загружаю мозг в оперативную память...", isUser = false))
                }

                llmEngine.loadModel(modelFile.absolutePath)
                
                withContext(Dispatchers.Main) {
                    isModelLoaded = true
                    messages.add(Message("✅ Мозг успешно подключен! Можешь писать.", isUser = false))
                }
            } catch (e: Exception) {
                Log.e("ModelLoader", "Ошибка копирования модели", e)
                withContext(Dispatchers.Main) {
                    messages.add(Message("❌ Ошибка загрузки модели: ${e.message}", isUser = false))
                }
            }
        }
    }

    private fun loadTextFileFromUri(uri: Uri) {
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
                            messages.add(Message("Файл принят!", isUser = false))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentModelPath: String?,
    isModelLoaded: Boolean,
    onSelectModel: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(text = "Мозг приложения", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (currentModelPath != null) {
                    "Файл: ${File(currentModelPath).name}\n(Размер: ${File(currentModelPath).length() / 1024 / 1024} МБ)"
                } else {
                    "Модель не выбрана."
                },
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = if (isModelLoaded) "Статус: Загружена (Готова)" else "Статус: Не загружена",
                color = if (isModelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onSelectModel) {
                Text("Выбрать файл модели (.gguf)")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<Message>,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalAgent") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙️", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
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
}