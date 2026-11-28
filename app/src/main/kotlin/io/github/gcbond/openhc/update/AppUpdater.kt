package io.github.gcbond.openhc.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.update.AppUpdateLogic.CheckFormat
import io.github.gcbond.openhc.update.AppUpdateLogic.DownloadRoute
import io.github.gcbond.openhc.update.AppUpdateLogic.ReleaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@Immutable
internal sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String,
        val checkedAtMillis: Long,
        val route: DownloadRoute,
    ) : UpdateState()

    data class Available(
        val release: ReleaseInfo,
        val route: DownloadRoute,
    ) : UpdateState()

    data class Downloading(
        val release: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val route: DownloadRoute,
        val switchedToProxy: Boolean,
    ) : UpdateState()

    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkPath: String,
        val route: DownloadRoute,
        val verifiedSha256: Boolean,
    ) : UpdateState()

    data class Failed(
        val message: String,
        val release: ReleaseInfo? = null,
    ) : UpdateState()
}

/**
 * 应用内版本检查与覆盖安装器：
 * - 检查更新与下载安装包均采用「先测 GitHub 原始地址，直连不通再自动切换 https://ghfast.top 代理加速」策略；
 * - 下载到私有 `cache/updates/` 目录并校对 SHA-256（如存在 `checksums.txt`），随后通过 [FileProvider] 拉起系统包安装器覆盖安装。
 */
internal object AppUpdater {
    private const val TAG = "OpenHC-Update"
    private const val UPDATES_DIR = "updates"
    private const val DIRECT_CONNECT_TIMEOUT_MS = 4_500
    private const val DIRECT_READ_TIMEOUT_MS = 10_000
    private const val PROXY_CONNECT_TIMEOUT_MS = 8_000
    private const val PROXY_READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 6
    private const val PROGRESS_INTERVAL_MS = 120L
    private const val MAX_META_BYTES = 512 * 1024

    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val checking = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    @Volatile
    private var downloadCancel: AtomicBoolean? = null

    /** 进入设置页时若尚未检查过，后台自动静默查一次。 */
    fun checkIfNeeded() {
        if (mutableState.value !is UpdateState.Idle) return
        checkNow()
    }

    /** 主动向 GitHub 检查最新版本（直连优先，不通自动切 ghfast.top）。 */
    fun checkNow() {
        if (downloading.get()) return
        if (!checking.compareAndSet(false, true)) return
        mutableState.value = UpdateState.Checking
        Thread({
            try {
                performCheck()
            } finally {
                checking.set(false)
            }
        }, "OpenHC-UpdateCheck").apply {
            isDaemon = true
            start()
        }
    }

    /** 下载指定版本 APK：先测原始 GitHub 下载链接，连不上自动切换 https://ghfast.top 加速。 */
    fun download(context: Context, release: ReleaseInfo) {
        if (!downloading.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        val cancel = AtomicBoolean(false)
        downloadCancel = cancel
        mutableState.value = UpdateState.Downloading(
            release = release,
            downloadedBytes = 0L,
            totalBytes = release.apkSizeBytes,
            route = DownloadRoute.DIRECT,
            switchedToProxy = false,
        )
        Thread({
            try {
                performDownload(app, release, cancel)
            } finally {
                downloadCancel = null
                downloading.set(false)
            }
        }, "OpenHC-UpdateDownload").apply {
            isDaemon = true
            start()
        }
    }

    fun cancelDownload() {
        downloadCancel?.set(true)
    }

    /** 安卓 8.0 起本应用是否已获「安装未知应用」权限。 */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「允许安装未知应用」系统授权页（安卓 8.0+）。 */
    fun unknownSourcesIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 调起系统包安装器覆盖安装已下载的 APK。返回 false 表示系统无安装器或文件不存在。 */
    fun launchInstaller(context: Context, apkPath: String): Boolean {
        val file = File(apkPath)
        if (!file.isFile || file.length() <= 0L) return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "调起安装器失败：${e.message}")
            false
        }
    }

    /** 用系统浏览器打开 GitHub Releases 页面；无浏览器车机返回 false。 */
    fun openReleasesPage(context: Context, url: String = AppUpdateLogic.RELEASES_PAGE_URL): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // ---------------------------------------------------------------- 网络执行

    private fun performCheck() {
        var lastError = "无法连接 GitHub"
        for (candidate in AppUpdateLogic.checkCandidates()) {
            try {
                val (connectMs, readMs) = timeoutsFor(candidate.route)
                val body = fetchText(candidate.url, connectMs, readMs)
                val release = when (candidate.format) {
                    CheckFormat.JSON_API -> AppUpdateLogic.parseLatestReleaseJson(body)
                    CheckFormat.ATOM_FEED -> AppUpdateLogic.parseLatestReleaseAtom(body)
                }
                if (release != null) {
                    val current = BuildConfig.VERSION_NAME
                    val newer = AppUpdateLogic.isUpdateAvailable(current, release)
                    Log.i(TAG, "版本检查完成（${candidate.route}）：当前 $current，最新 ${release.versionName}，有更新=$newer")
                    mutableState.value = if (newer) {
                        UpdateState.Available(release, candidate.route)
                    } else {
                        UpdateState.UpToDate(current, release.versionName, System.currentTimeMillis(), candidate.route)
                    }
                    return
                }
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                Log.w(TAG, "检查更新尝试失败（${candidate.route} ${candidate.format}）：$lastError")
            }
        }
        mutableState.value = UpdateState.Failed("检查更新失败：$lastError")
    }

    private fun performDownload(app: Context, release: ReleaseInfo, cancel: AtomicBoolean) {
        val dir = File(app.cacheDir, UPDATES_DIR).apply { mkdirs() }
        val partFile = File(dir, "${release.apkName}.part")
        val targetFile = File(dir, release.apkName)
        var lastError = "下载失败"
        var switchedToProxy = false

        for ((route, url) in AppUpdateLogic.downloadCandidates(release.apkUrl)) {
            if (cancel.get()) {
                partFile.delete()
                mutableState.value = UpdateState.Available(release, DownloadRoute.DIRECT)
                return
            }
            if (route == DownloadRoute.PROXY) {
                switchedToProxy = true
                Log.i(TAG, "原始地址不可达，已切换 ghfast.top 代理加速下载：${release.apkName}")
            }
            mutableState.value = UpdateState.Downloading(
                release = release,
                downloadedBytes = 0L,
                totalBytes = release.apkSizeBytes,
                route = route,
                switchedToProxy = switchedToProxy,
            )
            try {
                val (connectMs, readMs) = timeoutsFor(route)
                val sha256 = streamToFile(url, partFile, connectMs, readMs, cancel) { downloaded, total ->
                    val effectiveTotal = if (total > 0L) total else release.apkSizeBytes
                    mutableState.value = UpdateState.Downloading(
                        release = release,
                        downloadedBytes = downloaded,
                        totalBytes = effectiveTotal,
                        route = route,
                        switchedToProxy = switchedToProxy,
                    )
                }
                if (cancel.get()) {
                    partFile.delete()
                    mutableState.value = UpdateState.Available(release, route)
                    return
                }
                // 若发布附带 checksums.txt，顺带做 SHA-256 完整性校验。
                val expectedSha = release.checksumUrl?.let { fetchExpectedChecksum(it, release.apkName, preferProxy = route == DownloadRoute.PROXY) }
                if (expectedSha != null && !expectedSha.equals(sha256, ignoreCase = true)) {
                    partFile.delete()
                    lastError = "安装包 SHA-256 校验不一致"
                    Log.w(TAG, "$lastError（route=$route，期望=${expectedSha.take(12)}，实际=${sha256.take(12)}）")
                    continue
                }
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                cleanupOldApks(dir, keepName = targetFile.name)
                Log.i(TAG, "安装包下载完成（$route，${targetFile.length()} 字节，校验=${expectedSha != null}）")
                mutableState.value = UpdateState.ReadyToInstall(
                    release = release,
                    apkPath = targetFile.absolutePath,
                    route = route,
                    verifiedSha256 = expectedSha != null,
                )
                return
            } catch (e: Exception) {
                partFile.delete()
                if (cancel.get()) {
                    mutableState.value = UpdateState.Available(release, route)
                    return
                }
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                Log.w(TAG, "下载尝试失败（$route）：$lastError")
            }
        }
        mutableState.value = UpdateState.Failed("下载失败（直连与 ghfast.top 均不可用）：$lastError", release)
    }

    private fun fetchExpectedChecksum(checksumUrl: String, apkName: String, preferProxy: Boolean): String? {
        val candidates = AppUpdateLogic.downloadCandidates(checksumUrl)
        val ordered = if (preferProxy) candidates.asReversed() else candidates
        for ((route, url) in ordered) {
            try {
                val (connectMs, readMs) = timeoutsFor(route)
                val text = fetchText(url, connectMs, readMs)
                val parsed = AppUpdateLogic.parseChecksumFor(text, apkName)
                if (parsed != null) return parsed
            } catch (_: Exception) {
                // 校验文件拉取失败时尝试另一条路线；两条都失败时不阻断安装。
            }
        }
        return null
    }

    private fun cleanupOldApks(dir: File, keepName: String) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name != keepName) f.delete()
        }
    }

    private fun timeoutsFor(route: DownloadRoute): Pair<Int, Int> = when (route) {
        DownloadRoute.DIRECT -> DIRECT_CONNECT_TIMEOUT_MS to DIRECT_READ_TIMEOUT_MS
        DownloadRoute.PROXY -> PROXY_CONNECT_TIMEOUT_MS to PROXY_READ_TIMEOUT_MS
    }

    @Throws(IOException::class)
    private fun fetchText(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val conn = openWithRedirects(url, connectTimeoutMs, readTimeoutMs, accept = "application/vnd.github+json, application/atom+xml, text/plain, */*")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            BufferedInputStream(conn.inputStream).use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_META_BYTES) throw IOException("响应过大")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun streamToFile(
        url: String,
        dest: File,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        cancel: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val conn = openWithRedirects(url, connectTimeoutMs, readTimeoutMs, accept = "application/vnd.android.package-archive, application/octet-stream, */*")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: conn.contentLength.toLong()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReport = 0L
            BufferedInputStream(conn.inputStream, 32 * 1024).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancel.get()) throw IOException("已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
            if (downloaded <= 0L) throw IOException("下载内容为空")
            onProgress(downloaded, if (total > 0L) total else downloaded)
            digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** 手动跟随 301/302/303/307/308 跨主机重定向（github.com → release-assets.githubusercontent.com）。 */
    @Throws(IOException::class)
    private fun openWithRedirects(
        startUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String,
    ): HttpURLConnection {
        var current = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OpenHC/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("重定向缺少目标地址")
                current = URL(URL(current), location).toString()
                continue
            }
            return conn
        }
        throw IOException("重定向次数过多")
    }
}
