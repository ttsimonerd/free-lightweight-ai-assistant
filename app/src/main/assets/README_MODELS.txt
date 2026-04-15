========================================================
  Assets folder – Place your model files here
========================================================

PERSON DETECTION MODEL
──────────────────────
File:  person_detect.tflite
Source: TensorFlow Hub or TFLite Model Garden

Recommended models (free, open-source):
  • EfficientDet-Lite0 (COCO) – fast, ~4 MB
    https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1
  • MobileNet SSD v2 (COCO) – well-tested, ~20 MB
    https://www.kaggle.com/models/tensorflow/ssd-mobilenet-v2

Steps:
  1. Download the .tflite file
  2. Rename it to person_detect.tflite
  3. Place it in this folder (app/src/main/assets/)
  4. Uncomment the TFLite initialisation block in PersonDetector.kt
  5. Implement analyzeWithModel() with real inference logic

LOCAL LLM MODEL (optional, future)
────────────────────────────────────
File:  model.gguf  (or any format your JNI library accepts)
Source: Hugging Face (Llama, Phi, Gemma, etc. – quantised GGUF)

Recommended tiny models for Samsung A12 (≤ 1 GB RAM available):
  • Phi-2 (Q4_K_M GGUF) – ~1.6 GB → may be tight on A12
  • TinyLlama 1.1B (Q4_K_M GGUF) – ~700 MB → recommended
    https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF

Steps:
  1. Add llama.cpp Android JNI bindings (ggml-android or llama.cpp prebuilt .so)
  2. Load System.loadLibrary("llama") in LocalAssistant.kt
  3. Implement nativeInit() and nativeInfer() native methods
  4. Call them from generateWithModel() in LocalAssistant.kt
========================================================
