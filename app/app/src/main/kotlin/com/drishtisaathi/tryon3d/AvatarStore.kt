package com.drishtisaathi.tryon3d

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.drishtisaathi.engine.face.Landmark
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
 * The avatar's source data for ONE session: the cropped front photo (JPEG) and its landmarks. Kept in app-private
 * storage, AES-GCM encrypted with an Android Keystore key, deleted with the session (new customer, consent,
 * "Delete my 3D face"). Never uploaded, never part of the bridge profile or the PDFs. The mesh and texture are
 * rebuilt from it in memory.
 */
class AvatarStore(context: Context) {
    data class Avatar(
        val jpeg: ByteArray,
        /** The photo's crop inside the analysed image, normalised [x0, y0, x1, y1]. */
        val crop: FloatArray,
        val landmarks: List<Landmark>,
        val imageW: Int,
        val imageH: Int,
        val skinRgb: IntArray,
        val pdMm: Double?,
    )

    private val file = File(File(context.filesDir, "avatar").apply { mkdirs() }, "session.bin")

    val exists: Boolean get() = file.isFile

    fun save(a: Avatar) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(VERSION)
            o.writeInt(a.imageW); o.writeInt(a.imageH)
            a.crop.forEach { o.writeFloat(it) }
            a.skinRgb.forEach { o.writeInt(it) }
            o.writeDouble(a.pdMm ?: -1.0)
            o.writeInt(a.landmarks.size)
            a.landmarks.forEach { o.writeFloat(it.x); o.writeFloat(it.y); o.writeFloat(it.z) }
            o.writeInt(a.jpeg.size); o.write(a.jpeg)
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.doFinal(bos.toByteArray())
        val tmp = File(file.parentFile, "session.part")
        DataOutputStream(tmp.outputStream()).use { it.writeInt(c.iv.size); it.write(c.iv); it.write(sealed) }
        tmp.renameTo(file)
    }

    fun load(): Avatar? = runCatching {
        if (!file.isFile) return null
        val all = file.readBytes()
        val din = DataInputStream(all.inputStream())
        val iv = ByteArray(din.readInt()).also { din.readFully(it) }
        val body = all.copyOfRange(4 + iv.size, all.size)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(body)
        DataInputStream(plain.inputStream()).use { i ->
            check(i.readInt() == VERSION)
            val w = i.readInt(); val h = i.readInt()
            val crop = FloatArray(4) { i.readFloat() }
            val skin = IntArray(3) { i.readInt() }
            val pd = i.readDouble().takeIf { it > 0 }
            val lm = List(i.readInt()) { Landmark(i.readFloat(), i.readFloat(), i.readFloat()) }
            val jpeg = ByteArray(i.readInt()).also { i.readFully(it) }
            Avatar(jpeg, crop, lm, w, h, skin, pd)
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
        private const val VERSION = 1
    }
}
