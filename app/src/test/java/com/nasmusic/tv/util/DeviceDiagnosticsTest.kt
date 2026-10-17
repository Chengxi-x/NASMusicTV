package com.nasmusic.tv.util

import android.content.Context
import com.nasmusic.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * 高质量分离失败诊断快照的契约测试。
 *
 * 出问题的电视在用户那边，我们既不能复现也没有 adb —— 这段文本是**唯一的取证通道**，
 * 所以判据不是「格式好看」，而是「用户截图后能不能把
 * 内存给不出来 / 文件读不了 / 算子不支持 三者区分开」。
 *
 * ⚠️ 用 stub Context 而不是 Robolectric 真资源：本模块没开
 * `testOptions.unitTests.isIncludeAndroidResources`，单测里 `context.getString(R.string.x)`
 * 会抛 `Resources$NotFoundException: No package ID 7f`。为一条诊断文本去翻全局测试配置
 * 不值得（影响 2000+ 例）；文案本身的正确性由 lint 的 MissingTranslation（阻塞级）守住 zh/en 双语，
 * 占位符个数与 `report()` 的实参个数是逐条人工核对过的（各 8 条 + 2 条可选）。
 */
class DeviceDiagnosticsTest {

    /**
     * 桩资源：`L<资源id>|<实参1>|<实参2>…`。
     * 前缀用来断言「这一行在不在」，实参用来断言「这一行填的是不是设备事实」。
     */
    private fun label(id: Int) = "L$id"

    private fun render(invocation: org.mockito.invocation.InvocationOnMock): String =
        label(invocation.arguments[0] as Int) +
            invocation.arguments.drop(1).joinToString("|") { it?.toString() ?: "-" }

    private val context: Context = mock(Context::class.java).apply {
        `when`(getString(anyInt())).thenAnswer { render(it) }
        `when`(getString(anyInt(), any())).thenAnswer { render(it) }
        `when`(getString(anyInt(), any(), any())).thenAnswer { render(it) }
        `when`(getString(anyInt(), any(), any(), any())).thenAnswer { render(it) }
    }

    @Test
    fun `每条设备事实都必须在文本里各占一行`() {
        val out = DeviceDiagnostics.report(context = context, error = "模型初始化失败: xxx")
        // 标题行在最前，横幅里第一眼能看到失败原因
        assertTrue(out, out.startsWith("模型初始化失败"))
        listOf(
            R.string.diag_header,
            R.string.diag_error_code,
            R.string.diag_device_model,
            R.string.diag_os_version,
            R.string.diag_abi,
            R.string.diag_total_memory,
            R.string.diag_process_memory,
            R.string.diag_ort_version,
        ).forEach { id ->
            assertTrue("缺 ${label(id)} 这一行：\n$out", out.contains(label(id)))
        }
    }

    @Test
    fun `可选字段缺省时整行消失而不是留下空标签`() {
        val out = DeviceDiagnostics.report(context = context, error = "boom")
        assertFalse(out, out.contains(label(R.string.diag_model_file)))
        assertFalse(out, out.contains(label(R.string.diag_elapsed)))
    }

    @Test
    fun `异常类名进错误码行，非 OrtException 也不能抛`() {
        val out = DeviceDiagnostics.report(
            context = context,
            error = "boom",
            cause = IllegalStateException("native allocate failed")
        )
        assertTrue(out, out.contains("IllegalStateException"))
    }

    @Test
    fun `模型文件不存在时标注缺失而不是报 0 字节`() {
        val out = DeviceDiagnostics.report(
            context = context,
            error = "boom",
            modelFile = File("definitely-not-here-9e1c.onnx")
        )
        assertTrue(out, out.contains(label(R.string.diag_model_file)))
        assertTrue(out, out.contains(label(R.string.diag_model_missing)))
    }

    @Test
    fun `模型文件存在时给出大小与路径`() {
        val file = File.createTempFile("diag-model", ".onnx").apply {
            writeBytes(ByteArray(3))
            deleteOnExit()
        }
        val out = DeviceDiagnostics.report(context = context, error = "boom", modelFile = file)
        assertTrue(out, out.contains(file.absolutePath))
        assertFalse(out, out.contains(label(R.string.diag_model_missing)))
    }

    @Test
    fun `耗时按秒呈现`() {
        val out = DeviceDiagnostics.report(context = context, error = "boom", elapsedMs = 1_234L)
        assertTrue(out, out.contains(label(R.string.diag_elapsed)))
        assertTrue(out, out.contains("1.2s"))
    }

    @Test
    fun `诊断头之后的行数固定，多余空行会毁掉截图可读性`() {
        val lines = DeviceDiagnostics.report(context = context, error = "boom").lines()
        // 1 行原因 + 8 行设备事实
        assertEquals(9, lines.size)
        assertTrue(lines.none { it.isBlank() })
    }
}
