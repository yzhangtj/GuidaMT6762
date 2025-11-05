package com.google.ai.edge.gallery.ingress

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.util.downscaleToMaxDimension
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "IngressProcessor"

class AskImageProcessor(
  private val context: Context,
  private val modelManagerViewModel: ModelManagerViewModel,
) {

  suspend fun process(imageBytes: ByteArray, prompt: String): String =
    withContext(Dispatchers.Default) {
      val bitmap =
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
          ?: throw IllegalArgumentException("Unable to decode image payload")

      val task =
        modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_ASK_IMAGE)
          ?: throw IllegalStateException("Ask Image task not found")
      val model = task.models.firstOrNull()
        ?: throw IllegalStateException("No models available for Ask Image task")

      ensureModelReady(task, model)

      runInference(model, bitmap.downscaleToMaxDimension(512), prompt).also {
        try {
          LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = true,
            supportAudio = model.llmSupportAudio,
          )
        } catch (resetError: Exception) {
          Log.w(TAG, "Failed to reset conversation after ingress inference", resetError)
        }
      }
    }

  private suspend fun ensureModelReady(task: Task, model: Model) {
    if (model.instance != null && !model.initializing) {
      return
    }

    modelManagerViewModel.initializeModel(context, task, model)
    var waited = 0
    while (model.instance == null && waited < 100) {
      delay(100)
      waited++
    }
    if (model.instance == null) {
      throw IllegalStateException("Model failed to initialize in time")
    }
  }

  private suspend fun runInference(model: Model, image: android.graphics.Bitmap, prompt: String): String {
    if (model.instance == null) {
      throw IllegalStateException("Model not initialized")
    }

    val builder = StringBuilder()

    return suspendCoroutine { cont ->
      var completed = false
      LlmChatModelHelper.runInference(
        model = model,
        input = prompt,
        images = listOf(image),
        audioClips = listOf(),
        resultListener = { partialResult, done ->
          if (partialResult.isNotEmpty()) {
            builder.append(partialResult)
          }
          if (done && !completed) {
            completed = true
            val text = builder.toString()
            if (text.startsWith("Error:")) {
              cont.resumeWithException(IllegalStateException(text))
            } else {
              cont.resume(text)
            }
          }
        },
        cleanUpListener = {
          if (!completed) {
            completed = true
            cont.resumeWithException(IllegalStateException("Inference cancelled"))
          }
        },
      )
    }
  }
}

