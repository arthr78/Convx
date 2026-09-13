private fun exportDownloadToStorage(songId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val songData = database.getSongByIdBlocking(songId)
                val format = database.format(songId).first()
                val rawTitle = songData?.song?.title ?: "Track_$songId"
                val artistName = songData?.artists?.joinToString(", ") { it.name } ?: "Unknown Artist"
                val albumName = songData?.album?.title ?: ""
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
                    return@launch
                }

                val customUriStr = appContext.dataStore.data.map {
                    it[androidx.datastore.preferences.core.stringPreferencesKey("download_directory_uri")] ?: ""
                }.first()

                var outputUri: Uri? = null

                if (customUriStr.isNotBlank()) {
                    runCatching {
                        val treeUri = Uri.parse(customUriStr)
                        val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, treeUri)
                        if (pickedDir != null && pickedDir.canWrite()) {
                            val existing = pickedDir.findFile(fileName)
                            existing?.delete()
                            val newFile = pickedDir.createFile(mimeType, fileName)
                            outputUri = newFile?.uri
                        }
                    }
                }

                if (outputUri != null) {
                    appContext.contentResolver.openOutputStream(outputUri)?.use { out ->
                        for (span in spans) {
                            span.file?.inputStream()?.use { it.copyTo(out) }
                        }
                    }
                    Timber.d("Successfully exported $fileName to custom folder: $outputUri")
                    return@launch
                }

                // Fallback to MediaStore with full metadata tags populated
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val durationMs = songData?.song?.duration?.takeIf { it > 0 }?.times(1000L)
                        ?: (format?.contentLength ?: 0L)

                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.TITLE, rawTitle)
                        put(MediaStore.Audio.Media.ARTIST, artistName)
                        if (albumName.isNotBlank()) {
                            put(MediaStore.Audio.Media.ALBUM, albumName)
                        }
                        if (durationMs > 0) {
                            put(MediaStore.Audio.Media.DURATION, durationMs)
                        }
                        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Convx")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }

                    val resolver = appContext.contentResolver
                    val uri: Uri? = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            for (span in spans) {
                                span.file?.inputStream()?.use { it.copyTo(out) }
                            }
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        Timber.d("Exported $fileName to MediaStore with metadata")
                    }
                } else {
                    val musicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Convx")
                    if (!musicDir.exists()) musicDir.mkdirs()
                    val targetFile = File(musicDir, fileName)

                    FileOutputStream(targetFile).use { out ->
                        for (span in spans) {
                            span.file?.inputStream()?.use { it.copyTo(out) }
                        }
                    }
                    MediaScannerConnection.scanFile(appContext, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to export downloaded track $songId to external storage")
            }
        }
    }
