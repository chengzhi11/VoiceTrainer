package com.femininevoicetrainer.audio

import be.tarsos.dsp.pitch.Yin
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.FrameDsp
import com.femininevoicetrainer.audio.VoiceFeatureExtractor.VoiceFeatures
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log2

/**
 * GH#5 降噪落地离线验收(硬口径,对照 COD-97 基线):
 *
 * ① 80 wav 判定一致:dist/denoise/ 同批 80 wav 逐文件过 Kotlin 五门判据,
 *    对照 baseline-gates.tsv 需 ≥78/80(允许 ±2 臂边界抖动,如 3.47/3.50 级);
 * ② Kotlin 降噪链复现:16 个 raw 源臂 × 5 变体(含 hpf150)由 DenoiseProcessor
 *    在线处理后过同一判据,对照 TSV 对应行——验证 Kotlin 实现与调研 numpy 原型等价;
 * ③ P-011 防假分红线:纯噪声两臂(×2 噪声 ×2 电平)过完整 rescue 链后仍 100% 拒判;
 * ④ rescue 端到端:snr+10 级臂第一 pass 拒判 TOO_NOISY → 降噪重评翻盘出分;
 *    snr+5 级臂重评后仍拒;翻盘臂 f0P50 漂移 ≤±25 音分(§4 容差)。
 *
 * 语料位于统一目录 dist/denoise/(git 忽略、本机留存);缺失时按 JUnit 假设跳过,
 * 不判失败(语料由 scripts/denoise-baseline.py 可再生成)。
 */
class DenoiseBaselineConsistencyTest {

    companion object {
        private const val SR = 44100
        private const val FRAME = 2048
        private const val MIN_F0 = 50.0   // PitchAnalyzer.MIN_F0
        private const val MAX_F0 = 500.0  // PitchAnalyzer.MAX_F0

        /** TSV 行:noise/arm/variant → (usable, failReason, levelSnr, f0Med) */
        private lateinit var baseline: Map<String, List<String>>

        private fun repoFile(rel: String): File? {
            var dir: File? = File(System.getProperty("user.dir") ?: ".")
            var hops = 0
            while (dir != null && hops < 6) {
                val f = File(dir, rel)
                if (f.exists()) return f
                dir = dir.parentFile
                hops++
            }
            return null
        }

        private fun corpusDir(): File? = repoFile("dist/denoise")?.takeIf { it.isDirectory }

        private fun loadBaseline(): Map<String, List<String>> {
            if (::baseline.isInitialized) return baseline
            val tsv = repoFile("dist/denoise/baseline-gates.tsv")
                ?: error("baseline-gates.tsv not found")
            val rows = tsv.readLines().drop(1)
                .filter { it.isNotBlank() }
                .associate { line ->
                    val cols = line.split("\t")
                    "${cols[0]}-${cols[1]}-${cols[2]}" to cols
                }
            assertEquals("基线应 80 行", 80, rows.size)
            baseline = rows
            return baseline
        }

        /** 幅度信噪比(P90 电平 / 噪声底;VoiceFeatures 未单独留存,按同式重算) */
        private fun snrOf(f: VoiceFeatures): Double =
            if (f.noiseFloor > 0.0) f.speechLevel / f.noiseFloor else Double.POSITIVE_INFINITY

        /** P-016 wav 读取口径:int16 小端 /32768 */
        fun readWav(path: File): FloatArray {
            val bytes = path.readBytes()
            assertTrue("wav 头异常: ${path.name}", bytes.size > 44)
            val n = (bytes.size - 44) / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                val lo = bytes[44 + 2 * i].toInt() and 0xFF
                val hi = bytes[44 + 2 * i + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort().toInt() / 32768.0f
            }
            return out
        }

        /**
         * 快速五门判定(判据层只需 RMS + YIN,不含 HNR/共振峰/倾斜——
         * analyze() 的门限与归因只消费这两路;与 VoiceFeatureExtractor.analyze 同一实现)。
         */
        fun judgeGates(pcm: FloatArray): VoiceFeatures {
            val nFrames = pcm.size / FRAME
            val yin = Yin(SR.toFloat(), FRAME)
            val dsp = ArrayList<FrameDsp>(nFrames)
            val f0s = ArrayList<Double>(nFrames)
            val probs = ArrayList<Double>(nFrames)
            val frame = FloatArray(FRAME)
            for (i in 0 until nFrames) {
                System.arraycopy(pcm, i * FRAME, frame, 0, FRAME)
                var f0 = 0.0
                var prob = 0.0
                val r = yin.getPitch(frame)
                if (r.isPitched()) {
                    val p = r.pitch.toDouble()
                    if (p in MIN_F0..MAX_F0) {
                        f0 = p
                        prob = r.probability.toDouble()
                    }
                }
                dsp.add(FrameDsp(VoiceFeatureExtractor.computeRms(frame), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN))
                f0s.add(f0)
                probs.add(prob)
            }
            return VoiceFeatureExtractor.analyze(dsp, f0s, probs)
        }

        /** Kotlin 变体变换(镜像 scripts/denoise-baseline.py VARIANTS) */
        fun applyVariant(pcm: FloatArray, variant: String): FloatArray = when (variant) {
            "raw" -> pcm.copyOf()
            "hpf120" -> DenoiseProcessor.highPass(pcm, 120.0)
            "hpf150" -> DenoiseProcessor.highPass(pcm, 150.0)
            "ss" -> DenoiseProcessor.spectralSubtract(pcm)
            "hpf120+ss" -> DenoiseProcessor.process(pcm)
            else -> error("unknown variant $variant")
        }

        private fun mismatchText(key: String, f: VoiceFeatures, row: List<String>): String =
            "$key: kotlin=${if (f.isUsable) "PASS" else "FAIL"}/${f.failReason?.name ?: "-"} " +
                "snr=${"%.2f".format(snrOf(f))} vs 基线=${row[3]}/${row[4]} snr=${row[7]}"
    }

    // ---- ① 80 wav 文件判定一致(验收硬口径 ≥78/80)----

    @Test
    fun wavFiles_80_gatesConsistentWithBaseline() {
        val dir = corpusDir()
        assumeTrue("dist/denoise 语料缺失,跳过(COD-97 基线本机留存)", dir != null)
        val d = dir!!
        val tsv = loadBaseline()

        var ok = 0
        val diffs = StringBuilder()
        for ((key, row) in tsv.entries.sortedBy { it.key }) {
            val wav = File(d, "$key.wav")
            assumeTrue("语料文件缺失: $key.wav", wav.exists())
            val f = judgeGates(readWav(wav))
            val pass = f.isUsable == (row[3] == "PASS") &&
                (f.failReason?.name ?: "-") == row[4]
            if (pass) ok++ else diffs.appendln(mismatchText(key, f, row))
        }
        println("ACCEPT ① 80 wav 文件判定一致 $ok/80")
        assertTrue(
            "80 wav 判定一致数 $ok/80 < 78(允许 ±2 边界抖动)。不一致臂:\n$diffs",
            ok >= 78
        )
    }

    // ---- ② Kotlin 降噪链在 16 源臂 × 5 变体上复现基线 ----

    @Test
    fun kotlinChain_16arms5variants_consistentWithBaseline() {
        val dir = corpusDir()
        assumeTrue("dist/denoise 语料缺失,跳过", dir != null)
        val tsv = loadBaseline()
        val variants = listOf("raw", "hpf120", "hpf150", "ss", "hpf120+ss")

        // raw 源臂缓存:每臂读一次,5 变体复用
        val rawCache = HashMap<String, FloatArray>()
        fun rawOf(noise: String, arm: String): FloatArray {
            val key = "$noise-$arm"
            return rawCache.getOrPut(key) { readWav(File(dir!!, "$key-raw.wav")) }
        }

        var ok = 0
        var total = 0
        val diffs = StringBuilder()
        for ((key, row) in tsv.entries.sortedBy { it.key }) {
            val parts = key.split("-")
            val noise = parts[0]
            val arm = parts.subList(1, parts.size - 1).joinToString("-")
            val variant = parts.last()
            if (variant !in variants) continue
            val f = judgeGates(applyVariant(rawOf(noise, arm), variant))
            val pass = f.isUsable == (row[3] == "PASS") &&
                (f.failReason?.name ?: "-") == row[4]
            total++
            if (pass) ok++ else diffs.appendln(mismatchText(key, f, row))
        }
        println("ACCEPT ② Kotlin 链(16 臂 × 5 变体)判定一致 $ok/$total")
        if (diffs.isNotEmpty()) println("ACCEPT ② 不一致臂:\n$diffs")
        assertEquals(80, total)
        assertTrue(
            "Kotlin 链判定一致数 $ok/80 < 78(允许 ±2 边界抖动)。不一致臂:\n$diffs",
            ok >= 78
        )
    }

    // ---- ③ P-011 防假分:纯噪声臂过完整 rescue 链后仍 100% 拒判 ----

    @Test
    fun pureNoiseArms_fullRescueChain_stillRejected() {
        val dir = corpusDir()
        assumeTrue("dist/denoise 语料缺失,跳过", dir != null)

        val arms = listOf(
            "pink-noise_only", "pink-noise_only_hi",
            "car-noise_only", "car-noise_only_hi"
        )
        for (arm in arms) {
            val pcm = readWav(File(dir!!, "$arm-raw.wav"))
            val firstPass = judgeGates(pcm)
            assertFalse("纯噪声臂第一 pass 不应出分: $arm", firstPass.isUsable)

            // 完整 rescue 链(降噪 + 离线重评,生产同路径)
            val rescued = DenoiseRescue.rescue(pcm)
            assertFalse("P-011 红线:纯噪声臂($arm)过降噪链后不得出分", rescued.isUsable)
            assertNotNull("纯噪声臂($arm)重评必须带归因", rescued.failReason)
            assertTrue(
                "纯噪声臂($arm)重评 levelSnr=${snrOf(rescued)} 仍须低于门限 ${VoiceTypeThresholds.MIN_LEVEL_SNR}",
                snrOf(rescued) < VoiceTypeThresholds.MIN_LEVEL_SNR
            )
        }
    }

    // ---- ④ rescue 端到端:边界臂拒判→降噪重评翻盘/维持 ----

    @Test
    fun rescue_endToEnd_matchesBaselineAndDriftTolerance() {
        val dir = corpusDir()
        assumeTrue("dist/denoise 语料缺失,跳过", dir != null)
        val tsv = loadBaseline()

        // 车噪/粉噪 snr+10 与粉噪 snr+15(基线:重评过线) + 车噪 snr+5(基线:重评仍拒)
        val cases = listOf("car-snr+10", "pink-snr+10", "pink-snr+15", "car-snr+5", "car-noise_only")
        for (arm in cases) {
            val pcm = readWav(File(dir!!, "$arm-raw.wav"))
            val firstPass = OfflinePcmAnalyzer.analyze(pcm)

            // 第一 pass 与基线 raw 行一致(除车噪 noise_only 臂基线即拒)
            val rawRow = tsv.getValue("$arm-raw")
            assertEquals(
                "$arm 第一 pass usable 应与基线 raw 行一致",
                rawRow[3] == "PASS", firstPass.isUsable
            )

            // 触发条件:TOO_NOISY 且电平达标 → 重评结果与基线 hpf120+ss 行一致
            if (DenoiseRescue.shouldRescue(firstPass)) {
                val rescued = DenoiseRescue.rescue(pcm)
                val rescueRow = tsv.getValue("$arm-hpf120+ss")
                assertEquals(
                    "$arm 降噪重评 usable 应与基线 hpf120+ss 行一致(基线 ${rescueRow[3]},kotlin ${rescued.isUsable})",
                    rescueRow[3] == "PASS", rescued.isUsable
                )
                if (rescued.isUsable && rescueRow[10].toDouble() > 0.0) {
                    // §4 容差:f0P50 漂移 ≤ ±25 音分(对照基线 f0_med)
                    val cents = 1200.0 * log2(rescued.f0P50 / rescueRow[10].toDouble())
                    assertTrue(
                        "$arm 重评 f0P50=${rescued.f0P50} 漂移 ${"%.1f".format(cents)} 音分超 ±25",
                        abs(cents) <= 25.0
                    )
                }
            } else {
                fail("$arm 应触发 rescue(基线 raw 行归因 ${rawRow[4]})")
            }
        }
    }
}
