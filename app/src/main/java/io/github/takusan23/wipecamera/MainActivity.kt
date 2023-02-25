package io.github.takusan23.wipecamera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.wipecamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {

    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

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
        setContentView(viewBinding.root)

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

    /** カメラの初期化をする */
    private suspend fun setupCamera() = withContext(Dispatchers.Main) {
        val surfaceHolderList =
            listOf(viewBinding.previewSurfaceViewOne, viewBinding.previewSurfaceViewTwo)
                .map { async { waitSurfaceViewPrepared(it) } }
                .map { it.await() }
        val (backCameraId, frontCameraId) = getCameraIdData()
        // カメラを開いて、配列に入れる
        useCameraList.clear()
        useCameraList += Camera(this@MainActivity, backCameraId, surfaceHolderList[0].surface)
        useCameraList += Camera(this@MainActivity, frontCameraId, surfaceHolderList[1].surface)
        // 初期化
        useCameraList
            .map { async { it.setupCamera() } }
            .map { it.await() }
    }

    /** SurfaceViewの準備を待つ */
    private suspend fun waitSurfaceViewPrepared(surfaceView: SurfaceView) = suspendCoroutine { continuation ->
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                continuation.resume(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // do nothing
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // do nothing
            }
        })
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

    override fun onDestroy() {
        super.onDestroy()
        useCameraList.forEach { it.destroy() }
    }

}