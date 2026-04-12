package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.os.SystemClock
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.utils.GitHubExporter
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class AutoPilotConfig(
    val groupIds: List<Long>,
    val exportLimit: Int = 10,
    val maxLatencyMs: Long = 3000,
    val testUrl: String = DataStore.connectionTestURL,
    val allowedProtocols: Set<String> = setOf("all"),
    val intervalMinutes: Int = 60,
    val healthCheckMinutes: Int = 10,
    val deadThreshold: Int = 50,
    val combineExport: Boolean = true,
    val strictWhitelistMode: Boolean = false
)

data class AutoPilotProgress(
    val phase: String,
    val current: Int,
    val total: Int,
    val currentProxy: String? = null,
    val goodCount: Int = 0,
    val detail: String? = null
)

data class AutoPilotResult(
    val success: Boolean,
    val message: String,
    val exportedCount: Int = 0,
    val deadCount: Int = 0,
    val exportedIds: List<Long> = emptyList(),
    val isSkipped: Boolean = false,
    val aliveCount: Int = 0
)

class AutoPilotEngine(
    private val config: AutoPilotConfig,
    private val onProgress: suspend (AutoPilotProgress) -> Unit = {},
    private val shouldStop: () -> Boolean = { false }
) {
    private fun toProtocolKey(profile: ProxyEntity): String {
        return when (profile.type) {
            ProxyEntity.TYPE_SS -> "ss"
            ProxyEntity.TYPE_TROJAN, ProxyEntity.TYPE_TROJAN_GO -> "trojan"
            ProxyEntity.TYPE_VMESS -> if (profile.vmessBean?.isVLESS == true) "vless" else "vmess"
            else -> "other"
        }
    }

    private fun isProtocolAllowed(profile: ProxyEntity): Boolean {
        val allowed = config.allowedProtocols
        if (allowed.isEmpty() || allowed.contains("all")) return true
        return allowed.contains(toProtocolKey(profile))
    }

    private val whitelistDomains = listOf(
        "gov.ru", "kremlin.ru", "gosuslugi.ru", "gu-st.ru", "nalog.ru", "mos.ru", "pfrf.ru",
        "cikrf.ru", "izbirkom.ru", "xn--p1ai", "xn--80ajghhoc2aj1c8b.xn--p1ai", "res-nsdi.ru", "auth-nsdi.ru",
        "sbrf.ru", "sberbank.ru", "sber.ru", "vtb.ru", "tinkoff.ru", "tbank.ru", "alfabank.ru",
        "vk.com", "vk.ru", "vk-portal.net", "userapi.com", "cdn-vk.ru", "ok.ru", "okcdn.ru",
        "yandex.ru", "yandex.net", "yandex.com", "ya.ru", "dzen.ru", "yastatic.net", "auto.ru",
        "mail.ru", "mradx.net", "kinopoisk.ru",
        "x5.ru", "ozon.ru", "ozone.ru", "wildberries.ru", "wb.ru", "magnit.ru",
        "avito.ru", "avito.st", "lemanapro.ru", "lmru.tech",
        "rt.ru", "pochta.ru", "rzd.ru", "tutu.ru", "taximaxim.ru", "2gis.ru", "2gis.com", "t2.ru",
        "rutube.ru", "rutubelist.ru", "rambler.ru", "lenta.ru", "gazeta.ru", "rbc.ru", "kp.ru", "max.ru"
    )

    private fun isShutdownProof(profile: ProxyEntity): Boolean {
        return try {
            val bean = profile.requireBean()
            val link1 = try { profile.toStdLink().lowercase() } catch (e: Exception) { "" }
            val link2 = try { bean.toUniversalLink().lowercase() } catch (e: Exception) { "" }
            val name = profile.displayName().lowercase()
            val address = profile.displayAddress().lowercase()
            val allText = "$link1 | $link2 | $name | $address"

            whitelistDomains.any { domain -> allText.contains(domain) }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun runFullCycle(): AutoPilotResult = withContext(Dispatchers.Default) {
        val originalProxy = DataStore.selectedProxy
        val wasRunning = DataStore.serviceState.started
        val keepVpn = DataStore.autoPilotKeepVpn

        // Если пользователь разрешил — НЕ трогаем VPN
        if (wasRunning && !keepVpn) {
            SagerNet.stopService()
            waitForState(BaseService.State.Stopped, 10000)
            delay(1500)
        }

        try {
            val groupName = "🚀 AutoPilot Best"
            val targetGroup = SagerDatabase.groupDao.allGroups().find { it.name == groupName || it.displayName() == groupName }
            val survivingProxies = mutableListOf<ProxyEntity>()
            var deadCount = 0

            // === 1. ЭТАП РЕВИЗИИ ===
            if (targetGroup != null) {
                val currentBest = SagerDatabase.proxyDao.getByGroup(targetGroup.id)
                val currentBestFiltered = currentBest.filter { isProtocolAllowed(it) }
                if (currentBestFiltered.isNotEmpty()) {
                    onProgress(AutoPilotProgress("РЕВИЗИЯ", 0, currentBestFiltered.size, "Строгий тест серверов..."))

                    val verifiedOld = phaseStrictCoreTest(currentBestFiltered, currentBestFiltered.size, "РЕВИЗИЯ")
                    survivingProxies.addAll(verifiedOld)

                    val deadProxies = currentBestFiltered.filter { old -> verifiedOld.none { it.id == old.id } }
                    deadCount = deadProxies.size

                    if (deadProxies.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            for (dead in deadProxies) {
                                if (DataStore.selectedProxy == dead.id) DataStore.selectedProxy = 0L
                                ProfileManager.deleteProfile(targetGroup.id, dead.id)
                            }
                        }
                        withContext(Dispatchers.Main) { GroupManager.postReload(targetGroup.id) }
                    }
                }
            }

            val survivingSignatures = survivingProxies.map {
                try { "${it.requireBean().serverAddress}:${it.requireBean().serverPort}" } catch (e: Exception) { it.id.toString() }
            }.toSet()

            // === ЕСЛИ КОНФИГ В НОРМЕ ===
            if (survivingProxies.size >= config.exportLimit) {
                if (deadCount == 0) {
                    return@withContext AutoPilotResult(
                        success = true,
                        message = "Идеальное состояние.\nЖиво: ${survivingProxies.size} | Умерло: 0",
                        exportedCount = survivingProxies.size,
                        deadCount = 0,
                        exportedIds = survivingProxies.map { it.id },
                        isSkipped = true,
                        aliveCount = survivingProxies.size
                    )
                } else {
                    val bestToExport = survivingProxies.take(config.exportLimit)
                    val exportResult = withTimeoutOrNull(30_000L) { doExport(bestToExport) } ?: GitHubExporter.ExportResult(false, "Таймаут GitHub")
                    return@withContext AutoPilotResult(
                        success = exportResult.success,
                        message = "Удален мусор.\nЖиво: ${bestToExport.size} | Умерло: $deadCount",
                        exportedCount = bestToExport.size,
                        deadCount = deadCount,
                        exportedIds = bestToExport.map { it.id },
                        aliveCount = bestToExport.size
                    )
                }
            }

            // === 2. ЭТАП ДОБОРА ===
            val needCount = config.exportLimit - survivingProxies.size
            onProgress(AutoPilotProgress("ОБНОВЛЕНИЕ БАЗЫ", 0, config.groupIds.size, "Поиск новых серверов..."))

            var updatedCount = 0
            for (gid in config.groupIds) {
                if (shouldStop()) break
                val group = SagerDatabase.groupDao.getById(gid)
                if (group != null && group.type == GroupType.SUBSCRIPTION) {
                    try { GroupUpdater.startUpdate(group, false) } catch (_: Exception) {}
                }
                updatedCount++
                onProgress(AutoPilotProgress("ОБНОВЛЕНИЕ БАЗЫ", updatedCount, config.groupIds.size))
            }
            delay(2000)

            val allProxies = mutableListOf<ProxyEntity>()
            for (gid in config.groupIds) {
                if (shouldStop()) break
                allProxies.addAll(SagerDatabase.proxyDao.getByGroup(gid))
            }
            if (allProxies.isEmpty() && survivingProxies.isEmpty()) return@withContext AutoPilotResult(false, "Нет прокси в группах", deadCount = deadCount)

            var uniqueProxies = allProxies.distinctBy {
                try { "${it.requireBean().serverAddress}:${it.requireBean().serverPort}" }
                catch (e: Exception) { it.id.toString() }
            }.filter { isProtocolAllowed(it) }
                .filter {
                    val sig = try { "${it.requireBean().serverAddress}:${it.requireBean().serverPort}" } catch (e: Exception) { it.id.toString() }
                    !survivingSignatures.contains(sig)
                }

            if (uniqueProxies.isEmpty() && survivingProxies.isNotEmpty()) {
                return@withContext AutoPilotResult(
                    success = true,
                    message = "Новых серверов по выбранным протоколам не найдено.\nСохранено рабочих: ${survivingProxies.size}",
                    exportedCount = survivingProxies.size,
                    deadCount = deadCount,
                    exportedIds = survivingProxies.map { it.id },
                    isSkipped = true,
                    aliveCount = survivingProxies.size
                )
            }

            if (config.strictWhitelistMode) {
                onProgress(AutoPilotProgress("ФИЛЬТР", 0, uniqueProxies.size, "Ищем прокси..."))
                uniqueProxies = uniqueProxies.filter { isShutdownProof(it) }
            }

            onProgress(AutoPilotProgress("БЫСТРЫЙ ТЕСТ", 0, uniqueProxies.size))
            val fastAlive = phaseFastUrlTest(uniqueProxies)
            val sorted = fastAlive.sortedBy { it.ping }

            val maxToTest = (needCount * 3).coerceAtMost(sorted.size)
            val vpnVerified = if (maxToTest > 0) {
                onProgress(AutoPilotProgress("ФИНАЛЬНЫЙ ТЕСТ", 0, maxToTest))
                phaseStrictCoreTest(sorted, maxToTest, "ФИНАЛЬНЫЙ ТЕСТ")
            } else emptyList()

            // === 3. ОБЪЕДИНЕНИЕ И ВЫГРУЗКА ===
            val combinedBest = (survivingProxies + vpnVerified)
                .distinctBy { try { "${it.requireBean().serverAddress}:${it.requireBean().serverPort}" } catch (e: Exception) { it.id.toString() } }
                .sortedBy { it.ping }
                .take(config.exportLimit)

            if (combinedBest.isEmpty()) return@withContext AutoPilotResult(false, "Нет рабочих серверов", deadCount = deadCount)

            onProgress(AutoPilotProgress("ВЫГРУЗКА", 0, 1, goodCount = combinedBest.size))

            withTimeoutOrNull(10_000L) { saveToLocalAppGroupSmart(combinedBest) }
            val exportResult = withTimeoutOrNull(30_000L) { doExport(combinedBest) } ?: GitHubExporter.ExportResult(false, "Таймаут GitHub")

            val finalBestProxy = combinedBest.firstOrNull()
            if (finalBestProxy != null) {
                DataStore.selectedProxy = finalBestProxy.id
                ProfileManager.postUpdate(finalBestProxy.id)
            }

            return@withContext AutoPilotResult(
                success = exportResult.success,
                message = if (shouldStop()) {
                    "Остановлено пользователем.\nСохранено: ${combinedBest.size} | Умерло: $deadCount"
                } else {
                    "Успешно обновлено.\nЖиво: ${combinedBest.size} | Умерло: $deadCount"
                },
                exportedCount = combinedBest.size,
                deadCount = deadCount,
                exportedIds = combinedBest.map { it.id },
                aliveCount = combinedBest.size
            )

        } catch (e: Exception) {
            Logs.e("AutoPilot fatal", e)
            return@withContext AutoPilotResult(false, "Ошибка: ${e.readableMessage}")
        } finally {
            delay(3000)

            if (DataStore.selectedProxy == 0L) {
                DataStore.selectedProxy = originalProxy
                ProfileManager.postUpdate(originalProxy)
            }
            // Включаем VPN обратно ТОЛЬКО если мы его выключали
            if (wasRunning && !keepVpn) SagerNet.startService()
            for (gid in config.groupIds) { GroupManager.postReload(gid) }
        }
    }

    private suspend fun saveToLocalAppGroupSmart(newBestProxies: List<ProxyEntity>): String {
        return withContext(Dispatchers.IO) {
            try {
                val groupName = "🚀 AutoPilot Best"
                val allGroups = SagerDatabase.groupDao.allGroups()

                var targetGroup = allGroups.find { it.name == groupName || it.displayName() == groupName }
                if (targetGroup == null) {
                    val newGroup = ProxyGroup()
                    newGroup.name = groupName
                    newGroup.type = GroupType.BASIC
                    val newId = SagerDatabase.groupDao.createGroup(newGroup)
                    targetGroup = SagerDatabase.groupDao.getById(newId)
                }

                if (targetGroup == null) return@withContext "Ошибка группы"

                val groupId = targetGroup.id
                val oldProxies = SagerDatabase.proxyDao.getByGroup(groupId)

                fun getSignature(p: ProxyEntity): String {
                    return try { "${p.requireBean().serverAddress}:${p.requireBean().serverPort}" }
                    catch (e: Exception) { p.id.toString() }
                }

                val oldMap = oldProxies.associateBy { getSignature(it) }
                val toDelete = mutableListOf<ProxyEntity>()
                val toInsert = mutableListOf<ProxyEntity>()
                val toUpdate = mutableListOf<ProxyEntity>()

                val newMap = newBestProxies.associateBy { getSignature(it) }
                for ((sig, oldProxy) in oldMap) {
                    if (!newMap.containsKey(sig)) toDelete.add(oldProxy)
                }

                for ((index, newProxy) in newBestProxies.withIndex()) {
                    val sig = getSignature(newProxy)
                    val rankOrder = index.toLong()

                    if (!oldMap.containsKey(sig)) {
                        newProxy.userOrder = rankOrder
                        toInsert.add(newProxy)
                    } else {
                        val existing = oldMap[sig]!!
                        existing.ping = newProxy.ping
                        existing.status = newProxy.status
                        existing.userOrder = rankOrder
                        toUpdate.add(existing)
                    }
                }

                withContext(Dispatchers.IO) {
                    for (p in toDelete) {
                        if (DataStore.selectedProxy == p.id) DataStore.selectedProxy = 0L
                        ProfileManager.deleteProfile(groupId, p.id)
                    }
                }

                withContext(Dispatchers.Main) {
                    for (p in toInsert) {
                        try {
                            val createdProfile = ProfileManager.createProfile(groupId, p.requireBean())
                            createdProfile.userOrder = p.userOrder
                            createdProfile.ping = p.ping
                            createdProfile.status = p.status
                            ProfileManager.updateProfile(createdProfile)
                        } catch (e: Exception) {
                            Logs.e("AutoPilot: Ошибка", e)
                        }
                    }
                    for (p in toUpdate) {
                        ProfileManager.updateProfile(p)
                    }
                }

                withContext(Dispatchers.Main) {
                    GroupManager.postReload(groupId)
                }

                return@withContext "+${toInsert.size} / -${toDelete.size} (Обнов: ${toUpdate.size})"
            } catch (e: Exception) {
                Logs.e("DB Error", e)
                return@withContext "Ошибка БД"
            }
        }
    }

    // БЫСТРЫЙ ТЕСТ С ПРОГРЕВОМ
    private suspend fun phaseFastUrlTest(proxies: List<ProxyEntity>): List<ProxyEntity> {
        val alive = ConcurrentLinkedQueue<ProxyEntity>()
        val queue = ConcurrentLinkedQueue(proxies)
        val done = AtomicInteger(0)

        val concurrentThreads = DataStore.connectionTestConcurrent.coerceAtMost(3)
        val testUrl = config.testUrl.ifBlank { DataStore.connectionTestURL }

        coroutineScope {
            repeat(concurrentThreads) {
                launch(Dispatchers.IO) {
                    val ut = UrlTest(testUrl)
                    while (isActive) {
                        if (shouldStop()) break
                        val p = queue.poll() ?: break
                        try {
                            // ПРОГРЕВ
                            try {
                                withTimeout(2500L) { ut.doTest(p) }
                            } catch (_: Exception) {}

                            // ЧИСТЫЙ ЗАМЕР
                            val ms: Int = withTimeout(config.maxLatencyMs + 1000L) {
                                ut.doTest(p)
                            }.toInt()

                            if (ms > 15 && ms <= config.maxLatencyMs.toInt()) {
                                p.status = 1
                                p.ping = ms
                                p.error = null
                                alive.add(p)
                            } else {
                                p.status = 3
                                p.error = if (ms <= 15) "Заглушка" else "Тайм-аут"
                            }
                        } catch (e: Exception) {
                            p.status = 3
                            p.error = "Тайм-аут"
                        }

                        val n = done.incrementAndGet()
                        onProgress(AutoPilotProgress("БЫСТРЫЙ ТЕСТ", n, proxies.size, p.displayName(), alive.size))
                    }
                }
            }
        }
        return alive.toList()
    }

    // СТРОГИЙ ТЕСТ: ДВОЙНОЙ ЗАМЕР С ПРОВОКАЦИЕЙ
    private suspend fun phaseStrictCoreTest(
        candidates: List<ProxyEntity>,
        maxToTest: Int,
        phaseName: String = "ТЕСТ"
    ): List<ProxyEntity> {
        val good = mutableListOf<ProxyEntity>()
        val testUrl = config.testUrl.ifBlank { DataStore.connectionTestURL }
        val protocolName = if (testUrl.startsWith("https://")) "HTTPS" else "HTTP"

        for (i in 0 until maxToTest) {
            if (!currentCoroutineContext().isActive) break
            if (shouldStop()) break

            val c = candidates[i]

            onProgress(AutoPilotProgress(phaseName, i + 1, maxToTest, c.displayName(), good.size, "Подключение..."))

            try {
                val elapsedMs: Int = withContext(Dispatchers.IO) {

                    // УДАР 1: Прогрев
                    val warmupMs: Int = try {
                        withTimeout(3000L) { UrlTest(testUrl).doTest(c) }.toInt()
                    } catch (e: Exception) { -1 }

                    if (warmupMs <= 0) {
                        throw Exception("Прогрев не удался")
                    }
                    if (warmupMs <= 15) {
                        throw Exception("Заглушка (${warmupMs}ms)")
                    }

                    withContext(Dispatchers.Main) {
                        onProgress(AutoPilotProgress(phaseName, i + 1, maxToTest, c.displayName(), good.size, "Пауза 2 сек..."))
                    }

                    // ПАУЗА 2 СЕКУНДЫ
                    delay(2000L)

                    withContext(Dispatchers.Main) {
                        onProgress(AutoPilotProgress(phaseName, i + 1, maxToTest, c.displayName(), good.size, "Контрольный замер..."))
                    }

                    // УДАР 2: Контрольный замер
                    val finalMs: Int = withTimeout(config.maxLatencyMs + 1000L) {
                        UrlTest(testUrl).doTest(c)
                    }.toInt()

                    if (finalMs <= 15) {
                        throw Exception("Заглушка на контрольном")
                    }

                    // Берем ХУДШИЙ пинг
                    if (warmupMs > finalMs) warmupMs else finalMs
                }

                if (elapsedMs > 15 && elapsedMs <= config.maxLatencyMs.toInt()) {
                    c.status = 1
                    c.ping = elapsedMs
                    c.error = null
                    good.add(c)

                    onProgress(AutoPilotProgress(
                        phaseName, i + 1, maxToTest, c.displayName(), good.size,
                        "$protocolName Стабильный: ${elapsedMs}ms ✓✓"
                    ))
                } else {
                    c.status = 3
                    c.error = "Медленный: ${elapsedMs}ms"
                    onProgress(AutoPilotProgress(
                        phaseName, i + 1, maxToTest, c.displayName(), good.size, c.error
                    ))
                }
            } catch (e: Exception) {
                c.status = 3
                c.error = e.message ?: "Тайм-аут"
                onProgress(AutoPilotProgress(
                    phaseName, i + 1, maxToTest, c.displayName(), good.size, c.error ?: "Тайм-аут"
                ))
            }

            withContext(Dispatchers.Main) { ProfileManager.updateProfile(c) }

            // Очистка портов
            delay(500)
        }

        return good.sortedBy { it.ping }
    }

    private suspend fun doExport(proxies: List<ProxyEntity>): GitHubExporter.ExportResult {

        // --- МАГИЯ КРАСИВЫХ НАЗВАНИЙ ДЛЯ ЭКСПОРТА ---
        // Переименовываем все прокси прямо перед отправкой в GitHub
        proxies.forEachIndexed { index, p ->
            try {
                val bean = p.requireBean()
                // Заменяем имя на чистое и структурированное
                bean.name = formatNiceName(bean.name, index)
                // Так как объекты в Kotlin передаются по ссылке,
                // GitHubExporter автоматически получит уже новые красивые имена!
            } catch (_: Exception) {}
        }
        // --------------------------------------------

        return if (config.combineExport) {
            GitHubExporter.exportGroup("AutoPilot Best", proxies)
        } else {
            val byGroup = proxies.groupBy { it.groupId }
            val msgs = mutableListOf<String>()
            var anyFail = false
            for ((gid, list) in byGroup) {
                val gName = SagerDatabase.groupDao.getById(gid)?.displayName() ?: "G$gid"
                val r = GitHubExporter.exportGroup("AutoPilot - $gName", list)
                msgs.add("$gName: ${if (r.success) "✅" else "❌"}")
                if (!r.success) anyFail = true
            }
            GitHubExporter.ExportResult(!anyFail, msgs.joinToString("\n"))
        }
    }
    // Универсальный генератор названий для ВСЕХ стран мира
    @SuppressLint("DefaultLocale")
    private fun formatNiceName(originalName: String, index: Int): String {
        val upper = originalName.uppercase()
        // Определяем принадлежность к CIDR / LTE
        val isLte = upper.contains("CIDR") || upper.contains("LTE") || upper.contains("/24") || upper.contains("/16")

        var flag = "🌍"
        var countryName = "Неизвестно"

        // 1. Ищем уже готовый эмодзи-флаг в грязном названии
        val flagRegex = Regex("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")
        val flagMatch = flagRegex.find(originalName)

        if (flagMatch != null) {
            flag = flagMatch.value
            try {
                // Конвертируем Эмодзи в код страны (например 🇷🇺 -> RU)
                val c1 = flag.codePointAt(0) - 0x1F1E6 + 'A'.code
                val c2 = flag.codePointAt(2) - 0x1F1E6 + 'A'.code
                val countryCode = "${c1.toChar()}${c2.toChar()}"

                // Достаем название страны на русском из базы Android
                val loc = java.util.Locale("ru", countryCode)
                countryName = loc.displayCountry
            } catch (e: Exception) {
                countryName = "Локация"
            }
        } else {
            // 2. Если флага нет, прогоняем через базу ВСЕХ стран мира (около 250 стран)
            val allCountryCodes = java.util.Locale.getISOCountries()

            for (code in allCountryCodes) {
                val localeEn = java.util.Locale("en", code)
                val nameEn = localeEn.displayCountry.uppercase()
                val code3 = try { localeEn.isO3Country.uppercase() } catch (e: Exception) { "" }

                // Ищем английское имя (GERMANY), 3-букв код (DEU) или 2-букв код с границами ( DE , -DE-)
                if (upper.contains(nameEn) ||
                    (code3.length == 3 && upper.contains(code3)) ||
                    upper.contains(" $code ") ||
                    upper.contains("-$code-") ||
                    upper.contains("_${code}_")
                ) {
                    // Нашли! Генерируем эмодзи-флаг математически
                    val firstLetter = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
                    val secondLetter = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
                    flag = String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))

                    // Получаем русское название
                    countryName = java.util.Locale("ru", code).displayCountry
                    break // Страна найдена, останавливаем поиск
                }
            }
        }

        // Защита от дубликатов (порядковый номер 01, 02...)
        val num = String.format("%02d", index + 1)

        // Формируем финальную строку
        return if (isLte) {
            "$flag $countryName | LTE $num"
        } else {
            "$flag $countryName $num"
        }
    }
    private suspend fun waitForState(target: BaseService.State, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (DataStore.serviceState == target) return true
            delay(500)
        }
        return DataStore.serviceState == target
    }
}
