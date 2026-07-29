package com.biliscraper.android.utils

import com.biliscraper.android.api.ScrapedComment
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object CsvWriter {

    private val HEADERS = listOf(
        "rpid",
        "parent_rpid",
        "username",
        "content",
        "like_count",
        "timestamp",
        "ip_location"
    )

    fun writeToCsvFile(comments: List<ScrapedComment>, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            // Write UTF-8 BOM so Chinese characters render correctly in Excel
            fos.write(0xEF)
            fos.write(0xBB)
            fos.write(0xBF)
            
            BufferedWriter(OutputStreamWriter(fos, StandardCharsets.UTF_8)).use { writer ->
                // Header
                writer.write(HEADERS.joinToString(",") { escapeCsv(it) })
                writer.newLine()

                // Data Rows
                for (cmt in comments) {
                    val row = cmt.toCsvRow()
                    writer.write(row.joinToString(",") { escapeCsv(it) })
                    writer.newLine()
                }
                writer.flush()
            }
        }
    }

    private fun escapeCsv(value: String): String {
        var v = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
        if (v.contains(",") || v.contains("\"") || v.contains(";")) {
            v = "\"" + v.replace("\"", "\"\"") + "\""
        }
        return v
    }
}
