package com.arcana.carddeck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/** demo 自足的程序化贴图：不依赖任何 drawable 资源，也不在库里画一个字。 */
object Art {

    private const val GOLD = 0xFFD6B260.toInt()

    /** 3:5 深蓝金星牌背。 */
    fun cardBack(): Bitmap {
        val w = 360
        val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    Color.rgb(24, 30, 62), Color.rgb(10, 13, 30), Shader.TileMode.CLAMP,
                )
            },
        )
        c.drawRoundRect(
            RectF(12f, 12f, w - 12f, h - 12f), 22f, 22f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = GOLD; strokeWidth = 6f
            },
        )
        c.drawRoundRect(
            RectF(26f, 26f, w - 26f, h - 26f), 14f, 14f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = GOLD; strokeWidth = 2f; alpha = 150
            },
        )
        val cx = w / 2f
        val cy = h / 2f
        val star = Path()
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            val r = if (i % 2 == 0) 58f else 23f
            val x = cx + (Math.cos(a) * r).toFloat()
            val y = cy + (Math.sin(a) * r).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD })
        c.drawCircle(cx, cy, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 30, 62) })
        return bmp
    }

    /** 牌正面：暖色渐层 + 同心几何纹（无文字，纯图）。 */
    fun cardFace(): Bitmap {
        val w = 360
        val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    Color.rgb(63, 46, 92), Color.rgb(22, 18, 46), Shader.TileMode.CLAMP,
                )
            },
        )
        c.drawRoundRect(
            RectF(14f, 14f, w - 14f, h - 14f), 20f, 20f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = GOLD; strokeWidth = 5f
            },
        )
        val cx = w / 2f
        val cy = h / 2f
        for (r in intArrayOf(46, 78, 110, 142)) {
            c.drawCircle(
                cx, cy, r.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = GOLD; strokeWidth = 2f; alpha = 170
                },
            )
        }
        c.drawCircle(cx, cy, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD })
        return bmp
    }

    /** 性能对比用的小色块：6 种色相循环，200 张只占 6 张贴图，验证「图集/复用」。 */
    fun tile(variant: Int): Bitmap {
        val w = 128
        val h = 128
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hues = floatArrayOf(265f, 205f, 42f, 330f, 160f, 15f)
        val base = Color.HSVToColor(floatArrayOf(hues[variant % hues.size], 0.58f, 0.72f))
        val dark = Color.HSVToColor(floatArrayOf(hues[variant % hues.size], 0.72f, 0.26f))
        c.drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), base, dark, Shader.TileMode.CLAMP)
            },
        )
        c.drawRoundRect(
            RectF(6f, 6f, w - 6f, h - 6f), 14f, 14f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = GOLD; strokeWidth = 3f; alpha = 200
            },
        )
        c.drawCircle(w / 2f, h / 2f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD; alpha = 220 })
        return bmp
    }
}
