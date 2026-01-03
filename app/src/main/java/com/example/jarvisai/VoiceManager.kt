package com.example.jarvisai

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// UI State Definition
data class VoiceState(
    val status: String = "Initializing...", // Initializing, Listening, Processing, Speaking
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

    // STT Assets (Whisper)
    private val sttEncoder = "tiny-encoder.int8.onnx"
    private val sttDecoder = "tiny-decoder.int8.onnx"
    private val sttTokens = "tokens.txt"

    // TTS Assets (VITS Pratham)
    private val ttsModel = "model-pratham.onnx"
    private val ttsTokens = "tokens-pratham.txt"

    init {
        scope.launch {
            initModels()
        }
    }

    private suspend fun initModels() {
        try {
            // 1. Copy Assets to Internal Storage
            // Native C++ libraries cannot read directly from APK Assets, they need physical paths.
            val assetMap = mapOf(
                sttEncoder to sttEncoder,
                sttDecoder to sttDecoder,
                sttTokens to sttTokens,
                ttsModel to ttsModel,
                ttsTokens to ttsTokens
            )

            assetMap.forEach { (assetName, fileName) ->
                copyAssetToFile(context, assetName, fileName)
            }

            val dataDir = context.filesDir.absolutePath

            // 2. Initialize STT (Whisper)
            val recConfig = OfflineRecognizerConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(), // Empty
                    paraformer = com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(), // Empty
                    nemoCtc = com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(), // Empty
                    whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                        encoder = "$dataDir/$sttEncoder",
                        decoder = "$dataDir/$sttDecoder",
                        language = "hi", // Auto-detect or Hindi
                        task = "transcribe",
                        tailPaddings = -1 // Default
                    ),
                    tokens = "$dataDir/$sttTokens",
                    numThreads = 2,
                    debug = false,
                    modelType = "whisper",
                )
            )

            recognizer = OfflineRecognizer(recConfig)
            Log.d("VoiceManager", "STT Initialized")

            // 3. Initialize TTS (VITS)
            val ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$dataDir/$ttsModel",
                        tokens = "$dataDir/$ttsTokens",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    type = "vits"
                )
            )

            tts = OfflineTts(ttsConfig)
            Log.d("VoiceManager", "TTS Initialized")

            updateStatus("Ready", false, false)

        } catch (e: Exception) {
            Log.e("VoiceManager", "Error loading models", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Missing Model: ${e.message}", Toast.LENGTH_LONG).show()
                updateStatus("Error: Check Logs", false, false)
            }
        }
    }

    fun startListening() {
        if (recognizer == null) {
            Log.e("VoiceManager", "Recognizer not initialized")
            return
        }
        if (recordingJob?.isActive == true) return

        updateStatus("Listening...", isListening = true, isSpeaking = false)
        
        recordingJob = scope.launch {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            try {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)
                val stream = recognizer!!.createStream()

                while (isActive) {
                    val readResult = audioRecord.read(buffer, 0, buffer.size)
                    if (readResult > 0) {
                        val data = FloatArray(readResult) { buffer[it] / 32768f }
                        stream.acceptWaveform(data, sampleRate)

                        if (recognizer!!.isReady(stream)) {
                            recognizer!!.decode(stream)
                            val result = recognizer!!.getResult(stream)
                            
                            // Live update
                            if (result.text.isNotEmpty()) {
                                _uiState.value = _uiState.value.copy(transcript = result.text)
                                
                                // Simple keyword detection for "Stop" or end of sentence logic
                                // For this demo, we just print live.
                            }
                        }
                    }
                }
                
                audioRecord.stop()
                audioRecord.release()
                stream.release()

            } catch (e: SecurityException) {
                updateStatus("Permission Denied", false, false)
            } catch (e: Exception) {
                Log.e("VoiceManager", "Recording error", e)
            }
        }
    }

    fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
        updateStatus("Processing...", isListening = false, isSpeaking = false)
        
        // Example: Echo back what was heard
        val currentText = _uiState.value.transcript
        if (currentText.isNotEmpty()) {
            speak(currentText)
        } else {
            updateStatus("Idle", false, false)
        }
    }

    fun speak(text: String) {
        if (tts == null) return
        
        scope.launch {
            updateStatus("Speaking...", isListening = false, isSpeaking = true)
            
            try {
                val audioData = tts!!.generate(text, 0, 1.0f)
                if (audioData != null) {
                    playAudio(audioData.samples, audioData.sampleRate)
                }
            } catch (e: Exception) {
                Log.e("VoiceManager", "TTS Error", e)
            }
            
            updateStatus("Idle", false, false)
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()

        // Convert Float samples to PCM 16bit Short
        val shortBuffer = ShortArray(samples.size)
        for (i in samples.indices) {
            var s = samples[i]
            if (s > 1.0f) s = 1.0f
            if (s < -1.0f) s = -1.0f
            shortBuffer[i] = (s * 32767).toInt().toShort()
        }

        audioTrack.write(shortBuffer, 0, shortBuffer.size)
        audioTrack.stop()
        audioTrack.release()
    }

    private fun updateStatus(status: String, isListening: Boolean, isSpeaking: Boolean) {
        _uiState.value = _uiState.value.copy(
            status = status,
            isListening = isListening,
            isSpeaking = isSpeaking
        )
    }

    // --- Helper: Asset Extraction ---
    @Throws(IOException::class)
    private fun copyAssetToFile(context: Context, assetName: String, fileName: String) {
        val file = File(context.filesDir, fileName)
        if (file.exists()) return // Don't copy if already exists

        try {
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to copy asset: $assetName")
            throw e // Rethrow to be caught by initModels
        }
    }
}