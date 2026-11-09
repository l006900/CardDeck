package com.arcana.gfx

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.opengl.GLES11Ext
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.WindowManager

/**
 * **Camera2 实时采集源**：摄像头 → SurfaceTexture → `GL_TEXTURE_EXTERNAL_OES` → 合成管线。
 *
 * 和视频文件回放共用同一条零拷贝路径，区别只在生产者从 MediaPlayer 换成 CameraDevice。
 * 这正好验证 [FrameSource] 的接入契约：库不认识播放器，也不认识摄像头，只认一个 [Surface]。
 *
 * 线程：Camera2 的回调全部跑在自己的 [HandlerThread]，帧到达事件经 [frameSink] 进
 * C++ 无锁队列；所有 GLES 调用仍只在 GL 线程（由内部的 [ExternalTextureSource] 完成）。
 *
 * 权限：调用方 app 必须在 Manifest 声明 `android.permission.CAMERA` 并动态申请；
 * 没有权限时不会崩，[onFailure] 会收到提示，这一路源只是没有画面。
 *
 * GL 上下文重建（旋转 / 后台返回）时会拿到新的 Surface，内部自动关旧 session 再重开。
 */
class CameraFrameSource(
    private val context: Context,
    private val width: Int = 640,
    private val height: Int = 480,
    private val onFailure: ((String) -> Unit)? = null,
) : FrameSource {

    override val external: Boolean = true
    override val textureTarget: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES

    @Volatile
    override var frameSink: ((Long) -> Unit)? = null

    private val thread = HandlerThread("gfx-camera2").apply { start() }
    private val handler = Handler(thread.looper)

    private val delegate = ExternalTextureSource(
        name = "camera",
        onSurfaceReady = { surface -> openCamera(surface) },
        bufferWidth = width,
        bufferHeight = height,
    ).also {
        it.frameSink = { arrivalNs -> frameSink?.invoke(arrivalNs) }
    }

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var closed = false

    /**
     * 预览帧需要补的旋转角。后置 sensor 通常是 90°，竖屏手持时若直接用
     * SurfaceTexture 的原图会看到旋转 90° 的画面；这里按
     * `sensorOrientation - displayRotation` 算出来，在 uv 变换里补上。
     * 需要特殊朝向时调用方可在 [ready] 之后直接覆盖。
     */
    @Volatile
    var rotationDeg: Float = 0f

    private val stScratch = FloatArray(16)
    private val rotationMatrix = FloatArray(16)

    /** 当前是否已有摄像头 session 建立（供调用方显示状态）。 */
    val ready: Boolean get() = session != null

    /* -------------------------------------------------- FrameSource（全部转发） */

    override fun onGlCreated() = delegate.onGlCreated()
    override fun onGlLost() = delegate.onGlLost()
    override fun hasPendingFrame(): Boolean = delegate.hasPendingFrame()
    override fun update(): Long = delegate.update()
    override fun textureId(): Int = delegate.textureId()

    /**
     * 把 SurfaceTexture 的变换矩阵与「绕 uv 中心旋转」组合：
     * `uSTMatrix = stMatrix · (T(0.5) · R(rotationDeg) · T(-0.5))`。
     * 绕中心而不是绕原点，旋转 90° 后 uv 仍落在 [0,1] 内，不会被 clamp 拉出黑边。
     */
    override fun transform(out: FloatArray): FloatArray {
        delegate.transform(stScratch)
        val deg = rotationDeg
        if (deg == 0f) {
            System.arraycopy(stScratch, 0, out, 0, 16)
            return out
        }
        Matrix.setIdentityM(rotationMatrix, 0)
        Matrix.translateM(rotationMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotationMatrix, 0, deg, 0f, 0f, 1f)
        Matrix.translateM(rotationMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(out, 0, stScratch, 0, rotationMatrix, 0)
        return out
    }

    override fun byteCount(): Long = 0L   // BufferQueue 持有，不计入库的显存预算

    /** 摄像头实际推到 SurfaceTexture 的帧数；0 表示采集链没建立起来。 */
    fun framesArrived(): Long = delegate.framesArrived()

    /** 页面销毁：停采集、释放 Surface、退出回调线程。任意线程可调。 */
    fun close() {
        closed = true
        closeSession()
        delegate.close()
        thread.quitSafely()
    }

    /* -------------------------------------------------- Camera2 */

    @SuppressLint("MissingPermission")
    private fun openCamera(surface: Surface) {
        if (closed) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onFailure?.invoke("缺少 CAMERA 权限，采集源不可用")
            return
        }
        closeSession()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            onFailure?.invoke("设备没有 CameraManager")
            return
        }
        val id = try {
            manager.cameraIdList.firstOrNull {
                val facing = manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: Throwable) {
            onFailure?.invoke("枚举摄像头失败: ${e.message}")
            null
        }
        if (id == null) {
            onFailure?.invoke("没有可用摄像头")
            return
        }

        val fpsRange = try {
            val caps = manager.getCameraCharacteristics(id)
            val ranges = caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ranges?.firstOrNull { it.contains(30) } ?: ranges?.firstOrNull()
        } catch (_: Throwable) {
            null
        }

        rotationDeg = try {
            val sensor = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            val displayDeg = when (display) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            // uv 空间里 Matrix.rotateM 是逆时针；sensor 给的是「画面相对自然方向
            // 顺时针偏了多少」，所以取负角（+720 保证取模前为正）。
            ((displayDeg - sensor + 720) % 360).toFloat()
        } catch (_: Throwable) {
            0f
        }

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    startPreview(camera, surface, fpsRange)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onFailure?.invoke("CameraDevice error=$error")
                }
            }, handler)
        } catch (e: Throwable) {
            onFailure?.invoke("打开摄像头失败: ${e.message}")
        }
    }

    private fun startPreview(camera: CameraDevice, surface: Surface, fpsRange: Range<Int>?) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                if (fpsRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }
            }.build()
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed) { s.close(); return }
                        session = s
                        try {
                            s.setRepeatingRequest(request, null, handler)
                        } catch (e: Throwable) {
                            onFailure?.invoke("预览请求失败: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        onFailure?.invoke("CameraCaptureSession 配置失败")
                    }
                },
                handler,
            )
        } catch (e: Throwable) {
            onFailure?.invoke("创建预览会话失败: ${e.message}")
        }
    }

    private fun closeSession() {
        try { session?.stopRepeating() } catch (_: Throwable) { }
        try { session?.close() } catch (_: Throwable) { }
        session = null
        try { device?.close() } catch (_: Throwable) { }
        device = null
    }
}
