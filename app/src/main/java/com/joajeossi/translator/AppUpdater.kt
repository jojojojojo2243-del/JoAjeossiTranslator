package com.joajeossi.translator

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱을 켤 때 GitHub 최신 릴리스를 확인해서, 지금 설치된 것보다 새 버전이 있으면
 * 다운로드 후 설치 화면을 띄워 준다.
 *
 * 릴리스 태그는 "v<versionCode>" 형식(예: v105)이고, APK 파일이 첨부되어 있다.
 * (.github/workflows/android.yml 이 main 에 올라갈 때마다 자동으로 만든다)
 */
object AppUpdater {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/jojojojojo2243-del/joajeossitranslator/releases/latest"
    private const val APK_FILE_NAME = "JoAjeossiTranslator-update.apk"

    fun checkForUpdate(activity: Activity) {
        Thread {
            try {
                val release = fetchLatestRelease() ?: return@Thread
                if (release.versionCode > currentVersionCode(activity)) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing) showUpdateDialog(activity, release)
                    }
                }
            } catch (_: Exception) {
                // 인터넷이 안 되거나 GitHub 응답이 없으면 조용히 넘어간다.
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
                    return Release(
                        code,
                        json.optString("name", tag),
                        asset.getString("browser_download_url")
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun showUpdateDialog(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("새 버전이 있어요")
            .setMessage("${release.name}\n\n지금 업데이트할까요?")
            .setPositiveButton("업데이트") { _, _ -> startUpdate(activity, release) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun startUpdate(activity: Activity, release: Release) {
        // Android 8 이상은 "이 앱에서 설치 허용"을 한 번 켜 줘야 한다.
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                "'이 출처 허용'을 켜고 돌아오면 업데이트를 다시 물어볼게요.",
                Toast.LENGTH_LONG
            ).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        download(activity, release)
    }

    private fun download(activity: Activity, release: Release) {
        val app = activity.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // 예전에 받아둔 파일이 있으면 지운다.
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(APK_FILE_NAME)?.delete()

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("조아저씨 실시간 통역 업데이트")
            .setDescription(release.name)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
        val id = dm.enqueue(request)
        Toast.makeText(activity, "새 버전을 받는 중이에요…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                install(app, dm, id)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    private fun install(context: Context, dm: DownloadManager, id: Long) {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 24) {
            dm.getUriForDownloadedFile(id)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.resolve(APK_FILE_NAME)?.let { Uri.fromFile(it) }
        }
        if (uri == null) {
            Toast.makeText(context, "업데이트 파일을 받지 못했어요.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }
}
