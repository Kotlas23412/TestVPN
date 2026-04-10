package io.nekohasekai.sagernet.utils

import android.net.Uri
import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.net.toUri

object GitHubExporter {

    private val client = OkHttpClient()
    private val geoCache = mutableMapOf<String, Pair<String, String>>()

    data class ExportResult(val success: Boolean, val message: String)

    suspend fun exportGroup(
        groupName: String,
        proxies: List<ProxyEntity>
    ): ExportResult = withContext(Dispatchers.IO) {

        val token = DataStore.githubToken.trim()
        val repo = DataStore.githubRepo.trim()
        val path = DataStore.githubFilePath.trim()

        if (token.isBlank() || repo.isBlank() || path.isBlank()) {
            return@withContext ExportResult(false, "Не указаны настройки GitHub")
        }

        try {
            val txtResult = uploadTextFile(token, repo, path, groupName, proxies)
            if (!txtResult) return@withContext ExportResult(false, "Ошибка загрузки TXT файла")

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

    private fun buildHappJsonArray(proxies: List<ProxyEntity>): String {
        val rootArray = JSONArray()

        for ((index, p) in proxies.withIndex()) {
            try {
                val niceName = p.toNormalizedStandardLink(index, updateBean = false)
                val link = p.toStdLink()
                val uri = link.toUri()
                val protocol = uri.scheme?.lowercase() ?: "vless"

                val node = JSONObject()
                node.put("log", JSONObject().put("loglevel", "warning"))
                node.put("remarks", niceName)

                val inbounds = JSONArray()
                val sniff = JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls"))
                inbounds.put(JSONObject().put("port", 10808).put("protocol", "socks").put("settings", JSONObject().put("udp", true)).put("sniffing", sniff))
                inbounds.put(JSONObject().put("port", 10809).put("protocol", "http").put("sniffing", sniff))
                node.put("inbounds", inbounds)

                val outbounds = JSONArray()
                val proxyOut = JSONObject()
                proxyOut.put("tag", "proxy")
                proxyOut.put("protocol", protocol)

                val settings = JSONObject()
                val streamSettings = JSONObject()

                var address = uri.host ?: ""
                var port = uri.port.takeIf { it > 0 } ?: 443
                var uuid = uri.userInfo ?: ""
                var network = uri.getQueryParameter("type") ?: "tcp"
                var security = uri.getQueryParameter("security") ?: "none"
                var path = uri.getQueryParameter("path") ?: ""
                var host = uri.getQueryParameter("host") ?: ""
                var sni = uri.getQueryParameter("sni") ?: ""
                var fp = uri.getQueryParameter("fp") ?: ""

                var pluginStr: String? = uri.getQueryParameter("plugin")
                try {
                    val bean = p.requireBean()
                    val beanPlugin = bean.javaClass.getMethod("getPlugin").invoke(bean) as? String
                    if (!beanPlugin.isNullOrBlank()) pluginStr = beanPlugin
                } catch (e: Exception) {}

                if (!pluginStr.isNullOrBlank()) {
                    if (pluginStr.contains("mode=websocket") || pluginStr.contains("obfs=ws")) network = "ws"
                    if (pluginStr.contains("tls")) security = "tls"

                    val parts = pluginStr.split(";")
                    for (part in parts) {
                        if (part.startsWith("host=")) {
                            host = part.substring(5)
                            sni = host
                        }
                        if (part.startsWith("path=")) {
                            path = part.substring(5)
                        }
                    }
                }

                if (protocol == "vmess") {
                    try {
                        val base64Str = link.substringAfter("vmess://").substringBefore("#")
                        val vmessJson = JSONObject(String(Base64.decode(base64Str, Base64.DEFAULT)))
                        address = vmessJson.optString("add", address)
                        port = vmessJson.optInt("port", port)
                        uuid = vmessJson.optString("id", uuid)
                        network = vmessJson.optString("net", "tcp")
                        if (network == "h2") network = "http"
                        security = if (vmessJson.optString("tls") == "tls") "tls" else "none"
                        path = vmessJson.optString("path", path)
                        host = vmessJson.optString("host", host)
                        sni = vmessJson.optString("sni", sni.ifBlank { host })
                    } catch (e: Exception) {}
                }

                if (protocol == "vless" || protocol == "vmess") {
                    val vnext = JSONArray()
                    val server = JSONObject().put("address", address).put("port", port)
                    val user = JSONObject().put("id", uuid)
                    if (protocol == "vless") {
                        user.put("encryption", uri.getQueryParameter("encryption") ?: "none")
                        uri.getQueryParameter("flow")?.let { if (it.isNotBlank()) user.put("flow", it) }
                    } else {
                        val aid = try { JSONObject(String(Base64.decode(link.substringAfter("vmess://").substringBefore("#"), Base64.DEFAULT))).optInt("aid", 0) } catch(e:Exception){ 0 }
                        user.put("alterId", aid)
                        user.put("security", "auto")
                    }
                    server.put("users", JSONArray().put(user))
                    vnext.put(server)
                    settings.put("vnext", vnext)

                } else if (protocol == "ss") {
                    var methodAndPass = uri.userInfo ?: ""
                    if (!methodAndPass.contains(":") && methodAndPass.isNotBlank()) {
                        try { methodAndPass = String(Base64.decode(methodAndPass, Base64.DEFAULT)) } catch (e: Exception) {}
                    }
                    var method = methodAndPass.substringBefore(":")
                    val password = methodAndPass.substringAfter(":")

                    if (method.isBlank()) method = "none"

                    val server = JSONObject().put("address", address).put("port", port).put("method", method).put("password", password)
                    settings.put("servers", JSONArray().put(server))

                } else if (protocol == "trojan") {
                    val server = JSONObject().put("address", address).put("port", port).put("password", uuid)
                    settings.put("servers", JSONArray().put(server))
                }

                streamSettings.put("network", network)

                if (network == "ws") {
                    val wsSettings = JSONObject()
                    if (path.isNotBlank()) wsSettings.put("path", path)
                    if (host.isNotBlank()) wsSettings.put("headers", JSONObject().put("Host", host))
                    streamSettings.put("wsSettings", wsSettings)
                } else if (network == "grpc") {
                    val grpcSettings = JSONObject()
                    uri.getQueryParameter("serviceName")?.let { grpcSettings.put("serviceName", it) }
                    streamSettings.put("grpcSettings", grpcSettings)
                }

                if (security == "tls" || security == "reality") {
                    streamSettings.put("security", security)
                    val secObj = JSONObject()
                    if (sni.isNotBlank()) secObj.put("serverName", sni)
                    if (fp.isNotBlank()) secObj.put("fingerprint", fp)

                    if (security == "reality") {
                        uri.getQueryParameter("pbk")?.let { secObj.put("publicKey", it) }
                        uri.getQueryParameter("sid")?.let { secObj.put("shortId", it) }
                        uri.getQueryParameter("spx")?.let { secObj.put("spiderX", it) }
                        streamSettings.put("realitySettings", secObj)
                    } else {
                        streamSettings.put("tlsSettings", secObj)
                    }
                }

                try {
                    val bean = p.requireBean()
                    val fragEnabled = bean.javaClass.getMethod("getFragmentEnabled").invoke(bean) as? Boolean ?: false
                    if (fragEnabled) {
                        val packets = bean.javaClass.getMethod("getFragmentPackets").invoke(bean) as? String ?: "1-3"
                        val length = bean.javaClass.getMethod("getFragmentLength").invoke(bean) as? String ?: "50-100"
                        val interval = bean.javaClass.getMethod("getFragmentInterval").invoke(bean) as? String ?: "10-20"
                        proxyOut.put("fragment", JSONObject().put("packets", packets).put("length", length).put("interval", interval))
                    }
                } catch (_: Exception) {}

                proxyOut.put("settings", settings)
                proxyOut.put("streamSettings", streamSettings)

                outbounds.put(proxyOut)
                outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                node.put("outbounds", outbounds)

                val routing = JSONObject().put("domainStrategy", "IPIfNonMatch")
                val rules = JSONArray()
                rules.put(JSONObject().put("type", "field").put("protocol", JSONArray().put("bittorrent")).put("outboundTag", "direct"))
                routing.put("rules", rules)
                node.put("routing", routing)

                rootArray.put(node)
            } catch (e: Exception) {}
        }
        return rootArray.toString(2)
    }

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
                updatedText = if (updatedText.contains(regex)) updatedText.replace(regex, newGroupBlock) else updatedText.trimEnd() + "\n\n" + newGroupBlock
            }
            return uploadDirectFile(token, repo, path, updatedText, "Auto-update TXT: $groupName", fileSha)
        } catch (e: Exception) { return false }
    }

    private fun uploadDirectFile(token: String, repo: String, path: String, content: String, message: String, existingSha: String = ""): Boolean {
        val apiUrl = "https://api.github.com/repos/$repo/contents/$path"
        try {
            var finalSha = existingSha
            if (finalSha.isEmpty()) {
                client.newCall(Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $token").get().build()).execute().use {
                    if (it.isSuccessful) finalSha = JSONObject(it.body!!.string()).optString("sha", "")
                }
            }
            val putBody = JSONObject().put("message", message).put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            if (finalSha.isNotEmpty()) putBody.put("sha", finalSha)
            return client.newCall(Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $token").put(putBody.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) { return false }
    }

    private fun ProxyEntity.toNormalizedStandardLink(index: Int, updateBean: Boolean): String {
        val bean = requireBean()
        val originalName = (bean.name ?: "").ifBlank { displayName() }.ifBlank { "Unknown" }
        val upperName = originalName.uppercase()

        val address = bean.serverAddress?.trim()?.lowercase() ?: ""

        val link = try { toStdLink() } catch (_: Exception) { "" }
        val uri = try { link.toUri() } catch (_: Exception) { null }
        val sni = uri?.getQueryParameter("sni")?.lowercase() ?: ""

        var flag = ""
        var countryName = ""
        var resolved = false

        val isCorruptedName = upperName.contains("ЛОКАЦИЯ") || upperName.contains("GLOBAL") || upperName.contains("УЗЕЛ")

        if (!isCorruptedName) {
            val explicitRegex = Regex("([\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF])\\s*([A-Za-zА-Яа-яЁё\\-\\s]+?)(?:\\s*\\[|\\s*\\||\\s*#|\\s*\\*|$)")
            val match = explicitRegex.find(originalName.trim())

            if (match != null) {
                val foundFlag = match.groupValues[1]
                val rawName = match.groupValues[2].trim()

                if (rawName.isNotBlank() && !rawName.uppercase().contains("ANYCAST")) {
                    flag = foundFlag
                    countryName = when (rawName.uppercase()) {
                        "FRANCE" -> "Франция"
                        "GERMANY" -> "Германия"
                        "NORWAY" -> "Норвегия"
                        "THE NETHERLANDS", "NETHERLANDS" -> "Нидерланды"
                        "RUSSIA", "РОССИЯ" -> "Россия"
                        "UNITED STATES", "USA" -> "США"
                        "UNITED KINGDOM", "UK" -> "Великобритания"
                        "FINLAND" -> "Финляндия"
                        "SWEDEN" -> "Швеция"
                        "POLAND" -> "Польша"
                        "TURKEY" -> "Турция"
                        "HUNGARY" -> "Венгрия"
                        "ANDORRA" -> "Андорра"
                        else -> rawName
                    }
                    resolved = true
                }
            }
        }

        if (!resolved && address.isNotBlank()) {
            try {
                var ipToDetect = address
                if (!ipToDetect.matches(Regex("^[0-9a-fA-F\\.:]+$"))) {
                    try { ipToDetect = java.net.InetAddress.getByName(ipToDetect).hostAddress ?: address } catch (_: Exception) {}
                }

                val ipCode = moe.matsuri.nb4a.utils.GeoIPHelper.detectCountryByIpOffline(ipToDetect)
                if (!ipCode.isNullOrBlank() && ipCode.length == 2 && ipCode != "XX" && ipCode != "UN" && ipCode != "ZZ") {
                    val c1 = ipCode[0].uppercaseChar().code - 'A'.code + 0x1F1E6
                    val c2 = ipCode[1].uppercaseChar().code - 'A'.code + 0x1F1E6
                    flag = String(Character.toChars(c1)) + String(Character.toChars(c2))
                    countryName = java.util.Locale("ru", ipCode).displayCountry
                    resolved = true
                }
            } catch (_: Exception) {}
        }

        if (!resolved && address.isNotBlank()) {
            try {
                if (geoCache.containsKey(address)) {
                    val cached = geoCache[address]!!
                    flag = cached.first
                    countryName = cached.second
                    resolved = true
                } else {
                    val request = Request.Builder()
                        .url("http://ip-api.com/json/$address?fields=status,country,countryCode&lang=ru")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body!!.string())
                            if (json.optString("status") == "success") {
                                val country = json.optString("country", "")
                                val countryCode = json.optString("countryCode", "")
                                if (countryCode.isNotBlank() && country.isNotBlank()) {
                                    val c1 = countryCode[0].uppercaseChar().code - 'A'.code + 0x1F1E6
                                    val c2 = countryCode[1].uppercaseChar().code - 'A'.code + 0x1F1E6
                                    flag = String(Character.toChars(c1)) + String(Character.toChars(c2))
                                    countryName = country
                                    resolved = true
                                    geoCache[address] = Pair(flag, countryName)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (!resolved && sni.isNotBlank()) {
            when {
                sni.endsWith(".ru") || sni.endsWith(".su") || sni.endsWith(".рф") || sni.contains("yandex") || sni.contains("vk.com") || sni.contains("x5.ru") -> { flag = "🇷🇺"; countryName = "Россия"; resolved = true }
                sni.endsWith(".de") -> { flag = "🇩🇪"; countryName = "Германия"; resolved = true }
                sni.endsWith(".nl") -> { flag = "🇳🇱"; countryName = "Нидерланды"; resolved = true }
                sni.endsWith(".fi") -> { flag = "🇫🇮"; countryName = "Финляндия"; resolved = true }
                sni.endsWith(".us") -> { flag = "🇺🇸"; countryName = "США"; resolved = true }
                sni.endsWith(".uk") || sni.endsWith(".co.uk") -> { flag = "🇬🇧"; countryName = "Великобритания"; resolved = true }
                sni.endsWith(".fr") -> { flag = "🇫🇷"; countryName = "Франция"; resolved = true }
                sni.endsWith(".se") -> { flag = "🇸🇪"; countryName = "Швеция"; resolved = true }
            }
        }

        if (!resolved || countryName.isBlank()) {
            flag = "🌐"
            countryName = if (address.isNotBlank()) {
                "Узел $address"
            } else if (sni.isNotBlank()) {
                "SNI $sni"
            } else {
                "Global"
            }
        }

        val isLte = address.contains("/") || upperName.contains("CIDR") || upperName.contains("LTE")
        val lteSuffix = if (isLte) " | LTE" else ""

        val newName = "$flag $countryName$lteSuffix #${index + 1}"
        if (updateBean) bean.name = newName

        return newName
    }
}