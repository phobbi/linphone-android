/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.integration.calls

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import org.linphone.R
import org.linphone.core.tools.Log

/**
 * The foreground state that lets a managed call use the microphone.
 *
 * Android lets an app take the microphone while it is in the background only from a foreground service of type
 * microphone, and starting one from the background is refused - with one exception: a service a system component
 * started. Linphone's own in-call service is started by the app itself, so for a call the system phone app answered
 * (Linphone in the background) Android refused it: "your correspondent does not hear you". The ConnectionService
 * is bound by Telecom, a system component: made foreground itself, it may use the microphone.
 */
object ManagedCallForeground {
    private const val TAG = "[Managed Call Foreground]"
    private const val CHANNEL = "integration_managed_call"
    private const val NOTIFICATION_ID = 0x4c5043 // "LPC"

    private var service: WeakReference<Service>? = null

    @MainThread
    fun start(service: Service) {
        if (this.service?.get() === service) return
        this.service = WeakReference(service)
        val notification = notification(service)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, notification)
            Log.i("$TAG Foreground")
            return
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            service.startForeground(NOTIFICATION_ID, notification, types)
            Log.i("$TAG Foreground with types [$types] (microphone allowed)")
        } catch (e: Exception) {
            // should not happen (Telecom started the service); a phone call service still keeps the call going
            Log.e("$TAG Foreground with the microphone refused: $e")
            try {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } catch (e2: Exception) {
                Log.e("$TAG Foreground refused: $e2")
            }
        }
    }

    @MainThread
    fun stop() {
        val s = service?.get() ?: return
        service = null
        s.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        Log.i("$TAG Not foreground any more")
    }

    private fun notification(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // quiet: the system phone app shows the call itself; this one only keeps the microphone
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.integration_call_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.integration_call_channel_description)
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ShowCallScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.linphone_notification)
            .setContentTitle(context.getString(R.string.integration_call_notification_title))
            .setContentText(context.getString(R.string.integration_call_notification_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/** the ongoing call notification's tap: the system phone app's call screen */
class ShowCallScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        } catch (e: SecurityException) {
            Log.e("[Show Call Screen] $e")
        }
        finish()
    }
}
