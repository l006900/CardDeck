package com.arcana.carddeck

/**
 * GLSL 源码。ES 2.0 起步（minSdk 24 全覆盖），不用 ES 3 特性 —— 桌面渲染的
 * 复杂度到不了需要 compute / instancing 的量级，ES 2 反而把「哪块硬件都能跑」
 * 这个约束钉死了。
 */
internal object GlShaders {

    /* ------------------------------------------------------ 牌（含金箔高光） */

    /**
     * 牌顶点着色器 —— 「牌不是铁板」。
     *
     * 翻牌时由 [GlUtil.CARD_GRID] 细分出的网格顶点在这里按 X 位置做 Z 偏移：
     * 中间凸起、两侧边缘反向卷曲，弯曲幅度随 uBend（= sin(face·π)，翻到一半最大）
     * 起伏，静止时归零。旋转的透视仍由 CPU 侧模型矩阵提供 —— 这里只负责「纸的形变」，
     * 不再重复用 cos 压缩 x（旧实现 CPU 转一次、shader 再压一次，牌会僵硬地变窄）。
     *
     * 同时解析求出曲面法线并随翻转角绕 Y 旋转，交给片元做 Blinn-Phong 与菲涅尔。
     */
    const val CARD_VERTEX = """
        uniform mat4 uMvp;
        uniform float uAngle;      // 绕 Y 翻转进度 0..π
        uniform float uBend;       // 弯曲活跃度 0..1（翻到一半最大，静止为 0）
        attribute vec2 aPos;       // -1..1 的细分网格顶点
        attribute vec2 aUv;
        varying vec2 vUv;
        varying float vFront;
        varying float vFacingAmount;
        varying vec3 vNormal;
        varying float vBend;

        void main() {
            float c = cos(uAngle);
            float s = sin(uAngle);
            float x = aPos.x;

            // 纸张形变：中间鼓起（1-x²），两缘向后卷（x²）。
            // 顶点在未缩放的局部坐标里，牌半宽在引擎侧只有 ~0.03 世界单位，
            // 所以这里的幅度必须小一个数量级，否则牌会被拉扯变形。
            float bow  = 1.0 - x * x;
            float curl = x * x;
            float z = uBend * (0.012 * bow - 0.005 * curl);
            // 法线曲率与几何形变适度解耦：让高光/菲涅尔在翻面中扫得明显，
            // 又不至于把牌图扭歪（常见的材质 trick）。
            float curvature = 0.62 * x;
            vec3 n = normalize(vec3(-curvature * uBend, 0.0, 1.0));

            // 法线随牌一起绕 Y 旋转（与 CPU 侧 Matrix.rotateM 同向）
            vNormal = normalize(vec3(n.x * c + n.z * s, n.y, -n.x * s + n.z * c));
            vFront = sign(c);                 // >0 背面，<0 正面
            vFacingAmount = abs(c);
            vBend = uBend;
            vUv = aUv;
            gl_Position = uMvp * vec4(x, aPos.y, z, 1.0);
        }
    """

    /**
     * 牌片元着色器 —— 「金箔会反光」。
     *
     * 在真实牌图之上叠三层光：
     *   1. Blinn-Phong 漫反射 + 高光：曲面法线让光随翻面扫过牌面，不再是一张平贴图；
     *   2. 菲涅尔金边：牌转到侧面时 N·V→0，边缘泛起金色流光（UI 层做不出的效果）；
     *   3. 原有金箔 sweep + vignette + 侧棱衰减保留，翻开瞬间由 uGlow 点亮。
     * 所有叠加都刻意保守，避免盖掉 720×1200 真实牌面的插画细节。
     */
    const val CARD_FRAGMENT = """
        precision mediump float;
        uniform sampler2D uFace;
        uniform sampler2D uBack;
        uniform float uTime;
        uniform float uGlow;
        uniform float uReversed;
        uniform float uFoil;      // 金边/高光总强度（Effects.foil）
        uniform float uAlpha;     // 图元整体透明度（Pose.alpha）
        varying vec2 vUv;
        varying float vFront;
        varying float vFacingAmount;
        varying vec3 vNormal;
        varying float vBend;

        void main() {
            // 侧棱（正好 90°）时 quad 的屏幕投影退化成一条线，`mix(back, face, front)`
            // 会在此处露出 1px 亮线并随帧闪。直接丢掉这一瞬，比留一条假边干净。
            if (vFacingAmount < 0.015) discard;

            vec2 uv = vUv;
            float front = step(vFront, 0.0);
            if (front > 0.5) {
                uv.x = 1.0 - uv.x;
                if (uReversed > 0.5) uv = vec2(1.0 - uv.x, 1.0 - uv.y);
            }
            vec4 tex = mix(texture2D(uBack, vUv), texture2D(uFace, uv), front);

            vec3 N = normalize(vNormal);
            vec3 V = vec3(0.0, 0.0, 1.0);
            vec3 L = normalize(vec3(-0.38, 0.56, 0.74));
            vec3 H = normalize(L + V);
            vec3 gold = vec3(0.98, 0.82, 0.46);

            // 掠射程度：正对我 = 0，转到侧棱 = 1。所有「边缘流光」都由它**连续**驱动 ——
            // 原来乘的是 vBend（sinθ），静止时几乎为 0，牌就成了一张贴纸；改成由角度驱动后，
            // 牌越转到侧面金边越亮一条，静止时也保留一点金属感。
            float graze = pow(1.0 - clamp(vFacingAmount, 0.0, 1.0), 1.6);

            // ---- Blinn-Phong：漫反射只做轻微明暗，高光是金箔质感 ----
            float diff = max(dot(N, L), 0.0);
            float spec = pow(max(dot(N, H), 0.0), 34.0);
            tex.rgb *= mix(0.88, 1.0, 0.30 + 0.70 * diff);
            tex.rgb += gold * spec * (0.20 + 0.62 * graze) * uFoil;

            // ---- 菲涅尔金边：侧棱时点亮，边缘再收一道描边 ----
            float fres = pow(1.0 - clamp(abs(dot(N, V)), 0.0, 1.0), 3.0);
            float ed = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
            float rim = smoothstep(0.085, 0.0, ed);
            tex.rgb += gold * fres * (0.12 + 0.55 * graze) * (0.45 + 0.55 * rim) * uFoil;
            tex.rgb += gold * rim * fres * 0.30 * uFoil;

            // ---- 金箔流光：翻开瞬间沿对角线扫过 ----
            float band = uv.x * 0.8 + uv.y * 0.2;
            float sweep = fract(uTime * 0.13);
            float d = abs(band - sweep * 1.3 + 0.15);
            float sweepSpec = exp(-d * d * 260.0) * uGlow;
            tex.rgb += gold * sweepSpec * 0.85;

            // ---- vignette + 侧棱衰减 ----
            float edge = smoothstep(0.0, 0.08, min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)));
            tex.rgb *= mix(0.82, 1.0, edge);
            tex.rgb *= mix(0.45, 1.0, vFacingAmount);

            gl_FragColor = vec4(tex.rgb, tex.a * uAlpha);
        }
    """

    /** 假阴影：牌下的椭圆软阴影，随翻面角度缩放/变淡（AO 的廉价实现，无需 shadow map）。 */
    const val SHADOW_FRAGMENT = """
        precision mediump float;
        uniform float uStrength;
        varying vec2 vUv;
        void main() {
            vec2 p = (vUv - vec2(0.5)) * 2.0;
            p.y *= 1.22;
            float a = (1.0 - smoothstep(0.12, 1.0, length(p))) * uStrength;
            gl_FragColor = vec4(0.0, 0.0, 0.0, a);
        }
    """

    /* ------------------------------------------------------ 桌布 */

    /**
     * 桌布：全屏 quad，径向渐变 + 中心微亮。深紫蓝是品牌底色（#1b1430 一带），
     * 中心亮部给牌一个「被烛光照着」的舞台感 —— 占卜间的桌布/蜡烛（M2）把这里的
     * 渐变换成纹理即可，顶点与混合不变。
     */
    const val TABLE_FRAGMENT = """
        precision mediump float;
        uniform vec2 uResolution;
        uniform vec3 uInner;
        uniform vec3 uOuter;
        varying vec2 vUv;
        void main() {
            vec2 p = vUv;                       // 0..1
            vec2 c = p - vec2(0.5, 0.42);       // 光心略偏上（视觉重心）
            float r = length(c * vec2(1.15, 1.0));
            // 桌布色由调用方传入（占卜间预设）：inner = 渐变首色（光心），outer = 尾色。
            vec3 col = mix(uInner, uOuter, smoothstep(0.08, 0.85, r));
            // 微噪点：让大块渐变不至于「塑料」。两行 sin 叠加足够骗过眼睛。
            col += (sin(p.x * 640.0) + sin(p.y * 512.0)) * 0.0035;
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /* ------------------------------------------------------ 牌阵区 */

    /**
     * 牌阵 slot 框：**圆角细线 + 四角括角**，不要"厚直角方框"。
     *
     * 之前那版是「整块 30% 金填充 + 30% 宽度的实心直角边」，在牌桌上像给每个位置
     * 套了个相框 —— 用户原话「搞个那么厚的直角方框干啥，有没有审美」。牌位的作用
     * 只是提示「往哪摆」，它该退到牌布里去，而不是跟牌抢注意力。
     *
     * 现在三层，从弱到强：
     *  1. 底：几乎不可见的金雾（5%），只在视觉上把这块区域"温"一下；
     *  2. 线：圆角矩形（SDF 算，半径 0.14）细描边，1dp 出头；
     *  3. 角：四角各一段 L 形括号，稍粗稍亮 —— 取景框的锐度全在这里，
     *     中心留空，牌摆上去之后视线不会被"框"截断。
     *
     * 线宽由 uLine 传（相对半边长度的比例，由渲染侧按屏幕像素换算，不随牌大小变粗）。
     */
    const val SPREAD_FRAGMENT = """
        precision mediump float;
        uniform vec4 uColor;
        uniform float uLine;
        uniform float uAspectX;
        varying vec2 vUv;

        void main() {
            // 归一化到「半高 = 1」的物理空间再算距离。
            // 直接用 uv 算 SDF 会让**左右两条边比上下两条细**（框是 3:5 的窄长条，
            // x 方向的梯度只有 y 的 0.6 倍），放大看就是"上亮下亮、两侧发虚"。
            vec2 p = abs((vUv - 0.5) * 2.0);
            vec2 s = vec2(p.x * uAspectX, p.y);
            vec2 halfBox = vec2(uAspectX, 1.0);

            // 圆角矩形 SDF（<0 在内部），圆角半径按半高归一化
            const float R = 0.16;
            vec2 q = s - (halfBox - vec2(R));
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - R;

            // 1) 底雾：中心最淡、近边略实，避免整块色块
            //    （smoothstep 一律写正向再取 1-x：edge0 > edge1 的写法在 GLES 上是未定义行为）
            float inside = 1.0 - smoothstep(-0.30, 0.02, d);
            float fill = uColor.a * 0.055 * inside;

            // 2) 圆角细线（各向同宽）
            float line = 1.0 - smoothstep(0.0, uLine, abs(d));

            // 3) 四角 L 括号：**只落在直边上、从圆角结束处起算**，不提粗。
            //    前两版都栽在圆角上：按「距角近」判定会把那截圆弧整段点亮、加粗更糟，
            //    四角直接糊成四团亮斑。所以这里显式要求「已经离开圆弧」且「贴在这条边线上」，
            //    再沿边只取一段 band。
            float alongX = halfBox.x - s.x;   // 距左右边
            float alongY = halfBox.y - s.y;   // 距上下边
            float straightX = smoothstep(R - 0.02, R + 0.02, alongX);
            float straightY = smoothstep(R - 0.02, R + 0.02, alongY);
            float bandLen = 0.34;             // 括号长度（半高 = 1 的单位）
            // 沿边衰减：R 处最亮 → R+bandLen 处归零
            float segX = 1.0 - smoothstep(R + 0.02, R + bandLen, alongX);
            float segY = 1.0 - smoothstep(R + 0.02, R + bandLen, alongY);
            // 只在"这条边本身"上（离另一条边足够远才不是圆弧）
            float onEdgeY = 1.0 - smoothstep(uLine * 0.5, uLine * 3.0, alongY);
            float onEdgeX = 1.0 - smoothstep(uLine * 0.5, uLine * 3.0, alongX);
            float bracket = max(straightX * segX * onEdgeY, straightY * segY * onEdgeX);

            // 角上提亮到接近实色，中段留在 0.42 —— 取景框的锐度全靠这点对比
            float alpha = fill + line * uColor.a * mix(0.42, 0.92, bracket);
            gl_FragColor = vec4(uColor.rgb, clamp(alpha, 0.0, 1.0));
        }
    """

    /* ------------------------------------------------------ 星辉粒子 */

    /**
     * 粒子顶点着色器：全部运动都在 GPU 上算（CPU 零参与）。
     *
     * attribute 只有三个种子值（每颗粒子一组，初始化一次，之后再也不碰）：
     *   aSeed.x — 水平位置（0..1）
     *   aSeed.y — 上升速度 + 相位
     *   aSeed.z — 大小 / 亮度 / 色相（金或蓝）
     * 循环上升 = mod；闪烁 = sin(相位)；大小随距离衰减由透视 gl_Position.w 天然带出
     * （point size 乘 1/w），近处的星大、远处的星小，空间感免费。
     */
    const val PARTICLE_VERTEX = """
        uniform mat4 uMvp;
        uniform vec2 uResolution;
        uniform float uTime;
        attribute vec3 aSeed;
        varying float vAlpha;
        varying float vGold;
        void main() {
            float speed = 0.03 + aSeed.y * 0.05;
            float phase = aSeed.z * 6.2831;
            // y：循环上升（1.15 略超屏，无感回卷）；x：缓慢横漂。
            float y = mod(aSeed.y * 1.13 + uTime * speed, 1.15) * 2.0 - 1.0;
            float x = aSeed.x * 2.0 - 1.0 + sin(uTime * 0.35 + phase) * 0.04;
            vec3 pos = vec3(x * uResolution.x / uResolution.y, y * 0.96, -0.3);
            gl_Position = uMvp * vec4(pos, 1.0);
            // 闪烁：慢正弦 + 每粒错相。亮度 0.25..0.9。
            float twinkle = 0.55 + 0.45 * sin(uTime * (1.1 + aSeed.z) + phase);
            vAlpha = twinkle * (0.35 + aSeed.z * 0.4);
            vGold = step(0.72, aSeed.z);   // 28% 金、72% 冷蓝
            gl_PointSize = (1.4 + aSeed.z * 2.6) * uResolution.y / 720.0;
        }
    """

    /** 粒子片元：圆形衰减 + 加法混合会由管线做（blendFunc SRC_ALPHA, ONE）。 */
    const val PARTICLE_FRAGMENT = """
        precision mediump float;
        varying float vAlpha;
        varying float vGold;
        void main() {
            vec2 c = gl_PointCoord - vec2(0.5);
            float a = smoothstep(0.5, 0.05, length(c)) * vAlpha;
            vec3 cool = vec3(0.62, 0.68, 0.95);
            vec3 gold = vec3(0.95, 0.82, 0.50);
            gl_FragColor = vec4(mix(cool, gold, vGold) * a, a);
        }
    """

    /* ------------------------------------------------------ 体积光晕（抽卡揭示背光） */

    /**
     * 体积光晕：揭示瞬间牌背后的金色光晕 + 竖向光锥（god-ray），加法混合。
     *
     * 一张比牌略大的 quad（用 [PLAIN_VERTEX]），按 uv 距中心算径向辉光，再叠一条
     * 居中竖向光锥。轻微闪烁让光「活」着。uStrength 由引擎在揭示瞬间拉到 1、
     * 随后按 glow 衰减 —— 这是抽卡「仪式感」的视觉主峰，也是体积光（不是平面贴图）
     * 带来的层次。加法混合在管线里做（blendFunc SRC_ALPHA, ONE）。
     */
    const val HALO_FRAGMENT = """
        precision mediump float;
        uniform float uTime;
        uniform float uStrength;
        uniform vec3 uColor;
        varying vec2 vUv;
        void main() {
            vec2 p = vUv - vec2(0.5);
            // 径向辉光（略压扁成竖椭圆，贴合牌形）。
            float r = length(p * vec2(1.0, 1.35));
            float glow = smoothstep(0.55, 0.0, r);
            glow = pow(glow, 1.6);
            // 竖向光锥：x 收口 + y 向外淡出。
            float beam = smoothstep(0.16, 0.0, abs(p.x)) * smoothstep(0.62, 0.0, abs(p.y));
            // 轻微闪烁，让背光不是死板的一团。
            float flick = 0.86 + 0.14 * sin(uTime * 3.0 + p.x * 7.0);
            vec3 col = uColor * (glow * 1.25 + beam * 0.55) * flick * uStrength;
            float a = (glow * 0.9 + beam * 0.38) * uStrength;
            gl_FragColor = vec4(col, a);
        }
    """

    /* ------------------------------------------------------ 揭示粒子爆发 */

    /**
     * 爆发粒子顶点着色器：每颗粒子一组静态种子（方向 + 速度 + 相位），出生时
     * 由引擎给中心与出生时刻，之后位置 = 中心 + 方向 × 速度 × 年龄（带轻微减速）。
     * 一个固定粒子池被每次揭示复用（只换 uCenter / uBirth），省去每帧重建 buffer。
     */
    const val BURST_VERTEX = """
        uniform mat4 uMvp;
        uniform vec2 uCenter;     // 牌心世界坐标
        uniform float uZ;         // 粒子所在纵深（翻面时牌被拿到眼前，粒子要跟上去）
        uniform float uScale;     // 屏上放大系数（与透视一致，否则粒子会比牌小一圈）
        uniform float uTime;
        uniform float uBirth;
        uniform float uLife;
        attribute vec2 aDir;      // 单位方向（圆内均匀分布）
        attribute float aSpeed;
        attribute float aPhase;
        varying float vA;
        void main() {
            float age = uTime - uBirth;
            float k = clamp(age / uLife, 0.0, 1.0);
            float dist = aSpeed * age * (1.0 - 0.28 * k);   // 轻微减速
            vec2 pos = uCenter + aDir * dist;
            gl_Position = uMvp * vec4(pos, uZ, 1.0);
            float fade = 1.0 - k;
            vA = fade * fade;
            gl_PointSize = (4.5 + aPhase * 8.5) * (0.6 + 0.4 * fade) * uScale;
        }
    """

    /** 爆发粒子片元：圆形衰减 × 年龄淡出，加法混合。 */
    const val BURST_FRAGMENT = """
        precision mediump float;
        uniform vec3 uColor;
        varying float vA;
        void main() {
            vec2 c = gl_PointCoord - vec2(0.5);
            float a = smoothstep(0.5, 0.0, length(c)) * vA;
            gl_FragColor = vec4(uColor * a, a);
        }
    """

    /* ------------------------------------------------------ 公用顶点 */

    /** 全屏/单位 quad 共用顶点着色器（桌布、光晕共用）。 */
    const val PLAIN_VERTEX = """
        uniform mat4 uMvp;
        attribute vec2 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
        }
    """

    /* ------------------------------------------------------ 扇形牌堆（DeckEngine 专用） */

    /**
     * 牌堆里的**牌背**：圆角裁切 + 金边 + 斜向流光 + 暗角。
     *
     * 与桌面牌的 CARD_FRAGMENT 刻意分开：牌堆只画背面、不需要 3D 弯曲 / 正逆位 /
     * 菲涅尔，硬复用那套只会给每张牌白算一遍法线。几何（位置/角度/缩放）由
     * DeckGeometry 决定并在 CPU 侧算 MVP，这里只管「这张牌看起来是什么质感」。
     *
     * uniform:
     *   uBack    sampler2D，牌背纹理（单元 0）
     *   uTime    秒，单调递增 —— 流光相位
     *   uAngle   牌自身角度（弧度），让相邻牌的流光错开，不会整排一起闪
     *   uSelected 0/1，正上方那张的描边更亮
     *   uAspect  牌宽/高，等距空间校正圆角（720×1200 = 0.6）
     *   uGold    金色 rgb
     */
    const val DECK_FRAGMENT = """
        precision mediump float;
        uniform sampler2D uBack;
        uniform float uTime;
        uniform float uAngle;
        uniform float uSelected;
        uniform float uAspect;
        uniform vec3 uGold;
        varying vec2 vUv;
        void main() {
            vec2 p = (vUv - 0.5) * 2.0;
            vec2 e = vec2(p.x * uAspect, p.y);
            vec2 b = vec2(uAspect, 1.0);
            float R = 0.10;
            vec2 q = abs(e) - (b - vec2(R));
            float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - R;

            // smoothstep 一律正向写再取 1-x：GLES 上 edge0 > edge1 是未定义行为。
            float inside = 1.0 - smoothstep(-0.012, 0.012, sd);
            if (inside < 0.01) discard;

            vec3 tex = texture2D(uBack, vUv).rgb;

            // 斜向流光：相位带牌自身角度，转环时像光从牌堆表面扫过去。
            float phase = (vUv.x * 0.8 + vUv.y * 0.6) * 3.0 - uTime * 0.75 + uAngle;
            float s = max(sin(phase) * 0.5 + 0.5, 0.0);
            float sweep = pow(s, 14.0);

            float edge = 1.0 - smoothstep(0.0, 0.045, abs(sd + 0.022));
            tex += uGold * (sweep * 0.16 + uSelected * 0.06);
            // 只有选中那张给足金边；两侧若也描得一样亮，整圈金边会盖过牌背本身的花纹。
            tex = mix(tex, uGold, edge * (0.10 + uSelected * 0.62));

            gl_FragColor = vec4(tex, inside);
        }
    """

    /** 选中牌背后的一圈柔光，加法混合。quad 比牌大一圈，径向衰减。 */
    const val DECK_GLOW_FRAGMENT = """
        precision mediump float;
        uniform float uStrength;
        uniform vec3 uColor;
        varying vec2 vUv;
        void main() {
            vec2 p = (vUv - 0.5) * 2.0;
            float a = (1.0 - smoothstep(0.15, 1.0, length(p))) * uStrength;
            gl_FragColor = vec4(uColor, a);
        }
    """
}
