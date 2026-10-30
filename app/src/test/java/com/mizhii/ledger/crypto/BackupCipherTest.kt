package com.mizhii.ledger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份加密层（M5 §7.6）。
 *
 * 这里必须回答三个问题：
 *  1. 对的密码一定能解开；
 *  2. 错的密码一定解不开，而且**不会**静默返回半截数据；
 *  3. 密文被改过一个字节也一定被发现（GCM 认证标签）。
 */
class BackupCipherTest {

    private val password = "correct horse battery staple".toCharArray()

    /** 测试里用低迭代跑得快；默认 200k 迭代单独测一次 */
    private val fast = 1_000

    @Test
    fun `加密后能原样解回`() {
        val plain = "简账 备份 正文 \u001F 含分隔符".toByteArray(Charsets.UTF_8)
        val blob = BackupCipher.encrypt(password, plain, fast)
        assertArrayEquals(plain, BackupCipher.decrypt(password, blob))
    }

    @Test
    fun `空内容也能往返`() {
        val blob = BackupCipher.encrypt(password, ByteArray(0), fast)
        assertArrayEquals(ByteArray(0), BackupCipher.decrypt(password, blob))
    }

    @Test
    fun `大块内容能往返`() {
        val plain = ByteArray(200_000) { (it % 251).toByte() }
        val blob = BackupCipher.encrypt(password, plain, fast)
        assertArrayEquals(plain, BackupCipher.decrypt(password, blob))
    }

    @Test
    fun `默认迭代次数也能往返`() {
        val plain = "正式参数".toByteArray(Charsets.UTF_8)
        val blob = BackupCipher.encrypt(password, plain)
        assertArrayEquals(plain, BackupCipher.decrypt(password, blob))
    }

    @Test
    fun `口令错必须抛 WrongPasswordException`() {
        val blob = BackupCipher.encrypt(password, "机密".toByteArray(), fast)
        val wrong = "wrong password".toCharArray()
        val e = runCatching { BackupCipher.decrypt(wrong, blob) }.exceptionOrNull()
        assertTrue("应抛 WrongPasswordException，实际 $e", e is WrongPasswordException)
    }

    @Test
    fun `密文被改一个字节必须被发现`() {
        val blob = BackupCipher.encrypt(password, "机密内容".toByteArray(), fast)
        for (pos in intArrayOf(blob.size - 1, blob.size - 17, blob.size / 2, 39)) {
            val tampered = blob.copyOf()
            tampered[pos] = (tampered[pos].toInt() xor 0x01).toByte()
            val e = runCatching { BackupCipher.decrypt(password, tampered) }.exceptionOrNull()
            assertTrue("第 $pos 字节被改后应拒绝解密，实际 $e", e is WrongPasswordException)
        }
    }

    @Test
    fun `头部被改也必须被发现`() {
        val blob = BackupCipher.encrypt(password, "机密".toByteArray(), fast)
        // 改 salt：派生出的密钥变了，认证必然失败
        val tampered = blob.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0xFF).toByte()
        val e = runCatching { BackupCipher.decrypt(password, tampered) }.exceptionOrNull()
        assertTrue("salt 被改应拒绝，实际 $e", e is WrongPasswordException)
    }

    @Test
    fun `同一明文两次加密结果不同`() {
        val plain = "同样的话".toByteArray()
        val a = BackupCipher.encrypt(password, plain, fast)
        val b = BackupCipher.encrypt(password, plain, fast)
        assertNotEquals("salt 与 iv 都是随机的，密文不应相同", a.toList(), b.toList())
        assertArrayEquals(plain, BackupCipher.decrypt(password, a))
        assertArrayEquals(plain, BackupCipher.decrypt(password, b))
    }

    @Test
    fun `截断的文件被识别为损坏而不是口令错`() {
        val blob = BackupCipher.encrypt(password, "内容".toByteArray(), fast)
        val e = runCatching { BackupCipher.decrypt(password, blob.copyOf(20)) }.exceptionOrNull()
        assertTrue("应抛 CorruptBackupException，实际 $e", e is CorruptBackupException)
    }

    @Test
    fun `不是备份文件要被明确拒绝`() {
        assertFalse(BackupCipher.looksLikeBackup("这是一段普通文本".toByteArray()))
        assertFalse(BackupCipher.looksLikeBackup(ByteArray(0)))
        val ok = BackupCipher.encrypt(password, "x".toByteArray(), fast)
        assertTrue(BackupCipher.looksLikeBackup(ok))

        val e = runCatching { BackupCipher.decrypt(password, "普通文本".toByteArray()) }.exceptionOrNull()
        assertTrue("应抛 CorruptBackupException，实际 $e", e is CorruptBackupException)
    }

    @Test
    fun `未来版本的格式要拒绝并提示升级`() {
        val blob = BackupCipher.encrypt(password, "x".toByteArray(), fast)
        val tampered = blob.copyOf()
        tampered[4] = 99
        val e = runCatching { BackupCipher.decrypt(password, tampered) }.exceptionOrNull()
        assertTrue("应抛 CorruptBackupException，实际 $e", e is CorruptBackupException)
        assertTrue("提示里应带版本信息", e!!.message!!.contains("99"))
    }
}
