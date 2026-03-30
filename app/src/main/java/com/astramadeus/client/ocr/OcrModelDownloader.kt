package com.astramadeus.client.ocr

/**
 * Downloads PaddleOCR ONNX model files from ModelScope CDN.
 * Models are stored in `filesDir/ocr_models/`.
 */
object OcrModelDownloader : BaseModelDownloader() {

    override val tag = "OcrModelDownloader"
    override val modelDirName = "ocr_models"
    override val readyMarker = ".ready"
    override val connectTimeoutMs = 15_000
    override val readTimeoutMs = 60_000
    override val estimatedTotalSizeMb = 15

    private const val BASE_URL = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.7.0/onnx/PP-OCRv4"

    override val modelFiles: Map<String, String> = linkedMapOf(
        "ch_PP-OCRv4_det_infer.onnx" to "$BASE_URL/det/ch_PP-OCRv4_det_infer.onnx",
        "ch_PP-OCRv4_rec_infer.onnx" to "$BASE_URL/rec/ch_PP-OCRv4_rec_infer.onnx",
        "ch_ppocr_mobile_v2.0_cls_infer.onnx" to "$BASE_URL/cls/ch_ppocr_mobile_v2.0_cls_infer.onnx",
        "ppocr_keys_v1.txt" to "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt",
    )
}
