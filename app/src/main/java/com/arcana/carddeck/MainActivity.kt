package com.arcana.carddeck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DeckDemo() }
    }
}

/**
 * 最小验证：78 张牌背排成扇形，拖动转环、点中间那张抽走。
 * 背景是 Compose 渐变（CardDeck 用 background = false 透明叠在上面），
 * 同时也验证了「透明 GL 层合成在普通 Compose UI 之上」这条链路。
 */
@Composable
fun DeckDemo() {
    val allIds = remember { (0 until 78).map { i -> "card-%02d".format(i) } }
    var drawn by remember { mutableStateOf(emptySet<String>()) }
    var picked by remember { mutableStateOf<String?>(null) }
    val back = remember { makeCardBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        ComposeColor(0xFF0B1030),
                        ComposeColor(0xFF1B1430),
                        ComposeColor(0xFF070A14),
                    )
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 72.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "CardDeck",
                color = ComposeColor(0xFFE9D7A6),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "剩余 ${allIds.size - drawn.size} 张 · 已抽 ${drawn.size}",
                color = ComposeColor(0xFFB9BED1),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = picked?.let { "抽到 $it" } ?: "拖动转环，点中间那张抽牌",
                color = ComposeColor(0xFF8A8FA3),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { drawn = emptySet(); picked = null }) { Text("重置") }
        }

        CardDeck(
            cardIds = allIds,
            drawnIds = drawn,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp),
            background = false,
            backBitmap = back,
            emptyText = "牌堆已空",
        ) { id, _ ->
            drawn = drawn + id
            picked = id
        }
    }
}

/** 程序化生成一张 3:5 的深蓝金星牌背（免资源文件，demo 自足）。 */
private fun makeCardBack(): Bitmap {
    val w = 360
    val h = 600
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)

    val bg = Paint().apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Color.rgb(24, 30, 62), Color.rgb(10, 13, 30), Shader.TileMode.CLAMP,
        )
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

    val gold = Color.rgb(214, 178, 96)
    c.drawRoundRect(
        RectF(12f, 12f, w - 12f, h - 12f), 22f, 22f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = gold; strokeWidth = 6f
        },
    )
    c.drawRoundRect(
        RectF(26f, 26f, w - 26f, h - 26f), 14f, 14f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = gold; strokeWidth = 2f; alpha = 150
        },
    )

    val cx = w / 2f
    val cy = h / 2f
    val star = Path()
    val outer = 58f
    val inner = 23f
    for (i in 0 until 8) {
        val ang = Math.toRadians((i * 45).toDouble())
        val r = if (i % 2 == 0) outer else inner
        val x = cx + (Math.cos(ang) * r).toFloat()
        val y = cy + (Math.sin(ang) * r).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
    }
    star.close()
    c.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; style = Paint.Style.FILL })
    c.drawCircle(
        cx, cy, 10f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 30, 62) },
    )
    return bmp
}
