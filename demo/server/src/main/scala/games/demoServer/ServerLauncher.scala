package games.demoServer

import transport._
import transport.netty._

import scala.concurrent.ExecutionContext.Implicits.global

object ServerLauncher extends App {
    println("Server starting...")
    
    val netty = new WebSocketServer(8080, "/ws")
    netty.listen().foreach { promise =>
        promise.success { connection =>
            println("Client connected: " + connection)
            
            connection.handlerPromise.success { m =>
                println("Message received from " + connection + ": " + m)
                connection.write("Hello from server")
            }
        }
    }
    
    System.in.read()
    
    println("Server closing...")
    netty.shutdown()
    println("Server closed")
}
