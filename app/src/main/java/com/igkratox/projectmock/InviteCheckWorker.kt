package com.igkratox.projectmock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class InviteCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val username = prefs.getString(KEY_STUDENT_USERNAME, "")?.trim().orEmpty()
        if (username.isEmpty()) return Result.success()

        return try {
            val encoded = URLEncoder.encode(username, "UTF-8")
            val url = URL("$FIREBASE_DB_URL/studentInvites/$encoded.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText().trim()
                if (body.isNotEmpty() && body != "null") {
                    val json = JSONObject(body)
                    val now = System.currentTimeMillis()
                    val seen = prefs.getStringSet(KEY_SEEN_INVITES, emptySet())?.toMutableSet() ?: mutableSetOf()
                    var changed = false

                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val inv = json.optJSONObject(k) ?: continue
                        val rid = inv.optString("rid", "")
                        val expiresAt = inv.optLong("expiresAt", 0L)
                        val ts = inv.optLong("ts", 0L)
                        if (rid.isNotEmpty() && expiresAt > now) {
                            val invKey = "${rid}_${ts}"
                            if (!seen.contains(invKey)) {
                                seen.add(invKey)
                                changed = true
                                val examName = inv.optString("examName", "Live Exam")
                                val senseiName = inv.optString("examinerName", "Sensei")
                                postInviteNotification(examName, senseiName, rid)
                            }
                        }
                    }

                    if (changed) {
                        val trimmed = if (seen.size > 40) seen.toList().takeLast(40).toSet() else seen
                        prefs.edit().putStringSet(KEY_SEEN_INVITES, trimmed).apply()
                    }
                }
            }
            conn.disconnect()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postInviteNotification(examName: String, senseiName: String, rid: String) {
        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "exam_invites"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(chId, "Live Exam Invites", NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
            val openUrl = "${BASE_URL}#live=$rid"
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(openUrl)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                applicationContext,
                rid.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(applicationContext, chId)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle("📨 Live Exam Invite: $examName")
                .setContentText("$senseiName tomake invite korechen! Tap kore 5 min er moddhe Join dao.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(rid.hashCode(), notif)
        } catch (_: Exception) { }
    }

    companion object {
        const val PREFS_NAME = "shiken_prefs"
        const val KEY_STUDENT_USERNAME = "student_username"
        const val KEY_SEEN_INVITES = "seen_invite_keys"
        const val BASE_URL = "https://igkratox.github.io/mock-options-without-socials/"
        const val FIREBASE_DB_URL = "https://mock-exam-live-5c966-default-rtdb.asia-southeast1.firebasedatabase.app"
    }
}
