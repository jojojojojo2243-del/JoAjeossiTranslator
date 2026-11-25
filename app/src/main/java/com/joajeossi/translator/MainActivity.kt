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
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var sourceText: TextView
    private lateinit var translatedText: TextView
    private lateinit var startButton: Button
    private lateinit var speech: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var translator: Translator? = null
    private var listening = false
    private var sourceCode = TranslateLanguage.ENGLISH
    private var targetCode = TranslateLanguage.KOREAN
    private var sourceLocale = "en-US"
    private var targetLocale = Locale.KOREAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildScreen()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "이 기기에서 음성인식을 사용할 수 없습니다."
        } else {
            speech = SpeechRecognizer.createSpeechRecognizer(this)
            speech.setRecognitionListener(listener)
        }
        requestAudioPermission()
        configureTranslator()
    }

    private fun buildScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFFF4F7FF.toInt())
        }
        val title = TextView(this).apply {
            text = "🌱 조아저씨 실시간 통역"
            textSize = 25f; gravity = Gravity.CENTER
            setTextColor(0xFF1453B8.toInt())
        }
        status = TextView(this).apply {
            text = "통역 준비 완료"; textSize = 16f; gravity = Gravity.CENTER
            setPadding(8, 24, 8, 18)
        }
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val from = Spinner(this)
        val to = Spinner(this)
        val languages = listOf("영어" to TranslateLanguage.ENGLISH, "일본어" to TranslateLanguage.JAPANESE,
            "중국어" to TranslateLanguage.CHINESE, "한국어" to TranslateLanguage.KOREAN)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
        from.adapter = adapter; to.adapter = adapter
        from.setSelection(0); to.setSelection(3)
        from.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {}
            override fun onItemSelected(p0: AdapterView<*>?, v: View?, position: Int, id: Long) {
                sourceCode = languages[position].second
                sourceLocale = when(sourceCode) {
                    TranslateLanguage.JAPANESE -> "ja-JP"
                    TranslateLanguage.CHINESE -> "zh-CN"
                    TranslateLanguage.KOREAN -> "ko-KR"
                    else -> "en-US"
                }
                configureTranslator()
            }
        }
        to.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {}
            override fun onItemSelected(p0: AdapterView<*>?, v: View?, position: Int, id: Long) {
                targetCode = languages[position].second
                targetLocale = when(targetCode) {
                    TranslateLanguage.JAPANESE -> Locale.JAPANESE
                    TranslateLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
                    TranslateLanguage.ENGLISH -> Locale.US
                    else -> Locale.KOREAN
                }
                configureTranslator()
            }
        }
        langRow.addView(from, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = TextView(this).apply { text = "  →  "; textSize = 20f }
        langRow.addView(arrow)
        langRow.addView(to, LinearLayout.LayoutParams(0, -2, 1f))
        sourceText = makeText("상대방의 말이 여기에 표시됩니다.")
        translatedText = makeText("번역 결과가 여기에 표시됩니다.")
        startButton = Button(this).apply {
            text = "🎙 통역 시작"
            textSize = 20f
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        val hint = TextView(this).apply {
            text = "블루투스 이어폰을 연결한 뒤 통역을 시작하세요.\n화면을 켜 둔 상태에서 사용하는 기본 통역 버전입니다."
            textSize = 13f; setPadding(4, 18, 4, 4)
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(langRow)
        layout.addView(label("인식된 원문"))
        layout.addView(sourceText)
        layout.addView(label("한국어/선택 언어 번역"))
        layout.addView(translatedText)
        layout.addView(startButton)
        layout.addView(hint)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setTextColor(0xFF1453B8.toInt())
        setPadding(0, 22, 0, 6)
    }
    private fun makeText(s: String) = TextView(this).apply {
        text = s; textSize = 19f; setTextColor(0xFF17233D.toInt())
        setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(18, 22, 18, 22)
    }

    private fun configureTranslator() {
        if (sourceCode == targetCode) return
        translator?.close()
        val options = TranslatorOptions.Builder().setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode).build()
        translator = Translation.getClient(options)
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener { status.text = "번역 모델 준비 완료" }
            ?.addOnFailureListener { status.text = "번역 모델 다운로드 실패: 인터넷 연결을 확인하세요." }
    }

    private fun requestAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission(); return
        }
        if (sourceCode == targetCode) {
            status.text = "서로 다른 언어를 선택해 주세요."; return
        }
        listening = true
        startButton.text = "■ 통역 중지"
        status.text = "음성을 듣고 있습니다..."
        listenOnce()
    }

    private fun listenOnce() {
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { speech.startListening(intent) } catch (_: Exception) {
            status.text = "음성인식을 시작하지 못했습니다."; stopListening()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.text = "말씀해 주세요..." }
        override fun onBeginningOfSpeech() { status.text = "음성 인식 중..." }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { status.text = "번역 중..." }
        override fun onError(error: Int) {
            if (listening) sourceText.postDelayed({ listenOnce() }, 350)
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) translate(text)
            if (listening) sourceText.postDelayed({ listenOnce() }, 300)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) sourceText.text = text
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun translate(text: String) {
        sourceText.text = text
        val active = translator
        if (active == null) { translatedText.text = "번역기를 준비하고 있습니다."; return }
        active.translate(text).addOnSuccessListener { result ->
            translatedText.text = result
            tts.language = targetLocale
            tts.speak(result, TextToSpeech.QUEUE_FLUSH, null, "translated")
        }.addOnFailureListener {
            translatedText.text = "번역에 실패했습니다. 인터넷 연결을 확인해 주세요."
        }
    }

    private fun stopListening() {
        listening = false
        if (::speech.isInitialized) speech.stopListening()
        startButton.text = "🎙 통역 시작"
        status.text = "통역이 중지되었습니다."
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts.language = Locale.KOREAN
    }

    override fun onDestroy() {
        stopListening()
        if (::speech.isInitialized) speech.destroy()
        if (::tts.isInitialized) tts.shutdown()
        translator?.close()
        super.onDestroy()
    }
}
