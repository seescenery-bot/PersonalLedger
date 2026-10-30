package com.mizhii.ledger.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mizhii.ledger.R
import com.mizhii.ledger.ui.backup.BackupScreen
import com.mizhii.ledger.ui.category.CategoryManageScreen
import com.mizhii.ledger.ui.diag.DiagnosticsScreen
import com.mizhii.ledger.ui.home.HomeScreen
import com.mizhii.ledger.ui.imports.ImportScreen
import com.mizhii.ledger.ui.lab.ParserLabScreen
import com.mizhii.ledger.ui.pending.PendingScreen
import com.mizhii.ledger.ui.rule.RuleManageScreen
import com.mizhii.ledger.ui.settings.SettingsScreen
import com.mizhii.ledger.ui.stats.StatsScreen
import kotlinx.coroutines.flow.SharedFlow

object Routes {
    const val TXN = "txn"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val RULES = "rules"
    const val PENDING = "pending"
    const val PARSER_LAB = "parser_lab"
    const val DIAGNOSTICS = "diagnostics"
    const val IMPORT = "import"
    const val BACKUP = "backup"
}

private data class RootTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val route: String
)

private val RootTabs = listOf(
    RootTab(R.string.tab_txn, Icons.Filled.Home, Routes.TXN),
    RootTab(R.string.tab_stats, Icons.Filled.DateRange, Routes.STATS),
    RootTab(R.string.tab_settings, Icons.Filled.Settings, Routes.SETTINGS)
)

private val RootRoutes = RootTabs.map { it.route }.toSet()

/**
 * 应用根容器（文档 §10.3 导航结构）。
 * 底部三 Tab 为一级页面，其余全部是全屏二级页面。
 *
 * @param startRoute 起始页。默认首页；从「待确认」记账通知点进来时直接落到 [Routes.PENDING]。
 * @param openPendingEvents App 已在运行时又点了记账通知 —— 单例 Activity 不会重建，
 *   只能靠这个流把事件送进来。
 */
@Composable
fun LedgerRoot(
    startRoute: String = Routes.TXN,
    openPendingEvents: SharedFlow<Unit>? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.TXN

    if (openPendingEvents != null) {
        LaunchedEffect(openPendingEvents) {
            openPendingEvents.collect {
                navController.navigate(Routes.PENDING) {
                    launchSingleTop = true
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in RootRoutes) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    RootTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelRes)
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.TXN) {
                HomeScreen(onOpenPending = { navController.navigate(Routes.PENDING) })
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenRules = { navController.navigate(Routes.RULES) },
                    onOpenPending = { navController.navigate(Routes.PENDING) },
                    onOpenParserLab = { navController.navigate(Routes.PARSER_LAB) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) }
                )
            }
            composable(Routes.CATEGORIES) {
                CategoryManageScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.RULES) {
                RuleManageScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PENDING) {
                PendingScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PARSER_LAB) {
                ParserLabScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.IMPORT) {
                ImportScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.BACKUP) {
                BackupScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
