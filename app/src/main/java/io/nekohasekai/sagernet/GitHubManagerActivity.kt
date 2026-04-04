package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GitHubManagerActivity : AppCompatActivity() {

    // Классы для хранения строк файла
    companion object {
        const val TYPE_META = 0
        const val TYPE_GROUP = 1
        const val TYPE_PROXY = 2
    }

    data class LineItem(
        val originalLine: String,
        val type: Int,
        val displayTitle: String = ""
    ) {
        var isChecked: Boolean = false
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: GithubAdapter

    private var currentFileSha: String? = null
    private val allLines = mutableListOf<LineItem>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_manager)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        progressBar = findViewById(R.id.progress_bar)
        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = GithubAdapter(allLines)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.btn_clear_all).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this) // <--- Изменена только эта строчка
                .setTitle("Очистить всё?")
                .setMessage("Это удалит ВСЕ прокси и группы из файла. Останутся только настройки обновления вверху.")
                .setPositiveButton("Да, удалить всё") { _, _ ->
                    allLines.removeAll { it.type == TYPE_PROXY || it.type == TYPE_GROUP }
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Файл очищен!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        if (DataStore.githubToken.isBlank() || DataStore.githubRepo.isBlank()) {
            Toast.makeText(this, "Сначала настройте GitHub в настройках AutoPilot", Toast.LENGTH_LONG).show()
            return
        }

        loadFileFromGitHub()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val saveItem = menu.add(0, 1, 0, "Сохранить на GitHub")
        saveItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            saveFileToGitHub()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadFileFromGitHub() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/${DataStore.githubRepo}/contents/${DataStore.githubFilePath}"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${DataStore.githubToken}")
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    currentFileSha = json.getString("sha")
                    val contentBase64 = json.getString("content")
                    val decodedText = String(Base64.decode(contentBase64, Base64.DEFAULT))

                    parseTextToItems(decodedText)

                    withContext(Dispatchers.Main) {
                        adapter.notifyDataSetChanged()
                        progressBar.visibility = View.GONE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (response.code == 404) {
                            parseTextToItems("# Новый файл\n# Общее количество прокси: 0")
                            adapter.notifyDataSetChanged()
                        } else {
                            Toast.makeText(this@GitHubManagerActivity, "Ошибка: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Logs.e(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GitHubManagerActivity, "Ошибка сети: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun parseTextToItems(text: String) {
        allLines.clear()
        val lines = text.split("\n")

        for (line in lines) {
            if (line.isBlank()) continue

            when {
                line.startsWith("# === BEGIN") -> {
                    val title = line.replace("# === BEGIN", "").replace("===", "").trim()
                    allLines.add(LineItem(line, TYPE_GROUP, "📁 Группа: $title"))
                }
                line.startsWith("# === END") -> {
                    allLines.add(LineItem(line, TYPE_GROUP, "")) // Пустая строка для END, скроем её в UI
                }
                line.startsWith("vless://") || line.startsWith("vmess://") ||
                        line.startsWith("hy2://") || line.startsWith("trojan://") || line.startsWith("ss://") -> {
                    // Извлекаем название прокси после знака #
                    val rawName = line.substringAfterLast("#", "Неизвестный прокси")
                    // Декодируем URL (чтобы флаги и пробелы отображались нормально)
                    val decodedName = try {
                        URLDecoder.decode(rawName, "UTF-8")
                    } catch (e: Exception) {
                        rawName
                    }
                    allLines.add(LineItem(line, TYPE_PROXY, decodedName))
                }
                else -> {
                    // Метадата (# profile-title и т.д.)
                    allLines.add(LineItem(line, TYPE_META, line))
                }
            }
        }
    }

    private fun saveFileToGitHub() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Формируем новый текст файла
                val currentProxiesCount = allLines.count { it.type == TYPE_PROXY }
                val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                val currentDate = sdf.format(Date())

                val sb = StringBuilder()
                for (item in allLines) {
                    // Автоматически обновляем статистику в заголовке файла
                    if (item.type == TYPE_META) {
                        if (item.originalLine.startsWith("# Последнее обновление:")) {
                            sb.append("# Последнее обновление: $currentDate\n")
                            continue
                        }
                        if (item.originalLine.startsWith("# Общее количество прокси:")) {
                            sb.append("# Общее количество прокси: $currentProxiesCount\n")
                            continue
                        }
                    }

                    // Если это скрытый # === END === и перед ним нет прокси, можно было бы удалять,
                    // но для простоты просто сохраняем как есть
                    sb.append(item.originalLine).append("\n")
                }

                val finalFileContent = sb.toString().trimEnd()
                val base64Content = Base64.encodeToString(finalFileContent.toByteArray(), Base64.NO_WRAP)

                // 2. Отправляем на GitHub
                val url = "https://api.github.com/repos/${DataStore.githubRepo}/contents/${DataStore.githubFilePath}"
                val jsonBody = JSONObject().apply {
                    put("message", "Очистка/Редактирование через Менеджер ($currentProxiesCount прокси)")
                    put("content", base64Content)
                    if (currentFileSha != null) put("sha", currentFileSha)
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${DataStore.githubToken}")
                    .header("Accept", "application/vnd.github.v3+json")
                    .put(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        currentFileSha = json.getJSONObject("content").getString("sha")
                        Toast.makeText(this@GitHubManagerActivity, "Успешно сохранено!", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@GitHubManagerActivity, "Ошибка: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Logs.e(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GitHubManagerActivity, "Ошибка сети: ${e.message}", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    // ════════════════ ADAPTER ════════════════
    inner class GithubAdapter(private val items: List<LineItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return items[position].type
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_PROXY -> ProxyViewHolder(inflater.inflate(R.layout.item_github_proxy, parent, false))
                else -> HeaderViewHolder(inflater.inflate(R.layout.item_github_header, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is ProxyViewHolder -> {
                    holder.name.text = item.displayTitle
                    holder.checkbox.isChecked = item.isChecked

                    // Обработка клика по всему ряду (не только по чекбоксу)
                    holder.itemView.setOnClickListener {
                        item.isChecked = !item.isChecked
                        holder.checkbox.isChecked = item.isChecked
                    }
                    holder.checkbox.setOnClickListener {
                        item.isChecked = holder.checkbox.isChecked
                    }
                }
                is HeaderViewHolder -> {
                    // Прячем системные теги # === END ===
                    if (item.displayTitle.isBlank()) {
                        holder.itemView.visibility = View.GONE
                        holder.itemView.layoutParams = RecyclerView.LayoutParams(0, 0)
                    } else {
                        holder.itemView.visibility = View.VISIBLE
                        holder.itemView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        holder.text.text = item.displayTitle
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        inner class ProxyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val name: TextView = view.findViewById(R.id.proxy_name)
        }

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.header_text)
        }
    }
}