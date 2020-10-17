import kotlinext.js.jsObject
import react.dom.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.js.onClickFunction
import org.w3c.dom.events.Event
import kotlin.js.Date

fun measured(fn: (Event) -> Unit): (Event) -> Unit = {
    val start = Date().getTime()
    fn(it)
    window.alert("Elapsed: ${Date().getTime() - start}ms")
}

fun main() {
    val resourceManager = DefaultResourceManager()
    render(document.getElementById("root")) {
        button {
            attrs {
                onClickFunction = measured {
                    repeat(1000000) {
                        resourceManager.getTypeChecked<ImageResourceData>("a")
                    }
                }
            }
            +"Type checked performance"
        }

        button {
            attrs {
                onClickFunction = measured {
                    repeat(1000000) {
                        resourceManager.getNativeResult("a")
                    }
                }
            }
            +"Native performance"
        }
    }
}

data class ImageResourceData(val imageId: String)

class DefaultResourceManager {
    private val typeChecked: MutableMap<String, Any> = HashMap()
    private val nativeObj: dynamic = jsObject()

    init {
        typeChecked["a"] = ImageResourceData("a")
        nativeObj["a"] = ImageResourceData("a")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getTypeChecked(id: String): T = typeChecked.getValue(id) as T

    fun <T> getNativeResult(id: String): T = nativeObj[id]
}

