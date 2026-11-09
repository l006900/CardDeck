package com.arcana.gfx

import android.graphics.Bitmap
import android.opengl.GLES20

/**
 * 贴图注册表：把「调用方给的 Bitmap」变成「GL 线程能用的纹理 id」。
 *
 * **所有权约定（写进公开契约）**：
 * - 库在 [TextureBank] 存活期间保留传入 Bitmap 的引用（GL 上下文重建后要自动重新上传），
 *   调用方丢弃 TextureBank 或调 [clear] 即释放引用；
 * - 库**不会调用 `Bitmap.recycle()`** —— 那是调用方的对象，调用方决定何时释放；
 * - 库只释放自己 `glGenTextures` 创建的纹理 id（且只在 GL 线程执行）。
 *
 * 注册是线程安全的，但**上传必须发生在 GL 线程**：[register] 只入队，
 * 渲染器每帧开头调 [flushUploads]。这样 Compose 侧可以在任意时机注册，不用管 EGL 上下文。
 */
class TextureBank {

    /** 超过这个宽度先下采样：动效里的小图元不需要原始分辨率，省显存和上传带宽。 */
    companion object {
        private const val MAX_TEXTURE_WIDTH = 512
    }

    private val lock = Any()
    /** 原始 Bitmap，用于 GL 上下文重建后自动重新上传。 */
    private val sources = LinkedHashMap<String, Bitmap>()
    private val pending = LinkedHashMap<String, Bitmap>()
    private val textures = HashMap<String, Int>()
    private val pendingDelete = ArrayList<Int>()

    /** 注册 / 替换一张贴图。同 key 重复注册会覆盖旧的（旧纹理在下一帧 GL 线程释放）。 */
    fun register(key: String, bitmap: Bitmap) {
        synchronized(lock) {
            sources[key] = bitmap
            pending[key] = bitmap
        }
    }

    /** 批量注册，语义同 [register]。 */
    fun registerAll(entries: Map<String, Bitmap>) {
        synchronized(lock) {
            sources.putAll(entries)
            pending.putAll(entries)
        }
    }

    /** 清空所有贴图（页面退出时调用；旧 id 排队等 GL 线程释放）。 */
    fun clear() {
        synchronized(lock) {
            sources.clear()
            pending.clear()
            pendingDelete.addAll(textures.values)
            textures.clear()
        }
    }

    /** GL 线程：把队列里的 Bitmap 上传成纹理。返回本次上传张数。 */
    internal fun flushUploads(): Int {
        val jobs = synchronized(lock) {
            if (pending.isEmpty()) return 0
            val copy = LinkedHashMap(pending)
            pending.clear()
            copy
        }
        for ((key, bmp) in jobs) {
            val id = GlUtil.uploadBitmap(bmp, MAX_TEXTURE_WIDTH)
            if (id != 0) {
                synchronized(lock) {
                    textures.remove(key)?.let { pendingDelete.add(it) }
                    textures[key] = id
                }
            }
        }
        flushDeletes()
        return jobs.size
    }

    /** GL 线程：查纹理 id；找不到返回 0，渲染器会退回白色占位纹理。 */
    internal fun texture(key: String): Int = synchronized(lock) { textures[key] ?: 0 }

    /**
     * GL 上下文已失效 / View 即将销毁时调用，**可在任意线程**：只丢弃 id 引用并把
     * 原始 Bitmap 重新排进上传队列，不碰任何 GLES 调用（纹理随上下文由驱动回收）。
     * 下次 surface 创建后 [flushUploads] 会自动重传，页面回来不会黑屏。
     */
    internal fun forgetGpu() {
        synchronized(lock) {
            textures.clear()
            pendingDelete.clear()
            pending.putAll(sources)
        }
    }

    private fun flushDeletes() {
        val ids = synchronized(lock) {
            if (pendingDelete.isEmpty()) return
            val copy = ArrayList(pendingDelete)
            pendingDelete.clear()
            copy
        }
        for (id in ids) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }
}
