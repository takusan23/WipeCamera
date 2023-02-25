package io.github.takusan23.wipecamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView

/** カメラのプレビューを OpenGL でレンダリングする */
class CameraGlSurfaceView(
    context: Context,
    onCreatedTextureIds: (backCameraTextureId: Int, frontCameraTextureId: Int) -> Unit,
    onRequestBackCameraSurfaceTexture: () -> SurfaceTexture,
    onRequestFrontCameraSurfaceTexture: () -> SurfaceTexture,
) : GLSurfaceView(context) {

    init {
        // Create an OpenGL ES 2.0 context
        setEGLContextClientVersion(2)
        // GL Thread
        // OpenGL の操作はスレッドを考慮しないといけない、、、
        val cameraGlRenderer = CameraGlRenderer(onCreatedTextureIds, onRequestBackCameraSurfaceTexture, onRequestFrontCameraSurfaceTexture)
        setRenderer(cameraGlRenderer)
    }

}