package com.bloatware.bingblop.ui.screens.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bloatware.bingblop.data.model.DebloatSnapshot
import com.bloatware.bingblop.ui.theme.AccentCyan
import com.bloatware.bingblop.ui.theme.BgCard
import com.bloatware.bingblop.ui.theme.BgSurface
import com.bloatware.bingblop.ui.theme.BorderGlass
import com.bloatware.bingblop.ui.theme.CleanGreen
import com.bloatware.bingblop.ui.theme.StatusBloat
import com.bloatware.bingblop.ui.theme.TextDim
import com.bloatware.bingblop.ui.theme.TextMain
import com.bloatware.bingblop.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebloatSnapshotSheet(
    snapshots: List<DebloatSnapshot>,
    onCreateSnapshot: (name: String) -> Unit,
    onRestoreSnapshot: (snapshot: DebloatSnapshot) -> Unit,
    onDeleteSnapshot: (id: String) -> Unit,
    onExportSnapshot: ((DebloatSnapshot) -> Unit)? = null,
    onImportSnapshot: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var newSnapshotName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface)
            .padding(20.dp)
            .testTag("debloat_snapshot_sheet")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Debloat State Snapshots",
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Capture, export, import, or rollback exact app debloat states",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onImportSnapshot != null) {
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                        modifier = Modifier.testTag("import_snapshot_button")
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Import", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = { isCreating = !isCreating },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("new_snapshot_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Snapshot", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isCreating) "Cancel" else "Create", color = BgSurface, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("Import Snapshot JSON", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste the exported JSON snapshot payload below:", color = TextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = importJsonText,
                            onValueChange = { importJsonText = it },
                            placeholder = { Text("{\"version\": 1, ...}", color = TextDim, fontSize = 12.sp) },
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = BorderGlass,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("import_json_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (importJsonText.isNotBlank()) {
                                onImportSnapshot?.invoke(importJsonText)
                                importJsonText = ""
                                showImportDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CleanGreen),
                        modifier = Modifier.testTag("submit_import_button")
                    ) {
                        Text("Import", color = BgSurface, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                },
                containerColor = BgSurface
            )
        }

        if (isCreating) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newSnapshotName,
                    onValueChange = { newSnapshotName = it },
                    placeholder = { Text("Snapshot label (e.g. Before Gaming Tweaks)", color = TextDim, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderGlass,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("snapshot_name_input")
                )
                Button(
                    onClick = {
                        onCreateSnapshot(newSnapshotName)
                        newSnapshotName = ""
                        isCreating = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CleanGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("save_snapshot_submit_button")
                ) {
                    Text("Save", color = BgSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (snapshots.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No saved snapshots yet. Create one before debloating to rollback anytime.",
                    color = TextDim,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(snapshots, key = { it.id }) { snapshot ->
                    SnapshotCardItem(
                        snapshot = snapshot,
                        onRestore = { onRestoreSnapshot(snapshot) },
                        onDelete = { onDeleteSnapshot(snapshot.id) },
                        onExport = if (onExportSnapshot != null) { { onExportSnapshot(snapshot) } } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotCardItem(
    snapshot: DebloatSnapshot,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onExport: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dateStr = remember(snapshot.timestamp) {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(snapshot.timestamp))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(1.dp, BorderGlass, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snapshot.name,
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$dateStr • ${snapshot.disabledPackages.size} frozen of ${snapshot.totalPackages} apps",
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onExport != null) {
                IconButton(
                    onClick = onExport,
                    modifier = Modifier.testTag("export_snapshot_${snapshot.id}")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Export JSON", tint = AccentCyan, modifier = Modifier.size(18.dp))
                }
            }

            OutlinedButton(
                onClick = onRestore,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CleanGreen),
                modifier = Modifier.testTag("restore_snapshot_${snapshot.id}")
            ) {
                Icon(Icons.Default.Restore, contentDescription = "Restore", modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Restore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_snapshot_${snapshot.id}")
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StatusBloat, modifier = Modifier.size(18.dp))
            }
        }
    }
}
