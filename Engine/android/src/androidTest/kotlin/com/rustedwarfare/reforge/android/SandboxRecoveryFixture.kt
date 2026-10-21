package com.rustedwarfare.reforge.android

import android.content.Context
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Temporarily moves one verified APK-derived reference; never overwrites or deletes original bytes. */
internal class SandboxRecoveryFixture(context: Context, runName: String) : AutoCloseable {
    private val relative="TestLab/local/P002-知识沙盒输入/tilesets/p001-ground.tsx"
    private val source=context.filesDir.canonicalFile.toPath().resolve("p001").resolve(relative)
    private val cache=context.cacheDir.canonicalFile.toPath().resolve("s04-recovery")
    private val backup=cache.resolve(runName).resolve("p001-ground.tsx")
    private val original=context.assets.open("p001/$relative").use { it.readBytes() }
    private var identity: BasicFileAttributes?=null
    private var moved=false
    var restored=false
        private set
    val sha256=MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
    val byteCount=original.size
    init {
        require(runName.matches(Regex("run-[0-9]+-[0-9a-f]{8}")))
        check(original.size in 1..1024*1024)
    }
    private fun chain(path: Path) {
        var current: Path?=path
        while(current!=null) {
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS))
                check(!Files.isSymbolicLink(current) && current.toRealPath()==current) { "Noncanonical recovery path" }
            current=current.parent
        }
    }
    private fun originalAt(path: Path): BasicFileAttributes {
        chain(path)
        val attrs=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(attrs.isRegularFile && attrs.size()==original.size.toLong() && Files.readAllBytes(path).contentEquals(original))
        return attrs
    }
    fun hide() {
        check(!moved && !restored)
        identity=originalAt(source)
        chain(cache)
        if(!Files.exists(cache,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cache)
        check(Files.isDirectory(cache,LinkOption.NOFOLLOW_LINKS))
        Files.createDirectory(backup.parent)
        chain(backup.parent)
        check(!Files.exists(backup,LinkOption.NOFOLLOW_LINKS))
        Files.move(source,backup)
        moved=true
        verifyMoved()
    }
    private fun verifyMoved() {
        check(moved && !Files.exists(source,LinkOption.NOFOLLOW_LINKS))
        val current=originalAt(backup);val before=checkNotNull(identity)
        check(current.fileKey()==before.fileKey() && current.lastModifiedTime()==before.lastModifiedTime())
    }
    fun restore() {
        verifyMoved()
        chain(source.parent)
        Files.move(backup,source)
        moved=false
        val current=originalAt(source);val before=checkNotNull(identity)
        check(current.fileKey()==before.fileKey() && current.lastModifiedTime()==before.lastModifiedTime())
        restored=true
    }
    override fun close() { if(moved) restore() }
}
