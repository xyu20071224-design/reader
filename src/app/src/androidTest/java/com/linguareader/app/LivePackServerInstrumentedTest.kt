package com.linguareader.app

import android.Manifest
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.WordLookup
import com.linguareader.app.packs.PackRepository
import com.linguareader.app.packs.RemotePackOrigin
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.packs.PackValidator
import com.linguareader.shared.packs.RemotePack
import com.linguareader.shared.packs.RemotePackCatalog
import com.linguareader.shared.packs.RemotePackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in real HTTPS workflow; run each phase in its own instrumentation process on emulator-5580.
 * Required args: runLivePackServer=true, livePackDevice=emulator-5580,
 * livePackAvdName=ReaderIssueVerify35. No fake connection, TLS trust override or SSH is used.
 * Phase 2 leaves a private recovery marker for phase 3; phase 3 always restores it, even on failure.
 */
@RequiresApi(29)
@RunWith(AndroidJUnit4::class)
class LivePackServerInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val marker get() = File(context.filesDir, "live-pack-server-handoff.json")

    @Test
    fun phase1RealNetworkLossKeepsPartialAndNewClientResumes() {
        assumeTrue("Live service is opt-in", arguments.getString("runLivePackServer") == "true")
        assertVerifyEmulator()
        withNetworkPermissions {
            val original = radios()
            val directory = File(context.cacheDir, "live-pack-phase1-${UUID.randomUUID()}")
            val evidence = JSONObject().put("stage", "started").put("catalog", RemotePackCatalog.DEFAULT_URL)
                .put("originalNetwork", original.json())
            val cutAt = AtomicLong(-1)
            val disconnected = AtomicBoolean(false)
            try {
                assertNotNull("Phase 1 must begin online", connectivity.activeNetwork)
                val pack = runBlocking { RemotePackClient().fetchCatalog(BuildConfig.VERSION_CODE).getOrThrow() }
                    .single { it.packId == "ecdict" }
                requireRealEcdict(pack)
                val sha = requireNotNull(pack.sha256)
                val first = runBlocking {
                    withTimeout(120_000) {
                        RemotePackClient().download(pack, directory) { done, _ ->
                            if (done >= MIB && cutAt.compareAndSet(-1, done)) {
                                // The reader is paused in this synchronous callback while real radios go down.
                                // No Cancellation/IOException is fabricated to stand in for network loss.
                                setRadios(NetworkState(false, false))
                                awaitNetwork(false)
                                disconnected.set(true)
                            }
                        }
                    }
                }
                val partial = File(directory, "$sha.part")
                val previousLength = partial.length()
                evidence.put("cutAt", cutAt.get()).put("networkActuallyGone", disconnected.get())
                    .put("firstSucceeded", first.isSuccess).put("partialLength", previousLength)
                    .put("bytes", pack.bytes).put("sha256", sha)
                    .put("failureClass", first.exceptionOrNull()?.javaClass?.name)
                writeEvidence("phase1-after-interruption", evidence)
                assertTrue("The real connection must fail while radios are disabled", first.isFailure)
                assertTrue(disconnected.get())
                assertNull(connectivity.activeNetwork)
                assertTrue("Network loss must occur after 1 MiB and before completion", cutAt.get() in MIB until pack.bytes)
                assertTrue("A content-addressed partial must survive", partial.isFile && previousLength in 1 until pack.bytes)
                assertTrue(previousLength >= cutAt.get())

                setRadios(original)
                awaitNetwork(true)
                val firstResumedProgress = AtomicLong(-1)
                val complete = runBlocking {
                    withTimeout(120_000) {
                        // New client instance, same actual content-addressed partial and default platform TLS.
                        RemotePackClient().download(pack, directory) { done, _ ->
                            firstResumedProgress.compareAndSet(-1, done)
                        }.getOrThrow()
                    }
                }
                assertTrue("A 200 restart must not be mistaken for Range resume", firstResumedProgress.get() > previousLength)
                assertEquals(pack.bytes, complete.length())
                assertEquals(sha, sha256(complete))
                assertFalse("Verified publication consumes its partial", partial.exists())
                evidence.put("stage", "complete").put("firstResumedProgress", firstResumedProgress.get())
                    .put("completeBytes", complete.length()).put("completeSha256", sha256(complete))
            } finally {
                try {
                    setRadios(original)
                    awaitNetwork(original.wifi || original.data)
                    evidence.put("restoredNetwork", radios().json())
                } finally {
                    try { writeEvidence("phase1", evidence.put("lastProgressAtCut", cutAt.get())) }
                    finally { directory.deleteRecursively() }
                }
            }
        }
    }

    @Test
    fun phase2ActualResourcePageDownloadsAndInstallsEcdict() {
        assumeTrue("Live service is opt-in", arguments.getString("runLivePackServer") == "true")
        assertVerifyEmulator()
        withNetworkPermissions {
            assertNotNull("Phase 2 must begin online", connectivity.activeNetwork)
            assertFalse("An earlier live fixture needs phase 3 recovery before reinstalling", marker.exists())
            var state: JSONObject? = null
            var keep = false
            var ownerJob: Job? = null
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var observation: Job? = null
            val downloadSeen = AtomicBoolean(false)
            val installSeen = AtomicBoolean(false)
            val percentages = ConcurrentSkipListSet<Int>()
            try {
                state = prepareFixture()
                var asset: RemotePack? = null
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val viewModel = viewModel(scenario)
                    ownerJob = viewModel.viewModelScope.coroutineContext[Job]
                    assertNotNull(ownerJob)
                    readyShelf(scenario, viewModel)
                    assertUntouchedRemoteState(viewModel)
                    openResourcePage()
                    compose.onNodeWithText(context.getString(R.string.packs_remote_none_yet)).assertExists()
                    compose.onNodeWithText(context.getString(R.string.packs_remote_load)).performScrollTo().performClick()
                    compose.waitUntil(30_000) {
                        val remote = viewModel.state.value.packs.remote
                        if (!remote.loading && remote.error != null) throw AssertionError(remote.error)
                        remote.fetched && !remote.loading && remote.assets.any { it.packId == "ecdict" }
                    }
                    assertEquals(RemotePackOrigin.SERVER, viewModel.state.value.packs.remote.origin)
                    asset = viewModel.state.value.packs.remote.assets.single { it.packId == "ecdict" }
                    requireRealEcdict(requireNotNull(asset))
                    compose.onNodeWithText(context.getString(R.string.packs_remote_source_server)).assertExists()
                    compose.onNodeWithText(requireNotNull(asset).name).performScrollTo().assertIsDisplayed()
                    observation = observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        viewModel.state.collect { snapshot ->
                            val packs = snapshot.packs
                            if (packs.remote.downloading == asset?.name) {
                                downloadSeen.set(true)
                                packs.remote.downloadPercent?.let(percentages::add)
                            }
                            if (packs.installing) installSeen.set(true)
                        }
                    }
                    // The dedicated live fixture currently serves one download row; don't click an
                    // arbitrary action if that catalogue changes and makes the selector ambiguous.
                    val download = context.getString(R.string.packs_remote_download)
                    assertEquals("Live ECDICT fixture needs an unambiguous real download action", 1,
                        compose.onAllNodesWithText(download).fetchSemanticsNodes().size)
                    compose.onNodeWithText(download).performScrollTo().performClick()
                    compose.waitUntil(180_000) {
                        val snapshot = viewModel.state.value
                        if (snapshot.message != null) throw AssertionError(snapshot.message)
                        if (snapshot.packs.remote.error != null) throw AssertionError(snapshot.packs.remote.error)
                        !snapshot.packs.installing && snapshot.packs.items.any { it.packId == "ecdict" && it.active }
                    }
                    assertInstalledRow(viewModel)
                    assertTrue("Real download state must be observed", downloadSeen.get())
                    assertTrue("Real install state must be observed", installSeen.get())
                    assertTrue("A genuine intermediate percentage must be emitted", percentages.any { it in 1..99 })
                    assertTrue("Actual completed download progress must reach 100", 100 in percentages)
                    val repository = PackRepository(context)
                    val registry = repository.reload()
                    assertEquals("ecdict", registry.activeDictionary)
                    val installed = registry.packs.single { it.packId == "ecdict" }
                    assertEquals(requireNotNull(asset).version, installed.version)
                    assertEquals(PackType.DICTIONARY, installed.type)
                    assertTrue(PackValidator.validateFiles(installed.manifest, installed.root(repository.packsRoot)).ok)
                    val dictionary = requireNotNull(repository.dictionarySource())
                    assertTrue(dictionary.isFile && dictionary.length() > 0)
                    assertTrue(dictionary.canonicalPath.startsWith(repository.packsRoot.canonicalPath + File.separator))
                    state.put("assetSha256", asset?.sha256).put("assetVersion", asset?.version)
                        .put("phase2Pid", Process.myPid()).put("dictionaryPath", dictionary.absolutePath)
                }
                // Wait for all owned IO children before preserving or restoring directories.
                joinOwner(ownerJob)
                runBlocking { observation?.cancelAndJoin() }
                state.put("phase", "INSTALLED")
                saveMarker(state)
                writeEvidence("phase2", JSONObject().put("stage", "installed").put("pid", Process.myPid())
                    .put("catalog", RemotePackCatalog.DEFAULT_URL).put("assetSha256", state.getString("assetSha256"))
                    .put("percentages", JSONArray(percentages.toList())).put("downloadSeen", downloadSeen.get())
                    .put("installSeen", installSeen.get()).put("dictionaryPath", state.getString("dictionaryPath")))
                keep = true
            } finally {
                observerScope.cancel()
                // If an uncancellable IO child exceeds this bound, preserve the private backup/marker
                // for recovery in a fresh stopped process; never restore onto a still-active writer.
                joinOwner(ownerJob)
                if (!keep && marker.exists()) restoreFixture(state ?: loadMarker())
            }
        }
    }

    @Test
    fun phase3FreshOfflineLaunchConsumesInstalledPackAndRestoresFixture() {
        assumeTrue("Live service is opt-in", arguments.getString("runLivePackServer") == "true")
        assertVerifyEmulator()
        withNetworkPermissions {
            val state = loadMarker()
            var ownerJob: Job? = null
            var dictionaryRepository: DictionaryRepository? = null
            try {
                assertEquals("Run phase 2 successfully first", "INSTALLED", state.getString("phase"))
                assertNotEquals("Root must stop phase 2's process before the offline launch", state.getInt("phase2Pid"), Process.myPid())
                awaitNetwork(false)
                assertNull("Root must disable networking before starting phase 3", connectivity.activeNetwork)
                moveBuiltInAside(state)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val viewModel = viewModel(scenario)
                    ownerJob = viewModel.viewModelScope.coroutineContext[Job]
                    readyShelf(scenario, viewModel)
                    assertUntouchedRemoteState(viewModel)
                    openResourcePage()
                    compose.waitUntil(15_000) { viewModel.state.value.packs.items.any { it.packId == "ecdict" && it.active } }
                    assertInstalledRow(viewModel)
                    val repository = PackRepository(context)
                    val source = requireNotNull(repository.dictionarySource())
                    assertTrue(source.canonicalPath.startsWith(repository.packsRoot.canonicalPath + File.separator))
                    dictionaryRepository = field(viewModel, "dictionary") as DictionaryRepository
                    val lookup = WordLookup("apple", "An apple rests here.", "An apple rests here.", 3, 0f, 0f)
                    val result = runBlocking { withTimeout(30_000) { viewModel.lookup(lookup) } }
                    assertTrue("The real local apple entry must contain 苹果", result.entry?.senses.orEmpty().any { it.text.contains("苹果") })
                    val database = field(requireNotNull(dictionaryRepository), "database") as SQLiteDatabase
                    assertEquals("The VM must actually open the package database", source.canonicalPath, File(database.path).canonicalPath)
                    assertFalse("An active pack must not recreate the built-in copy", builtInFile().exists())
                    val deadline = SystemClock.uptimeMillis() + 3_000
                    do {
                        assertNull(connectivity.activeNetwork)
                        assertUntouchedRemoteState(viewModel)
                        SystemClock.sleep(100)
                    } while (SystemClock.uptimeMillis() < deadline)
                    writeEvidence("phase3", JSONObject().put("stage", "offline-lookup-complete").put("pid", Process.myPid())
                        .put("catalog", RemotePackCatalog.DEFAULT_URL).put("resourceGeneration", remoteGeneration(viewModel))
                        .put("activeDictionaryPath", source.absolutePath).put("openedDatabasePath", database.path)
                        .put("appleContainsExpectedMeaning", true).put("builtInCopyCreated", builtInFile().exists())
                        .put("activeNetworkPresent", connectivity.activeNetwork != null))
                }
            } finally {
                try {
                    joinOwner(ownerJob)
                } catch (failure: Throwable) {
                    restoreNetworkFromState(state)
                    throw failure // Keep backups for recovery after force-stop, never race a live IO writer.
                }
                dictionaryRepository?.invalidate()
                restoreFixture(state)
            }
        }
    }

    private fun assertVerifyEmulator() {
        assertEquals("Only the disposable verification package is allowed", "com.linguareader.app.verify", context.packageName)
        assertEquals("emulator-5580", arguments.getString("livePackDevice"))
        assertEquals("ReaderIssueVerify35", arguments.getString("livePackAvdName"))
        assertTrue(Build.VERSION.SDK_INT >= 29 && Build.PRODUCT.startsWith("sdk_"))
        val avd = shell("getprop ro.boot.qemu.avd_name").trim().ifBlank { shell("getprop ro.kernel.qemu.avd_name").trim() }
        assertEquals("The AVD itself must match the task", "ReaderIssueVerify35", avd)
        val endpoint = URI(RemotePackCatalog.DEFAULT_URL)
        assertEquals("https", endpoint.scheme)
        assertTrue("Rebuild with the user-selected 25000–26000 endpoint before running live tests", endpoint.port in 25000..26000)
    }

    private inline fun withNetworkPermissions(block: () -> Unit) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.READ_PHONE_STATE)
        try { block() } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private data class NetworkState(val wifi: Boolean, val data: Boolean) {
        fun json() = JSONObject().put("wifi", wifi).put("data", data)
    }
    private fun radios() = NetworkState(
        context.getSystemService(WifiManager::class.java).isWifiEnabled,
        context.getSystemService(TelephonyManager::class.java).isDataEnabled)
    private fun setRadios(state: NetworkState) {
        shell("svc wifi ${if (state.wifi) "enable" else "disable"}")
        shell("svc data ${if (state.data) "enable" else "disable"}")
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (radios() != state && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals("The real radio settings did not settle", state, radios())
    }
    private fun awaitNetwork(present: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while ((connectivity.activeNetwork != null) != present && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertEquals("The real default network did not reach the requested state", present, connectivity.activeNetwork != null)
    }

    private fun requireRealEcdict(pack: RemotePack) {
        assertEquals("ecdict", pack.packId)
        assertEquals(PackType.DICTIONARY, pack.type)
        assertEquals(EXPECTED_BYTES, pack.bytes)
        assertEquals(EXPECTED_SHA, pack.sha256)
    }
    private fun viewModel(scenario: ActivityScenario<MainActivity>): AppViewModel {
        lateinit var result: AppViewModel
        scenario.onActivity { result = ViewModelProvider(it)[AppViewModel::class.java] }
        return result
    }
    private fun readyShelf(scenario: ActivityScenario<MainActivity>, viewModel: AppViewModel) {
        compose.waitUntil(20_000) { !viewModel.state.value.loading }
        scenario.onActivity { viewModel.dismissLaunchPrompt() }
        compose.onNodeWithContentDescription(context.getString(R.string.storage_title)).assertIsDisplayed()
    }
    private fun openResourcePage() {
        compose.onNodeWithContentDescription(context.getString(R.string.storage_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.storage_packs_manage)).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.packs_title)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertInstalledRow(viewModel: AppViewModel) {
        val item = viewModel.state.value.packs.items.single { it.packId == "ecdict" && it.active }
        // Match PacksSheet.packDisplayName; UI labels remain resource-driven in either locale.
        val name = if (Locale.getDefault().language.startsWith("zh")) item.nameZh.ifBlank { item.nameEn }
            else item.nameEn.ifBlank { item.nameZh }
        compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.packs_active)).assertIsDisplayed()
    }
    private fun remoteGeneration(viewModel: AppViewModel): Long {
        val repository = field(viewModel, "remotePacksRepository")
        val snapshot = (field(repository, "state") as AtomicReference<*>).get()
        return (field(requireNotNull(snapshot), "generation") as Number).toLong()
    }
    private fun assertUntouchedRemoteState(viewModel: AppViewModel) {
        val remote = viewModel.state.value.packs.remote
        assertFalse(remote.loading); assertFalse(remote.fetched)
        assertTrue(remote.assets.isEmpty()); assertNull(remote.error); assertNull(remote.downloading)
        // A real repository generation is stronger than guessing from zero offline byte counters:
        // any load/invalidation, even one that failed before observation, advances this value.
        assertEquals("Opening the page/startup must not initiate catalog discovery", 0L, remoteGeneration(viewModel))
    }
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        requireNotNull(it.get(owner)) { "Expected initialized $name" }
    }
    private fun joinOwner(job: Job?) { if (job != null) runBlocking { withTimeout(60_000) { job.cancelAndJoin() } } }

    private fun prepareFixture(): JSONObject {
        val nonce = UUID.randomUUID().toString()
        val root = File(context.filesDir, "live-pack-server-state-$nonce")
        assertTrue(root.mkdirs())
        val prefs = JSONArray()
        for ((name, key) in listOf("ai_settings" to "enabled", "update_settings" to "auto_check_enabled")) {
            val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.put(JSONObject().put("name", name).put("key", key).put("type", "boolean")
                .put("had", store.contains(key)).put("value", store.getBoolean(key, false)))
        }
        val sync = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
        prefs.put(JSONObject().put("name", "sync_settings").put("key", "settings").put("type", "string")
            .put("had", sync.contains("settings")).put("value", sync.getString("settings", "")))
        val state = JSONObject().put("owner", OWNER).put("package", context.packageName).put("nonce", nonce)
            .put("phase", "PREPARING").put("network", radios().json()).put("prefs", prefs)
            .put("packsHad", File(context.filesDir, "packs").exists())
            .put("remoteCacheHad", File(context.cacheDir, "remote-packs").exists())
            .put("vocabularyHad", File(context.filesDir, "vocabulary.json").isFile)
            .put("vocabularyBackedUp", false)
        saveMarker(state)
        val vocabulary = File(context.filesDir, "vocabulary.json")
        if (vocabulary.isFile) {
            vocabulary.copyTo(File(root, "vocabulary-original"))
            assertEquals(sha256(vocabulary), sha256(File(root, "vocabulary-original")))
            state.put("vocabularyBackedUp", true); saveMarker(state)
        }
        moveIfPresent(File(context.filesDir, "packs"), File(root, "packs-original"))
        moveIfPresent(File(context.cacheDir, "remote-packs"), File(root, "remote-cache-original"))
        for (i in 0 until prefs.length()) {
            val p = prefs.getJSONObject(i)
            val editor = context.getSharedPreferences(p.getString("name"), Context.MODE_PRIVATE).edit()
            if (p.getString("type") == "boolean") editor.putBoolean(p.getString("key"), false)
            else editor.putString("settings", (p.optString("value").takeIf { it.isNotEmpty() }?.let(::JSONObject) ?: JSONObject()).put("enabled", false).toString())
            assertTrue(editor.commit())
        }
        state.put("phase", "PREPARED"); saveMarker(state)
        return state
    }
    private fun stateRoot(state: JSONObject): File {
        assertEquals(OWNER, state.getString("owner")); assertEquals(context.packageName, state.getString("package"))
        val nonce = state.getString("nonce")
        assertEquals(UUID.fromString(nonce).toString(), nonce)
        return File(context.filesDir, "live-pack-server-state-$nonce").also {
            assertEquals(File(context.filesDir.canonicalFile, it.name), it.canonicalFile)
        }
    }
    private fun moveIfPresent(source: File, target: File) {
        if (source.exists()) { assertFalse(target.exists()); assertTrue("Backup rename failed: ${source.name}", source.renameTo(target)) }
    }
    private fun saveMarker(state: JSONObject) {
        stateRoot(state)
        val temporary = File(context.filesDir, "${marker.name}.new")
        temporary.outputStream().use { it.write(state.toString().toByteArray()); it.fd.sync() }
        assertTrue("Cannot commit private recovery marker", temporary.renameTo(marker))
    }
    private fun loadMarker(): JSONObject {
        assertTrue("Phase 2 recovery marker is required", marker.isFile)
        return JSONObject(marker.readText()).also { stateRoot(it) }
    }
    private fun builtInFile() = File(context.filesDir, "dictionary/ecdict-v2.sqlite")
    private fun moveBuiltInAside(state: JSONObject) {
        val root = stateRoot(state)
        val entries = state.optJSONArray("builtIn") ?: JSONArray().also { rows ->
            for (name in BUILTIN_NAMES) rows.put(JSONObject().put("name", name)
                .put("had", File(builtInFile().parentFile, name).exists()))
            state.put("builtIn", rows); saveMarker(state)
        }
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val name = entry.getString("name")
            assertTrue(name in BUILTIN_NAMES)
            val current = File(builtInFile().parentFile, name)
            val backup = File(root, "builtin-$name")
            if (backup.exists() || !entry.getBoolean("had")) {
                // A retry must keep the first snapshot, not adopt a fallback copy made by a failed probe.
                if (current.exists()) assertTrue(current.delete())
            } else {
                assertTrue("The original built-in copy must still be available", current.isFile)
                moveIfPresent(current, backup)
            }
        }
        assertFalse(builtInFile().exists())
    }
    private fun restoreFixture(state: JSONObject) {
        val root = stateRoot(state)
        if (state.optString("phase") == "RESTORED") {
            assertTrue(root.deleteRecursively()); assertTrue(marker.delete()); return
        }
        fun restoreTree(current: File, backup: File, originallyPresent: Boolean) {
            if (backup.exists()) { assertTrue(current.deleteRecursively()); assertTrue(backup.renameTo(current)) }
            else if (!originallyPresent) assertTrue(current.deleteRecursively())
            else assertTrue("Original or backup must remain available", current.exists())
        }
        try {
        restoreTree(File(context.filesDir, "packs"), File(root, "packs-original"), state.getBoolean("packsHad"))
        restoreTree(File(context.cacheDir, "remote-packs"), File(root, "remote-cache-original"), state.getBoolean("remoteCacheHad"))
        val entries = state.optJSONArray("builtIn") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            assertTrue(entry.getString("name") in BUILTIN_NAMES)
            restoreTree(File(builtInFile().parentFile, entry.getString("name")), File(root, "builtin-${entry.getString("name")}"), entry.getBoolean("had"))
        }
        val vocabulary = File(context.filesDir, "vocabulary.json")
        if (state.getBoolean("vocabularyBackedUp")) File(root, "vocabulary-original").copyTo(vocabulary, overwrite = true)
        else if (!state.getBoolean("vocabularyHad")) vocabulary.delete()
        val prefs = state.getJSONArray("prefs")
        for (i in 0 until prefs.length()) {
            val p = prefs.getJSONObject(i)
            val editor = context.getSharedPreferences(p.getString("name"), Context.MODE_PRIVATE).edit()
            if (!p.getBoolean("had")) editor.remove(p.getString("key"))
            else if (p.getString("type") == "boolean") editor.putBoolean(p.getString("key"), p.getBoolean("value"))
            else editor.putString(p.getString("key"), p.getString("value"))
            assertTrue(editor.commit())
        }
        } finally { restoreNetworkFromState(state) }
        state.put("phase", "RESTORED"); saveMarker(state)
        assertTrue(root.deleteRecursively()); assertTrue(marker.delete())
    }
    private fun restoreNetworkFromState(state: JSONObject) {
        val network = state.getJSONObject("network")
        setRadios(NetworkState(network.getBoolean("wifi"), network.getBoolean("data")))
        awaitNetwork(network.getBoolean("wifi") || network.getBoolean("data"))
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun writeEvidence(name: String, value: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "live-pack-server").apply { mkdirs() }
        File(directory, "$name.json").writeText(value.toString(2))
    }
    private companion object {
        val BUILTIN_NAMES = listOf("ecdict-v2.sqlite", "ecdict-v2.sqlite-wal", "ecdict-v2.sqlite-shm", "ecdict-v2.sqlite-journal")
        const val OWNER = "LivePackServerInstrumentedTest-v1"
        const val MIB = 1024L * 1024L
        const val EXPECTED_BYTES = 26_738_571L
        const val EXPECTED_SHA = "1b1b119dadbfef93946c8ee2a491a8c85fe11b2b43e233b25bd3c04c41b0a7d0"
    }
}
