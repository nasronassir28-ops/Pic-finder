package com.example.photorecovery

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val found = mutableStateListOf<Found>()
    private val selected = mutableStateListOf<String>()
    private var status by mutableStateOf("اضغط «بدء الفحص» للبحث عن الصور المحذوفة")
    private var busy by mutableStateOf(false)
    private var pendingTrashKeys: List<String> = emptyList()

    private val trashLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            if (r.resultCode == RESULT_OK) {
                found.removeAll { it.key in pendingTrashKeys }
                status = "تمت استعادة الصور من السلة إلى المعرض"
            } else status = "أُلغيت العملية"
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startScan() else status = "الإذن مطلوب لإجراء الفحص"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { Screen() }
                }
            }
        }
    }

    private fun ensureAccessThenScan() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            status = "فعّل «الوصول إلى كل الملفات» ثم ارجع واضغط بدء الفحص"
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(perm); return
        }
        startScan()
    }

    private fun startScan() {
        lifecycleScope.launch {
            busy = true; found.clear(); selected.clear(); status = "جارٍ الفحص…"
            val res = withContext(Dispatchers.IO) {
                Scanner.trash(this@MainActivity) + Scanner.files(this@MainActivity)
            }
            found.addAll(res.distinctBy { it.key })
            status = if (found.isEmpty()) "لم يتم العثور على صور قابلة للاستعادة"
            else "تم العثور على ${found.size} صورة"
            busy = false
        }
    }

    private fun restore() {
        val sel = found.filter { it.key in selected }
        val trashed = sel.filter { it.trashed && it.uri != null }
        val files = sel.filter { !it.trashed && it.file != null }
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { files.count { Scanner.copyOut(this@MainActivity, it.file!!) } }
            found.removeAll(files.toSet()); selected.removeAll(files.map { it.key }.toSet())
            if (files.isNotEmpty()) status = "تمت استعادة $n صورة إلى Pictures/Recovered"
            if (trashed.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                pendingTrashKeys = trashed.map { it.key }
                val pi = MediaStore.createTrashRequest(contentResolver, trashed.map { it.uri!! }, false)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
        }
    }

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("استعادة الصور المحذوفة", style = MaterialTheme.typography.headlineSmall)
            Text(status, Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ensureAccessThenScan() }, enabled = !busy) { Text("بدء الفحص") }
                OutlinedButton(
                    onClick = { selected.clear(); selected.addAll(found.map { it.key }) },
                    enabled = found.isNotEmpty()
                ) { Text("تحديد الكل") }
                Button(onClick = { restore() }, enabled = selected.isNotEmpty()) {
                    Text("استعادة (${selected.size})")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(found, key = { it.key }) { f ->
                    val isSel = f.key in selected
                    Box(
                        Modifier.aspectRatio(1f).clickable {
                            if (isSel) selected.remove(f.key) else selected.add(f.key)
                        }
                    ) {
                        AsyncImage(
                            model = f.file ?: f.uri, contentDescription = f.name,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                        if (isSel) Box(
                            Modifier.fillMaxSize().background(Color(0x663366FF)),
                            contentAlignment = Alignment.Center
                        ) { Text("✓", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
                    }
                }
            }
        }
    }
}
