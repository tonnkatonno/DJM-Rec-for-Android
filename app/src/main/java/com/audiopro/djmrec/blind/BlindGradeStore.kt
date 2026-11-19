package com.audiopro.djmrec.blind

import android.content.Context
import android.util.Log
import com.audiopro.djmrec.storage.LibraryRecording
import com.audiopro.djmrec.storage.RecordingLibrary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The car's grades, one small text file per list in app-private storage ([GradeCodec]). */
internal object BlindGradeStore {
    private const val TAG = "BlindGradeStore"

    private fun file(context: Context, listId: String): File =
        File(File(context.filesDir, "blind").apply { mkdirs() }, "${squashName(listId)}.tsv")

    fun load(context: Context, list: BlindList): BlindSession {
        val entries = runCatching { file(context, list.id).takeIf { it.isFile }?.readText()?.let(GradeCodec::decode) }
            .onFailure { Log.w(TAG, "could not read the grades of ${list.id}", it) }
            .getOrNull().orEmpty()
        return BlindSession(list, entries)
    }

    /** Writes a snapshot ([BlindSession.entries]) atomically: a half-written file never replaces the last good one. */
    fun save(context: Context, listId: String, entries: Map<Int, GradeEntry>) {
        runCatching {
            val target = file(context, listId)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(GradeCodec.encode(entries))
            check(temp.renameTo(target)) { "rename failed" }
        }.onFailure { Log.e(TAG, "could not store the grades of $listId", it) }
    }

    /** For the diagnostic report: every list's grades, so the user can send them back with one tap. */
    fun appendReport(context: Context, sb: StringBuilder) {
        sb.appendLine("=== Blind grades (vak próba, Android Auto) ===")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (list in BlindLists.all) {
            val session = load(context, list)
            val recording = runCatching { BlindRecordings.find(context, list) }.getOrNull()
            sb.appendLine("list ${list.id} (${list.match}): ${session.done}/${session.total} graded; recording on the phone: " +
                (recording?.displayName ?: "not found"))
            session.reportLines { format.format(Date(it)) }.forEach(sb::appendLine)
        }
        sb.appendLine()
    }
}

/** Finds a list's recording among the phone's sets (Music/DJMRec), by its name. */
internal object BlindRecordings {
    fun find(context: Context, list: BlindList): LibraryRecording? {
        val want = squashName(list.match)
        // The exact name only: an edited copy ("… (edit)") may be cut, and then the moments' times would be wrong.
        return RecordingLibrary.list(context).filter { squashName(it.title) == want }.maxByOrNull { it.size }
    }
}
