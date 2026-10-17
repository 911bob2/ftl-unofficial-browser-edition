package xyz.znix.xftl.sys
import java.io.InputStream
import java.io.ByteArrayInputStream
import ftl.browser.BrowserFiles
object XftlResources {
    var opener: ((String) -> InputStream?)? = null
    @JvmStatic fun open(path: String): InputStream? = ByteArrayInputStream(BrowserFiles.read("engine/" + path.removePrefix("/")))
}
