package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.PreparedImagePack
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** An untrusted hint selects a frozen verifier, never bypasses that verifier's full proof checks. */
internal fun originalCertificateProfile(pack: PreparedImagePack, name: String): String? {
    check(name == "extraction.json" || name == "resource-extraction.json")
    val file = pack.manifest.parent.resolve(name)
    return try {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || file.toRealPath() != file || Files.size(file) !in 1..16L * 1024 * 1024)
            null
        else {
            val bytes = Files.newInputStream(file).use { it.readNBytes(16 * 1024 * 1024 + 1) }
            if (bytes.size > 16 * 1024 * 1024) null else JsonReader().parse(bytes.toString(Charsets.UTF_8)).getString("profileId", null)
        }
    } catch (_: java.io.IOException) { null }
      catch (_: RuntimeException) { null }
}
