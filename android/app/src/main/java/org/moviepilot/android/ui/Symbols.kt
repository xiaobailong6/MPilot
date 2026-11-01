package org.moviepilot.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 把网页声明的 SF Symbol 名称映射到 Material 图标。
 *
 * 只覆盖 MoviePilot-Frontend 目前实际使用的一小部分；返回 null 时调用方改用文字标签，
 * 因此新增图标不需要改协议，也不会因为缺失图标而丢功能。
 */
fun symbolIcon(name: String): ImageVector? = when (name) {
    "magnifyingglass", "magnifyingglass.circle" -> Icons.Filled.Search
    "bell", "bell.fill", "bell.badge" -> Icons.Filled.Notifications
    "person", "person.fill", "person.crop.circle", "person.2", "account" -> Icons.Filled.Person
    "gearshape", "gearshape.fill", "gear" -> Icons.Filled.Settings
    "ellipsis", "ellipsis.circle" -> Icons.Filled.MoreVert
    "arrow.clockwise", "arrow.clockwise.circle" -> Icons.Filled.Refresh
    "arrow.left", "chevron.left", "chevron.backward" -> Icons.Filled.ArrowBack
    "chevron.right", "chevron.forward", "arrow.right" -> Icons.Filled.KeyboardArrowRight
    "checkmark", "checkmark.circle" -> Icons.Filled.Check
    "xmark", "xmark.circle", "close" -> Icons.Filled.Close
    "house", "house.fill", "home" -> Icons.Filled.Home
    "star", "star.fill", "star.circle" -> Icons.Filled.Star
    "play", "play.fill", "film", "film.fill", "video" -> Icons.Filled.PlayArrow
    "calendar", "clock", "clock.fill" -> Icons.Filled.DateRange
    "trash", "trash.fill" -> Icons.Filled.Delete
    "list.bullet", "list.dash", "list" -> Icons.Filled.List
    "square.and.arrow.up", "square.and.arrow.up.fill" -> Icons.Filled.Share
    "globe", "link", "network" -> Icons.Filled.Place
    "puzzlepiece", "puzzlepiece.extension", "wand.and.stars" -> Icons.Filled.Build
    "info.circle", "questionmark.circle" -> Icons.Filled.Info
    else -> null
}