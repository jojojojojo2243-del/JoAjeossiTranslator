package com.joajeossi.translator

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 줌 앱처럼 알아서 업데이트한다.
 *
 * 1. 앱을 켜면 GitHub 최신 릴리스(태그 "v<버전번호>")를 확인한다.
 * 2. 지금보다 새 버전이면 묻지 않고 뒤에서 APK 를 받는다.
 * 3. 받은 뒤 바로 설치한다.
 *    - Android 12 이상이고, 이 앱이 스스로 업데이트한 적이 있으면 아무것도 안 눌러도 설치된다.
 *    - 그 밖의 경우에는 안드로이드가 "업데이트할까요?"를 한 번 묻는다 (보안상 꼭 필요).
 *
 * 화면의 [업데이트 확인] 버튼을 누르면 같은 일을 바로 하고, 결과를 알려 준다.
 */
object AppUpdater {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/jojojojojo2243-del/JoAjeossiTranslator/releases/latest"
    private const val ACTION_INSTALL_RESULT = "com.joajeossi.translator.INSTALL_RESULT"

    @Volatile private var busy = false

    /** 화면에 지금 상태를 보여 주고 싶을 때 쓰는 통로 (예: "새 버전 받는 중…") */
    var onStatus: ((String) -> Unit)? = null

    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** @param manual 사람이 [업데이트 확인]을 누른 경우 true: 결과를 꼭 알려 준다. */
    fun checkForUpdate(activity: Activity, manual: Boolean = false) {
        if (busy) {
            if (manual) toast(activity, "이미 업데이트를 받고 있어요.")
            return
        }
        busy = true
        if (manual) status(activity, "새 버전 확인 중…")
        Thread {
            try {
                val release = fetchLatestRelease()
                when {
                    release == null -> {
                        busy = false
                        if (manual) status(activity, "새 버전 정보를 찾지 못했어요. (아직 올라간 버전이 없어요)")
                    }
                    release.versionCode <= currentVersionCode(activity) -> {
                        busy = false
                        if (manual) status(activity, "최신 버전이에요. (${currentVersionName(activity)})")
                    }
                    else -> activity.runOnUiThread { startUpdate(activity, release) }
                }
            } catch (e: Exception) {
                busy = false
                if (manual) status(activity, "업데이트 확인 실패: 인터넷 연결을 확인하세요.")
            }
        }.start()
    }

    private class Release(val versionCode: Long, val name: String, val apkUrl: String)

    private fun fetchLatestRelease(): Release? {
        val conn = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = tag.removePrefix("v").toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(code, json.optString("name", tag), asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun startUpdate(activity: Activity, release: Release) {
        // Android 8 이상은 "이 앱에서 설치 허용"을 처음 한 번 켜 줘야 한다.
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            busy = false
            if (activity.isFinishing) return
            AlertDialog.Builder(activity)
                .setTitle("새 버전이 있어요 (${release.name})")
                .setMessage(
                    "자동 업데이트를 하려면 처음 한 번만 '이 출처 허용'을 켜 주세요.\n\n" +
                        "켠 다음 뒤로 돌아오면 알아서 업데이트해요."
                )
                .setPositiveButton("설정 열기") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("나중에", null)
                .show()
            return
        }

        status(activity, "새 버전(${release.name}) 받는 중…")
        val app = activity.applicationContext
        Thread {
            try {
                val apk = download(release.apkUrl, File(app.cacheDir, "update.apk")) { percent ->
                    status(activity, "새 버전(${release.name}) 받는 중… $percent%")
                }
                status(activity, "새 버전 설치 중…")
                install(app, apk)
            } catch (e: Exception) {
                status(activity, "업데이트 받기 실패: 인터넷 연결을 확인하세요.")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var lastPercent = -1
            target.delete()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent >= lastPercent + 5) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** 안드로이드 설치기(PackageInstaller)로 설치한다. 조건이 맞으면 묻지 않고 설치된다. */
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun status(activity: Activity, text: String) {
        activity.runOnUiThread { onStatus?.invoke(text) }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** 설치 결과를 받는 곳. 사용자 확인이 필요하면 안드로이드의 "업데이트할까요?" 화면을 띄운다. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 새 버전으로 바뀌면서 앱이 다시 시작된다
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(context, "업데이트 설치 실패 $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
