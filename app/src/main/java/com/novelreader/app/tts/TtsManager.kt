package com.novelreader.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val onChapterFinished: (() -> Unit)? = null,
    private val onParagraphStarted: ((Int) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isInitialized by mutableStateOf(false)
        private set

    var isSpeaking by mutableStateOf(false)
        private set

    var currentParagraphIndex by mutableStateOf(-1)
        private set

    private var currentParagraphs: List<String> = emptyList()
    private var pendingParagraphs: List<String>? = null
    private var pendingStartIndex: Int = 0

    init {
        // 取得使用者系統設定中指定的預設 TTS 引擎（例如 Google 語音服務或三星 TTS）
        val defaultEngine = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_SYNTH
            )
        } catch (e: Exception) {
            null
        }

        tts = if (!defaultEngine.isNullOrBlank()) {
            TextToSpeech(context.applicationContext, this, defaultEngine)
        } else {
            TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                // 不執行任何 setLanguage() 或 setVoice()，完全繼承使用者在系統設定中的語音與發音人！
                applyVoiceSettings()

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        if (utteranceId != null && utteranceId.startsWith("PARA_")) {
                            val idx = utteranceId.removePrefix("PARA_").toIntOrNull()
                            if (idx != null) {
                                currentParagraphIndex = idx
                                onParagraphStarted?.invoke(idx)
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null && utteranceId.startsWith("PARA_")) {
                            val idx = utteranceId.removePrefix("PARA_").toIntOrNull()
                            if (idx != null && idx == currentParagraphs.size - 1) {
                                isSpeaking = false
                                currentParagraphIndex = -1
                                onChapterFinished?.invoke()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        currentParagraphIndex = -1
                        onError?.invoke("語音朗讀發生錯誤，請檢查系統語音引擎設定。")
                    }
                })
                isInitialized = true
                applyVoiceSettings()

                // 若初始化完成前有待播放的內容，立即觸發播放
                pendingParagraphs?.let {
                    val list = it
                    val sIdx = pendingStartIndex
                    pendingParagraphs = null
                    speakParagraphs(list, sIdx)
                }
            }
        } else {
            onError?.invoke("無法初始化語音引擎 (代碼: $status)")
        }
    }

    var speechRateMultiplier by mutableStateOf(1.0f)
        private set

    fun updateSpeechRate(rate: Float) {
        speechRateMultiplier = rate
        applyVoiceSettings()
        if (isSpeaking && currentParagraphs.isNotEmpty()) {
            val resumeIdx = if (currentParagraphIndex >= 0) currentParagraphIndex else 0
            speakParagraphs(currentParagraphs, resumeIdx)
        }
    }

    fun openTtsSettings() {
        try {
            val intent = android.content.Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                onError?.invoke("無法開啟系統語音設定頁面")
            }
        }
    }

    private fun applyVoiceSettings() {
        val engine = tts ?: return
        if (!isInitialized) return

        // 嚴格採用系統預設語音與音色，僅調整使用者自訂的語速倍率
        engine.setSpeechRate(1.0f * speechRateMultiplier)
    }

    fun speakParagraphs(paragraphs: List<String>, startIndex: Int = 0) {
        currentParagraphs = paragraphs
        if (!isInitialized) {
            pendingParagraphs = paragraphs
            pendingStartIndex = startIndex
            return
        }
        applyVoiceSettings()
        stop()

        if (paragraphs.isEmpty() || startIndex !in paragraphs.indices) return

        isSpeaking = true
        currentParagraphIndex = startIndex

        // 從 startIndex 開始依序加入 TTS 隊列
        var queueMode = TextToSpeech.QUEUE_FLUSH
        for (i in startIndex until paragraphs.size) {
            val text = paragraphs[i].trim()
            if (text.isBlank()) continue
            tts?.speak(text, queueMode, null, "PARA_$i")
            queueMode = TextToSpeech.QUEUE_ADD
        }
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isSpeaking = false
        currentParagraphIndex = -1
        isInitialized = false
    }
}
