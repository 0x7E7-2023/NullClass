package com.nullclass.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

internal val AgendaPage = intPreferencesKey("agenda_page")
internal val AgendaPageKey = stringPreferencesKey("agenda_page_key")
internal val TargetPage = ActionParameters.Key<Int>("target_page")
internal val TargetPageKey = ActionParameters.Key<String>("target_page_key")
internal val SourcePage = ActionParameters.Key<Int>("source_page")
internal val PageCount = ActionParameters.Key<Int>("page_count")
internal val PageActionEnabled = ActionParameters.Key<Boolean>("page_action_enabled")

/** GlanceId 对应独立的 Preferences；翻页不会影响桌面上的另一个实例。 */
class WidgetPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val page = parameters[TargetPage] ?: return
        val key = parameters[TargetPageKey] ?: return
        val source = parameters[SourcePage] ?: return
        val count = parameters[PageCount] ?: return
        val enabled = parameters[PageActionEnabled] ?: return
        if (!enabled) return
        updateAppWidgetState(context, glanceId) { prefs ->
            val next = resolveWidgetPageRequest(
                storedPage = prefs[AgendaPage] ?: 0,
                storedKey = prefs[AgendaPageKey],
                sourcePage = source,
                targetPage = page,
                pageCount = count,
                requestKey = key,
                enabled = enabled,
            )
            if (next == null) {
                Log.i("WidgetPageAction", "stale tap ignored: source=$source target=$page stored=${prefs[AgendaPage]} keyMatch=${prefs[AgendaPageKey] == key}")
                return@updateAppWidgetState
            }
            prefs[AgendaPage] = next
            prefs[AgendaPageKey] = key
        }
        // 被判为过期的点击也重画：画面若与已存页码脱节，不重画就会一直按不动。
        TodayGlanceWidget().update(context, glanceId)
    }
}
