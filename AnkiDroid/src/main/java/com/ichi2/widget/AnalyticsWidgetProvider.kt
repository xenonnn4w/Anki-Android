/*
 *  Copyright (c) 2024 Anoop <xenonnn4w@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import androidx.annotation.CallSuper
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.analytics.UsageAnalytics
import timber.log.Timber

abstract class AnalyticsWidgetProvider : AppWidgetProvider() {

    @CallSuper
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        UsageAnalytics.sendAnalyticsEvent(this.javaClass.simpleName, "enabled")
    }

    @CallSuper
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        UsageAnalytics.sendAnalyticsEvent(this.javaClass.simpleName, "disabled")
    }

    @CallSuper
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (!IntentHandler.grantedStoragePermissions(context, showToast = false)) {
            Timber.w("Opening widget without storage access")
            return
        }
        // Pass usageAnalytics to performUpdate
        performUpdate(context, appWidgetManager, appWidgetIds, UsageAnalytics)
    }

    abstract fun performUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray, usageAnalytics: UsageAnalytics)
}
