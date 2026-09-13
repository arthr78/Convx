private fun exportDownloadToStorage(songId: String) {
        try {
            val songData = database.getSongByIdBlocking(songId)
            val format = runBlocking { database.format(songId).first() }
            val rawTitle = songData?.song?.title ?: "Track_$songId"
            val artistName = songData?.artists?.joinToString(", ") { it.name } ?: "Unknown Artist"
            val safeTitle = rawTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val safeArtist = artistName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val fileNameBase = "$safeArtist - $safeTitle"

            val mimeType = format?.mimeType ?: "audio/mp4"
            val extension = when {
                mimeType.contains("mp4") || mimeType.contains("m4a") -> "m4a"
                mimeType.contains("webm") || mimeType.contains("opus") -> "opus"
                mimeType.contains("flac") -> "flac"
                else -> "mp3"
            }
            val fileName = "$fileNameBase.$extension"

            val spans = downloadCache.getCachedSpans(songId).sortedBy { it.position }
            if (spans.isEmpty()) {
                Timber.w("No cached spans found to export for songId: $songId")
                return
            }

            val customUriStr = runBlocking {
                appContext.dataStore.data.map { it[androidx.datastore.preferences.core.stringPreferencesKey("download_directory_uri")] ?: "" }.first()
            }

            if (customUriStr.isNotBlank()) {
                val treeUri = Uri.parse(customUriStr)
                val docTreeId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val docTreeUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docTreeId)
                val targetDocUri = android.provider.DocumentsContract.createDocument(
                    appContext.contentResolver,
                    docTreeUri,
                    mimeType,
                    fileName
                )
                if (targetDocUri != null) {
                    appContext.contentResolver.openOutputStream(targetDocUri)?.use { outputStream ->
                        for (span in spans) {
                            span.file?.inputStream()?.use { it.copyTo(outputStream) }
                        }
                    }
                    Timber.d("Successfully exported $fileName to custom SAF tree: $targetDocUri")
                    return
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.TITLE, rawTitle)
                    put(MediaStore.Audio.Media.ARTIST, artistName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Convx")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val resolver = appContext.contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        for (span in spans) {
                            span.file?.inputStream()?.use { it.copyTo(outputStream) }
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    Timber.d("Successfully exported $fileName to MediaStore default path")
                }
            } else {
                val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Convx")
                if (!musicDir.exists()) musicDir.mkdirs()
                val targetFile = File(musicDir, fileName)

                FileOutputStream(targetFile).use { outputStream ->
                    for (span in spans) {
                        span.file?.inputStream()?.use { it.copyTo(outputStream) }
                    }
                }
                MediaScannerConnection.scanFile(appContext, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to export downloaded track $songId to storage")
        }
    }
