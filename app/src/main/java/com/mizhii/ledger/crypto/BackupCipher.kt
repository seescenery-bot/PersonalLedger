package com.mizhii.ledger.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 口令错误（GCM 认证失败）—— 与"文件损坏"必须区分开，提示语不一样 */
class WrongPasswordException(cause: Throwable? = null) : Exception("口令错误", cause)

/** 文件格式不认识 / 被截断 */
class CorruptBackupException(message: String) : Exception(message)

/**
 * 备份文件加密（M5 §7.6）。
 *
 * 方案：**PBKDF2-HMAC-SHA256 派生密钥 + AES-256-GCM**。
 *
 * 为什么不用 Android Keystore：
 *  - Keystore 的密钥绑定设备，换机后解不开，备份就失去意义；
 *  - 用户的真实威胁是"手机丢了 / 刷机了 / 误删了 App"，不是本地取证。
 *
 * 为什么用 GCM 而不是 CBC：
 *  - GCM 自带认证标签，**篡改一定会被发现**（CBC 需要自己加 HMAC，容易写错）；
 *  - 反过来说：口令错和文件被改，都会以同一个 AEADBadTagException 表现出来，
 *    这在密码学上是正确行为（不泄露"差一点就对了"这种信息）。
 *
 * 文件二进制布局（全部大端）：
 * ```
 *  0..3   "JZBK"            magic
 *  4      formatVersion = 1
 *  5..8   iterations        PBKDF2 迭代次数
 *  9      saltLen = 16
 * 10..25  salt
 * 26     ivLen = 12
 * 27..38  iv
 * 39..    密文 + 16 字节 GCM tag
 * ```
 */
object BackupCipher {

    /** 文件魔数：看到它就知道这是简账的备份 */
    private val MAGIC = byteArrayOf('J'.code.toByte(), 'Z'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())

    const val FORMAT_VERSION: Int = 1

    /** PBKDF2 迭代次数。手机上一次约 150~400ms，足够不可接受"猜"。 */
    const val DEFAULT_ITERATIONS: Int = 200_000

    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    private const val HEADER_LEN = 4 + 1 + 4 + 1 + SALT_LEN + 1 + IV_LEN // 39

    private val random = SecureRandom()

    fun encrypt(password: CharArray, plaintext: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(iterations > 0) { "iterations 必须为正" }
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val key = deriveKey(password, salt, iterations)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plaintext)

        val out = ByteArray(HEADER_LEN + body.size)
        var p = 0
        MAGIC.copyInto(out, p); p += 4
        out[p++] = FORMAT_VERSION.toByte()
        putInt(out, p, iterations); p += 4
        out[p++] = SALT_LEN.toByte()
        salt.copyInto(out, p); p += SALT_LEN
        out[p++] = IV_LEN.toByte()
        iv.copyInto(out, p); p += IV_LEN
        body.copyInto(out, p)
        return out
    }

    fun decrypt(password: CharArray, blob: ByteArray): ByteArray {
        val header = parseHeader(blob)
        val key = deriveKey(password, header.salt, header.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.iv))
        return try {
            cipher.doFinal(blob, HEADER_LEN, blob.size - HEADER_LEN)
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException 落在这里：口令错，或文件被改过
            throw WrongPasswordException(e)
        }
    }

    /** 不做解密，只读文件头 —— 用来在提示"选择文件"的阶段判断"这是不是简账的备份" */
    fun looksLikeBackup(blob: ByteArray): Boolean = runCatching { parseHeader(blob); true }.getOrDefault(false)

    private data class Header(val iterations: Int, val salt: ByteArray, val iv: ByteArray)

    private fun parseHeader(blob: ByteArray): Header {
        if (blob.size < HEADER_LEN) {
            throw CorruptBackupException("文件太小，不是有效的备份文件（${blob.size} 字节）")
        }
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) throw CorruptBackupException("文件头不是 JZBK，选错文件了？")
        }
        val version = blob[4].toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw CorruptBackupException("备份格式版本 $version 不受支持（本版本只认 $FORMAT_VERSION）")
        }
        val iterations = getInt(blob, 5)
        // 真实备份固定用 200k，这里给的区间只是"明显的垃圾头部"兜底；
        // 真正的防篡改由 GCM 认证标签负责。下限留到 1000 是为了单测能用低迭代跑得快。
        if (iterations !in 1_000..10_000_000) {
            throw CorruptBackupException("迭代次数异常：$iterations")
        }
        var p = 9
        val saltLen = blob[p++].toInt() and 0xFF
        if (saltLen != SALT_LEN) throw CorruptBackupException("salt 长度异常：$saltLen")
        val salt = blob.copyOfRange(p, p + SALT_LEN); p += SALT_LEN
        val ivLen = blob[p++].toInt() and 0xFF
        if (ivLen != IV_LEN) throw CorruptBackupException("iv 长度异常：$ivLen")
        val iv = blob.copyOfRange(p, p + IV_LEN)
        return Header(iterations, salt, iv)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun putInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    private fun getInt(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
}
