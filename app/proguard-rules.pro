# R8 规则（M6）
#
# Room / Compose / Navigation / DataStore 都自带 consumer rules，这里只补几条
# 本项目自己依赖"字符串名字"的地方。

# 枚举名会原样写进数据库（Converters 用 name 存取），
# R8 若把枚举常量名改成 a/b/c，旧数据就再也读不出来了。
-keepclassmembers enum com.mizhii.ledger.data.db.entity.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Room 实体/DAO 通过生成代码直接访问，类名不能动
-keep class com.mizhii.ledger.data.db.entity.** { *; }
-keep interface com.mizhii.ledger.data.db.dao.** { *; }

# 备份格式里用 `Enum.name` 做持久化标识，同上
-keepclassmembers class com.mizhii.ledger.domain.backup.** { *; }
-keepclassmembers class com.mizhii.ledger.crypto.** { *; }

# 异常类的 message 要能在 Log 里看到，别被改成空
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
