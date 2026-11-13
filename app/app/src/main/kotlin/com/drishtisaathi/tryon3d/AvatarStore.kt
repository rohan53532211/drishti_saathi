package com.drishtisaathi.tryon3d

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.drishtisaathi.engine.face.Landmark
import com.drishtisaathi.engine.tryon3d.AvatarStep
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The avatar's source data for ONE session: the three guided photos (front, left, right, JPEG) with their landmarks,
 * transformation matrices and focal length. Kept in app-private storage, AES-GCM encrypted with an Android Keystore
 * key, deleted with the session (new customer, consent, "Delete 3D face", app start). Never uploaded, never part of
 * the bridge profile or the PDFs. The mesh and texture are rebuilt from it in memory.
 */
class AvatarStore(context: Context) {
    class View(
        val step: AvatarStep,
        val jpeg: ByteArray,
        val landmarks: List<Landmark>,
        val imageW: Int,
        val imageH: Int,
        val matrix: FloatArray?,
        val focalPx: Double,
        /** [jpeg] is a face crop of the photo: its top-left in full-photo px and crop px per photo px. */
        val cropX: Double = 0.0,
        val cropY: Double = 0.0,
        val cropScale: Double = 1.0,
    )

    class Avatar(val views: List<View>, val pdMm: Double?)

    private val file = File(File(context.filesDir, "avatar").apply { mkdirs() }, "session.bin")

    val exists: Boolean get() = file.isFile

    fun save(a: Avatar) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(VERSION)
            o.writeDouble(a.pdMm ?: -1.0)
            o.writeInt(a.views.size)
            for (v in a.views) {
                o.writeInt(v.step.ordinal)
                o.writeInt(v.imageW); o.writeInt(v.imageH)
                o.writeDouble(v.focalPx)
                o.writeDouble(v.cropX); o.writeDouble(v.cropY); o.writeDouble(v.cropScale)
                o.writeBoolean(v.matrix != null)
                v.matrix?.forEach { o.writeFloat(it) }
                o.writeInt(v.landmarks.size)
                v.landmarks.forEach { o.writeFloat(it.x); o.writeFloat(it.y); o.writeFloat(it.z) }
                o.writeInt(v.jpeg.size); o.write(v.jpeg)
            }
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.doFinal(bos.toByteArray())
        val tmp = File(file.parentFile, "session.part")
        DataOutputStream(tmp.outputStream()).use { it.writeInt(c.iv.size); it.write(c.iv); it.write(sealed) }
        tmp.renameTo(file)
    }

    /** The saved avatar, or null (an unreadable or older file is deleted). */
    fun load(): Avatar? = runCatching {
        if (!file.isFile) return null
        val all = file.readBytes()
        val din = DataInputStream(all.inputStream())
        val iv = ByteArray(din.readInt()).also { din.readFully(it) }
        val body = all.copyOfRange(4 + iv.size, all.size)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(body)
        DataInputStream(plain.inputStream()).use { i ->
            check(i.readInt() == VERSION)
            val pd = i.readDouble().takeIf { it > 0 }
            val views = List(i.readInt()) {
                val step = AvatarStep.entries[i.readInt()]
                val w = i.readInt(); val h = i.readInt()
                val f = i.readDouble()
                val cx = i.readDouble(); val cy = i.readDouble(); val cs = i.readDouble()
                val m = if (i.readBoolean()) FloatArray(16) { i.readFloat() } else null
                val lm = List(i.readInt()) { Landmark(i.readFloat(), i.readFloat(), i.readFloat()) }
                val jpeg = ByteArray(i.readInt()).also { i.readFully(it) }
                View(step, jpeg, lm, w, h, m, f, cx, cy, cs)
            }
            Avatar(views, pd)
        }
    }.getOrElse { clear(); null }

    /** Deletes the photo and landmarks (overwritten first, then removed). */
    fun clear() {
        file.parentFile?.listFiles()?.forEach { f ->
            runCatching { f.writeBytes(ByteArray(f.length().toInt().coerceAtMost(4 shl 20))) }
            f.delete()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "drishti_avatar"
        private const val VERSION = 3
    }
}
