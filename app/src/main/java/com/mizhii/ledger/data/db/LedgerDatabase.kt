package com.mizhii.ledger.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.mizhii.ledger.data.db.dao.CategoryDao
import com.mizhii.ledger.data.db.dao.IgnoreEntryDao
import com.mizhii.ledger.data.db.dao.ImportBatchDao
import com.mizhii.ledger.data.db.dao.RawCaptureDao
import com.mizhii.ledger.data.db.dao.RuleDao
import com.mizhii.ledger.data.db.dao.TxnDao
import com.mizhii.ledger.data.db.entity.CategoryEntity
import com.mizhii.ledger.data.db.entity.IgnoreEntryEntity
import com.mizhii.ledger.data.db.entity.ImportBatchEntity
import com.mizhii.ledger.data.db.entity.RawCaptureEntity
import com.mizhii.ledger.data.db.entity.RuleEntity
import com.mizhii.ledger.data.db.entity.TxnEntity

/**
 * 本地数据库（文档 §8）。
 *
 * 注意：**不使用** fallbackToDestructiveMigration。正式数据不允许因为版本号变化被清空，
 * 每次改表都必须写 Migration。
 */
@Database(
    entities = [
        TxnEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        RawCaptureEntity::class,
        ImportBatchEntity::class,
        IgnoreEntryEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun txnDao(): TxnDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun ignoreEntryDao(): IgnoreEntryDao

    companion object {
        const val DB_NAME = "ledger.db"

        fun build(context: Context): LedgerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                DB_NAME
            ).build()
    }
}
