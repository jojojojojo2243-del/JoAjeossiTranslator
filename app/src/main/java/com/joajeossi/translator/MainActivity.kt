package com.joajeossi.translator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * 양방향 통역 화면 (1차 버전)
 *
 * - 화면 위쪽 절반: 상대방용 (180도 돌려 놓아서 마주 앉은 상대가 바로 읽을 수 있음)
 * - 화면 아래쪽 절반: 나용
 * - 각자 자기 쪽 버튼을 누르고 한 문장 말하면 → 번역 → 상대 쪽 화면에 크게 표시 + 상대 언어로 읽어 줌
 * - 번역기는 방향마다 따로 둠 (나→상대, 상대→나)
 * - 버튼을 한 번 누르면 한 문장만 듣고 멈춤. 그래서 읽어 주는 소리를 다시 번역하는 일이 없음
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    /** 지원 언어. speechTag = 음성인식 언어, ttsLocale = 읽어 주기 언어 */
    private data class Lang(val label: String, val code: String, val speechTag: String, val ttsLocale: Locale)

    private val languages = listOf(
        Lang("한국어", TranslateLanguage.KOREAN, "ko-KR", Locale.KOREAN),
        Lang("영어", TranslateLanguage.ENGLISH, "en-US", Locale.US),
        Lang("일본어", TranslateLanguage.JAPANESE, "ja-JP", Locale.JAPANESE),
        Lang("중국어", TranslateLanguage.CHINESE, "zh-CN", Locale.SIMPLIFIED_CHINESE),
        Lang("태국어", TranslateLanguage.THAI, "th-TH", Locale("th", "TH")),
        Lang("베트남어", TranslateLanguage.VIETNAMESE, "vi-VN", Locale("vi", "VN")),
        // 필리핀어: ML Kit 번역은 타갈로그어(tl)로 지원. 음성인식/읽어 주기는 휴대폰에 따라 안 될 수 있음
        Lang("필리핀어(타갈로그)", TranslateLanguage.TAGALOG, "fil-PH", Locale("fil", "PH"))
    )

    private enum class Speaker { ME, OTHER }

    private class Side(
        val panel: LinearLayout,
        val sourceText: TextView,
        val translatedText: TextView,
        val button: Button
    )

    private lateinit var me: Side
    private lateinit var other: Side
    private lateinit var status: TextView

    private var speech: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var myLang = languages[0]      // 기본: 나 = 한국어
    private var otherLang = languages[1]   // 기본: 상대 = 영어

    private var meToOther: Translator? = null
    private var otherToMe: Translator? = null

    /** 지금 듣고 있는 사람 (null 이면 아무도 안 듣는 중) */
    private var activeSpeaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildScreen()
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speech = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        } else {
            status.text = "이 기기에서 음성인식을 사용할 수 없습니다."
        }
        requestAudioPermission()
        configureTranslators()
    }

    // ───────────────────────── 화면 ─────────────────────────

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7FF.toInt())
        }
        status = TextView(this).apply {
            text = "통역 준비 중..."
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFFE3E9F7.toInt())
            setPadding(8, 12, 8, 12)
        }

        other = buildSide(
            title = "상대방",
            sourceHint = "상대방이 한 말",
            translatedHint = "제 말의 번역이 여기에 보입니다",
            color = 0xFF0F7B5F.toInt(),
            initialLang = 1
        ) { pos -> otherLang = languages[pos]; configureTranslators() }

        me = buildSide(
            title = "나",
            sourceHint = "내가 한 말",
            translatedHint = "상대방 말의 번역이 여기에 보입니다",
            color = 0xFF1453B8.toInt(),
            initialLang = 0
        ) { pos -> myLang = languages[pos]; configureTranslators() }

        // 상대방 쪽 절반은 180도 돌려서 마주 앉은 사람이 읽기 쉽게
        other.panel.rotation = 180f

        root.addView(other.panel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(me.panel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        keepClearOfSystemBars(root)

        me.button.setOnClickListener { onSpeakPressed(Speaker.ME) }
        other.button.setOnClickListener { onSpeakPressed(Speaker.OTHER) }
        updateButtons()

        // GitHub에 새 버전이 올라와 있으면 업데이트를 물어본다
        AppUpdater.checkForUpdate(this)
    }

    /**
     * Android 15(targetSdk 35)부터는 앱 화면이 상태바/내비게이션바 밑까지 그려진다.
     * 그래서 위쪽 상대방 버튼이 상태바에, 아래쪽 내 버튼이 내비게이션바에 가렸다.
     * 시스템 바와 노치 크기만큼 안쪽 여백을 줘서 버튼이 다 보이게 한다.
     */
    private fun keepClearOfSystemBars(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun buildSide(
        title: String, sourceHint: String, translatedHint: String,
        color: Int, initialLang: Int, onLangChanged: (Int) -> Unit
    ): Side {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title; textSize = 18f; setTextColor(color)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.label })
            setSelection(initialLang)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p0: AdapterView<*>?) {}
                override fun onItemSelected(p0: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    onLangChanged(position)
                }
            }
        }
        header.addView(spinner, LinearLayout.LayoutParams(0, -2, 1.4f))

        val translated = bubble(translatedHint, 20f, 0xFF17233D.toInt())
        val source = bubble(sourceHint, 14f, 0xFF666666.toInt())
        val button = Button(this).apply {
            textSize = 18f
            minHeight = 0; minimumHeight = 0
            setPadding(0, 22, 0, 22)
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(color)
        }

        // 버튼을 패널의 맨 처음에 둔다. 아래쪽(나) 패널에서는 화면 가운데 쪽,
        // 180도 돌린 위쪽(상대방) 패널에서도 화면 가운데 쪽에 오게 된다.
        // 그래서 상태바나 내비게이션바가 있어도 두 버튼은 가운데에 모여 항상 눌린다.
        panel.addView(button, LinearLayout.LayoutParams(-1, -2))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4 })
        // 번역(내가 읽을 글)은 남는 공간을 쓰고 길면 스크롤, 원문은 작게
        panel.addView(ScrollView(this).apply { addView(translated) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 4 })
        panel.addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4 })
        return Side(panel, source, translated, button)
    }

    private fun bubble(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(16, 10, 16, 10)
    }

    // ───────────────────────── 번역기 (방향마다 하나씩) ─────────────────────────

    private fun configureTranslators() {
        if (!::status.isInitialized) return
        meToOther?.close(); otherToMe?.close()
        meToOther = null; otherToMe = null
        if (myLang.code == otherLang.code) {
            status.text = "나와 상대방의 언어를 다르게 골라 주세요."
            return
        }
        status.text = "번역 모델 준비 중... (처음엔 다운로드가 필요합니다)"
        val a = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(myLang.code).setTargetLanguage(otherLang.code).build())
        val b = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(otherLang.code).setTargetLanguage(myLang.code).build())
        meToOther = a; otherToMe = b

        val conditions = DownloadConditions.Builder().build()
        var done = 0
        val onReady = {
            done++
            if (done == 2 && meToOther === a) status.text = "준비 완료 · ${myLang.label} ↔ ${otherLang.label}"
        }
        a.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { if (meToOther === a) status.text = "번역 모델 다운로드 실패: 인터넷 연결을 확인하세요." }
        b.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { if (meToOther === a) status.text = "번역 모델 다운로드 실패: 인터넷 연결을 확인하세요." }
    }

    // ───────────────────────── 듣기 ─────────────────────────

    private fun requestAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    private fun onSpeakPressed(who: Speaker) {
        // 듣는 중에 같은 버튼을 다시 누르면 중지
        if (activeSpeaker == who) { stopListening("중지했습니다."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission(); return
        }
        val recognizer = speech ?: run { status.text = "이 기기에서 음성인식을 사용할 수 없습니다."; return }
        if (myLang.code == otherLang.code) { status.text = "나와 상대방의 언어를 다르게 골라 주세요."; return }

        if (activeSpeaker != null) recognizer.cancel()   // 다른 사람이 듣는 중이었으면 넘겨받기
        if (ttsReady) tts.stop()                          // 읽어 주는 중이면 끊고 바로 듣기

        activeSpeaker = who
        updateButtons()
        val lang = if (who == Speaker.ME) myLang else otherLang
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.speechTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang.speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.startListening(intent)
            status.text = "${lang.label}로 말씀해 주세요..."
        } catch (_: Exception) {
            stopListening("음성인식을 시작하지 못했습니다.")
        }
    }

    private fun stopListening(message: String?) {
        speech?.cancel()
        activeSpeaker = null
        updateButtons()
        if (message != null) status.text = message
    }

    private fun updateButtons() {
        me.button.text = if (activeSpeaker == Speaker.ME) "■ 듣는 중 (누르면 중지)" else "🎙 내가 말하기"
        // 상대방 버튼 글자는 우선 한국어로 둠 (상대 언어로 바꾸는 건 다음 버전)
        other.button.text = if (activeSpeaker == Speaker.OTHER) "■ 듣는 중 (누르면 중지)" else "🎙 상대방 말하기"
        me.button.alpha = if (activeSpeaker == Speaker.OTHER) 0.5f else 1f
        other.button.alpha = if (activeSpeaker == Speaker.ME) 0.5f else 1f
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { status.text = "듣고 있습니다..." }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status.text = "번역 중..." }
        override fun onError(error: Int) {
            if (activeSpeaker == null) return   // 직접 취소한 경우
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "말소리를 못 들었습니다. 다시 눌러 주세요."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "이 휴대폰은 이 언어 음성인식을 지원하지 않습니다."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "음성인식 네트워크 오류입니다. 인터넷을 확인해 주세요."
                else -> "음성인식 오류($error). 다시 눌러 주세요."
            }
            stopListening(msg)
        }
        override fun onResults(results: Bundle?) {
            val who = activeSpeaker
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            activeSpeaker = null
            updateButtons()
            if (who != null && !text.isNullOrBlank()) translate(who, text)
            else status.text = "말소리를 못 들었습니다. 다시 눌러 주세요."
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) return
            when (activeSpeaker) {
                Speaker.ME -> me.sourceText.text = text
                Speaker.OTHER -> other.sourceText.text = text
                null -> {}
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ───────────────────────── 번역 + 읽어 주기 ─────────────────────────

    private fun translate(who: Speaker, text: String) {
        val speakerSide = if (who == Speaker.ME) me else other
        val listenerSide = if (who == Speaker.ME) other else me
        val translator = if (who == Speaker.ME) meToOther else otherToMe
        val targetLang = if (who == Speaker.ME) otherLang else myLang

        speakerSide.sourceText.text = text
        if (translator == null) { status.text = "번역기를 준비하고 있습니다."; return }
        status.text = "번역 중..."
        translator.translate(text).addOnSuccessListener { result ->
            // 번역 결과는 듣는 사람 쪽 화면에 크게 보여 줌
            listenerSide.translatedText.text = result
            status.text = "${myLang.label} ↔ ${otherLang.label}"
            speak(result, targetLang)
        }.addOnFailureListener {
            status.text = "번역에 실패했습니다. 모델 다운로드가 끝났는지 확인해 주세요."
        }
    }

    private fun speak(text: String, lang: Lang) {
        if (!ttsReady) return
        // 읽는 동안 마이크는 꺼져 있어야 함 (자기 목소리를 다시 번역하지 않도록)
        if (activeSpeaker != null) stopListening(null)
        val r = tts.setLanguage(lang.ttsLocale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = "이 휴대폰은 ${lang.label} 읽어 주기를 지원하지 않습니다. 화면 글자를 보여 주세요."
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translated")
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { status.text = "읽어 주는 중..." }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread { if (activeSpeaker == null) status.text = "${myLang.label} ↔ ${otherLang.label}" }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    override fun onDestroy() {
        speech?.destroy()
        if (::tts.isInitialized) tts.shutdown()
        meToOther?.close(); otherToMe?.close()
        super.onDestroy()
    }
}
