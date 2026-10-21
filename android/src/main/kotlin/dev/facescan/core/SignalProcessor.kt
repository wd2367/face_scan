package dev.facescan.core

import kotlin.math.*

/** 以 30 FPS 为基准处理绿色通道信号。
 * 时间戳用于有效采样时长和质量统计，频率计算使用固定采样基准。
 */
internal object SignalProcessor {
    fun peak(samples: List<Double>, minHz: Double, maxHz: Double, window: Int = 512, smooth: Boolean = false): Double {
        if (samples.size < 64) return 0.0
        val use = min(samples.size, window)
        val data = samples.takeLast(use)
        val mean = data.average()
        var signal = DoubleArray(use) { i -> (data[i] - mean) * (0.5 - 0.5 * cos(2.0 * PI * i / (use - 1))) }
        if (smooth) {
            val original = signal
            signal = DoubleArray(use) { i -> original[i] * 0.25 + original.getOrElse(i + 1) { 0.0 } * 0.5 + original.getOrElse(i + 2) { 0.0 } * 0.25 }
        }
        // 只计算通带内 DFT，与 zero-padded 1024 点 FFT 的通带峰值相同。
        val lo = max(1, (minHz * 1024 / 30).toInt())
        val hi = min(511, (maxHz * 1024 / 30).toInt())
        var best = lo
        var power = 0.0
        for (bin in lo..hi) {
            var real = 0.0; var imaginary = 0.0
            for (i in signal.indices) {
                val angle = 2.0 * PI * bin * i / 1024
                real += signal[i] * cos(angle)
                imaginary -= signal[i] * sin(angle)
            }
            val current = real * real + imaginary * imaginary
            if (current > power) { power = current; best = bin }
        }
        return if (power <= 1e-12) 0.0 else best * 30.0 / 1024
    }
    fun livePulse(green: List<Double>) = peak(green, 45.0 / 60, 150.0 / 60, 256, true) * 60
    private fun clamp(x: Double, min: Double, max: Double) = x.coerceIn(min, max)
    private fun round(x: Double) = floor(x + 0.5).toInt()

    /** 派生指标由信号和个人资料计算，失败判定由采样层负责。 */
    fun result(pulses: List<Double>, green: List<Double>, profile: Map<*, *>): Map<String, Any> {
        require(pulses.isNotEmpty() && green.size >= 270) { "Insufficient signal" }
        val age = (profile["age"] as? Number)?.toInt()?.coerceIn(12, 99) ?: 40
        val gender = if (profile["gender"] == "male") 1.0 else 0.0
        val height = (profile["heightCm"] as? Number)?.toDouble() ?: 165.0
        val weight = (profile["weightKg"] as? Number)?.toDouble() ?: 60.0
        val bmi = weight / (height / 100).pow(2)
        val rr = pulses.filter { it > 0 }.map { 60000 / it }
        val fromRR = if (rr.isEmpty()) 0.0 else 60000 / rr.average()
        val fft = peak(green, 0.75, 2.5) * 60
        val pulse = if (fromRR > 0 && (fft <= 0 || abs(fromRR - fft) <= 10)) fromRR else fft
        require(pulse in 30.0..220.0) { "Unreliable pulse" }
        var hrv = if (rr.size > 1) sqrt(rr.zipWithNext { a, b -> (b - a).pow(2) }.average()) else 0.0
        if (hrv <= 0) hrv = 25.0 // 派生指标的默认值不是实测值。
        val brRaw = peak(green, 0.10, 0.50) * 60
        val breathing = round(if (brRaw > 0) brRaw else 15.0)
        val hrvStress = clamp(100 - (hrv - 10) / 50 * 100, 0.0, 100.0)
        val hrStress = when { pulse > 90 -> min(100.0, (pulse - 90) * 4); pulse < 60 -> min(100.0, (60 - pulse) * 3); else -> 0.0 }
        val respStress = when { breathing < 12 -> min(100.0, (12 - breathing) * 10.0); breathing > 18 -> min(100.0, (breathing - 18) * 10.0); else -> 0.0 }
        val stress = clamp(0.60 * hrvStress + 0.25 * hrStress + 0.15 * respStress, 0.0, 100.0)
        val hrScore = when { pulse > 90 -> max(30.0, 100 - (pulse - 90) * 2); pulse < 60 -> max(30.0, 100 - (60 - pulse) * 2); else -> 100.0 }
        val hrvScore = when { hrv <= 10 -> 30.0; hrv >= 60 -> 100.0; else -> 30 + (hrv - 10) / 50 * 70 }
        val brScore = when { breathing < 12 -> max(30.0, 100 - (12 - breathing) * 8.0); breathing > 18 -> max(30.0, 100 - (breathing - 18) * 8.0); else -> 100.0 }
        val score = round(clamp(0.35 * hrScore + 0.35 * hrvScore + 0.15 * (100 - stress) + 0.15 * brScore, 0.0, 100.0))
        val hrAge = when { pulse < 50 -> 2; pulse < 60 -> 0; pulse <= 80 -> -1; pulse <= 90 -> 0; pulse <= 100 -> 2; else -> 4 }
        val hrvAge = when { hrv > 50 -> -3; hrv >= 35 -> -1; hrv >= 25 -> 2; else -> 5 }
        val stressAge = when { stress < 30 -> -2; stress < 60 -> 1; stress < 80 -> 3; else -> 5 }
        val brAge = when { breathing in 12..18 -> 0; breathing in 10..20 -> 1; else -> 2 }
        val heartAge = (age + hrAge + hrvAge + stressAge + brAge).coerceIn(age - 10, age + 15)
        val hrLoad = when { pulse <= 60 -> 20.0; pulse <= 100 -> 20 + (pulse - 60) / 40 * 50; pulse <= 120 -> 70 + (pulse - 100) / 20 * 30; else -> 100.0 }
        val workload = round(clamp(0.5 * hrLoad + 0.3 * stress + 0.2 * (100 - hrvScore), 0.0, 100.0))
        val ageD = age - 40.0; val bmiD = bmi - 22; val hrD = pulse - 70; val hrvD = 40 - hrv
        var sbp = round(clamp(120 + 0.4 * ageD + bmiD + 0.3 * hrD + 0.15 * hrvD + 2 * gender, 85.0, 180.0))
        val dbp = round(clamp(78 + 0.15 * ageD + 0.5 * bmiD + 0.2 * hrD + 0.08 * hrvD + 1.5 * gender, 50.0, 120.0))
        if (sbp <= dbp) sbp = min(180, dbp + 1)
        val sugar = round(clamp(88 + 0.22 * ageD + 1.6 * bmiD + 0.1 * hrD + 0.08 * hrvD + 0.18 * (stress - 30) + 2 * gender, 70.0, 180.0))
        val bpRisk = max(clamp((sbp - 110.0) / 60 * 100, 0.0, 100.0), clamp((dbp - 70.0) / 40 * 100, 0.0, 100.0))
        val ageRisk = clamp((age - 20.0) / 60 * 100, 0.0, 100.0)
        val bmiRisk = clamp((bmi - 18.5) / 21.5 * 100, 0.0, 100.0)
        val hrvRisk = clamp((50 - hrv) / 40 * 100, 0.0, 100.0)
        val hrRisk = when { pulse < 60 -> min(100.0, (60 - pulse) * 5); pulse > 100 -> min(100.0, (pulse - 100) * 5); else -> 0.0 }
        return mapOf("pulse" to round(pulse), "score" to score, "hrv" to round(hrv), "stress" to round(stress), "breathing" to breathing, "workload" to workload,
            "heartAge" to heartAge, "wellnessAge" to heartAge, "userAge" to age, "systolic" to sbp, "diastolic" to dbp, "sugar" to sugar,
            "cvd" to round(0.3 * bpRisk + 0.2 * hrRisk + 0.15 * hrvRisk + 0.15 * stress + 0.1 * ageRisk + 0.1 * bmiRisk) / 10.0,
            "diabetes" to round(0.5 * clamp((sugar - 80.0) / 60 * 100, 0.0, 100.0) + 0.2 * bmiRisk + 0.15 * ageRisk + 0.1 * stress + 0.05 * hrvRisk) / 10.0,
            "hyper" to round(0.55 * bpRisk + 0.2 * ageRisk + 0.15 * bmiRisk + 0.1 * hrvRisk) / 10.0,
            "pulseSeries" to pulses.toList(), "estimated" to true)
    }
}
