// GLSL ES 2.0 着色器源码。
// 之所以用 ES 2.0：兼容性最好，且这套逻辑（一个 OES 采样 + 一个 RGBA 叠加）
// 在 ES 2.0 上已经是纯 GPU 完成，没必要上 3.0。
#pragma once

// 顶点着色器：aPos 是 NDC 坐标，aTex 是纹理坐标，
// uMatrix 用来做「平移 + 旋转 + 缩放」（相机画面用单位矩阵，水印用变换矩阵）。
static const char* kVertexShader = R"(
attribute vec4 aPos;
attribute vec2 aTex;
uniform mat4 uMatrix;
varying vec2 vTex;
void main() {
    vTex = aTex;
    gl_Position = uMatrix * aPos;
}
)";

// 相机画面：samplerExternalOES 直接把摄像头 SurfaceTexture 的 OES 纹理采样出来，
// CPU 完全不碰像素，这是省电的关键。
static const char* kCameraFragment = R"(
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES sTex;
uniform mat4 uTexMatrix;
void main() {
    vec2 uv = (uTexMatrix * vec4(vTex, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(sTex, uv);
}
)";

// 水印：普通 2D 纹理，带预乘 alpha，混合方式在 C++ 里设为 ONE / ONE_MINUS_SRC_ALPHA
static const char* kWatermarkFragment = R"(
precision mediump float;
varying vec2 vTex;
uniform sampler2D sTex;
void main() {
    gl_FragColor = texture2D(sTex, vTex);
}
)";
