//
// Created by Esteban Uri on 17/01/2025.
//
#include <jni.h>
#include <android/log.h>
#include <cpu-features.h>
#include <stdint.h>

#define LOG_TAG "ImageProcessing"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Check for NEON support
//bool isNeonSupported() {
//    return (android_getCpuFeatures() & ANDROID_CPU_ARM_FEATURE_NEON) != 0;
//}
//
//// NEON optimized ARGB to RGB function
//void argb_to_rgb_neon(uint32_t* src, uint8_t* dest, int width, int height) {
//    // NEON optimized conversion code here
//    // This example doesn't contain actual NEON instructions but you can add NEON-specific operations.
//    // Assume each pixel is 32-bit ARGB, and the result is stored in RGB.
//
//    for (int i = 0; i < width * height; i++) {
//        uint32_t pixelValue = src[i];
//        uint8_t r = (pixelValue >> 16) & 0xFF;
//        uint8_t g = (pixelValue >> 8) & 0xFF;
//        uint8_t b = pixelValue & 0xFF;
//
//        dest[i * 3] = r;
//        dest[i * 3 + 1] = g;
//        dest[i * 3 + 2] = b;
//    }
//}



extern "C"
JNIEXPORT void JNICALL
Java_com_ultralytics_yolo_ImageProcessing_argb2yolo(
    JNIEnv *env,
    jobject thiz,
    jintArray srcArray,
    jobject destBuffer,
    jint width,
    jint height
) {

    // Acquire destination DirectByteBuffer address first (allowed outside critical)
    float* dest = (float*) env->GetDirectBufferAddress(destBuffer);
    if (dest == NULL) {
        // Invalid buffer, nothing to do
        return;
    }
    // Now get direct access to the source array without copying
    jint* src = (jint*) env->GetPrimitiveArrayCritical(srcArray, NULL);

    // Convert ARGB to normalized RGB floats using pointer arithmetic
    const int numPixels = width * height;
    static const float inv255 = 1.0f / 255.0f;
    jint* srcPtr = src;
    float* destPtr = dest;
    for (int i = 0; i < numPixels; ++i, ++srcPtr) {
        uint32_t pixel = static_cast<uint32_t>(*srcPtr);
        *destPtr++ = ((pixel >> 16) & 0xFF) * inv255;
        *destPtr++ = ((pixel >> 8) & 0xFF) * inv255;
        *destPtr++ = (pixel & 0xFF) * inv255;
    }
    // Release without copying back since src not modified
    env->ReleasePrimitiveArrayCritical(srcArray, src, JNI_ABORT);
}