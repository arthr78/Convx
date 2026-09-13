/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.playback

import timber.log.Timber
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.music.innertube.YouTube
import com.convx.music.constants.AudioQuality
import com.convx.music.constants.AudioQualityKey
import com.convx.music.constants.IpVersionKey
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import com.convx.music.db.MusicDatabase
import com.convx.music.db.entities.FormatEntity
import com.convx.music.db.entities.SongEntity
import com.convx.music.di.DownloadCache
import com.convx.music.di.PlayerCache
import com.convx.music.ui.utils.resize
import com.convx.music.constants.AutoDownloadOnLikeKey
import com.convx.music.utils.YTPlayerUtils
import com.convx.music.utils.enumPreference
import com.convx.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.convx.music.applecanvas.AppleMusicCanvasProvider
import com.convx.music.canvas.AppleMusicArtistBackgroundProvider
import com.convx.music.constants.CanvasSource
import com.convx.music.constants.CanvasSourceKey
import com.convx.music.ui.player.normalizeCanvasArtistName
import com.convx.music.ui.player.normalizeCanvasSongTitle
import com.convx.music.utils.dataStore
import com.convx.music.vivimusiccanvas.EchoMusicCanvasProvider
import com.convx.music.vivimusiccanvas.ViviMusicCanvasProvider
import com.convx.music.canvas.TidalCanvasProvider
import java.util.Locale
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val ipVersion by enumPreference(context, IpVersionKey, IpVersion.AUTO)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()
    private val appContext: Context = context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    init {
        scope.launch {
            var known: Set<String>? = null
            database.likedSongIds().collect { ids ->
                val current = ids.toSet()
                val previous = known
                known = current
                if (previous == null) return@collect
                if (!appContext.dataStore.get(AutoDownloadOnLikeKey, false)) return@collect

                for (songId in current - previous) {
                    if (downloads.value[songId] != null) continue
                    runCatching {
                        val title = database.songTitle(songId).orEmpty()
                        DownloadService.sendAddDownload(
                            appContext,
                            ExoDownloadService::class.java,
                            DownloadRequest.Builder(songId, songId.toUri())
                                .setCustomCacheKey(songId)
                                .setData(title.toByteArray())
                                .build(),
                            false,
                        )
                    }.onFailure {
                        Timber.e(it, "Auto-download on like failed for $songId")
                    }
                }
            }
        }
    }

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        OkHttpClient.Builder()
                            .dns(object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    val addresses = Dns.SYSTEM.lookup(hostname)
                                    return when (this@DownloadUtil.ipVersion) {
                                        IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                                        IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                                        IpVersion.AUTO -> addresses
                                    }
                                }
                            })
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    context = appContext,
                    allowLossless = false,
                )
            }.getOrThrow()
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "mp4a.40.2",
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength ?: 0L,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song

                val updatedSong = if (existing != null) {
                    existing.copy(
                        dateDownload = existing.dateDownload ?: now,
                        thumbnailUrl = existing.thumbnailUrl
                            ?: playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url?.resize(1200, 1200),
                    )
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url?.resize(1200, 1200),
                        dateDownload = now,
                        isDownloaded = false
                    )
                }

                upsert(updatedSong)

                updatedSong.thumbnailUrl?.let { url ->
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .diskCacheKey(url)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    SingletonImageLoader.get(context).enqueue(request)
                }

                // --- CANVAS CACHING ---
                scope.launch {
                    val canvasSource = context.dataStore.data.map { it[CanvasSourceKey] ?: CanvasSource.AUTO.name }.first().let { name -> CanvasSource.entries.find { it.name == name } ?: CanvasSource.AUTO }

                    val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
                    val requestedTitle = playbackData.videoDetails?.title.orEmpty()
                    val requestedArtist = playbackData.videoDetails?.author.orEmpty()
                    
                    val s = normalizeCanvasSongTitle(requestedTitle)
                    val a = normalizeCanvasArtistName(requestedArtist)

                    val canvas = when (canvasSource) {
                        CanvasSource.AUTO -> {
                            EchoMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                                ?: AppleMusicCanvasProvider.getBySongArtist(s, a, "", storefront)?.preferredAnimationUrl
                                ?: ViviMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                                ?: TidalCanvasProvider.getBySongArtist(s, a, "")?.preferredAnimationUrl
                        }
                        CanvasSource.ECHO_MUSIC -> EchoMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                        CanvasSource.APPLE_MUSIC -> AppleMusicCanvasProvider.getBySongArtist(s, a, "", storefront)?.preferredAnimationUrl
                        CanvasSource.VIVIMUSIC -> ViviMusicCanvasProvider.getBySongArtist(s, a)?.preferredAnimationUrl
                        CanvasSource.TIDAL -> TidalCanvasProvider.getBySongArtist(s, a, "")?.preferredAnimationUrl
                    }

                    canvas?.let { url ->
                        val dataSpec = DataSpec.Builder()
                            .setUri(url.toUri())
                            .setKey("$mediaId#canvas")
                            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                            .build()
                            
                        val dataSource = CacheDataSource.Factory()
                            .setCache(downloadCache)
                            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
                            .setCacheWriteDataSinkFactory(null)
                            .createDataSource()
                        
                        kotlin.runCatching {
                            val writer = CacheWriter(
                                dataSource,
                                dataSpec,
                                null,
                                null
                            )
                            writer.cache()
                            Timber.tag("CanvasDownload").d("Successfully cached canvas for $mediaId")
                        }.onFailure { e ->
                            Timber.tag("CanvasDownload").e(e, "Failed to cache canvas for $mediaId")
                        }
                    }
                }
            }

            val streamUrl = if (playbackData.isSaavnStream || playbackData.isTidalStream || playbackData.isSpineStream) {
                playbackData.streamUrl
            } else {
                "${playbackData.streamUrl}&range=0-${format.contentLength ?: 10_000_000}"
            }

            songUrlCache[mediaId] =
                streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context,
                    downloadNotificationHelper,
                    ExoDownloadService.NOTIFICATION_ID + 1,
                )
            )
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        if (download.state == Download.STATE_FAILED) {
                            Timber.e(
                                finalException,
                                "Download failed: id=%s title=%s reason=%d",
                                download.request.id,
                                Util.fromUtf8Bytes(download.request.data),
                                download.failureReason,
                            )
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    exportDownloadToStorage(download.request.id)
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
    }

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
                    Timber.d("Successfully exported $fileName to MediaStore via scoped storage")
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
                Timber.d("Successfully exported $fileName to legacy storage: ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to export downloaded track $songId to external storage")
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }
}
