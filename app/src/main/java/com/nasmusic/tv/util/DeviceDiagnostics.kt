package com.nasmusic.tv.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import ai.onnxruntime.OrtException
import com.nasmusic.tv.R
import java.io.File

/**
 * 失败诊断快照。
 *
 * 为什么要把它拼进**用户可见的提示**：高质量分离的失败发生在 ONNX Runtime 建会话阶段
 * （[DemucsSeparator] 的 `createSession`），而只有 native 侧原文加上设备实况才能把
 * 「内存给不出来 / 文件读不了 / 算子不支持」三者区分开。出问题的电视在用户那边，
 * 我们没有 adb，唯一可行的取证通道是用户对着屏幕截图。
 */
object DeviceDiagnostics {

    private const val UNKNOWN = "-"

    /**
     * @param error 已本地化的失败原因（只作标题行，诊断字段另起行）
     * @param cause 触发失败的异常；[OrtException] 的错误码会被单独提取
     * @param modelFile 模型文件（存在性、大小、路径）
     * @param ortVersion ONNX Runtime 版本；拿不到传 null
     * @param elapsedMs 失败前已消耗的毫秒数（加载耗时能区分「立刻拒」和「撑到最后崩」）
     */
    fun report(
        context: Context,
        error: String,
        cause: Throwable? = null,
        modelFile: File? = null,
        ortVersion: String? = null,
        elapsedMs: Long? = null
    ): String {
        val mem = readMemoryInfo(context)
        val parts = mutableListOf<String>()
        parts += context.getString(R.string.diag_header)
        parts += context.getString(R.string.diag_error_code, describeError(cause))
        parts += context.getString(R.string.diag_device_model, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        parts += context.getString(R.string.diag_os_version, Build.VERSION.RELEASE ?: UNKNOWN, Build.VERSION.SDK_INT.toString())
        parts += context.getString(R.string.diag_abi, Build.SUPPORTED_ABIS?.joinToString(", ") ?: UNKNOWN, processBitness())
        parts += context.getString(
            R.string.diag_total_memory,
            mb(mem?.totalMem),
            mb(mem?.availMem),
            if (mem?.lowMemory == true) context.getString(R.string.diag_low_memory_flag) else ""
        )
        parts += context.getString(
            R.string.diag_process_memory,
            mb(runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull()),
            mb(runCatching { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }.getOrNull()),
            mb(runCatching { Runtime.getRuntime().maxMemory() }.getOrNull())
        )
        parts += context.getString(R.string.diag_ort_version, ortVersion ?: UNKNOWN)
        if (modelFile != null) {
            parts += context.getString(
                R.string.diag_model_file,
                if (modelFile.exists()) mb(modelFile.length()) else context.getString(R.string.diag_model_missing),
                modelFile.absolutePath
            )
        }
        if (elapsedMs != null) {
            parts += context.getString(
                R.string.diag_elapsed,
                String.format(java.util.Locale.US, "%.1fs", elapsedMs / 1000.0)
            )
        }
        // 标题在最前，便于在横幅里第一眼看到失败原因
        return error + "\n" + parts.joinToString("\n")
    }

    private fun describeError(cause: Throwable?): String {
        if (cause == null) return UNKNOWN
        val code = (cause as? OrtException)?.code?.name
        val cls = cause.javaClass.simpleName
        return if (code != null) "$code ($cls)" else cls
    }

    private class MemorySnapshot(val totalMem: Long?, val availMem: Long?, val lowMemory: Boolean)

    private fun readMemoryInfo(context: Context): MemorySnapshot? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            MemorySnapshot(info.totalMem, info.availMem, info.lowMemory)
        }.getOrNull()
    }

    /**
     * 本进程的指令集位宽。32 位进程的可映射地址空间只有约 3GB，是「模型装得下装不下」的分水岭。
     *
     * API 23 以下没有公开 API，退化为读 `/proc/self/maps` 里 so 的加载目录
     * （`/lib/arm64`、`/lib64/` 视为 64 位，`/lib/armeabi…`、`/system/lib/` 视为 32 位）。
     */
    private fun processBitness(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { return if (android.os.Process.is64Bit()) "64" else "32" }
        }
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrNull() ?: return UNKNOWN
        return when {
            maps.contains("/lib/arm64") || maps.contains("/lib64/") || maps.contains("/lib/x86_64") -> "64"
            maps.contains("/lib/armeabi") || maps.contains("/lib/x86/") -> "32"
            else -> UNKNOWN
        }
    }

    private fun mb(bytes: Long?): String =
        if (bytes == null || bytes < 0) UNKNOWN else "${bytes / (1024 * 1024)} MB"
}
