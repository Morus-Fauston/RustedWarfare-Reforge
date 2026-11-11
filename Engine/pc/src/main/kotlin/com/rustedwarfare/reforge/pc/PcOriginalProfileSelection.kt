package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.PreparedImagePack
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** An untrusted hint selects a frozen verifier, never bypasses that verifier's full proof checks. */
internal fun originalCertificateProfile(pack: PreparedImagePack, name: String): String? = originalCertificateProfile(pack.manifest, name, null)

internal fun originalCertificateProfile(manifest: Path, name: String, inputs: PcFrozenResourceInputs?): String? {
    checkVisualCancellation()
    check(name == "extraction.json" || name == "resource-extraction.json")
    val file = manifest.parent.resolve(name)
    return try {
        if (inputs != null) JsonReader().parse(inputs.read(file, 16 * 1024 * 1024).toString(Charsets.UTF_8)).getString("profileId", null)
        else if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || file.toRealPath() != file || Files.size(file) !in 1..16L * 1024 * 1024)
            null
        else {
            val bytes = Files.newInputStream(file).use { it.readNBytes(16 * 1024 * 1024 + 1) }
            if (bytes.size > 16 * 1024 * 1024) null else JsonReader().parse(bytes.toString(Charsets.UTF_8)).getString("profileId", null)
        }
    } catch (failure: CancellationException) { throw failure }
      catch (_: java.io.IOException) { null }
      catch (_: RuntimeException) { null }
}

/** One frozen image/sound lineage. Hints select its full verifier; they never grant qualification. */
internal object PcOriginalProfileVersions {
    private val versions = listOf(lazy { PcOriginalResourceProfile.versionOne() }, lazy { PcOriginalResourceProfile.versionTwo() },
        lazy { PcOriginalResourceProfile.versionThree() }, lazy { PcOriginalResourceProfile.versionFour() },
        lazy { PcOriginalResourceProfile.versionFive() }, lazy { PcOriginalResourceProfile.bundled() })
    val supportedVersions = listOf(1, 2, 3, 4, 5, 7)
    fun version(number: Int): PcOriginalResourceProfile {
        val index = supportedVersions.indexOf(number)
        require(index >= 0)
        return versions[index].value
    }
    fun resources(manifest: Path, inputs: PcFrozenResourceInputs?): PcOriginalResourceProfile {
        val hint = originalCertificateProfile(manifest, "resource-extraction.json", inputs)
        return version(supportedVersions.firstOrNull { hint == "rw.pc.steam.9902063.resources.v$it" } ?: 7)
    }
    fun images(manifest: Path, inputs: PcFrozenResourceInputs?): PcOriginalImageProfile {
        val hint = originalCertificateProfile(manifest, "extraction.json", inputs)
        return version(supportedVersions.firstOrNull { hint == "rw.pc.steam.9902063.images.v$it" } ?: 7).images
    }
}
