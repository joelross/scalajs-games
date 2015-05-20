package games.demo

import scala.concurrent.{ Future, ExecutionContext }
import games.{ Utils, Resource }
import games.opengl.{ Token, GLES2 }
import games.math.{ Vector2f, Vector3f, Vector4f, Matrix2f, Matrix3f, Matrix4f }
import games.utils.SimpleOBJParser

import scala.collection.mutable
import scala.collection.immutable

case class OpenGLSubMesh(indicesBuffer: Token.Buffer, verticesCount: Int, ambientColor: Vector3f, diffuseColor: Vector3f, name: String)
case class OpenGLMesh(verticesBuffer: Token.Buffer, normalsBuffer: Token.Buffer, verticesCount: Int, subMeshes: Array[OpenGLSubMesh])

object Rendering {
  def validAttribLocation(aloc: Int): Boolean = aloc >= 0

  def loadAllShaders(resourceFolder: String, gl: GLES2, openglContext: ExecutionContext)(implicit ec: ExecutionContext): Future[immutable.Map[String, Token.Program]] = {
    loadAllFromList(resourceFolder, gl, openglContext, path => { loadShadersFromResourceFolder(path, gl, openglContext) })
  }

  def loadAllModels(resourceFolder: String, gl: GLES2, openglContext: ExecutionContext)(implicit ec: ExecutionContext): Future[immutable.Map[String, OpenGLMesh]] = {
    loadAllFromList(resourceFolder, gl, openglContext, path => { loadModelFromResourceFolder(path, gl, openglContext) })
  }

  def loadAllFromList[T](resourceFolder: String, gl: GLES2, openglContext: ExecutionContext, asyncGet: (String) => Future[T])(implicit ec: ExecutionContext): Future[immutable.Map[String, T]] = {
    val listResource = Resource(resourceFolder + "/list")
    val listFileFuture = Utils.getTextDataFromResource(listResource)
    listFileFuture.flatMap { listFile =>
      val lines = Utils.lines(listFile)
      val dataFutures = lines.map { line =>
        val dataResourcePath = resourceFolder + "/" + line
        asyncGet(dataResourcePath)
      }
      val datasFuture = Future.sequence(dataFutures.toSeq)
      datasFuture.map { seqShaders =>
        lines.zip(seqShaders).toMap
      }
    }
  }

  def loadShadersFromResourceFolder(resourceFolder: String, gl: GLES2, openglContext: ExecutionContext)(implicit ec: ExecutionContext): Future[Token.Program] = {
    val vertexResource = Resource(resourceFolder + "/vertex.c")
    val fragmentResource = Resource(resourceFolder + "/fragment.c")

    val vertexFileFuture = Utils.getTextDataFromResource(vertexResource)
    val fragmentFileFuture = Utils.getTextDataFromResource(fragmentResource)

    val filesFuture = Future.sequence(Seq(vertexFileFuture, fragmentFileFuture))

    filesFuture.map {
      case Seq(vertexFile, fragmentFile) =>
        val program = gl.createProgram()

        def compileShader(shaderType: Int, source: String): Token.Shader = {
          val shader = gl.createShader(shaderType)
          gl.shaderSource(shader, source)
          gl.compileShader(shader)

          // Check for compilation error
          if (gl.getShaderParameterb(shader, GLES2.COMPILE_STATUS) == false) {
            val msg = gl.getShaderInfoLog(shader)
            throw new RuntimeException("Error in the compilation of the shader: " + msg)
          }

          gl.attachShader(program, shader)
          shader
        }

        val vertexShader = compileShader(GLES2.VERTEX_SHADER, vertexFile)
        val fragmentShader = compileShader(GLES2.FRAGMENT_SHADER, fragmentFile)
        gl.linkProgram(program)

        // Check for linking error
        if (gl.getProgramParameterb(program, GLES2.LINK_STATUS) == false) {
          val msg = gl.getProgramInfoLog(program)
          throw new RuntimeException("Error in the linking of the program: " + msg)
        }

        gl.checkError()

        program
    }(openglContext)
  }

  def loadModelFromResourceFolder(resourceFolder: String, gl: GLES2, openglContext: ExecutionContext)(implicit ec: ExecutionContext): Future[OpenGLMesh] = {
    val mainResource = Resource(resourceFolder + "/main")
    val mainFileFuture = Utils.getTextDataFromResource(mainResource)
    mainFileFuture.flatMap { mainFile =>
      val mainLines = Utils.lines(mainFile)

      var nameOpt: Option[String] = None
      var objPathOpt: Option[String] = None
      val mtlPaths: mutable.Queue[String] = mutable.Queue()

      mainLines.foreach { line =>
        val tokens = line.split("=", 2)
        if (tokens.size != 2) throw new RuntimeException("Main model file malformed: \"" + line + "\"")
        val key = tokens(0)
        val value = tokens(1)

        key match {
          case "name" => nameOpt = Some(value)
          case "obj"  => objPathOpt = Some(value)
          case "mtl"  => mtlPaths += value
          case _      => Console.err.println("Warning: unknown model key in line: \"" + line + "\"")
        }
      }

      def missing(missingKey: String) = throw new RuntimeException("Missing key for " + missingKey + " in model")

      val name = nameOpt.getOrElse(missing("name"))
      val objPath = objPathOpt.getOrElse(missing("obj path"))

      val objResource = Resource(resourceFolder + "/" + objPath)
      val objFileFuture = Utils.getTextDataFromResource(objResource)

      val mtlFileFutures = for (mtlPath <- mtlPaths) yield {
        val mtlResource = Resource(resourceFolder + "/" + mtlPath)
        val mtlFileFuture = Utils.getTextDataFromResource(mtlResource)
        mtlFileFuture
      }

      val mtlFilesFuture = Future.sequence(mtlFileFutures)

      val meshFuture = for (
        objFile <- objFileFuture;
        mtlFiles <- mtlFilesFuture
      ) yield {
        val objLines = Utils.lines(objFile)
        val mtlLines = mtlPaths.zip(mtlFiles.map(Utils.lines(_))).toMap

        val objs = SimpleOBJParser.parseOBJ(objLines, mtlLines)
        val meshes = SimpleOBJParser.convOBJObjectToTriMesh(objs)

        val mesh = meshes(name)

        mesh
      }

      // Execute the loading part separately, in the OpenGL context
      meshFuture.map { mesh =>
        val meshVerticesCount = mesh.vertices.length
        val verticesData = GLES2.createFloatBuffer(meshVerticesCount * 3)
        mesh.vertices.foreach { v => v.store(verticesData) }
        assert(verticesData.remaining() == 0) // Sanity check
        verticesData.flip()
        val verticesBuffer = gl.createBuffer()
        gl.bindBuffer(GLES2.ARRAY_BUFFER, verticesBuffer)
        gl.bufferData(GLES2.ARRAY_BUFFER, verticesData, GLES2.STATIC_DRAW)
        val normals = mesh.normals.getOrElse(throw new RuntimeException("Missing normals"))
        assert(meshVerticesCount == normals.length) // Sanity check
        val normalsData = GLES2.createFloatBuffer(meshVerticesCount * 3)
        normals.foreach { v => v.store(normalsData) }
        assert(normalsData.remaining() == 0) // Sanity check
        normalsData.flip()
        val normalsBuffer = gl.createBuffer()
        gl.bindBuffer(GLES2.ARRAY_BUFFER, normalsBuffer)
        gl.bufferData(GLES2.ARRAY_BUFFER, normalsData, GLES2.STATIC_DRAW)
        val openGLSubMeshes = mesh.submeshes.map { submesh =>
          val tris = submesh.tris
          val submeshVerticesCount = tris.length * 3
          val indicesData = GLES2.createShortBuffer(submeshVerticesCount)
          tris.foreach {
            case (i0, i1, i2) =>
              indicesData.put(i0.toShort)
              indicesData.put(i1.toShort)
              indicesData.put(i2.toShort)
          }
          assert(indicesData.remaining() == 0) // Sanity check
          indicesData.flip()
          val indicesBuffer = gl.createBuffer()
          gl.bindBuffer(GLES2.ELEMENT_ARRAY_BUFFER, indicesBuffer)
          gl.bufferData(GLES2.ELEMENT_ARRAY_BUFFER, indicesData, GLES2.STATIC_DRAW)
          val material = submesh.material.getOrElse(throw new RuntimeException("Missing material"))
          OpenGLSubMesh(indicesBuffer, submeshVerticesCount, material.ambientColor.getOrElse(throw new RuntimeException("Missing ambient color")), material.diffuseColor.getOrElse(throw new RuntimeException("Missing ambient color")), material.name)
        }

        gl.checkError()

        OpenGLMesh(verticesBuffer, normalsBuffer, meshVerticesCount, openGLSubMeshes)
      }(openglContext)
    }
  }

  def loadTriMeshFromResourceFolder(resourceFolder: String, gl: GLES2, openglContext: ExecutionContext)(implicit ec: ExecutionContext): Future[games.utils.SimpleOBJParser.TriMesh] = {
    val mainResource = Resource(resourceFolder + "/main")
    val mainFileFuture = Utils.getTextDataFromResource(mainResource)
    mainFileFuture.flatMap { mainFile =>
      val mainLines = Utils.lines(mainFile)

      var nameOpt: Option[String] = None
      var objPathOpt: Option[String] = None
      val mtlPaths: mutable.Queue[String] = mutable.Queue()

      mainLines.foreach { line =>
        val tokens = line.split("=", 2)
        if (tokens.size != 2) throw new RuntimeException("Main model file malformed: \"" + line + "\"")
        val key = tokens(0)
        val value = tokens(1)

        key match {
          case "name" => nameOpt = Some(value)
          case "obj"  => objPathOpt = Some(value)
          case "mtl"  => mtlPaths += value
          case _      => Console.err.println("Warning: unknown model key in line: \"" + line + "\"")
        }
      }

      def missing(missingKey: String) = throw new RuntimeException("Missing key for " + missingKey + " in model")

      val name = nameOpt.getOrElse(missing("name"))
      val objPath = objPathOpt.getOrElse(missing("obj path"))

      val objResource = Resource(resourceFolder + "/" + objPath)
      val objFileFuture = Utils.getTextDataFromResource(objResource)

      val mtlFileFutures = for (mtlPath <- mtlPaths) yield {
        val mtlResource = Resource(resourceFolder + "/" + mtlPath)
        val mtlFileFuture = Utils.getTextDataFromResource(mtlResource)
        mtlFileFuture
      }

      val mtlFilesFuture = Future.sequence(mtlFileFutures)

      for (
        objFile <- objFileFuture;
        mtlFiles <- mtlFilesFuture
      ) yield {
        val objLines = Utils.lines(objFile)
        val mtlLines = mtlPaths.zip(mtlFiles.map(Utils.lines(_))).toMap

        val objs = SimpleOBJParser.parseOBJ(objLines, mtlLines)
        val meshes = SimpleOBJParser.convOBJObjectToTriMesh(objs)

        val mesh = meshes(name)

        mesh
      }
    }
  }

  var projection: Matrix4f = new Matrix4f

  private val fovy: Float = 60f // vertical field of view: 60°
  // render between 1cm and 1km
  private val near: Float = 0.01f
  private val far: Float = 1000f

  def setProjection(width: Int, height: Int)(implicit gl: GLES2): Unit = {
    gl.viewport(0, 0, width, height)
    Matrix4f.setPerspective3D(fovy, width.toFloat / height.toFloat, near, far, projection)
  }

  object Player {
    var mesh: OpenGLMesh = _

    def setup(mesh: OpenGLMesh)(implicit gl: GLES2): Unit = {
      this.mesh = mesh
    }
  }

  object Bullet {
    var mesh: OpenGLMesh = _

    def setup(mesh: OpenGLMesh)(implicit gl: GLES2): Unit = {
      this.mesh = mesh
    }
  }

  object Wall {
    var program: Token.Program = _

    var verticesBuffer: Token.Buffer = _
    var normalsBuffer: Token.Buffer = _
    var indicesBufferBySubmesh: Array[Token.Buffer] = _
    var renderCountBySubmesh: Array[Int] = _

    var submeshCount: Int = _

    var positionAttrLoc: Int = _
    var normalAttrLoc: Int = _
    var projectionUniLoc: Token.UniformLocation = _
    var modelViewUniLoc: Token.UniformLocation = _
    var modelViewInvTrUniLoc: Token.UniformLocation = _

    def setup(program: Token.Program, mesh: games.utils.SimpleOBJParser.TriMesh, map: Map)(implicit gl: GLES2): Unit = {
      this.program = program

      this.positionAttrLoc = gl.getAttribLocation(program, "position")
      this.normalAttrLoc = gl.getAttribLocation(program, "normal")

      this.projectionUniLoc = gl.getUniformLocation(program, "projection")
      this.modelViewUniLoc = gl.getUniformLocation(program, "modelView")
      this.modelViewInvTrUniLoc = gl.getUniformLocation(program, "modelViewInvTr")

      val vertices = mesh.vertices
      val normals = mesh.normals.get

      assert(vertices.length == normals.length) // sanity check

      this.submeshCount = mesh.submeshes.length

      val entityCount = (map.lWalls.length + map.rWalls.length + map.tWalls.length + map.bWalls.length)
      val entityTrisCountBySubmesh = mesh.submeshes.map(_.tris.length)

      val globalVerticesCount = entityCount * vertices.length
      val globalNormalsCount = entityCount * normals.length
      val globalTrisCountBySubmesh = entityTrisCountBySubmesh.map { trisCount => entityCount * trisCount }

      this.renderCountBySubmesh = globalTrisCountBySubmesh.map { trisCount => trisCount * 3 }

      this.renderCountBySubmesh.foreach { renderCount =>
        assert(renderCount <= Short.MaxValue) // Sanity check, make sure the indexing will be within short limits (could use "<= 0xFFFF" as the short are unsigned in latter opengl)
      }

      val globalVerticesData = GLES2.createFloatBuffer(globalVerticesCount * 3) // 3 floats (x, y, z) per vertex
      val globalNormalsData = GLES2.createFloatBuffer(globalNormalsCount * 3) // 3 floats (x, y, z) per normal
      val globalIndicesDataBySubmesh = globalTrisCountBySubmesh.map { trisCount => GLES2.createShortBuffer(trisCount * 3) } // 3 indices (vertices) per triangle

      var indicesOffset = 0

      def extractWalls(walls: Array[Vector2f], orientation: Float): Unit = {
        val wallTransform = Matrix4f.scale3D(new Vector3f(1, 1, 1) * Map.roomSize) * Matrix4f.rotate3D(orientation, Vector3f.Up)
        for (wall <- walls) {
          val pos2d = wall
          val pos3d = new Vector3f(pos2d.x, Map.roomHalfSize, pos2d.y)

          val transform = Matrix4f.translate3D(pos3d) * wallTransform
          val normalTransform = transform.toCartesian().invertedCopy().transposedCopy()

          for (vertex <- vertices) {
            val transformedVertex = (transform * vertex.toHomogeneous()).toCartesian()
            transformedVertex.store(globalVerticesData)
          }
          for (normal <- normals) {
            val transformedNormal = (normalTransform * normal).normalizedCopy()
            transformedNormal.store(globalNormalsData)
          }
          for (i <- 0 until this.submeshCount) {
            val submesh = mesh.submeshes(i)
            val globalIndicesData = globalIndicesDataBySubmesh(i)

            val tris = submesh.tris

            for ((i0, i1, i2) <- tris) {
              globalIndicesData.put((i0 + indicesOffset).toShort)
              globalIndicesData.put((i1 + indicesOffset).toShort)
              globalIndicesData.put((i2 + indicesOffset).toShort)
            }
          }
          indicesOffset += vertices.length
        }
      }

      extractWalls(map.lWalls, 270f)
      extractWalls(map.rWalls, 90f)
      extractWalls(map.tWalls, 180f)
      extractWalls(map.bWalls, 0f)

      assert(globalVerticesData.remaining() == 0) // sanity check
      assert(globalNormalsData.remaining() == 0) // sanity check
      globalIndicesDataBySubmesh.foreach { globalIndicesData =>
        assert(globalIndicesData.remaining() == 0) // sanity check
      }

      globalVerticesData.flip()
      globalNormalsData.flip()
      globalIndicesDataBySubmesh.foreach { indicesData => indicesData.flip() }

      val globalVerticesBuffer = gl.createBuffer()
      val globalNormalsBuffer = gl.createBuffer()
      val globalIndicesBufferBySubmesh = mesh.submeshes.map { _ => gl.createBuffer() }

      gl.bindBuffer(GLES2.ARRAY_BUFFER, globalVerticesBuffer)
      gl.bufferData(GLES2.ARRAY_BUFFER, globalVerticesData, GLES2.STATIC_DRAW)

      gl.bindBuffer(GLES2.ARRAY_BUFFER, globalNormalsBuffer)
      gl.bufferData(GLES2.ARRAY_BUFFER, globalNormalsData, GLES2.STATIC_DRAW)

      for (i <- 0 until this.submeshCount) {
        val globalIndicesBuffer = globalIndicesBufferBySubmesh(i)
        val globalIndicesData = globalIndicesDataBySubmesh(i)

        gl.bindBuffer(GLES2.ELEMENT_ARRAY_BUFFER, globalIndicesBuffer)
        gl.bufferData(GLES2.ELEMENT_ARRAY_BUFFER, globalIndicesData, GLES2.STATIC_DRAW)
      }

      this.verticesBuffer = globalVerticesBuffer
      this.normalsBuffer = globalNormalsBuffer
      this.indicesBufferBySubmesh = globalIndicesBufferBySubmesh

      gl.checkError()
    }

    def init()(implicit gl: GLES2): Unit = {
      gl.useProgram(program)
      gl.uniformMatrix4f(projectionUniLoc, projection)

      gl.enableVertexAttribArray(positionAttrLoc)
      gl.enableVertexAttribArray(normalAttrLoc)
    }

    def close()(implicit gl: GLES2): Unit = {
      gl.disableVertexAttribArray(normalAttrLoc)
      gl.disableVertexAttribArray(positionAttrLoc)
    }

    def render(cameraTransformInv: Matrix4f)(implicit gl: GLES2): Unit = {
      val modelView = cameraTransformInv
      val modelViewInvTr = modelView.invertedCopy().transpose()

      gl.uniformMatrix4f(modelViewUniLoc, modelView)
      gl.uniformMatrix4f(modelViewInvTrUniLoc, modelViewInvTr)

      gl.bindBuffer(GLES2.ARRAY_BUFFER, this.verticesBuffer)
      gl.vertexAttribPointer(positionAttrLoc, 3, GLES2.FLOAT, false, 0, 0)

      gl.bindBuffer(GLES2.ARRAY_BUFFER, this.normalsBuffer)
      gl.vertexAttribPointer(normalAttrLoc, 3, GLES2.FLOAT, false, 0, 0)

      for (i <- 0 until this.submeshCount) {
        val indicesBuffer = this.indicesBufferBySubmesh(i)
        val renderCount = this.renderCountBySubmesh(i)

        gl.bindBuffer(GLES2.ELEMENT_ARRAY_BUFFER, indicesBuffer)
        gl.drawElements(GLES2.TRIANGLES, renderCount, GLES2.UNSIGNED_SHORT, 0)
      }
    }
  }

  object Standard {
    var program: Token.Program = _

    var positionAttrLoc: Int = _
    var normalAttrLoc: Int = _
    var diffuseColorUniLoc: Token.UniformLocation = _
    var projectionUniLoc: Token.UniformLocation = _
    var modelViewUniLoc: Token.UniformLocation = _
    var modelViewInvTrUniLoc: Token.UniformLocation = _

    def setup(program: Token.Program)(implicit gl: GLES2): Unit = {
      this.program = program

      positionAttrLoc = gl.getAttribLocation(program, "position")
      normalAttrLoc = gl.getAttribLocation(program, "normal")

      diffuseColorUniLoc = gl.getUniformLocation(program, "diffuseColor")
      projectionUniLoc = gl.getUniformLocation(program, "projection")
      modelViewUniLoc = gl.getUniformLocation(program, "modelView")
      modelViewInvTrUniLoc = gl.getUniformLocation(program, "modelViewInvTr")
    }

    def init()(implicit gl: GLES2): Unit = {
      gl.useProgram(program)
      gl.uniformMatrix4f(projectionUniLoc, projection)

      gl.enableVertexAttribArray(positionAttrLoc)
      gl.enableVertexAttribArray(normalAttrLoc)
    }

    def close()(implicit gl: GLES2): Unit = {
      gl.disableVertexAttribArray(normalAttrLoc)
      gl.disableVertexAttribArray(positionAttrLoc)
    }

    def render(playerId: Int, mesh: OpenGLMesh, transform: Matrix4f, cameraTransformInv: Matrix4f)(implicit gl: GLES2): Unit = {
      val modelView = cameraTransformInv * transform
      val modelViewInvTr = modelView.invertedCopy().transpose()

      gl.uniformMatrix4f(modelViewUniLoc, modelView)
      gl.uniformMatrix4f(modelViewInvTrUniLoc, modelViewInvTr)

      gl.bindBuffer(GLES2.ARRAY_BUFFER, mesh.verticesBuffer)
      gl.vertexAttribPointer(positionAttrLoc, 3, GLES2.FLOAT, false, 0, 0)
      gl.bindBuffer(GLES2.ARRAY_BUFFER, mesh.normalsBuffer)
      gl.vertexAttribPointer(normalAttrLoc, 3, GLES2.FLOAT, false, 0, 0)
      mesh.subMeshes.foreach { submesh =>
        val color = if (submesh.name == "[player]") Data.colors(playerId) else submesh.diffuseColor
        gl.uniform3f(diffuseColorUniLoc, color)
        gl.bindBuffer(GLES2.ELEMENT_ARRAY_BUFFER, submesh.indicesBuffer)
        gl.drawElements(GLES2.TRIANGLES, submesh.verticesCount, GLES2.UNSIGNED_SHORT, 0)
      }
    }
  }
}
