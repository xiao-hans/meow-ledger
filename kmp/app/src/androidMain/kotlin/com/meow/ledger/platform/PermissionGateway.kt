package com.meow.ledger.platform

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 把系统权限状态映射为 App 内的功能状态，供 Compose 权限页显示。 */
data class PermissionStatus(
    val key: String,
    val labelRes: Int,
    val purpose: String,
    val granted: Boolean,
    /** 能否在本机落到可操作的系统页；false 表示仅展示能力状态，不提供申请入口。 */
    val actionable: Boolean = true,
    /** 未开启时给用户的原因 / 下一步，例如不支持指纹、厂商页缺失。 */
    val hint: String = "",
)

interface PermissionGateway {
    fun statuses(): List<PermissionStatus>

    /** 返回待系统弹窗申请的危险权限；空列表表示无需申请。 */
    fun missingRuntimePermissions(): List<String>

    fun openSettings(key: String)
}

class AndroidPermissionGateway(private val context: Context) : PermissionGateway {
    override fun statuses(): List<PermissionStatus> = buildList {
        // 危险权限：只有系统能授予，按 SDK 版本显示；READ_SMS 已移除，本版不需要登录验证码。
        add(
            PermissionStatus(
                key = KEY_NOTIFICATION,
                labelRes = com.meow.ledger.R.string.permission_notification,
                purpose = "显示账单提醒和周期记账通知",
                granted = runtimeGranted(Manifest.permission.POST_NOTIFICATIONS),
                hint = "用于本地账单提醒；不申请也不影响手工记账。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_MEDIA_IMAGES,
                labelRes = com.meow.ledger.R.string.permission_media_images,
                purpose = "从相册选择票据并识别金额",
                granted = runtimeGranted(Manifest.permission.READ_MEDIA_IMAGES),
                hint = "Android 13 以下由系统相册授权，不需要单独申请。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_CAMERA,
                labelRes = com.meow.ledger.R.string.permission_camera,
                purpose = "拍摄票据或扫描账本二维码",
                granted = runtimeGranted(Manifest.permission.CAMERA),
                hint = "没有相机的设备可跳过，改用相册或粘贴文本。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_NOTIFICATION_LISTENER,
                labelRes = com.meow.ledger.R.string.permission_notification_listener,
                purpose = "把支付通知放进待确认队列，确认后才入账",
                granted = notificationListenerGranted(),
                hint = "系统「通知使用权」；部分国产 ROM 隐藏入口时用应用详情页进入。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_ACCESSIBILITY,
                labelRes = com.meow.ledger.R.string.permission_accessibility,
                purpose = "识别支付页面文字，结果仍须人工确认",
                granted = accessibilityGranted(),
                hint = "系统「无障碍」；请手动选择「喵啊记账」服务。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_USAGE_STATS,
                labelRes = com.meow.ledger.R.string.permission_usage_stats,
                purpose = "识别自动记账来源应用",
                granted = usageStatsGranted(),
                hint = "系统「使用情况访问权限」；部分 ROM 在「高级设置」里。",
            ),
        )
        // 应用锁：硬件能力 + 用户是否已登记生物特征。不把「有指纹模块」当成已授权。
        add(
            PermissionStatus(
                key = KEY_BIOMETRIC,
                labelRes = com.meow.ledger.R.string.permission_biometric,
                purpose = "使用生物识别解锁账本",
                granted = biometricEnrolled(),
                actionable = biometricSupported(),
                hint = if (biometricSupported()) {
                    "请到系统「生物识别与密码」登记指纹/人脸后再回到 App 开启应用锁。"
                } else {
                    "这台设备不支持生物识别硬件，可跳过；不影响记账功能。"
                },
            ),
        )
        add(
            PermissionStatus(
                key = KEY_EXACT_ALARM,
                labelRes = com.meow.ledger.R.string.permission_exact_alarm,
                purpose = "按设定时间触发周期记账提醒",
                granted = exactAlarmGranted(),
                hint = "Android 12+ 由系统「闹钟与提醒」批准；拒绝后提醒可能延迟。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_OVERLAY,
                labelRes = com.meow.ledger.R.string.permission_overlay,
                purpose = "在桌面显示悬浮宠物入口",
                granted = Settings.canDrawOverlays(context),
                hint = "系统「显示在其他应用上层」；部分 ROM 还需允许后台弹窗。",
            ),
        )
        add(
            PermissionStatus(
                key = KEY_BATTERY,
                labelRes = com.meow.ledger.R.string.permission_battery,
                purpose = "让通知监听、周期记账和提醒在后台持续运行",
                granted = batteryOptimizationIgnored(),
                hint = "系统「电池优化白名单」；国产 ROM 还需打开自启动/允许后台运行。",
            ),
        )
    }

    override fun missingRuntimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            add(Manifest.permission.CAMERA)
        }
        // 不申请 READ_SMS：本版没有账号/验证码登录，那是高敏权限且申请不到。
        // 系统设置类权限不能通过 RequestMultiplePermissions 申请，见 openSettings。
    }.filterNot(::runtimeGranted)

    override fun openSettings(key: String) {
        val status = statuses().firstOrNull { it.key == key } ?: return
        if (!status.actionable || status.granted) return
        val intents = settingIntents(key)
        for (intent in intents) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        // 所有厂商页都打不开时，最后一定能落到本应用详情页。
        runCatching {
            context.startActivity(appDetailsIntent())
        }
    }

    /** 标准 Action → 常见国产 ROM 页 → 应用详情，逐层兜底。 */
    private fun settingIntents(key: String): List<Intent> {
        val pkg = Uri.parse("package:${context.packageName}")
        val standard = when (key) {
            KEY_NOTIFICATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                        .putExtra("app_package", context.packageName)
                        .putExtra("app_uid", context.applicationInfo.uid),
                )
            } else emptyList()
            KEY_MEDIA_IMAGES, KEY_CAMERA -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            KEY_NOTIFICATION_LISTENER -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            KEY_ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            KEY_USAGE_STATS -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            KEY_BIOMETRIC -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(
                        Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                            .putExtra("android.intent.extra.BIOMETRIC_AUTHENTICATORS_ALLOWED", 255),
                    )
                }
                add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            KEY_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            } else emptyList()
            KEY_OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            KEY_BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            else -> emptyList()
        }
        val brands = brandIntents(key)
        return (standard + brands + listOf(appDetailsIntent()))
            .map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .distinctBy { it.action + "|" + it.component?.flattenToString().orEmpty() + "|" + it.dataString.orEmpty() }
    }

    /** 厂商定制页都用显式 Component 尝试；不存在时 startActivity 抛异常并由调用方兜底。 */
    private fun brandIntents(key: String): List<Intent> {
        if (key !in setOf(KEY_BATTERY, KEY_NOTIFICATION, KEY_EXACT_ALARM, KEY_OVERLAY)) return emptyList()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val components = mutableListOf<String>()
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
            components += "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
            components += "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        }
        if (manufacturer.contains("vivo")) {
            components += "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager"
            components += "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity"
            components += "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity"
        }
        if (manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme")) {
            components += "com.coloros.safecenter/.startupapp.StartupAppListActivity"
            components += "com.coloros.safecenter/.permission.PermissionTopActivity"
            components += "com.oppo.safe/.permission.startup.StartupAppListActivity"
        }
        if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
            components += "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity"
            components += "com.huawei.systemmanager/.optimize.process.ProtectActivity"
        }
        if (manufacturer.contains("samsung")) {
            components += "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity"
        }
        return components.mapNotNull { flat ->
            val split = flat.split("/", limit = 2)
            if (split.size != 2) return@mapNotNull null
            Intent().setClassName(split[0], split[1].removePrefix(".").let { second ->
                if (flat.contains("/.")) split[0] + "." + second else second
            })
        }
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun runtimeGranted(permission: String): Boolean =
        Build.VERSION.SDK_INT < when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION_CODES.TIRAMISU
            Manifest.permission.READ_MEDIA_IMAGES -> Build.VERSION_CODES.TIRAMISU
            else -> 0
        } || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationListenerGranted(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled.orEmpty().contains(context.packageName)
    }

    private fun accessibilityGranted(): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        )
        return enabled == 1 && Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().contains(context.packageName)
    }

    private fun usageStatsGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun biometricSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                context.packageManager.hasSystemFeature("android.hardware.biometrics"))

    /** 已登记生物特征才算「已开启」；没有凭据时跳登记页而不是安全设置总页。 */
    private fun biometricEnrolled(): Boolean {
        if (!biometricSupported()) return false
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        // 有锁屏密码不代表已录入指纹，但能提示用户至少去系统登记；用系统设置状态作可见近似。
        return keyguard?.isDeviceSecure == true && Settings.Secure.getInt(
            context.contentResolver,
            "user_setup_complete",
            0,
        ) == 1
    }

    private fun batteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return power?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    private fun exactAlarmGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }

    companion object {
        const val KEY_NOTIFICATION = "notification"
        const val KEY_MEDIA_IMAGES = "media_images"
        const val KEY_CAMERA = "camera"
        const val KEY_NOTIFICATION_LISTENER = "notification_listener"
        const val KEY_ACCESSIBILITY = "accessibility"
        const val KEY_USAGE_STATS = "usage_stats"
        const val KEY_BIOMETRIC = "biometric"
        const val KEY_EXACT_ALARM = "exact_alarm"
        const val KEY_OVERLAY = "overlay"
        const val KEY_BATTERY = "battery"
    }
}
