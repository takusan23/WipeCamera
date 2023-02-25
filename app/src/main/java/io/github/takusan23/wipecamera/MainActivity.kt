package io.github.takusan23.wipecamera

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.takusan23.wipecamera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity(), SurfaceTexture.OnFrameAvailableListener {

    private val viewBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    /** Surface で描画している OpenGL の管理するやつ */
    private val surfaceInputOpenGlList = mutableListOf<SurfaceInputOpenGl>()

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

    /** [MediaRecorder] */
    private val mediaRecorder by lazy {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
            setMediaRecorderParams(this)
        }
    }

    /** 権限リクエストする */
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.all { it.value }) {
            lifecycleScope.launch {
                setupCamera()
            }
        }
    }

    var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // SystemUI Hide
        supportActionBar?.hide()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        // 権限チェック
        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            lifecycleScope.launch {
                setupCamera()
            }
        } else {
            // 権限を求める
            permissionRequester.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
        }

        // 録画する
        viewBinding.recordButton.setOnClickListener {
            if (!isRecording) {
                Toast.makeText(this, "録画開始", Toast.LENGTH_SHORT).show()
                viewBinding.recordButton.setImageResource(R.drawable.outline_stop_24)
                mediaRecorder.start()
            } else {
                Toast.makeText(this, "終了", Toast.LENGTH_SHORT).show()
                viewBinding.recordButton.setImageResource(R.drawable.outline_videocam_24)
                mediaRecorder.stop()
                setMediaRecorderParams(mediaRecorder)
            }
            isRecording = !isRecording
        }
    }

    /**
     * SurfaceTexture (カメラのフレーム) が更新されたら呼ばれる
     * OpenGL で加工して Surface に流す
     */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // プレビュー の Surface へ描画する
        val (previewGl, recorderGl) = surfaceInputOpenGlList
        previewGl.makeCurrent()
        previewGl.drawFrame()
        previewGl.swapBuffers()
        // 次に MediaRecorder の Surface に描画する
        if (isRecording) {
            recorderGl.makeCurrent()
            recorderGl.drawFrame()
            recorderGl.swapBuffers()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceInputOpenGlList.forEach { it.release() }
        surfaceTextureList.forEach { it.release() }
        useCameraList.forEach { it.destroy() }
    }

    /** カメラの初期化をする */
    private suspend fun setupCamera() {
        // プレビュー用の Surface
        val previewSurface = waitSurface().surface
        // 録画用の Surface
        val recorderSurface = mediaRecorder.surface

        // Surface の数だけ CameraGlRenderer 作る
        val previewGlRenderer = CameraGlRenderer(
            rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90f else 0f,
            // プレビュー用に作った SurfaceTexture
            onRequestBackCameraSurfaceTexture = { surfaceTextureList[0] },
            onRequestFrontCameraSurfaceTexture = { surfaceTextureList[1] }
        )
        val recorderGlRenderer = CameraGlRenderer(
            rotation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90f else 0f,
            // 録画用に作った SurfaceTexture
            onRequestBackCameraSurfaceTexture = { surfaceTextureList[2] },
            onRequestFrontCameraSurfaceTexture = { surfaceTextureList[3] }
        )

        // プレビューSurface 録画Surface それぞれ OpenGL のソースを用意する
        surfaceInputOpenGlList.clear()
        val previewSurfaceInputOpenGl = SurfaceInputOpenGl(previewSurface, previewGlRenderer)
        val recorderSurfaceInputOpenGl = SurfaceInputOpenGl(recorderSurface, recorderGlRenderer)
        surfaceInputOpenGlList.addAll(listOf(previewSurfaceInputOpenGl, recorderSurfaceInputOpenGl))

        // SurfaceTexture を用意
        // makeCurrent を呼ぶと GLThread のエラーが消える？
        previewSurfaceInputOpenGl.makeCurrent()
        val previewSurfaceTexturePair = previewGlRenderer.setupProgram().let { (backCameraTextureId, frontCameraTextureId) ->
            // バックカメラ、フロントカメラ それぞれ SurfaceTexture を作成する
            // SurfaceTexture の場合は setDefaultBufferSize で解像度の設定ができる
            val back = SurfaceTexture(backCameraTextureId).apply {
                setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                setOnFrameAvailableListener(this@MainActivity)
            }
            val front = SurfaceTexture(frontCameraTextureId).apply {
                setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                setOnFrameAvailableListener(this@MainActivity)
            }
            back to front
        }

        recorderSurfaceInputOpenGl.makeCurrent()
        val recorderSurfaceTexturePair = recorderGlRenderer.setupProgram().let { (backCameraTextureId, frontCameraTextureId) ->
            val back = SurfaceTexture(backCameraTextureId).apply {
                setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                setOnFrameAvailableListener(this@MainActivity)
            }
            val front = SurfaceTexture(frontCameraTextureId).apply {
                setDefaultBufferSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
                setOnFrameAvailableListener(this@MainActivity)
            }
            back to front
        }
        // リソース開放用に配列に入れておく
        surfaceTextureList.clear()
        surfaceTextureList.addAll(previewSurfaceTexturePair.toList() + recorderSurfaceTexturePair.toList())

        // カメラを開いて、配列に入れる
        val (backCameraId, frontCameraId) = getCameraIdData()
        useCameraList.clear()
        // プレビュー用 SurfaceTexture / 録画用 SurfaceTexture
        useCameraList += Camera(this@MainActivity, backCameraId, listOf(Surface(previewSurfaceTexturePair.first), Surface(recorderSurfaceTexturePair.first)))
        useCameraList += Camera(this@MainActivity, frontCameraId, listOf(Surface(previewSurfaceTexturePair.second), Surface(recorderSurfaceTexturePair.second)))
        // 初期化
        useCameraList.forEach { it.setupCamera() }
    }

    /** Surface の用意が終わるまで一時停止する */
    private suspend fun waitSurface() = suspendCoroutine { continuation ->
        viewBinding.previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
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

    /** [MediaRecorder]のパラメーターをセットする */
    private fun setMediaRecorderParams(mediaRecorder: MediaRecorder) {
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setVideoEncodingBitRate(1_000_000)
            setVideoFrameRate(30)
            setVideoSize(CAMERA_RESOLTION_WIDTH, CAMERA_RESOLTION_HEIGHT)
            setAudioSamplingRate(44100)
            setOutputFile(File(getExternalFilesDir(null), "${System.currentTimeMillis()}.mp4"))
            prepare()
        }
    }

    /** バックカメラ、フロントカメラのIDを取得する */
    private fun getCameraIdData(): Pair<String, String> {
        var backCameraId = ""
        var frontCameraId = ""
        for (cameraId in cameraManager.cameraIdList) {
            when (cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> backCameraId = cameraId
                CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = cameraId
            }
            // 両方揃ったら return
            if (backCameraId.isNotEmpty() && frontCameraId.isNotEmpty()) {
                break
            }
        }
        return backCameraId to frontCameraId
    }

    companion object {

        /** 720P 解像度 幅 */
        private const val CAMERA_RESOLTION_WIDTH = 1280

        /** 720P 解像度 高さ */
        private const val CAMERA_RESOLTION_HEIGHT = 720

    }

}