package io.nekohasekai.sagernet.utils

import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GitHubExporter {

    private val client = OkHttpClient()
    // Кэш стран, чтобы не спамить запросами для одинаковых IP
    private val countryCache = HashMap<String, CountryInfo?>()
    private val flagRegex = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    data class ExportResult(val success: Boolean, val message: String)
    data class CountryInfo(val code: String, val name: String)

    /**
     * Выполняет "умный" экспорт прокси на GitHub с авто-переименованием.
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
            // 1. Получаем текущий файл (чтобы узнать его SHA и текущий текст)
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
                for (p in proxies) {
                    appendLine(p.toNormalizedLink())
                }
                append("# === END $groupName ===")
            }

            // 3. Интегрируем блок в существующий текст
            val updatedText = if (currentText.isEmpty()) {
                newGroupBlock
            } else {
                val regex = Regex("# === BEGIN $groupName ===.*?# === END $groupName ===", RegexOption.DOT_MATCHES_ALL)
                if (currentText.contains(regex)) {
                    currentText.replace(regex, newGroupBlock)
                } else {
                    currentText + "\n\n" + newGroupBlock
                }
            }

            // 4. Считаем общее количество прокси
            val lines = updatedText.lines()
            val cleanLines = lines.filter { !it.startsWith("#") && it.contains("://") }
            totalProxiesInFile = cleanLines.size

            // 5. Отправляем обновленный файл на GitHub
            val finalBase64 = Base64.encodeToString(updatedText.toByteArray(), Base64.NO_WRAP)

            val putBody = JSONObject().apply {
                put("message", "Auto-update group: $groupName")
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
                    return@withContext ExportResult(true, "Успешно выгружено $totalProxiesInFile прокси на GitHub!")
                } else {
                    return@withContext ExportResult(false, "Ошибка отправки: ${response.code} ${response.body?.string()}")
                }
            }

        } catch (e: Exception) {
            return@withContext ExportResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Меняет имя и сразу конвертирует в ссылку
     */
    private fun ProxyEntity.toNormalizedLink(): String {
        val bean = requireBean()
        val normalizedName = buildProxyName(bean)
        bean.name = normalizedName // перезаписываем имя
        return bean.toUniversalLink() // генерируем ссылку
    }

    private fun buildProxyName(bean: AbstractBean): String {
        val country = detectCountry(bean)
        val countryInfo = country ?: detectCountryFromName(bean.name.orEmpty())

        val flag = if (countryInfo != null) countryCodeToFlag(countryInfo.code) else "\uD83C\uDFF3️"
        val countryName = countryInfo?.name ?: "Unknown"
        val lte = if (isCidrProxy(bean)) " | LTE" else ""

        return "$flag $countryName$lte"
    }

    private fun detectCountry(bean: AbstractBean): CountryInfo? {
        val host = bean.serverAddress?.trim().orEmpty().removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return null

        // Смотрим в кэш
        countryCache[host]?.let { return it }

        val ip = if (isIpAddress(host)) {
            host
        } else {
            try {
                InetAddress.getByName(host).hostAddress ?: host
            } catch (_: Exception) {
                host
            }
        }

        val resolved = requestCountryByIp(ip)
        countryCache[host] = resolved
        if (ip != host) countryCache[ip] = resolved
        return resolved
    }

    private fun requestCountryByIp(ip: String): CountryInfo? {
        if (ip.isBlank() || ip.contains("/")) return null
        return try {
            val req = Request.Builder()
                .url("https://ipwho.is/$ip")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) return null
                val code = json.optString("country_code").uppercase(Locale.US)
                val name = json.optString("country")
                if (code.length == 2 && name.isNotBlank()) CountryInfo(code, name) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectCountryFromName(name: String): CountryInfo? {
        val text = name.trim()
        val flag = flagRegex.find(text)?.value
        if (flag != null) {
            val code = flagToCountryCode(flag)
            val countryName = text.substringAfter(flag).trim()
                .split("|")
                .firstOrNull()
                ?.trim()
                ?.replace(Regex("[^\\p{L} .'-]"), "")
                .orEmpty()
            if (code.isNotBlank()) {
                return CountryInfo(code, countryName.ifBlank { code })
            }
        }
        return null
    }

    private fun isCidrProxy(bean: AbstractBean): Boolean {
        val host = bean.serverAddress.orEmpty()
        val name = bean.name.orEmpty()
        return host.contains("/") || name.contains("cidr", ignoreCase = true)
    }

    private fun isIpAddress(value: String): Boolean {
        val ipv4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
        val ipv6 = Regex("^[0-9a-fA-F:]+$")
        return ipv4.matches(value) || ipv6.matches(value)
    }

    private fun countryCodeToFlag(countryCode: String): String {
        val code = countryCode.uppercase(Locale.US)
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "\uD83C\uDFF3️"
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun flagToCountryCode(flag: String): String {
        if (flag.length < 4) return "" // Флаг должен состоять как минимум из 4 char (2 суррогатные пары)

        // Извлекаем кодовые точки с учетом суррогатных пар для API 21+
        val firstCp = Character.codePointAt(flag, 0)
        val secondCp = Character.codePointAt(flag, 2)

        val first = firstCp - 0x1F1E6 + 'A'.code
        val second = secondCp - 0x1F1E6 + 'A'.code

        if (first !in 'A'.code..'Z'.code || second !in 'A'.code..'Z'.code) return ""

        return "${first.toChar()}${second.toChar()}"
    }
}