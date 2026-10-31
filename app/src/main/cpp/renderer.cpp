#include "renderer.h"
#include "shaders.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>

#define LOG_TAG "BodyCamGL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

const GLfloat kQuad[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
const GLfloat kTexCoord[] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};
// 位图第 0 行是图像顶部，而 GL 的 v=0 在底部，所以水印贴图要翻转 v
const GLfloat kTexCoordFlipY[] = {0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f};

int64_t nowNanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

/** 列主序 4x4 矩阵相乘：out = a * b */
void multiply4(const float a[16], const float b[16], float out[16]) {
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.f;
            for (int k = 0; k < 4; ++k) sum += a[k * 4 + row] * b[col * 4 + k];
            out[col * 4 + row] = sum;
        }
    }
}

/**
 * 纹理坐标的旋转矩阵（绕 (0.5,0.5)）。
 *
 * 方向说明：相机 SurfaceTexture 的变换矩阵自带一次 Y 翻转，
 * 旋转是乘在它左边的，视觉方向会被这次翻转反号。
 * 所以这里「逻辑 90（顺时针）」在 UV 空间要写成逆时针的采样式，
 * 屏幕上看才是顺时针，才能和系统相机 / 我们自己的 JPEG 输出对齐。
 *
 *   逻辑 90  -> (u,v) -> (v, 1-u)
 *   逻辑 180 -> (u,v) -> (1-u, 1-v)
 *   逻辑 270 -> (u,v) -> (1-v, u)
 *
 * 几何顶点始终铺满视口，所以旋转后不会因为视口长宽比不同而被拉扁。
 */
void rotationMatrix2d(float deg, float m[16]) {
    for (int i = 0; i < 16; ++i) m[i] = 0.f;
    m[0] = m[5] = m[10] = m[15] = 1.f;

    int d = static_cast<int>(deg) % 360;
    if (d < 0) d += 360;

    if (d == 90) {
        m[0] = 0.f;  m[1] = -1.f;  // col0
        m[4] = 1.f;  m[5] = 0.f;   // col1
        m[12] = 0.f; m[13] = 1.f;  // col3
    } else if (d == 180) {
        m[0] = -1.f; m[5] = -1.f;
        m[12] = 1.f; m[13] = 1.f;
    } else if (d == 270) {
        m[0] = 0.f;  m[1] = 1.f;
        m[4] = -1.f; m[5] = 0.f;
        m[12] = 1.f; m[13] = 0.f;
    }
}

}  // namespace

GlRenderer::GlRenderer(JavaVM* vm, int videoWidth, int videoHeight)
    : vm_(vm), videoW_(videoWidth), videoH_(videoHeight) {}

GlRenderer::~GlRenderer() { release(); }

// ---------------------------------------------------------------- 生命周期

bool GlRenderer::start() {
    {
        std::lock_guard<std::mutex> lk(lock_);
        if (running_) return initOk_.load();
        running_ = true;
    }
    thread_ = std::thread(&GlRenderer::threadMain, this);

    std::unique_lock<std::mutex> lk(lock_);
    initCv_.wait_for(lk, std::chrono::seconds(10), [this] { return initialized_; });
    return initOk_.load();
}

void GlRenderer::release() {
    {
        std::lock_guard<std::mutex> lk(lock_);
        if (!running_) return;
        running_ = false;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void GlRenderer::threadMain() {
    JNIEnv* env = nullptr;
    if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("AttachCurrentThread failed");
        std::lock_guard<std::mutex> lk(lock_);
        initialized_ = true;
        initOk_ = false;
        initCv_.notify_all();
        return;
    }
    env_ = env;

    const bool ok = initEgl() && buildPrograms();
    if (ok) {
        glGenTextures(1, &cameraTex_);
        cameraTexId_.store(static_cast<int>(cameraTex_));
        LOGI("GL ready, video=%dx%d texture=%u", videoW_, videoH_, cameraTex_);
    } else {
        LOGE("GL init failed");
    }

    {
        std::lock_guard<std::mutex> lk(lock_);
        initialized_ = true;
        initOk_ = ok;
    }
    initCv_.notify_all();

    if (!ok) {
        vm_->DetachCurrentThread();
        return;
    }

    while (true) {
        std::deque<std::function<void()>> commands;
        bool frame = false;
        {
            std::unique_lock<std::mutex> lk(lock_);
            cv_.wait_for(lk, std::chrono::milliseconds(100),
                         [this] { return !running_ || !commands_.empty() || framePending_; });
            if (!running_) break;
            frame = framePending_;
            framePending_ = false;
            commands.swap(commands_);
        }
        for (auto& c : commands) c();
        if (frame) renderFrame();
    }

    // 收尾：上下文还在，先删 GL 资源
    if (wmTex_) glDeleteTextures(1, &wmTex_);
    if (cameraTex_) glDeleteTextures(1, &cameraTex_);
    if (cameraProgram_) glDeleteProgram(cameraProgram_);
    if (watermarkProgram_) glDeleteProgram(watermarkProgram_);
    if (texMatrixArray_ && env_) {
        env_->DeleteGlobalRef(texMatrixArray_);
        texMatrixArray_ = nullptr;
    }
    if (stClass_ && env_) {
        env_->DeleteGlobalRef(stClass_);
        stClass_ = nullptr;
    }
    if (stObject_ && env_) {
        env_->DeleteGlobalRef(stObject_);
        stObject_ = nullptr;
    }
    destroyEgl();
    env_ = nullptr;
    vm_->DetachCurrentThread();
}

// ---------------------------------------------------------------- EGL

bool GlRenderer::initEgl() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(display_, &major, &minor)) {
        LOGE("eglInitialize failed");
        display_ = EGL_NO_DISPLAY;
        return false;
    }
    LOGI("EGL %d.%d", major, minor);
    eglBindAPI(EGL_OPENGL_ES_API);

    struct Attempt {
        EGLint surfaceType;
        bool recordable;
    };
    // 优先要 RECORDABLE（给 MediaRecorder 用），不行再逐级退让
    const Attempt attempts[] = {
            {EGL_WINDOW_BIT | EGL_PBUFFER_BIT, true},
            {EGL_WINDOW_BIT | EGL_PBUFFER_BIT, false},
            {EGL_WINDOW_BIT, true},
            {EGL_WINDOW_BIT, false},
    };

    for (const auto& a : attempts) {
        EGLint attrs[32];
        int i = 0;
        attrs[i++] = EGL_RENDERABLE_TYPE;
        attrs[i++] = EGL_OPENGL_ES2_BIT;
        attrs[i++] = EGL_SURFACE_TYPE;
        attrs[i++] = a.surfaceType;
        attrs[i++] = EGL_RED_SIZE;
        attrs[i++] = 8;
        attrs[i++] = EGL_GREEN_SIZE;
        attrs[i++] = 8;
        attrs[i++] = EGL_BLUE_SIZE;
        attrs[i++] = 8;
        attrs[i++] = EGL_ALPHA_SIZE;
        attrs[i++] = 8;
        if (a.recordable) {
            attrs[i++] = EGL_RECORDABLE_ANDROID;
            attrs[i++] = 1;
        }
        attrs[i++] = EGL_NONE;

        EGLint count = 0;
        config_ = nullptr;
        if (eglChooseConfig(display_, attrs, &config_, 1, &count) && count > 0 && config_) {
            LOGI("EGLConfig selected (recordable=%d)", a.recordable ? 1 : 0);
            break;
        }
    }
    if (!config_) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttrs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed 0x%x", eglGetError());
        return false;
    }

    const EGLint pbAttrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pbAttrs);
    if (pbuffer_ == EGL_NO_SURFACE || !makeCurrent(pbuffer_)) {
        if (!eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, context_)) {
            LOGE("eglMakeCurrent failed 0x%x", eglGetError());
            return false;
        }
    }
    return true;
}

void GlRenderer::destroyEgl() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (previewSurface_ != EGL_NO_SURFACE) eglDestroySurface(display_, previewSurface_);
        if (encoderSurface_ != EGL_NO_SURFACE) eglDestroySurface(display_, encoderSurface_);
        if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
    if (previewWindow_) {
        ANativeWindow_release(previewWindow_);
        previewWindow_ = nullptr;
    }
    if (encoderWindow_) {
        ANativeWindow_release(encoderWindow_);
        encoderWindow_ = nullptr;
    }
    previewSurface_ = EGL_NO_SURFACE;
    encoderSurface_ = EGL_NO_SURFACE;
    pbuffer_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
    display_ = EGL_NO_DISPLAY;
}

bool GlRenderer::makeCurrent(EGLSurface surface) {
    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) return false;
    if (surface == EGL_NO_SURFACE) {
        if (pbuffer_ == EGL_NO_SURFACE) {
            return eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, context_) == EGL_TRUE;
        }
        surface = pbuffer_;
    }
    return eglMakeCurrent(display_, surface, surface, context_) == EGL_TRUE;
}

// ---------------------------------------------------------------- 着色器

GLuint GlRenderer::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? len : 1);
        glGetShaderInfoLog(shader, len, nullptr, log.data());
        LOGE("shader compile failed: %s", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool GlRenderer::buildPrograms() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint camFs = compileShader(GL_FRAGMENT_SHADER, kCameraFragment);
    GLuint wmFs = compileShader(GL_FRAGMENT_SHADER, kWatermarkFragment);
    if (!vs || !camFs || !wmFs) return false;

    auto link = [&](GLuint fs) -> GLuint {
        GLuint program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        GLint linked = 0;
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (!linked) {
            GLint len = 0;
            glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
            std::vector<char> log(len > 1 ? len : 1);
            glGetProgramInfoLog(program, len, nullptr, log.data());
            LOGE("program link failed: %s", log.data());
            glDeleteProgram(program);
            return 0;
        }
        return program;
    };

    cameraProgram_ = link(camFs);
    watermarkProgram_ = link(wmFs);
    glDeleteShader(vs);
    glDeleteShader(camFs);
    glDeleteShader(wmFs);
    if (!cameraProgram_ || !watermarkProgram_) return false;

    camPosLoc_ = glGetAttribLocation(cameraProgram_, "aPos");
    camTexLoc_ = glGetAttribLocation(cameraProgram_, "aTex");
    camMatrixLoc_ = glGetUniformLocation(cameraProgram_, "uMatrix");
    camSamplerLoc_ = glGetUniformLocation(cameraProgram_, "sTex");
    camTexMatrixLoc_ = glGetUniformLocation(cameraProgram_, "uTexMatrix");

    wmPosLoc_ = glGetAttribLocation(watermarkProgram_, "aPos");
    wmTexLoc_ = glGetAttribLocation(watermarkProgram_, "aTex");
    wmMatrixLoc_ = glGetUniformLocation(watermarkProgram_, "uMatrix");
    wmSamplerLoc_ = glGetUniformLocation(watermarkProgram_, "sTex");

    jfloatArray local = env_->NewFloatArray(16);
    if (!local) return false;
    texMatrixArray_ = static_cast<jfloatArray>(env_->NewGlobalRef(local));
    env_->DeleteLocalRef(local);
    return texMatrixArray_ != nullptr;
}

// ---------------------------------------------------------------- SurfaceTexture

bool GlRenderer::attachSurfaceTexture(jobject surfaceTextureGlobalRef) {
    if (!surfaceTextureGlobalRef) return false;
    return postAndWait([this, surfaceTextureGlobalRef]() {
        if (stClass_) {
            env_->DeleteGlobalRef(stClass_);
            stClass_ = nullptr;
        }
        if (stObject_) {
            env_->DeleteGlobalRef(stObject_);
        }
        stObject_ = surfaceTextureGlobalRef;
        stClass_ = static_cast<jclass>(env_->NewGlobalRef(env_->GetObjectClass(stObject_)));
        stUpdateTexImage_ = env_->GetMethodID(stClass_, "updateTexImage", "()V");
        stGetTimestamp_ = env_->GetMethodID(stClass_, "getTimestamp", "()J");
        stGetTransformMatrix_ = env_->GetMethodID(stClass_, "getTransformMatrix", "([F)V");
        LOGI("SurfaceTexture attached");
    });
}

void GlRenderer::updateCameraTexture() {
    if (!stObject_ || !env_ || !stUpdateTexImage_) return;
    env_->CallVoidMethod(stObject_, stUpdateTexImage_);
    if (env_->ExceptionCheck()) {
        env_->ExceptionClear();
        return;
    }
    lastTimestamp_ = env_->CallLongMethod(stObject_, stGetTimestamp_);
    if (stGetTransformMatrix_ && texMatrixArray_) {
        env_->CallVoidMethod(stObject_, stGetTransformMatrix_, texMatrixArray_);
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
        } else {
            env_->GetFloatArrayRegion(texMatrixArray_, 0, 16, texMatrix_);
        }
    }
}

// ---------------------------------------------------------------- 水印

void GlRenderer::setWatermarkPixels(std::vector<uint8_t> pixels, int width, int height,
                                    float leftFrac, float bottomFrac, float widthFrac) {
    std::lock_guard<std::mutex> lk(wmMutex_);
    wmPixels_ = std::move(pixels);
    wmPixelW_ = width;
    wmPixelH_ = height;
    wmLeftFrac_ = leftFrac;
    wmBottomFrac_ = bottomFrac;
    wmWidthFrac_ = widthFrac;
    wmDirty_ = true;
}

void GlRenderer::setRotation(float degrees) {
    post([this, degrees]() { rotationDeg_ = degrees; });
}

void GlRenderer::uploadWatermarkIfNeeded() {
    std::vector<uint8_t> data;
    int w = 0, h = 0;
    {
        std::lock_guard<std::mutex> lk(wmMutex_);
        if (!wmDirty_ || wmPixels_.empty()) return;
        wmDirty_ = false;
        data.swap(wmPixels_);
        w = wmPixelW_;
        h = wmPixelH_;
    }
    if (w <= 0 || h <= 0) return;

    if (!wmTex_) glGenTextures(1, &wmTex_);
    glBindTexture(GL_TEXTURE_2D, wmTex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
    wmTexW_ = w;
    wmTexH_ = h;
}

// ---------------------------------------------------------------- 绘制

void GlRenderer::renderFrame() {
    const bool hasPreview = previewVisible_ && previewSurface_ != EGL_NO_SURFACE;
    const bool hasEncoder = recording_ && encoderSurface_ != EGL_NO_SURFACE;

    if (!hasPreview && !hasEncoder) {
        // 关键：即使这一刻不画，也必须把相机帧消费掉（updateTexImage）。
        // 否则 SurfaceTexture 的缓冲队列会被几帧填满，相机拿不到空闲 buffer，
        // 之后就不再出帧了 —— 表现就是「切后台再回前台，预览黑屏」。
        if (makeCurrent(EGL_NO_SURFACE)) {
            updateCameraTexture();
        }
        return;
    }

    EGLSurface first = hasEncoder ? encoderSurface_ : previewSurface_;
    if (!makeCurrent(first)) {
        LOGW("makeCurrent failed, drop frame");
        return;
    }

    updateCameraTexture();
    uploadWatermarkIfNeeded();

    // 先画编码器：它可能阻塞（等编码器消费），不能让它拖住预览
    if (hasEncoder) {
        drawScene(videoW_, videoH_, false);
        const int64_t ts = lastTimestamp_ > 0 ? lastTimestamp_ : nowNanos();
        eglPresentationTimeANDROID(display_, encoderSurface_, ts);
        if (!eglSwapBuffers(display_, encoderSurface_)) {
            LOGW("encoder swap failed 0x%x", eglGetError());
        }
    }

    // 预览最后画并最后 swap，保证屏幕上看到的是最新一帧
    if (hasPreview) {
        if (hasEncoder && !makeCurrent(previewSurface_)) return;
        drawScene(previewW_, previewH_, true);
        if (!eglSwapBuffers(display_, previewSurface_)) {
            LOGW("preview swap failed 0x%x", eglGetError());
        }
    }

    if ((frameCounter_++ % 30) == 0) {
        LOGI("frame preview=%d enc=%d visible=%d pSurf=%p eSurf=%p",
             hasPreview ? 1 : 0, hasEncoder ? 1 : 0, previewVisible_ ? 1 : 0,
             reinterpret_cast<void*>(previewSurface_),
             reinterpret_cast<void*>(encoderSurface_));
    }
}

void GlRenderer::drawScene(int targetWidth, int targetHeight, bool fitInside) {
    if (targetWidth <= 0 || targetHeight <= 0) return;

    int vpX = 0, vpY = 0, vpW = targetWidth, vpH = targetHeight;
    if (fitInside) {
        // 预览铺满屏幕（cover）：宁可裁掉一点点边缘，也不要上下/左右黑边
        const float scale = std::max(static_cast<float>(targetWidth) / videoW_,
                                     static_cast<float>(targetHeight) / videoH_);
        vpW = static_cast<int>(videoW_ * scale);
        vpH = static_cast<int>(videoH_ * scale);
        vpX = (targetWidth - vpW) / 2;
        vpY = (targetHeight - vpH) / 2;
    }
    glViewport(vpX, vpY, vpW, vpH);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    // ---- 相机画面 ----
    glDisable(GL_BLEND);
    glUseProgram(cameraProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTex_);
    glUniform1i(camSamplerLoc_, 0);

    // 关键：方向只在纹理坐标上旋转（stMatrix * R），几何始终铺满视口。
    // 这样竖屏输出时把 1920x1080 的画面转 90° 填进 1080x1920，像素不变形。
    float uvRot[16];
    rotationMatrix2d(rotationDeg_, uvRot);
    float texM[16];
    multiply4(texMatrix_, uvRot, texM);
    glUniformMatrix4fv(camTexMatrixLoc_, 1, GL_FALSE, texM);

    const GLfloat identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUniformMatrix4fv(camMatrixLoc_, 1, GL_FALSE, identity);

    glEnableVertexAttribArray(camPosLoc_);
    glVertexAttribPointer(camPosLoc_, 2, GL_FLOAT, GL_FALSE, 0, kQuad);
    glEnableVertexAttribArray(camTexLoc_);
    glVertexAttribPointer(camTexLoc_, 2, GL_FLOAT, GL_FALSE, 0, kTexCoord);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(camPosLoc_);
    glDisableVertexAttribArray(camTexLoc_);

    // ---- 水印 ----
    // 画面已经是「正向」的（旋转已经烘进像素），水印不需要再转
    if (wmTex_ == 0 || wmTexW_ <= 0 || wmTexH_ <= 0) return;

    const float aspect = static_cast<float>(wmTexW_) / static_cast<float>(wmTexH_);
    const float wNdc = 2.0f * wmWidthFrac_;
    const float hNdc = 2.0f * (wmWidthFrac_ * videoW_ / aspect) / videoH_;
    const float cx = -1.0f + wNdc * 0.5f + wmLeftFrac_ * 2.0f;
    const float cy = -1.0f + hNdc * 0.5f + wmBottomFrac_ * 2.0f;

    const float sx = wNdc * 0.5f;
    const float sy = hNdc * 0.5f;

    const float matrix[16] = {
            sx,  0.f, 0.f, 0.f,
            0.f, sy,  0.f, 0.f,
            0.f, 0.f, 1.f, 0.f,
            cx,  cy,  0.f, 1.f,
    };

    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);  // 预乘 alpha
    glUseProgram(watermarkProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, wmTex_);
    glUniform1i(wmSamplerLoc_, 0);
    glUniformMatrix4fv(wmMatrixLoc_, 1, GL_FALSE, matrix);

    glEnableVertexAttribArray(wmPosLoc_);
    glVertexAttribPointer(wmPosLoc_, 2, GL_FLOAT, GL_FALSE, 0, kQuad);
    glEnableVertexAttribArray(wmTexLoc_);
    glVertexAttribPointer(wmTexLoc_, 2, GL_FLOAT, GL_FALSE, 0, kTexCoordFlipY);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(wmPosLoc_);
    glDisableVertexAttribArray(wmTexLoc_);
    glDisable(GL_BLEND);
}

// ---------------------------------------------------------------- 命令投递

void GlRenderer::post(std::function<void()> fn) {
    {
        std::lock_guard<std::mutex> lk(lock_);
        if (!running_) return;
        commands_.push_back(std::move(fn));
    }
    cv_.notify_one();
}

bool GlRenderer::postAndWait(const std::function<void()>& fn) {
    struct WaitState {
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
    };
    auto state = std::make_shared<WaitState>();
    {
        std::lock_guard<std::mutex> lk(lock_);
        if (!running_) return false;
        commands_.push_back([state, fn]() {
            fn();
            {
                std::lock_guard<std::mutex> l(state->m);
                state->done = true;
            }
            state->cv.notify_one();
        });
    }
    cv_.notify_one();
    std::unique_lock<std::mutex> lk(state->m);
    return state->cv.wait_for(lk, std::chrono::seconds(5), [&] { return state->done; });
}

bool GlRenderer::setPreviewWindow(ANativeWindow* window, int viewWidth, int viewHeight) {
    // 异步投递：录像时渲染线程可能被编码器 swap 阻塞几百毫秒，
    // 用同步等待容易超时，导致预览表面再也挂不上去（表现就是录像时预览全黑）。
    post([this, window, viewWidth, viewHeight]() {
        if (previewSurface_ != EGL_NO_SURFACE) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(display_, previewSurface_);
            previewSurface_ = EGL_NO_SURFACE;
        }
        if (previewWindow_) {
            ANativeWindow_release(previewWindow_);
            previewWindow_ = nullptr;
        }
        previewW_ = viewWidth;
        previewH_ = viewHeight;
        if (!window) return;
        previewWindow_ = window;
        previewSurface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
        if (previewSurface_ == EGL_NO_SURFACE) {
            LOGW("create preview EGL surface failed 0x%x", eglGetError());
        } else {
            LOGI("preview window attached %dx%d", viewWidth, viewHeight);
        }
    });
    return true;
}

void GlRenderer::updatePreviewSize(int viewWidth, int viewHeight) {
    post([this, viewWidth, viewHeight]() {
        previewW_ = viewWidth;
        previewH_ = viewHeight;
    });
}

void GlRenderer::setPreviewVisible(bool visible) {
    post([this, visible]() { previewVisible_ = visible; });
}

void GlRenderer::setVideoSize(int width, int height) {
    post([this, width, height]() {
        videoW_ = width;
        videoH_ = height;
    });
}

bool GlRenderer::setEncoderWindow(ANativeWindow* window) {
    return postAndWait([this, window]() {
        if (encoderSurface_ != EGL_NO_SURFACE) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(display_, encoderSurface_);
            encoderSurface_ = EGL_NO_SURFACE;
        }
        if (encoderWindow_) {
            ANativeWindow_release(encoderWindow_);
            encoderWindow_ = nullptr;
        }
        if (!window) return;
        encoderWindow_ = window;
        encoderSurface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
        if (encoderSurface_ == EGL_NO_SURFACE) {
            LOGW("create encoder EGL surface failed 0x%x", eglGetError());
        }
    });
}

void GlRenderer::clearEncoderWindow() {
    setEncoderWindow(nullptr);
}

void GlRenderer::setRecording(bool recording) {
    // 必须同步：返回后保证不会再往编码器 Surface 出帧，
    // 调用方紧接着调用 MediaRecorder.stop() 才是安全的。
    postAndWait([this, recording]() { recording_ = recording; });
}

void GlRenderer::onFrameAvailable() {
    {
        std::lock_guard<std::mutex> lk(lock_);
        framePending_ = true;
    }
    cv_.notify_one();
}
