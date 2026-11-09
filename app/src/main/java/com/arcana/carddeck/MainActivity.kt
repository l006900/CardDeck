package com.arcana.carddeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CardDeck 库的验证 sample：三个页面分别验证
 * 1) 高层扇形牌堆控件 [CardDeck]；
 * 2) 低层通用场景图 [SpriteStage]（布局 / 翻面 / 光晕 / 粒子 / 拖动）；
 * 3) 200 张卡同屏对跑的性能对比（Compose 原生 vs OpenGL 场景图）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SampleRoot() }
    }
}

@Composable
fun SampleRoot() {
    var page by remember { mutableIntStateOf(2) }
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF070A14))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 34.dp, start = 12.dp, end = 12.dp, bottom = 6.dp),
        ) {
            listOf("扇形牌堆", "场景图", "性能对比").forEachIndexed { i, name ->
                Button(
                    onClick = { page = i },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (page == i) Color(0xFFD6B260) else Color(0xFF232848),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                ) { Text(name, fontSize = 11.sp) }
            }
        }
        when (page) {
            0 -> DeckDemo()
            1 -> SceneDemo()
            else -> PerfDemo()
        }
    }
}
