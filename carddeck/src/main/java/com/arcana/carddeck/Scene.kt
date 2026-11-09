package com.arcana.carddeck

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一个 2.5D 图元此刻的位姿。
 *
 * **坐标系（写死，别传像素）**：`x/y` 是归一化 `[0,1]`、左上原点，除以视图宽高得到像素；
 * `width/height` 同样是归一化比例（相对视图宽 / 高），不是 dp、不是 px。
 * `rotation` 单位「度」，屏幕 **顺时针为正**（与 Compose `graphicsLayer.rotationZ` 一致）。
 *
 * `flip` 是这张图元的「正反面进度」：`0` = 完全显示 [Node.back]，`1` = 完全显示 [Node.front]，
 * 中间值由顶点着色器做纸张弯曲 + 菲涅尔金边，是翻面动画的驱动量（库不认识"牌"）。
 * `lift` 是离桌高度（世界单位的相对量），只驱动假阴影与体积光晕，不改变屏幕位置。
 *
 * 全部字段不可变：`Scene` 每帧给 GL 线程的是一份快照，[Motion] 返回新的 [Pose] 而不是改旧值，
 * 这样跨线程不需要锁住每个字段。
 */
data class Pose(
    val x: Float,
    val y: Float,
    val width: Float = 0.12f,
    val height: Float = 0.20f,
    val rotation: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val flip: Float = 0f,
    val lift: Float = 0f,
) {
    /** 返回一个只改动指定字段的副本，供 [Motion] 每帧生成新位姿（零副作用）。 */
    fun copyWith(
        x: Float = this.x,
        y: Float = this.y,
        rotation: Float = this.rotation,
        scale: Float = this.scale,
        alpha: Float = this.alpha,
        flip: Float = this.flip,
        lift: Float = this.lift,
    ): Pose = Pose(x, y, width, height, rotation, scale, alpha, flip, lift)
}

/**
 * 图元上叠加的 shader 特效强度，全部是 `0..1`（[Effects.bend] 可 >1）。
 * 特效是可组合的 fragment / vertex pass，不依赖任何业务语义，任何贴图都能套。
 */
data class Effects(
    /** 金边描边 + 斜向流光强度。 */
    val foil: Float = 1f,
    /** 背后的体积光晕（径向辉光 + 竖向光锥），0 = 不画这个 pass。 */
    val halo: Float = 0f,
    /** 椭圆接触阴影，随 [Pose.lift] 变大变淡；0 = 关。 */
    val shadow: Float = 1f,
    /** 翻面时的纸张弯曲幅度（默认调小，见 CARD_VERTEX 的 uBend）。 */
    val bend: Float = 0.55f,
    /** 翻开瞬间沿对角线扫过的金色脉冲，0..1。 */
    val glow: Float = 0f,
) {
    companion object {
        val None = Effects(foil = 0f, halo = 0f, shadow = 0f, bend = 0f, glow = 0f)
    }
}

/**
 * 场景里的一个带贴图矩形。
 *
 * [front] / [back] 是 [TextureBank] 里的**键**（字符串），不是 Bitmap：
 * 贴图由 GL 线程统一上传、按 key 查表，Node 只持有轻量描述，可以几千个同时存在。
 * 只显示单面时让 `back = front` 即可。
 *
 * **纹理所有权**：注册进 [TextureBank] 的 Bitmap 由库上传成 GL 纹理，但库**不会 recycle**
 * 调用方的 Bitmap（那是调用方的对象）；库在 surface 销毁时只释放自己创建的 GL 纹理 id。
 */
data class Node(
    val id: String,
    val pose: Pose,
    val front: String,
    val back: String = front,
    val effects: Effects = Effects(),
)

/**
 * 命中结果。[corners] 是图元的**四个角点**（像素坐标，顺序：左上 → 右上 → 右下 → 左下），
 * 长度 8 的 `FloatArray`。
 *
 * 刻意不返回「矩形」：2.5D 下带旋转与透视弯曲的图元在屏幕上不是轴对齐矩形，
 * 给 cx/cy/w/h/rot 会让调用方做共享元素过场时对不上。用四角点可直接喂给任意变换。
 */
data class Pick(
    val id: String,
    val index: Int,
    val corners: FloatArray,
)

/**
 * 每帧位姿函数 —— **在 GL 线程执行**，主线程零参与。
 *
 * 这是「几百个元素每帧都在动还不卡」的关键：调用方不在 Compose 里 `animate*AsState`
 * 逐帧重组，而是给一个纯函数，渲染线程按自己的时钟求值。
 * 必须线程安全、不得捕获可变 UI 状态、不得分配大对象（每帧每节点调用一次）。
 */
fun interface Motion {
    fun poseAt(timeSec: Float, index: Int, base: Pose): Pose
}

/**
 * 可提升的 2.5D 场景状态。
 *
 * 线程纪律：UI 线程改结构（[submit] / [update] / [motion] / [burst]），
 * GL 线程每帧调 [snapshot] 取一份零分配快照；两者只锁结构，不锁每个字段
 * （字段全不可变，天然跨线程可见）。
 *
 * ```
 * val scene = remember { Scene() }
 * scene.submit(Layouts.grid(200, ...).mapIndexed { i, p -> Node("n$i", p, "tile") })
 * scene.motion = Motion { t, i, base -> base.copyWith(rotation = t * 30f + i) }
 * SpriteStage(scene, textures, onPick = { pick -> ... })
 * ```
 */
class Scene {

    private val lock = Any()

    /** 绘制顺序即 z 序（后画的在上）。只经 [submit] / [update] 改动。 */
    private var nodes: List<Node> = emptyList()

    /** 双缓冲快照：60fps 下每帧新建 ArrayList 会造成年轻代 GC 尖刺。只有 GL 线程调用。 */
    private val buffers = arrayOf(ArrayList<Node>(256), ArrayList<Node>(256))
    private var flip = 0

    /** 结构/贴图发生变化：GL 线程消费后复位，避免每帧重复建索引。 */
    @Volatile
    var dirty: Boolean = true
        private set

    /** 每帧位姿函数；null = 静态场景。GL 线程读，UI 线程赋值（赋值会置脏）。 */
    @Volatile
    var motion: Motion? = null
        set(value) {
            field = value
            dirty = true
        }

    /** 是否画背景星辉粒子（GPU 端算运动，CPU 零参与）。 */
    @Volatile
    var starfield: Boolean = false

    /** 是否画自带深色渐变底；false = 透明，合成在下面的 Compose UI 之上。 */
    @Volatile
    var background: Boolean = false

    /** 待消费的一次性粒子爆发（归一化 x/y + 颜色 ARGB + 数量），GL 线程取走后清空。 */
    @Volatile
    private var pendingBurst: Burst? = null

    /** 全量替换节点。按 id 去重由调用方保证；顺序即层级。 */
    fun submit(items: List<Node>) {
        synchronized(lock) { nodes = items.toList() }
        dirty = true
    }

    /** 在锁内原地修改节点列表（改某个 Node 的 pose / effects 等）。 */
    fun update(block: (MutableList<Node>) -> Unit) {
        synchronized(lock) {
            val mutable = nodes.toMutableList()
            block(mutable)
            nodes = mutable
        }
        dirty = true
    }

    /** 请求一次粒子爆发；下一次 GL 帧取走（只保留最后一次，避免堆积）。 */
    fun burst(nx: Float, ny: Float, color: Int = 0xFFD6B260.toInt(), count: Int = 96) {
        pendingBurst = Burst(nx, ny, color, count)
    }

    /** GL 线程：取走并清空待爆发请求。 */
    internal fun consumeBurst(): Burst? = pendingBurst.also { pendingBurst = null }

    /** GL 线程：读并复位脏标记。 */
    internal fun consumeDirty(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }

    /** GL 线程每帧调用：零分配的节点快照。 */
    fun snapshot(): List<Node> = synchronized(lock) {
        val idx = flip
        flip = 1 - idx
        val buf = buffers[idx]
        buf.clear()
        buf.addAll(nodes)
        buf
    }

    /** 当前节点数（UI 线程用，走锁）。 */
    val size: Int get() = synchronized(lock) { nodes.size }

    /**
     * 变换空间命中：从最上层往下找第一张覆盖 (px,py) 的图元。
     * 点在像素坐标，命中会把旋转考虑进去（转到图元局部坐标再比包围盒）。
     */
    fun hitTest(px: Float, py: Float, viewWidth: Float, viewHeight: Float): Pick? {
        val list = synchronized(lock) { nodes }
        for (i in list.indices.reversed()) {
            val n = list[i]
            val p = n.pose
            val halfW = p.width * viewWidth * p.scale * 0.5f
            val halfH = p.height * viewHeight * p.scale * 0.5f
            val cx = p.x * viewWidth
            val cy = p.y * viewHeight
            val rad = Math.toRadians(p.rotation.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val dx = px - cx
            val dy = py - cy
            // 屏幕顺时针旋转 R(θ) 的逆变换 R(-θ)
            val lx = dx * c + dy * s
            val ly = -dx * s + dy * c
            if (abs(lx) <= halfW && abs(ly) <= halfH) {
                return Pick(n.id, i, cornersOf(cx, cy, halfW, halfH, c, s))
            }
        }
        return null
    }

    private fun cornersOf(
        cx: Float, cy: Float, halfW: Float, halfH: Float, c: Float, s: Float,
    ): FloatArray {
        // 左上 → 右上 → 右下 → 左下
        val lx = floatArrayOf(-halfW, halfW, halfW, -halfW)
        val ly = floatArrayOf(-halfH, -halfH, halfH, halfH)
        val out = FloatArray(8)
        for (i in 0..3) {
            out[i * 2] = cx + lx[i] * c - ly[i] * s
            out[i * 2 + 1] = cy + lx[i] * s + ly[i] * c
        }
        return out
    }

    internal data class Burst(val nx: Float, val ny: Float, val color: Int, val count: Int)
}
