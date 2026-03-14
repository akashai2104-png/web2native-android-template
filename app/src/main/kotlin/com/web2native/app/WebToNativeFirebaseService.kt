package com.web2native.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

@Keep
// TEMPLATE_MARKER:WebToNativeFirebaseService_v2
class WebToNativeFirebaseService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "W2N_FCM"

        @JvmStatic
        @Keep
        fun ensureTokenRegistered() {
            Log.i(TAG, "=== PUSH DEBUG ===")
            Log.i(TAG, "API_BASE_URL=${BuildConfig.API_BASE_URL}")
            Log.i(TAG, "PROJECT_ID=${BuildConfig.PROJECT_ID}")
            Log.i(TAG, "SUPABASE_ANON_KEY length=${BuildConfig.SUPABASE_ANON_KEY.length}")
            Log.i(TAG, "=== END PUSH DEBUG ===")
            requestTokenWithRetry(attemptsLeft = 3)
        }

        @Keep
        private fun requestTokenWithRetry(attemptsLeft: Int) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "Proactive FCM token received")
                    registerToken(token)
                } else {
                    Log.w(TAG, "Failed to get FCM token", task.exception)
                    if (attemptsLeft > 1) {
                        Handler(Looper.getMainLooper()).postDelayed(
                            { requestTokenWithRetry(attemptsLeft - 1) },
                            3500L
                        )
                    }
                }
            }
        }

        @Keep
        private fun registerToken(token: String) {
            Thread {
                try {
                    val apiBaseUrl = BuildConfig.API_BASE_URL
                    if (apiBaseUrl.isNullOrEmpty()) {
                        Log.w(TAG, "API_BASE_URL not configured, skipping token registration")
                        return@Thread
                    }
                    val projectId = BuildConfig.PROJECT_ID
                    if (projectId.isNullOrEmpty()) {
                        Log.w(TAG, "PROJECT_ID not configured, skipping token registration")
                        return@Thread
                    }
                    val anonKey = BuildConfig.SUPABASE_ANON_KEY
                    if (anonKey.isNullOrEmpty()) {
                        Log.w(TAG, "SUPABASE_ANON_KEY not configured, skipping token registration")
                        return@Thread
                    }

                    val url = URL("$apiBaseUrl/functions/v1/register-push-token")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("apikey", anonKey)
                    conn.setRequestProperty("Authorization", "Bearer $anonKey")
                    conn.doOutput = true

                    val body = """{"project_id":"$projectId","device_token":"$token"}"""
                    conn.outputStream.use { it.write(body.toByteArray()) }

                    val responseCode = conn.responseCode
                    Log.d(TAG, "Token registration response: $responseCode")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register token", e)
                }
            }.start()
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val notification = remoteMessage.notification
        val title = notification?.title ?: getString(R.string.app_name)
        val body = notification?.body ?: ""
        val imageUrl = notification?.imageUrl?.toString()
            ?: remoteMessage.data["image"]

        sendNotification(title, body, imageUrl)
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token received")
        registerToken(token)
    }

    private fun sendNotification(title: String, messageBody: String, imageUrl: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        val channelId = "web2native_default"
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)

        if (!imageUrl.isNullOrEmpty()) {
            try {
                val url = URL(imageUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.doInput = true
                conn.connect()
                val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
                if (bitmap != null) {
                    notificationBuilder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as android.graphics.Bitmap?)
                    )
                    notificationBuilder.setLargeIcon(bitmap)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load notification image", e)
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Default Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
