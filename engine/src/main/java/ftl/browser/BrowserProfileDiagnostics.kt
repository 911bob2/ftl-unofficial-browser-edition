package ftl.browser

import org.jdom2.Document
import xyz.znix.xftl.game.SaveProfile

/** For diagnostics that change options and progress and put both back: the save with the settings inside it. */
fun SaveProfile.snapshot(): Document {
    val doc = save()
    doc.rootElement.addContent(saveSettings().detachRootElement())
    return doc
}

fun SaveProfile.restore(doc: Document) {
    load(doc)
    doc.rootElement.getChild("xftl-settings")?.let { loadSettings(Document(it.clone())) }
}
