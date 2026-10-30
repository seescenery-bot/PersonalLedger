package com.mizhii.ledger.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类图标映射（文档 §7.4：内置图标供分类选择）。
 * key 与 `BuiltinData` / 数据库 `category.iconKey` 对应；未知 key 回落到 MoreHoriz，不崩溃。
 */
object CategoryIcons {

    private val icons: Map<String, ImageVector> = mapOf(
        // 支出
        "restaurant" to Icons.Filled.Restaurant,
        "directions_bus" to Icons.Filled.DirectionsBus,
        "shopping_bag" to Icons.Filled.ShoppingBag,
        "shopping_cart" to Icons.Filled.ShoppingCart,
        "home" to Icons.Filled.Home,
        "phone" to Icons.Filled.Phone,
        "sports_esports" to Icons.Filled.SportsEsports,
        "local_hospital" to Icons.Filled.LocalHospital,
        "school" to Icons.Filled.School,
        "fitness_center" to Icons.Filled.FitnessCenter,
        "trending_up" to Icons.Filled.TrendingUp,
        "swap_horiz" to Icons.Filled.SwapHoriz,
        "receipt_long" to Icons.Filled.ReceiptLong,
        "more_horiz" to Icons.Filled.MoreHoriz,
        // 收入
        "payments" to Icons.Filled.Payments,
        "undo" to Icons.Filled.Undo,
        "emoji_events" to Icons.Filled.EmojiEvents,
        "redeem" to Icons.Filled.Redeem,
        // 扩展可选图标
        "savings" to Icons.Filled.Savings,
        "wallet" to Icons.Filled.AccountBalanceWallet,
        "credit_card" to Icons.Filled.CreditCard,
        "sports" to Icons.Filled.SportsBasketball,
        "pets" to Icons.Filled.Pets,
        "flight" to Icons.Filled.Flight,
        "child_care" to Icons.Filled.ChildCare,
        "local_cafe" to Icons.Filled.LocalCafe,
        "movie" to Icons.Filled.Movie,
        "local_bar" to Icons.Filled.LocalBar,
        "local_pharmacy" to Icons.Filled.LocalPharmacy,
        "grocery" to Icons.Filled.LocalGroceryStore,
        "fastfood" to Icons.Filled.Fastfood,
        "clothing" to Icons.Filled.Checkroom,
        "gift" to Icons.Filled.CardGiftcard,
        "subscriptions" to Icons.Filled.Subscriptions,
        "car" to Icons.Filled.DirectionsCar,
        "commute" to Icons.Filled.Commute,
        "train" to Icons.Filled.Train,
        "bolt" to Icons.Filled.ElectricBolt,
        "build" to Icons.Filled.Build,
        "work" to Icons.Filled.Work
    )

    val availableKeys: List<String> = icons.keys.toList()

    fun of(key: String?): ImageVector =
        icons[key ?: ""] ?: Icons.Filled.MoreHoriz
}
