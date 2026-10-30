package com.mizhii.ledger.util

import java.security.MessageDigest

object Hashing {
    /** 内容哈希，用于幂等键 rowHash（§6.5.4） */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }
}
