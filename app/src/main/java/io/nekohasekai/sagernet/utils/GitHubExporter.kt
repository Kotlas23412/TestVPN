package io.nekohasekai.sagernet.utils

import android.annotation.SuppressLint
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
import java.util.Locale

object GitHubExporter {

    private val client = OkHttpClient()

    data class ExportResult(val success: Boolean, val message: String)

    /**
     * Выполняет экспорт прокси на GitHub с авто-переименованием.
     */
    suspend fun exportGroup(
        groupName: String,
        proxies: List<ProxyEntity>
    ): ExportResult = withContext(Dispatchers.IO) {

        val token = DataStore.githubToken.trim()
        val repo = DataStore.githubRepo.trim()
        val path = DataStore.githubFilePath.trim()

        if (token.isBlank() || repo.isBlank() || path.isBlank()) {
            return@withContext ExportResult(false, "Не указаны настройки GitHub (Token, Repo, Path)")
        }

        val apiUrl = "https://api.github.com/repos/$repo/contents/$path"

        try {
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
                    val json = JSONObject(response.body!!.string())
                    fileSha = json.optString("sha", "")
                    val base64Content = json.optString("content", "").replace("\n", "")
                    if (base64Content.isNotEmpty()) {
                        currentText = String(Base64.decode(base64Content, Base64.DEFAULT))
                    }
                } else if (response.code != 404) {
                    return@withContext ExportResult(false, "Ошибка получения файла: ${response.code}")
                }
            }

            // 2. Формируем новый блок прокси для текущей группы
            val newGroupBlock = buildString {
                appendLine("# === BEGIN $groupName ===")
                for ((index, p) in proxies.withIndex()) {
                    try {
                        // ПРЕВРАЩАЕМ В КРАСИВЫЙ СТАНДАРТНЫЙ ЛИНК
                        appendLine(p.toNormalizedStandardLink(index))
                    } catch (e: Exception) {
                        // Игнорируем битые прокси
                    }
                }
                append("# === END $groupName ===")
            }

            // 3. Интегрируем блок в существующий текст
            var updatedText = currentText
            if (updatedText.isEmpty()) {
                updatedText = newGroupBlock
            } else {
                val regex = Regex("# === BEGIN $groupName ===.*?# === END $groupName ===", RegexOption.DOT_MATCHES_ALL)
                if (updatedText.contains(regex)) {
                    updatedText = updatedText.replace(regex, newGroupBlock)
                } else {
                    updatedText = updatedText.trimEnd() + "\n\n" + newGroupBlock
                }
            }

            // 4. Считаем общее количество прокси
            val cleanLines = updatedText.lines().filter { !it.startsWith("#") && it.contains("://") }
            totalProxiesInFile = cleanLines.size

            // 5. Отправляем обновленный файл на GitHub
            val finalBase64 = Base64.encodeToString(updatedText.toByteArray(), Base64.NO_WRAP)

            val putBody = JSONObject().apply {
                put("message", "Auto-update group: $groupName")
                put("content", finalBase64)
                if (fileSha.isNotEmpty()) put("sha", fileSha)
            }

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github.v3+json")
                .put(putBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(putReq).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext ExportResult(true, "Успешно выгружено $totalProxiesInFile прокси на GitHub!")
                } else {
                    return@withContext ExportResult(false, "Ошибка отправки: ${response.code}")
                }
            }

        } catch (e: Exception) {
            return@withContext ExportResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Меняет имя, добавляет номер и генерирует СТАНДАРТНУЮ ссылку (vless:// и т.д.)
     */
    @SuppressLint("DefaultLocale")
    private fun ProxyEntity.toNormalizedStandardLink(index: Int): String {
        val bean = requireBean()
        val originalName = bean.name ?: "Unknown"

        // Используем 100% оффлайн систему NekoBox для поиска страны
        var countryCode = moe.matsuri.nb4a.utils.GeoIPHelper.extractCountryFromOriginalName(originalName)

        if (countryCode == "Unknown" || countryCode.isBlank()) {
            val serverAddr = bean.serverAddress ?: ""
            // Если по имени не нашли, NekoBox сам проверит IP-адрес по оффлайн-базе geoip.dat!
            val ipCode = moe.matsuri.nb4a.utils.GeoIPHelper.detectCountryByIpOffline(serverAddr)
            countryCode = moe.matsuri.nb4a.utils.GeoIPHelper.buildBaseName(ipCode)
        }

        // Делаем красивый флаг и русское имя
        val flag = countryCodeToFlag(countryCode)
        var countryName = "Локация"
        try {
            if (countryCode.length == 2) {
                countryName = java.util.Locale("ru", countryCode).displayCountry
            }
        } catch (_: Exception) {}

        // Определяем CIDR / LTE
        val upperName = originalName.uppercase()
        val isLte = bean.serverAddress?.contains("/") == true || upperName.contains("CIDR") || upperName.contains("LTE")
        val lteSuffix = if (isLte) " | LTE" else ""

        // Добавляем номер (01, 02), чтобы ссылки не склеивались при импорте
        val num = String.format("%02d", index + 1)

        // Применяем новое идеальное имя
        bean.name = "$flag $countryName$lteSuffix $num"

        // ВОТ ОНО! toStdLink() заставит NekoBox выдать чистый vless:// без sn://
        return toStdLink()
    }

    private fun countryCodeToFlag(countryCode: String): String {
        val code = countryCode.uppercase(Locale.US)
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌍"
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}