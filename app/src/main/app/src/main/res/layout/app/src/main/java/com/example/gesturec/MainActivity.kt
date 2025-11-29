package com.example.gesturec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var startButton: Button
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private var processing = false
    private val siteUrl = "https://chatunity.it"

    private lateinit var tflite: Interpreter

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startCamera()
            else Toast.makeText(this, "Camera permission richiesta", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        startButton = findViewById(R.id.startButton)

        // Carica modello TFLite dalla cartella assets
        tflite = Interpreter(loadModelFile("c_gesture.tflite"))

        startButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!processing) {
                    processing = true
                    val bitmap = imageProxy.toBitmap()
                    GlobalScope.launch(Dispatchers.Default) {
                        try {
                            if (detectCGesture(bitmap)) {
                                withContext(Dispatchers.Main) {
                                    openSite()
                                }
                            }
                        } finally {
                            processing = false
                            imageProxy.close()
                        }
                    }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
            } catch (exc: Exception) {
                Toast.makeText(this, "Impossibile avviare la camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openSite() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl))
        startActivity(intent)
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val channel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun detectCGesture(bitmap: Bitmap): Boolean {
        // Preprocess bitmap se necessario (ridimensiona, normalizza)
        val input = preprocessBitmap(bitmap) // funzione che converte bitmap in input tensor
        val output = Array(1) { FloatArray(1) }
        tflite.run(input, output)
        // Se la probabilità > 0.8 considera gesto rilevato
        return output[0][0] > 0.8f
    }

    private fun preprocessBitmap(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        // Ridimensiona bitmap a 224x224, normalizza pixel a [0,1]
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
        for (x in 0 until 224) {
            for (y in 0 until 224) {
                val px = resized.getPixel(x, y)
                input[0][y][x][0] = ((px shr 16 and 0xFF) / 255.0f)
                input[0][y][x][1] = ((px shr 8 and 0xFF) / 255.0f)
                input[0][y][x][2] = ((px and 0xFF) / 255.0f)
            }
        }
        return input
    }
}

// --- helper extension ---
fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
