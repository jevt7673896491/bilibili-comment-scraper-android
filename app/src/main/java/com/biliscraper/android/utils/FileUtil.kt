package com.biliscraper.android.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

object FileUtil {

    fun getDefaultSaveFolder(context: Context): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val target = File(docsDir, "BiliCommentScraper")
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    fun copyFileToSafFolder(context: Context, safTreeUri: Uri, fileName: String, mimeType: String, sourceFile: File): Uri? {
        try {
            val documentTree = DocumentFile.fromTreeUri(context, safTreeUri) ?: return null
            documentTree.findFile(fileName)?.delete()

            val newDoc = documentTree.createFile(mimeType, fileName) ?: return null
            context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            return newDoc.uri
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun shareFiles(context: Context, files: List<File>) {
        val uris = ArrayList<Uri>()
        for (f in files) {
            if (f.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f
                )
                uris.add(uri)
            }
        }
        if (uris.isEmpty()) return

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享数据文件"))
    }

    fun openFolder(context: Context, folder: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                folder
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "打开保存目录"))
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(folder.path), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(Intent.createChooser(fallbackIntent, "查看文件夹"))
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}
