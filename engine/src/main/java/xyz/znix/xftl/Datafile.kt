package xyz.znix.xftl

import ftl.browser.BrowserFiles
import ftl.browser.BrowserXml
import org.jdom2.Document
import xyz.znix.xftl.modding.SlipstreamMod
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.StagedImageLoad
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

const val HEADER_SIZE = 16
/** The image FTL draws in place of one it cannot load. */
const val NULL_RESOURCE = "img/nullResource.png"
const val ENTRY_SIZE = 20
interface IVanillaDatafile {
    fun getAllFiles(): List<VanillaDatafile.Entry>
    fun containsFile(name: String): Boolean
    fun read(file: VanillaDatafile.Entry): ByteArray
    val xmlCacheSupported: Boolean
}
class VanillaDatafile(val underlyingFile: File) : IVanillaDatafile {
    override val xmlCacheSupported = false
    override fun getAllFiles() = BrowserFiles.names().map { Entry(it,0,0) }
    override fun containsFile(name: String) = BrowserFiles.contains(name)
    override fun read(file: Entry) = BrowserFiles.read(file.name)
    operator fun get(name: String) = Entry(name,0,0)
    class Entry(val name: String, val offset: Int, val length: Int)
    companion object { @JvmStatic fun createWithDefaultPath() = VanillaDatafile(File("ftl.dat")) }
}
open class Datafile {
    val vanilla = VanillaDatafile(File("ftl.dat"))
    constructor()
    constructor(v: IVanillaDatafile, mods: List<SlipstreamMod>)
    operator fun get(name: String): FTLFile = getOrNull(name) ?: error("Missing asset: $name")
    fun getOrNull(name: String): FTLFile? = if(BrowserFiles.contains(name)) FTLFile(name) else null
    fun read(file: FTLFile): ByteArray = BrowserFiles.read(file.name)
    fun readString(file: FTLFile) = String(read(file), Charsets.UTF_8).replace("\r\n", "\n")
    fun parseXML(file: FTLFile): Document = xyz.znix.xftl.hs.HsLoad.parseXml(file.name, readString(file))
    fun open(file: FTLFile): InputStream = ByteArrayInputStream(read(file))
    /**
     * As in FTL, a file that cannot be read as an image (not a PNG, an interlaced PNG) loads as
     * `img/nullResource.png`, the image shown for a missing file, and the log says "Error! Could not parse file".
     */
    open fun readImage(context: ResourceContext, file: FTLFile): Image = try {
        StagedImageLoad(context, file.name, read(file)).finish()
    } catch (e: Exception) {
        if (file.name == NULL_RESOURCE || getOrNull(NULL_RESOURCE) == null) throw e
        println("Error! Could not parse file: ${file.name} ($e)")
        StagedImageLoad(context, NULL_RESOURCE, read(get(NULL_RESOURCE))).finish()
    }
    fun getAllFiles() = BrowserFiles.names().map { FTLFile(it) }
    companion object { fun loadWithMods(v: VanillaDatafile) = Datafile() }
}
