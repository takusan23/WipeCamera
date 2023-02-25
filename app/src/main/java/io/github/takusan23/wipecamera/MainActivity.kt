package io.github.takusan23.wipecamera

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SurfaceTexture.OnFrameAvailableListener {

    private var cameraGlSurfaceView: CameraGlSurfaceView? = null

    /**
     * SurfaceTexture の配列
     *
     * Index 0 = バックカメラ
     * Index 1 = フロントカメラ
     */
    private val surfaceTextureList = mutableListOf<SurfaceTexture>()

    /** カメラ */
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val useCameraList = mutableListOf<Camera>()

    /** 権限リクエストする */
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.all { it.value }) {
            lifecycleScope.launch {
                setupCamera()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView() GLSurfaceView インスタンス生成後に呼んでいます
        supportActionBar?.hide()

        // 権限チェック
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                setupCamera()
            }
        } else {
            // 権限を求める
            permissionRequester.launch(arrayOf(android.Manifest.permission.CAMERA))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceTextureList.forEach { it.release() }
        useCameraList.forEach { it.destroy() }
    }

    /** カメラの初期化をする */
    private fun setupCamera() {
        // OpenGL 側の用意
        // SurfaceTexture を利用して OpenGL 側でカメラ映像をテクスチャとして利用できるようにする
        val cameraGlSurfaceView = CameraGlSurfaceView(
            context = this@MainActivity,
            rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90f else 0f,
            onCreatedTextureIds = { backCameraTextureId, frontCameraTextureId ->
                // onSurfaceCreated のあとに呼ばれる
                surfaceTextureList.clear()
                surfaceTextureList += SurfaceTexture(backCameraTextureId).apply { setOnFrameAvailableListener(this@MainActivity) }
                surfaceTextureList += SurfaceTexture(frontCameraTextureId).apply { setOnFrameAvailableListener(this@MainActivity) }

                // カメラを開いて、配列に入れる
                val textureSurfaceList = surfaceTextureList.map { Surface(it) }
                val (backCameraId, frontCameraId) = getCameraIdData()
                useCameraList.clear()
                useCameraList += Camera(this@MainActivity, backCameraId, textureSurfaceList[0])
                useCameraList += Camera(this@MainActivity, frontCameraId, textureSurfaceList[1])
                // 初期化
                lifecycleScope.launch {
                    useCameraList.forEach { it.setupCamera() }
                }
            },
            onRequestBackCameraSurfaceTexture = { surfaceTextureList[0] },
            onRequestFrontCameraSurfaceTexture = { surfaceTextureList[1] }
        )
        setContentView(cameraGlSurfaceView)
        this@MainActivity.cameraGlSurfaceView = cameraGlSurfaceView
    }

    /**
     * SurfaceTexture が更新されたら呼ばれる
     * [CameraGlRenderer.onDrawFrame]を呼び出す
     */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        cameraGlSurfaceView?.requestRender()
    }

    /** バックカメラ、フロントカメラのIDを取得する */
    private fun getCameraIdData(): Pair<String, String> {
        var backCameraId = ""
        var frontCameraId = ""
        cameraManager.cameraIdList.forEach { cameraId ->
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> backCameraId = cameraId
                CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = cameraId
            }
        }
        return backCameraId to frontCameraId
    }

}