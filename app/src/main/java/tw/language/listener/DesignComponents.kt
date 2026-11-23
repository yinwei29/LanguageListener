@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.language.listener.i18n.UiStrings

@Composable internal fun tr(s: String)=UiStrings(LocalContext.current).message(s)
@Composable internal fun Panel(title: String,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(title.isNotEmpty()) Text(title,modifier=Modifier.semantics { heading() },style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            content()
        }
    }
}
@Composable internal fun Actions(content: @Composable FlowRowScope.()->Unit) { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content) }
@Composable internal fun StatusTag(label: String,literal: Boolean=false) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(20.dp)) { Text(if(literal) label else tr(label),Modifier.padding(horizontal=14.dp,vertical=7.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onPrimaryContainer) }
}
@Composable internal fun PrimaryAction(label: String,enabled: Boolean=true,onClick: ()->Unit) { Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)) { Text(tr(label),fontWeight=FontWeight.SemiBold) } }
@Composable internal fun MenuLink(title: String,detail: String,icon: ImageVector,literal: Boolean=false,onClick: ()->Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(18.dp).heightIn(min=54.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(12.dp)) { Icon(icon,null,Modifier.padding(10.dp).size(24.dp),tint=MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(if(literal) title else tr(title),style=MaterialTheme.typography.titleMedium);if(detail.isNotEmpty()) Text(if(literal) detail else tr(detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun EmptyCard(title: String,detail: String,icon: ImageVector) { Panel("") { Icon(icon,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary);Text(tr(title),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(tr(detail),color=MaterialTheme.colorScheme.onSurfaceVariant) } }
