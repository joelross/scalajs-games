package games.audio

import scala.scalajs.js
import org.scalajs.dom
import scala.concurrent.{ Future, Promise }
import scalajs.concurrent.JSExecutionContext.Implicits.queue

import scala.collection.{ mutable, immutable }

import games.Resource
import games.Utils
import games.JsUtils
import games.math.Vector3f

sealed trait JsAbstractSource extends Source {
  def inputNode: js.Dynamic

  override def close(): Unit = {
    super.close()
  }
}
class JsSource(val ctx: WebAudioContext, outputNode: js.Dynamic) extends Source with JsAbstractSource {
  val inputNode = outputNode

  ctx.registerSource(this)

  override def close(): Unit = {
    super.close()

    ctx.unregisterSource(this)
  }
}
class JsSource3D(val ctx: WebAudioContext, outputNode: js.Dynamic) extends Source3D with JsAbstractSource {
  val pannerNode = {
    val pannerNode = ctx.webApi.createPanner()
    pannerNode.connect(outputNode)
    pannerNode
  }
  val inputNode = pannerNode

  private val positionData = new Vector3f(0, 0, 0)

  // Init
  this.position = positionData

  ctx.registerSource(this)

  def position: games.math.Vector3f = positionData.copy()
  def position_=(position: games.math.Vector3f): Unit = {
    Vector3f.set(position, positionData)
    pannerNode.setPosition(positionData.x, positionData.y, positionData.z)
  }

  override def close(): Unit = {
    super.close()

    ctx.unregisterSource(this)

    pannerNode.disconnect()
  }
}

sealed trait JsData extends Data {
  override def close(): Unit = {
    super.close()
  }
}

class JsBufferData(val ctx: WebAudioContext, webAudioBuffer: js.Dynamic) extends BufferedData with JsData {
  ctx.registerData(this)

  def attachNow(source: games.audio.Source): games.audio.JsBufferPlayer = {
    val jsSource = source.asInstanceOf[JsAbstractSource]
    new JsBufferPlayer(this, jsSource, webAudioBuffer)
  }

  override def close(): Unit = {
    super.close()

    ctx.unregisterData(this)
  }
}

class JsStreamingData(val ctx: WebAudioContext, res: Resource) extends Data with JsData {
  private var backupDataFromAurora: Option[JsBufferData] = None

  ctx.registerData(this)

  def attach(source: games.audio.Source): Future[games.audio.JsPlayer] = {
    val promise = Promise[games.audio.JsPlayer]

    val audioElement: js.Dynamic = js.Dynamic.newInstance(js.Dynamic.global.Audio)()
    val path = JsUtils.pathForResource(res)
    audioElement.src = path

    audioElement.oncanplay = () => {
      val jsSource = source.asInstanceOf[JsAbstractSource]
      val player = new JsStreamingPlayer(this, jsSource, audioElement)
      promise.success(player)
    }

    audioElement.onerror = () => {
      val errorCode = audioElement.error.code.asInstanceOf[Int]
      val errorMessage = errorCode match {
        case 1 => "request aborted"
        case 2 => "network error"
        case 3 => "decoding error"
        case 4 => "source not supported"
        case _ => "unknown error"
      }
      val msg = "Failed to load the stream from " + res + ", cause: " + errorMessage

      // If Aurora is available and this error seems due to decoding, try with Aurora
      if (WebAudioContext.canUseAurora && (errorCode == 3 || errorCode == 4)) {
        backupDataFromAurora match {
          case Some(data) => promise.success(data.attachNow(source))
          case None =>
            val auroraDataFuture = AuroraHelper.createDataFromAurora(ctx, res)
            auroraDataFuture.onSuccess {
              case data =>
                backupDataFromAurora = Some(data)
                promise.success(data.attachNow(source))
            }
            auroraDataFuture.onFailure { case t => promise.failure(new RuntimeException(msg + " (result with Aurora: " + t + ")", t)) }
        }

      } else { // TODO is this one really necessary?
        if (!promise.isCompleted) promise.failure(new RuntimeException(msg))
        else Console.err.println(msg)
      }
    }

    promise.future
  }

  override def close(): Unit = {
    super.close()

    ctx.unregisterData(this)

    for (data <- backupDataFromAurora) {
      data.close()
    }
  }
}

sealed trait JsPlayer extends Player

class JsBufferPlayer(val data: JsBufferData, val source: JsAbstractSource, webAudioBuffer: js.Dynamic) extends JsPlayer {
  // Init
  private var sourceNode = data.ctx.webApi.createBufferSource()
  sourceNode.buffer = webAudioBuffer
  private val gainNode = data.ctx.webApi.createGain()
  gainNode.gain.value = 1.0
  sourceNode.connect(gainNode)
  gainNode.connect(source.inputNode)

  private var isPlaying = false

  private var needRestarting = false
  private var nextStartTime = 0.0
  private var lastStartDate = 0.0

  source.registerPlayer(this)
  data.registerPlayer(this)

  def playing: Boolean = isPlaying
  def playing_=(playing: Boolean): Unit = if (playing) {
    if (needRestarting) { // a SourceNode can only be started once, need to create a new one
      val oldNode = sourceNode
      oldNode.disconnect() // disconnect the old node

      sourceNode = data.ctx.webApi.createBufferSource()
      sourceNode.loop = oldNode.loop
      sourceNode.buffer = oldNode.buffer
      sourceNode.playbackRate.value = oldNode.playbackRate.value
      sourceNode.connect(gainNode)
    }

    sourceNode.start(0, nextStartTime)
    lastStartDate = JsUtils.now()
    isPlaying = true

    sourceNode.onended = () => {
      isPlaying = false
      needRestarting = true
      nextStartTime = (JsUtils.now() - lastStartDate) / 1000.0 // msec -> sec
    }
  } else {
    sourceNode.stop()
  }

  def volume: Float = gainNode.gain.value.asInstanceOf[Double].toFloat
  def volume_=(volume: Float) = {
    gainNode.gain.value = volume.toDouble
  }

  def loop: Boolean = sourceNode.loop.asInstanceOf[Boolean]
  def loop_=(loop: Boolean) = {
    sourceNode.loop = loop
  }

  def pitch: Float = sourceNode.playbackRate.value.asInstanceOf[Double].toFloat
  def pitch_=(pitch: Float) = {
    sourceNode.playbackRate.value = pitch.toDouble
  }

  override def close(): Unit = {
    super.close()

    source.unregisterPlayer(this)
    data.unregisterPlayer(this)

    sourceNode.disconnect()
    gainNode.disconnect()
  }
}

class JsStreamingPlayer(val data: JsStreamingData, val source: JsAbstractSource, audioElement: js.Dynamic) extends JsPlayer {
  private val sourceNode = data.ctx.webApi.createMediaElementSource(audioElement)
  sourceNode.connect(source.inputNode)

  audioElement.onpause = audioElement.onended = () => {
    isPlaying = false
  }

  private var isPlaying = false

  source.registerPlayer(this)
  data.registerPlayer(this)

  def playing: Boolean = isPlaying
  def playing_=(playing: Boolean): Unit = if (playing) {
    audioElement.play()
    isPlaying = true
  } else {
    audioElement.pause()
  }

  def volume: Float = audioElement.volume.asInstanceOf[Double].toFloat
  def volume_=(volume: Float) = {
    audioElement.volume = volume.toDouble
  }

  def loop: Boolean = audioElement.loop.asInstanceOf[Boolean]
  def loop_=(loop: Boolean) = {
    audioElement.loop = loop
  }

  def pitch: Float = audioElement.playbackRate.asInstanceOf[Double].toFloat
  def pitch_=(pitch: Float) = {
    audioElement.playbackRate = pitch.toDouble
  }

  override def close(): Unit = {
    super.close()

    source.unregisterPlayer(this)
    data.unregisterPlayer(this)

    sourceNode.disconnect()
  }
}
