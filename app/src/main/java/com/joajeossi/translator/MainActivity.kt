package com.joajeossi.translator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * 한 화면 동시 통역
 *
 * - 위에서 "나" 언어와 "상대방" 언어를 고른다.
 * - [동시 통역 시작]을 누르면 계속 듣는다. 누가 말하든 두 언어 중 어느 쪽인지 알아서 구분해서
 *   반대 언어로 번역하고, 대화 카드로 쌓고, 소리로 읽어 준다.
 * - 읽어 주는 동안은 마이크를 꺼 둔다 (읽는 소리를 다시 번역하지 않도록). 다 읽으면 다시 듣는다.
 * - [통역 종료]를 누르면 멈춘다.
 *
 * 차례 바꾸기:
 *  휴대폰 음성 인식은 한 번에 한 언어로만 듣는다. 그래서 "누구 차례"인지 정해 두고 그 사람 언어로 듣는다.
 *  - 내가 말해서 번역되면 → 다음은 상대방 차례(상대방 언어로 듣기)
 *  - 상대방이 말해서 번역되면 → 다음은 내 차례(내 언어로 듣기)
 *  - 상대방 차례인데 한동안 아무 말이 없으면 → 다시 내 차례
 *  - 회색 상태 줄을 누르면 차례를 직접 바꿀 수 있다.
 *  그래도 받아 적은 글자가 다른 사람 언어의 문자(한글/한자 등)면 그쪽으로 판단한다.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    /** 지원 언어. speechTag = 음성인식 언어, ttsLocale = 읽어 주기 언어, script = 문자 종류 */
    private data class Lang(
        val label: String, val code: String, val speechTag: String,
        val ttsLocale: Locale, val script: Script
    )

    private enum class Script { HANGUL, KANA, HAN, THAI, LATIN }

    private val languages = listOf(
        Lang("한국어", TranslateLanguage.KOREAN, "ko-KR", Locale.KOREAN, Script.HANGUL),
        Lang("영어", TranslateLanguage.ENGLISH, "en-US", Locale.US, Script.LATIN),
        Lang("일본어", TranslateLanguage.JAPANESE, "ja-JP", Locale.JAPANESE, Script.KANA),
        Lang("중국어", TranslateLanguage.CHINESE, "zh-CN", Locale.SIMPLIFIED_CHINESE, Script.HAN),
        Lang("태국어", TranslateLanguage.THAI, "th-TH", Locale("th", "TH"), Script.THAI),
        Lang("베트남어", TranslateLanguage.VIETNAMESE, "vi-VN", Locale("vi", "VN"), Script.LATIN),
        // 필리핀어: ML Kit 번역은 타갈로그어(tl)로 지원. 음성인식/읽어 주기는 휴대폰에 따라 안 될 수 있음
        Lang("필리핀어(타갈로그)", TranslateLanguage.TAGALOG, "fil-PH", Locale("fil", "PH"), Script.LATIN)
    )

    private enum class Speaker { ME, OTHER }

    private val blue = 0xFF1453B8.toInt()
    private val green = 0xFF0F7B5F.toInt()
    private val red = 0xFFC62828.toInt()

    private lateinit var status: TextView
    private lateinit var log: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var mySpinner: Spinner
    private lateinit var otherSpinner: Spinner

    private var speech: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val languageId = LanguageIdentification.getClient()

    private var myLang = languages[0]      // 기본: 나 = 한국어
    private var otherLang = languages[1]   // 기본: 상대 = 영어
    private var meToOther: Translator? = null
    private var otherToMe: Translator? = null

    /** 동시 통역이 켜져 있는지 */
    private var running = false
    /** 번역문을 읽는 중인지 (이때는 마이크 꺼 둠) */
    private var speaking = false
    /** Android 14 이상에서 음성 인식이 알려 준 언어 (예: "en-US") */
    private var detectedTag: String? = null
    /** 지금 누구 말을 듣는 차례인지 (그 사람 언어로 음성 인식) */
    private var turn = Speaker.ME
    /** 상대방 차례에서 말 없이 지나간 횟수 */
    private var silentCount = 0

    private fun langOf(who: Speaker) = if (who == Speaker.ME) myLang else otherLang

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

    override fun onResume() {
        super.onResume()
        // 앱을 열 때마다 GitHub에 새 버전이 있는지 보고, 있으면 알아서 받아서 설치한다
        AppUpdater.checkForUpdate(this)
    }

    // ───────────────────────── 화면 ─────────────────────────

    // 맨 아래 줄: "버전 1.0.30"  [업데이트 확인]
    private fun buildVersionRow(): LinearLayout {
        val versionText = TextView(this).apply {
            text = "버전 ${AppUpdater.currentVersionName(this@MainActivity)}"
            textSize = 13f; setTextColor(0xFF666666.toInt())
        }
        val checkButton = TextView(this).apply {
            text = "업데이트 확인"
            textSize = 13f; setTextColor(blue)
            background = rounded(0xFFFFFFFF.toInt(), 6, blue)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { AppUpdater.checkForUpdate(this@MainActivity, manual = true) }
        }
        val updateStatus = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF666666.toInt())
            gravity = Gravity.END; setPadding(dp(8), 0, dp(8), 0)
        }
        AppUpdater.onStatus = { msg -> updateStatus.text = msg }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(versionText, LinearLayout.LayoutParams(-2, -2))
            addView(updateStatus, LinearLayout.LayoutParams(0, -2, 1f))
            addView(checkButton, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7FF.toInt())
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(8))
        }

        val title = TextView(this).apply {
            text = "🌱 조아저씨 동시 통역"
            textSize = 21f; gravity = Gravity.CENTER; setTextColor(blue)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(10))
        }

        // 언어 고르기: [나 ▾]  ⇄  [상대방 ▾]
        mySpinner = languageSpinner(0) { pos -> myLang = languages[pos]; configureTranslators() }
        otherSpinner = languageSpinner(1) { pos -> otherLang = languages[pos]; configureTranslators() }
        val swap = TextView(this).apply {
            text = "⇄"; textSize = 24f; setTextColor(blue); gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                val m = mySpinner.selectedItemPosition
                mySpinner.setSelection(otherSpinner.selectedItemPosition)
                otherSpinner.setSelection(m)
            }
        }
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(languageBox("나", mySpinner), LinearLayout.LayoutParams(0, -2, 1f))
            addView(swap, LinearLayout.LayoutParams(-2, -2))
            addView(languageBox("상대방", otherSpinner), LinearLayout.LayoutParams(0, -2, 1f))
        }

        status = TextView(this).apply {
            text = "통역 준비 중..."
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFF444444.toInt())
            background = rounded(0xFFE3E9F7.toInt(), 8)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            // 누르면 차례 바꾸기 (나 ↔ 상대방)
            setOnClickListener { switchTurn() }
        }

        // 대화 카드가 쌓이는 곳
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        log.addView(TextView(this).apply {
            text = "아래 [동시 통역 시작]을 누르고 말씀하세요.\n나와 상대방 누가 말해도 알아서 번역합니다."
            textSize = 15f; setTextColor(0xFF888888.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            tag = "hint"
        })
        logScroll = ScrollView(this).apply { addView(log) }

        startButton = bigButton("🎙 동시 통역 시작", blue) { startInterpreting() }
        stopButton = bigButton("■ 통역 종료", red) { stopInterpreting("통역을 종료했습니다.") }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
            addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        }

        inner.addView(title)
        inner.addView(langRow)
        inner.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        inner.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        inner.addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        inner.addView(buildVersionRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(inner, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        keepClearOfSystemBars(root)
        updateButtons()
    }

    private fun languageSpinner(initial: Int, onChanged: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.label })
        setSelection(initial)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {}
            override fun onItemSelected(p0: AdapterView<*>?, v: View?, position: Int, id: Long) = onChanged(position)
        }
    }

    private fun languageBox(label: String, spinner: Spinner) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xFFFFFFFF.toInt(), 10, 0xFFD5DCEF.toInt())
        setPadding(dp(10), dp(4), dp(4), dp(2))
        addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(0xFF666666.toInt()) })
        addView(spinner)
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
        background = rounded(color, 12)
        setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { onClick() }
    }

    /**
     * Android 15(targetSdk 35)부터는 앱 화면이 상태바/내비게이션바 밑까지 그려진다.
     * 시스템 바와 노치 크기만큼 안쪽 여백을 줘서 버튼이 가리지 않게 한다.
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

    private fun updateButtons() {
        startButton.alpha = if (running) 0.45f else 1f
        stopButton.alpha = if (running) 1f else 0.45f
    }

    /** 대화 카드 하나 추가: 누가 · 어느 언어 → 어느 언어 / 원문(작게) / 번역(크게) */
    private fun addCard(who: Speaker, from: Lang, to: Lang, original: String, translated: String) {
        log.findViewWithTag<View>("hint")?.let { log.removeView(it) }
        val color = if (who == Speaker.ME) blue else green
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFFFFF.toInt(), 12)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val bar = View(this).apply { background = rounded(color, 3) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bar, LinearLayout.LayoutParams(dp(6), -1))
            addView(card, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(TextView(this).apply {
            text = "${if (who == Speaker.ME) "나" else "상대방"} · ${from.label} → ${to.label}"
            textSize = 12f; setTextColor(color); setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply { text = original; textSize = 14f; setTextColor(0xFF666666.toInt()) })
        card.addView(TextView(this).apply {
            text = translated; textSize = 21f; setTextColor(0xFF17233D.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        log.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        while (log.childCount > 60) log.removeViewAt(0)   // 너무 많이 쌓이면 오래된 것부터 지움
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
            if (done == 2 && meToOther === a && !running) status.text = "준비 완료 · ${myLang.label} ↔ ${otherLang.label}"
        }
        val onFail = { _: Exception ->
            if (meToOther === a) status.text = "번역 모델 다운로드 실패: 인터넷 연결을 확인하세요."
        }
        a.downloadModelIfNeeded(conditions).addOnSuccessListener { onReady() }.addOnFailureListener(onFail)
        b.downloadModelIfNeeded(conditions).addOnSuccessListener { onReady() }.addOnFailureListener(onFail)
    }

    // ───────────────────────── 시작 / 종료 ─────────────────────────

    private fun requestAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    private fun startInterpreting() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission(); return
        }
        if (speech == null) { status.text = "이 기기에서 음성인식을 사용할 수 없습니다."; return }
        if (myLang.code == otherLang.code) { status.text = "나와 상대방의 언어를 다르게 골라 주세요."; return }
        running = true
        speaking = false
        turn = Speaker.ME
        silentCount = 0
        updateButtons()
        listenOnce()
    }

    private fun stopInterpreting(message: String) {
        running = false
        speaking = false
        speech?.cancel()
        if (ttsReady) tts.stop()
        updateButtons()
        status.text = message
    }

    // ───────────────────────── 듣기 ─────────────────────────

    private fun turnText() =
        if (turn == Speaker.ME) "🔵 나(${myLang.label}) 차례 · 말씀하세요\n(이 줄을 누르면 상대방 차례로 바뀌어요)"
        else "🟢 상대방(${otherLang.label}) 차례 · 듣는 중\n(이 줄을 누르면 내 차례로 바뀌어요)"

    /** 회색 줄을 누르면 차례를 바꾸고 바로 그 언어로 다시 듣는다 */
    private fun switchTurn() {
        turn = if (turn == Speaker.ME) Speaker.OTHER else Speaker.ME
        silentCount = 0
        if (!running) { status.text = "${if (turn == Speaker.ME) "나" else "상대방"} 차례부터 시작해요. [동시 통역 시작]을 누르세요."; return }
        if (speaking) return
        speech?.cancel()
        listenOnce()
    }

    private fun listenOnce() {
        if (!running || speaking) return
        val recognizer = speech ?: return
        detectedTag = null
        val listenLang = langOf(turn)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 지금 차례인 사람의 언어로 듣는다 (휴대폰 음성 인식은 한 번에 한 언어만 제대로 알아듣는다)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang.speechTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, listenLang.speechTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer.startListening(intent)
            status.text = turnText()
        } catch (_: Exception) {
            stopInterpreting("음성인식을 시작하지 못했습니다.")
        }
    }

    private fun resumeListening(delayMs: Long) {
        runOnUiThread {
            speaking = false
            if (running) status.postDelayed({ listenOnce() }, delayMs)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (running) status.text = "번역 중..." }
        override fun onError(error: Int) {
            if (!running || speaking) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopInterpreting("마이크 권한을 허용해 주세요."); requestAudioPermission()
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    stopInterpreting("이 휴대폰은 ${langOf(turn).label} 음성인식을 지원하지 않습니다. " +
                        "설정 > 일반 > 음성 입력에서 ${langOf(turn).label}를 추가해 주세요.")
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // 상대방 차례인데 한동안 아무 말이 없으면 다시 내 차례로
                    if (turn == Speaker.OTHER && ++silentCount >= SILENT_LIMIT) {
                        turn = Speaker.ME; silentCount = 0
                    }
                    status.postDelayed({ listenOnce() }, 300)
                }
                // 그 밖의 경우는 조용히 다시 듣는다
                else -> status.postDelayed({ listenOnce() }, 300)
            }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!running) return
            if (text.isNullOrBlank()) { status.postDelayed({ listenOnce() }, 300); return }
            silentCount = 0
            decideSpeaker(text) { who ->
                // 한 사람이 말하고 나면 다음은 다른 사람 차례
                turn = if (who == Speaker.ME) Speaker.OTHER else Speaker.ME
                translate(who, text)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (running && !text.isNullOrBlank()) status.text = "🔴 $text"
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        // Android 14 이상에서만 불림: 음성 인식이 감지한 언어
        override fun onLanguageDetection(results: Bundle) {
            detectedTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
        }
    }

    // ───────────────────────── 누가 말했는지 구분 ─────────────────────────

    private fun decideSpeaker(text: String, done: (Speaker) -> Unit) {
        // 기본은 "지금 차례인 사람". 받아 적은 글자가 분명히 다른 쪽이면 그쪽으로.
        // 1) Android 14 이상: 음성 인식이 알려 준 언어
        detectedTag?.let { tag ->
            val prefix = tag.substringBefore('-').lowercase()
            if (prefix == otherLang.speechTag.substringBefore('-')) { done(Speaker.OTHER); return }
            if (prefix == myLang.speechTag.substringBefore('-')) { done(Speaker.ME); return }
        }
        // 2) 문자 종류로 구분 (한글/가나/한자/태국 문자/로마자)
        if (myLang.script != otherLang.script) { done(scriptGuess(text) ?: turn); return }
        // 3) 둘 다 로마자 언어 등: ML Kit 언어 감지, 모르면 차례대로
        languageId.identifyLanguage(text)
            .addOnSuccessListener { code ->
                done(when (code) { otherLang.code -> Speaker.OTHER; myLang.code -> Speaker.ME; else -> turn })
            }
            .addOnFailureListener { done(turn) }
    }

    private fun scriptOf(c: Char): Script? = when {
        c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏' -> Script.HANGUL
        c in '぀'..'ヿ' -> Script.KANA
        c in '一'..'鿿' -> Script.HAN
        c in '฀'..'๿' -> Script.THAI
        c.isLetter() && c.code < 0x2000 -> Script.LATIN
        else -> null
    }

    private fun scriptGuess(text: String): Speaker? {
        if (myLang.script == otherLang.script) return null
        val counts = text.mapNotNull { scriptOf(it) }.groupingBy { it }.eachCount()
        fun score(l: Lang): Int = when (l.script) {
            // 일본어는 한자도 섞어 쓰니 가나+한자를 함께 센다
            Script.KANA -> (counts[Script.KANA] ?: 0) + (counts[Script.HAN] ?: 0)
            else -> counts[l.script] ?: 0
        }
        val mine = score(myLang)
        val theirs = score(otherLang)
        if (mine == theirs) return null
        return if (mine > theirs) Speaker.ME else Speaker.OTHER
    }

    // ───────────────────────── 번역 + 읽어 주기 ─────────────────────────

    private fun translate(who: Speaker, text: String) {
        val from = if (who == Speaker.ME) myLang else otherLang
        val to = if (who == Speaker.ME) otherLang else myLang
        val translator = if (who == Speaker.ME) meToOther else otherToMe
        if (translator == null) {
            status.text = "번역기를 준비하고 있습니다."
            resumeListening(300); return
        }
        translator.translate(text).addOnSuccessListener { result ->
            addCard(who, from, to, text, result)
            speak(result, to)
        }.addOnFailureListener {
            status.text = "번역에 실패했습니다. 모델 다운로드가 끝났는지 확인해 주세요."
            resumeListening(300)
        }
    }

    /** 마이크를 끄고 번역문을 읽는다. 다 읽으면 다시 듣는다. */
    private fun speak(text: String, lang: Lang) {
        if (!running) return
        if (!ttsReady) { resumeListening(300); return }
        val r = tts.setLanguage(lang.ttsLocale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = "이 휴대폰은 ${lang.label} 읽어 주기를 지원하지 않습니다."
            resumeListening(800); return
        }
        speaking = true
        speech?.cancel()
        status.text = "🔈 읽는 중... (마이크 꺼짐)"
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.ERROR) resumeListening(300)
    }

    override fun onInit(statusCode: Int) {
        if (statusCode != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) resumeListening(RESUME_DELAY_MS)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) resumeListening(RESUME_DELAY_MS)
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == UTTERANCE_ID) resumeListening(RESUME_DELAY_MS)
            }
        })
    }

    override fun onDestroy() {
        running = false
        speech?.destroy()
        if (::tts.isInitialized) tts.shutdown()
        meToOther?.close(); otherToMe?.close()
        languageId.close()
        super.onDestroy()
    }

    companion object {
        private const val UTTERANCE_ID = "translated"
        /** 다 읽은 뒤 스피커 잔향이 잡히지 않도록 잠깐 기다렸다가 다시 듣는다 */
        private const val RESUME_DELAY_MS = 400L
        /** 상대방 차례에서 이만큼(약 5초씩) 조용하면 다시 내 차례로 */
        private const val SILENT_LIMIT = 3
    }
}
