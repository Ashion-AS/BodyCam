#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <vector>

#include "renderer.h"

#define LOG_TAG "BodyCamJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
JavaVM* gVm = nullptr;

GlRenderer* toRenderer(jlong handle) { return reinterpret_cast<GlRenderer*>(handle); }
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jint videoWidth, jint videoHeight) {
    if (!gVm) return 0;
    auto* renderer = new GlRenderer(gVm, videoWidth, videoHeight);
    if (!renderer->start()) {
        LOGE("renderer start failed");
        delete renderer;
        return 0;
    }
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeCameraTexture(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle) {
    GlRenderer* r = toRenderer(handle);
    return r ? r->cameraTexture() : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeAttachSurfaceTexture(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong handle, jobject surfaceTexture) {
    GlRenderer* r = toRenderer(handle);
    if (!r || !surfaceTexture) return JNI_FALSE;
    jobject global = env->NewGlobalRef(surfaceTexture);
    if (!global) return JNI_FALSE;
    if (!r->attachSurfaceTexture(global)) {
        env->DeleteGlobalRef(global);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetPreviewWindow(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle, jobject surface,
                                                               jint viewWidth, jint viewHeight) {
    GlRenderer* r = toRenderer(handle);
    if (!r) return JNI_FALSE;
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!r->setPreviewWindow(window, viewWidth, viewHeight)) {
        // 极端情况下命令未能投递，这里宁可泄漏一个引用也不重复释放
        LOGW("setPreviewWindow failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeUpdatePreviewSize(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                jlong handle, jint viewWidth,
                                                                jint viewHeight) {
    if (GlRenderer* r = toRenderer(handle)) r->updatePreviewSize(viewWidth, viewHeight);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetPreviewVisible(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                jlong handle, jboolean visible) {
    if (GlRenderer* r = toRenderer(handle)) r->setPreviewVisible(visible == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetVideoSize(JNIEnv* /*env*/, jobject /*thiz*/,
                                                           jlong handle, jint width, jint height) {
    if (GlRenderer* r = toRenderer(handle)) r->setVideoSize(width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetEncoderWindow(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle, jobject surface) {
    GlRenderer* r = toRenderer(handle);
    if (!r || !surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    if (!r->setEncoderWindow(window)) {
        LOGW("setEncoderWindow failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeClearEncoderWindow(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                 jlong handle) {
    if (GlRenderer* r = toRenderer(handle)) r->clearEncoderWindow();
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetPreRecordSurface(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong handle, jobject surface) {
    GlRenderer* r = toRenderer(handle);
    if (!r) return;
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    r->setPreRecordWindow(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetRecording(JNIEnv* /*env*/, jobject /*thiz*/,
                                                           jlong handle, jboolean recording) {
    if (GlRenderer* r = toRenderer(handle)) r->setRecording(recording == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetWatermark(JNIEnv* env, jobject /*thiz*/,
                                                           jlong handle, jobject bitmap,
                                                           jfloat leftFrac, jfloat bottomFrac,
                                                           jfloat widthFrac) {
    GlRenderer* r = toRenderer(handle);
    if (!r || !bitmap) return;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) return;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;

    // 逐行拷贝，兼容 stride != width * 4 的位图
    const size_t rowBytes = static_cast<size_t>(info.width) * 4;
    std::vector<uint8_t> data(rowBytes * info.height);
    for (uint32_t y = 0; y < info.height; ++y) {
        std::memcpy(data.data() + y * rowBytes,
                    reinterpret_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride,
                    rowBytes);
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    r->setWatermarkPixels(std::move(data), static_cast<int>(info.width),
                          static_cast<int>(info.height), leftFrac, bottomFrac, widthFrac);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeSetRotation(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle, jfloat degrees) {
    if (GlRenderer* r = toRenderer(handle)) r->setRotation(degrees);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeOnFrameAvailable(JNIEnv* /*env*/, jobject /*thiz*/,
                                                               jlong handle) {
    if (GlRenderer* r = toRenderer(handle)) r->onFrameAvailable();
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsh_bodycam_gl_WatermarkEngine_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/,
                                                      jlong handle) {
    GlRenderer* r = toRenderer(handle);
    if (!r) return;
    r->release();
    delete r;
}
