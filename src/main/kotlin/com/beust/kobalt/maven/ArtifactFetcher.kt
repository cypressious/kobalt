package com.beust.kobalt.maven

import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.log
import com.beust.kobalt.misc.warn
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.inject.assistedinject.Assisted
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(val factory: ArtifactFetcher.IFactory) {
    class Key(val url: String, val fileName: String, val executor: ExecutorService) {
        override fun equals(other: Any?): Boolean {
            return (other as Key).url == url
        }

        override fun hashCode(): Int {
            return url.hashCode()
        }
    }

    private val CACHE : LoadingCache<Key, Future<File>> = CacheBuilder.newBuilder()
        .build(object : CacheLoader<Key, Future<File>>() {
            override fun load(key: Key): Future<File> {
                return key.executor.submit(factory.create(key.url, key.fileName))
            }
        })

    fun download(url: String, fileName: String, executor: ExecutorService)
            : Future<File> = CACHE.get(Key(url, fileName, executor))
}

/**
 * Fetches an artifact (a file in a Maven repo, .jar, -javadoc.jar, ...) to the given local file.
 */
class ArtifactFetcher @Inject constructor(@Assisted("url") val url: String,
        @Assisted("fileName") val fileName: String,
        val files: KFiles, val urlFactory: Kurl.IFactory) : Callable<File> {
    interface IFactory {
        fun create(@Assisted("url") url: String, @Assisted("fileName") fileName: String) : ArtifactFetcher
    }

    override fun call() : File {
        val k = urlFactory.create(url + ".md5")
        val remoteMd5 =
            if (k.exists) k.string.trim(' ', '\t', '\n').substring(0, 32)
            else null

        val tmpFile = Paths.get(fileName + ".tmp")
        val file = Paths.get(fileName)
        with(tmpFile.toFile()) {
            parentFile.mkdirs()
            urlFactory.create(url).toFile(this)
        }
        log(2, "Done downloading, renaming $tmpFile to $file")
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING)
        log(1, "  Downloaded $url")
        log(2, "     to $file")

        val localMd5 = Md5.toMd5(file.toFile())
        if (remoteMd5 != null && remoteMd5 != localMd5) {
            warn("MD5 not matching for $url")
        } else {
            log(2, "No md5 found for $url, skipping md5 check")
        }

        return file.toFile()
    }
}
