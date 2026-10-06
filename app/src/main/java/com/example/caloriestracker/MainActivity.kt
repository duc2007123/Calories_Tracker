package com.example.caloriestracker

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
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
    val baseMacro: MacroNutrient,
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
    val manualCalories: Float = 0f,
    val isCompleted: Boolean = false
) {
    fun calculateBurnedCalories(userWeightKg: Float): Float {
        if (manualCalories > 0f) return manualCalories
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
    fun hasFoodData(): Boolean = totalCaloriesIn > 0f || loggedFoods.isNotEmpty()
    fun hasWorkoutData(): Boolean = activeCaloriesOut > 0f
    fun getNaturalBurnCalories(userBmr: Float): Float = userBmr
    fun getTotalCaloriesOut(userBmr: Float): Float = getNaturalBurnCalories(userBmr) + activeCaloriesOut
    fun getCalorieBalance(userBmr: Float): Float = totalCaloriesIn - getTotalCaloriesOut(userBmr)
}

enum class Screen {
    ONBOARDING, DASHBOARD, ADD_FOOD, CREATE_WORKOUT, ANALYTICS, PROFILE_SWITCHER, EDIT_PROFILE, SET_SCHEDULE_FOOD, SET_SCHEDULE_WORKOUT
}

// =========================================================================================
// 2. STATE HOLDER
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
    val isProgressExpanded: Boolean = true,
    // Workout temporary selection for scheduling
    val tempWorkoutName: String = "",
    val tempWorkoutCategory: WorkoutCategory = WorkoutCategory.GYM,
    val tempWorkoutDetail: String = "",
    val tempWorkoutMet: Float = 5.5f,
    val tempWorkoutDuration: Float = 30f,
    val tempWorkoutManualCal: Float = 0f,
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
    private val prefs: SharedPreferences = context.getSharedPreferences("calorie_tracker_v9_storage", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(AppState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    // 22+ Món ăn & nguyên liệu cơ bản
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

    // 18+ Món ăn Việt Nam
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

    // DANH MỤC CÁC BÀI TẬP GYM & THỂ THAO
    val workoutCatalog = listOf(
        // GYM / TẠ
        WorkoutPreset("w1", "Đẩy ngực ngang (Bench Press)", WorkoutCategory.GYM, "🏋️", 4, 10, 60f, "5:30", 25f, 5.5f, "Gym"),
        WorkoutPreset("w2", "Đẩy ngực dốc lên (Incline Press)", WorkoutCategory.GYM, "🏋️", 4, 10, 50f, "5:30", 25f, 5.5f, "Gym"),
        WorkoutPreset("w3", "Kéo xô lưng (Lat Pulldown)", WorkoutCategory.GYM, "🏋️", 4, 12, 45f, "5:30", 25f, 5.0f, "Gym"),
        WorkoutPreset("w4", "Gánh đùi sau (Squat Barbell)", WorkoutCategory.GYM, "🏋️", 4, 8, 80f, "5:30", 30f, 6.0f, "Gym"),
        WorkoutPreset("w5", "Đạp đùi máy nghiêng (Leg Press)", WorkoutCategory.GYM, "🏋️", 4, 12, 100f, "5:30", 25f, 5.5f, "Gym"),
        WorkoutPreset("w6", "Kéo lưng đùi (Deadlift)", WorkoutCategory.GYM, "🏋️", 3, 6, 90f, "5:30", 25f, 6.5f, "Gym"),
        WorkoutPreset("w7", "Đẩy vai tạ đơn (Shoulder Press)", WorkoutCategory.GYM, "🏋️", 4, 10, 16f, "5:30", 20f, 5.0f, "Gym"),
        WorkoutPreset("w8", "Cuốn tay trước (Bicep Curl)", WorkoutCategory.GYM, "🏋️", 3, 12, 12f, "5:30", 20f, 4.5f, "Gym"),
        WorkoutPreset("w9", "Gập bụng (Abdominal Crunch)", WorkoutCategory.GYM, "🏋️", 4, 20, 0f, "5:30", 15f, 4.0f, "Gym"),
        WorkoutPreset("w10", "Hít xà đơn (Pull-up)", WorkoutCategory.GYM, "🏋️", 4, 8, 0f, "5:30", 20f, 6.0f, "Gym"),
        WorkoutPreset("w11", "Hít đất / Chống đẩy (Push-up)", WorkoutCategory.GYM, "🏋️", 4, 15, 0f, "5:30", 20f, 5.0f, "Gym"),

        // THỂ THAO / CHẠY
        WorkoutPreset("w12", "Cầu lông đối kháng đôi", WorkoutCategory.CARDIO, "🏸", 1, 1, 0f, "Đấu đôi", 45f, 6.5f, "Thể thao"),
        WorkoutPreset("w13", "Cầu lông đơn cường độ cao", WorkoutCategory.CARDIO, "🏸", 1, 1, 0f, "Đấu đơn", 45f, 7.5f, "Thể thao"),
        WorkoutPreset("w14", "Bóng đá sân cỏ 7 người", WorkoutCategory.CARDIO, "⚽", 1, 1, 0f, "Thi đấu", 60f, 8.5f, "Thể thao"),
        WorkoutPreset("w15", "Futsal / Bóng đá sân 5", WorkoutCategory.CARDIO, "⚽", 1, 1, 0f, "Cường độ cao", 50f, 9.0f, "Thể thao"),
        WorkoutPreset("w16", "Chạy bộ ngoài trời (Running)", WorkoutCategory.CARDIO, "🏃", 1, 1, 0f, "5:30", 30f, 9.8f, "Chạy"),
        WorkoutPreset("w17", "Chạy nhẹ nhàng hồi phục", WorkoutCategory.CARDIO, "🏃", 1, 1, 0f, "6:30", 35f, 8.0f, "Chạy"),
        WorkoutPreset("w18", "Đi bộ nhanh (Brisk Walking)", WorkoutCategory.CARDIO, "🚶", 1, 1, 0f, "9:30", 45f, 4.5f, "Chạy"),
        WorkoutPreset("w19", "Bơi sải tốc độ cao", WorkoutCategory.CARDIO, "🏊", 1, 1, 0f, "Liên tục", 40f, 8.5f, "Bơi"),
        WorkoutPreset("w20", "Bơi ếch vừa sức", WorkoutCategory.CARDIO, "🏊", 1, 1, 0f, "Vừa sức", 40f, 6.0f, "Bơi"),
        WorkoutPreset("w21", "Đạp xe ngoài trời (18-22 km/h)", WorkoutCategory.CARDIO, "🚴", 1, 1, 0f, "20 km/h", 45f, 7.5f, "Đạp xe"),
        WorkoutPreset("w22", "Nhảy dây đốt mỡ (Jump Rope)", WorkoutCategory.CARDIO, "🪢", 1, 1, 0f, "120 nhịp/p", 20f, 10.0f, "Cardio"),
        WorkoutPreset("w23", "Boxing / Đấm bao cát", WorkoutCategory.CARDIO, "🥊", 1, 1, 0f, "Cường độ cao", 45f, 9.0f, "Võ thuật"),
        WorkoutPreset("w24", "Bóng rổ toàn sân (Basketball)", WorkoutCategory.CARDIO, "🏀", 1, 1, 0f, "Toàn sân", 60f, 7.5f, "Thể thao")
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

        // Load logs
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
                        LoggedFoodItem(name = "Cơm Tấm Sườn Bì Chả", portion = "1 Đĩa đầy đủ", macro = MacroNutrient(34f, 82f, 26f))
                    )
                } else if (i == 0) {
                    listOf(
                        LoggedFoodItem(name = "Phở Bò Tái Nạm", portion = "1 Bát vừa", macro = MacroNutrient(28f, 58f, 14f))
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
                                manualCalories = o.optDouble("manualCalories", 0.0).toFloat(),
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
                    WorkoutItem("w1", activeId, today, WorkoutCategory.CARDIO, "Chạy bộ ngoài trời", "Tốc độ: 5:30 • 30 phút", met = 9.8f, durationMin = 30f, isCompleted = true),
                    WorkoutItem("w2", activeId, today, WorkoutCategory.GYM, "Đẩy ngực ngang (Bench Press)", "4 sets x 10 reps (60kg)", met = 5.5f, durationMin = 25f, isCompleted = false)
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
                o.put("manualCalories", w.manualCalories)
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
        _uiState.update { it.copy(backStack = it.backStack + screen) }
    }

    fun navigateBack(): Boolean {
        val stack = _uiState.value.backStack
        if (stack.size > 1) {
            _uiState.update { it.copy(backStack = stack.dropLast(1)) }
            return true
        }
        return false
    }

    fun toggleProgressExpanded() {
        _uiState.update { it.copy(isProgressExpanded = !it.isProgressExpanded) }
    }

    fun setTempWorkout(name: String, category: WorkoutCategory, detail: String, met: Float, duration: Float, manualCal: Float) {
        _uiState.update {
            it.copy(
                tempWorkoutName = name,
                tempWorkoutCategory = category,
                tempWorkoutDetail = detail,
                tempWorkoutMet = met,
                tempWorkoutDuration = duration,
                tempWorkoutManualCal = manualCal
            )
        }
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

    // GHOST BAR & SELECTION
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

    fun commitFoodLog(foodName: String = "Món ăn", macro: MacroNutrient, targetDate: LocalDate = _uiState.value.selectedDate) {
        val state = _uiState.value
        val uId = state.activeProfileId

        val targetLog = state.userLogs[uId]?.get(targetDate) ?: DailyLog(date = targetDate)
        val updatedFoods = targetLog.loggedFoods + LoggedFoodItem(
            name = foodName.ifBlank { "Món ăn" },
            portion = if (state.selectedRawIngredient != null) {
                if (state.selectedRawIngredient.unitType == UnitType.GRAM) "${state.rawAmount.toInt()}g" else "${state.rawAmount.toInt()} ${state.selectedRawIngredient.unitName}"
            } else if (state.selectedFoodPreset != null) {
                "${state.previewPortionMultiplier} phần"
            } else "1 khẩu phần",
            macro = macro
        )

        val updatedMacros = targetLog.currentMacros + macro
        val updatedCaloriesIn = targetLog.totalCaloriesIn + macro.calories

        val newDailyLog = targetLog.copy(
            loggedFoods = updatedFoods,
            currentMacros = updatedMacros,
            totalCaloriesIn = updatedCaloriesIn
        )

        val userDateMap = state.userLogs[uId]?.toMutableMap() ?: mutableMapOf()
        userDateMap[targetDate] = newDailyLog

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

    fun commitFoodLogMultiDays(foodName: String, macro: MacroNutrient, dates: List<LocalDate>) {
        val state = _uiState.value
        val uId = state.activeProfileId
        val userDateMap = state.userLogs[uId]?.toMutableMap() ?: mutableMapOf()

        dates.forEach { targetDate ->
            val targetLog = userDateMap[targetDate] ?: DailyLog(date = targetDate)
            val updatedFoods = targetLog.loggedFoods + LoggedFoodItem(
                name = foodName.ifBlank { "Món ăn" },
                portion = if (state.selectedRawIngredient != null) {
                    if (state.selectedRawIngredient.unitType == UnitType.GRAM) "${state.rawAmount.toInt()}g" else "${state.rawAmount.toInt()} ${state.selectedRawIngredient.unitName}"
                } else if (state.selectedFoodPreset != null) {
                    "${state.previewPortionMultiplier} phần"
                } else "1 khẩu phần",
                macro = macro
            )
            val updatedMacros = targetLog.currentMacros + macro
            val updatedCaloriesIn = targetLog.totalCaloriesIn + macro.calories

            userDateMap[targetDate] = targetLog.copy(
                loggedFoods = updatedFoods,
                currentMacros = updatedMacros,
                totalCaloriesIn = updatedCaloriesIn
            )
        }

        val updatedUserLogs = state.userLogs.toMutableMap()
        updatedUserLogs[uId] = userDateMap

        _uiState.update {
            it.copy(
                userLogs = updatedUserLogs,
                previewMacro = null,
                previewFoodName = "",
                selectedFoodPreset = null,
                selectedRawIngredient = null,
                backStack = listOf(Screen.DASHBOARD)
            )
        }
        saveStateToDisk()
    }

    // WORKOUTS
    fun addWorkoutScheduleMultiDays(
        name: String,
        category: WorkoutCategory,
        detail: String,
        met: Float,
        durationMin: Float,
        manualCalories: Float = 0f,
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
                title = name.ifBlank { if (category == WorkoutCategory.GYM) "Tập Gym" else "Vận động" },
                detail = detail,
                met = met,
                durationMin = durationMin,
                manualCalories = manualCalories,
                isCompleted = false
            )
        }

        val userWorkoutList = (state.userWorkouts[uId] ?: emptyList()) + newWorkouts
        val updatedUserWorkouts = state.userWorkouts.toMutableMap()
        updatedUserWorkouts[uId] = userWorkoutList.toMutableList()

        _uiState.update {
            it.copy(
                userWorkouts = updatedUserWorkouts,
                backStack = listOf(Screen.DASHBOARD)
            )
        }
        saveStateToDisk()
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

    // ANALYTICS
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
                    append("• Đạt chuẩn dung sai WHO: Khẩu phần ăn dao động trong khoảng tự nhiên lành mạnh (±10-12%).\n")
                }
                pctDiff < -12f -> {
                    append("• Mức nạp đang thấp hơn khuyến nghị (-${abs(diff).toInt()} kcal).\n")
                }
                else -> {
                    append("• Mức nạp thặng dư trung bình (+${diff.toInt()} kcal/ngày). Có thể bù đắp bằng các buổi tập thể thao 3-4 lần/tuần.\n")
                }
            }

            if (stdDev > 450f) {
                append("• Nhịp sinh học: Năng lượng giữa các ngày dao động khá cao.")
            } else {
                append("• Tính đều đặn: Thói quen ăn uống rất ổn định qua các ngày!")
            }
        }

        return AnalyticsResult(days, mean, stdDev, advice, logsList)
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
// 3. MAIN ACTIVITY & SCREENS
// =========================================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val stateHolder = remember { CalorieTrackerStateHolder(context) }
            val uiState by stateHolder.uiState.collectAsState()

            var showExitDialog by remember { mutableStateOf(false) }

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
                    primary = Color(0xFF10B981),      // Emerald Green
                    secondary = Color(0xFF38BDF8),    // Sky Blue
                    tertiary = Color(0xFFF59E0B),     // Amber Gold
                    background = Color(0xFF090D16),   // Deep Dark Midnight
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
                        Screen.SET_SCHEDULE_FOOD -> SetScheduleFoodScreen(stateHolder, uiState)
                        Screen.SET_SCHEDULE_WORKOUT -> SetScheduleWorkoutScreen(stateHolder, uiState)
                        Screen.ANALYTICS -> AnalyticsScreen(stateHolder)
                        Screen.EDIT_PROFILE -> {}
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
    // 3.2. DASHBOARD SCREEN
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
                // TOP HEADER: PROFILE SWITCHER & DATE PICKER
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

                // CURRENT DATE TITLE (KHÔNG CÓ CHỮ QUÁ KHỨ / TƯƠNG LAI)
                val formatter = DateTimeFormatter.ofPattern("EEEE, 'ngày' dd/MM/yyyy", java.util.Locale("vi"))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(selectedDate.format(formatter), fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color.White)
                        val relativeText = when {
                            daysDifference == 0L -> "Hôm nay"
                            daysDifference > 0L -> "$daysDifference ngày trước"
                            else -> "${abs(daysDifference)} ngày tới"
                        }
                        Text(
                            relativeText,
                            fontSize = 11.sp,
                            color = if (daysDifference == 0L) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 7-DAY DYNAMIC HORIZONTAL CALENDAR STRIP (STREAK: 🥗 Ăn đủ, 🔥 Đốt đủ, 🥗🔥 Cả hai)
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
                        val dayWorkouts = (uiState.userWorkouts[activeUser.id] ?: emptyList()).filter { it.date == date }

                        val hasFood = dayLog.hasFoodData()
                        val hasWorkout = dayLog.hasWorkoutData() || dayWorkouts.any { it.isCompleted }

                        val streakIcon = when {
                            hasFood && hasWorkout -> "🥗🔥"
                            hasFood -> "🥗"
                            hasWorkout -> "🔥"
                            else -> "•"
                        }

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
                                .width(52.dp)
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
                                    streakIcon,
                                    fontSize = if (streakIcon.length > 1) 10.sp else 11.sp,
                                    color = if (isSelected) Color.Black else Color(0xFF475569)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // CÂN BẰNG NĂNG LƯỢNG - ĐỒNG BỘ 1 DÒNG DUY NHẤT
                if (!isFutureDate && hasDailyData) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val whoTolerance = activeUser.targetCaloriesIn * 0.12f

                            val badgeText = when {
                                abs(balanceCal) <= whoTolerance -> "Cân bằng (±${abs(balanceCal).toInt()} kcal)"
                                balanceCal > 0 -> "Dư ${balanceCal.toInt()} kcal"
                                else -> "Thiếu ${abs(balanceCal).toInt()} kcal"
                            }

                            val badgeBgColor = when {
                                abs(balanceCal) <= whoTolerance -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                balanceCal > 0 -> Color(0xFFEF4444).copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            }
                            val badgeTextColor = when {
                                abs(balanceCal) <= whoTolerance -> MaterialTheme.colorScheme.secondary
                                balanceCal > 0 -> Color(0xFFEF4444)
                                else -> MaterialTheme.colorScheme.primary
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚖️", fontSize = 17.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("CÂN BẰNG NĂNG LƯỢNG", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                }

                                Surface(
                                    color = badgeBgColor,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeTextColor.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        badgeText,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = badgeTextColor
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
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
                                        Text("IN", fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("${currentLog.totalCaloriesIn.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        Text("kcal từ món ăn", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                    }
                                }

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
                                        Text("OUT", fontSize = 12.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.tertiary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("${totalOutCal.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        Text("BMR: ${naturalBurnBmr.toInt()} + Tập: ${currentLog.activeCaloriesOut.toInt()}", fontSize = 9.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

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
                                        "⚠️ Thâm hụt ${abs(balanceCal).toInt()} kcal khá sâu. WHO khuyến cáo không nên cắt giảm quá mức liên tục để tránh suy giảm trao đổi chất."
                                    balanceCal <= whoTolerance * 2.5f ->
                                        "💪 Nạp thặng dư nhẹ ${balanceCal.toInt()} kcal rất tốt cho phát triển cơ bắp, có thể bù trừ linh hoạt qua các buổi tập tiếp theo."
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

                // TIẾN TRÌNH IN & OUT
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                Text("IN", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
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
                                Text("OUT", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
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
                                Text("＋ Tập Luyện", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // CHI TIẾT MÓN ĂN
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

                // CHI TIẾT BÀI TẬP
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
    // 3.4. ADD FOOD SCREEN (TAB IN)
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AddFoodScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Dân dã / Tự chọn", "Món Việt (18+)", "Scan Bao Bì")

        var searchQuery by remember { mutableStateOf("") }
        var inputGramText by remember { mutableStateOf("100") }

        var customName by remember { mutableStateOf("") }
        var customProtein by remember { mutableStateOf("") }
        var customCarb by remember { mutableStateOf("") }
        var customFat by remember { mutableStateOf("") }
        var showCustomManual by remember { mutableStateOf(false) }

        val currentLog = uiState.currentDailyLog
        val targetMacros = uiState.activeUser.targetMacros
        val targetCalories = uiState.activeUser.targetCaloriesIn

        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { stateHolder.processNutritionImage(it) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Thêm Khẩu Phần", fontWeight = FontWeight.Bold) },
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

                // THANH TIẾN TRÌNH ĐẶT LÊN ĐẦU VỚI TÍNH NĂNG THU GỌN / MỞ RỘNG
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { stateHolder.toggleProgressExpanded() },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tiến trình", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                if (uiState.previewMacro != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("Đang xem 👁️", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(
                                if (uiState.isProgressExpanded) "Thu gọn ▲" else "Mở rộng ▼",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        AnimatedVisibility(
                            visible = uiState.isProgressExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically()
                        ) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                GhostBarProgressItem(
                                    label = "Kcal",
                                    current = currentLog.totalCaloriesIn,
                                    preview = uiState.previewMacro?.calories ?: 0f,
                                    target = targetCalories,
                                    unit = "kcal",
                                    baseColor = Color(0xFFA855F7)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                GhostBarProgressItem(
                                    label = "Protein",
                                    current = currentLog.currentMacros.protein,
                                    preview = uiState.previewMacro?.protein ?: 0f,
                                    target = targetMacros.protein,
                                    unit = "g",
                                    baseColor = Color(0xFF38BDF8)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                GhostBarProgressItem(
                                    label = "Carb",
                                    current = currentLog.currentMacros.carb,
                                    preview = uiState.previewMacro?.carb ?: 0f,
                                    target = targetMacros.carb,
                                    unit = "g",
                                    baseColor = Color(0xFF10B981)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                GhostBarProgressItem(
                                    label = "Fat",
                                    current = currentLog.currentMacros.fat,
                                    preview = uiState.previewMacro?.fat ?: 0f,
                                    target = targetMacros.fat,
                                    unit = "g",
                                    baseColor = Color(0xFFF59E0B)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

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

                Spacer(modifier = Modifier.height(8.dp))

                // SEARCH BAR
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
                        0 -> { // NGUYÊN LIỆU DÂN DÃ - ĐÃ ĐẨY TỰ NHẬP MÓN KHÁC LÊN ĐẦU
                            val filteredRaw = remember(searchQuery) {
                                if (searchQuery.isBlank()) stateHolder.rawFoodCatalog
                                else stateHolder.rawFoodCatalog.filter {
                                    it.name.contains(searchQuery, ignoreCase = true) || it.category.contains(searchQuery, ignoreCase = true)
                                }
                            }

                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { showCustomManual = !showCustomManual },
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (showCustomManual) MaterialTheme.colorScheme.tertiary else Color(0xFF26334D))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("✍️", fontSize = 16.sp)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Tự nhập món khác (Custom Macros)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                                                }
                                                Text(if (showCustomManual) "Thu gọn ▲" else "Nhập ngay ▼", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
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
                                    Spacer(modifier = Modifier.height(2.dp))
                                }

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

                                            if (isSelected) {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Divider(color = Color(0xFF26334D))
                                                Spacer(modifier = Modifier.height(8.dp))

                                                if (item.unitType == UnitType.GRAM) {
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
                                                    "Macros: P: ${macro.protein.toInt()}g | C: ${macro.carb.toInt()}g | F: ${macro.fat.toInt()}g",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> { // 18+ MÓN VIỆT NAM
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
                        2 -> { // SCAN ẢNH OCR
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
                                            Text("AI đang đọc bảng dinh dưỡng...", fontSize = 12.sp, color = Color.LightGray)
                                        } else {
                                            Text("📷", fontSize = 32.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Bấm để Chọn Ảnh Bao Bì", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("Tự động bóc tách Calo, P, C, F", fontSize = 11.sp, color = Color(0xFF94A3B8))
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

                // HAI Ô NẰM NGANG CẠNH NHAU
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            uiState.previewMacro?.let {
                                stateHolder.commitFoodLog(uiState.previewFoodName, it)
                            }
                        },
                        enabled = uiState.previewMacro != null,
                        modifier = Modifier.weight(1.2f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = Color(0xFF1E283D)
                        )
                    ) {
                        Text(
                            "Xác Nhận Ăn",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = if (uiState.previewMacro != null) Color.Black else Color(0xFF64748B)
                        )
                    }

                    Button(
                        onClick = {
                            if (uiState.previewMacro != null) {
                                stateHolder.navigateTo(Screen.SET_SCHEDULE_FOOD)
                            }
                        },
                        enabled = uiState.previewMacro != null,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            disabledContainerColor = Color(0xFF1E283D)
                        )
                    ) {
                        Text(
                            "SET LỊCH 📅",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = if (uiState.previewMacro != null) Color.Black else Color(0xFF64748B)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun GhostBarProgressItem(label: String, current: Float, preview: Float, target: Float, unit: String, baseColor: Color) {
        val totalProjected = current + preview
        val isOverTarget = totalProjected > target
        val surplus = totalProjected - target

        val currentRatio = (current / target).coerceIn(0f, 1f)
        val ghostRatio = (totalProjected / target).coerceIn(0f, 1f)

        val animatedCurrent by animateFloatAsState(targetValue = currentRatio, animationSpec = tween(350), label = "c")
        val animatedGhost by animateFloatAsState(targetValue = ghostRatio, animationSpec = tween(350), label = "g")

        val ghostColor = if (isOverTarget) Color(0xFFEF4444) else baseColor.copy(alpha = 0.5f)

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isOverTarget) {
                        Text("${target.toInt()} / ${target.toInt()}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = baseColor)
                        Text(" + ${surplus.toInt()}", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFFEF4444))
                        Text(" $unit", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    } else {
                        Text("${current.toInt()}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = baseColor)
                        if (preview > 0f) {
                            Text(" + ${preview.toInt()}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text(" / ${target.toInt()} $unit", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
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
    // 3.5. CREATE WORKOUT SCREEN (VỚI 2 NÚT PHÂN LOẠI CHUẨN [🏋️ Gym / Tạ] VÀ [⚽ Thể thao / Chạy] NHƯ HÌNH)
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CreateWorkoutScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        var workoutCategory by remember { mutableStateOf(WorkoutCategory.GYM) }
        var exerciseName by remember { mutableStateOf("Đẩy ngực ngang (Bench Press)") }

        var setsText by remember { mutableStateOf("4") }
        var repsText by remember { mutableStateOf("10") }
        var weightText by remember { mutableStateOf("60") }

        var paceText by remember { mutableStateOf("5:30") }
        var durationText by remember { mutableStateOf("30") }
        var currentMet by remember { mutableStateOf(5.5f) }
        var manualCalorieText by remember { mutableStateOf("") }

        var workoutSearchQuery by remember { mutableStateOf("") }

        val activeUser = uiState.activeUser
        val currentLog = uiState.currentDailyLog

        // Tính lượng Calo còn thiếu hôm nay
        val neededCalorie = (activeUser.targetCaloriesOut - currentLog.activeCaloriesOut).coerceAtLeast(0f)

        // Danh sách bài tập lọc theo Phân loại và Tìm kiếm
        val filteredPresets = remember(workoutCategory, workoutSearchQuery, stateHolder.workoutCatalog) {
            val byCat = stateHolder.workoutCatalog.filter { it.category == workoutCategory }
            if (workoutSearchQuery.isBlank()) byCat
            else byCat.filter { it.name.contains(workoutSearchQuery, ignoreCase = true) || it.tag.contains(workoutSearchQuery, ignoreCase = true) }
        }

        // Danh sách gợi ý bài tập thông minh theo Calo thiếu
        val smartSuggestions = remember(neededCalorie, activeUser.weightKg) {
            if (neededCalorie <= 0f) {
                listOf(
                    Triple("Hít đất / Chống đẩy (Push-up)", WorkoutCategory.GYM, 100f),
                    Triple("Đi bộ nhanh (Brisk Walking)", WorkoutCategory.CARDIO, 4.5f * activeUser.weightKg * 0.5f)
                )
            } else if (neededCalorie <= 250f) {
                listOf(
                    Triple("Chạy bộ ngoài trời (Running)", WorkoutCategory.CARDIO, 9.8f * activeUser.weightKg * (25f / 60f)),
                    Triple("Nhảy dây đốt mỡ (Jump Rope)", WorkoutCategory.CARDIO, 10.0f * activeUser.weightKg * (15f / 60f))
                )
            } else if (neededCalorie <= 500f) {
                listOf(
                    Triple("Bóng đá sân cỏ 7 người", WorkoutCategory.CARDIO, 8.5f * activeUser.weightKg * (45f / 60f)),
                    Triple("Cầu lông đối kháng đôi", WorkoutCategory.CARDIO, 6.5f * activeUser.weightKg * (50f / 60f)),
                    Triple("Gánh đùi sau (Squat Barbell)", WorkoutCategory.GYM, 6.0f * activeUser.weightKg * (35f / 60f))
                )
            } else {
                listOf(
                    Triple("Chạy bộ ngoài trời (Running)", WorkoutCategory.CARDIO, 9.8f * activeUser.weightKg * (40f / 60f)),
                    Triple("Bơi sải tốc độ cao", WorkoutCategory.CARDIO, 8.5f * activeUser.weightKg * (45f / 60f)),
                    Triple("Kéo lưng đùi (Deadlift)", WorkoutCategory.GYM, 6.5f * activeUser.weightKg * (35f / 60f))
                )
            }
        }

        // Tự động tính số Calo đốt dự kiến
        val calculatedBurnCalories = remember(
            workoutCategory, setsText, durationText, manualCalorieText, currentMet, activeUser.weightKg
        ) {
            val manCal = manualCalorieText.toFloatOrNull()
            if (manCal != null && manCal > 0f) {
                manCal
            } else {
                val dur = if (workoutCategory == WorkoutCategory.GYM) {
                    (setsText.toFloatOrNull() ?: 4f) * 6f
                } else {
                    durationText.toFloatOrNull() ?: 30f
                }
                currentMet * activeUser.weightKg * (dur / 60f)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Tập Luyện", fontWeight = FontWeight.Bold) },
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
                // 1. GỢI Ý BÀI TẬP THÔNG MINH DỰA TRÊN CALO THIẾU Ở ĐẦU TRANG
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0F1D33),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("💡", fontSize = 17.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Gợi ý theo Calo thiếu hôm nay", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(
                                if (neededCalorie > 0f) "Thiếu: ${neededCalorie.toInt()} kcal" else "Đã đạt mục tiêu ✓",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = if (neededCalorie > 0f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        smartSuggestions.forEach { (name, cat, estCal) ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        workoutCategory = cat
                                        exerciseName = name
                                        val found = stateHolder.workoutCatalog.find { it.name == name }
                                        if (found != null) {
                                            setsText = found.defaultSets.toString()
                                            repsText = found.defaultReps.toString()
                                            weightText = found.defaultWeightKg.toInt().toString()
                                            paceText = found.defaultPace
                                            durationText = found.defaultDurationMin.toInt().toString()
                                            currentMet = found.met
                                        }
                                        manualCalorieText = estCal.toInt().toString()
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "🔥 +${estCal.toInt()} kcal",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. PHÂN LOẠI: 2 NÚT TO ĐẸP [ 🏋️ Gym / Tạ ] VÀ [ ⚽ Thể thao / Chạy ] CHUẨN NHƯ HÌNH
                Text("Phân loại:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.LightGray)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            workoutCategory = WorkoutCategory.GYM
                            if (currentMet > 6f) currentMet = 5.5f
                            val firstGym = stateHolder.workoutCatalog.firstOrNull { it.category == WorkoutCategory.GYM }
                            if (firstGym != null && (exerciseName.contains("bộ", ignoreCase = true) || exerciseName.contains("bóng", ignoreCase = true))) {
                                exerciseName = firstGym.name
                                setsText = firstGym.defaultSets.toString()
                                repsText = firstGym.defaultReps.toString()
                                weightText = firstGym.defaultWeightKg.toInt().toString()
                                currentMet = firstGym.met
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (workoutCategory == WorkoutCategory.GYM) Color(0xFFF59E0B) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(
                            "🏋️ Gym / Tạ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (workoutCategory == WorkoutCategory.GYM) Color.Black else Color.White
                        )
                    }

                    Button(
                        onClick = {
                            workoutCategory = WorkoutCategory.CARDIO
                            if (currentMet < 5f) currentMet = 8.0f
                            val firstCardio = stateHolder.workoutCatalog.firstOrNull { it.category == WorkoutCategory.CARDIO }
                            if (firstCardio != null && (exerciseName.contains("Press", ignoreCase = true) || exerciseName.contains("Squat", ignoreCase = true))) {
                                exerciseName = firstCardio.name
                                paceText = firstCardio.defaultPace
                                durationText = firstCardio.defaultDurationMin.toInt().toString()
                                currentMet = firstCardio.met
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (workoutCategory == WorkoutCategory.CARDIO) Color(0xFFF59E0B) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(
                            "⚽ Thể thao / Chạy",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (workoutCategory == WorkoutCategory.CARDIO) Color.Black else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. TÌM KIẾM & DANH SÁCH BÀI TẬP GỢI Ý THEO PHÂN LOẠI
                OutlinedTextField(
                    value = workoutSearchQuery,
                    onValueChange = { workoutSearchQuery = it },
                    placeholder = { Text("🔍 Tìm bài tập (vd: đá bóng, ngực, bơi, squat...)", fontSize = 12.sp, color = Color(0xFF64748B)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (workoutSearchQuery.isNotEmpty()) {
                            Text("✕", modifier = Modifier.clickable { workoutSearchQuery = "" }.padding(8.dp), color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text("Gợi ý bài tập (${filteredPresets.size}):", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF94A3B8))
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredPresets.forEach { preset ->
                        val isSelected = exerciseName == preset.name
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    exerciseName = preset.name
                                    setsText = preset.defaultSets.toString()
                                    repsText = preset.defaultReps.toString()
                                    weightText = preset.defaultWeightKg.toInt().toString()
                                    paceText = preset.defaultPace
                                    durationText = preset.defaultDurationMin.toInt().toString()
                                    currentMet = preset.met
                                    manualCalorieText = ""
                                },
                            color = if (isSelected) Color(0xFF16253D) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else Color(0xFF26334D))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(preset.icon, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    preset.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.secondary else Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. Ô NHẬP TÊN BÀI TẬP / MÔN THỂ THAO
                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it },
                    label = { Text("Tên bài tập / môn thể thao") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 5. PHẦN NHẬP LIỆU TƯƠNG ỨNG VỚI PHÂN LOẠI
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
                            label = { Text("Tốc độ / Pace (mđ: 5:30)") },
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

                Spacer(modifier = Modifier.height(10.dp))

                // Ô NHẬP CALO TỰ TÍNH (NẾU MUỐN GHI ĐÈ)
                OutlinedTextField(
                    value = manualCalorieText,
                    onValueChange = { manualCalorieText = it },
                    placeholder = { Text("Tùy chọn: Nhập đè số kcal nếu muốn tự tính", fontSize = 11.sp, color = Color(0xFF64748B)) },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 6. SỐ CALO ĐỐT DỰ KIẾN HIỂN THỊ NỔI BẬT
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1E283D),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔥", fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Số Calo đốt dự kiến:", fontSize = 11.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (workoutCategory == WorkoutCategory.GYM) "${setsText.ifBlank { "4" }} sets • ${activeUser.weightKg}kg" else "${durationText.ifBlank { "30" }} phút • ${activeUser.weightKg}kg",
                                    fontSize = 11.sp,
                                    color = Color.White
                                )
                            }
                        }

                        Text(
                            "+${calculatedBurnCalories.toInt()} kcal",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 7. HAI Ô NẰM NGANG Ở CHÂN TRANG (LƯU HÔM NAY VS SET LỊCH)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val finalName = exerciseName.ifBlank { if (workoutCategory == WorkoutCategory.GYM) "Tập Gym" else "Vận động" }
                            val detailStr = if (workoutCategory == WorkoutCategory.GYM) {
                                "${setsText.ifBlank { "4" }} sets x ${repsText.ifBlank { "10" }} reps (${weightText.ifBlank { "50" }}kg)"
                            } else {
                                "Tốc độ: ${paceText.ifBlank { "5:30" }} • ${durationText.ifBlank { "30" }} phút"
                            }
                            val duration = if (workoutCategory == WorkoutCategory.GYM) (setsText.toFloatOrNull() ?: 4f) * 6f else (durationText.toFloatOrNull() ?: 30f)
                            val manualCal = manualCalorieText.toFloatOrNull() ?: 0f

                            stateHolder.addWorkoutScheduleMultiDays(finalName, workoutCategory, detailStr, currentMet, duration, manualCal, listOf(uiState.selectedDate))
                        },
                        modifier = Modifier.weight(1.2f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Lưu Hôm Nay", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.Black)
                    }

                    Button(
                        onClick = {
                            val finalName = exerciseName.ifBlank { if (workoutCategory == WorkoutCategory.GYM) "Tập Gym" else "Vận động" }
                            val detailStr = if (workoutCategory == WorkoutCategory.GYM) {
                                "${setsText.ifBlank { "4" }} sets x ${repsText.ifBlank { "10" }} reps (${weightText.ifBlank { "50" }}kg)"
                            } else {
                                "Tốc độ: ${paceText.ifBlank { "5:30" }} • ${durationText.ifBlank { "30" }} phút"
                            }
                            val duration = if (workoutCategory == WorkoutCategory.GYM) (setsText.toFloatOrNull() ?: 4f) * 6f else (durationText.toFloatOrNull() ?: 30f)
                            val manualCal = manualCalorieText.toFloatOrNull() ?: 0f

                            stateHolder.setTempWorkout(finalName, workoutCategory, detailStr, currentMet, duration, manualCal)
                            stateHolder.navigateTo(Screen.SET_SCHEDULE_WORKOUT)
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("SET LỊCH 📅", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color.Black)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.6. SET LỊCH CHO KHẨU PHẦN ĂN
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SetScheduleFoodScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        val context = LocalContext.current
        val today = LocalDate.now()
        var selectedDates by remember { mutableStateOf(setOf(uiState.selectedDate)) }
        var activeRepeatType by remember { mutableStateOf<String?>(null) }

        val previewMacro = uiState.previewMacro ?: MacroNutrient(20f, 40f, 10f)
        val foodName = uiState.previewFoodName.ifBlank { "Món ăn đã chọn" }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SET LỊCH ĂN UỐNG 📅", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box(modifier = Modifier.padding(horizontal = 12.dp).clickable { stateHolder.navigateBack() }) {
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Món ăn đang lên lịch:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(foodName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text("${previewMacro.calories.toInt()} kcal", fontSize = 15.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chọn thủ công ngày (${selectedDates.size} ngày):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val d = selectedDates.firstOrNull() ?: uiState.selectedDate
                                DatePickerDialog(
                                    context,
                                    { _, y, m, day ->
                                        val picked = LocalDate.of(y, m + 1, day)
                                        selectedDates = if (picked in selectedDates) selectedDates - picked else selectedDates + picked
                                    },
                                    d.year,
                                    d.monthValue - 1,
                                    d.dayOfMonth
                                ).show()
                            },
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    ) {
                        Text("📅 + Mở Lịch", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (offset in -2..14) {
                        val d = today.plusDays(offset.toLong())
                        val isSelected = d in selectedDates
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
                                .width(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedDates = if (isSelected) selectedDates - d else selectedDates + d
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(dayOfWeekStr, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color(0xFF94A3B8))
                                Text("${d.dayOfMonth}", fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (isSelected) Color.Black else Color.White)
                                Text(if (isSelected) "✓" else "•", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color(0xFF64748B))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Lặp lại theo chu kỳ:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))

                val baseDate = selectedDates.firstOrNull() ?: uiState.selectedDate
                val dayOfWeekVi = when (baseDate.dayOfWeek) {
                    DayOfWeek.MONDAY -> "Thứ 2"
                    DayOfWeek.TUESDAY -> "Thứ 3"
                    DayOfWeek.WEDNESDAY -> "Thứ 4"
                    DayOfWeek.THURSDAY -> "Thứ 5"
                    DayOfWeek.FRIDAY -> "Thứ 6"
                    DayOfWeek.SATURDAY -> "Thứ 7"
                    DayOfWeek.SUNDAY -> "Chủ Nhật"
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeRepeatType == "DAILY") {
                                    activeRepeatType = null
                                    selectedDates = setOf(uiState.selectedDate)
                                } else {
                                    activeRepeatType = "DAILY"
                                    selectedDates = (0..29).map { baseDate.plusDays(it.toLong()) }.toSet()
                                }
                            },
                        color = if (activeRepeatType == "DAILY") MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (activeRepeatType == "DAILY") MaterialTheme.colorScheme.primary else Color(0xFF26334D))
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔁", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Hàng ngày (30 ngày liên tiếp)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("Áp dụng món ăn vào mọi ngày", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeRepeatType == "WEEKLY") {
                                    activeRepeatType = null
                                    selectedDates = setOf(uiState.selectedDate)
                                } else {
                                    activeRepeatType = "WEEKLY"
                                    selectedDates = (0..11).map { baseDate.plusWeeks(it.toLong()) }.toSet()
                                }
                            },
                        color = if (activeRepeatType == "WEEKLY") MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (activeRepeatType == "WEEKLY") MaterialTheme.colorScheme.primary else Color(0xFF26334D))
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🗓️", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Hàng tuần vào $dayOfWeekVi (12 tuần)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("Lặp lại định kỳ vào mỗi $dayOfWeekVi", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeRepeatType == "MONTHLY") {
                                    activeRepeatType = null
                                    selectedDates = setOf(uiState.selectedDate)
                                } else {
                                    activeRepeatType = "MONTHLY"
                                    selectedDates = (0..5).map { baseDate.plusMonths(it.toLong()) }.toSet()
                                }
                            },
                        color = if (activeRepeatType == "MONTHLY") MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (activeRepeatType == "MONTHLY") MaterialTheme.colorScheme.primary else Color(0xFF26334D))
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📆", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Hàng tháng vào ngày ${baseDate.dayOfMonth} (6 tháng)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("Lặp lại vào ngày ${baseDate.dayOfMonth} hàng tháng", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val finalDates = selectedDates.ifEmpty { setOf(uiState.selectedDate) }.toList()
                        stateHolder.commitFoodLogMultiDays(foodName, previewMacro, finalDates)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Đẩy Lịch Cho ${selectedDates.size} Ngày Đã Chọn", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.Black)
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.7. SET LỊCH CHO TẬP LUYỆN
    // -----------------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SetScheduleWorkoutScreen(stateHolder: CalorieTrackerStateHolder, uiState: AppState) {
        val context = LocalContext.current
        val today = LocalDate.now()
        var selectedDates by remember { mutableStateOf(setOf(uiState.selectedDate)) }
        var activeRepeatType by remember { mutableStateOf<String?>(null) }

        val workoutName = uiState.tempWorkoutName.ifBlank { "Bài tập đã chọn" }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SET LỊCH TẬP LUYỆN 📅", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box(modifier = Modifier.padding(horizontal = 12.dp).clickable { stateHolder.navigateBack() }) {
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Bài tập lên lịch:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(workoutName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(uiState.tempWorkoutDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chọn thủ công ngày (${selectedDates.size} ngày):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val d = selectedDates.firstOrNull() ?: uiState.selectedDate
                                DatePickerDialog(
                                    context,
                                    { _, y, m, day ->
                                        val picked = LocalDate.of(y, m + 1, day)
                                        selectedDates = if (picked in selectedDates) selectedDates - picked else selectedDates + picked
                                    },
                                    d.year,
                                    d.monthValue - 1,
                                    d.dayOfMonth
                                ).show()
                            },
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    ) {
                        Text("📅 + Mở Lịch", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (offset in -2..14) {
                        val d = today.plusDays(offset.toLong())
                        val isSelected = d in selectedDates
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
                                .width(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedDates = if (isSelected) selectedDates - d else selectedDates + d
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(dayOfWeekStr, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color(0xFF94A3B8))
                                Text("${d.dayOfMonth}", fontSize = 14.sp, fontWeight = FontWeight.Black, color = if (isSelected) Color.Black else Color.White)
                                Text(if (isSelected) "✓" else "•", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.Black else Color(0xFF64748B))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Lặp lại theo chu kỳ:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                Spacer(modifier = Modifier.height(6.dp))

                val baseDate = selectedDates.firstOrNull() ?: uiState.selectedDate
                val dayOfWeekVi = when (baseDate.dayOfWeek) {
                    DayOfWeek.MONDAY -> "Thứ 2"
                    DayOfWeek.TUESDAY -> "Thứ 3"
                    DayOfWeek.WEDNESDAY -> "Thứ 4"
                    DayOfWeek.THURSDAY -> "Thứ 5"
                    DayOfWeek.FRIDAY -> "Thứ 6"
                    DayOfWeek.SATURDAY -> "Thứ 7"
                    DayOfWeek.SUNDAY -> "Chủ Nhật"
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeRepeatType == "DAILY") {
                                    activeRepeatType = null
                                    selectedDates = setOf(uiState.selectedDate)
                                } else {
                                    activeRepeatType = "DAILY"
                                    selectedDates = (0..29).map { baseDate.plusDays(it.toLong()) }.toSet()
                                }
                            },
                        color = if (activeRepeatType == "DAILY") MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (activeRepeatType == "DAILY") MaterialTheme.colorScheme.tertiary else Color(0xFF26334D))
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔁", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Hàng ngày (30 ngày liên tiếp)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("Áp dụng bài tập vào mọi ngày", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeRepeatType == "WEEKLY") {
                                    activeRepeatType = null
                                    selectedDates = setOf(uiState.selectedDate)
                                } else {
                                    activeRepeatType = "WEEKLY"
                                    selectedDates = (0..11).map { baseDate.plusWeeks(it.toLong()) }.toSet()
                                }
                            },
                        color = if (activeRepeatType == "WEEKLY") MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (activeRepeatType == "WEEKLY") MaterialTheme.colorScheme.tertiary else Color(0xFF26334D))
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🗓️", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Hàng tuần vào $dayOfWeekVi (12 tuần)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("Lặp lại vào mỗi $dayOfWeekVi", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (activeRepeatType == "MONTHLY") {
                                    activeRepeatType = null
                                    selectedDates = setOf(uiState.selectedDate)
                                } else {
                                    activeRepeatType = "MONTHLY"
                                    selectedDates = (0..5).map { baseDate.plusMonths(it.toLong()) }.toSet()
                                }
                            },
                        color = if (activeRepeatType == "MONTHLY") MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (activeRepeatType == "MONTHLY") MaterialTheme.colorScheme.tertiary else Color(0xFF26334D))
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📆", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Hàng tháng vào ngày ${baseDate.dayOfMonth} (6 tháng)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("Lặp lại vào ngày ${baseDate.dayOfMonth} hàng tháng", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val finalDates = selectedDates.ifEmpty { setOf(uiState.selectedDate) }.toList()
                        stateHolder.addWorkoutScheduleMultiDays(
                            uiState.tempWorkoutName,
                            uiState.tempWorkoutCategory,
                            uiState.tempWorkoutDetail,
                            uiState.tempWorkoutMet,
                            uiState.tempWorkoutDuration,
                            uiState.tempWorkoutManualCal,
                            finalDates
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Đẩy Lịch Cho ${selectedDates.size} Ngày Đã Chọn", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color.Black)
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3.8. ANALYTICS SCREEN
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
}
