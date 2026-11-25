package com.joajeossi.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * 인터넷(구글 Gemini)으로 "무슨 언어로 말했는지"를 알아서 구분하는 통역기.
 *
 * 1) 마이크로 계속 듣다가, 소리가 커지면 말 시작 / 1.2초쯤 조용하면 말 끝으로 (잠깐 멈췄다 이어 말해도 한 마디로) 본다.
 * 2) 그 한 마디를 녹음 파일(WAV)로 만들어 Gemini 에 보낸다.
 * 3) Gemini 가 "어느 언어인지 + 받아 적은 글 + 반대 언어로 번역"을 돌려준다.
 *
 * 휴대폰 음성 인식은 한 번에 한 언어로만 듣기 때문에, 두 언어를 자동 구분하려면 이 방법이 필요하다.
 */
class GeminiInterpreter(
    private val apiKey: String,
    /** 한 마디가 끝나면 녹음(WAV)을 넘겨준다. 백그라운드 스레드에서 불린다. */
    private val onUtterance: (ByteArray) -> Unit,
    /** 말이 시작되면 알려 준다 (화면 표시용). 백그라운드 스레드에서 불린다. */
    private val onSpeechStart: () -> Unit,
    /** 마이크를 못 쓸 때. 백그라운드 스레드에서 불린다. */
    private val onMicError: () -> Unit
) {
    data class Result(val lang: String, val text: String, val translation: String)

    @Volatile private var running = false
    /** true 면 마이크 소리를 버린다 (번역문을 읽는 중이거나 처리 중일 때) */
    @Volatile var paused = false
        set(value) { field = value; if (!value) resetRequested = true }
    @Volatile private var resetRequested = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread { recordLoop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 4))
        } catch (_: Exception) { onMicError(); running = false; return }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); onMicError(); running = false; return }

        val frame = ShortArray(FRAME)
        val preRoll = ArrayDeque<ShortArray>()          // 말 시작 직전 소리 (첫 글자가 잘리지 않게)
        val speechBuf = ByteArrayOutputStream()
        var noise = 300.0                               // 주변 소음 크기 (계속 따라감)
        var inSpeech = false
        var loudFrames = 0
        var silentFrames = 0
        var speechFrames = 0

        fun reset() {
            preRoll.clear(); speechBuf.reset()
            inSpeech = false; loudFrames = 0; silentFrames = 0; speechFrames = 0
        }

        try {
            recorder.startRecording()
            while (running) {
                val n = recorder.read(frame, 0, FRAME)
                if (n <= 0) continue
                if (resetRequested) { resetRequested = false; reset() }
                if (paused) { if (inSpeech || preRoll.isNotEmpty()) reset(); continue }

                val level = rms(frame, n)
                val threshold = maxOf(noise * 2.5, 450.0)
                val loud = level > threshold

                if (!inSpeech) {
                    // 조용할 때만 소음 크기를 천천히 따라간다
                    if (!loud) noise = noise * 0.95 + level * 0.05
                    preRoll.addLast(frame.copyOf(n))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    loudFrames = if (loud) loudFrames + 1 else 0
                    if (loudFrames >= START_FRAMES) {
                        inSpeech = true
                        silentFrames = 0
                        speechFrames = loudFrames
                        preRoll.forEach { writePcm(speechBuf, it) }
                        preRoll.clear()
                        onSpeechStart()
                    }
                } else {
                    writePcm(speechBuf, frame.copyOf(n))
                    speechFrames++
                    silentFrames = if (loud) 0 else silentFrames + 1
                    val ended = silentFrames >= END_SILENCE_FRAMES
                    val tooLong = speechFrames >= MAX_FRAMES
                    if (ended || tooLong) {
                        val voiced = speechFrames - silentFrames
                        val pcm = speechBuf.toByteArray()
                        reset()
                        if (voiced >= MIN_VOICED_FRAMES) {
                            paused = true          // 처리하고 읽는 동안은 듣지 않는다
                            onUtterance(wav(pcm))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (running) onMicError()
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun writePcm(out: ByteArrayOutputStream, s: ShortArray) {
        for (v in s) { out.write(v.toInt() and 0xFF); out.write((v.toInt() shr 8) and 0xFF) }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) { for (i in 0..3) out.write((v shr (8 * i)) and 0xFF) }
        fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1)
        int(SAMPLE_RATE); int(SAMPLE_RATE * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /**
     * 녹음 한 마디를 Gemini 에 보내서 언어 구분 + 받아 적기 + 번역을 받는다. (백그라운드 스레드에서 부를 것)
     * langA/langB = 언어 코드(예: "ko", "zh"), nameA/nameB = 영어 이름(예: "Korean").
     */
    fun recognize(wav: ByteArray, langA: String, nameA: String, langB: String, nameB: String): Result {
        val prompt = """
            The audio is one person speaking either $nameA (code "$langA") or $nameB (code "$langB").
            1. Decide which of these two languages is spoken.
            2. Transcribe exactly what was said, in that language's normal writing system.
            3. Translate it naturally into the OTHER of the two languages.
            Reply with JSON only: {"lang": "$langA" or "$langB" or "none", "text": "...", "translation": "..."}
            If there is no clear human speech (only noise, music, or silence), reply {"lang":"none","text":"","translation":""}.
        """.trimIndent()

        val audioPart = JSONObject().put("inline_data", JSONObject()
            .put("mime_type", "audio/wav")
            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
        val contents = JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(audioPart)))

        // 구글 서버가 바쁘면(503 등) 다른 모델로 바로 바꿔 보고, 다 바쁘면 잠깐 쉬었다가 한 번 더 돈다.
        var lastError: Exception? = null
        for (round in 0 until 2) {
            if (round > 0) Thread.sleep(800)
            val order = (listOfNotNull(workingModel) + MODELS).distinct()
            for (model in order) {
                if (model in missingModels) continue
                for (withThinkingOff in listOf(true, false)) {
                    val config = JSONObject().put("temperature", 0).put("responseMimeType", "application/json")
                    if (withThinkingOff) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    val body = JSONObject().put("contents", contents).put("generationConfig", config)
                    try {
                        val result = parse(post(model, body.toString()))
                        workingModel = model            // 다음엔 잘 되던 모델부터 쓴다
                        return result
                    } catch (e: HttpError) {
                        lastError = e
                        val keyProblem = e.body.contains("API key", true) || e.body.contains("API_KEY", true)
                        if (keyProblem) throw e         // 열쇠 문제는 다른 모델로 바꿔도 소용없다
                        if (e.code == 400 && withThinkingOff) continue   // 이 모델은 생각 끄기 옵션을 모름
                        if (e.code == 404) missingModels.add(model)      // 없는 모델 이름
                        if (workingModel == model) workingModel = null
                        break                            // 바쁨(503/500/429) 등: 다음 모델로
                    } catch (e: Exception) {
                        lastError = e                    // 인터넷 끊김, 응답 해석 실패 등: 다음 모델로
                        break
                    }
                }
            }
        }
        throw lastError ?: RuntimeException("Gemini 응답 없음")
    }

    @Volatile private var workingModel: String? = null
    private val missingModels = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    class HttpError(val code: Int, val body: String) : RuntimeException("HTTP $code")

    private fun post(model: String, json: String): String {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(response: String): Result {
        val parts = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        var t = sb.toString().trim()
        // 혹시 ```json ... ``` 으로 감싸서 오면 벗긴다
        if (t.startsWith("```")) t = t.substringAfter('\n').substringBeforeLast("```").trim()
        val o = JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
        return Result(o.optString("lang", "none").lowercase().trim(), o.optString("text").trim(), o.optString("translation").trim())
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME = 480                     // 30ms
        private const val PRE_ROLL_FRAMES = 10            // 0.3초
        private const val START_FRAMES = 3                // 0.09초 이상 소리가 크면 말 시작
        // 말하다 잠깐 멈춰도(생각하느라) 끊기지 않게 1.2초 조용해야 말 끝으로 본다
        private const val END_SILENCE_FRAMES = 40
        private const val MIN_VOICED_FRAMES = 10          // 0.3초보다 짧은 소리는 무시 (기침, 탁 소리 등)
        private const val MAX_FRAMES = 500                // 한 마디 최대 15초
        /** 앞에서부터 써 보고, 없는 모델이면 다음 것 */
        private val MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.0-flash")
    }
}
