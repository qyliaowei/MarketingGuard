package com.example.marketingguard

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 本地离线 OCR（ML Kit 中文模型，模型已随 APK 打包，识别过程不联网）
 * 用于第二级检测：当无障碍节点取不到足够文本时（如纯画面视频、字幕烧录在视频里），
 * 截屏后识别屏幕文字再送入规则引擎。
 */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别成功回调文本，失败回调空串（调用方需保证在主线程使用回调） */
    fun recognize(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { onResult(it.text) }
            .addOnFailureListener { onResult("") }
    }
}
