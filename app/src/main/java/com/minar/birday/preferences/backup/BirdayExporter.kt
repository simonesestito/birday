package com.minar.birday.preferences.backup

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.sqlite.db.SimpleSQLiteQuery
import com.minar.birday.R
import com.minar.birday.activities.MainActivity
import com.minar.birday.persistence.EventDatabase
import com.minar.birday.utilities.shareFile
import java.io.File
import java.time.LocalDate


class BirdayExporter(context: Context, attrs: AttributeSet?) : Preference(context, attrs),
    View.OnClickListener {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val v = holder.itemView
        v.setOnClickListener(this)
    }

    // Vibrate, export a backup and immediately share it if possible
    override fun onClick(v: View) {
        val act = context as MainActivity
        act.vibrate()
        // Only export if there's at least one event
        if (act.mainViewModel.allEventsUnfiltered.value.isNullOrEmpty())
            act.showSnackbar(context.getString(R.string.no_events))
        else {
            val thread = Thread {
                val exported = exportEvents(context)
                if (exported.isNotBlank()) shareFile(context, exported)
            }
            thread.start()
        }
    }

    // Export the room database to a file in Android/data/com.minar.birday/files
    private fun exportEvents(context: Context): String {
        // Perform a checkpoint to empty the write ahead logging temporary files and avoid closing the entire db
        val eventDao = EventDatabase.getBirdayDatabase(context).eventDao()
        eventDao.checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(full)"))

        val dbFile = context.getDatabasePath("BirdayDB").absoluteFile
        val appDirectory = File(context.getExternalFilesDir(null)!!.absolutePath)
        val fileName = "BirdayBackup_${LocalDate.now()}"
        val fileFullPath: String = appDirectory.path + File.separator + fileName
        // Snackbar need the UI thread to work, so they must be forced on that thread
        try {
            dbFile.copyTo(File(fileFullPath), true)
            (context as MainActivity).runOnUiThread { context.showSnackbar(context.getString(R.string.birday_export_success)) }
        } catch (e: Exception) {
            (context as MainActivity).runOnUiThread {
                context.showSnackbar(context.getString(R.string.birday_export_failure))
            }
            e.printStackTrace()
            return ""
        }
        return fileFullPath
    }
}