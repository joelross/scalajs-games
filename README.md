# Scalajs-games

Library for graphics/audio/inputs for Scala.js

## Demo

### Commands

* Press **Escape** to exit
* Press **F** to toggle fullscreen
* Press **L** to toggle pointer lock
* Maintain **W** to accelerate or **S** to brake
* Left mouse button to shoot and mouse movement to navigate

Players are dispatched in room of up to 4 players.

### Launching

#### General

The address the clients will attempt to reach is located in ```demo/shared/src/main/resources/games/demo/config``` (```ws://localhost:8080/``` by default, which should be fine if you are using the included server locally).

#### Server + Scala.js client

* Run ```sbt```. Once in SBT, enter ```serverDemoJS/reStart```. This will start the server and make the Scala.js client available through it (press Ctrl + C to stop the server and exit SBT).
* To use the Scala.js client, open your browser (preferably Chrome or Firefox) to the specified address (normally [http://localhost:8080/](http://localhost:8080/)).

#### JVM client (requires a running server to connect to)

Run ```sbt "demoJVM/run"```.

## License

Scalajs-games code itself is under the BSD license

The dependencies for the JVM code are:
* [LWJGL](https://github.com/LWJGL/lwjgl)
* [JOrbis](http://www.jcraft.com/jorbis/)

The dependencies for the Scala.js code are:
* [Aurora.js](https://github.com/audiocogs/aurora.js) (optional)
* [ogg.js](https://github.com/audiocogs/ogg.js) (with underlying [libogg](https://xiph.org/ogg/)) (optional)
* [vorbis.js](https://github.com/audiocogs/vorbis.js) (with underlying [libvorbis](https://xiph.org/vorbis/)) (optional)
