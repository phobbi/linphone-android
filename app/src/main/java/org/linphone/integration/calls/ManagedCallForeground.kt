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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import org.linphone.R
import org.linphone.integration.IntegrationDiag
import org.linphone.integration.IntegrationPrefs

/**
 * The microphone for managed calls.
 *
 * Android gives an app the microphone in the background only through a foreground service of type microphone,
 * and refuses to start one from the background - with few exceptions ("a system component starts the service";
 * the app is visible). A call the system phone app answers leaves Linphone in the background, so:
 *
 *  1. the ConnectionService, which Telecom (a system component) started, is made foreground with the microphone
 *     type when Telecom creates the call ([start])
 *  2. when the call is up, if that was refused (or always, by the setting), [MicActivity] - invisible, gone at
 *     once - makes the app visible for a moment, and the foreground service gets the microphone then
 *     ([ensureMicrophone]). Starting it from the background needs "display over other apps"
 *     (SYSTEM_ALERT_WINDOW), granted once in the settings.
 *
 * Every step is written to [IntegrationDiag].
 */
object ManagedCallForeground {
    private const val CHANNEL = "integration_managed_call"
    private const val NOTIFICATION_ID = 0x4c5043 // "LPC"

    private var service: WeakReference<Service>? = null

    /** the foreground service holds the microphone type (startForeground with it did not throw) */
    @Volatile var microphoneGranted = false
        private set

    /** the microphone activity is up right now (it did start) */
    @Volatile private var activityShown = false

    private val handler = Handler(Looper.getMainLooper())

    @MainThread
    fun start(service: Service) {
        if (this.service?.get() === service) return
        this.service = WeakReference(service)
        microphoneGranted = false
        foreground(service, "call created by Telecom")
    }

    /** startForeground with the microphone when it may; true: it was taken */
    @MainThread
    private fun foreground(service: Service, why: String): Boolean {
        val notification = notification(service)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // before Android 11 a foreground service has the microphone whatever its type
            service.startForeground(NOTIFICATION_ID, notification)
            microphoneGranted = true
            IntegrationDiag.add("Microphone: foreground ($why)")
            return true
        }
        val recordGranted = ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!recordGranted) IntegrationDiag.add("Microphone: RECORD_AUDIO permission not granted!")
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            (if (recordGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        return try {
            service.startForeground(NOTIFICATION_ID, notification, types)
            microphoneGranted = recordGranted
            IntegrationDiag.add("Microphone: foreground service with the microphone ($why)")
            recordGranted
        } catch (e: Exception) {
            IntegrationDiag.add("Microphone: refused ($why): ${e.javaClass.simpleName}")
            try {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } catch (e2: Exception) {
                IntegrationDiag.add("Microphone: foreground refused entirely: ${e2.javaClass.simpleName}")
            }
            false
        }
    }

    /** the call is up: make sure the microphone is the app's (setting "Microphone access") */
    @MainThread
    fun ensureMicrophone(context: Context) {
        val mode = IntegrationPrefs.micBoost
        val needed = when (mode) {
            IntegrationPrefs.MicBoost.NEVER -> false
            IntegrationPrefs.MicBoost.ALWAYS -> true
            IntegrationPrefs.MicBoost.WHEN_REFUSED -> !microphoneGranted
        }
        if (!needed) return
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        IntegrationDiag.add(
            "Microphone: bringing Linphone up for a moment (" +
                (if (canOverlay) "display over other apps granted" else "display over other apps NOT granted") + ")"
        )
        activityShown = false
        try {
            context.startActivity(
                Intent(context, MicActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            IntegrationDiag.add("Microphone: the activity could not start: ${e.javaClass.simpleName}")
            return
        }
        handler.postDelayed({
            if (!activityShown) {
                IntegrationDiag.add(
                    "Microphone: Android did not let the activity start (allow \"display over other apps\" in the settings)"
                )
            }
        }, 3000)
    }

    /** from [MicActivity]: the app is visible now, the foreground service may take the microphone */
    @MainThread
    fun onVisible() {
        activityShown = true
        val s = service?.get()
        if (s == null) {
            IntegrationDiag.add("Microphone: no call service any more")
            return
        }
        foreground(s, "with Linphone visible")
    }

    @MainThread
    fun stop() {
        val s = service?.get() ?: return
        service = null
        microphoneGranted = false
        s.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        IntegrationDiag.add("Call over: foreground service stopped")
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

/**
 * Invisible and gone at once: while it is resumed the app is visible, which is when Android lets the foreground
 * service take the microphone. Over the lock screen too (showWhenLocked); it does not turn the screen on.
 */
class MicActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
    }

    override fun onResume() {
        super.onResume()
        ManagedCallForeground.onVisible()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** the ongoing call notification's tap: the system phone app's call screen */
class ShowCallScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        } catch (e: SecurityException) {
            IntegrationDiag.add("Can't show the system call screen: ${e.javaClass.simpleName}")
        }
        finish()
    }
}
