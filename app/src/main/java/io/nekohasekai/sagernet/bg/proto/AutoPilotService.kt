package io.nekohasekai.sagernet.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.*

class AutoPilotService : Service() {

    companion object {
        const val CHANNEL_ID = "autopilot_channel"
        const val FOREGROUND_ID = 19999
        const val RESULT_ID = 20000
        const val ACTION_START = "ap_start"
        const val ACTION_STOP = "ap_stop"

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, AutoPilotService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, AutoPilotService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    private var mainJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager
    @Volatile
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NekoBox:AutoPilot")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    stopRequested = false
                    isRunning = true
                    startForegroundSafe("🤖 AutoPilot", "Запуск системы...")
                    launchAutoPilot()
                }
            }
            ACTION_STOP -> {
                stopRequested = true
                updateForegroundNotif(
                    "⏹ Завершаем цикл",
                    "Выполняю финальный тест найденных и экспорт, затем остановлюсь"
                )
            }
        }
        return START_STICKY
    }

    private fun launchAutoPilot() {
        mainJob = scope.launch {
            val config = loadConfig()
            val engine = AutoPilotEngine(
                config = config,
                onProgress = { p ->
                    updateForegroundNotif("🤖 Проверка серверов", "${p.phase} [${p.current}/${p.total}] ✓${p.goodCount}\n${p.currentProxy ?: ""}")
                },
                shouldStop = { stopRequested }
            )

            while (isActive) {
                // 1. Просыпаемся и показываем неотключаемое уведомление процесса
                startForegroundSafe("🤖 AutoPilot проснулся", "Подготовка к тесту...")

                if (!isInternetAvailable()) {
                    showResultAndSleep("⏸ Ожидание сети", "Тест на паузе (нет интернета). Повтор через 5 мин")
                    delay(5 * 60_000L)
                    continue
                }

                if (isBatteryLowAndNotCharging()) {
                    showResultAndSleep("🔋 Батарея разряжена", "Уровень <15%. Ждем зарядку (пауза 10 мин)")
                    delay(10 * 60_000L)
                    continue
                }

                wakeLock?.acquire(15 * 60 * 1000L)
                DataStore.runningTest = true

                // 2. ЗАПУСК УМНОГО ЦИКЛА
                val result = try {
                    withTimeout(15 * 60 * 1000L) { engine.runFullCycle() }
                } catch (e: TimeoutCancellationException) {
                    AutoPilotResult(false, "Слишком долгий тест (более 15 мин)")
                } catch (e: Exception) {
                    AutoPilotResult(false, "Ошибка: ${e.message}")
                } finally {
                    DataStore.runningTest = false
                    try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
                }

                // 3. ОБРАБОТКА РЕЗУЛЬТАТОВ (уведомление исчезнет само через 1 мин)
                if (result.isSkipped) {
                    showResultAndSleep("✅ Всё идеально, сплю", "Рабочих серверов: ${result.exportedCount}\nСлед. проверка через: ${config.healthCheckMinutes} мин")
                    if (stopRequested) {
                        shutdown()
                        break
                    }
                    delay(config.healthCheckMinutes * 60_000L)
                } else if (result.success) {
                    showResultAndSleep("🔄 База обновлена", "Живых: ${result.exportedCount} | Умерло: ${result.deadCount}\nСлед. проверка через: ${config.healthCheckMinutes} мин")
                    if (stopRequested) {
                        shutdown()
                        break
                    }
                    delay(config.healthCheckMinutes * 60_000L)
                } else {
                    showResultAndSleep("❌ Ошибка проверки", "${result.message}\nПовтор через 5 мин")
                    if (stopRequested) {
                        shutdown()
                        break
                    }
                    delay(5 * 60_000L)
                }
            }
        }
    }

    // --- МАГИЯ УВЕДОМЛЕНИЙ ---

    private fun startForegroundSafe(title: String, text: String) {
        val notif = buildNotif(title, text, isOngoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(FOREGROUND_ID, notif)
        } else {
            startForeground(FOREGROUND_ID, notif)
        }
    }

    private fun updateForegroundNotif(title: String, text: String) {
        try { nm.notify(FOREGROUND_ID, buildNotif(title, text, isOngoing = true)) } catch (_: Exception) {}
    }

    private fun showResultAndSleep(title: String, text: String) {
        // Убираем постоянное уведомление о работе сервиса
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }

        // Показываем результат, который САМ ИСЧЕЗНЕТ через 60 секунд (60_000 мс)
        val resultNotif = buildNotif(title, text, isOngoing = false)
        try { nm.notify(RESULT_ID, resultNotif) } catch (_: Exception) {}
    }

    private fun buildNotif(title: String, text: String, isOngoing: Boolean): Notification {
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, AutoPilotService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_my_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(isOngoing)

        if (isOngoing) {
            // Кнопка СТОП только когда идет тест
            builder.addAction(R.drawable.ic_service_idle, "Остановить AutoPilot", stopIntent)
        } else {
            // Если это финальный результат - задаем таймер самоуничтожения (1 минута)
            builder.setTimeoutAfter(60_000L)
        }

        return builder.build()
    }

    // --- СЛУЖЕБНЫЕ ФУНКЦИИ ---

    private fun isBatteryLowAndNotCharging(): Boolean {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val batteryPct = if (level != -1 && scale != -1) level * 100 / scale.toFloat() else 100f
        return batteryPct <= 15f && !isCharging
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION") val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION") return networkInfo != null && networkInfo.isConnected
        }
    }

    private fun loadConfig() = AutoPilotConfig(
        groupIds = DataStore.autoPilotGroupIds.split(",").mapNotNull { it.toLongOrNull() },
        exportLimit = DataStore.autoPilotExportLimit,
        maxLatencyMs = DataStore.autoPilotMaxPing.toLong(),
        testUrl = DataStore.autoPilotTestUrl.ifBlank { DataStore.connectionTestURL },
        allowedProtocols = DataStore.autoPilotProtocols
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { setOf("all") },
        intervalMinutes = DataStore.autoPilotInterval,
        healthCheckMinutes = DataStore.autoPilotHealthInterval,
        deadThreshold = DataStore.autoPilotDeadThreshold,
        combineExport = DataStore.autoPilotCombine,
        strictWhitelistMode = DataStore.autoPilotStrictWhitelist
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AutoPilot", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun shutdown() {
        mainJob?.cancel()
        isRunning = false
        stopRequested = false
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { stopForeground(STOP_FOREGROUND_REMOVE) } else { @Suppress("DEPRECATION") stopForeground(true) }
        try { nm.cancel(RESULT_ID) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
