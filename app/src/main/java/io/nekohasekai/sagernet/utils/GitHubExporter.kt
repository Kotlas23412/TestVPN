package io.nekohasekai.sagernet.utils

import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GitHubExporter {
    private val client = OkHttpClient()

    data class ExportResult(val success: Boolean, val message: String)

    suspend fun exportGroup(
        groupName: String,
        proxies: List<ProxyEntity>
    ): ExportResult {
        return exportMultipleGroups(mapOf(groupName to proxies))
    }

    suspend fun exportMultipleGroups(
        groups: Map<String, List<ProxyEntity>>
    ): ExportResult = withContext(Dispatchers.IO) {
        // БРОНЯ: Оборачиваем ВЕСЬ код в try/catch(Throwable), чтобы ни одна ошибка,
        // нехватка памяти или кривой ответ сервера не смогли закрыть приложение.
        try {
            val token = DataStore.githubToken.trim()
            val repo = DataStore.githubRepo.trim()
            val path = DataStore.githubFilePath.trim()

            if (token.isBlank() || repo.isBlank() || path.isBlank()) {
                return@withContext ExportResult(false, "Не указаны настройки GitHub (Token, Repo, Path)")
            }

            val apiUrl = "https://api.github.com/repos/$repo/contents/$path"

            // 1. Получаем текущий файл
            val getReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            var currentText = ""
            var fileSha = ""
            var totalProxiesInFile = 0

            client.newCall(getReq).execute().use { response ->
                if (response.isSuccessful) {
                    // Исключаем краш NullPointerException, если тело ответа пустое
                    val bodyString = response.body?.string() ?: "{}"
                    val json = JSONObject(bodyString)

                    fileSha = json.optString("sha", "")
                    val base64Content = json.optString("content", "").replace("\n", "")
                    if (base64Content.isNotEmpty()) {
                        currentText = String(Base64.decode(base64Content, Base64.DEFAULT))
                    }
                } else if (response.code != 404) {
                    return@withContext ExportResult(false, "Ошибка получения файла: ${response.code}")
                }
            }

            // 2. Обрабатываем каждый отдельный блок (страну) по очереди
            var updatedText = currentText
            for ((blockName, proxies) in groups) {
                // ФИКС КРАША REGEX: Экранируем спецсимволы в названии группы!
                // Если имя группы "AutoPilot (Best)", оно не сломает поиск и приложение.
                val safeBlockName = Regex.escape(blockName)

                val newGroupBlock = buildString {
                    appendLine("# === BEGIN $blockName ===")
                    for (p in proxies) {
                        try {
                            // Если сервер "битый", он просто пропустится, а не крашнет выгрузку
                            val link = p.toStdLink()
                            if (link.isNotBlank()) {
                                appendLine(link)
                            }
                        } catch (e: Throwable) {
                            // Игнорируем кривую ссылку
                        }
                    }
                    append("# === END $blockName ===")
                }

                if (updatedText.isEmpty()) {
                    updatedText = newGroupBlock
                } else {
                    val regex = Regex("# === BEGIN $safeBlockName ===.*?# === END $safeBlockName ===", RegexOption.DOT_MATCHES_ALL)
                    if (updatedText.contains(regex)) {
                        updatedText = updatedText.replace(regex, newGroupBlock)
                    } else {
                        updatedText = updatedText.trimEnd() + "\n\n" + newGroupBlock
                    }
                }
            }

            // 3. Считаем общее количество прокси
            val lines = updatedText.lines()
            val cleanLines = lines.filter { !it.startsWith("#") && it.contains("://") }
            totalProxiesInFile = cleanLines.size

            // 4. Генерируем заголовки
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()
            val currentDate = sdf.format(Date())

            val rawTitle = "Всё рабочее (Тест) \uD83D\uDD16 $currentDate"
            val titleBase64 = Base64.encodeToString(rawTitle.toByteArray(), Base64.NO_WRAP)
            val updatedGroupsNames = groups.keys.joinToString(", ")

            val headerBlock = buildString {
                appendLine("# profile-title: base64:$titleBase64")
                appendLine("# profile-update-interval: 1")
                appendLine("# Последнее обновление: $currentDate")
                appendLine("# Общее количество прокси: $totalProxiesInFile")
                appendLine("# Последние обновленные группы: $updatedGroupsNames")
                appendLine()
            }

            val textWithoutHeaders = updatedText.replace(Regex("(?s)^.*?# === BEGIN"), "# === BEGIN")
            val finalText = headerBlock + textWithoutHeaders
            val finalBase64 = Base64.encodeToString(finalText.toByteArray(), Base64.NO_WRAP)

            // 5. Отправляем обратно на GitHub
            val putBody = JSONObject().apply {
                put("message", "Auto-update ${groups.size} groups (Countries)")
                put("content", finalBase64)
                if (fileSha.isNotEmpty()) {
                    put("sha", fileSha)
                }
            }

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .put(putBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(putReq).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext ExportResult(true, "Успешно выгружено $totalProxiesInFile прокси (Стран: ${groups.size})!")
                } else {
                    return@withContext ExportResult(false, "Ошибка отправки: ${response.code} ${response.body?.string()}")
                }
            }

        } catch (e: Throwable) {
            // Ловим вообще ЛЮБЫЕ ошибки (нет интернета, сломался JSON, нехватка памяти и т.д.)
            e.printStackTrace()
            return@withContext ExportResult(false, "Сбой GitHub: ${e.message}")
        }
    }
}