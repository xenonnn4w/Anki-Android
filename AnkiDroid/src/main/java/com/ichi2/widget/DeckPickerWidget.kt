/**************************************************************************************
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import com.ichi2.anki.R
import timber.log.Timber

class DeckPickerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Timber.d("onUpdate")
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Timber.d("widgetSizeChanged")
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            for (widgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val options: Bundle = appWidgetManager.getAppWidgetOptions(widgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            // Calculate the width and height in number of cells (each cell is roughly 70dp in width)
            val cellWidth = (minWidthDp + 30) / 70
            val cellHeight = (minHeightDp + 30) / 70

            Timber.d("Widget ID: $widgetId, minWidthDp: $minWidthDp, minHeightDp: $minHeightDp, cellWidth: $cellWidth, cellHeight: $cellHeight")

            // Choose the appropriate layout based on the value of cellWidth
            val remoteViews = if (cellWidth >= 4) {
                RemoteViews(context.packageName, R.layout.widget_deck_picker_large)
            } else {
                RemoteViews(context.packageName, R.layout.widget_deck_picker_small)
            }
            // Update the AppWidget with the chosen layout
            appWidgetManager.updateAppWidget(widgetId, remoteViews)
        }
    }
}
