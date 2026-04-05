package io.nekohasekai.sagernet.utils

import android.net.Uri
import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import androidx.core.net.toUri

object GitHubExporter {

    private val client = OkHttpClient()
    data class ExportResult(val success: Boolean, val message: String)

    /**
     * Выполняет экспорт прокси на GitHub в ДВУХ форматах: .txt и .json (для Happ)
     */
    suspend fun exportGroup(
        groupName: String,
        proxies: List<ProxyEntity>
    ): ExportResult = withContext(Dispatchers.IO) {

        val token = DataStore.githubToken.trim()
        val repo = DataStore.githubRepo.trim()
        val path = DataStore.githubFilePath.trim() // например: proxies.txt

        if (token.isBlank() || repo.isBlank() || path.isBlank()) {
            return@withContext ExportResult(false, "Не указаны настройки GitHub")
        }

        try {
            // ========================================================
            // ШАГ 1: ГЕНЕРАЦИЯ И ОТПРАВКА ОБЫЧНОГО TXT ФАЙЛА (vless://)
            // ========================================================
            val txtResult = uploadTextFile(token, repo, path, groupName, proxies)
            if (!txtResult) return@withContext ExportResult(false, "Ошибка загрузки TXT файла")

            // ========================================================
            // ШАГ 2: ГЕНЕРАЦИЯ И ОТПРАВКА JSON ФАЙЛА ДЛЯ HAPP
            // ========================================================
            val jsonPath = if (path.endsWith(".txt")) path.replace(".txt", ".json") else "$path.json"
            val jsonArrayString = buildHappJsonArray(proxies)

            if (jsonArrayString.length > 10) {
                uploadDirectFile(token, repo, jsonPath, jsonArrayString, "Auto-update JSON Happ: $groupName")
            }

            return@withContext ExportResult(true, "Успешно выгружено в TXT и JSON форматы!")

        } catch (e: Exception) {
            return@withContext ExportResult(false, "Ошибка: ${e.message}")
        }
    }

    // =========================================================================
    // ГЕНЕРАТОР XRAY JSON ДЛЯ HAPP (Обход блокировок + Исключение Рунета)
    // =========================================================================
    private fun buildHappJsonArray(proxies: List<ProxyEntity>): String {
        val rootArray = JSONArray()

        for ((index, p) in proxies.withIndex()) {
            try {
                // Генерируем нормализованное красивое имя (🇷🇺 Россия | LTE #1)
                val niceName = p.toNormalizedStandardLink(index, updateBean = false)

                // Парсим стандартную ссылку vless://, чтобы достать все параметры без ошибок
                val link = p.toStdLink()
                val uri = link.toUri()
                val protocol = uri.scheme?.lowercase() ?: "vless"

                val node = JSONObject()
                node.put("log", JSONObject().put("loglevel", "warning"))
                node.put("remarks", niceName)

                // 1. INBOUNDS (Сниффинг обязателен для работы маршрутизации!)
                val inbounds = JSONArray()
                val sniff = JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls"))
                inbounds.put(JSONObject().put("port", 10808).put("protocol", "socks").put("settings", JSONObject().put("udp", true)).put("sniffing", sniff))
                inbounds.put(JSONObject().put("port", 10809).put("protocol", "http").put("sniffing", sniff))
                node.put("inbounds", inbounds)

                // 2. OUTBOUNDS
                val outbounds = JSONArray()
                val proxyOut = JSONObject()
                proxyOut.put("tag", "proxy")
                proxyOut.put("protocol", protocol)

                val settings = JSONObject()
                val streamSettings = JSONObject()

                val address = uri.host ?: ""
                val port = uri.port.takeIf { it > 0 } ?: 443
                val uuid = uri.userInfo ?: ""

                // Настройки сервера
                if (protocol == "vless" || protocol == "vmess") {
                    val vnext = JSONArray()
                    val server = JSONObject().put("address", address).put("port", port)
                    val user = JSONObject().put("id", uuid)
                    if (protocol == "vless") {
                        user.put("encryption", uri.getQueryParameter("encryption") ?: "none")
                        uri.getQueryParameter("flow")?.let { if (it.isNotBlank()) user.put("flow", it) }
                    } else {
                        user.put("alterId", uri.getQueryParameter("alterId")?.toIntOrNull() ?: 0)
                        user.put("security", uri.getQueryParameter("security") ?: "auto")
                    }
                    server.put("users", JSONArray().put(user))
                    vnext.put(server)
                    settings.put("vnext", vnext)
                }

                // Транспорт (TCP, WS, GRPC)
                val network = uri.getQueryParameter("type") ?: "tcp"
                streamSettings.put("network", network)

                if (network == "ws") {
                    val wsSettings = JSONObject()
                    uri.getQueryParameter("path")?.let { wsSettings.put("path", it) }
                    uri.getQueryParameter("host")?.let { wsSettings.put("headers", JSONObject().put("Host", it)) }
                    streamSettings.put("wsSettings", wsSettings)
                } else if (network == "grpc") {
                    val grpcSettings = JSONObject()
                    uri.getQueryParameter("serviceName")?.let { grpcSettings.put("serviceName", it) }
                    streamSettings.put("grpcSettings", grpcSettings)
                }

                // Security (TLS / Reality)
                val sec = uri.getQueryParameter("security")
                if (sec == "tls" || sec == "reality") {
                    streamSettings.put("security", sec)
                    val secObj = JSONObject()
                    uri.getQueryParameter("sni")?.let { secObj.put("serverName", it) }
                    uri.getQueryParameter("fp")?.let { secObj.put("fingerprint", it) }

                    if (sec == "reality") {
                        uri.getQueryParameter("pbk")?.let { secObj.put("publicKey", it) }
                        uri.getQueryParameter("sid")?.let { secObj.put("shortId", it) }
                        uri.getQueryParameter("spx")?.let { secObj.put("spiderX", it) }
                        streamSettings.put("realitySettings", secObj)
                    } else {
                        streamSettings.put("tlsSettings", secObj)
                    }
                }

                // ФРАГМЕНТАЦИЯ (Извлекаем из бина напрямую)
                try {
                    val bean = p.requireBean()
                    val fragEnabled = bean.javaClass.getMethod("getFragmentEnabled").invoke(bean) as? Boolean ?: false
                    if (fragEnabled) {
                        val packets = bean.javaClass.getMethod("getFragmentPackets").invoke(bean) as? String ?: "1-3"
                        val length = bean.javaClass.getMethod("getFragmentLength").invoke(bean) as? String ?: "50-100"
                        val interval = bean.javaClass.getMethod("getFragmentInterval").invoke(bean) as? String ?: "10-20"

                        proxyOut.put("fragment", JSONObject()
                            .put("packets", packets)
                            .put("length", length)
                            .put("interval", interval))
                    }
                } catch (_: Exception) {}

                proxyOut.put("settings", settings)
                proxyOut.put("streamSettings", streamSettings)

                outbounds.put(proxyOut)
                outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                node.put("outbounds", outbounds)

                // 3. ЖЕСТКАЯ МАРШРУТИЗАЦИЯ ДЛЯ РОССИИ (В обход VPN)
                val routing = JSONObject()
                routing.put("domainStrategy", "IPIfNonMatch")
                val rules = JSONArray()

                // Торренты - мимо VPN
                rules.put(JSONObject().put("type", "field").put("protocol", JSONArray().put("bittorrent")).put("outboundTag", "direct"))

                // Российские сайты, домены, ВК, Яндекс, Банки - мимо VPN
                val directDomains = JSONArray()
                    .put("geosite:ru").put("geosite:yandex").put("geosite:vk").put("geosite:mailru")
                    .put("domain:ru").put("domain:su").put("domain:xn--p1ai").put("domain:avito.st")
                    .put("keyword:yandex").put("keyword:vk").put("keyword:rutube").put("keyword:sber")
                    .put("keyword:tinkoff").put("keyword:alfabank").put("keyword:vtb").put("keyword:gosuslugi")
                rules.put(JSONObject().put("type", "field").put("domain", directDomains).put("outboundTag", "direct"))

                // Российские IP-адреса и локальная сеть - мимо VPN
                rules.put(JSONObject().put("type", "field").put("ip", JSONArray().put("geoip:ru").put("geoip:private")).put("outboundTag", "direct"))

                // Реклама - блокировать
                rules.put(JSONObject().put("type", "field").put("domain", JSONArray().put("geosite:category-ads-all")).put("outboundTag", "block"))

                routing.put("rules", rules)
                node.put("routing", routing)

                rootArray.put(node)
            } catch (e: Exception) {
                // Игнорируем ошибки при сборке конкретного прокси
            }
        }
        // Возвращаем красивый JSON (с отступами)
        return rootArray.toString(2)
    }

    // =========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ЗАГРУЗКИ НА GITHUB
    // =========================================================================

    private fun uploadTextFile(token: String, repo: String, path: String, groupName: String, proxies: List<ProxyEntity>): Boolean {
        val apiUrl = "https://api.github.com/repos/$repo/contents/$path"
        try {
            val getReq = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $token").get().build()
            var currentText = ""
            var fileSha = ""

            client.newCall(getReq).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body!!.string())
                    fileSha = json.optString("sha", "")
                    val base64Content = json.optString("content", "").replace("\n", "")
                    if (base64Content.isNotEmpty()) currentText = String(Base64.decode(base64Content, Base64.DEFAULT))
                }
            }

            val newGroupBlock = buildString {
                appendLine("# === BEGIN $groupName ===")
                for ((index, p) in proxies.withIndex()) {
                    try {
                        p.toNormalizedStandardLink(index, updateBean = true)
                        appendLine(p.toStdLink())
                    } catch (_: Exception) {}
                }
                append("# === END $groupName ===")
            }

            var updatedText = currentText
            if (updatedText.isEmpty()) updatedText = newGroupBlock
            else {
                val regex = Regex("# === BEGIN $groupName ===.*?# === END $groupName ===", RegexOption.DOT_MATCHES_ALL)
                if (updatedText.contains(regex)) updatedText = updatedText.replace(regex, newGroupBlock)
                else updatedText = updatedText.trimEnd() + "\n\n" + newGroupBlock
            }

            return uploadDirectFile(token, repo, path, updatedText, "Auto-update TXT: $groupName", fileSha)
        } catch (e: Exception) {
            return false
        }
    }

    private fun uploadDirectFile(token: String, repo: String, path: String, content: String, message: String, existingSha: String = ""): Boolean {
        val apiUrl = "https://api.github.com/repos/$repo/contents/$path"
        try {
            var finalSha = existingSha
            if (finalSha.isEmpty()) {
                val getReq = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $token").get().build()
                client.newCall(getReq).execute().use { response ->
                    if (response.isSuccessful) finalSha = JSONObject(response.body!!.string()).optString("sha", "")
                }
            }

            val putBody = JSONObject().apply {
                put("message", message)
                put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
                if (finalSha.isNotEmpty()) put("sha", finalSha)
            }

            val putReq = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $token")
                .put(putBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            return client.newCall(putReq).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Создает стандартизированное красивое имя и применяет его
     */
    private fun ProxyEntity.toNormalizedStandardLink(index: Int, updateBean: Boolean): String {
        val bean = requireBean()
        val originalName = bean.name ?: "Unknown"
        val upperName = originalName.uppercase()

        var flag = "🌍"
        var countryName = "Локация"

        val flagRegex = Regex("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")
        val flagMatch = flagRegex.find(originalName)

        if (flagMatch != null) {
            flag = flagMatch.value
            try {
                val c1 = flag.codePointAt(0) - 0x1F1E6 + 'A'.code
                val c2 = flag.codePointAt(2) - 0x1F1E6 + 'A'.code
                countryName = java.util.Locale("ru", "${c1.toChar()}${c2.toChar()}").displayCountry
            } catch (_: Exception) {}
        } else {
            when {
                upperName.contains("RUSSIA") || upperName.contains(" RU ") || upperName.contains("-RU") -> { flag = "🇷🇺"; countryName = "Россия" }
                upperName.contains("GERMANY") || upperName.contains(" DE ") || upperName.contains("-DE") -> { flag = "🇩🇪"; countryName = "Германия" }
                upperName.contains("FINLAND") || upperName.contains(" FI ") || upperName.contains("-FI") -> { flag = "🇫🇮"; countryName = "Финляндия" }
                upperName.contains("UNITED STATES") || upperName.contains(" US ") || upperName.contains("-US") -> { flag = "🇺🇸"; countryName = "США" }
                upperName.contains("NETHERLANDS") || upperName.contains(" NL ") || upperName.contains("-NL") -> { flag = "🇳🇱"; countryName = "Нидерланды" }
                upperName.contains("FRANCE") || upperName.contains(" FR ") || upperName.contains("-FR") -> { flag = "🇫🇷"; countryName = "Франция" }
                upperName.contains("KINGDOM") || upperName.contains(" UK ") || upperName.contains(" GB ") -> { flag = "🇬🇧"; countryName = "Великобритания" }
                upperName.contains("TURKEY") || upperName.contains(" TR ") || upperName.contains("-TR") -> { flag = "🇹🇷"; countryName = "Турция" }
                upperName.contains("POLAND") || upperName.contains(" PL ") || upperName.contains("-PL") -> { flag = "🇵🇱"; countryName = "Польша" }
                upperName.contains("SWEDEN") || upperName.contains(" SE ") || upperName.contains("-SE") -> { flag = "🇸🇪"; countryName = "Швеция" }
                else -> {
                    try {
                        val ipCode = moe.matsuri.nb4a.utils.GeoIPHelper.detectCountryByIpOffline(bean.serverAddress ?: "")
                        if (!ipCode.isNullOrBlank() && ipCode.length == 2 && ipCode != "XX") {
                            val c1 = ipCode[0].uppercaseChar().code - 'A'.code + 0x1F1E6
                            val c2 = ipCode[1].uppercaseChar().code - 'A'.code + 0x1F1E6
                            flag = String(Character.toChars(c1)) + String(Character.toChars(c2))
                            countryName = java.util.Locale("ru", ipCode).displayCountry
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (countryName.isBlank() || countryName == "Unknown") countryName = "Локация"
        val isLte = bean.serverAddress?.contains("/") == true || upperName.contains("CIDR") || upperName.contains("LTE")
        val lteSuffix = if (isLte) " | LTE" else ""

        val newName = "$flag $countryName$lteSuffix #${index + 1}"
        if (updateBean) bean.name = newName

        return newName
    }
}