package com.example.disasterpreparedness

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ==========================================
// 1. 資料模型結構 (與 iOS App 完全對齊)
// ==========================================
data class DisasterCheckResult(
    @SerializedName("score") val score: Int,
    @SerializedName("advice") val advice: String,
    @SerializedName("missingItems") val missingItems: List<String>?,
    @SerializedName("overstockedItems") val overstockedItems: List<String>?
)

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    // 🌟【動態身分與模式緩存】── 攔截自網頁 O2O 喚醒的真實參數
    private val webUserId = mutableStateOf("test_android_user")
    private val webStudentName = mutableStateOf("Android學生")
    private val webMode = mutableStateOf("home-safety")

    // UI 顯示控制
    private val capturedBitmap = mutableStateOf<Bitmap?>(null)
    private val isLoading = mutableStateOf(false)
    private val aiResultText = mutableStateOf("等待拍攝防災萬用包物資...")
    private val auditScore = mutableStateOf(0)
    private val missingItemsList = mutableStateListOf<String>()
    private val overstockedItemsList = mutableStateListOf<String>()
    private val showResultSheet = mutableStateOf(false)

    // Gemini API 金鑰與 Supabase 配置 (神聖直連，不經 Firebase)
    private val GEMINI_API_KEY = "AIzaSyB0nqEdU_txzStkTBfhK95E5qbWA6YXpRw"
    private val SUPABASE_URL = "https://ovyygcsuiwwbgoywplbq.supabase.co/rest/v1/lms_disaster_progress"
    private val SUPABASE_ANON_KEY = "sb_publishable_Rfekybrmzpc7rjaHAq5Q8Q_ChnHYcAI"

    // 權限請求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "需要相機權限才能進行 AI 視覺健檢喔！", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. 攔截 Intent 解析 Deep Link
        handleDeepLink(intent)

        // 2. 請求權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 3. 載入 UI
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF020617), // Slate-950 質感深藍黑
                    surface = Color(0xFF0F172A),    // Slate-900 次級深色面
                    primary = Color(0xFF2563EB),    // Blue-600 核心藍
                    secondary = Color(0xFF10B981)  // Emerald-500 核心綠
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // 📡 核心攔截管線：拆解網頁行李箱 (支援 user_id, name, mode)
    private fun handleDeepLink(intent: Intent) {
        val uri: Uri? = intent.data
        if (uri != null) {
            Log.d("DisasterAndroid", "📥 接收到原始 Deep Link: $uri")
            val userId = uri.getQueryParameter("user_id") ?: uri.getQueryParameter("userid")
            val name = uri.getQueryParameter("name")
            val mode = uri.getQueryParameter("mode")

            if (!userId.isNullOrEmpty()) {
                webUserId.value = userId
            }
            if (!name.isNullOrEmpty()) {
                webStudentName.value = name
            }
            if (!mode.isNullOrEmpty()) {
                webMode.value = mode
            }
            Log.d("DisasterAndroid", "🎉 [解析成功] 姓名: ${webStudentName.value} | UUID: ${webUserId.value} | 模式: ${webMode.value}")
        }
    }

    // ==========================================
    // 🎨 UI 元件設計 (Slate 質感風格)
    // ==========================================
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        Box(modifier = Modifier.fillMaxSize()) {
            if (!showResultSheet.value) {
                // 相機鏡頭預覽層
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            imageCapture = ImageCapture.Builder().build()
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageCapture
                                )
                            } catch (exc: Exception) {
                                Log.e("DisasterAndroid", "ProcessCameraProvider 繫結失敗", exc)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 畫面上層的 UI 遮罩與提示語
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val prompt = when (webMode.value) {
                        "fire" -> "請對準家中的滅火器、住警器或逃生通道進行拍攝"
                        "earthquake" -> "請對準櫥櫃固定、電視固定或家電防滑進行拍攝"
                        "typhoon" -> "請對準沙包、防颱板或盆栽防護進行拍攝"
                        else -> "請對準防災物資 (避難包) 進行拍攝"
                    }

                    Text(
                        text = prompt,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 拍照按鈕
                    IconButton(
                        onClick = { takePhoto(context) },
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(6.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }

                // 懸浮退出按鈕 (左上角)
                IconButton(
                    onClick = { finish() },
                    modifier = Modifier
                        .padding(start = 20.dp, top = 40.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "退出",
                        tint = Color.White
                    )
                }
            } else {
                // 展示健檢書結果頁面
                ResultLayout()
            }

            // Loading 全域遮罩
            if (isLoading.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(50.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = aiResultText.value, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun ResultLayout() {
        val scrollState = rememberScrollState()

        val sheetTitle = when (webMode.value) {
            "fire" -> "居家防火配置 AI 健檢書"
            "earthquake" -> "家具防震固定 AI 健檢書"
            "typhoon" -> "防颱整備配置 AI 健檢書"
            else -> "防災萬用包 AI 健檢書"
        }

        val missingSectionTitle = when (webMode.value) {
            "fire" -> "官方基準目前缺少項目 (建議補齊)"
            "earthquake" -> "官方基準目前缺少固定 (建議加固)"
            "typhoon" -> "官方基準目前缺少準備 (建議改善)"
            else -> "官方基準目前缺少物資 (建議補齊)"
        }

        val overstockedSectionTitle = when (webMode.value) {
            "fire" -> "居家易燃雜物與逃生隱患"
            "earthquake" -> "家具擺設與重物傾倒隱患"
            "typhoon" -> "盆栽與抗風結構安全隱患"
            else -> "準備過多/過重項目 (建議調整)"
        }

        val colorAccent = if (auditScore.value >= 60) MaterialTheme.colorScheme.secondary else Color.Red

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020617)) // Slate-950
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = sheetTitle,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // 拍攝的圖片展示
            capturedBitmap.value?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "拍攝的防災物資",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 整備率大卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A)) // Slate-900
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "目前整備度完成率", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        text = "${auditScore.value}%",
                        color = colorAccent,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Icon(
                    imageVector = if (auditScore.value >= 60) Icons.Filled.Check else Icons.Filled.Warning,
                    contentDescription = "結果狀態",
                    tint = colorAccent,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 進度條
            LinearProgressIndicator(
                progress = auditScore.value / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = colorAccent,
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // AI 反饋建議
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x16F59E0B)) // Subtle amber
                    .border(1.dp, Color(0x4DF59E0B), RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.Info, contentDescription = "AI建議", tint = Color(0xFFF59E0B))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "AI 即時專家優化建議", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = aiResultText.value,
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 缺少物件
            SectionListView(
                title = missingSectionTitle,
                items = missingItemsList,
                icon = Icons.Filled.Warning,
                iconColor = Color.Red,
                emptyText = when (webMode.value) {
                    "fire" -> "🎉 恭喜！已備妥所有核心防火設備。"
                    "earthquake" -> "🎉 恭喜！家具與電器均有安全防震固定。"
                    "typhoon" -> "🎉 恭喜！防颱整備與物資相當齊全。"
                    else -> "🎉 恭喜！目前不缺少任何核心維生項目。"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 多餘/隱患物件
            SectionListView(
                title = overstockedSectionTitle,
                items = overstockedItemsList,
                icon = Icons.Filled.Info,
                iconColor = Color(0xFFF57C00),
                emptyText = when (webMode.value) {
                    "fire" -> "逃生通道順暢，無任何易燃雜物堵塞。"
                    "earthquake" -> "家具擺設安全合理，無高處墜落重物隱患。"
                    "typhoon" -> "室外無懸掛墜落風險，防風抗風良好。"
                    else -> "現有物資配置合理，無多餘或過重負擔。"
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 同步與退出按鈕
            Button(
                onClick = { syncToSupabase() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "將資料同步至提案健檢書", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = {
                showResultSheet.value = false
                capturedBitmap.value = null
            }) {
                Text(text = "重新拍攝", color = Color.Gray)
            }
        }
    }

    @Composable
    fun SectionListView(
        title: String,
        items: List<String>,
        icon: ImageVector,
        iconColor: Color,
        emptyText: String
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (items.isEmpty()) {
                Text(
                    text = emptyText,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp)
                )
            } else {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 28.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(text = "•", color = iconColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = item, color = Color.LightGray, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }

    // ==========================================
    // 📸 CameraX 拍照管線與圖片壓縮
    // ==========================================
    private fun takePhoto(context: Context) {
        val imageCapture = imageCapture ?: return

        isLoading.value = true
        aiResultText.value = "拍攝擷取中..."

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    if (bitmap != null) {
                        // 旋轉與尺寸壓縮 (防HEIC/大圖上網過慢，限制在1024寬度以內)
                        val scaledBitmap = resizeBitmap(bitmap, 1024)
                        capturedBitmap.value = scaledBitmap
                        analyzeImageWithGemini(scaledBitmap)
                    } else {
                        isLoading.value = false
                        Toast.makeText(context, "圖片處理失敗", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("DisasterAndroid", "拍照失敗: ${exception.message}", exception)
                    isLoading.value = false
                    Toast.makeText(context, "拍照失敗: ${exception.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        // 根據 Exif 旋轉度處理
        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0 && bitmap != null) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun resizeBitmap(bitmap: Bitmap, maxLimit: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val ratio = maxLimit.toFloat() / Math.max(width, height)
        if (ratio >= 1.0f) return bitmap

        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // ==========================================
    // 🧠 Gemini 2.5 Flash 視覺審計管線
    // ==========================================
    private fun analyzeImageWithGemini(bitmap: Bitmap) {
        val mode = webMode.value
        aiResultText.value = when (mode) {
            "fire" -> "正在分析消防安全設備配置..."
            "earthquake" -> "正在分析家具防震固定配置..."
            "typhoon" -> "正在分析防颱整備物資與措施..."
            else -> "正在比對消防署官方三日維生基準..."
        }

        val systemInstruction = when (mode) {
            "fire" -> """
                你是一位極度親切、溫柔且專業的「居家防火安全設備健檢專家」。
                請嚴謹審查圖片中是否包含：合格家用滅火器、住宅用火災警報器（住警器）或防火逃生通道暢通度。
                請針對畫面中的實體情況給出整備度評分（0~100）及優化建議。
                評分標準：滅火器 = 40分、住警器 = 30分、逃生通道暢通 = 30分。
                請輸出一個嚴格的 JSON 格式（不要包含任何 markdown 標記如 ```json）：
                {
                  "score": 0到100的整數,
                  "advice": "150字左右親切的專家建議與防護觀念引導",
                  "missingItems": ["未發現或不合格的項目"],
                  "overstockedItems": ["居家易燃雜物與逃生隱患項目"]
                }
                若畫面中完全沒有任何與防火安全相關的主體，總分必須判定為 0 分，並在 overstockedItems 內寫入「未發現相關防火設備，建議盡速準備合格滅火器與安裝住警器，以維護居家消防安全」。
            """.trimIndent()
            "earthquake" -> """
                你是一位極度親切、溫柔且專業的「家具防震加固與安全健檢專家」。
                請嚴謹審查圖片中是否包含：大型重型櫥櫃（衣櫃/書櫃）固定加固、電視防傾倒固定、家電（如冰箱/微波爐）防震防滑。
                請針對畫面中的實體情況給出整備度評分（0~100）及優化建議。
                評分標準：大型櫥櫃固定 = 40分、電視防傾倒 = 30分、家電防滑 = 30分。
                請輸出一個嚴格的 JSON 格式（不要包含任何 markdown 標記如 ```json）：
                {
                  "score": 0到100的整數,
                  "advice": "150字左右親切的專家建議與防護觀念引導",
                  "missingItems": ["缺少防震固定的項目"],
                  "overstockedItems": ["家具擺設與重物傾倒隱患項目"]
                }
                若畫面中完全沒有任何防震加固相關的主體，總分必須判定為 0 分，並在 overstockedItems 內寫入「未發現防震固定設備，建議對重型櫥櫃與電視進行L型金屬片或綁帶加固」。
            """.trimIndent()
            "typhoon" -> """
                你是一位極度親切、溫柔且專業的「防颱整備與防水淹防禦專家」。
                請嚴謹審查圖片中是否包含：防汛沙包或擋水板、陽台盆栽搬入室內或修剪防護、排水溝清理、防颱備水與照明。
                請針對畫面中的實體情況給出整備度評分（0~100）及優化建議。
                評分標準：防汛沙包/擋水板 = 40分、陽台盆栽保護 = 30分、備水/排水/照明 = 30分。
                請輸出一個嚴格的 JSON 格式（不要包含任何 markdown 標記如 ```json）：
                {
                  "score": 0到100的整數,
                  "advice": "150字左右親切的專家建議與防護觀念引導",
                  "missingItems": ["缺少或未完成的防颱措施項目"],
                  "overstockedItems": ["陽台雜物或防風隱患項目"]
                }
                若畫面中完全沒有任何防颱準備相關的主體，總分必須判定為 0 分，並在 overstockedItems 內寫入「未發現防颱整備物品，請於颱風來臨前備妥沙包並固定盆栽，防止強風豪雨損害」。
            """.trimIndent()
            else -> """
                你是一位極度親切、溫柔且專業的「應急避難包與維生物資專家」。
                請嚴謹審查畫面中的物資，並對照官方三日生存能量給出整備度評分（0~100）與超貼心管家建議。
                評分標準：飲用水 = 40分、乾糧 = 30分、照明/電池 = 15分、急救包/哨子 = 15分。
                請輸出一個嚴格的 JSON 格式（不要包含任何 markdown 標記如 ```json）：
                {
                  "score": 0到100的整數,
                  "advice": "150字左右親切的專家建議與物資引導",
                  "missingItems": ["缺少的生存物資項目"],
                  "overstockedItems": ["非維生不必要或過重行李"]
                }
                若畫面中完全沒有任何維生避難物資，總分必須判定為 0 分，並在 overstockedItems 內寫入「未發現任何核心防災物資，建議準備 3 日份飲用水與糧食」。
            """.trimIndent()
        }

        // 把圖片轉換成 Base64
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        // 使用 Coroutine 非同步調用 API
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()

                val responseSchema = """
                    {
                      "type": "OBJECT",
                      "properties": {
                        "score": {"type": "INTEGER"},
                        "advice": {"type": "STRING"},
                        "missingItems": {
                          "type": "ARRAY",
                          "items": {"type": "STRING"}
                        },
                        "overstockedItems": {
                          "type": "ARRAY",
                          "items": {"type": "STRING"}
                        }
                      },
                      "required": ["score", "advice", "missingItems", "overstockedItems"]
                    }
                """.trimIndent()

                val requestJson = """
                    {
                      "systemInstruction": {
                        "parts": [
                          {"text": ${Gson().toJson(systemInstruction)}}
                        ]
                      },
                      "contents": [
                        {
                          "parts": [
                            {"text": "請根據這張圖片進行分析："},
                            {
                              "inlineData": {
                                "mimeType": "image/jpeg",
                                "data": "$base64Image"
                              }
                            }
                          ]
                        }
                      ],
                      "generationConfig": {
                        "temperature": 0.1,
                        "responseMimeType": "application/json",
                        "responseSchema": $responseSchema
                      }
                    }
                """.trimIndent()

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = requestJson.toRequestBody(mediaType)
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val gson = Gson()
                    val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
                    val rawText = geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                    val cleanJson = rawText.replace("```json", "").replace("```", "").trim()

                    val result = gson.fromJson(cleanJson, DisasterCheckResult::class.java)

                    withContext(Dispatchers.Main) {
                        isLoading.value = false
                        auditScore.value = result.score
                        aiResultText.value = result.advice
                        missingItemsList.clear()
                        result.missingItems?.let { missingItemsList.addAll(it) }
                        overstockedItemsList.clear()
                        result.overstockedItems?.let { overstockedItemsList.addAll(it) }

                        showResultSheet.value = true
                    }
                } else {
                    throw Exception("API 回傳失敗, 狀態碼: ${response.code}, 訊息: $responseBody")
                }
            } catch (e: Exception) {
                Log.e("DisasterAndroid", "Gemini 請求錯誤", e)
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    // 啟用 Android 本地保底診斷機制 (對齊 iOS)
                    fallbackAuditResult(mode, e.localizedMessage ?: "網路連線異常")
                }
            }
        }
    }

    // 🚨 完美容錯：本機保底離線診斷書 (對齊 iOS)
    private fun fallbackAuditResult(mode: String, errorMsg: String) {
        auditScore.value = 0
        missingItemsList.clear()
        overstockedItemsList.clear()

        when (mode) {
            "fire" -> {
                aiResultText.value = "🚨【消防署官方防火指引提示】\n經智慧視覺審計判定，目前居家防火配置尚有改善空間。\n\n(💡 偵幕後訊息：$errorMsg)"
                missingItemsList.addAll(listOf(
                    "家用合格滅火器 (需確認壓力指針在綠色安全範圍內)",
                    "住宅用火災警報器 (住警器) (應安裝於天花板，且定期測試電力)",
                    "防火逃生通道暢通 (通道與樓梯間不得擺放鞋櫃、雜物或瓦斯罐)"
                ))
                overstockedItemsList.addAll(listOf(
                    "紙箱與易燃雜物 (易造成火勢延燒並阻礙逃生，建議立即清除)",
                    "鐵窗未開逃生口 (屬於防火逃生隱患，應加裝可內開的安全鎖逃生窗)"
                ))
            }
            "earthquake" -> {
                aiResultText.value = "🚨【消防署官方防震指引提示】\n經智慧視覺審計判定，目前家具固定配置尚有改善空間。\n\n(💡 偵幕後訊息：$errorMsg)"
                missingItemsList.addAll(listOf(
                    "大型重型櫥櫃固定 (書櫃與衣櫃需使用 L 型金屬片或防震帶牢固鎖在牆壁或樑柱上)",
                    "電視防傾倒固定 (液晶電視已用防傾倒鋼線或綁帶固定在電視櫃或牆面上)",
                    "廚房家電防滑防震 (冰箱背面有固定，微波爐/烤箱下方有黏貼式防滑墊片)"
                ))
                overstockedItemsList.addAll(listOf(
                    "床頭重型懸掛物 (地震時極易掉落砸傷頭部，建議移開)"
                ))
            }
            "typhoon" -> {
                aiResultText.value = "🚨【消防署官方防颱指引提示】\n經智慧視覺審計判定，目前防颱整備措施尚有改善空間。\n\n(💡 偵幕後訊息：$errorMsg)"
                missingItemsList.addAll(listOf(
                    "防汛沙包 / 擋水板 (低窪或易積水處應備妥，以防豪雨積水倒灌)",
                    "陽台盆栽移入室內 / 排水孔清理 (防範強風吹落砸傷人，或落葉堵塞排水孔導致積水)",
                    "緊急照明設備與備用水 (防範颱風強風豪雨導致局部或大範圍停電停水)"
                ))
                overstockedItemsList.addAll(listOf(
                    "窗戶貼X型膠帶 (消防署已證實完全無法增加抗風壓，僅能防玻璃碎裂飛散)"
                ))
            }
            else -> {
                aiResultText.value = "🚨【消防署官方維生指引提示】\n經智慧視覺審計判定，目前包裹缺乏核心三日生存能量。\n\n(💡 偵幕後訊息：$errorMsg)"
                missingItemsList.addAll(listOf(
                    "飲用水 (每人每天 3 公升，至少備足 3 天份共 9公升 / 或大瓶裝 6 瓶)",
                    "乾糧 / 餅乾 / 罐頭 (成人每日需 2000 大卡，高熱量避難糧食需達 1500~1800 公克)",
                    "手電筒 (需備專用高亮度 LED 1 支，並額外備妥全新 3 號/4 號鹼性電池 4~6 顆)",
                    "急救應急包 / 哨子 (內含高分貝防災哨、優碘棉片、無菌紗布與個人隨身藥品)"
                ))
                overstockedItemsList.addAll(listOf(
                    "筆記型電腦 (屬於非維生奢侈品，過重且排擠避難負重容量)",
                    "香水 (屬於非必要精細物品，無助於初期防禦與救生需求)"
                ))
            }
        }
        showResultSheet.value = true
    }

    // ==========================================
    // 📡 數據同步管線：直連 Supabase REST API (去 Firebase)
    // ==========================================
    private fun syncToSupabase() {
        isLoading.value = true
        aiResultText.value = "同步資料至雲端健檢書..."

        val userId = webUserId.value
        val name = webStudentName.value
        val score = auditScore.value
        val advice = aiResultText.value

        // 完美對齊：在 missing_items 首位插入 "mode:currentMode"
        val finalMissing = missingItemsList.toMutableList()
        finalMissing.add(0, "mode:${webMode.value}")

        val overstocked = overstockedItemsList.toList()

        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = format.format(Date())

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()

                val rowPayload = mapOf(
                    "user_id" to userId,
                    "student_name" to name,
                    "disaster_kit_score" to score,
                    "ai_feedback" to advice,
                    "missing_items" to finalMissing,
                    "overstocked_items" to overstocked,
                    "updated_at" to timestamp
                )

                // Payload 必須包裹在 Array 之中 (Supabase Bulk Insert 格式)
                val payloadJson = Gson().toJson(listOf(rowPayload))

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = payloadJson.toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(SUPABASE_URL)
                    .post(body)
                    .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .addHeader("apiKey", SUPABASE_ANON_KEY)
                    .addHeader("Prefer", "return=representation")
                    .build()

                val response = client.newCall(request).execute()
                val resBody = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "🎉 同步成功！進度已回寫至 LMS 教室！", Toast.LENGTH_LONG).show()
                        finish() // 成功後直接關閉，返回 Web 前端
                    } else {
                        Log.e("DisasterAndroid", "Supabase 同步失敗: ${response.code}, $resBody")
                        Toast.makeText(this@MainActivity, "同步失敗: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("DisasterAndroid", "Supabase 連線失敗", e)
                withContext(Dispatchers.Main) {
                    isLoading.value = false
                    Toast.makeText(this@MainActivity, "雲端同步出錯: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

// ==========================================
// 📦 Gemini API 頂層 JSON 反應結構模型
// ==========================================
data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<Candidate>
)

data class Candidate(
    @SerializedName("content") val content: Content
)

data class Content(
    @SerializedName("parts") val parts: List<Part>
)

data class Part(
    @SerializedName("text") val text: String
)
