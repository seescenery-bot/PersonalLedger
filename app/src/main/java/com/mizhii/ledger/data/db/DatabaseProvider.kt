package com.mizhii.ledger.data.db

import android.content.Context

/**
 * 数据库单例（M0 阶段的手写 DI）。
 * 若后续引入 Hilt，则删除此对象，改由 DatabaseModule 提供（§9.5）。
 */
object DatabaseProvider {

    @Volatile
    private var instance: LedgerDatabase? = null

    fun get(context: Context): LedgerDatabase =
        instance ?: synchronized(this) {
            instance ?: LedgerDatabase.build(context).also { instance = it }
        }
}
