package ftl.android.app.mods

import android.content.res.AssetManager
import ftl.mods.BytesSource
import ftl.mods.MergeArchive
import ftl.mods.Zip
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Our own data layers for the touch layout over a mod whose files name mouse or keyboard controls where no rule of the
 * engine can help: text written by us (the `<name>_TOUCH` lines the engine's touch layout reads in place of `<name>`),
 * merged after the mods like one more mod, only over the exact archives each was written for (by SHA-256). They copy
 * nothing from the mod. The app is always in the touch layout.
 */
object TouchPatches {
    /** A layer: its asset folder (under `touch-patches/`), the files in it, and the archives it is for. */
    class Patch(val id: String, val version: Int, val files: List<String>, val forArchives: Set<String>)

    val all = listOf(
        // Multiverse 5.5.1's Lua tutorial (data/mv_scripts/tutorial.lua): seven of its lines in data/text_tutorial.xml
        // name the right mouse button, hovering, clicking or a hotkey.
        Patch("multiverse-5.5.1", 2, listOf("data/text_tutorial.xml.append"),
            setOf("487f7e198bb12818e655597a55b71a4d7ed1cd5a41f8d31cc270c801b72c68b0")),
    )

    /** The layers for a mod set (the archives' SHA-256 in load order). */
    fun forMods(sha256: List<String>): List<Patch> = all.filter { p -> sha256.any { it in p.forArchives } }

    /** What the layers add to the merge cache's key. */
    fun key(patches: List<Patch>): String = patches.joinToString(",") { "${it.id}@${it.version}" }

    /** A layer as an archive for the merge, read from the app's assets. */
    fun archive(assets: AssetManager, p: Patch): MergeArchive {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in p.files) {
                zip.putNextEntry(ZipEntry(name))
                assets.open("touch-patches/${p.id}/$name").use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return MergeArchive("touch layer ${p.id}", Zip.open(BytesSource(out.toByteArray())))
    }
}
