package com.google.ai.edge.gallery.util

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

fun Bitmap.downscaleToMaxDimension(maxDimension: Int): Bitmap {
  val currentMax = max(width, height)
  if (currentMax <= maxDimension) {
    return this
  }
  val scale = maxDimension.toFloat() / currentMax.toFloat()
  val newWidth = (width * scale).roundToInt().coerceAtLeast(1)
  val newHeight = (height * scale).roundToInt().coerceAtLeast(1)
  return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
}

