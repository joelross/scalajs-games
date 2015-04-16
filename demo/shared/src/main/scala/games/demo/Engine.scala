package games.demo

import transport.ConnectionHandle
import transport.WebSocketUrl
import games.demo.Specifics.WebSocketClient

import scala.concurrent.{ Promise, Future, ExecutionContext }
import games._
import games.math
import games.math.{ Vector3f, Vector4f, Matrix3f, Matrix4f }
import games.opengl._
import games.audio._
import games.input._
import games.utils._
import java.nio.{ ByteBuffer, FloatBuffer, ByteOrder }
import games.opengl.GLES2Debug
import games.audio.Source3D
import games.input.ButtonEvent
import games.audio.AbstractSource
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import scala.collection.immutable
import scala.collection.mutable

abstract class EngineInterface {
  def printLine(msg: String): Unit
  def initGL(): GLES2
  def initAudio(): Context
  def initKeyboard(): Keyboard
  def initMouse(): Mouse
  def update(): Boolean
  def close(): Unit
}

class PlayerData(var position: Vector3f, var velocity: Float, var orientation: Vector3f, var rotation: Vector3f)
class ExternalPlayerData(var id: Int, var data: PlayerData, var latency: Int)

class Engine(itf: EngineInterface)(implicit ec: ExecutionContext) extends games.FrameListener {
  private val updateIntervalMs = 25 // Resend position at 40Hz
  private val rotationMultiplier: Float = 50.0f

  private val fovy: Float = 60f

  // render between 10cm and 1km
  private val near: Float = 0.1f
  private val far: Float = 1000f

  def context: games.opengl.GLES2 = gl

  private var continueCond = true

  private var gl: GLES2 = _
  private var audioContext: Context = _
  private var keyboard: Keyboard = _
  private var mouse: Mouse = _

  private var connection: Option[ConnectionHandle] = None
  private var localPlayerId: Int = 0

  private var screenDim: (Int, Int) = _
  private var projection: Matrix4f = _

  private var localData: PlayerData = new PlayerData(new Vector3f, 0f, new Vector3f, new Vector3f)
  private var extData: Map[Int, ExternalPlayerData] = Map()

  private var lastTimeUpdateFromServer: Option[Long] = None
  private var lastTimeUpdateToServer: Option[Long] = None

  private var planeMesh: OpenGLMesh = _
  private var shipMesh: OpenGLMesh = _
  private var shipProgram: Token.Program = _

  private val planeTransform = Matrix4f.translate3D(new Vector3f(0, 0, -10)) * Matrix4f.scale3D(new Vector3f(50, 50, 50))

  private var positionAttrLoc: Int = _
  private var normalAttrLoc: Int = _
  private var diffuseColorUniLoc: Token.UniformLocation = _
  private var projectionUniLoc: Token.UniformLocation = _
  private var modelViewUniLoc: Token.UniformLocation = _
  private var modelViewInvTrUniLoc: Token.UniformLocation = _

  private def conv(v: Vector3): Vector3f = new Vector3f(v.x, v.y, v.z)
  private def conv(v: Vector3f): Vector3 = Vector3(v.x, v.y, v.z)

  def sendMsg(msg: ClientMessage): Unit = connection match {
    case None => throw new RuntimeException("Websocket not connected")
    case Some(conn) =>
      val data = upickle.write(msg)
      conn.write(data)
  }

  def continue(): Boolean = continueCond

  def onClose(): Unit = {
    itf.printLine("Closing...")
    itf.close()

    mouse.close()
    keyboard.close()
    audioContext.close()
    gl.close()

    for (conn <- connection) {
      conn.close()
      connection = None
    }
  }

  def onCreate(): Option[Future[Unit]] = {
    itf.printLine("Starting...")
    this.gl = itf.initGL() // Init OpenGL (Enable automatic error checking by encapsuling it in GLES2Debug)
    this.audioContext = itf.initAudio() // Init Audio
    this.keyboard = itf.initKeyboard() // Init Keyboard listening
    this.mouse = itf.initMouse() // Init Mouse listener

    audioContext.volume = 0.25f // Lower the initial global volume

    // Loading data
    val modelsFuture = Rendering.loadAllModels("/games/demo/models", gl, loopExecutionContext)
    val shadersFuture = Rendering.loadAllShaders("/games/demo/shaders", gl, loopExecutionContext)

    val dataLoadedFuture = for (
      models <- modelsFuture;
      shaders <- shadersFuture
    ) yield {
      itf.printLine("All data loaded successfully: " + models.size + " model(s), " + shaders.size + " shader(s)")

      planeMesh = models("plane")
      shipMesh = models("ship")
      shipProgram = shaders("ship")
    }

    // Retrieve useful data from shaders (require access to OpenGL context)
    val retrieveInfoFromDataFuture = dataLoadedFuture.map { _ =>
      positionAttrLoc = gl.getAttribLocation(shipProgram, "position")
      normalAttrLoc = gl.getAttribLocation(shipProgram, "normal")

      diffuseColorUniLoc = gl.getUniformLocation(shipProgram, "diffuseColor")
      projectionUniLoc = gl.getUniformLocation(shipProgram, "projection")
      modelViewUniLoc = gl.getUniformLocation(shipProgram, "modelView")
      modelViewInvTrUniLoc = gl.getUniformLocation(shipProgram, "modelViewInvTr")
    }(loopExecutionContext)

    val helloPacketReceived = Promise[Unit]

    // Init network (wait for data loading to complete before that)
    val networkFuture = retrieveInfoFromDataFuture.flatMap { _ =>
      val futureConnection = new WebSocketClient().connect(WebSocketUrl(Data.server))
      futureConnection.map { conn =>
        itf.printLine("Websocket connection established")
        // Wait for the Hello packet to register the connection
        conn.handlerPromise.success { msg =>
          val now = System.currentTimeMillis()
          val serverMsg = upickle.read[ServerMessage](msg)

          Future { // To avoid concurrency issue, process the following in the loop thread
            serverMsg match {
              case Ping => // answer that ASAP
                sendMsg(Pong)

              case Hello(playerId, initPostion, initOrientation) =>
                this.connection = Some(conn)
                localPlayerId = playerId
                localData.position = conv(initPostion)
                localData.orientation = conv(initOrientation)
                itf.printLine("You are player " + playerId)
                helloPacketReceived.success((): Unit)

              case ServerUpdate(players, newEvents) =>
                lastTimeUpdateFromServer = Some(now)

                val (locals, externals) = players.partition(_.id == localPlayerId)

                assert(locals.size == 1)
                val local = locals.head

                extData = externals.flatMap { serverUpdatePlayerData =>
                  serverUpdatePlayerData.space.map { spaceData =>
                    (serverUpdatePlayerData.id, new ExternalPlayerData(serverUpdatePlayerData.id, new PlayerData(conv(spaceData.position), spaceData.velocity, conv(spaceData.orientation), conv(spaceData.rotation)), serverUpdatePlayerData.latency + local.latency))
                  }
                }.toMap
                newEvents.foreach { event =>
                  // TODO process event
                }
            }
          }(loopExecutionContext)

        }
        conn.closedFuture.onSuccess {
          case _ =>
            itf.printLine("Websocket connection closed")
            this.connection = None
        }
      }
    }.flatMap { _ => helloPacketReceived.future }

    // Setup OpenGL
    gl.clearColor(0.75f, 0.75f, 0.75f, 1) // black background

    gl.enable(GLES2.DEPTH_TEST)
    gl.depthFunc(GLES2.LESS)

    val width = gl.display.width
    val height = gl.display.height

    screenDim = (width, height)

    gl.viewport(0, 0, width, height)
    projection = Matrix4f.perspective3D(fovy, width.toFloat / height.toFloat, near, far)

    Some(networkFuture) // wait for network setup (last part) to complete before proceding
  }

  def onDraw(fe: games.FrameEvent): Unit = {
    val now = System.currentTimeMillis()
    val elapsedSinceLastFrame = fe.elapsedTime

    val width = gl.display.width
    val height = gl.display.height

    // Update from inputs
    val delta = mouse.deltaPosition

    def processKeyboard() {
      val optKeyEvent = keyboard.nextEvent()
      for (keyEvent <- optKeyEvent) {
        if (keyEvent.down) keyEvent.key match {
          case Key.Escape => continueCond = false
          case Key.L      => mouse.locked = !mouse.locked
          case Key.F      => gl.display.fullscreen = !gl.display.fullscreen
          case _          => // nothing to do
        }

        processKeyboard() // process next event
      }
    }
    processKeyboard()

    if (keyboard.isKeyDown(Key.W)) localData.velocity = 3f
    else if (keyboard.isKeyDown(Key.S)) localData.velocity = 1f
    else localData.velocity = 2f

    // Simulation

    // Input for local Player
    val inputRotationX = (delta.x.toFloat / width.toFloat) * -rotationMultiplier
    val inputRotationY = (delta.y.toFloat / height.toFloat) * -rotationMultiplier
    val inputRotationXSpeed = inputRotationX / elapsedSinceLastFrame
    val inputRotationYSpeed = inputRotationY / elapsedSinceLastFrame

    localData.rotation.x = if (Math.abs(inputRotationXSpeed) > Physics.maxRotationXSpeed) Math.signum(inputRotationXSpeed) * Physics.maxRotationXSpeed else inputRotationXSpeed
    localData.rotation.y = if (Math.abs(inputRotationYSpeed) > Physics.maxRotationYSpeed) Math.signum(inputRotationYSpeed) * Physics.maxRotationYSpeed else inputRotationYSpeed

    Physics.step(elapsedSinceLastFrame, localData) // Local Player
    for ((extId, extVal) <- extData) { // External players
      Physics.step(elapsedSinceLastFrame, extVal.data)
    }

    // Network (if necessary)
    for (conn <- connection) {
      if (lastTimeUpdateToServer.isEmpty || now - lastTimeUpdateToServer.get > updateIntervalMs) {
        val position = conv(localData.position)
        val velocity = localData.velocity
        val orientation = conv(localData.orientation)
        val rotation = conv(localData.rotation)
        val clientUpdate = ClientUpdate(position, velocity, orientation, rotation)

        val msgText = upickle.write(clientUpdate)
        conn.write(msgText)

        lastTimeUpdateToServer = Some(now)
      }
    }

    // Rendering
    val curDim = (width, height)
    if (curDim != screenDim) {
      screenDim = curDim
      gl.viewport(0, 0, width, height)
      Matrix4f.setPerspective3D(fovy, width.toFloat / height.toFloat, near, far, projection)
    }

    gl.clear(GLES2.COLOR_BUFFER_BIT | GLES2.DEPTH_BUFFER_BIT)
    gl.useProgram(shipProgram)
    gl.uniformMatrix4f(projectionUniLoc, projection)

    gl.enableVertexAttribArray(positionAttrLoc)
    gl.enableVertexAttribArray(normalAttrLoc)

    val cameraOrientation = localData.orientation.copy()
    cameraOrientation.z = 0

    val cameraTransform = Matrix4f.translate3D(localData.position) * Physics.matrixForOrientation(cameraOrientation).toHomogeneous()
    val cameraTransformInv = cameraTransform.invertedCopy()

    //Rendering.render(localPlayerId, planeMesh, planeTransform, cameraTransformInv, gl, positionAttrLoc, normalAttrLoc, modelViewUniLoc, modelViewInvTrUniLoc, diffuseColorUniLoc)

    for ((extId, extVal) <- extData) {
      val transform = Matrix4f.translate3D(extVal.data.position) * Physics.matrixForOrientation(extVal.data.orientation).toHomogeneous()
      Rendering.renderShip(extId, shipMesh, transform, cameraTransformInv, gl, positionAttrLoc, normalAttrLoc, modelViewUniLoc, modelViewInvTrUniLoc, diffuseColorUniLoc)
    }

    gl.disableVertexAttribArray(normalAttrLoc)
    gl.disableVertexAttribArray(positionAttrLoc)

    // Ending
    continueCond = continueCond && itf.update()
  }
}
