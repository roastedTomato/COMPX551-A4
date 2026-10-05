package com.example.polarh10activityviewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.polarh10activityviewer.ui.theme.PolarH10ActivityViewerTheme
import com.example.polarh10activityviewer.storage.SessionStorage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStorage.get(applicationContext)
        enableEdgeToEdge()
        setContent {
            PolarH10ActivityViewerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WelcomeScreen(
                        onEnterSession = {
                            startActivity(Intent(this@MainActivity, SensorActivity::class.java))
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(onEnterSession: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight) //最小高度=viewportHeight=maxHeight
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,//分散布局
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(56.dp)) {//画logo，但其实应该是导入logo图片才合适
                    drawCircle(Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF8B5CF6))))
                    val pulse = Path().apply {
                        moveTo(size.width * .20f, size.height * .50f)
                        lineTo(size.width * .33f, size.height * .50f)
                        lineTo(size.width * .40f, size.height * .39f)
                        lineTo(size.width * .47f, size.height * .66f)
                        lineTo(size.width * .55f, size.height * .24f)
                        lineTo(size.width * .64f, size.height * .76f)
                        lineTo(size.width * .71f, size.height * .50f)
                        lineTo(size.width * .81f, size.height * .50f)
                    }
                    drawPath(pulse, Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                Text(
                    "Viewer for Polar H10",
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    textAlign = TextAlign.Center
                )
            }
            Box(
                Modifier.padding(vertical = 32.dp).size(260.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(colors.primary.copy(alpha = .08f), radius = size.width * .38f,
                        center = Offset(size.width * .55f, size.height * .43f))
                    drawOval(colors.primary.copy(alpha = .14f),
                        topLeft = Offset(size.width * .22f, size.height * .91f),
                        size = Size(size.width * .60f, size.height * .04f))
                    listOf(.35f, .46f, .59f).forEachIndexed { index, y ->
                        drawLine(colors.primary.copy(alpha = .35f),
                            Offset(size.width * (if (index == 1) .02f else .10f), size.height * y),
                            Offset(size.width * .20f, size.height * y), strokeWidth = 4.dp.toPx())
                    }
                }
                Image(painterResource(R.drawable.welcome_runner), contentDescription = null,//不重要，屏幕阅读器可以忽略它
                    modifier = Modifier.fillMaxSize())
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    "Track your heart rate and movement during your activity.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onEnterSession,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = CircleShape
                ) {
                    Text("Enter Session  →", modifier = Modifier.padding(vertical = 6.dp),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WelcomePreview() {
    PolarH10ActivityViewerTheme {
        WelcomeScreen(onEnterSession = {})
    }
}

@Preview(showBackground = true)
@Composable
fun WelcomeDarkPreview() {
    PolarH10ActivityViewerTheme(darkTheme = true) {
        WelcomeScreen(onEnterSession = {})
    }
}
