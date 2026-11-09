package com.arcana.carddeck

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Demo 1 —— 扇形牌堆（高层 API [CardDeck]）。
 * 78 张牌背排成 ∩ 形拱，拖动转环、点中间那张抽走；透明 GL 合成在 Compose 渐变之上。
 */
@Composable
fun DeckDemo() {
    val allIds = remember { (0 until 78).map { i -> "card-%02d".format(i) } }
    var drawn by remember { mutableStateOf(emptySet<String>()) }
    var picked by remember { mutableStateOf<String?>(null) }
    val back = remember { Art.cardBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1030), Color(0xFF1B1430), Color(0xFF070A14)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("CardDeck · 扇形牌堆", color = Color(0xFFE9D7A6), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("剩余 ${allIds.size - drawn.size} 张 · 已抽 ${drawn.size}", color = Color(0xFFB9BED1), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                picked?.let { "抽到 $it" } ?: "拖动转环，点中间那张抽牌",
                color = Color(0xFF8A8FA3), fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { drawn = emptySet(); picked = null }) { Text("重置") }
        }

        CardDeck(
            cardIds = allIds,
            drawnIds = drawn,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(300.dp),
            background = false,
            backBitmap = back,
            emptyText = "牌堆已空",
        ) { id, _ ->
            drawn = drawn + id
            picked = id
        }
    }
}
