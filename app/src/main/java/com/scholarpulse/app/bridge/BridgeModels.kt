package com.scholarpulse.app.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BridgeRequest(
    val id: String,
    val method: String,
    val params: JsonElement? = null,
    val timestamp: Long = 0L
)

@Serializable
data class BridgeResponse(
    val id: String,
    val success: Boolean,
    val result: JsonElement? = null,
    val error: BridgeError? = null
)

@Serializable
data class BridgeError(
    val code: String,
    val message: String,
    val details: JsonElement? = null
)

@Serializable
data class NativeEvent(
    val event: String,
    val payload: JsonElement? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class SafeAreaInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0
)

@Serializable
data class DeviceInfoResult(
    val platform: String = "android",
    val osVersion: Int,
    val appVersion: String,
    val deviceId: String,
    val fcmToken: String? = null,
    val hasNotificationPermission: Boolean,
    val hasExactAlarmPermission: Boolean,
    val safeAreaInsets: SafeAreaInsets
)

@Serializable
data class HapticParams(
    val style: String = "light"
)

@Serializable
data class UpdateThemeParams(
    val theme: String = "system"
)

@Serializable
data class StartTimerParams(
    val subjectId: String = "",
    val subjectName: String? = null,
    val topic: String = "Focused Study",
    val notes: String? = null,
    val mode: String = "pomodoro",
    val targetDurationSeconds: Int = 1500,
    val idempotencyKey: String = "",
    val accumulatedSeconds: Long = 0L,
    val phase: String = "focus"
)

@Serializable
data class StopTimerParams(
    val productivityRating: Int? = null,
    val notes: String? = null,
    val discard: Boolean = false
)

@Serializable
data class AudioChimeParams(
    val type: String = "focus_end"
)

@Serializable
data class ScheduleItemDto(
    val id: String,
    val subjectId: String,
    val subjectName: String? = null,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String? = null,
    val description: String? = null
)

@Serializable
data class SyncScheduleAlarmsParams(
    val schedules: List<ScheduleItemDto> = emptyList(),
    val dailyReminderTime: String? = null,
    val dailyTargetMinutes: Int? = null
)

@Serializable
data class TimerStatusResult(
    val status: String,
    val phase: String,
    val mode: String,
    val subjectId: String,
    val subjectName: String? = null,
    val topic: String,
    val elapsedSeconds: Long,
    val remainingSeconds: Long,
    val targetDurationSeconds: Long,
    val completedPomodoros: Int,
    val startedAt: String? = null,
    val lastResumedAt: String? = null
)

@Serializable
data class RemoteTimerSyncParams(
    val action: String = "PAUSE",
    val accumulatedSeconds: Long? = null,
    val remainingMs: Long? = null,
    val updatedAt: Long? = null,
    val originDeviceId: String? = null
)

object BridgeErrorCodes {
    const val E_UNAUTHORIZED_ORIGIN = "E_UNAUTHORIZED_ORIGIN"
    const val E_INVALID_PARAMS = "E_INVALID_PARAMS"
    const val E_PERMISSION_DENIED = "E_PERMISSION_DENIED"
    const val E_AUTH_CANCELLED = "E_AUTH_CANCELLED"
    const val E_AUTH_FAILED = "E_AUTH_FAILED"
    const val E_SERVICE_UNAVAILABLE = "E_SERVICE_UNAVAILABLE"
    const val E_TIMEOUT = "E_TIMEOUT"
    const val E_METHOD_NOT_FOUND = "E_METHOD_NOT_FOUND"
    const val E_INTERNAL_ERROR = "E_INTERNAL_ERROR"
}
