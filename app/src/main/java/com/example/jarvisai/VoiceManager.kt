package com.example.jarvisai

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class VoiceState(
    val status: String = "Initializing...",
    val transcript: String = "",
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false
)

class VoiceManager(private val context: Context) {

    private val _uiState = MutableStateFlow(VoiceState())
    val uiState: StateFlow<VoiceState> = _uiState.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // STT Assets (Whisper Tiny) - MATCH THESE NAMES WITH ASSETS
    private val sttEncoder = "tiny-encoder.int8.onnx"
    private val sttDecoder = "tiny-decoder.int8.onnx"
    private val sttTokens = "tokens.txt"

    // TTS Assets (Pratham) - MATCH THESE NAMES WITH ASSETS
    private val ttsModel = "model-pratham.onnx"      // Rename your file to this
    private val ttsTokens = "tokens-pratham.txt"     // Rename your file to this
    private val espeakFolder = "espeak-ng-data"      // Ensure folder name matches

    init {
        scope.launch { initModels() }
    }

    private suspend fun initModels() {
        try {
            val dataDir = context.filesDir.absolutePath
            
            // 1. Copy Assets to Local Storage (Phone Memory)
            copyAssetToFile(context, sttEncoder, "$dataDir/$sttEncoder")
            copyAssetToFile(context, sttDecoder, "$dataDir/$sttDecoder")
            copyAssetToFile(context, sttTokens, "$dataDir/$sttTokens")
            copyAssetToFile(context, ttsModel, "$dataDir/$ttsModel")
            copyAssetToFile(context, ttsTokens, "$dataDir/$ttsTokens")
            copyAssetFolder(context.assets, espeakFolder, "$dataDir/$espeakFolder")

            // 2. Init STT (Whisper)
            val recConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$dataDir/$sttEncoder",
                        decoder = "$dataDir/$sttDecoder",
                        language = "hi", // Hindi/Hinglish
                        task = "transcribe",
                        tailPaddings = -1
                    ),
                    tokens = "$dataDir/$sttTokens",
                    numThreads = 1,
                    debug = true,
                    modelType = "whisper",
                )
            )
            recognizer = OfflineRecognizer(recConfig)

            // 3. Init TTS (Pratham)
            val ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$dataDir/$ttsModel",
                        tokens = "$dataDir/$ttsTokens",
                        dataDir = "$dataDir/$espeakFolder",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f 
                    ),
                    numThreads = 1,
                    debug = true,
                    provider = "cpu",
                    type = "vits"
                )
            )
            tts = OfflineTts(ttsConfig)

            updateStatus("Jarvis Ready", false, false)

        } catch (e: Exception) {
            Log.e("VoiceManager", "Init Error: Check Asset Names!", e)
            updateStatus("Asset Error: Check Logs", false, false)
        }
    }

    fun toggleListening() {
        if (_uiState.value.isListening) stopListening() else startListening()
    }

    private fun startListening() {
        if (recognizer == null) return
        recordingJob = scope.launch {
            updateStatus("Listening...", true, false)
            try {
                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)
                val stream = recognizer!!.createStream()

                while (isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val samples = FloatArray(read) { buffer[it] / 32768f }
                        stream.acceptWaveform(samples, sampleRate)
                        if (recognizer!!.isReady(stream)) {
                            recognizer!!.decode(stream)
                            val text = recognizer!!.getResult(stream).text
                            if (text.isNotEmpty()) _uiState.value = _uiState.value.copy(transcript = text)
                        }
                    }
                }
                audioRecord.stop()
                stream.release()
            } catch (e: Exception) {
                updateStatus("Mic Error", false, false)
            }
        }
    }

    private fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
        updateStatus("Processing...", false, false)
        
        val text = _uiState.value.transcript
        if (text.isNotEmpty()) {
            speak(text) 
        } else {
            updateStatus("Ready", false, false)
        }
    }

    private fun speak(text: String) {
        if (tts == null) return
        scope.launch {
            updateStatus("Speaking...", false, true)
            try {
                val audio = tts!!.generate(text, 0, 1.0f)
                if (audio != null) {
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(audio.samples.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    val samplesShort = ShortArray(audio.samples.size) { (audio.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                    track.write(samplesShort, 0, samplesShort.size)
                    track.play()
                    
                    kotlinx.coroutines.delay((samplesShort.size.toDouble() / audio.sampleRate * 1000).toLong())
                }
            } catch (e: Exception) {
                Log.e("VoiceManager", "TTS Error", e)
            }
            updateStatus("Ready", false, false)
        }
    }

    private fun updateStatus(status: String, isListening: Boolean, isSpeaking: Boolean) {
        _uiState.value = _uiState.value.copy(status = status, isListening = isListening, isSpeaking = isSpeaking)
    }

    private fun copyAssetToFile(context: Context, assetName: String, path: String) {
        val file = File(path)
        if (!file.exists()) {
            context.assets.open(assetName).use { `in` -> FileOutputStream(file).use { out -> `in`.copyTo(out) } }
        }
    }

    private fun copyAssetFolder(am: AssetManager, from: String, to: String) {
        File(to).mkdirs()
        am.list(from)?.forEach { f ->
            val subFrom = "$from/$f"
            val subTo = "$to/$f"
            if (am.list(subFrom)?.isNotEmpty() == true) copyAssetFolder(am, subFrom, subTo)
            else copyAssetToFile(context, subFrom, subTo)
        }
    }
}