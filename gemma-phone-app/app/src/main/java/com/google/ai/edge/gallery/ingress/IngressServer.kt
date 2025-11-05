package com.google.ai.edge.gallery.ingress

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class IngressServer(private val processor: AskImageProcessor) : NanoHTTPD(PORT) {

  private val started = AtomicBoolean(false)

  fun startServer() {
    if (started.compareAndSet(false, true)) {
      start(SOCKET_READ_TIMEOUT, false)
      Log.i(TAG, "Ingress server started on port $PORT")
    }
  }

  fun stopServer() {
    if (started.compareAndSet(true, false)) {
      stop()
      Log.i(TAG, "Ingress server stopped")
    }
  }

  override fun serve(session: IHTTPSession): Response {
    return try {
      if (session.method != Method.POST || session.uri != "/ingress") {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON, jsonError("Not found"))
      }

      val files = HashMap<String, String>()
      session.parseBody(files)

      val prompt = session.parameters["prompt"]?.firstOrNull()?.trim().orEmpty()
      val imageTempPath = files["image"] ?: files.values.firstOrNull { it.endsWith("-image") }

      if (prompt.isBlank()) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, jsonError("Missing prompt"))
      }

      if (imageTempPath == null) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, jsonError("Missing image"))
      }

      val imageBytes = File(imageTempPath).takeIf { it.exists() }?.readBytes()
      if (imageBytes == null) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, jsonError("Invalid image payload"))
      }

      val result =
        runBlocking { runCatching { processor.process(imageBytes, prompt) } }

      if (result.isSuccess) {
        val payload = JSONObject().apply { put("text", result.getOrNull()) }
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, payload.toString())
      } else {
        val errorMessage = result.exceptionOrNull()?.message ?: "Processing failed"
        Log.e(TAG, "Ingress processing error", result.exceptionOrNull())
        newFixedLengthResponse(
          Response.Status.INTERNAL_ERROR,
          MIME_JSON,
          jsonError(errorMessage),
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Ingress server exception", e)
      newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        MIME_JSON,
        jsonError(e.message ?: "Unexpected error"),
      )
    }
  }

  private fun jsonError(message: String): String {
    return JSONObject().apply { put("error", message) }.toString()
  }

  companion object {
    private const val TAG = "IngressServer"
    private const val PORT = 8080
    private const val MIME_JSON = "application/json"
  }
}

