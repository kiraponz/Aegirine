package com.kira3.ametrine.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import com.kira3.ametrine.ui.settings.BackButton
import com.kira3.ametrine.ui.settings.listPadding
import com.kira3.ametrine.ui.settings.segmentedShapes
import com.kira3.ametrine.ui.settings.settingsListColors
import com.kira3.ametrine.ui.settings.settingsTopBarColors

/** What the app includes from others, by group; a row opens its license. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LicensesScreen(onOpenLicense: (id: String) -> Unit, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = { BackButton(onBack) },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = listPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            for ((group, components) in openSourceComponents) {
                item(key = "header:${group.name}") {
                    Text(
                        stringResource(group.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(components, key = { _, component -> "component:${component.id}" }) { index, component ->
                    SegmentedListItem(
                        onClick = { onOpenLicense(component.id) },
                        shapes = segmentedShapes(index, components.size),
                        colors = settingsListColors(),
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        supportingContent = { Text(component.license) },
                    ) { Text(component.name) }
                }
            }
        }
    }
}

/** One component's copyright notice and license text. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LicenseScreen(id: String, onBack: () -> Unit) {
    val component = openSourceComponent(id)
    val context = LocalContext.current
    val text by produceState<String?>(null, component) {
        value = component?.let { runCatching { licenseText(context, it) }.getOrDefault("") }
    }
    val openLink = rememberOpenLink()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(component?.name.orEmpty(), maxLines = 2) },
                subtitle = component?.let { { Text(it.license, maxLines = 1) } },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (component != null) {
                        TextButton(onClick = { openLink(component.url) }) {
                            Text(stringResource(R.string.licenses_open_site))
                        }
                    }
                },
                colors = settingsTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val body = text
        if (body == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        LazyColumn(contentPadding = listPadding(innerPadding), modifier = Modifier.fillMaxSize()) {
            component?.notice?.let { notice ->
                item(key = "notice") {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
                    )
                }
            }
            // One item per paragraph, so a long text (the GPL is ~700 lines) is laid out lazily.
            val paragraphs = paragraphs(body)
            itemsIndexed(paragraphs) { index, paragraph ->
                SelectionContainer {
                    Text(
                        paragraph,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceBright, paragraphShape(index, paragraphs.size))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .padding(top = if (index == 0) 8.dp else 0.dp, bottom = if (index == paragraphs.lastIndex) 8.dp else 0.dp),
                    )
                }
            }
        }
    }
}

/** The text's paragraphs, each prose paragraph's hard line breaks undone so it wraps to the screen. */
private fun paragraphs(text: String): List<String> =
    text.split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }.map { paragraph ->
        // Whole texts are often indented (the GPL by two spaces); only indentation beyond that counts.
        val lines = paragraph.trimIndent().lines()
        // Lists, tables and indented blocks keep their lines.
        val laidOut = lines.drop(1).any { line ->
            line.startsWith(" ") || line.startsWith("\t") || listMarker.containsMatchIn(line)
        }
        if (laidOut) lines.joinToString("\n") else lines.joinToString(" ") { it.trim() }
    }

private val listMarker = Regex("^\\s*([-*•]|\\(?[0-9a-z]{1,3}[.)])\\s")

/** The paragraphs read as one card: rounded at its top and bottom only. */
@Composable
private fun paragraphShape(index: Int, count: Int): Shape {
    val radius = 16.dp
    return RoundedCornerShape(
        topStart = if (index == 0) radius else 0.dp,
        topEnd = if (index == 0) radius else 0.dp,
        bottomStart = if (index == count - 1) radius else 0.dp,
        bottomEnd = if (index == count - 1) radius else 0.dp,
    )
}
