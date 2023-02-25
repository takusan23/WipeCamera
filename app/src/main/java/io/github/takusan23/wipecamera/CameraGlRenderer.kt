package io.github.takusan23.wipecamera

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * カメラをレンダリングする
 * カメラの映像は、[SurfaceTexture]を利用することで、OpenGLのテクスチャとして利用ができる。
 *
 * @param rotation 画面が回転している場合、テクスチャも回転させる必要があるので
 * @param onCreatedTextureIds フロントカメラのテクスチャID、バックカメラのテクスチャIDを返す
 * @param onRequestBackCameraSurfaceTexture バックカメラの [SurfaceTexture]
 * @param onRequestFrontCameraSurfaceTexture フロントカメラの [SurfaceTexture]
 */
class CameraGlRenderer(
    private val rotation: Float,
    private val onCreatedTextureIds: (backCameraTextureId: Int, frontCameraTextureId: Int) -> Unit,
    private val onRequestBackCameraSurfaceTexture: () -> SurfaceTexture,
    private val onRequestFrontCameraSurfaceTexture: () -> SurfaceTexture,
) : GLSurfaceView.Renderer {

    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    private val mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.size * FLOAT_SIZE_BYTES).run {
        order(ByteOrder.nativeOrder())
        asFloatBuffer().apply {
            put(mTriangleVerticesData)
            position(0)
        }
    }

    // ハンドルたち
    private var mProgram = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    // テクスチャID
    // SurfaceTexture に渡す
    private var frontCameraTextureId = 0
    private var backCameraTextureId = 0

    // テクスチャのハンドル
    private var uFrontCameraTextureHandle = 0
    private var uBackCameraTextureHandle = 0
    private var uDrawBackCameraHandle = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GL Thread
        // OpenGL の操作はスレッドを考慮しないといけない、、、
        val (backCameraTextureId, frontCameraTextureId) = setupProgram()
        onCreatedTextureIds(backCameraTextureId, frontCameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // do nothing
    }

    // requestRender のたびに呼ばれる
    override fun onDrawFrame(gl: GL10?) {
        prepareDraw()
        drawBackCamera(onRequestBackCameraSurfaceTexture())
        drawFrontCamera(onRequestFrontCameraSurfaceTexture())
        GLES20.glFinish()
    }

    /**
     * シェーダーの用意をする。
     * テクスチャIDを返すので、SurfaceTexture のコンストラクタ入れてね。
     *
     * @return バックカメラのテクスチャID と フロントカメラのテクスチャID を返す
     */
    fun setupProgram(): Pair<Int, Int> {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }
        uBackCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "uBackCameraTexture")
        checkGlError("glGetUniformLocation uBackCameraTextureHandle")
        if (uBackCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for uBackCameraTextureHandle")
        }
        uFrontCameraTextureHandle = GLES20.glGetUniformLocation(mProgram, "uFrontCameraTexture")
        checkGlError("glGetUniformLocation uFrontCameraTexture")
        if (uFrontCameraTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for uFrontCameraTexture")
        }
        uDrawBackCameraHandle = GLES20.glGetUniformLocation(mProgram, "uDrawBackCamera")
        checkGlError("glGetUniformLocation uDrawBackCameraHandle")
        if (uDrawBackCameraHandle == -1) {
            throw RuntimeException("Could not get attrib location for uDrawBackCameraHandle")
        }

        // カメラ2つなので、2つ分のテクスチャを作成
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        // バックカメラ
        backCameraTextureId = textures[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backCameraTextureId)
        checkGlError("glBindTexture backCameraTextureId")

        // 縮小拡大時の補間設定
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter backCameraTexture")

        // フロントカメラ
        frontCameraTextureId = textures[1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frontCameraTextureId)
        checkGlError("glBindTexture frontCameraTextureId")

        // 縮小拡大時の補間設定
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter frontCameraTexture")

        // アルファブレンドを有効
        // これにより、透明なテクスチャがちゃんと透明に描画される
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGlError("glEnable BLEND")

        return backCameraTextureId to frontCameraTextureId
    }

    /** 描画前に呼び出す */
    fun prepareDraw() {
        // glError 1282 の原因とかになる
        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        // Snapdragon だと glClear が無いと映像が乱れる
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
    }

    /** バックカメラの映像 [SurfaceTexture] を描画する */
    private fun drawBackCamera(surfaceTexture: SurfaceTexture) {
        // テクスチャ更新。呼ばないと真っ黒
        surfaceTexture.updateTexImage()
        checkGlError("drawBackCamera start")
        surfaceTexture.getTransformMatrix(mSTMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backCameraTextureId)
        // バックカメラのテクスチャIDは GLES20.GL_TEXTURE0 なので 0
        GLES20.glUniform1i(uBackCameraTextureHandle, 0)
        // フロントカメラのテクスチャIDは GLES20.GL_TEXTURE1 なので 1
        GLES20.glUniform1i(uFrontCameraTextureHandle, 1)
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        // ----
        // バックカメラを描画するフラグを立てる
        // ----
        GLES20.glUniform1i(uDrawBackCameraHandle, 1)
        // Matrix.XXX のユーティリティー関数で行列の操作をする場合、適用させる順番に注意する必要があります
        Matrix.setIdentityM(mMVPMatrix, 0)
        // 画面回転している場合は回転する
        Matrix.rotateM(mMVPMatrix, 0, rotation, 0f, 0f, 1f)

        // 横幅を計算して合わせる
        // 縦は outputHeight 最大まで
        // val scaleY = (outputVideoHeight / originVideoHeight.toFloat())
        // val textureWidth = originVideoWidth * scaleY
        // val percent = textureWidth / outputVideoWidth.toFloat()
        // Matrix.scaleM(mMVPMatrix, 0, percent, 1f, 1f)

        // 描画する
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays BackCamera")
    }

    /** フロントカメラの映像 [SurfaceTexture] を描画する */
    private fun drawFrontCamera(surfaceTexture: SurfaceTexture) {
        // テクスチャ更新。呼ばないと真っ黒
        surfaceTexture.updateTexImage()
        checkGlError("drawBackCamera start")
        surfaceTexture.getTransformMatrix(mSTMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frontCameraTextureId)
        // バックカメラのテクスチャIDは GLES20.GL_TEXTURE0 なので 0
        GLES20.glUniform1i(uBackCameraTextureHandle, 0)
        // フロントカメラのテクスチャIDは GLES20.GL_TEXTURE1 なので 1
        GLES20.glUniform1i(uFrontCameraTextureHandle, 1)
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")
        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices)
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")
        // ----
        // バックカメラを描画するフラグを下ろしてフロントカメラにする
        // ----
        GLES20.glUniform1i(uDrawBackCameraHandle, 0)
        // Matrix.XXX のユーティリティー関数で行列の操作をする場合、適用させる順番に注意する必要があります
        Matrix.setIdentityM(mMVPMatrix, 0)
        // 右上に移動させる
        Matrix.translateM(mMVPMatrix, 0, 1f - 0.3f, 1f - 0.3f, 1f)
        // 半分ぐらいにする
        Matrix.scaleM(mMVPMatrix, 0, 0.3f, 0.3f, 1f)
        // 画面回転している場合は回転する
        Matrix.rotateM(mMVPMatrix, 0, rotation, 0f, 0f, 1f)

        // 横幅を計算して合わせる
        // 縦は outputHeight 最大まで
        // val scaleY = (outputVideoHeight / originVideoHeight.toFloat())
        // val textureWidth = originVideoWidth * scaleY
        // val percent = textureWidth / outputVideoWidth.toFloat()
        // Matrix.scaleM(mMVPMatrix, 0, percent, 1f, 1f)

        // 描画する
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays FrontCamera")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) {
            return 0
        }
        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            return 0
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }

    companion object {
        private val mTriangleVerticesData = floatArrayOf(
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f
        )

        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
        private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3

        /** バーテックスシェーダー。座標などを決める */
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        /** フラグメントシェーダー。実際の色を返す */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uBackCameraTexture;        
            uniform samplerExternalOES uFrontCameraTexture;        
            
            // バックカメラを描画するのか、フロントカメラを描画するのかのフラグ
            uniform int uDrawBackCamera;
        
            void main() {
                vec4 backCameraTexture = texture2D(uBackCameraTexture, vTextureCoord);
                vec4 frontCameraTexture = texture2D(uFrontCameraTexture, vTextureCoord);
                
                if (bool(uDrawBackCamera)) {
                    gl_FragColor = backCameraTexture;                
                } else {
                    gl_FragColor = frontCameraTexture;
                }
            }
        """
    }
}