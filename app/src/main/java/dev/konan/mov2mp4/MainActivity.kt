package dev.konan.mov2mp4

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.konan.mov2mp4.data.Converter
import dev.konan.mov2mp4.data.Job
import dev.konan.mov2mp4.data.JobState
import dev.konan.mov2mp4.ui.AppTheme
import dev.konan.mov2mp4.ui.HomeScreen
import dev.konan.mov2mp4.ui.NotifyOffer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) accept(intent)

        setContent {
            AppTheme {
                val jobs by Converter.jobs.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> if (uris.isNotEmpty()) Converter.enqueue(uris) }

                // Asked once, and only while a long conversion is running.
                val prefs = remember { getSharedPreferences("ui", MODE_PRIVATE) }
                var notifyAnswered by remember { mutableStateOf(prefs.getBoolean(KEY_NOTIFY_ANSWERED, false)) }
                val answered = {
                    notifyAnswered = true
                    prefs.edit().putBoolean(KEY_NOTIFY_ANSWERED, true).apply()
                }
                val notifyPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { answered() }
                val notifyOffer = if (
                    Build.VERSION.SDK_INT >= 33 && !notifyAnswered &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    NotifyOffer(
                        accept = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        dismiss = answered,
                    )
                } else null

                HomeScreen(
                    jobs = jobs,
                    onPick = {
                        try {
                            // Not "video/quicktime": Android lists most .mov files as video/mp4.
                            picker.launch(arrayOf("video/*"))
                        } catch (e: ActivityNotFoundException) {
                            toast("No file picker on this phone")
                        }
                    },
                    onOpen = ::play,
                    onShare = ::share,
                    notifyOffer = notifyOffer,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    override fun onStart() {
        super.onStart()
        App.visible = true
    }

    override fun onStop() {
        App.visible = false
        super.onStop()
    }

    /** Files handed over by another app: "Open with" or the share sheet. */
    private fun accept(intent: Intent?) {
        intent ?: return
        // Reopening from Recents replays the old intent; don't convert the file again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uris = buildList<Uri> {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let(::add)
                Intent.ACTION_SEND ->
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let(::addAll)
                else -> return
            }
            if (isEmpty()) {
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
                }
            }
        }
        if (uris.isNotEmpty()) Converter.enqueue(uris)
    }

    private fun play(job: Job) {
        val done = job.state as? JobState.Done ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(done.output, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No video player found")
        }
    }

    private fun share(jobs: List<Job>) {
        val outputs = jobs.mapNotNull { (it.state as? JobState.Done)?.output }
        if (outputs.isEmpty()) return
        val intent = if (outputs.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, outputs.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outputs))
        }
        intent.type = "video/mp4"
        intent.clipData = ClipData.newUri(contentResolver, "MP4", outputs.first()).apply {
            outputs.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val KEY_NOTIFY_ANSWERED = "notify_answered"
    }
}
