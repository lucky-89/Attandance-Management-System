package com.finowise.hr.data

import com.finowise.hr.core.JSON
import com.finowise.hr.core.Persistence
import com.finowise.hr.core.model.HrData
import java.io.File

/** All HR data in one JSON file in the app's private storage, written atomically. */
class FilePersistence(dir: File) : Persistence {
    private val file = File(dir, "hr-data.json")
    private val tmp = File(dir, "hr-data.json.tmp")
    private val bak = File(dir, "hr-data.json.bak")

    override fun load(): HrData? {
        for (f in listOf(file, bak)) {
            if (!f.exists()) continue
            try { return JSON.decodeFromString(HrData.serializer(), f.readText()) } catch (_: Exception) { }
        }
        return null
    }

    @Synchronized
    override fun save(data: HrData) {
        tmp.writeText(JSON.encodeToString(HrData.serializer(), data))
        if (file.exists()) file.copyTo(bak, overwrite = true)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
