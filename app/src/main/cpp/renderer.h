#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/native_window.h>
#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

/**
 * 单线程 OpenGL 渲染器。
 *
 * 数据流：
 *   Camera2 ──SurfaceTexture──> OES 纹理 ──┐
 *                                          ├─ GL 合成（相机 + 水印）─┬─> 预览 SurfaceView
 *   水印 Bitmap ──RGBA 纹理 ───────────────┘                        └─> MediaRecorder 输入 Surface
 *
 * 设计约定：
 * 1. 所有 EGL / GL 调用只发生在渲染线程，其他线程通过命令队列投递；
 * 2. 跨线程不传 Java 对象——JNI 层先把 Surface 转成 ANativeWindow、
 *    把 Bitmap 拷成像素数组，再交给这里，避免 AttachCurrentThread 的坑；
 * 3. 需要同步结果的接口用 postAndWait，最长等 5 秒，避免死锁。
 */
class GlRenderer {
public:
    GlRenderer(JavaVM* vm, int videoWidth, int videoHeight);
    ~GlRenderer();

    bool start();
    void release();

    /** 摄像头输出用的 OES 纹理名（在 start() 成功后有效） */
    int cameraTexture() const { return cameraTexId_.load(); }

    /** 绑定 SurfaceTexture 的全局引用（所有权转移给渲染器）；失败时调用方负责释放 */
    bool attachSurfaceTexture(jobject surfaceTextureGlobalRef);

    /** 预览窗口（所有权转移；传 nullptr 表示销毁）；失败时调用方负责释放 */
    bool setPreviewWindow(ANativeWindow* window, int viewWidth, int viewHeight);
    void updatePreviewSize(int viewWidth, int viewHeight);
    void setPreviewVisible(bool visible);

    void setVideoSize(int width, int height);

    /** 编码器输入窗口（所有权转移）；失败时调用方负责释放 */
    bool setEncoderWindow(ANativeWindow* window);
    void clearEncoderWindow();

    /** 开关「是否往编码器画帧」 */
    void setRecording(bool recording);

    /** 更新水印：像素为 RGBA_8888 预乘 alpha */
    void setWatermarkPixels(std::vector<uint8_t> pixels, int width, int height,
                            float leftFrac, float bottomFrac, float widthFrac);

    /**
     * 画面方向（度，0/90/180/270）。
     * 只在**纹理坐标**上旋转，几何始终铺满整个视口，
     * 所以旋转后不会因为视口长宽比不同而被拉伸变形。
     */
    void setRotation(float degrees);

    /** 相机新帧到达（SurfaceTexture.OnFrameAvailableListener 里调用） */
    void onFrameAvailable();

private:
    void threadMain();
    bool initEgl();
    void destroyEgl();
    bool makeCurrent(EGLSurface surface);
    bool buildPrograms();
    GLuint compileShader(GLenum type, const char* source);
    void updateCameraTexture();
    void uploadWatermarkIfNeeded();
    void renderFrame();
    void drawScene(int targetWidth, int targetHeight, bool fitInside);

    void post(std::function<void()> fn);
    bool postAndWait(const std::function<void()>& fn);

    JavaVM* vm_;
    int videoW_;
    int videoH_;

    // ---- EGL ----
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface previewSurface_ = EGL_NO_SURFACE;
    EGLSurface encoderSurface_ = EGL_NO_SURFACE;
    ANativeWindow* previewWindow_ = nullptr;
    ANativeWindow* encoderWindow_ = nullptr;
    int previewW_ = 0;
    int previewH_ = 0;

    // ---- GL ----
    GLuint cameraTex_ = 0;
    std::atomic<int> cameraTexId_{0};
    GLuint cameraProgram_ = 0;
    GLuint watermarkProgram_ = 0;

    GLint camPosLoc_ = -1;
    GLint camTexLoc_ = -1;
    GLint camMatrixLoc_ = -1;
    GLint camSamplerLoc_ = -1;
    GLint camTexMatrixLoc_ = -1;

    GLint wmPosLoc_ = -1;
    GLint wmTexLoc_ = -1;
    GLint wmMatrixLoc_ = -1;
    GLint wmSamplerLoc_ = -1;

    GLuint wmTex_ = 0;
    int wmTexW_ = 0;
    int wmTexH_ = 0;

    // ---- 待上传的水印位图 ----
    std::mutex wmMutex_;
    std::vector<uint8_t> wmPixels_;
    int wmPixelW_ = 0;
    int wmPixelH_ = 0;
    float wmLeftFrac_ = 0.0f;
    float wmBottomFrac_ = 0.0f;
    float wmWidthFrac_ = 0.5f;
    bool wmDirty_ = false;

    /** 画面旋转角度，0/90/180/270（作用在纹理坐标上） */
    float rotationDeg_ = 0.0f;

    // ---- SurfaceTexture ----
    jobject stObject_ = nullptr;
    jclass stClass_ = nullptr;
    jmethodID stUpdateTexImage_ = nullptr;
    jmethodID stGetTimestamp_ = nullptr;
    jmethodID stGetTransformMatrix_ = nullptr;
    jfloatArray texMatrixArray_ = nullptr;
    float texMatrix_[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    int64_t lastTimestamp_ = 0;

    // ---- 线程与命令队列 ----
    std::thread thread_;
    std::mutex lock_;
    std::condition_variable cv_;
    std::condition_variable initCv_;
    std::deque<std::function<void()>> commands_;
    bool running_ = false;
    bool recording_ = false;
    bool previewVisible_ = true;
    bool framePending_ = false;
    bool initialized_ = false;
    std::atomic<bool> initOk_{false};
    int frameCounter_ = 0;

    JNIEnv* env_ = nullptr;  // 仅渲染线程使用
};
