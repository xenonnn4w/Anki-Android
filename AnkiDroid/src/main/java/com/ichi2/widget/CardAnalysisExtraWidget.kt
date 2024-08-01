/*
   Copyright (c) 2024 Anoop <xenonnn4w@gmail.com>
This program is free software; you can redistribute it and/or modify it under
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

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.ichi2.anki.AnkiDroidApp.Companion.applicationScope
import com.ichi2.anki.R
import com.ichi2.anki.analytics.UsageAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * AnalyticsWidgetProvider class for Card Analysis Widget that integrates
 * with UsageAnalytics to send analytics events when the widget is enabled, disabled,
 * or updated..
 * This widget displays a decks with respective name, new, learning, and review card counts.
 * It updates every minute .
 * No user actions can be performed from this widget as of now; it is for display purposes only.
 */
class CardAnalysisExtraWidget : AnalyticsWidgetProvider() {

    companion object {
        const val ACTION_APPWIDGET_UPDATE = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        const val ACTION_UPDATE_WIDGET = "com.ichi2.widget.ACTION_UPDATE_WIDGET"

        /**
         * Updates the widget with the deck data.
         *
         * @param context the context of the application
         * @param appWidgetManager the app widget manager
         * @param widgetId the array of widget IDs
         * @param deckIds the array of deck IDs
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: IntArray,
            deckIds: LongArray
        ) {
            val remoteViews = RemoteViews(context.packageName, R.layout.widget_card_analysis_extra)

            applicationScope.launch(Dispatchers.Main) {
                val deckData = getDeckNameAndStats(deckIds.toList())

                if (deckData.isNotEmpty()) {
                    val deck = deckData[0]
                    remoteViews.setTextViewText(R.id.deckNameCardAnalysisExtra, deck.name)
                    remoteViews.setTextViewText(R.id.deckNew_card_analysis_extra_widget, deck.newCount.toString())
                    remoteViews.setTextViewText(R.id.deckDue_card_analysis_extra_widget, deck.reviewCount.toString())
                    remoteViews.setTextViewText(R.id.deckLearn_card_analysis_extra_widget, deck.learnCount.toString())
                }
                appWidgetManager.updateAppWidget(widgetId, remoteViews)
            }
        }

        /**
         * Sets a recurring alarm to update the widget every minute.
         *
         * @param context the context of the application
         * @param appWidgetId the ID of the widget
         */
        private fun setRecurringAlarm(context: Context, appWidgetId: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CardAnalysisExtraWidget::class.java).apply {
                action = ACTION_UPDATE_WIDGET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

            /**
             * When onUpdate is called, the code checks if an existing alarm PendingIntent
             * is already set for the widget .If an Alarm Already Exists: PendingIntent.getBroadcast
             * returns the existing PendingIntent, and pendingIntent is not null.
             * The if block is skipped, and no new alarm is set.
             */

            if (pendingIntent != null) {
                Timber.v("Recurring alarm PendingIntent already exists for widget ID: $appWidgetId")
                return
            }

            Timber.v("Creating a new recurring alarm PendingIntent for widget ID: $appWidgetId")
            val newPendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            // Set alarm to trigger every minute
            val ONE_MINUTE_MILLIS = 60.seconds.inWholeMilliseconds
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ONE_MINUTE_MILLIS,
                ONE_MINUTE_MILLIS,
                newPendingIntent
            )
        }

        /**
         * Cancels the recurring alarm for the widget.
         *
         * @param context the context of the application
         * @param appWidgetId the ID of the widget
         */
        private fun cancelRecurringAlarm(context: Context, appWidgetId: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CardAnalysisExtraWidget::class.java).apply {
                action = ACTION_UPDATE_WIDGET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            Timber.d("Canceling recurring alarm for widget ID: $appWidgetId")
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun performUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        usageAnalytics: UsageAnalytics
    ) {
        val widgetPreferences = WidgetPreferences(context)

        for (widgetId in appWidgetIds) {
            val selectedDeckIds = widgetPreferences.getSelectedDeckIdsFromPreferencesCardAnalysisExtraWidgetData(widgetId)
            if (selectedDeckIds.isNotEmpty()) {
                updateWidget(context, appWidgetManager, intArrayOf(widgetId), selectedDeckIds)
            }
            setRecurringAlarm(context, widgetId)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Timber.e("Context or intent is null in onReceive")
            return
        }
        super.onReceive(context, intent)

        val widgetPreferences = WidgetPreferences(context)

        when (intent.action) {
            ACTION_APPWIDGET_UPDATE -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                val selectedDeckIds = intent.getLongArrayExtra("card_analysis_extra_widget_selected_deck_ids")

                if (appWidgetIds != null && selectedDeckIds != null) {
                    updateWidget(context, appWidgetManager, appWidgetIds, selectedDeckIds)
                }
            }
            ACTION_UPDATE_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val selectedDeckIds = widgetPreferences.getSelectedDeckIdsFromPreferencesCardAnalysisExtraWidgetData(appWidgetId)
                    if (selectedDeckIds.isNotEmpty()) {
                        updateWidget(context, appWidgetManager, intArrayOf(appWidgetId), selectedDeckIds)
                    }
                }
            }
        }
    }

    /**
     * Triggers the cancel recurring alarm when the widget is deleted.
     *
     * @param context the context of the application
     * @param appWidgetIds the array of widget IDs being deleted
     */
    override fun onDeleted(context: Context?, appWidgetIds: IntArray?) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds?.forEach { widgetId ->
            cancelRecurringAlarm(context!!, widgetId)
        }
    }
}
