package com.example.smartclassifier.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.smartclassifier.data.util.FaceSwapper
import com.example.smartclassifier.domain.model.BodyPose
import com.example.smartclassifier.domain.model.FaceInfo
import com.example.smartclassifier.domain.model.Mood
import com.example.smartclassifier.domain.repository.FaceRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

class FaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : FaceRepository {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    // ---------------------------------------------------------------- load

    override suspend fun loadBitmap(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Unable to read image")

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.postRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return@withContext raw
        }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated != raw) raw.recycle()
        rotated
    }

    // ---------------------------------------------------------------- detect

    override suspend fun detectFaces(bitmap: Bitmap, analyzePose: Boolean): List<FaceInfo> {
        val faces = faceDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
            .sortedBy { it.boundingBox.centerX() }

        return faces.mapIndexed { index, face ->
            val box = clamp(face.boundingBox, bitmap.width, bitmap.height)
            val smile: Float? = face.smilingProbability
            FaceInfo(
                id = index + 1,
                boundingBox = box,
                rollAngle = face.headEulerAngleZ,
                smileProbability = smile,
                mood = moodOf(smile),
                pose = if (analyzePose) detectPose(bitmap, box, faces.size == 1) else BodyPose.UNKNOWN
            )
        }
    }

    private fun moodOf(smile: Float?): Mood = when {
        smile == null -> Mood.UNKNOWN
        smile >= 0.65f -> Mood.HAPPY
        smile >= 0.2f -> Mood.NEUTRAL
        else -> Mood.SAD
    }

    private fun clamp(r: Rect, w: Int, h: Int) = Rect(
        r.left.coerceIn(0, w - 1), r.top.coerceIn(0, h - 1),
        r.right.coerceIn(1, w), r.bottom.coerceIn(1, h)
    )

    // ---------------------------------------------------------------- pose

    private suspend fun detectPose(bitmap: Bitmap, face: Rect, wholeImage: Boolean): BodyPose {
        return try {
            val input = if (wholeImage) {
                bitmap
            } else {
                val fw = face.width()
                val fh = face.height()
                val left = (face.centerX() - fw * 2).coerceAtLeast(0)
                val top = (face.top - fh / 2).coerceAtLeast(0)
                val right = (face.centerX() + fw * 2).coerceAtMost(bitmap.width)
                val bottom = (face.bottom + fh * 7).coerceAtMost(bitmap.height)
                if (right - left < 32 || bottom - top < 32) return BodyPose.UNKNOWN
                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            }
            classifyPose(poseDetector.process(InputImage.fromBitmap(input, 0)).await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BodyPose.UNKNOWN
        }
    }

    private fun classifyPose(pose: Pose): BodyPose {
        fun point(type: Int): PointF? =
            pose.getPoseLandmark(type)?.takeIf { it.inFrameLikelihood >= 0.5f }?.position

        fun mid(a: PointF?, b: PointF?): PointF? =
            if (a != null && b != null) PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f) else a ?: b

        val shoulder = mid(point(PoseLandmark.LEFT_SHOULDER), point(PoseLandmark.RIGHT_SHOULDER))
            ?: return BodyPose.UNKNOWN
        val hip = mid(point(PoseLandmark.LEFT_HIP), point(PoseLandmark.RIGHT_HIP))
            ?: return BodyPose.UPPER_BODY_ONLY

        // Torso more horizontal than vertical -> lying
        if (abs(shoulder.x - hip.x) > abs(shoulder.y - hip.y)) return BodyPose.LYING

        val knee = mid(point(PoseLandmark.LEFT_KNEE), point(PoseLandmark.RIGHT_KNEE))
            ?: return BodyPose.UPPER_BODY_ONLY
        val ankle = mid(point(PoseLandmark.LEFT_ANKLE), point(PoseLandmark.RIGHT_ANKLE))

        val thigh = hypot(knee.x - hip.x, knee.y - hip.y)
        if (thigh < 1f) return BodyPose.UNKNOWN

        val verticalRatio = (knee.y - hip.y) / thigh          // ~1 = thigh vertical, ~0 = horizontal
        val kneeAngle = ankle?.let { angleAt(hip, knee, it) }  // ~180 = straight leg
        val straightLeg = kneeAngle == null || kneeAngle >= 135.0

        return if (verticalRatio >= 0.75f && straightLeg) BodyPose.STANDING else BodyPose.SITTING
    }

    private fun angleAt(a: PointF, b: PointF, c: PointF): Double {
        val ab = atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())
        val cb = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble())
        var deg = Math.toDegrees(abs(ab - cb))
        if (deg > 180.0) deg = 360.0 - deg
        return deg
    }

    // ---------------------------------------------------------------- edit

    override suspend fun swapFaces(base: Bitmap, first: FaceInfo, second: FaceInfo): Bitmap =
        withContext(Dispatchers.Default) {
            FaceSwapper.swap(
                base, first.boundingBox, first.rollAngle, second.boundingBox, second.rollAngle
            )
        }

    override suspend fun replaceFace(
        base: Bitmap,
        target: FaceInfo,
        sourceBitmap: Bitmap,
        sourceFace: FaceInfo
    ): Bitmap = withContext(Dispatchers.Default) {
        FaceSwapper.replace(
            base, target.boundingBox, target.rollAngle,
            sourceBitmap, sourceFace.boundingBox, sourceFace.rollAngle
        )
    }

    // ---------------------------------------------------------------- save

    override suspend fun saveBitmap(bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SmartClassifier_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmartClassifier")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create image file")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: throw IllegalStateException("Unable to write image")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }

    private companion object {
        const val MAX_DIMENSION = 1600
    }
}
