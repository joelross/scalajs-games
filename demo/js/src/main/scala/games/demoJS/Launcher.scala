package games.demoJS

import scala.scalajs.js
import org.scalajs.dom
import scalajs.concurrent.JSExecutionContext.Implicits.queue

import games._
import games.math
import games.math.Vector3f
import games.opengl._
import games.audio._
import games.input._

import games.demo._

object Launcher extends js.JSApp {
  def main(): Unit = {
    JsUtils.setResourcePath("/resources")

    val output = dom.document.getElementById("demo-output")
    val canvas = dom.document.getElementById("demo-canvas-main").asInstanceOf[dom.html.Canvas]

    val itf = new EngineInterface {
      def printLine(msg: String): Unit = {
        val line = dom.document.createElement("p")
        line.innerHTML = msg
        output.appendChild(line)
      }
      def getScreenDim(): (Int, Int) = {
        val width = canvas.width
        val height = canvas.height
        (width, height)
      }
      def initGL(): GLES2 = {
        val glContext: GLES2 = new GLES2WebGL(canvas)
        glContext
      }
      def initAudio(): Context = {
        val audioContext: Context = new WebAudioContext()
        audioContext
      }
      def initKeyboard(): Keyboard = {
        val keyboard = new KeyboardJS()
        keyboard
      }
      def update(): Boolean = true
      def close(): Unit = {}
    }

    val engine = new Engine(itf)

    Utils.startFrameListener(engine)
  }
}
