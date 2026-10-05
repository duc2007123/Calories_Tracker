package com.example.caloriestracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// =========================================================================================
// 1. DATA MODELS & ENUMS
// =========================================================================================

enum class Gender { MALE, FEMALE }

enum class Goal(val label: String, val calorieDelta: Int) {
    LOSE_FAT("Giảm mỡ (-500 kcal)", -500),
    MAINTAIN("Giữ cân (0 kcal)", 0),
    BUILD_MUSCLE("Tăng cơ (+300 kcal)", 300)
}

enum class WorkoutCategory { GYM, CARDIO }

enum class UnitType { GRAM, COUNT }

data class MacroNutrient(
    val protein: Float = 0f,
    val carb: Float = 0f,
    val fat: Float = 0f
) {
    val calories: Float get() = (protein * 4f) + (carb * 4f) + (fat * 9f)

    operator fun plus(other: MacroNutrient) = MacroNutrient(
        protein = this.protein + other.protein,
        carb = this.carb + other.carb,
        fat = this.fat + other.fat
    )

    operator fun times(multiplier: Float) = MacroNutrient(
        protein = this.protein * multiplier,
        carb = this.carb * multiplier,
        fat = this.fat * multiplier
    )
}

data class UserProfile(
    val id: String = "u_default",
    val name: String = "Tôi",
    val age: Int = 24,
    val gender: Gender = Gender.MALE,
    val heightCm: Float = 175f,
    val weightKg: Float = 70f,
    val goal: Goal = Goal.LOSE_FAT,
    val bmr: Float = 1680f,
    val tdee: Float = 2268f,
    val targetCaloriesIn: Float = 1768f,
    val targetCaloriesOut: Float = 500f,
    val targetMacros: MacroNutrient = MacroNutrient(132f, 198f, 49f)
)

data class FoodPreset(
    val id: String,
    val name: String,
    val defaultPortion: String,
    val category: String,
    val baseMacro: MacroNutrient
)

data class WorkoutPreset(
    val id: String,
    val name: String,
    val category: WorkoutCategory,
    val icon: String,
    val defaultSets: Int = 4,
    val defaultReps: Int = 10,
    val defaultWeightKg: Float = 50f,
    val defaultPace: String = "5:30",
    val defaultDurationMin: Float = 30f,
    val met: Float = 6.0f,
    val tag: String = "Gym"
)

data class RawIngredient(
    val id: String,
    val name: String,
    val category: String,
    val unitType: UnitType,
    val unitName: String,
    val defaultAmount: Float,
    val baseMacro: MacroNutrient, // per 100g if GRAM, per 1 unit if COUNT
    val icon: String = "🥩"
) {
    fun calculateMacro(amount: Float): MacroNutrient {
        return if (unitType == UnitType.GRAM) {
            baseMacro * (amount / 100f)
        } else {
            baseMacro * amount
        }
    }
}

data class LoggedFoodItem(
    val id: String = UUID.randomUUID().toString().take(6),
    val name: String,
    val portion: String,
    val macro: MacroNutrient,
    val timeLabel: String = "Trong ngày"
)

data class WorkoutItem(
    val id: String,
    val userId: String,
    val date: LocalDate,
    val category: WorkoutCategory,
    val title: String,
    val detail: String,
    val met: Float,
    val durationMin: Float,
    val isCompleted: Boolean = false
) {
    fun calculateBurnedCalories(userWeightKg: Float): Float {
        return met * userWeightKg * (durationMin / 60f)
    }
}

data class DailyLog(
    val date: LocalDate,
    val loggedFoods: List<LoggedFoodItem> = emptyList(),
    val currentMacros: MacroNutrient = MacroNutrient(),
    val totalCaloriesIn: Float = 0f,
    val activeCaloriesOut: Float = 0f
) {
    fun hasData(): Boolean = totalCaloriesIn > 0f || activeCaloriesOut > 0f || loggedFoods.isNotEmpty()

    fun getNaturalBurnCalories(userBmr: Float): Float = userBmr

    fun getTotalCaloriesOut(userBmr: Float): Float = getNaturalBurnCalories(userBmr) + activeCaloriesOut

    fun getCalorieBalance(userBmr: Float): Float = totalCaloriesIn - getTotalCaloriesOut(userBmr)
}

enum class Screen {
    ONBOARDING, DASHBOARD, ADD_FOOD, CREATE_WORKOUT, ANALYTICS, PROFILE_SWITCHER, EDIT_PROFILE
}

// =========================================================================================
// 2. STATE HOLDER WITH SEARCH, RAW INGREDIENTS, MULTI-DAY WORKOUT & STREAK
// =========================================================================================

data class AppState(
    val isOnboarded: Boolean = false,
    val profiles: List<UserProfile> = emptyList(),
    val activeProfileId: String = "u_default",
    val selectedDate: LocalDate = LocalDate.now(),
    val userLogs: Map<String, Map<LocalDate, DailyLog>> = emptyMap(),
    val userWorkouts: Map<String, List<WorkoutItem>> = emptyMap(),
    val previewMacro: MacroNutrient? = null,
    val previewFoodName: String = "",
    val previewPortionMultiplier: Float = 1.0f,
    val selectedFoodPreset: FoodPreset? = null,
    val selectedRawIngredient: RawIngredient? = null,
    val rawAmount: Float = 100f,
    val scannedImageUri: Uri? = null,
    val isScanningImage: Boolean = false,
    val backStack: List<Screen> = listOf(Screen.ONBOARDING)
) {
    val activeUser: UserProfile
        get() = profiles.find { it.id == activeProfileId } ?: UserProfile()

    val currentScreen: Screen
        get() = backStack.lastOrNull() ?: if (isOnboarded) Screen.DASHBOARD else Screen.ONBOARDING

    val currentDailyLog: DailyLog
        get() = userLogs[activeProfileId]?.get(selectedDate) ?: DailyLog(date = selectedDate)

    val currentWorkouts: List<WorkoutItem>
        get() = (userWorkouts[activeProfileId] ?: emptyList()).filter { it.date == selectedDate }
}

class CalorieTrackerStateHolder(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("calorie_tracker_v8_storage", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(AppState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    // 22+ MÓN ĂN DÂN DÃ & NGUYÊN LIỆU CƠ BẢN
    val rawFoodCatalog = listOf(
        RawIngredient("r1", "Thịt Lợn Nạc (Heo thăn)", "Thịt Heo", UnitType.GRAM, "g", 100f, MacroNutrient(26f, 0f, 4.5f), "🥩"),
        RawIngredient("r2", "Thịt Ba Chỉ Heo", "Thịt Heo", UnitType.GRAM, "g", 100f, MacroNutrient(16.5f, 0f, 21.5f), "🥓"),
        RawIngredient("r3", "Thịt Bò Thăn", "Thịt Bò", UnitType.GRAM, "g", 100f, MacroNutrient(26f, 0f, 8.5f), "🥩"),
        RawIngredient("r4", "Bắp Bò Luộc", "Thịt Bò", UnitType.GRAM, "g", 100f, MacroNutrient(28f, 0f, 5f), "🥩"),
        RawIngredient("r5", "Ức Gà Phi Lê", "Gia Cầm", UnitType.GRAM, "g", 100f, MacroNutrient(26f, 0f, 1.5f), "🍗"),
        RawIngredient("r6", "Đùi Gà Luộc", "Gia Cầm", UnitType.GRAM, "g", 100f, MacroNutrient(20f, 0f, 10.5f), "🍗"),
        RawIngredient("r7", "Đậu Phụ Trắng (Bìa)", "Đậu Hũ", UnitType.COUNT, "bìa", 1f, MacroNutrient(12f, 3f, 6f), "🧈"),
        RawIngredient("r8", "Đậu Phụ Rán", "Đậu Hũ", UnitType.COUNT, "bìa", 1f, MacroNutrient(14f, 4f, 12f), "🧈"),
        RawIngredient("r9", "Trứng Gà Luộc / Ốp", "Trứng", UnitType.COUNT, "quả", 1f, MacroNutrient(6.3f, 0.4f, 4.8f), "🥚"),
        RawIngredient("r10", "Trứng Vịt", "Trứng", UnitType.COUNT, "quả", 1f, MacroNutrient(9f, 1f, 9.8f), "🥚"),
        RawIngredient("r11", "Trứng Cút (5 quả)", "Trứng", UnitType.COUNT, "phần", 1f, MacroNutrient(6f, 0.5f, 5f), "🥚"),
        RawIngredient("r12", "Cá Thu Tươi", "Hải Sản", UnitType.GRAM, "g", 100f, MacroNutrient(19f, 0f, 12.5f), "🐟"),
        RawIngredient("r13", "Cá Hồi Phi Lê", "Hải Sản", UnitType.GRAM, "g", 100f, MacroNutrient(20f, 0f, 13f), "🐟"),
        RawIngredient("r14", "Cá Lóc (Cá Quả)", "Thủy Sản", UnitType.GRAM, "g", 100f, MacroNutrient(18.2f, 0f, 2.7f), "🐟"),
        RawIngredient("r15", "Tôm Sú / Tôm Nõn", "Hải Sản", UnitType.GRAM, "g", 100f, MacroNutrient(24f, 0.2f, 0.3f), "🦐"),
        RawIngredient("r16", "Khoai Lang Luộc", "Tinh Bột", UnitType.COUNT, "củ", 1f, MacroNutrient(2.4f, 30f, 0.2f), "🍠"),
        RawIngredient("r17", "Cơm Trắng", "Tinh Bột", UnitType.COUNT, "bát", 1f, MacroNutrient(4.2f, 44.5f, 0.5f), "🍚"),
        RawIngredient("r18", "Chuối Tiêu", "Trái Cây", UnitType.COUNT, "quả", 1f, MacroNutrient(1.1f, 22.8f, 0.3f), "🍌"),
        RawIngredient("r19", "Rau Muống Luộc", "Rau Xanh", UnitType.GRAM, "g", 150f, MacroNutrient(3.8f, 3.2f, 0.3f), "🥬"),
        RawIngredient("r20", "Rau Cải Ngọt Luộc", "Rau Xanh", UnitType.GRAM, "g", 150f, MacroNutrient(2.4f, 3.8f, 0.3f), "🥦"),
        RawIngredient("r21", "Sữa Tươi Không Đường", "Đồ Uống", UnitType.COUNT, "hộp", 1f, MacroNutrient(5.6f, 7.7f, 6.3f), "🥛"),
        RawIngredient("r22", "Lạc Luộc / Đậu Phộng", "Hạt", UnitType.GRAM, "g", 50f, MacroNutrient(6.8f, 10.5f, 11f), "🥜")
    )

    // 18+ MÓN ĂN VIỆT NAM TRUYỀN THỐNG
    val foodCatalog = listOf(
        FoodPreset("1", "Phở Bò Tái Nạm", "1 Bát vừa", "Món Nước", MacroNutrient(28f, 58f, 14f)),
        FoodPreset("2", "Cơm Tấm Sườn Bì Chả", "1 Đĩa đầy đủ", "Cơm", MacroNutrient(34f, 82f, 26f)),
        FoodPreset("3", "Bún Bò Huế Đặc Biệt", "1 Bát to", "Món Nước", MacroNutrient(32f, 62f, 22f)),
        FoodPreset("4", "Bún Chả Hà Nội", "1 Suất đầy đủ", "Bún", MacroNutrient(29f, 68f, 24f)),
        FoodPreset("5", "Bánh Mì Thịt Chả", "1 Ổ vừa", "Bánh Mì", MacroNutrient(18f, 48f, 16f)),
        FoodPreset("6", "Bún Đậu Mắm Tôm", "1 Mẹt vừa", "Bún", MacroNutrient(35f, 75f, 22f)),
        FoodPreset("7", "Gỏi Cuốn Tôm Thịt", "3 Cuốn (kèm tương)", "Khai Vị", MacroNutrient(16f, 32f, 6f)),
        FoodPreset("8", "Hủ Tiếu Nam Vang", "1 Tô vừa", "Món Nước", MacroNutrient(24f, 54f, 15f)),
        FoodPreset("9", "Bánh Cuốn Nóng Thịt", "1 Đĩa (kèm chả)", "Ăn Sáng", MacroNutrient(15f, 46f, 12f)),
        FoodPreset("10", "Bún Riêu Cua Đồng", "1 Bát đầy đủ", "Món Nước", MacroNutrient(24f, 56f, 15f)),
        FoodPreset("11", "Cơm Gà Hội An", "1 Đĩa", "Cơm", MacroNutrient(32f, 74f, 16f)),
        FoodPreset("12", "Bánh Xèo Miền Tây", "1 Cái vừa", "Bánh", MacroNutrient(14f, 45f, 20f)),
        FoodPreset("13", "Miến Gà Nước Dùng", "1 Bát vừa", "Món Nước", MacroNutrient(26f, 52f, 8f)),
        FoodPreset("14", "Xôi Xéo Mỡ Hành Ruốc", "1 Gói", "Ăn Sáng", MacroNutrient(14f, 78f, 12f)),
        FoodPreset("15", "Canh Chua Cá Lóc", "1 Bát to", "Canh", MacroNutrient(22f, 14f, 5f)),
        FoodPreset("16", "Chả Giò Rán (Nem)", "3 Chiếc", "Khai Vị", MacroNutrient(12f, 28f, 18f)),
        FoodPreset("17", "Yến Mạch Sữa Chua", "1 Hộp vừa", "Tráng Miệng", MacroNutrient(10f, 38f, 6f)),
        FoodPreset("18", "Sinh Tố Bơ Chuối", "1 Cốc 350ml", "Đồ Uống", MacroNutrient(6f, 48f, 16f))
    )

    // 24+ BÀI TẬP GYM, MÔN THỂ THAO & CARDIO
    val workoutCatalog = listOf(
        // GYM / THỂ HÌNH
        WorkoutPreset("w1", "Đẩy ngực ngang (Bench Press)", WorkoutCategory.GYM, "🏋️", 4, 10, 60f, "5:30", 25f, 5.5f, "Gym / Ngực"),
        WorkoutPreset("w2", "Đẩy ngực dốc lên (Incline Press)", WorkoutCategory.GYM, "🏋️", 4, 10, 50f, "5:30", 25f, 5.5f, "Gym / Ngực"),
        WorkoutPreset("w3", "Kéo xô lưng (Lat Pulldown)", WorkoutCategory.GYM, "🏋️", 4, 12, 45f, "5:30", 25f, 5.0f, "Gym / Lưng"),
        WorkoutPreset("w4", "Gánh đùi sau (Squat Barbell)", WorkoutCategory.GYM, "🏋️", 4, 8, 80f, "5:30", 30f, 6.0f, "Gym / Chân"),
        WorkoutPreset("w5", "Đạp đùi máy nghiêng (Leg Press)", WorkoutCategory.GYM, "🏋️", 4, 12, 100f, "5:30", 25f, 5.5f, "Gym / Chân"),
        WorkoutPreset("w6", "Kéo lưng đùi (Deadlift)", WorkoutCategory.GYM, "🏋️", 3, 6, 90f, "5:30", 25f, 6.5f, "Gym / Toàn thân"),
        WorkoutPreset("w7", "Đẩy vai tạ đơn (Shoulder Press)", WorkoutCategory.GYM, "🏋️", 4, 10, 16f, "5:30", 20f, 5.0f, "Gym / Vai"),
        WorkoutPreset("w8", "Cuốn tay trước (Bicep Curl)", WorkoutCategory.GYM, "🏋️", 3, 12, 12f, "5:30", 20f, 4.5f, "Gym / Tay"),
        WorkoutPreset("w9", "Đẩy tay sau kéo cáp (Tricep Pushdown)", WorkoutCategory.GYM, "🏋️", 3, 12, 25f, "5:30", 20f, 4.5f, "Gym / Tay"),
        WorkoutPreset("w10", "Gập bụng (Abdominal Crunch)", WorkoutCategory.GYM, "🏋️", 4, 20, 0f, "5:30", 15f, 4.0f, "Gym / Bụng"),
        WorkoutPreset("w11", "Hít xà đơn (Pull-up)", WorkoutCategory.GYM, "🏋️", 4, 8, 0f, "5:30", 20f, 6.0f, "Gym / Lưng"),
        WorkoutPreset("w12", "Chống đẩy / Hít đất (Push-up)", WorkoutCategory.GYM, "🏋️", 4, 15, 0f, "5:30", 20f, 5.0f, "Gym / Ngực"),

        // CÁC MÔN THỂ THAO & CARDIO NGOÀI TRỜI
        WorkoutPreset("w13", "Bóng đá / Đá bóng (Football)", WorkoutCategory.CARDIO, "⚽", 1, 1, 0f, "Sân 7 người", 60f, 8.5f, "Thể thao"),
        WorkoutPreset("w14", "Cầu lông (Badminton)", WorkoutCategory.CARDIO, "🏸", 1, 1, 0f, "Đấu đơn/đôi", 45f, 7.0f, "Thể thao"),
        WorkoutPreset("w15", "Bơi lội (Swimming)", WorkoutCategory.CARDIO, "🏊", 1, 1, 0f, "Bơi sải/ếch", 45f, 8.0f, "Thể thao"),
        WorkoutPreset("w16", "Bóng rổ (Basketball)", WorkoutCategory.CARDIO, "🏀", 1, 1, 0f, "Toàn sân", 60f, 7.5f, "Thể thao"),
        WorkoutPreset("w17", "Đạp xe ngoài trời (Cycling)", WorkoutCategory.CARDIO, "🚴", 1, 1, 0f, "18-22 km/h", 45f, 7.5f, "Cardio"),
        WorkoutPreset("w18", "Nhảy dây đốt mỡ (Jump Rope)", WorkoutCategory.CARDIO, "🪢", 1, 1, 0f, "120 nhịp/phút", 20f, 10.0f, "Cardio"),
        WorkoutPreset("w19", "Đi bộ nhanh (Brisk Walking)", WorkoutCategory.CARDIO, "🚶", 1, 1, 0f, "Pace 9:30 (6.3 km/h)", 45f, 4.5f, "Cardio"),
        WorkoutPreset("w20", "Chạy bộ ngoài trời (Running)", WorkoutCategory.CARDIO, "🏃", 1, 1, 0f, "Pace 5:30 (10.9 km/h)", 30f, 9.8f, "Cardio"),
        WorkoutPreset("w21", "Quần vợt (Tennis)", WorkoutCategory.CARDIO, "🎾", 1, 1, 0f, "Đánh sân cứng", 60f, 7.3f, "Thể thao"),
        WorkoutPreset("w22", "Bóng bàn (Table Tennis)", WorkoutCategory.CARDIO, "🏓", 1, 1, 0f, "Đối kháng", 45f, 4.5f, "Thể thao"),
        WorkoutPreset("w23", "Boxing / Đấm bao cát / Võ", WorkoutCategory.CARDIO, "🥊", 1, 1, 0f, "Cường độ cao", 45f, 9.0f, "Thể thao"),
        WorkoutPreset("w24", "Yoga / Giãn cơ (Stretching)", WorkoutCategory.CARDIO, "🧘", 1, 1, 0f, "Hít thở sâu", 45f, 3.0f, "Thư giãn")
    )

    init {
        loadDataFromDisk()
    }

    private fun loadDataFromDisk() {
        val today = LocalDate.now()
        val isOnboarded = prefs.getBoolean("is_onboarded", false)

        if (!isOnboarded) {
            _uiState.update { it.copy(isOnboarded = false, backStack = listOf(Screen.ONBOARDING)) }
            return
        }

        val profilesJson = prefs.getString("profiles_json", null)
        val loadedProfiles = mutableListOf<UserProfile>()
        if (profilesJson != null) {
            try {
                val arr = JSONArray(profilesJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    loadedProfiles.add(
                        UserProfile(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            age = o.getInt("age"),
                            gender = Gender.valueOf(o.getString("gender")),
                            heightCm = o.getDouble("heightCm").toFloat(),
                            weightKg = o.getDouble("weightKg").toFloat(),
                            goal = Goal.valueOf(o.getString("goal")),
                            bmr = o.getDouble("bmr").toFloat(),
                            tdee = o.getDouble("tdee").toFloat(),
                            targetCaloriesIn = o.getDouble("targetCaloriesIn").toFloat(),
                            targetCaloriesOut = o.getDouble("targetCaloriesOut").toFloat(),
                            targetMacros = MacroNutrient(
                                o.getDouble("macroP").toFloat(),
                                o.getDouble("macroC").toFloat(),
                                o.getDouble("macroF").toFloat()
                            )
                        )
                    )
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (loadedProfiles.isEmpty()) {
            val defaultUser = buildProfile("u_default", "Tôi", 24, Gender.MALE, 175f, 70f, Goal.LOSE_FAT)
            loadedProfiles.add(defaultUser)
        }

        val activeId = prefs.getString("active_profile_id", loadedProfiles.first().id) ?: loadedProfiles.first().id

        // Load Logs
        val userLogsMap = mutableMapOf<String, MutableMap<LocalDate, DailyLog>>()
        val logsJson = prefs.getString("user_logs_json", null)
        if (logsJson != null) {
            try {
                val rootObj = JSONObject(logsJson)
                rootObj.keys().forEach { uId ->
                    val userObj = rootObj.getJSONObject(uId)
                    val dateMap = mutableMapOf<LocalDate, DailyLog>()
                    userObj.keys().forEach { dKey ->
                        val item = userObj.getJSONObject(dKey)
                        val date = LocalDate.parse(dKey)

                        val foodsList = mutableListOf<LoggedFoodItem>()
                        if (item.has("foods")) {
                            val fArr = item.getJSONArray("foods")
                            for (fIdx in 0 until fArr.length()) {
                                val fObj = fArr.getJSONObject(fIdx)
                                foodsList.add(
                                    LoggedFoodItem(
                                        id = fObj.getString("id"),
                                        name = fObj.getString("name"),
                                        portion = fObj.getString("portion"),
                                        macro = MacroNutrient(
                                            fObj.getDouble("p").toFloat(),
                                            fObj.getDouble("c").toFloat(),
                                            fObj.getDouble("f").toFloat()
                                        ),
                                        timeLabel = fObj.optString("time", "Trong ngày")
                                    )
                                )
                            }
                        }

                        dateMap[date] = DailyLog(
                            date = date,
                            loggedFoods = foodsList,
                            currentMacros = MacroNutrient(
                                item.getDouble("p").toFloat(),
                                item.getDouble("c").toFloat(),
                                item.getDouble("f").toFloat()
                            ),
                            totalCaloriesIn = item.getDouble("totalIn").toFloat(),
                            activeCaloriesOut = item.getDouble("totalOut").toFloat()
                        )
                    }
                    userLogsMap[uId] = dateMap
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // Seed default logs if empty
        val activeLogMap = userLogsMap.getOrPut(activeId) { mutableMapOf() }
        if (activeLogMap.isEmpty()) {
            for (i in 365 downTo -30) {
                val pastDate = today.minusDays(i.toLong())
                val isPast = i > 0
                val baseIn = 1650f + ((i * 23) % 550)
                val baseOut = if (isPast) 420f else if (i == 0) 140f else 0f

                val sampleFoods = if (isPast) {
                    listOf(
                        LoggedFoodItem(name = "Phở Bò Tái Nạm", portion = "1 Bát vừa", macro = MacroNutrient(28f, 58f, 14f)),
                        LoggedFoodItem(name = "Cơm Tấm Sườn Bì Chả", portion = "1 Đĩa đầy đủ", macro = MacroNutrient(34f, 82f, 26f)),
                        LoggedFoodItem(name = "Ức Gà Phi Lê", portion = "200g", macro = MacroNutrient(52f, 0f, 3f))
                    )
                } else if (i == 0) {
                    listOf(
                        LoggedFoodItem(name = "Phở Bò Tái Nạm", portion = "1 Bát vừa", macro = MacroNutrient(28f, 58f, 14f)),
                        LoggedFoodItem(name = "Cơm Trắng", portion = "1 Bát", macro = MacroNutrient(4.2f, 44.5f, 0.5f))
                    )
                } else emptyList()

                activeLogMap[pastDate] = DailyLog(
                    date = pastDate,
                    loggedFoods = sampleFoods,
                    currentMacros = MacroNutrient(baseIn * 0.075f, baseIn * 0.11f, baseIn * 0.028f),
                    totalCaloriesIn = if (isPast) baseIn else (if (i == 0) 670f else 0f),
                    activeCaloriesOut = baseOut
                )
            }
        }

        // Load Workouts
        val userWorkoutsMap = mutableMapOf<String, MutableList<WorkoutItem>>()
        val workoutsJson = prefs.getString("user_workouts_json", null)
        if (workoutsJson != null) {
            try {
                val rootObj = JSONObject(workoutsJson)
                rootObj.keys().forEach { uId ->
                    val arr = rootObj.getJSONArray(uId)
                    val list = mutableListOf<WorkoutItem>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            WorkoutItem(
                                id = o.getString("id"),
                                userId = uId,
                                date = LocalDate.parse(o.getString("date")),
                                category = WorkoutCategory.valueOf(o.getString("category")),
                                title = o.getString("title"),
                                detail = o.getString("detail"),
                                met = o.getDouble("met").toFloat(),
                                durationMin = o.getDouble("durationMin").toFloat(),
                                isCompleted = o.getBoolean("isCompleted")
                            )
                        )
                    }
                    userWorkoutsMap[uId] = list
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        val mainUserWorkouts = userWorkoutsMap.getOrPut(activeId) { mutableListOf() }
        if (mainUserWorkouts.isEmpty()) {
            mainUserWorkouts.addAll(
                listOf(
                    WorkoutItem("w1", activeId, today, WorkoutCategory.CARDIO, "Chạy bộ công viên", "Pace 5:45 • 30 phút", met = 9.8f, durationMin = 30f, isCompleted = true),
                    WorkoutItem("w2", activeId, today, WorkoutCategory.GYM, "Bench Press (Đẩy ngực)", "4 sets x 10 reps (65kg)", met = 5.0f, durationMin = 25f, isCompleted = false),
                    WorkoutItem("w3", activeId, today, WorkoutCategory.GYM, "Squat Barbell", "4 sets x 8 reps (80kg)", met = 6.0f, durationMin = 30f, isCompleted = false)
                )
            )
        }

        _uiState.update {
            it.copy(
                isOnboarded = true,
                profiles = loadedProfiles,
                activeProfileId = activeId,
                userLogs = userLogsMap,
                userWorkouts = userWorkoutsMap,
                backStack = listOf(Screen.DASHBOARD)
            )
        }
    }

    private fun saveStateToDisk() {
        val state = _uiState.value
        val editor = prefs.edit()
        editor.putBoolean("is_onboarded", state.isOnboarded)
        editor.putString("active_profile_id", state.activeProfileId)

        // Save Profiles
        val pArr = JSONArray()
        state.profiles.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("age", p.age)
            o.put("gender", p.gender.name)
            o.put("heightCm", p.heightCm)
            o.put("weightKg", p.weightKg)
            o.put("goal", p.goal.name)
            o.put("bmr", p.bmr)
            o.put("tdee", p.tdee)
            o.put("targetCaloriesIn", p.targetCaloriesIn)
            o.put("targetCaloriesOut", p.targetCaloriesOut)
            o.put("macroP", p.targetMacros.protein)
            o.put("macroC", p.targetMacros.carb)
            o.put("macroF", p.targetMacros.fat)
            pArr.put(o)
        }
        editor.putString("profiles_json", pArr.toString())

        // Save Logs
        val logsRoot = JSONObject()
        state.userLogs.forEach { (uId, map) ->
            val uObj = JSONObject()
            map.forEach { (d, log) ->
                val lItem = JSONObject()
                lItem.put("p", log.currentMacros.protein)
                lItem.put("c", log.currentMacros.carb)
                lItem.put("f", log.currentMacros.fat)
                lItem.put("totalIn", log.totalCaloriesIn)
                lItem.put("totalOut", log.activeCaloriesOut)

                val fArr = JSONArray()
                log.loggedFoods.forEach { f ->
                    val fObj = JSONObject()
                    fObj.put("id", f.id)
                    fObj.put("name", f.name)
                    fObj.put("portion", f.portion)
                    fObj.put("p", f.macro.protein)
                    fObj.put("c", f.macro.carb)
                    fObj.put("f", f.macro.fat)
                    fObj.put("time", f.timeLabel)
                    fArr.put(fObj)
                }
                lItem.put("foods", fArr)
                uObj.put(d.toString(), lItem)
            }
            logsRoot.put(uId, uObj)
        }
        editor.putString("user_logs_json", logsRoot.toString())

        // Save Workouts
        val wRoot = JSONObject()
        state.userWorkouts.forEach { (uId, list) ->
            val arr = JSONArray()
            list.forEach { w ->
                val o = JSONObject()
                o.put("id", w.id)
                o.put("date", w.date.toString())
                o.put("category", w.category.name)
                o.put("title", w.title)
                o.put("detail", w.detail)
                o.put("met", w.met)
                o.put("durationMin", w.durationMin)
                o.put("isCompleted", w.isCompleted)
                arr.put(o)
            }
            wRoot.put(uId, arr)
        }
        editor.putString("user_workouts_json", wRoot.toString())

        editor.apply()
    }

    // NAVIGATION
    fun navigateTo(screen: Screen) {
        _uiState.update {
            it.copy(
                backStack = it.backStack + screen,
                previewMacro = null,
                previewFoodName = "",
                selectedFoodPreset = null,
                selectedRawIngredient = null
            )
        }
    }

    fun navigateBack(): Boolean {
        val stack = _uiState.value.backStack
        if (stack.size > 1) {
            _uiState.update {
                it.copy(
                    backStack = stack.dropLast(1),
                    previewMacro = null,
                    previewFoodName = "",
                    selectedFoodPreset = null,
                    selectedRawIngredient = null
                )
            }
            return true
        }
        return false
    }

    // MULTI-USER MANAGEMENT
    fun switchActiveProfile(profileId: String) {
        _uiState.update { it.copy(activeProfileId = profileId) }
        saveStateToDisk()
        navigateBack()
    }

    fun addNewProfile(name: String, age: Int, gender: Gender, heightCm: Float, weightKg: Float, goal: Goal) {
        val newId = "u_${UUID.randomUUID().toString().take(6)}"
        val newProfile = buildProfile(newId, name, age, gender, heightCm, weightKg, goal)
        _uiState.update {
            it.copy(
                profiles = it.profiles + newProfile,
                activeProfileId = newId
            )
        }
        saveStateToDisk()
        navigateBack()
    }

    fun completeFirstOnboarding(name: String, age: Int, gender: Gender, heightCm: Float, weightKg: Float, goal: Goal) {
        val firstUser = buildProfile("u_primary", name.ifBlank { "Hồ sơ chính" }, age, gender, heightCm, weightKg, goal)
        _uiState.update {
            it.copy(
                isOnboarded = true,
                profiles = listOf(firstUser),
                activeProfileId = firstUser.id,
                backStack = listOf(Screen.DASHBOARD)
            )
        }
        saveStateToDisk()
    }

    private fun buildProfile(id: String, name: String, age: Int, gender: Gender, heightCm: Float, weightKg: Float, goal: Goal): UserProfile {
        val s = if (gender == Gender.MALE) 5f else -161f
        val bmr = (10f * weightKg) + (6.25f * heightCm) - (5f * age) + s
        val tdee = bmr * 1.35f
        val targetIn = (tdee + goal.calorieDelta).coerceAtLeast(1200f)
        val targetOut = 500f

        val targetProtein = (targetIn * 0.30f) / 4f
        val targetCarb = (targetIn * 0.45f) / 4f
        val targetFat = (targetIn * 0.25f) / 9f

        return UserProfile(
            id = id,
            name = name,
            age = age,
            gender = gender,
            heightCm = heightCm,
            weightKg = weightKg,
            goal = goal,
            bmr = bmr,
            tdee = tdee,
            targetCaloriesIn = targetIn,
            targetCaloriesOut = targetOut,
            targetMacros = MacroNutrient(targetProtein, targetCarb, targetFat)
        )
    }

    // DATE NAVIGATION
    fun selectDate(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
    }

    fun jumpDateByDays(days: Long) {
        _uiState.update { it.copy(selectedDate = it.selectedDate.plusDays(days)) }
    }

    // GHOST BAR & RAW / PRESET SELECTION
    fun selectFoodPreset(preset: FoodPreset?) {
        if (preset == null) {
            _uiState.update { it.copy(selectedFoodPreset = null, previewMacro = null, previewFoodName = "") }
        } else {
            val macro = preset.baseMacro * _uiState.value.previewPortionMultiplier
            _uiState.update {
                it.copy(
                    selectedFoodPreset = preset,
                    selectedRawIngredient = null,
                    previewMacro = macro,
                    previewFoodName = preset.name
                )
            }
        }
    }

    fun selectRawIngredient(ingredient: RawIngredient?, amount: Float = 100f) {
        if (ingredient == null) {
            _uiState.update { it.copy(selectedRawIngredient = null, previewMacro = null, previewFoodName = "") }
        } else {
            val macro = ingredient.calculateMacro(amount)
            val portionLabel = if (ingredient.unitType == UnitType.GRAM) "${amount.toInt()}g" else "${amount.toInt()} ${ingredient.unitName}"
            _uiState.update {
                it.copy(
                    selectedRawIngredient = ingredient,
                    selectedFoodPreset = null,
                    rawAmount = amount,
                    previewMacro = macro,
                    previewFoodName = "${ingredient.name} ($portionLabel)"
                )
            }
        }
    }

    fun updateRawAmount(amount: Float) {
        val ingredient = _uiState.value.selectedRawIngredient
        if (ingredient != null) {
            val validAmount = amount.coerceAtLeast(1f)
            val macro = ingredient.calculateMacro(validAmount)
            val portionLabel = if (ingredient.unitType == UnitType.GRAM) "${validAmount.toInt()}g" else "${validAmount.toInt()} ${ingredient.unitName}"
            _uiState.update {
                it.copy(
                    rawAmount = validAmount,
                    previewMacro = macro,
                    previewFoodName = "${ingredient.name} ($portionLabel)"
                )
            }
        }
    }

    fun updatePortionMultiplier(multiplier: Float) {
        val currentPreset = _uiState.value.selectedFoodPreset
        val newMacro = if (currentPreset != null) currentPreset.baseMacro * multiplier else _uiState.value.previewMacro
        _uiState.update { it.copy(previewPortionMultiplier = multiplier, previewMacro = newMacro) }
    }

    fun setCustomPreviewMacro(name: String, macro: MacroNutrient?) {
        _uiState.update {
            it.copy(
                previewFoodName = name,
                previewMacro = macro,
                selectedFoodPreset = null,
                selectedRawIngredient = null
            )
        }
    }

    fun processNutritionImage(uri: Uri) {
        _uiState.update { it.copy(scannedImageUri = uri, isScanningImage = true) }
        val parsedMacro = MacroNutrient(protein = 18f, carb = 55f, fat = 12f)
        val parsedName = "Sản phẩm đóng gói (Scan OCR)"
        _uiState.update {
            it.copy(
                isScanningImage = false,
                previewFoodName = parsedName,
                previewMacro = parsedMacro,
                selectedFoodPreset = null,
                selectedRawIngredient = null
            )
        }
    }

    fun commitFoodLog(foodName: String = "Món ăn", macro: MacroNutrient) {
        val state = _uiState.value
        val uId = state.activeProfileId
        val date = state.selectedDate

        val currentLog = state.currentDailyLog
        val updatedFoods = currentLog.loggedFoods + LoggedFoodItem(
            name = foodName.ifBlank { "Món ăn" },
            portion = if (state.selectedRawIngredient != null) {
                if (state.selectedRawIngredient.unitType == UnitType.GRAM) "${state.rawAmount.toInt()}g" else "${state.rawAmount.toInt()} ${state.selectedRawIngredient.unitName}"
            } else if (state.selectedFoodPreset != null) {
                "${state.previewPortionMultiplier} phần"
            } else "1 khẩu phần",
            macro = macro
        )

        val updatedMacros = currentLog.currentMacros + macro
        val updatedCaloriesIn = currentLog.totalCaloriesIn + macro.calories

        val newDailyLog = currentLog.copy(
            loggedFoods = updatedFoods,
            currentMacros = updatedMacros,
            totalCaloriesIn = updatedCaloriesIn
        )

        val userDateMap = state.userLogs[uId]?.toMutableMap() ?: mutableMapOf()
        userDateMap[date] = newDailyLog

        val updatedUserLogs = state.userLogs.toMutableMap()
        updatedUserLogs[uId] = userDateMap

        _uiState.update {
            it.copy(
                userLogs = updatedUserLogs,
                previewMacro = null,
                previewFoodName = "",
                selectedFoodPreset = null,
                selectedRawIngredient = null
            )
        }
        saveStateToDisk()
        navigateBack()
    }

    // WORKOUTS - MULTI-DAY SCHEDULE SUPPORT
    fun addWorkoutScheduleMultiDays(
        name: String,
        category: WorkoutCategory,
        detail: String,
        met: Float,
        durationMin: Float,
        dates: List<LocalDate>
    ) {
        val state = _uiState.value
        val uId = state.activeProfileId

        val newWorkouts = dates.map { date ->
            WorkoutItem(
                id = UUID.randomUUID().toString().take(6),
                userId = uId,
                date = date,
                category = category,
                title = name.ifBlank { if (category == WorkoutCategory.GYM) "Tập Gym" else "Chạy bộ" },
                detail = detail,
                met = met,
                durationMin = durationMin,
                isCompleted = false
            )
        }

        val userWorkoutList = (state.userWorkouts[uId] ?: emptyList()) + newWorkouts
        val updatedUserWorkouts = state.userWorkouts.toMutableMap()
        updatedUserWorkouts[uId] = userWorkoutList.toMutableList()

        _uiState.update { it.copy(userWorkouts = updatedUserWorkouts) }
        saveStateToDisk()
        navigateBack()
    }

    fun toggleWorkoutCompletion(workoutId: String) {
        val state = _uiState.value
        val uId = state.activeProfileId
        val date = state.selectedDate

        val workouts = state.userWorkouts[uId]?.toMutableList() ?: mutableListOf()
        val index = workouts.indexOfFirst { it.id == workoutId }
        if (index != -1) {
            val oldItem = workouts[index]
            val updatedItem = oldItem.copy(isCompleted = !oldItem.isCompleted)
            workouts[index] = updatedItem

            val currentLog = state.currentDailyLog
            val activeUser = state.activeUser
            val burned = updatedItem.calculateBurnedCalories(activeUser.weightKg)
            val newActiveOut = if (updatedItem.isCompleted) {
                currentLog.activeCaloriesOut + burned
            } else {
                (currentLog.activeCaloriesOut - burned).coerceAtLeast(0f)
            }

            val newDailyLog = currentLog.copy(activeCaloriesOut = newActiveOut)
            val userDateMap = state.userLogs[uId]?.toMutableMap() ?: mutableMapOf()
            userDateMap[date] = newDailyLog

            val updatedUserLogs = state.userLogs.toMutableMap()
            updatedUserLogs[uId] = userDateMap
            val updatedUserWorkouts = state.userWorkouts.toMutableMap()
            updatedUserWorkouts[uId] = workouts

            _uiState.update {
                it.copy(
                    userLogs = updatedUserLogs,
                    userWorkouts = updatedUserWorkouts
                )
            }
            saveStateToDisk()
        }
    }

    // ANALYTICS CALCULATION
    fun calculateAnalytics(days: Int): AnalyticsResult {
        val state = _uiState.value
        val uId = state.activeProfileId
        val user = state.activeUser
        val userLogs = state.userLogs[uId] ?: emptyMap()

        val logsList = mutableListOf<DailyLog>()
        for (i in (days - 1) downTo 0) {
            val date = state.selectedDate.minusDays(i.toLong())
            val log = userLogs[date] ?: DailyLog(date = date)
            logsList.add(log)
        }

        val calorieValues = logsList.map { it.totalCaloriesIn }
        val mean = if (calorieValues.isNotEmpty()) calorieValues.average().toFloat() else 0f
        val variance = if (calorieValues.isNotEmpty()) {
            calorieValues.map { (it - mean).pow(2) }.average().toFloat()
        } else 0f
        val stdDev = sqrt(variance)

        val target = user.targetCaloriesIn

        val advice = buildString {
            append("🩺 Đánh giá dinh dưỡng (Tiêu chuẩn WHO) cho ${user.name} ($days ngày):\n")
            append("• Mức nạp trung bình: ${mean.toInt()} kcal/ngày (Mục tiêu: ${target.toInt()} kcal).\n")

            val diff = mean - target
            val pctDiff = (diff / target) * 100f

            when {
                abs(pctDiff) <= 12f -> {
                    append("• Đạt chuẩn dung sai WHO: Khẩu phần ăn dao động trong khoảng tự nhiên lành mạnh (±10-12%). Sự cân đối tương đối này rất bền vững, không cần ép ăn chuẩn xác từng gram mỗi ngày!\n")
                }
                pctDiff < -12f -> {
                    append("• Mức nạp đang thấp hơn khuyến nghị (-${abs(diff).toInt()} kcal). WHO khuyến cáo tránh cắt giảm calo quá sâu liên tục để bảo toàn khối cơ và hệ miễn dịch.\n")
                }
                else -> {
                    append("• Mức nạp thặng dư trung bình (+${diff.toInt()} kcal/ngày). Nếu không phải giai đoạn tăng cơ, bạn có thể bù đắp bằng các buổi tập thể thao 3-4 lần/tuần.\n")
                }
            }

            if (stdDev > 450f) {
                append("• Nhịp sinh học: Năng lượng giữa các ngày có độ dao động khá cao. Cố gắng ăn đúng bữa để dạ dày và trao đổi chất làm việc ổn định.")
            } else {
                append("• Tính đều đặn: Thói quen ăn uống của bạn rất ổn định qua các ngày (rất đáng khen ngợi!).")
            }
        }

        return AnalyticsResult(
            days = days,
            meanCalories = mean,
            stdDev = stdDev,
            adviceText = advice,
            logs = logsList
        )
    }
}

data class AnalyticsResult(
    val days: Int,
    val meanCalories: Float,
    val stdDev: Float,
    val adviceText: String,
    val logs: List<DailyLog>
)

// =========================================================================================
// 3. COMPOSE UI COMPONENTS - ULTRA MODERN HIGH-AESTHETIC REDESIGN
// =========================================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val stateHolder = remember { CalorieTrackerStateHolder(context) }
            val uiState by stateHolder.uiState.collectAsState()

            var showExitDialog by remember { mutableStateOf(false) }

            // BACK HANDLER
            BackHandler(enabled = true) {
                val handled = stateHolder.navigateBack()
                if (!handled) {
                    showExitDialog = true
                }
            }

            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("Thoát Ứng Dụng?", fontWeight = FontWeight.Bold) },
                    text = { Text("Bạn có chắc chắn muốn đóng ứng dụng không? Dữ liệu của bạn đã được lưu an toàn.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showExitDialog = false
                                (context as? Activity)?.finish()
                            }
                        ) {
                            Text("Thoát", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text("Ở lại", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),      // Neon Emerald
                    secondary = Color(0xFF38BDF8),    // Electric Blue
                    tertiary = Color(0xFFF59E0B),     // Vibrant Amber
                    background = Color(0xFF090D16),   // Deep Midnight
                    surface = Color(0xFF151C2C),      // Polished Slate Surface
                    surfaceVariant = Color(0xFF1E283D),
                    onPrimary = Color(0xFF041E15),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (uiState.currentScreen) {
                        Screen.ONBOARDING -> OnboardingScreen(stateHolder, uiState)
                        Screen.DASHBOARD -> DashboardScreen(stateHolder, uiState)
                        Screen.PROFILE_SWITCHER -> ProfileSwitcherScreen(stateHolder, uiState)
                        Screen.ADD_FOOD -> AddFoodScreen(stateHolder, uiState)
                        Screen.CREATE_WORKOUT -> CreateWorkoutScreen(stateHolder, uiState)
                        Screen.ANALYTICS -> AnalyticsScreen(stateHolder)
                        Screen.EDIT_PROFILE -> EditProfileScreen(stateHolder, uiState)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.1. ONBOARDING SCREEN
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun OnboardingScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        var name by remember { mutableStateOf(if (uiState.isOnboarded) "Người dùng ${uiState.profiles.size + 1}" else "Tôi") }
        var ageText by remember { mutableStateOf("24") }
        var heightText by remember { mutableStateOf("175") }
        var weightText by remember { mutableStateOf("70") }
        var gender by remember { mutableStateOf(Gender.MALE) }
        var goal by remember { mutableStateOf(Goal.LOSE_FAT) }

        val isCreatingSecondary = uiState.isOnboarded

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isCreatingSecondary) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = { stateHolder.navigateBack() }) {
                        Text("← Quay lại", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🏋️", fontSize = 36.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                if (isCreatingSecondary) "Thêm Hồ Sơ Cá Nhân" else "Khởi Tạo Mục Tiêu Calo",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text(
                "Tính toán BMR & TDEE chuẩn Mifflin-St Jeor",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tên người dùng") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { gender = Gender.MALE },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (gender == Gender.MALE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("Nam ♂", fontWeight = FontWeight.Bold, color = if (gender == Gender.MALE) Color.Black else Color.White)
                }
                Button(
                    onClick = { gender = Gender.FEMALE },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (gender == Gender.FEMALE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("Nữ ♀", fontWeight = FontWeight.Bold, color = if (gender == Gender.FEMALE) Color.Black else Color.White)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ageText,
                    onValueChange = { ageText = it },
                    label = { Text("Tuổi") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("Cao (cm)") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text("Nặng (kg)") },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Mục tiêu thể hình:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.LightGray, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(6.dp))
            Goal.values().forEach { g ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { goal = g },
                    color = if (goal == g) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF101624),
                    border = if (goal == g) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = goal == g, onClick = { goal = g })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(g.label, fontSize = 13.sp, fontWeight = if (goal == g) FontWeight.Bold else FontWeight.Normal, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = {
                    val age = ageText.toIntOrNull() ?: 24
                    val height = heightText.toFloatOrNull() ?: 175f
                    val weight = weightText.toFloatOrNull() ?: 70f
                    if (isCreatingSecondary) {
                        stateHolder.addNewProfile(name, age, gender, height, weight, goal)
                    } else {
                        stateHolder.completeFirstOnboarding(name, age, gender, height, weight, goal)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Hoàn Thành & Khởi Chạy", fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color.Black)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.2. DASHBOARD SCREEN (WITH STREAK FLAME ON DAYS WITH DATA & DATE PICKER)
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DashboardScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        val context = LocalContext.current
        val activeUser = uiState.activeUser
        val selectedDate = uiState.selectedDate
        val today = LocalDate.now()
        val currentLog = uiState.currentDailyLog
        val workoutsToday = uiState.currentWorkouts

        val isFutureDate = selectedDate.isAfter(today)
        val hasDailyData = currentLog.hasData()

        val naturalBurnBmr = currentLog.getNaturalBurnCalories(activeUser.bmr)
        val totalOutCal = currentLog.getTotalCaloriesOut(activeUser.bmr)
        val balanceCal = currentLog.getCalorieBalance(activeUser.bmr)

        val daysDifference = java.time.temporal.ChronoUnit.DAYS.between(selectedDate, today)

        // Native Android Date Picker
        val openDatePicker = {
            val dpd = DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    val picked = LocalDate.of(year, month + 1, dayOfMonth)
                    stateHolder.selectDate(picked)
                },
                selectedDate.year,
                selectedDate.monthValue - 1,
                selectedDate.dayOfMonth
            )
            dpd.show()
        }

        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { stateHolder.navigateTo(Screen.ANALYTICS) },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = Color.Black,
                    shape = CircleShape
                ) {
                    Text("📊", fontSize = 20.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // TOP HEADER: PROFILE SWITCHER & CLEAN DATE PICKER BUTTON
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Profile Switcher Chip
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { stateHolder.navigateTo(Screen.PROFILE_SWITCHER) },
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("👤", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(activeUser.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("${activeUser.weightKg}kg • BMR: ${activeUser.bmr.toInt()}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("▼", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    // DATE CONTROLS: "HÔM NAY" + "📅 CHỌN NGÀY CỤ THỂ"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (daysDifference != 0L) {
                            Button(
                                onClick = { stateHolder.selectDate(today) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Hôm nay", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }

                        // DATE PICKER MODAL TRIGGER
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { openDatePicker() },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📅", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Chọn ngày", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // CURRENT DATE TITLE & STATUS BADGE
                val formatter = DateTimeFormatter.ofPattern("EEEE, 'ngày' dd/MM/yyyy", java.util.Locale("vi"))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(selectedDate.format(formatter), fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
                        val relativeText = when {
                            daysDifference == 0L -> "Hôm nay • Đang diễn ra"
                            daysDifference > 0L -> "$daysDifference ngày trước (Quá khứ)"
                            else -> "${abs(daysDifference)} ngày tới (Tương lai)"
                        }
                        Text(
                            relativeText,
                            fontSize = 11.sp,
                            color = if (daysDifference == 0L) MaterialTheme.colorScheme.primary else if (daysDifference > 0L) Color(0xFF94A3B8) else MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 7-DAY DYNAMIC HORIZONTAL CALENDAR STRIP (STREAK FLAME LOGIC)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in -3..3) {
                        val date = selectedDate.plusDays(i.toLong())
                        val isSelected = i == 0
                        val dayLog = uiState.userLogs[activeUser.id]?.get(date) ?: DailyLog(date = date)

                        // NGÀY NÀO Ở HIỆN TẠI HOẶC QUÁ KHỨ CÓ GHI DỮ LIỆU THÌ HIỆN NGỌN LỬA STREAK
                        val hasRecordedData = !date.isAfter(today) && dayLog.hasData()

                        val dayOfWeekVi = when (date.dayOfWeek) {
                            DayOfWeek.MONDAY -> "T2"
                            DayOfWeek.TUESDAY -> "T3"
                            DayOfWeek.WEDNESDAY -> "T4"
                            DayOfWeek.THURSDAY -> "T5"
                            DayOfWeek.FRIDAY -> "T6"
                            DayOfWeek.SATURDAY -> "T7"
                            DayOfWeek.SUNDAY -> "CN"
                        }

                        Surface(
                            modifier = Modifier
                                .width(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { stateHolder.selectDate(date) },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    dayOfWeekVi,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.Black else Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${date.dayOfMonth}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    if (hasRecordedData) "🔥" else "•",
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.Black else Color(0xFF475569)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // SPACIOUS 2-COLUMN DAILY CALORIE BALANCE & EVALUATION CARD
                if (!isFutureDate && hasDailyData) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val whoTolerance = activeUser.targetCaloriesIn * 0.12f // Ngưỡng dung sai khuyến nghị WHO ±12% (~200 kcal)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚖️", fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("CÂN BẰNG NĂNG LƯỢNG", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                }

                                if (abs(balanceCal) <= whoTolerance) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            "CÂN BẰNG LÝ TƯỞNG (±${abs(balanceCal).toInt()} kcal)",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                } else if (balanceCal < -whoTolerance) {
                                    val isSafeDeficit = balanceCal >= -650f
                                    Surface(
                                        color = if (isSafeDeficit) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSafeDeficit) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFFEF4444).copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            if (isSafeDeficit) "THÂM HỤT AN TOÀN (-${abs(balanceCal).toInt()} kcal)" else "THÂM HỤT SÂU (-${abs(balanceCal).toInt()} kcal)",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isSafeDeficit) MaterialTheme.colorScheme.primary else Color(0xFFEF4444)
                                        )
                                    }
                                } else {
                                    val isMildSurplus = balanceCal <= whoTolerance * 2.5f
                                    Surface(
                                        color = if (isMildSurplus) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isMildSurplus) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f) else Color(0xFFEF4444).copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            if (isMildSurplus) "DƯ THỪA NHẸ (+${balanceCal.toInt()} kcal)" else "DƯ THỪA NHIỀU (+${balanceCal.toInt()} kcal)",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isMildSurplus) MaterialTheme.colorScheme.tertiary else Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 2-COLUMN SPACIOUS LAYOUT (IN VS TOTAL OUT)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Column 1: IN
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFF0C121E),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E283D))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("NẠP THỰC TẾ (IN)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("${currentLog.totalCaloriesIn.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        Text("kcal từ món ăn", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                    }
                                }

                                // Column 2: OUT
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFF0C121E),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E283D))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("TỔNG TIÊU THỤ (OUT)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("${totalOutCal.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        Text("BMR: ${naturalBurnBmr.toInt()} + Tập: ${currentLog.activeCaloriesOut.toInt()}", fontSize = 9.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Evaluation Text (Realistic WHO recommendation)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF0C121E),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                val evalText = when {
                                    abs(balanceCal) <= whoTolerance ->
                                        "🌿 Năng lượng nạp & tiêu thụ hôm nay ở trạng thái cân bằng lý tưởng theo tiêu chuẩn dung sai WHO (±10-15%). Chênh lệch nhỏ là hoàn toàn tự nhiên và rất lành mạnh!"
                                    balanceCal < -whoTolerance && balanceCal >= -650f ->
                                        "🎯 Thâm hụt ${abs(balanceCal).toInt()} kcal nằm trong ngưỡng an toàn chuẩn (300-600 kcal) giúp đốt mỡ bền vững, bảo toàn khối cơ và không gây kiệt sức."
                                    balanceCal < -650f ->
                                        "⚠️ Thâm hụt ${abs(balanceCal).toInt()} kcal khá sâu. WHO khuyến cáo không nên cắt giảm quá mức liên tục để tránh suy giảm miễn dịch và làm chậm trao đổi chất (BMR)."
                                    balanceCal <= whoTolerance * 2.5f ->
                                        "💪 Nạp thặng dư nhẹ ${balanceCal.toInt()} kcal rất tốt cho phát triển cơ bắp (Lean Bulking), có thể bù trừ linh hoạt qua các buổi tập tiếp theo trong tuần."
                                    else ->
                                        "⚠️ Lượng nạp vượt mức tiêu thụ ${balanceCal.toInt()} kcal. Bạn có thể duy trì vận động thể thao nhẹ (chạy bộ, đá bóng 30p) để cơ thể cân đối lại."
                                }
                                Text(
                                    evalText,
                                    modifier = Modifier.padding(12.dp),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                } else if (isFutureDate || !hasDailyData) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E283D))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📅", fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isFutureDate) "Ngày trong tương lai" else "Chưa có dữ liệu",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                            Text(
                                if (isFutureDate) "Chưa thể tính cân bằng calo cho ngày chưa diễn ra." else "Ngày này chưa có khẩu phần ăn hoặc bài tập nào được ghi nhận.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // PROGRESS CARDS (IN & OUT)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // IN PROGRESS
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🥗", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("NẠP (IN)", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "${currentLog.totalCaloriesIn.toInt()} / ${activeUser.targetCaloriesIn.toInt()}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = (currentLog.totalCaloriesIn / activeUser.targetCaloriesIn).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color(0xFF1E283D)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { stateHolder.navigateTo(Screen.ADD_FOOD) },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("＋ Thêm Món", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }

                    // OUT PROGRESS
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🔥", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ĐỐT TẬP (OUT)", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "${currentLog.activeCaloriesOut.toInt()} / ${activeUser.targetCaloriesOut.toInt()}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = (currentLog.activeCaloriesOut / activeUser.targetCaloriesOut).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = Color(0xFF1E283D)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { stateHolder.navigateTo(Screen.CREATE_WORKOUT) },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Text("＋ Lên Lịch", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // CHI TIẾT CÁC MÓN ĂN TRONG NGÀY
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chi tiết món đã ăn (${currentLog.loggedFoods.size} món):", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (currentLog.loggedFoods.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Chưa có món ăn nào trong ngày này. Bấm 'Thêm Món' để ghi nhật ký!",
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        currentLog.loggedFoods.forEach { food ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🍲", fontSize = 20.sp)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(food.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                            Text("${food.portion} • P:${food.macro.protein.toInt()}g C:${food.macro.carb.toInt()}g F:${food.macro.fat.toInt()}g", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        }
                                    }
                                    Text("+${food.macro.calories.toInt()} kcal", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // CHI TIẾT CÁC BÀI TẬP TRONG NGÀY
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lịch tập luyện (${workoutsToday.count { it.isCompleted }}/${workoutsToday.size} hoàn thành):", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (workoutsToday.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Không có bài tập nào vào ngày này.",
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        workoutsToday.forEach { workout ->
                            val burned = workout.calculateBurnedCalories(activeUser.weightKg).toInt()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { stateHolder.toggleWorkoutCompletion(workout.id) },
                                color = if (workout.isCompleted) Color(0xFF0F291E) else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (workout.isCompleted) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFF26334D))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = workout.isCompleted,
                                        onCheckedChange = { stateHolder.toggleWorkoutCompletion(workout.id) }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(workout.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (workout.isCompleted) Color.Gray else Color.White)
                                        Text("${workout.detail} • (~$burned kcal)", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                    }
                                    AnimatedVisibility(visible = workout.isCompleted, enter = fadeIn() + scaleIn()) {
                                        Text("🔥 +$burned kcal", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.3. PROFILE SWITCHER SCREEN
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProfileSwitcherScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Chuyển Đổi Người Dùng", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp).clickable { stateHolder.navigateBack() }
                        ) {
                            Text("← Quay lại", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Danh sách hồ sơ cá nhân:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))

                uiState.profiles.forEach { p ->
                    val isActive = p.id == uiState.activeProfileId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { stateHolder.switchActiveProfile(p.id) },
                        color = if (isActive) Color(0xFF1E2F4D) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.secondary else Color(0xFF26334D))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("👤", fontSize = 28.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("${p.age} tuổi • ${p.weightKg}kg • BMR: ${p.bmr.toInt()} kcal", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text("Mục tiêu: ${p.targetCaloriesIn.toInt()} kcal/ngày", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (isActive) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Đang chọn ✓", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = { stateHolder.navigateTo(Screen.ONBOARDING) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("＋ Thêm Cá Nhân Mới", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.Black)
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.4. ADD FOOD SCREEN (WITH PRECISE GRAM/QUANTITY INPUT & GHOST BAR)
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AddFoodScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Dân dã / Tự chọn", "Món Việt (18+)", "Scan Bao Bì")

        var searchQuery by remember { mutableStateOf("") }
        var inputGramText by remember { mutableStateOf("100") }

        // Custom manual input state
        var customName by remember { mutableStateOf("") }
        var customProtein by remember { mutableStateOf("") }
        var customCarb by remember { mutableStateOf("") }
        var customFat by remember { mutableStateOf("") }
        var showCustomManual by remember { mutableStateOf(false) }

        val currentLog = uiState.currentDailyLog
        val targetMacros = uiState.activeUser.targetMacros

        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { stateHolder.processNutritionImage(it) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Thêm Khẩu Phần (Ghost Bar)", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp).clickable { stateHolder.navigateBack() }
                        ) {
                            Text("← Quay lại", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                // SEGMENTAL PILL TAB BAR
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            val isTabSel = selectedTab == index
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedTab = index
                                        stateHolder.selectFoodPreset(null)
                                        stateHolder.selectRawIngredient(null)
                                        searchQuery = ""
                                    },
                                color = if (isTabSel) MaterialTheme.colorScheme.primary else Color.Transparent
                            ) {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        title,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isTabSel) Color.Black else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // SEARCH BAR FOR TAB 0 & TAB 1
                if (selectedTab == 0 || selectedTab == 1) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                if (selectedTab == 0) "🔍 Tìm thịt bò, heo, gà, trứng, đậu, rau..." else "🔍 Tìm phở, cơm tấm, bún riêu, bánh mì...",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                Text(
                                    "✕",
                                    modifier = Modifier.clickable { searchQuery = "" }.padding(8.dp),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> { // TAB 0: RAW INGREDIENTS / DÂN DÃ (TỰ TÍNH CALO, Ô NHẬP GAM/SỐ LƯỢNG TÙY Ý)
                            val filteredRaw = remember(searchQuery) {
                                if (searchQuery.isBlank()) stateHolder.rawFoodCatalog
                                else stateHolder.rawFoodCatalog.filter {
                                    it.name.contains(searchQuery, ignoreCase = true) || it.category.contains(searchQuery, ignoreCase = true)
                                }
                            }

                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(filteredRaw) { item ->
                                    val isSelected = uiState.selectedRawIngredient?.id == item.id
                                    val currentAmount = if (isSelected) uiState.rawAmount else item.defaultAmount
                                    val macro = item.calculateMacro(currentAmount)

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                if (isSelected) {
                                                    stateHolder.selectRawIngredient(null)
                                                } else {
                                                    val initAmt = item.defaultAmount
                                                    inputGramText = initAmt.toInt().toString()
                                                    stateHolder.selectRawIngredient(item, initAmt)
                                                }
                                            },
                                        color = if (isSelected) Color(0xFF16253D) else MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else Color(0xFF26334D))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(item.icon, fontSize = 22.sp)
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Surface(
                                                                color = Color(0xFF1E283D),
                                                                shape = RoundedCornerShape(4.dp)
                                                            ) {
                                                                Text(
                                                                    item.category,
                                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                                    fontSize = 9.sp,
                                                                    color = Color(0xFF94A3B8)
                                                                )
                                                            }
                                                        }
                                                        Text(
                                                            if (item.unitType == UnitType.GRAM) "Chuẩn: ${item.baseMacro.calories.toInt()} kcal / 100g" else "Chuẩn: ${item.baseMacro.calories.toInt()} kcal / 1 ${item.unitName}",
                                                            fontSize = 11.sp,
                                                            color = Color(0xFF94A3B8)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    "${macro.calories.toInt()} kcal",
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            // CONTROLLER (Ô NHẬP GAM CỤ THỂ HOẶC SỐ LƯỢNG)
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Divider(color = Color(0xFF26334D))
                                                Spacer(modifier = Modifier.height(8.dp))

                                                if (item.unitType == UnitType.GRAM) {
                                                    // Ô NHẬP SỐ GAM TÙY Ý (VD: 80g, 120g...) + CHIP CHỌN NHANH
                                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text("Nhập khối lượng (gam):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)

                                                            OutlinedTextField(
                                                                value = inputGramText,
                                                                onValueChange = {
                                                                    inputGramText = it
                                                                    val num = it.toFloatOrNull() ?: 0f
                                                                    stateHolder.updateRawAmount(num)
                                                                },
                                                                label = { Text("Số gam (g)") },
                                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                                shape = RoundedCornerShape(10.dp),
                                                                modifier = Modifier.width(130.dp)
                                                            )
                                                        }

                                                        Row(
                                                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            listOf(50f, 80f, 100f, 150f, 200f, 250f, 300f).forEach { g ->
                                                                Surface(
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(8.dp))
                                                                        .clickable {
                                                                            inputGramText = g.toInt().toString()
                                                                            stateHolder.updateRawAmount(g)
                                                                        },
                                                                    color = if (currentAmount == g) MaterialTheme.colorScheme.primary else Color(0xFF0F172A),
                                                                    border = if (currentAmount == g) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                                                                ) {
                                                                    Text(
                                                                        "${g.toInt()}g",
                                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = if (currentAmount == g) Color.Black else Color(0xFF94A3B8)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // BỘ ĐẾM & NHẬP ĐƠN VỊ (QUẢ, BÌA, CỦ, BÁT, HỘP...)
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text("Số lượng (${item.unitName}):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)

                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Button(
                                                                onClick = {
                                                                    val next = (currentAmount - 1f).coerceAtLeast(1f)
                                                                    inputGramText = next.toInt().toString()
                                                                    stateHolder.updateRawAmount(next)
                                                                },
                                                                contentPadding = PaddingValues(0.dp),
                                                                modifier = Modifier.size(32.dp),
                                                                shape = RoundedCornerShape(8.dp),
                                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                                                            ) { Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }

                                                            Text("${currentAmount.toInt()} ${item.unitName}", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White)

                                                            Button(
                                                                onClick = {
                                                                    val next = currentAmount + 1f
                                                                    inputGramText = next.toInt().toString()
                                                                    stateHolder.updateRawAmount(next)
                                                                },
                                                                contentPadding = PaddingValues(0.dp),
                                                                modifier = Modifier.size(32.dp),
                                                                shape = RoundedCornerShape(8.dp),
                                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                                                            ) { Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    "Chi tiết Macros: P: ${macro.protein.toInt()}g | C: ${macro.carb.toInt()}g | F: ${macro.fat.toInt()}g",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }

                                // OPTION TO MANUALLY INPUT CUSTOM UNKNOWN FOOD
                                item {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { showCustomManual = !showCustomManual },
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("✍️ Tự nhập món khác (Custom Macros)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                                                Text(if (showCustomManual) "▲" else "▼", fontSize = 11.sp, color = Color.Gray)
                                            }

                                            if (showCustomManual) {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                OutlinedTextField(
                                                    value = customName,
                                                    onValueChange = { customName = it },
                                                    label = { Text("Tên món ăn") },
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    OutlinedTextField(
                                                        value = customProtein,
                                                        onValueChange = {
                                                            customProtein = it
                                                            val p = it.toFloatOrNull() ?: 0f
                                                            val c = customCarb.toFloatOrNull() ?: 0f
                                                            val f = customFat.toFloatOrNull() ?: 0f
                                                            stateHolder.setCustomPreviewMacro(customName, MacroNutrient(p, c, f))
                                                        },
                                                        label = { Text("P (g)") },
                                                        shape = RoundedCornerShape(10.dp),
                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    OutlinedTextField(
                                                        value = customCarb,
                                                        onValueChange = {
                                                            customCarb = it
                                                            val p = customProtein.toFloatOrNull() ?: 0f
                                                            val c = it.toFloatOrNull() ?: 0f
                                                            val f = customFat.toFloatOrNull() ?: 0f
                                                            stateHolder.setCustomPreviewMacro(customName, MacroNutrient(p, c, f))
                                                        },
                                                        label = { Text("C (g)") },
                                                        shape = RoundedCornerShape(10.dp),
                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    OutlinedTextField(
                                                        value = customFat,
                                                        onValueChange = {
                                                            customFat = it
                                                            val p = customProtein.toFloatOrNull() ?: 0f
                                                            val c = customCarb.toFloatOrNull() ?: 0f
                                                            val f = it.toFloatOrNull() ?: 0f
                                                            stateHolder.setCustomPreviewMacro(customName, MacroNutrient(p, c, f))
                                                        },
                                                        label = { Text("F (g)") },
                                                        shape = RoundedCornerShape(10.dp),
                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> { // TAB 1: EXPANDED PRESET CATALOG (18+ MÓN VIỆT VỚI TÌM KIẾM)
                            val filteredPresets = remember(searchQuery) {
                                if (searchQuery.isBlank()) stateHolder.foodCatalog
                                else stateHolder.foodCatalog.filter {
                                    it.name.contains(searchQuery, ignoreCase = true) || it.category.contains(searchQuery, ignoreCase = true)
                                }
                            }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Khẩu phần:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    listOf(0.5f to "0.5 phần", 1.0f to "1.0 phần", 1.5f to "1.5 phần", 2.0f to "2.0 phần").forEach { (multiplier, label) ->
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { stateHolder.updatePortionMultiplier(multiplier) },
                                            color = if (uiState.previewPortionMultiplier == multiplier) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            border = if (uiState.previewPortionMultiplier == multiplier) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                                        ) {
                                            Text(
                                                label,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (uiState.previewPortionMultiplier == multiplier) Color.Black else Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }

                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(filteredPresets) { food ->
                                        val isSelected = uiState.selectedFoodPreset?.id == food.id
                                        val macro = food.baseMacro * uiState.previewPortionMultiplier

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable { stateHolder.selectFoodPreset(if (isSelected) null else food) },
                                            color = if (isSelected) Color(0xFF16253D) else MaterialTheme.colorScheme.surface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else Color(0xFF26334D))
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(food.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(color = Color(0xFF1E283D), shape = RoundedCornerShape(4.dp)) {
                                                            Text(food.category, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                                        }
                                                    }
                                                    Text("${food.defaultPortion} • ${macro.calories.toInt()} kcal", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                                    Text(
                                                        "P: ${macro.protein.toInt()}g | C: ${macro.carb.toInt()}g | F: ${macro.fat.toInt()}g",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                                if (isSelected) {
                                                    Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                                                        Text("Đang xem 👁️", modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> { // TAB 2: REAL OCR SCANNER TAB
                            Column(
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                        .clickable { imagePickerLauncher.launch("image/*") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (uiState.isScanningImage) {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("AI đang đọc bảng dinh dưỡng trên ảnh...", fontSize = 12.sp, color = Color.LightGray)
                                        } else {
                                            Text("📷", fontSize = 32.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Bấm để Chọn Ảnh Bao Bì Quét Thật", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("AI tự động bóc tách Calo, Protein, Carb, Fat", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                if (uiState.previewMacro != null && uiState.selectedFoodPreset == null && uiState.selectedRawIngredient == null) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF0F291E),
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Text("✓ Đã nhận diện thành công từ ảnh:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                            Text(uiState.previewFoodName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                                            Text(
                                                "Calo: ${uiState.previewMacro?.calories?.toInt()} kcal | P: ${uiState.previewMacro?.protein?.toInt()}g | C: ${uiState.previewMacro?.carb?.toInt()}g | F: ${uiState.previewMacro?.fat?.toInt()}g",
                                                fontSize = 11.sp,
                                                color = Color.LightGray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // GHOST BAR PROGRESS (HIGH-AESTHETIC LUMINOUS PREVIEW)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tiến Trình Macros (Ghost Bar Realtime)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            Text(if (uiState.previewMacro != null) "Đang xem 👁️" else "Chưa chọn", fontSize = 11.sp, color = if (uiState.previewMacro != null) MaterialTheme.colorScheme.tertiary else Color(0xFF64748B), fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        GhostBarProgressItem("Protein", currentLog.currentMacros.protein, uiState.previewMacro?.protein ?: 0f, targetMacros.protein, "g", Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.height(8.dp))
                        GhostBarProgressItem("Carb", currentLog.currentMacros.carb, uiState.previewMacro?.carb ?: 0f, targetMacros.carb, "g", Color(0xFF10B981))
                        Spacer(modifier = Modifier.height(8.dp))
                        GhostBarProgressItem("Fat", currentLog.currentMacros.fat, uiState.previewMacro?.fat ?: 0f, targetMacros.fat, "g", Color(0xFFF59E0B))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        uiState.previewMacro?.let {
                            stateHolder.commitFoodLog(uiState.previewFoodName, it)
                        }
                    },
                    enabled = uiState.previewMacro != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color(0xFF1E283D)
                    )
                ) {
                    Text(
                        if (uiState.previewMacro != null) "Xác Nhận Ăn (+${uiState.previewMacro!!.calories.toInt()} kcal)" else "Chọn Món Để Xem Ghost Bar",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = if (uiState.previewMacro != null) Color.Black else Color(0xFF64748B)
                    )
                }
            }
        }
    }

    @Composable
    fun GhostBarProgressItem(label: String, current: Float, preview: Float, target: Float, unit: String, baseColor: Color) {
        val totalProjected = current + preview
        val isOverTarget = totalProjected > target

        val currentRatio = (current / target).coerceIn(0f, 1f)
        val ghostRatio = (totalProjected / target).coerceIn(0f, 1f)

        val animatedCurrent by animateFloatAsState(targetValue = currentRatio, animationSpec = tween(350), label = "c")
        val animatedGhost by animateFloatAsState(targetValue = ghostRatio, animationSpec = tween(350), label = "g")

        val ghostColor = if (isOverTarget) Color(0xFFEF4444) else baseColor.copy(alpha = 0.5f)

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Row {
                    Text("${current.toInt()}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = baseColor)
                    if (preview > 0f) {
                        Text(
                            " + ${preview.toInt()}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOverTarget) Color(0xFFEF4444) else MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Text(" / ${target.toInt()} $unit", fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0C121E))
            ) {
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedGhost).background(ghostColor))
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedCurrent).background(baseColor))
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.5. CREATE WORKOUT SCREEN (WITH MULTI-DATE PICKER, SEARCH & 24+ PRESETS)
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CreateWorkoutScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        val context = LocalContext.current
        var workoutCategory by remember { mutableStateOf(WorkoutCategory.GYM) }
        var exerciseName by remember { mutableStateOf("Đẩy ngực ngang (Bench Press)") }

        var setsText by remember { mutableStateOf("4") }
        var repsText by remember { mutableStateOf("10") }
        var weightText by remember { mutableStateOf("60") }

        var paceText by remember { mutableStateOf("5:30") }
        var durationText by remember { mutableStateOf("30") }
        var currentMet by remember { mutableStateOf(5.5f) }

        var workoutSearchQuery by remember { mutableStateOf("") }
        val today = LocalDate.now()
        // Multi-date selection set
        var selectedTargetDates by remember { mutableStateOf(setOf(uiState.selectedDate)) }

        val activeUser = uiState.activeUser
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val shortDateFormatter = DateTimeFormatter.ofPattern("dd/MM")

        // Filter presets by search
        val filteredPresets = remember(workoutSearchQuery, stateHolder.workoutCatalog) {
            if (workoutSearchQuery.isBlank()) stateHolder.workoutCatalog
            else stateHolder.workoutCatalog.filter {
                it.name.contains(workoutSearchQuery, ignoreCase = true) ||
                        it.tag.contains(workoutSearchQuery, ignoreCase = true)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Tạo Lịch Tập Luyện", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp).clickable { stateHolder.navigateBack() }
                        ) {
                            Text("← Quay lại", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // TDEE & BMR INFO CARD
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("TDEE người dùng (${activeUser.name})", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Text("${activeUser.tdee.toInt()} kcal/ngày", fontSize = 17.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text("BMR: ${activeUser.bmr.toInt()} kcal", fontSize = 12.sp, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // MULTI-DATE SELECTION SECTION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chọn các ngày áp dụng (${selectedTargetDates.size} ngày):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val d = selectedTargetDates.firstOrNull() ?: uiState.selectedDate
                                DatePickerDialog(
                                    context,
                                    { _, y, m, day ->
                                        selectedTargetDates = selectedTargetDates + LocalDate.of(y, m + 1, day)
                                    },
                                    d.year,
                                    d.monthValue - 1,
                                    d.dayOfMonth
                                ).show()
                            },
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    ) {
                        Text(
                            "📅 + Chọn thêm ngày",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // INTERACTIVE MINI CALENDAR STRIP (BẤM ĐỂ CHỌN NHIỀU NGÀY NHANH)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (offset in -2..12) {
                        val d = today.plusDays(offset.toLong())
                        val isSelected = d in selectedTargetDates
                        val dayOfWeekStr = when (d.dayOfWeek) {
                            DayOfWeek.MONDAY -> "T2"
                            DayOfWeek.TUESDAY -> "T3"
                            DayOfWeek.WEDNESDAY -> "T4"
                            DayOfWeek.THURSDAY -> "T5"
                            DayOfWeek.FRIDAY -> "T6"
                            DayOfWeek.SATURDAY -> "T7"
                            DayOfWeek.SUNDAY -> "CN"
                        }

                        Surface(
                            modifier = Modifier
                                .width(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedTargetDates = if (isSelected) {
                                        if (selectedTargetDates.size > 1) selectedTargetDates - d else selectedTargetDates
                                    } else {
                                        selectedTargetDates + d
                                    }
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(dayOfWeekStr, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color(0xFF94A3B8))
                                Text("${d.dayOfMonth}", fontSize = 13.sp, fontWeight = FontWeight.Black, color = if (isSelected) Color.Black else Color.White)
                                Text(if (isSelected) "✓" else "•", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color(0xFF64748B))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // DISPLAY SELECTED DATE CHIPS WITH REMOVE
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedTargetDates.sorted().forEach { d ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(d.format(shortDateFormatter), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "✕",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier.clickable {
                                        if (selectedTargetDates.size > 1) selectedTargetDates = selectedTargetDates - d
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // QUICK PERIOD SELECTION CHIPS
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Cả tuần (7 ngày)" to {
                            selectedTargetDates = (0..6).map { today.plusDays(it.toLong()) }.toSet()
                        },
                        "30 ngày (1 tháng)" to {
                            selectedTargetDates = (0..29).map { today.plusDays(it.toLong()) }.toSet()
                        },
                        "Thứ 2, 4, 6" to {
                            selectedTargetDates = (0..13).map { today.plusDays(it.toLong()) }.filter {
                                it.dayOfWeek in listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                            }.toSet()
                        },
                        "Thứ 3, 5, 7" to {
                            selectedTargetDates = (0..13).map { today.plusDays(it.toLong()) }.filter {
                                it.dayOfWeek in listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
                            }.toSet()
                        }
                    ).forEach { (label, action) ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { action() },
                            color = Color(0xFF1E283D),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // WORKOUT SEARCH BAR
                OutlinedTextField(
                    value = workoutSearchQuery,
                    onValueChange = { workoutSearchQuery = it },
                    label = { Text("🔍 Tìm bài tập (vd: đá bóng, ngực, bơi, squat...)") },
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (workoutSearchQuery.isNotEmpty()) {
                            Text("✕", modifier = Modifier.clickable { workoutSearchQuery = "" }.padding(8.dp), color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // PRESET WORKOUT QUICK SELECTION LIST
                Text("Gợi ý bài tập & môn thể thao (${filteredPresets.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF94A3B8))
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filteredPresets.take(14).forEach { preset ->
                        val isSelected = exerciseName == preset.name
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    exerciseName = preset.name
                                    workoutCategory = preset.category
                                    setsText = preset.defaultSets.toString()
                                    repsText = preset.defaultReps.toString()
                                    weightText = preset.defaultWeightKg.toInt().toString()
                                    paceText = preset.defaultPace
                                    durationText = preset.defaultDurationMin.toInt().toString()
                                    currentMet = preset.met
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(preset.icon, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    preset.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // CATEGORY TOGGLE (GYM VS CARDIO/SPORT)
                Text("Phân loại:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.LightGray)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            workoutCategory = WorkoutCategory.GYM
                            if (currentMet > 6f) currentMet = 5.5f
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (workoutCategory == WorkoutCategory.GYM) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🏋️ Gym / Tạ", fontWeight = FontWeight.Bold, color = if (workoutCategory == WorkoutCategory.GYM) Color.Black else Color.White)
                    }
                    Button(
                        onClick = {
                            workoutCategory = WorkoutCategory.CARDIO
                            if (currentMet < 5f) currentMet = 8.0f
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (workoutCategory == WorkoutCategory.CARDIO) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⚽ Thể thao / Chạy", fontWeight = FontWeight.Bold, color = if (workoutCategory == WorkoutCategory.CARDIO) Color.Black else Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it },
                    label = { Text("Tên bài tập / môn thể thao") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (workoutCategory == WorkoutCategory.GYM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = setsText,
                            onValueChange = { setsText = it },
                            label = { Text("Số Set (mđ: 4)") },
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = repsText,
                            onValueChange = { repsText = it },
                            label = { Text("Số Rep (mđ: 10)") },
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { weightText = it },
                            label = { Text("Tạ kg (mđ: 50)") },
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = paceText,
                            onValueChange = { paceText = it },
                            label = { Text("Tốc độ (Pace, mđ: 5:30)") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.3f)
                        )
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { durationText = it },
                            label = { Text("Phút (mđ: 30)") },
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val finalExerciseName = exerciseName.ifBlank {
                            if (workoutCategory == WorkoutCategory.GYM) "Bài tập Gym" else "Thể thao ngoài trời"
                        }
                        val finalSets = setsText.ifBlank { "4" }
                        val finalReps = repsText.ifBlank { "10" }
                        val finalWeight = weightText.ifBlank { "50" }
                        val finalPace = paceText.ifBlank { "5:30" }
                        val finalDuration = durationText.ifBlank { "30" }

                        val detailStr = if (workoutCategory == WorkoutCategory.GYM) {
                            "$finalSets sets x $finalReps reps (${finalWeight}kg)"
                        } else {
                            "Tốc độ: $finalPace • $finalDuration phút"
                        }
                        val met = if (workoutCategory == WorkoutCategory.GYM) 5.5f else currentMet
                        val duration = if (workoutCategory == WorkoutCategory.GYM) (finalSets.toFloatOrNull() ?: 4f) * 6f else (finalDuration.toFloatOrNull() ?: 30f)

                        // Lưu lịch tập cho toàn bộ các ngày đã chọn
                        val datesToSave = selectedTargetDates.ifEmpty { setOf(uiState.selectedDate) }.toList()
                        stateHolder.addWorkoutScheduleMultiDays(finalExerciseName, workoutCategory, detailStr, met, duration, datesToSave)
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Lưu Lịch Tập Cho ${selectedTargetDates.size} Ngày Đã Chọn", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.Black)
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.6. ANALYTICS SCREEN
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AnalyticsScreen(stateHolder: CalorieTrackerStateHolder) {
        var selectedDays by remember { mutableStateOf(7) }
        val analytics = remember(selectedDays) { stateHolder.calculateAnalytics(selectedDays) }
        val user = stateHolder.uiState.collectAsState().value.activeUser

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Phân Tích & Lời Khuyên", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp).clickable { stateHolder.navigateBack() }
                        ) {
                            Text("← Quay lại", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(7 to "1 Tuần", 14 to "2 Tuần", 30 to "1 Tháng", 365 to "1 Năm (365N)").forEach { (days, label) ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedDays = days },
                            color = if (selectedDays == days) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = if (selectedDays == days) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedDays == days) Color.Black else Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Biểu Đồ Nạp Calo (${user.name})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            Text("Mục tiêu: ${user.targetCaloriesIn.toInt()} kcal", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        val logs = analytics.logs
                        val targetCalorie = user.targetCaloriesIn
                        val maxCalorie = maxOf((logs.maxOfOrNull { it.totalCaloriesIn } ?: 0f), targetCalorie * 1.3f, 2500f)

                        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                            val width = size.width
                            val height = size.height
                            val barCount = logs.size
                            val barWidth = (width / barCount) * 0.65f
                            val gap = (width - (barWidth * barCount)) / (barCount + 1)

                            val targetY = height - (targetCalorie / maxCalorie * height)
                            drawLine(
                                color = Color(0xFF10B981),
                                start = Offset(0f, targetY),
                                end = Offset(width, targetY),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                            )

                            logs.forEachIndexed { index, log ->
                                val barHeight = (log.totalCaloriesIn / maxCalorie) * height
                                val x = gap + index * (barWidth + gap)
                                val y = height - barHeight
                                val barColor = if (log.totalCaloriesIn > targetCalorie * 1.15f) Color(0xFFEF4444) else Color(0xFF38BDF8)

                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(x, y),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0F1A2E),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✨", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Lời Khuyên Dành Cho ${user.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = analytics.adviceText, fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFFE2E8F0))
                        Spacer(modifier = Modifier.height(10.dp))
                        Divider(color = Color(0xFF26334D))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ℹ️ Dữ liệu được tính toán riêng biệt cho từng người dùng dựa trên độ lệch chuẩn (Std Dev).",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    @Composable
    fun EditProfileScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
    }
}
