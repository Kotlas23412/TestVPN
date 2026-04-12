package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.AutoPilotService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import io.nekohasekai.sagernet.bg.proto.FullTestInstance
import io.nekohasekai.sagernet.utils.GitHubExporter

private fun applyGroupOrder(proxies: List<ProxyEntity>, order: Int, groupId: Long): List<ProxyEntity> {
    fun toProtocolPriorityKey(displayType: String): String {
        val value = displayType.lowercase()
        return when {
            value.contains("vless") -> "vless"
            value.contains("shadow") -> "ss"
            value.contains("trojan") -> "trojan"
            else -> value
        }
    }

    return when (order) {
        GroupOrder.BY_NAME -> proxies.sortedBy { it.displayName() }
        GroupOrder.BY_DELAY -> proxies.sortedBy { if (it.status == 1) it.ping else 114514 }
        GroupOrder.BY_PROTOCOL -> {
            val preferredProtocol = DataStore.getGroupProtocolPriority(groupId)
                ?.takeIf { it == "vless" || it == "ss" || it == "trojan" }
                ?: "vless"
            val protocolBestPing = proxies.groupBy { toProtocolPriorityKey(it.displayType()) }
                .mapValues { (_, list) ->
                    list.filter { it.status == 1 }.minOfOrNull { it.ping } ?: Int.MAX_VALUE
                }
            proxies.sortedWith(
                compareBy<ProxyEntity> { proxy ->
                    if (toProtocolPriorityKey(proxy.displayType()) == preferredProtocol) {
                        0
                    } else {
                        1
                    }
                }.thenBy { proxy ->
                    protocolBestPing[toProtocolPriorityKey(proxy.displayType())] ?: Int.MAX_VALUE
                }
                    .thenBy { toProtocolPriorityKey(it.displayType()) }
                        .thenBy { if (it.status == 1) it.ping else Int.MAX_VALUE }
                        .thenBy { it.displayName().lowercase() }
            )
        }

        else -> proxies
    }
}

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onQueryTextChange(query: String): Boolean {
        getCurrentGroupFragment()?.adapter?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        val searchView = toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> startActivity(Intent(context, ScannerActivity::class.java))

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) snackbar(getString(R.string.clipboard_empty)).show()
                else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) onMainDispatcher { snackbar(getString(R.string.no_proxies_found_in_clipboard)).show() }
                        else import(proxies)
                    } catch (e: SubscriptionFoundException) {
                        (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                    } catch (e: Exception) {
                        onMainDispatcher { snackbar(e.readableMessage).show() }
                    }
                }
            }

            R.id.action_import_file -> startFilesForResult(importFile, "*/*")
            R.id.action_new_socks -> startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            R.id.action_new_http -> startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            R.id.action_new_ss -> startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            R.id.action_new_vmess -> startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            R.id.action_new_vless -> startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply { putExtra("vless", true) })
            R.id.action_new_trojan -> startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            R.id.action_new_trojan_go -> startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            R.id.action_new_mieru -> startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            R.id.action_new_naive -> startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            R.id.action_new_hysteria -> startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            R.id.action_new_tuic -> startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            R.id.action_new_ssh -> startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            R.id.action_new_wg -> startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            R.id.action_new_shadowtls -> startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            R.id.action_new_anytls -> startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            R.id.action_new_config -> startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            R.id.action_new_chain -> startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))

            // 1. Update current Group's subscription
            R.id.action_update_subscription -> {
                runOnDefaultDispatcher {
                    val group = SagerDatabase.groupDao.getById(DataStore.currentGroupId())
                    if (group != null && group.type == GroupType.SUBSCRIPTION) {
                        io.nekohasekai.sagernet.group.GroupUpdater.startUpdate(group, false)
                        onMainDispatcher { snackbar("Запущено обновление подписки...").show() }
                    } else {
                        onMainDispatcher { snackbar("Эта группа не является подпиской").show() }
                    }
                }
            }

            // 2. Clear traffic statistics
            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    val proxies = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    for (p in proxies) { p.tx = 0L; p.rx = 0L; ProfileManager.updateProfile(p) }
                    onMainDispatcher { GroupManager.postReload(DataStore.currentGroupId()); snackbar("Статистика трафика очищена").show() }
                }
            }

            // 3. Remove duplicate servers
            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) toClear += pf
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(getString(R.string.delete_confirm_prompt) + "\nУдаляем дубликатов: ${toClear.size} шт.")
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) ProfileManager.deleteProfile(profile.groupId, profile.id)
                                        onMainDispatcher { GroupManager.postReload(DataStore.currentGroupId()) }
                                    }
                                }
                                .setNegativeButton(R.string.no, null).show()
                        }
                    } else onMainDispatcher { snackbar("Дубликатов не найдено").show() }
                }
            }

            // 4. Clear test results
            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val proxies = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    for (p in proxies) { p.status = 0; p.ping = 0; p.error = null; ProfileManager.updateProfile(p) }
                    onMainDispatcher { GroupManager.postReload(DataStore.currentGroupId()); snackbar("Результаты тестов очищены").show() }
                }
            }

            // 5, 6. Тесты
            R.id.action_connection_tcp_ping -> pingTest(false)
            R.id.action_connection_url_test -> urlTest()
            R.id.action_subscription_auto_https_cleanup -> runSubscriptionAutoHttpsCleanup()

            // 7, 8. Ручные экспорты
            R.id.action_github_export_selected -> runGithubExportSelected()
            R.id.action_github_export_country -> runGithubExportByCountry()
            R.id.action_autopilot_settings -> showAutoPilotSettingsDialog()
            R.id.action_protocol_priority -> showProtocolPriorityDialog(DataStore.currentGroupId())
            R.id.action_subscription_protocol_filter -> showSubscriptionProtocolFilterDialog(DataStore.currentGroupId())
            // 9. Менеджер GitHub
            R.id.action_github_manager -> startActivity(Intent(requireActivity(), GitHubManagerActivity::class.java))

            // 15. Clear unavailable
            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = profiles.filter { it.status != 0 && it.status != 1 }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage("Удалить нерабочих серверов: ${toClear.size} шт.?")
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) ProfileManager.deleteProfile(profile.groupId, profile.id)
                                        onMainDispatcher { GroupManager.postReload(DataStore.currentGroupId()) }
                                    }
                                }
                                .setNegativeButton(R.string.no, null).show()
                        }
                    } else onMainDispatcher { snackbar("Нет нерабочих серверов").show() }
                }
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) {
            snackbar("Тестирование уже запущено! Дождитесь окончания.").show()
            return
        } else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                    if (icmpPing) {
                        if (it.requireBean().canICMPing()) {
                            return@filter true
                        }
                    } else {
                        if (it.requireBean().canTCPing()) {
                            return@filter true
                        }
                    }
                    return@filter false
                }.sortedBy { it.userOrder }
                test.proxyN = profilesList.size
                val profiles = ConcurrentLinkedQueue(profilesList)
                repeat(DataStore.connectionTestConcurrent) {
                    testJobs.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = profiles.poll() ?: break

                            profile.status = 0
                            var address = profile.requireBean().serverAddress
                            if (!address.isIpAddress()) {
                                try {
                                    SagerNet.underlyingNetwork!!.getAllByName(address).apply {
                                        if (isNotEmpty()) {
                                            address = this[0].hostAddress
                                        }
                                    }
                                } catch (ignored: UnknownHostException) {
                                }
                            }
                            if (!isActive) break
                            if (!address.isIpAddress()) {
                                profile.status = 2
                                profile.error = app.getString(R.string.connection_test_domain_not_found)
                                test.update(profile)
                                continue
                            }
                            try {
                                if (icmpPing) {
                                    // removed
                                } else {
                                    val socket =
                                        SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                            ?: Socket()
                                    try {
                                        socket.soTimeout = 3000
                                        socket.bind(InetSocketAddress(0))
                                        val start = SystemClock.elapsedRealtime()
                                        socket.connect(
                                            InetSocketAddress(
                                                address, profile.requireBean().serverPort
                                            ), 3000
                                        )
                                        if (!isActive) break
                                        profile.status = 1
                                        profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                        test.update(profile)
                                    } finally {
                                        socket.closeQuietly()
                                    }
                                }
                            } catch (e: Exception) {
                                if (!isActive) break
                                val message = e.readableMessage

                                if (icmpPing) {
                                    profile.status = 2
                                    profile.error = getString(R.string.connection_test_unreachable)
                                } else {
                                    profile.status = 2
                                    when {
                                        !message.contains("failed:") -> profile.error =
                                            getString(R.string.connection_test_timeout)

                                        else -> when {
                                            message.contains("ECONNREFUSED") -> {
                                                profile.error =
                                                    getString(R.string.connection_test_refused)
                                            }

                                            message.contains("ENETUNREACH") -> {
                                                profile.error =
                                                    getString(R.string.connection_test_unreachable)
                                            }

                                            else -> {
                                                profile.status = 3
                                                profile.error = message
                                            }
                                        }
                                    }
                                }
                                test.update(profile)
                            }
                        }
                    })
                }

                testJobs.joinAll()

                runOnMainDispatcher {
                    test.cancel()
                }
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("Ошибка теста: ${e.readableMessage}").show()
                }
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                requireContext(),
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) {
            snackbar("Тестирование уже запущено! Дождитесь окончания.").show()
            return
        } else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).sortedBy { it.userOrder }
                test.proxyN = profilesList.size
                val profiles = ConcurrentLinkedQueue(profilesList)

                repeat(DataStore.connectionTestConcurrent) {
                    testJobs.add(launch(Dispatchers.IO) {
                        val urlTest = UrlTest() // note: this is NOT in bg process
                        while (isActive) {
                            val profile = profiles.poll() ?: break
                            profile.status = 0

                            try {
                                val result = urlTest.doTest(profile)
                                profile.status = 1
                                profile.ping = result
                            } catch (e: PluginManager.PluginNotFoundException) {
                                profile.status = 2
                                profile.error = e.readableMessage
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.readableMessage
                            }

                            test.update(profile)
                        }
                    })
                }

                testJobs.joinAll()

                runOnMainDispatcher {
                    test.cancel()
                }
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("Ошибка теста: ${e.readableMessage}").show()
                }
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                requireContext(),
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun fullHttpsTest() {
        if (DataStore.runningTest) {
            snackbar("Тестирование уже запущено! Дождитесь окончания.").show()
            return
        } else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).sortedBy { it.userOrder }
                test.proxyN = profilesList.size
                val profiles = ConcurrentLinkedQueue(profilesList)
                repeat(DataStore.connectionTestConcurrent) {
                    testJobs.add(launch(Dispatchers.IO) {
                        while (isActive) {
                            val profile = profiles.poll() ?: break
                            profile.status = 0

                            try {
                                val result = FullTestInstance(
                                    profile = profile,
                                    timeout = 15000,
                                    minOk = 2
                                ).doTest()

                                if (result.success) {
                                    profile.status = 1
                                    profile.ping = result.bestLatencyMs.toInt()
                                    profile.error = null
                                } else {
                                    profile.status = 3
                                    profile.error = result.error ?: "HTTPS test failed"
                                }
                            } catch (e: PluginManager.PluginNotFoundException) {
                                profile.status = 2
                                profile.error = e.readableMessage
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.readableMessage
                            }

                            test.update(profile)
                        }
                    })
                }

                testJobs.joinAll()

                runOnMainDispatcher {
                    test.cancel()
                }
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("Ошибка HTTPS-теста: ${e.readableMessage}").show()
                }
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                requireContext(),
                "[${group.displayName()}] ${getString(R.string.full_https_test)}"
            )
            dialog.hide()
        }
    }

    private fun ProxyGroup.supportsSubscriptionAutoCheck(): Boolean {
        if (type == GroupType.SUBSCRIPTION) return true
        val autoPilotBestName = "🚀 AutoPilot Best"
        return name?.trim() == autoPilotBestName || displayName().trim() == autoPilotBestName
    }

    private suspend fun waitForServiceConnected(timeoutMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (DataStore.serviceState.connected) return true
            if (!isActive) return false
            delay(250L)
        }
        return DataStore.serviceState.connected
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun runSubscriptionManualTestCleanup() {
        if (DataStore.runningTest) {
            snackbar("Тестирование уже запущено! Дождитесь окончания.").show()
            return
        }

        val group = DataStore.currentGroup()
        if (!group.supportsSubscriptionAutoCheck()) {
            snackbar(getString(R.string.group_not_subscription)).show()
            return
        }
        DataStore.runningTest = true

        val test = TestDialog()
        val dialog = test.builder.show()
        val oldSelected = DataStore.selectedProxy
        val wasRunning = DataStore.serviceState.started

        val mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).sortedBy { it.userOrder }
                test.proxyN = profilesList.size
                var deletedCount = 0

                for (profile in profilesList) {
                    if (!isActive) break
                    profile.status = 0

                    try {
                        onMainDispatcher {
                            DataStore.selectedProxy = profile.id
                            ProfileManager.postUpdate(profile.id)
                            if (DataStore.serviceState.canStop) {
                                SagerNet.reloadService()
                            } else {
                                SagerNet.startService()
                            }
                        }

                        val connected = waitForServiceConnected(20_000L)
                        if (!connected) {
                            throw IllegalStateException("Сервис не подключился")
                        }

                        val result = FullTestInstance(
                            profile = profile,
                            timeout = 15000,
                            minOk = 2
                        ).doTest()

                        if (result.success) {
                            profile.status = 1
                            profile.ping = result.bestLatencyMs.toInt()
                            profile.error = null
                            ProfileManager.updateProfile(profile)
                        } else {
                            profile.status = 3
                            profile.error = result.error ?: "HTTPS test failed"
                            ProfileManager.deleteProfile(profile.groupId, profile.id)
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        profile.status = 3
                        profile.error = e.readableMessage
                        ProfileManager.deleteProfile(profile.groupId, profile.id)
                        deletedCount++
                    }

                    test.update(profile)
                }

                onMainDispatcher {
                    DataStore.selectedProxy = oldSelected
                    ProfileManager.postUpdate(oldSelected)
                    if (wasRunning) {
                        if (DataStore.serviceState.canStop) {
                            SagerNet.reloadService()
                        } else {
                            SagerNet.startService()
                        }
                    } else if (DataStore.serviceState.started) {
                        SagerNet.stopService()
                    }
                }

                GroupManager.postReload(group.id)
                DataStore.runningTest = false

                onMainDispatcher {
                    test.dialogStatus.set(2)
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("Ручная автопроверка завершена. Удалено: $deletedCount").show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("Ошибка ручной автопроверки: ${e.readableMessage}").show()
                }
            }
        }

        test.cancel = {
            test.dialogStatus.set(2)
            if (dialog.isShowing) dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                withContext(NonCancellable) {
                    onMainDispatcher {
                        DataStore.selectedProxy = oldSelected
                        ProfileManager.postUpdate(oldSelected)
                        if (wasRunning) {
                            if (DataStore.serviceState.canStop) {
                                SagerNet.reloadService()
                            } else {
                                SagerNet.startService()
                            }
                        } else if (DataStore.serviceState.started) {
                            SagerNet.stopService()
                        }
                    }
                }
                GroupManager.postReload(group.id)
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                requireContext(),
                "[${group.displayName()}] ${getString(R.string.subscription_manual_test_cleanup)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun runSubscriptionAutoHttpsCleanup() {
        if (DataStore.runningTest) {
            snackbar("Тестирование уже запущено! Дождитесь окончания.").show()
            return
        }

        val group = DataStore.currentGroup()
        if (!group.supportsSubscriptionAutoCheck()) {
            snackbar(getString(R.string.group_not_subscription)).show()
            return
        }
        DataStore.runningTest = true

        val test = TestDialog()
        val dialog = test.builder.show()
        test.proxyN = 0

        val mainJob = runOnDefaultDispatcher {
            try {
                fun protocolKey(profile: ProxyEntity): String = when (profile.type) {
                    ProxyEntity.TYPE_SS -> "ss"
                    ProxyEntity.TYPE_TROJAN, ProxyEntity.TYPE_TROJAN_GO -> "trojan"
                    ProxyEntity.TYPE_VMESS -> if (profile.vmessBean?.isVLESS == true) "vless" else "vmess"
                    else -> "other"
                }
                val allowedProtocols = DataStore.autoPilotProtocols
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val useAllProtocols = allowedProtocols.isEmpty() || allowedProtocols.contains("all")

                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
                    .sortedBy { it.userOrder }
                    .filter { useAllProtocols || allowedProtocols.contains(protocolKey(it)) }
                test.proxyN = profilesList.size
                val deletedProfiles = mutableListOf<ProxyEntity>()

                for (profile in profilesList) {
                    if (!isActive) break
                    profile.status = 0

                    try {
                        val result = FullTestInstance(
                            profile = profile,
                            timeout = 15000,
                            minOk = 2
                        ).doTest()

                        if (result.success) {
                            profile.status = 1
                            profile.ping = result.bestLatencyMs.toInt()
                            profile.error = null
                            ProfileManager.updateProfile(profile)
                        } else {
                            profile.status = 3
                            profile.error = result.error ?: "HTTPS test failed"
                            deletedProfiles += profile
                            ProfileManager.deleteProfile(profile.groupId, profile.id)
                        }
                    } catch (e: PluginManager.PluginNotFoundException) {
                        profile.status = 2
                        profile.error = e.readableMessage
                        deletedProfiles += profile
                        ProfileManager.deleteProfile(profile.groupId, profile.id)
                    } catch (e: Exception) {
                        profile.status = 3
                        profile.error = e.readableMessage
                        deletedProfiles += profile
                        ProfileManager.deleteProfile(profile.groupId, profile.id)
                    }

                    test.update(profile)
                }

                GroupManager.postReload(group.id)
                DataStore.runningTest = false

                onMainDispatcher {
                    test.dialogStatus.set(2)
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar(
                        "Автопроверка подписки завершена. Удалено: ${deletedProfiles.size}, осталось: ${profilesList.size - deletedProfiles.size}"
                    ).show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("Ошибка автопроверки: ${e.readableMessage}").show()
                }
            }
        }

        test.cancel = {
            test.dialogStatus.set(2)
            if (dialog.isShowing) dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                GroupManager.postReload(group.id)
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                requireContext(),
                "[${group.displayName()}] ${getString(R.string.subscription_auto_https_cleanup)}"
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        groupList = newGroupList
                        notifyDataSetChanged()
                        if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                        val hideTab = groupList.size < 2
                        tabLayout.isGone = hideTab
                        toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                        if (!select) {
                            groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return

            tabLayout.post {
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            val byProtocol = menu.findItem(R.id.action_order_by_protocol)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }

                GroupOrder.BY_PROTOCOL -> {
                    byProtocol.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
            byProtocol.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_PROTOCOL)
                (parentFragment as? ConfigurationFragment)
                    ?.showProtocolPriorityDialog(proxyGroup.id)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            if (!select) {

                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter?.move(
                            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter?.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
                }.keys)
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                    //
                    val oldProfile = configurationList[profile.id]
                    if (noTraffic && oldProfile != null) {
                        runOnDefaultDispatcher {
                            onUpdated(
                                TrafficData(
                                    id = profile.id,
                                    rx = oldProfile.rx,
                                    tx = oldProfile.tx
                                )
                            )
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                try {
                    val index = configurationIdList.indexOf(data.id)
                    if (index != -1) {
                        val holder = layoutManager.findViewByPosition(index)
                            ?.let { configurationListView.getChildViewHolder(it) } as ConfigurationHolder?
                        if (holder != null) {
                            onMainDispatcher {
                                holder.bind(holder.entity, data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles() {
                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                newProfiles = applyGroupOrder(newProfiles, proxyGroup.order, proxyGroup.id)

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    if (selectedProfileIndex != -1) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                    } else if (newProfiles.isNotEmpty()) {
                        configurationListView.scrollTo(0, true)
                    }

                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity

                if (select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                onMainDispatcher {
                                    selectedView.visibility = View.VISIBLE
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx
                if (trafficData != null) {
                    // use new data
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                var address = proxyEntity.displayAddress()
                if (showTraffic && address.length >= 30) {
                    address = address.substring(0, 27) + "..."
                }

                if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone =
                    (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = getString(R.string.available, proxyEntity.ping)
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: "<?>"
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(err).tryToShow()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                removeButton.setOnClickListener {
                    adapter?.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        it.remove(index)
                        undoManager.remove(index to proxyEntity)
                    }
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                shareLayout.isGone = selectOrChain
                editButton.isGone = select
                removeButton.isGone = select

                proxyEntity.nekoBean?.apply {
                    shareLayout.isGone = true
                }

                runOnDefaultDispatcher {
                    val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                    val started =
                        selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                    onMainDispatcher {
                        editButton.isEnabled = !started
                        removeButton.isEnabled = !started
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                    }

                    fun showShare(anchor: View) {
                        val popup = PopupMenu(requireContext(), anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        when {
                            !proxyEntity.haveStandardLink() -> {
                                popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                                popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                                    R.id.action_standard_clipboard
                                )
                            }

                            !proxyEntity.haveLink() -> {
                                popup.menu.removeItem(R.id.action_group_qr)
                                popup.menu.removeItem(R.id.action_group_clipboard)
                            }
                        }

                        if (proxyEntity.nekoBean != null) {
                            popup.menu.removeItem(R.id.action_group_configuration)
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                        onMainDispatcher {
                            shareLayer.setBackgroundColor(Color.TRANSPARENT)
                            shareButton.setImageResource(R.drawable.ic_social_share)
                            shareButton.setColorFilter(Color.GRAY)
                            shareButton.isVisible = true

                            shareLayout.setOnClickListener {
                                showShare(it)
                            }
                        }
                    }
                }

            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                // Если toUri отсутствует или был переименован, мы используем toUniversalLink() как 100% безопасный и рабочий фоллбэк для всех видов ссылок.
                val link = entity.requireBean().toUniversalLink()

                when (item.itemId) {
                    R.id.action_group_qr, R.id.action_standard_qr -> {
                        showCode(link)
                    }

                    R.id.action_group_clipboard, R.id.action_standard_clipboard -> {
                        export(link)
                    }

                    R.id.action_group_configuration -> {
                        runOnDefaultDispatcher {
                            try {
                                val text = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(entity.requireBean())
                                onMainDispatcher {
                                    val dialog = MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("Configuration")
                                        .setMessage(text)
                                        .setPositiveButton(android.R.string.copy) { _, _ ->
                                            export(text)
                                        }
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show()
                                    dialog.findViewById<TextView>(android.R.id.message)?.apply {
                                        setTextIsSelectable(true)
                                        typeface = android.graphics.Typeface.MONOSPACE
                                    }
                                }
                            } catch (e: Exception) {
                                Logs.e(e)
                            }
                        }
                    }

                    R.id.action_send_to_autopilot_best -> {
                        sendProxyToAutoPilotBest(entity)
                    }
                }
                return true
            }
        }
    }

    private fun sendProxyToAutoPilotBest(proxy: ProxyEntity) {
        runOnDefaultDispatcher {
            val syncError = syncExportToAutoPilotBestGroup(listOf(proxy))
            onMainDispatcher {
                snackbar(syncError ?: "Прокси отправлен в 🚀 AutoPilot Best").show()
            }
        }
    }

    private fun sendProxyToAutoPilotBest(proxy: ProxyEntity) {
        runOnDefaultDispatcher {
            val syncError = syncExportToAutoPilotBestGroup(listOf(proxy))
            onMainDispatcher {
                snackbar(syncError ?: "Прокси отправлен в 🚀 AutoPilot Best").show()
            }
        }
    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun runGithubAutoExport(useHttpsTest: Boolean) {
        if (DataStore.runningTest) {
            snackbar("Тестирование уже запущено! Дождитесь окончания.").show()
            return
        }
        DataStore.runningTest = true

        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        // 1. Показываем уведомление, что начали
        test.notification = ConnectionTestNotification(
            requireContext(),
            "[${group.displayName()}] ${getString(R.string.github_export_running)}"
        )

        val mainJob = runOnDefaultDispatcher {
            try {
                val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
                test.proxyN = profilesList.size
                val profiles = ConcurrentLinkedQueue(profilesList)

                // 2. Тестируем все прокси в группе
                repeat(DataStore.connectionTestConcurrent) {
                    testJobs.add(launch(Dispatchers.IO) {
                        val urlTest = if (!useHttpsTest) UrlTest() else null

                        while (isActive) {
                            val profile = profiles.poll() ?: break
                            profile.status = 0

                            try {
                                if (useHttpsTest) {
                                    // Хардкорный HTTPS тест
                                    val result =
                                        FullTestInstance(profile, timeout = 15000, minOk = 2).doTest()
                                    if (result.success) {
                                        profile.status = 1
                                        profile.ping = result.bestLatencyMs.toInt()
                                        profile.error = null
                                    } else {
                                        profile.status = 3
                                        profile.error = result.error ?: "HTTPS failed"
                                    }
                                } else {
                                    // Обычный URL тест
                                    val result = urlTest!!.doTest(profile)
                                    profile.status = 1
                                    profile.ping = result
                                    profile.error = null
                                }
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.readableMessage
                            }

                            test.update(profile)
                        }
                    })
                }

                // Ждем завершения всех тестов
                testJobs.joinAll()

                // 3. Отбираем лучшие прокси
                val limit = DataStore.githubExportLimit
                val testedProfiles = test.results.toList()

                // Сортируем: только успешные (status == 1), сортировка по пингу (от меньшего к большему)
                val bestProxies = testedProfiles
                    .filter { it.status == 1 }
                    .sortedBy { it.ping }
                    .take(limit)

                onMainDispatcher {
                    test.dialogStatus.set(2)
                    dialog.dismiss()
                }

                if (bestProxies.isEmpty()) {
                    DataStore.runningTest = false
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Экспорт отменён")
                            .setMessage("Нет ни одного рабочего прокси для экспорта!")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return@runOnDefaultDispatcher
                }

                // 4. Отправляем на GitHub!
                val progressDialog = onMainDispatcher {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Выгрузка на GitHub")
                        .setMessage("Отправляем ${bestProxies.size} лучших прокси...")
                        .setCancelable(false)
                        .show()
                }

                // Обновляем статусы в базе данных локально
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())

                // Делаем экспорт
                val result = GitHubExporter.exportGroup(group.displayName(), bestProxies)
                DataStore.runningTest = false

                onMainDispatcher {
                    progressDialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(if (result.success) "Успех!" else "Ошибка выгрузки")
                        .setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                DataStore.runningTest = false
                onMainDispatcher {
                    if (dialog.isShowing) dialog.dismiss()
                    snackbar("AutoPilot ошибка: ${e.readableMessage}").show()
                }
            }
        }

        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                DataStore.runningTest = false
            }
        }

        test.minimize = {
            test.dialogStatus.set(1)
            dialog.hide()
        }
    }

    private fun showAutoPilotSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_autopilot_settings, null)
        val limit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.ap_limit)
        val healthInterval = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.ap_health_interval)
        val maxPing = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.ap_max_ping)
        val testUrl = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.ap_test_url)
        val protocolAll = view.findViewById<android.widget.CheckBox>(R.id.ap_protocol_all)
        val protocolVless = view.findViewById<android.widget.CheckBox>(R.id.ap_protocol_vless)
        val protocolSs = view.findViewById<android.widget.CheckBox>(R.id.ap_protocol_ss)
        val protocolTrojan = view.findViewById<android.widget.CheckBox>(R.id.ap_protocol_trojan)
        val combine = view.findViewById<android.widget.CheckBox>(R.id.ap_combine)
        val strictWhitelist = view.findViewById<android.widget.CheckBox>(R.id.ap_strict_whitelist)

        limit.setText(DataStore.autoPilotExportLimit.toString())
        healthInterval.setText(DataStore.autoPilotHealthInterval.toString())
        maxPing.setText(DataStore.autoPilotMaxPing.toString())
        testUrl.setText(DataStore.autoPilotTestUrl.ifBlank { DataStore.connectionTestURL })
        combine.isChecked = DataStore.autoPilotCombine
        strictWhitelist.isChecked = DataStore.autoPilotStrictWhitelist
        val selectedProtocols = DataStore.autoPilotProtocols
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val isAllSelected = selectedProtocols.isEmpty() || selectedProtocols.contains("all")
        protocolAll.isChecked = isAllSelected
        protocolVless.isChecked = isAllSelected || selectedProtocols.contains("vless")
        protocolSs.isChecked = isAllSelected || selectedProtocols.contains("ss")
        protocolTrojan.isChecked = isAllSelected || selectedProtocols.contains("trojan")
        fun setProtocolChecksEnabled(enabled: Boolean) {
            protocolVless.isEnabled = enabled
            protocolSs.isEnabled = enabled
            protocolTrojan.isEnabled = enabled
        }
        setProtocolChecksEnabled(!protocolAll.isChecked)
        protocolAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                protocolVless.isChecked = false
                protocolSs.isChecked = false
                protocolTrojan.isChecked = false
            }
            setProtocolChecksEnabled(!checked)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🤖 AutoPilot")
            .setView(view)
            .setPositiveButton("Запустить") { _, _ ->
                DataStore.autoPilotExportLimit = limit.text?.toString()?.toIntOrNull() ?: 10
                DataStore.autoPilotHealthInterval = healthInterval.text?.toString()?.toIntOrNull() ?: 10
                DataStore.autoPilotMaxPing = maxPing.text?.toString()?.toIntOrNull() ?: 3000
                DataStore.autoPilotTestUrl = testUrl.text?.toString()?.trim().orEmpty()
                val protocolSelection = if (protocolAll.isChecked) {
                    "all"
                } else {
                    buildList {
                        if (protocolVless.isChecked) add("vless")
                        if (protocolSs.isChecked) add("ss")
                        if (protocolTrojan.isChecked) add("trojan")
                    }.joinToString(",").ifBlank { "all" }
                }
                DataStore.autoPilotProtocols = protocolSelection
                DataStore.autoPilotCombine = combine.isChecked
                DataStore.autoPilotStrictWhitelist = strictWhitelist.isChecked
                runOnDefaultDispatcher {
                    val groups = SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.SUBSCRIPTION }
                        .sortedBy { it.displayName().lowercase() }
                    val selectedIds = DataStore.autoPilotGroupIds.split(",")
                        .mapNotNull { it.toLongOrNull() }
                        .toSet()

                    onMainDispatcher {
                        if (groups.isEmpty()) {
                            snackbar("Нет подписок для проверки").show()
                            return@onMainDispatcher
                        }

                        val labels = groups.map { it.displayName() }.toTypedArray()
                        val checked = BooleanArray(groups.size) { index ->
                            selectedIds.contains(groups[index].id)
                        }

                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Какие подписки проверять?")
                            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                                checked[which] = isChecked
                            }
                            .setPositiveButton("Запустить") { _, _ ->
                                val selected = groups.filterIndexed { index, _ -> checked[index] }
                                if (selected.isEmpty()) {
                                    snackbar("Выберите хотя бы одну подписку").show()
                                    return@setPositiveButton
                                }
                                DataStore.autoPilotGroupIds = selected.joinToString(",") { it.id.toString() }
                                AutoPilotService.start(requireContext())
                                snackbar("AutoPilot запущен").show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setNeutralButton("Остановить") { _, _ ->
                AutoPilotService.stop(requireContext())
                snackbar("AutoPilot остановится после финализации цикла").show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun runGithubExportSelected() {
        runOnDefaultDispatcher {
            // Получаем прокси с учетом текущей сортировки группы
            val group = DataStore.currentGroup()
            var allProxies = SagerDatabase.proxyDao.getByGroup(group.id)

            // Применяем сортировку в соответствии с настройками группы
            allProxies = applyGroupOrder(allProxies, group.order, group.id)

            if (allProxies.isEmpty()) {
                onMainDispatcher { snackbar("В этой группе нет прокси!").show() }
                return@runOnDefaultDispatcher
            }

            val names = allProxies.map { proxy ->
                val statusText = when (proxy.status) {
                    1 -> "✅ ${proxy.ping} ms"
                    0 -> "⏳ не тестировался"
                    2, 3 -> "❌ ${proxy.error ?: "не работает"}"
                    else -> "⚪ ${proxy.error ?: "без статуса"}"
                }
                "${proxy.displayName()}  [$statusText]"
            }.toTypedArray()
            val checked = BooleanArray(allProxies.size)
            val hasAvailable = allProxies.any { it.status == 1 }

            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Выберите прокси для экспорта")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setNeutralButton(if (hasAvailable) "Выбрать рабочие" else "Выбрать все") { _, _ ->
                        val selectAvailable = hasAvailable
                        for (i in allProxies.indices) {
                            checked[i] = if (selectAvailable) allProxies[i].status == 1 else true
                        }
                        val selected = allProxies.filterIndexed { i, _ -> checked[i] }
                        if (selected.isEmpty()) {
                            snackbar("Ничего не выбрано").show()
                            return@setNeutralButton
                        }
                        exportMultipleGroups(
                            selected,
                            "Отправляем ${selected.size} прокси..."
                        )
                    }
                    .setPositiveButton("Экспорт") { _, _ ->
                        val selected = allProxies.filterIndexed { i, _ -> checked[i] }

                        if (selected.isEmpty()) {
                            snackbar("Ничего не выбрано").show()
                            return@setPositiveButton
                        }

                        exportMultipleGroups(
                            selected,
                            "Отправляем ${selected.size} прокси..."
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showProtocolPriorityDialog(groupId: Long) {
        fun toProtocolPriorityKey(displayType: String): String {
            val value = displayType.lowercase()
            return when {
                value.contains("vless") -> "vless"
                value.contains("shadow") -> "ss"
                value.contains("trojan") -> "trojan"
                else -> value
            }
        }
        fun protocolLabel(key: String): String = when (key) {
            "vless" -> "VLESS"
            "ss" -> "SS"
            "trojan" -> "TROJAN"
            else -> key.uppercase()
        }

        runOnDefaultDispatcher {
            val proxies = SagerDatabase.proxyDao.getByGroup(groupId)
            val supportedKeys = listOf("vless", "ss", "trojan")
            val protocols = proxies.map { toProtocolPriorityKey(it.displayType()) }
                .distinct()
                .filter { it in supportedKeys }
            val currentPriority = DataStore.getGroupProtocolPriority(groupId)
                ?.takeIf { it in supportedKeys }
                ?: "vless"

            onMainDispatcher {
                if (protocols.isEmpty()) {
                    snackbar(getString(R.string.group_protocol_priority_empty)).show()
                    return@onMainDispatcher
                }

                var selectedIndex = protocols.indexOf(currentPriority).takeIf { it >= 0 } ?: 0
                val labels = protocols.map { protocolLabel(it) }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.group_protocol_priority_title)
                    .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                        selectedIndex = which
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selectedProtocol = protocols[selectedIndex]
                        DataStore.setGroupProtocolPriority(groupId, selectedProtocol)
                        runOnDefaultDispatcher { GroupManager.postReload(groupId) }
                        snackbar("Приоритет протокола: ${protocolLabel(selectedProtocol)}").show()
                    }
                    .setNeutralButton("VLESS") { _, _ ->
                        DataStore.setGroupProtocolPriority(groupId, "vless")
                        runOnDefaultDispatcher { GroupManager.postReload(groupId) }
                        snackbar("Приоритет протокола: VLESS").show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showSubscriptionProtocolFilterDialog(groupId: Long) {
        if (groupId <= 0L) return
        val protocolKeys = listOf("vless", "vmess", "trojan", "ss", "hysteria", "tuic", "anytls")
        val protocolLabels = listOf("VLESS", "VMESS", "TROJAN", "SS", "HYSTERIA", "TUIC", "ANYTLS")
        val checked = BooleanArray(protocolKeys.size)
        val current = DataStore.getGroupSubscriptionProtocolFilter(groupId)
        if (current.isEmpty()) {
            checked.fill(true)
        } else {
            protocolKeys.forEachIndexed { index, key ->
                checked[index] = current.contains(key)
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscription_protocol_filter_title)
            .setMultiChoiceItems(protocolLabels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                DataStore.setGroupSubscriptionProtocolFilter(groupId, emptySet())
                snackbar(getString(R.string.subscription_protocol_filter_saved, "ALL")).show()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = protocolKeys.filterIndexed { index, _ -> checked[index] }.toSet()
                if (selected.isEmpty()) {
                    snackbar(getString(R.string.subscription_protocol_filter_empty)).show()
                    return@setPositiveButton
                }
                DataStore.setGroupSubscriptionProtocolFilter(groupId, selected)
                val selectedText = protocolKeys.filterIndexed { index, _ -> checked[index] }
                    .joinToString(", ") { it.uppercase() }
                snackbar(getString(R.string.subscription_protocol_filter_saved, selectedText)).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun runGithubExportByCountry() {
        runOnDefaultDispatcher {
            val allProxies = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())


            if (allProxies.isEmpty()) {
                onMainDispatcher { snackbar("Нет прокси для экспорта").show() }
                return@runOnDefaultDispatcher
            }

            val countryMap = mutableMapOf<String, MutableList<ProxyEntity>>()
            val flagRegex = Regex("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")

            for (proxy in allProxies) {
                val name = proxy.displayName()
                var categoryName = "🌍 Остальные"

                try {
                    val flagMatch = flagRegex.find(name)

                    if (flagMatch != null) {
                        val flag = flagMatch.value
                        var countryName = ""

                        try {
                            val c1 = flag.codePointAt(0) - 0x1F1E6 + 'A'.code
                            val c2 = flag.codePointAt(2) - 0x1F1E6 + 'A'.code
                            val isoCode = "${c1.toChar()}${c2.toChar()}"
                            val display = java.util.Locale("ru", isoCode).displayCountry
                            if (display.length > 2) {
                                countryName = display
                            }
                        } catch (e: Exception) {}

                        if (countryName.isEmpty()) {
                            val textAfterFlag = name.substringAfter(flag).trim()
                            val firstWord = textAfterFlag.split(Regex("[^\\p{L}]+")).firstOrNull { it.length > 2 }
                            countryName = firstWord ?: "Локация"
                        }

                        countryName = countryName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                        categoryName = "$flag $countryName"
                    } else {
                        val upper = name.uppercase()
                        when {
                            upper.contains("GERMANY") || upper.contains(" DE ") -> categoryName = "🇩🇪 Германия"
                            upper.contains("FINLAND") || upper.contains(" FI ") -> categoryName = "🇫🇮 Финляндия"
                            upper.contains("RUSSIA") || upper.contains(" RU ") -> categoryName = "🇷🇺 Россия"
                            upper.contains("UNITED STATES") || upper.contains(" US ") -> categoryName = "🇺🇸 США"
                            upper.contains("NETHERLANDS") || upper.contains(" NL ") -> categoryName = "🇳🇱 Нидерланды"
                            upper.contains("FRANCE") || upper.contains(" FR ") -> categoryName = "🇫🇷 Франция"
                            upper.contains("KINGDOM") || upper.contains(" UK ") || upper.contains(" GB ") -> categoryName = "🇬🇧 Великобритания"
                            upper.contains("TURKEY") || upper.contains(" TR ") -> categoryName = "🇹🇷 Турция"
                            upper.contains("POLAND") || upper.contains(" PL ") -> categoryName = "🇵🇱 Польша"
                            upper.contains("SWEDEN") || upper.contains(" SE ") -> categoryName = "🇸🇪 Швеция"
                        }
                    }
                } catch (e: Exception) {
                    Logs.e("Ошибка парсинга страны", e)
                }

                countryMap.getOrPut(categoryName) { mutableListOf() }.add(proxy)
            }

            val keys = countryMap.keys.toTypedArray()
            keys.sort()

            onMainDispatcher {
                val checked = BooleanArray(keys.size)

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Выберите страны (экспорт: топ-2 по пингу на страну)")
                    .setMultiChoiceItems(keys, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }

                    .setNeutralButton("Выбрать все") { _, _ ->
                        for (i in checked.indices) checked[i] = true
                        val selectedCountries = keys.toList()
                        val bestByCountry = selectedCountries.flatMap { country ->
                            countryMap[country].orEmpty()
                                .sortedWith(
                                    compareBy<ProxyEntity> { if (it.ping > 0) it.ping else Int.MAX_VALUE }
                                        .thenBy { it.displayName() }
                                )
                                .take(2)
                        }
                        if (bestByCountry.isEmpty()) {
                            snackbar("Нет прокси для экспорта").show()
                            return@setNeutralButton
                        }
                        exportMultipleGroups(
                            bestByCountry,
                            "Отправка ${bestByCountry.size} прокси (по 2 лучших на страну)..."
                        )
                    }

                    .setPositiveButton("Экспорт") { _, _ ->
                        val selectedCountries = keys.filterIndexed { index, _ -> checked[index] }
                        if (selectedCountries.isEmpty()) {
                            snackbar("Вы ничего не выбрали!").show()
                            return@setPositiveButton
                        }

                        val bestByCountry = selectedCountries.flatMap { country ->
                            val countryProxies = countryMap[country].orEmpty()
                            countryProxies
                                .sortedWith(
                                    compareBy<ProxyEntity> { if (it.ping > 0) it.ping else Int.MAX_VALUE }
                                        .thenBy { it.displayName() }
                                )
                                .take(2)
                        }

                        if (bestByCountry.isEmpty()) {
                            snackbar("Нет прокси для экспорта").show()
                            return@setPositiveButton
                        }

                        val countriesLabel = selectedCountries.joinToString(", ")
                        exportMultipleGroups(
                            bestByCountry,
                            "Отправка ${bestByCountry.size} прокси (по 2 лучших на страну: $countriesLabel)..."
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun exportMultipleGroups(
        list: List<ProxyEntity>,
        msg: String
    ) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("GitHub")
            .setMessage(msg)
            .setCancelable(false)
            .show()

        runOnDefaultDispatcher {
            val syncError = syncExportToAutoPilotBestGroup(list)
            val result = if (syncError == null) {
                io.nekohasekai.sagernet.utils.GitHubExporter.exportGroup("AutoPilot Best", list)
            } else {
                GitHubExporter.ExportResult(false, syncError)
            }

            onMainDispatcher {
                dialog.dismiss()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (result.success) "Успех" else "Ошибка")
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private suspend fun getOrderedProxiesForCurrentGroup(): List<ProxyEntity> {
        val group = DataStore.currentGroup()
        var proxies = SagerDatabase.proxyDao.getByGroup(group.id)
        proxies = applyGroupOrder(proxies, group.order, group.id)
        return proxies
    }

    private suspend fun syncExportToAutoPilotBestGroup(list: List<ProxyEntity>): String? {
        return try {
            val groupName = "🚀 AutoPilot Best"
            val allGroups = SagerDatabase.groupDao.allGroups()
            var targetGroup = allGroups.find { it.name == groupName || it.displayName() == groupName }
            if (targetGroup == null) {
                val newGroup = ProxyGroup().apply {
                    name = groupName
                    type = GroupType.BASIC
                }
                val newId = SagerDatabase.groupDao.createGroup(newGroup)
                targetGroup = SagerDatabase.groupDao.getById(newId)
            }

            val bestGroup = targetGroup ?: return "Не удалось создать группу AutoPilot Best"
            val groupId = bestGroup.id

            SagerDatabase.proxyDao.deleteByGroup(groupId)

            for ((index, proxy) in list.withIndex()) {
                val created = ProfileManager.createProfile(groupId, proxy.requireBean())
                created.userOrder = index.toLong()
                created.ping = proxy.ping
                created.status = proxy.status
                created.error = proxy.error
                ProfileManager.updateProfile(created)
            }

            GroupManager.postReload(groupId)
            null
        } catch (e: Exception) {
            Logs.e("AutoPilot Best sync failed", e)
            "Ошибка синхронизации AutoPilot Best: ${e.readableMessage}"
        }
    }

}
