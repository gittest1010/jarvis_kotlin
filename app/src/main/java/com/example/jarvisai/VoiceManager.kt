
package com.example.jarvisai

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceManager(private val context: Context) {
    private var onlineRecognizer: OnlineRecognizer? = null
    private var offlineTts: OfflineTts? = null

    companion object {
        private const val TAG = "JarvisVoiceManager"
    }

    suspend fun initModels() = withContext(Dispatchers.IO) {
        try {
            initSTT()
            initTTS()
        } catch (e: Exception) {
            handleError("Model Loading Failed: ${e.message}", e)
        }
    }

    private fun initSTT() {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                whisper = OnlineWhisperLayers(
                    encoder = getAssetPath("tiny-encoder.int8.onnx"),
                    decoder = getAssetPath("tiny-decoder.int8.onnx"),
                    tokens = getAssetPath("tokens")
                ),
                modelType = "whisper",
                numThreads = 4,
                debug = true
            )
        )
        onlineRecognizer = OnlineRecognizer(context.assets, config)
        Log.d(TAG, "STT (Whisper) Initialized")
    }

    private fun initTTS() {
        val vitsConfig = OfflineTtsVitsModelConfig(
            model = getAssetPath("model-pratham.onnx"),
            tokens = getAssetPath("tokens-pratham"),
            dataDir = getAssetPath("espeak-ng-data"),
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f
        )
        val ttsConfig = OfflineTtsConfig(
            modelConfig = OfflineTtsModelConfig(vits = vitsConfig),
            ruleFsts = "",
            maxNumSentences = 1
        )
        offlineTts = OfflineTts(context.assets, ttsConfig)
        Log.d(TAG, "TTS (VITS) Initialized")
    }

    private fun getAssetPath(filename: String): String {
        return try {
            context.assets.open(filename).use { } 
            filename
        } catch (e: Exception) {
            try {
                val list = context.assets.list(filename)
                if (list != null && list.isNotEmpty()) return filename
                throw Exception("Asset not found")
            } catch (e2: Exception) {
                Log.e(TAG, "Asset missing: $filename")
                ""
            }
        }
    }

    private suspend fun handleError(msg: String, e: Exception) {
        Log.e(TAG, msg, e)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun release() {
        onlineRecognizer?.release()
        offlineTts?.release()
    }
}