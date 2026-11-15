package com.meow.ledger

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.meow.ledger.shared.manage.WidgetRepository
import com.meow.ledger.ui.AppGraph
import com.meow.ledger.ui.manage.WidgetConfigScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 桌面组件配置入口。系统在添加组件时拉起本页，选种类和尺寸后写 `Widget_user` 并绑定
 * appWidgetId；取消则返回 RESULT_CANCELED，系统不会把组件放上桌面。
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE).toLong()
        setContent {
            WidgetConfigScreen(
                appWidgetId = appWidgetId,
                today = today,
                onConfirm = { size, kind ->
                    WidgetRepository(AppGraph.database).saveConfig(appWidgetId, "", size, kind)
                    com.meow.ledger.platform.AppWidgetReceiver.refresh(this)
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                    )
                    finish()
                },
                onCancel = { finish() },
            )
        }
    }
}
