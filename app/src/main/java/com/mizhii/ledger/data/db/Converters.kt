package com.mizhii.ledger.data.db

import androidx.room.TypeConverter
import com.mizhii.ledger.data.db.entity.CategorySource
import com.mizhii.ledger.data.db.entity.Channel
import com.mizhii.ledger.data.db.entity.Direction
import com.mizhii.ledger.data.db.entity.MatchField
import com.mizhii.ledger.data.db.entity.MatchType
import com.mizhii.ledger.data.db.entity.TimePrecision
import com.mizhii.ledger.data.db.entity.TxnSource
import com.mizhii.ledger.data.db.entity.TxnStatus

/**
 * 枚举一律以 name 字符串存储（§8.2）。
 * 禁止存 ordinal —— 枚举顺序一调整，历史数据就会错位。
 */
class Converters {

    @TypeConverter
    fun fromDirection(v: Direction?): String? = v?.name

    @TypeConverter
    fun toDirection(v: String?): Direction? = v?.let { safeEnum<Direction>(it) }

    @TypeConverter
    fun fromChannel(v: Channel?): String? = v?.name

    @TypeConverter
    fun toChannel(v: String?): Channel? = v?.let { safeEnum<Channel>(it) }

    @TypeConverter
    fun fromTxnSource(v: TxnSource?): String? = v?.name

    @TypeConverter
    fun toTxnSource(v: String?): TxnSource? = v?.let { safeEnum<TxnSource>(it) }

    @TypeConverter
    fun fromTxnStatus(v: TxnStatus?): String? = v?.name

    @TypeConverter
    fun toTxnStatus(v: String?): TxnStatus? = v?.let { safeEnum<TxnStatus>(it) }

    @TypeConverter
    fun fromCategorySource(v: CategorySource?): String? = v?.name

    @TypeConverter
    fun toCategorySource(v: String?): CategorySource? = v?.let { safeEnum<CategorySource>(it) }

    @TypeConverter
    fun fromTimePrecision(v: TimePrecision?): String? = v?.name

    @TypeConverter
    fun toTimePrecision(v: String?): TimePrecision? = v?.let { safeEnum<TimePrecision>(it) }

    @TypeConverter
    fun fromMatchField(v: MatchField?): String? = v?.name

    @TypeConverter
    fun toMatchField(v: String?): MatchField? = v?.let { safeEnum<MatchField>(it) }

    @TypeConverter
    fun fromMatchType(v: MatchType?): String? = v?.name

    @TypeConverter
    fun toMatchType(v: String?): MatchType? = v?.let { safeEnum<MatchType>(it) }

    private inline fun <reified T : Enum<T>> safeEnum(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()
}
