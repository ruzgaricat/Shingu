package com.example.shingu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.shingu.api.ApiClient
import com.example.shingu.api.CloudinaryConfig
import com.example.shingu.api.CloudinaryUploadResponse
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

// Galeriden seçilen görseli küçültüp Cloudinary'ye yükler ve herkese açık bağlantısını döndürür.
object ImageUploader {

    private const val MAX_SIDE_PX = 1600 // Uzun kenar en fazla 1600 px (banner ve ürün için yeterli)
    private const val JPEG_QUALITY = 85

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun upload(context: Context, uri: Uri, folder: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!CloudinaryConfig.isConfigured) {
            onFailure("Görsel yükleme henüz ayarlanmadı (CloudinaryConfig.kt).")
            return
        }
        val appContext = context.applicationContext

        // Görseli çözme/küçültme işlemi ana iş parçacığını kilitlemesin diye arka planda yapılır
        executor.execute {
            val bytes = try {
                compress(appContext, uri)
            } catch (e: Exception) {
                mainHandler.post { onFailure("Görsel okunamadı: ${e.localizedMessage}") }
                return@execute
            }
            mainHandler.post { send(bytes, folder, onSuccess, onFailure) }
        }
    }

    // Web API 4: Cloudinary'ye "unsigned" yükleme (multipart/form-data)
    private fun send(bytes: ByteArray, folder: String, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        val filePart = MultipartBody.Part.createFormData(
            "file", "image.jpg", RequestBody.create(MediaType.parse("image/jpeg"), bytes)
        )
        val text = MediaType.parse("text/plain")
        ApiClient.cloudinaryApi.upload(
            CloudinaryConfig.CLOUD_NAME,
            filePart,
            RequestBody.create(text, CloudinaryConfig.UPLOAD_PRESET),
            RequestBody.create(text, folder)
        ).enqueue(object : Callback<CloudinaryUploadResponse> {
            override fun onResponse(call: Call<CloudinaryUploadResponse>, response: Response<CloudinaryUploadResponse>) {
                val url = response.body()?.secure_url
                if (response.isSuccessful && url != null) {
                    onSuccess(url)
                } else {
                    // Cloudinary hata mesajı gövdede döner (örn. yanlış ön ayar adı)
                    onFailure("Yükleme reddedildi (${response.code()}): ${response.errorBody()?.string()?.take(150)}")
                }
            }

            override fun onFailure(call: Call<CloudinaryUploadResponse>, t: Throwable) {
                onFailure("Yüklenemedi: ${t.localizedMessage ?: "internet bağlantınızı kontrol edin"}")
            }
        })
    }

    // Görseli okur, fotoğrafın yönünü (EXIF) düzeltir, uzun kenarı MAX_SIDE_PX'e küçültür ve JPEG'e çevirir
    private fun compress(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver

        // 1) Önce sadece boyutları oku, belleğe sığacak şekilde örnekleme oranı seç
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_SIDE_PX) sampleSize *= 2

        // 2) Görseli küçültülmüş olarak oku
        var bitmap = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw IllegalArgumentException("Desteklenmeyen görsel biçimi")

        // 3) Telefon kamerasıyla çekilen fotoğraflar yan durmasın diye EXIF yönüne göre döndür
        val rotation = resolver.openInputStream(uri).use { stream ->
            when (stream?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }

        // 4) Tam boyuta ölçekle ve döndür
        val scale = MAX_SIDE_PX.toFloat() / max(bitmap.width, bitmap.height)
        if (rotation != 0f || scale < 1f) {
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0f) postRotate(rotation)
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}

// Formlardaki "Galeriden Seç" butonu: Android fotoğraf seçicisini açar (izin istemez),
// seçilen görseli yükler ve bağlantıyı onUploaded ile verir.
// Fragment oluşturulurken (alan olarak) tanımlanmalıdır; ActivityResult kaydı onCreate'ten önce yapılmalı.
class ImagePickerHelper(
    private val fragment: Fragment,
    private val folder: String,
    private val onUploaded: (String) -> Unit
) {
    private var button: Button? = null
    private var buttonText: CharSequence = ""

    private val launcher = fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) upload(uri)
    }

    fun attach(button: Button) {
        this.button = button
        buttonText = button.text
        button.setOnClickListener {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun upload(uri: Uri) {
        setUploading(true)
        ImageUploader.upload(fragment.requireContext(), uri, folder,
            onSuccess = { url ->
                setUploading(false)
                if (!fragment.isAdded) return@upload
                onUploaded(url)
                Toast.makeText(fragment.requireContext(), "Görsel yüklendi", Toast.LENGTH_SHORT).show()
            },
            onFailure = { message ->
                setUploading(false)
                if (fragment.isAdded) Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setUploading(uploading: Boolean) {
        if (!fragment.isAdded) return
        button?.isEnabled = !uploading
        button?.text = if (uploading) "Yükleniyor..." else buttonText
    }
}
