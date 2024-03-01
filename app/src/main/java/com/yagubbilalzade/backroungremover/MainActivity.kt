package com.yagubbilalzade.backroungremover

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream


class MainActivity : AppCompatActivity() {

    private lateinit var captureIV: ImageView
    private lateinit var imageUri: Uri
    val cameraPermission = android.Manifest.permission.CAMERA
    val storagePermission = android.Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        val frag_view: View = findViewById(R.id.frag_view)

        supportFragmentManager.beginTransaction().replace(R.id.frag_view, GreetingsFragment())
            .commit()

        Handler(Looper.getMainLooper()).postDelayed({
            ViewGone(frag_view)
        }, 3000)

        captureIV = findViewById(R.id.iv_camera)
        val btn_camera: Button = findViewById(R.id.btn_camera)
        val btn_galery: Button = findViewById(R.id.btn_galery)

        val storagePermissionGranted = ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(this, cameraPermission) == PackageManager.PERMISSION_GRANTED


        val CAMERA_PERMISSION_REQUEST_CODE = 123



        imageUri = createImageUri()

        val pickImg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->


            if (uri != null) {
                processImage(uri)



            }
        }

        val contract = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->

            if (success) {
                processImage(imageUri)

            }
        }

        btn_camera.setOnClickListener {

            if(cameraPermissionGranted){
                contract.launch(imageUri)
            }else{
                ActivityCompat.requestPermissions(this, arrayOf(cameraPermission),
                    CAMERA_PERMISSION_REQUEST_CODE)
            }

        }

        btn_galery.setOnClickListener {
            if(storagePermissionGranted){
                pickImg.launch("image/*")
            }else{
                ActivityCompat.requestPermissions(this, arrayOf(storagePermission),
                    CAMERA_PERMISSION_REQUEST_CODE)
            }

        }


    }

    private fun createImageUri(): Uri {
        val image = File(filesDir, "camera_photos.png")
        return FileProvider.getUriForFile(
            this, "com.yagubbilalzade.backroungremover.FileProvider", image
        )
    }

    fun ViewGone(view: View) {
        view.visibility = View.GONE
    }

    fun ViewCome(view: View) {
        view.visibility = View.VISIBLE
    }
    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            null
        }
    }


    private fun processImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val inputImage = createInputImage(applicationContext, uri)
            if (inputImage != null) {
                val result: Task<SegmentationMask> = getSegmentationResult(inputImage)

                val bitmapUri = uriToBitmap(uri)
                displaySegmentationResult(bitmapUri!!, result)
            }
        }
    }

    private fun createInputImage(context: Context, uri: Uri): InputImage? {
        val filePath = getRealPathFromUri(uri)
        return if (filePath != null) {
            InputImage.fromFilePath(context, Uri.fromFile(File(filePath)))
        } else {
            null
        }
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        val context = applicationContext
        val contentResolver = context.contentResolver

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val fileName = cursor.getString(columnIndex)

                val filePath =
                    context.getExternalFilesDir(null)?.absolutePath + File.separator + fileName
                saveFileFromUri(uri, filePath)
                return filePath
            }
        } finally {
            cursor?.close()
        }

        return null
    }

    private fun saveFileFromUri(uri: Uri, filePath: String) {
        val inputStream = contentResolver.openInputStream(uri)
        val outputStream = FileOutputStream(filePath)

        inputStream?.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
    }

    private suspend fun getSegmentationResult(inputImage: InputImage): Task<SegmentationMask> =
        withContext(Dispatchers.IO) {
            val options =
                SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask().build()

            val segmenter = Segmentation.getClient(options)
            return@withContext segmenter.process(inputImage)
        }

    private fun displaySegmentationResult(BitmapImage: Bitmap,result: Task<SegmentationMask>) {
        result.addOnSuccessListener { segmentationMask ->
            val maskBitmap = createBitmapFromMask(BitmapImage,segmentationMask)


            Glide.with(this).load(maskBitmap)
                .override(captureIV.width, captureIV.height).into(captureIV)
        }
    }

    private fun createBitmapFromMask(originalImage: Bitmap, segmentationMask: SegmentationMask): Bitmap {
        val maskBuffer = segmentationMask.buffer
        val maskWidth = segmentationMask.width
        val maskHeight = segmentationMask.height

        // Resize the original image to match the mask dimensions
        val resizedOriginalImage = Bitmap.createScaledBitmap(originalImage, maskWidth, maskHeight, true)

        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)

        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                // Gets the confidence of the (x, y) pixel in the mask being in the foreground.
                val foregroundConfidence = maskBuffer.getFloat()

                // Set the pixel color based on the confidence
                val pixelColor = if (foregroundConfidence > 0.5f) {
                    // Set color for the foreground (person's face)
                    resizedOriginalImage.getPixel(x, y)
                } else {
                    // Set color for the background (original color)
                    // You can use the original color or set it to a different color if needed.
                    Color.WHITE
                }

                // Set the pixel color to the Bitmap
                maskBitmap.setPixel(x, y, pixelColor)
            }
        }

        return maskBitmap
    }



}

