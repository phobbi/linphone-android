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
package org.linphone.integration

import android.content.Context
import androidx.annotation.AnyThread
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log

/**
 * What the Android integration did on the last calls (call path, microphone, foreground), for Settings → Android
 * integration → Diagnostics: the user sees why something did not work without a computer and adb. No numbers or
 * addresses are written here.
 */
object IntegrationDiag {
    private const val PREFS = "integration_diag"
    private const val KEY = "lines"
    private const val KEEP = 60

    @AnyThread
    @Synchronized
    fun add(line: String) {
        Log.i("[Integration] $line")
        val context = coreContext.context
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT).format(Date())
        val lines = (prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() } + "$stamp  $line")
            .takeLast(KEEP)
        prefs.edit { putString(KEY, lines.joinToString("\n")) }
    }

    @AnyThread
    fun lines(context: Context): List<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
    }

    @AnyThread
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }
    }
}
