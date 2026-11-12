package com.drishtisaathi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted Room database (SQLCipher).
 *
 * - The database passphrase is 32 random bytes (as 64 hex characters), made once per install. It is stored only
 *   encrypted, with an AES-256-GCM key that lives in the Android Keystore and never leaves it ([DbKey]).
 * - A database written by an earlier (plain SQLite) build is encrypted in place on first start ([encryptInPlace]):
 *   copied with `sqlcipher_export` into a new encrypted file, checked (same tables, same row counts, same schema
 *   version), and only then swapped in. On any failure the plain file is left untouched and opened as before, and the
 *   next start tries again, so no session is lost.
 * - android:allowBackup is false, so the encrypted file and its wrapped key never reach another phone.
 */
object DbEncryption {
    private const val TAG = "DbEncryption"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    enum class FileState { MISSING, PLAIN, ENCRYPTED }

    /** A plain SQLite file starts with "SQLite format 3\0"; an SQLCipher file starts with random bytes. */
    fun stateOf(header: ByteArray?): FileState = when {
        header == null || header.isEmpty() -> FileState.MISSING
        header.size >= SQLITE_HEADER.size && header.copyOf(SQLITE_HEADER.size).contentEquals(SQLITE_HEADER) -> FileState.PLAIN
        else -> FileState.ENCRYPTED
    }

    fun stateOf(file: File): FileState {
        if (!file.exists() || file.length() == 0L) return FileState.MISSING
        val head = ByteArray(SQLITE_HEADER.size)
        val n = file.inputStream().use { it.read(head) }
        return stateOf(if (n <= 0) null else head.copyOf(n))
    }

    /** `ATTACH DATABASE '<path>' AS encrypted KEY '<passphrase>'` with SQL quotes escaped. */
    fun attachSql(path: String, passphrase: String): String =
        "ATTACH DATABASE '${path.replace("'", "''")}' AS encrypted KEY '${passphrase.replace("'", "''")}'"

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Returns the passphrase Room should open [dbName] with. Encrypts a plain database first; if that fails, returns
     * an empty passphrase so SQLCipher opens the plain file unchanged (retried on the next start).
     */
    fun prepare(context: Context, dbName: String): ByteArray {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(dbName)
        val passphrase = runCatching { DbKey.passphrase(context) }.getOrElse { e ->
            // The Keystore key is gone (should not happen with allowBackup=false): an encrypted file can no longer be
            // read. Keep it aside rather than deleting it, and start with a fresh key and database.
            Log.e(TAG, "database key unreadable, starting a new key", e)
            if (stateOf(file) == FileState.ENCRYPTED) {
                val stamp = System.currentTimeMillis()
                listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).renameTo(File("${file.path}$it.unreadable-$stamp")) }
            }
            DbKey.reset(context)
            DbKey.passphrase(context)
        }
        return when (stateOf(file)) {
            FileState.MISSING, FileState.ENCRYPTED -> passphrase.toByteArray(Charsets.US_ASCII)
            FileState.PLAIN ->
                if (runCatching { encryptInPlace(file, passphrase) }.onFailure { Log.e(TAG, "encrypt failed, keeping plain DB", it) }.isSuccess) {
                    passphrase.toByteArray(Charsets.US_ASCII)
                } else ByteArray(0)
        }
    }

    /** Plain → encrypted copy → verified → swapped in. Throws (leaving [file] untouched) on any problem. */
    fun encryptInPlace(file: File, passphrase: String) {
        val tmp = File(file.parentFile, file.name + ".encrypting")
        tmp.delete()
        val counts: Map<String, Long>
        val version: Int
        SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null).let { plain ->
            try {
                // Room uses WAL: fold it into the main file so the export sees every committed row.
                plain.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray<String>()).use { it.moveToFirst() }
                version = plain.version
                counts = rowCounts(plain)
                plain.execSQL(attachSql(tmp.path, passphrase))
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
                plain.execSQL("PRAGMA encrypted.user_version = $version")
                plain.execSQL("DETACH DATABASE encrypted")
            } finally {
                plain.close()
            }
        }
        // verify the copy before touching the original
        SQLiteDatabase.openDatabase(tmp.path, passphrase, null, SQLiteDatabase.OPEN_READWRITE, null, null).let { enc ->
            try {
                check(enc.version == version) { "user_version ${enc.version} != $version" }
                val copied = rowCounts(enc)
                check(copied == counts) { "row counts differ: $copied vs $counts" }
            } finally {
                enc.close()
            }
        }
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        check(file.delete()) { "could not delete the plain database" }
        check(tmp.renameTo(file)) { "could not move the encrypted database into place" }
        Log.i(TAG, "database encrypted: ${counts.values.sum()} rows in ${counts.size} tables")
    }

    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            emptyArray<String>(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { t ->
            db.rawQuery("SELECT COUNT(*) FROM `${t.replace("`", "``")}`", emptyArray<String>()).use { c -> c.moveToFirst(); c.getLong(0) }
        }
    }
}

/** The database passphrase, stored wrapped by a non-exportable Android Keystore key. */
object DbKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "drishti_db_wrap"
    private const val PREFS = "db_key"
    private const val PREF_IV = "iv"
    private const val PREF_CT = "ct"

    @Synchronized
    fun passphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString(PREF_IV, null)
        val ct = prefs.getString(PREF_CT, null)
        if (iv != null && ct != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.US_ASCII)
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pass = DbEncryption.toHex(raw)
        raw.fill(0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val sealed = cipher.doFinal(pass.toByteArray(Charsets.US_ASCII))
        check(
            prefs.edit()
                .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(PREF_CT, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .commit(),
        ) { "could not store the database key" }
        return pass
    }

    /** Forgets the stored passphrase and the Keystore key (only used when the old one cannot be read). */
    @Synchronized
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
