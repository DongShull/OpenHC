package io.github.gcbond.openhc.wifi

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.gcbond.openhc.core.common.adb.AdbAuth
import io.github.gcbond.openhc.core.common.adb.AdbClient
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 「连接时暂停车机 WiFi 扫描」的开关和待恢复记号（单独一个 SharedPreferences 文件）。 */
class ScanPausePreferences(context: Context) : ScanPauseJournal {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 设置开关，默认关。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** 同步写盘：暂停前必须先落盘，进程随时可能没了。 */
    override var pending: Boolean
        get() = prefs.getBoolean(KEY_PENDING, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_PENDING, value) }

    private companion object {
        const val FILE = "openhc-scan-pause"
        const val KEY_ENABLED = "enabled"
        const val KEY_PENDING = "pending"
    }
}

/**
 * 经车机本机的网络 ADB（127.0.0.1:5555，只连本机）以 shell 身份跑 [ScanSwitchMain]。ADB 钥匙是本应用自己生成的
 * 2048 位 RSA，存在应用私有目录（不参与备份）；车主在车机上点过「允许」之后，后台使用不再弹框。
 */
internal class LocalAdbScanSwitch(context: Context) : ScanSwitchRunner {
    private val app = context.applicationContext

    override fun run(command: String, ask: Boolean): ScanSwitchResult {
        val keys = try {
            loadKeys()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "ADB 钥匙读不了：${e.javaClass.simpleName}")
            return ScanSwitchResult.FAILED
        } catch (e: IOException) {
            Log.w(TAG, "ADB 钥匙读不了：${e.javaClass.simpleName}")
            return ScanSwitchResult.FAILED
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(InetAddress.getByName(LOCALHOST), PORT), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            socket.close()
            return ScanSwitchResult.ADB_OFF
        }
        return socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.soTimeout = if (ask) APPROVAL_TIMEOUT_MS else HANDSHAKE_TIMEOUT_MS
                val client = AdbClient(BufferedInputStream(s.getInputStream()), s.getOutputStream(), keys, KEY_COMMENT)
                when (client.connect(mayAsk = ask)) {
                    AdbClient.Access.READY -> Unit
                    AdbClient.Access.NOT_APPROVED -> return ScanSwitchResult.NOT_APPROVED
                    AdbClient.Access.TLS_ONLY -> return ScanSwitchResult.TLS_ONLY
                    AdbClient.Access.FAILED -> return ScanSwitchResult.FAILED
                }
                s.soTimeout = COMMAND_TIMEOUT_MS
                val output = client.shell(ScanSwitchCommand.line(app.applicationInfo.sourceDir, command))
                ScanSwitchCommand.parse(output)
            } catch (e: IOException) {
                Log.w(TAG, "车机 ADB 跑命令出错：${e.javaClass.simpleName}")
                ScanSwitchResult.FAILED
            }
        }
    }

    @Synchronized
    private fun loadKeys(): KeyPair {
        val dir = File(app.noBackupFilesDir, KEY_DIR)
        val private = File(dir, "key")
        val public = File(dir, "key.pub")
        val factory = KeyFactory.getInstance("RSA")
        if (private.isFile && public.isFile) {
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(public.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())),
            )
        }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("建不了钥匙目录")
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(AdbAuth.KEY_BITS) }.generateKeyPair()
        private.writeBytes(pair.private.encoded)
        public.writeBytes(pair.public.encoded)
        Log.i(TAG, "生成了本应用的 ADB 钥匙")
        return pair
    }

    private companion object {
        const val TAG = "ScanPause"
        const val LOCALHOST = "127.0.0.1"
        const val PORT = 5555
        const val CONNECT_TIMEOUT_MS = 2_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000

        /** 等车主在车机上点「允许」。 */
        const val APPROVAL_TIMEOUT_MS = 60_000

        /** 小程序 10 秒内自己退出，这里多留一些启动时间。 */
        const val COMMAND_TIMEOUT_MS = 20_000
        const val KEY_DIR = "adb"
        const val KEY_COMMENT = "OpenHC"
    }
}

/** 单个后台线程（守护线程）。 */
private class ExecutorWorker : ScanPauseWorker {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "openhc-scan-pause").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) {
        executor.execute(guard(task))
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
        val future = executor.schedule(guard(task), delayMillis, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    private fun guard(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (e: RuntimeException) {
            Log.e("ScanPause", "暂停车机 WiFi 扫描出错", e)
        }
    }
}

internal fun createScanPause(context: Context): ScanPause {
    val prefs = ScanPausePreferences(context)
    return ScanPause(
        runner = LocalAdbScanSwitch(context),
        journal = prefs,
        enabled = { prefs.enabled },
        worker = ExecutorWorker(),
        log = { Log.i("ScanPause", it) },
    )
}
