package games.demo

import games.math._

object Physics {
  final val playerRadius = 0.5f

  /**
   * Sets an angle in degrees in the interval ]180, 180]
   */
  def angleCentered(angle: Float): Float = {
    var ret = angle
    while (ret > 180f) ret -= 360f
    while (ret <= -180f) ret += 360f
    ret
  }

  /**
   * Sets an angle in degrees in the interval [0, 360[
   */
  def anglePositive(angle: Float): Float = {
    var ret = angle
    while (ret >= 360f) ret -= 360f
    while (ret < 0f) ret += 360f
    ret
  }

  def interpol(curIn: Float, minIn: Float, maxIn: Float, startValue: Float, endValue: Float): Float = startValue + (curIn - minIn) * (endValue - startValue) / (maxIn - minIn)

  object Wall {
    var map: Map = _

    def setup(map: Map): Unit = {
      this.map = map
    }

    def playerCollision(playerPos: Vector2f): Unit = {
      for (wall <- map.ctWalls) {
        val pos = wall.position
        val length = wall.length
        val halfLength = wall.halfLength
        if (Math.abs(pos.y - playerPos.y) < playerRadius && Math.abs(pos.x - playerPos.x) < (playerRadius + halfLength)) { // AABB test
          if (Math.abs(pos.x - playerPos.x) < halfLength) { // front contact
            playerPos.y = pos.y + playerRadius
          } else { // contact on the corner
            val cornerPos = if (playerPos.x > pos.x) { // Right corner
              pos + new Vector2f(halfLength, 0)
            } else { // Left corner
              pos + new Vector2f(-halfLength, 0)
            }
            val diff = (playerPos - cornerPos)
            if (diff.length() < playerRadius) {
              diff.normalize()
              diff *= playerRadius
              Vector2f.set(cornerPos + diff, playerPos)
            }
          }
        }
      }

      for (wall <- map.cbWalls) {
        val pos = wall.position
        val length = wall.length
        val halfLength = wall.halfLength
        if (Math.abs(pos.y - playerPos.y) < playerRadius && Math.abs(pos.x - playerPos.x) < (playerRadius + halfLength)) { // AABB test
          if (Math.abs(pos.x - playerPos.x) < halfLength) { // front contact
            playerPos.y = pos.y - playerRadius
          } else { // contact on the corner
            val cornerPos = if (playerPos.x > pos.x) { // Right corner
              pos + new Vector2f(halfLength, 0)
            } else { // Left corner
              pos + new Vector2f(-halfLength, 0)
            }
            val diff = (playerPos - cornerPos)
            if (diff.length() < playerRadius) {
              diff.normalize()
              diff *= playerRadius
              Vector2f.set(cornerPos + diff, playerPos)
            }
          }
        }
      }

      for (wall <- map.clWalls) {
        val pos = wall.position
        val length = wall.length
        val halfLength = wall.halfLength
        if (Math.abs(pos.x - playerPos.x) < playerRadius && Math.abs(pos.y - playerPos.y) < (playerRadius + halfLength)) { // AABB test
          if (Math.abs(pos.y - playerPos.y) < halfLength) { // front contact
            playerPos.x = pos.x + playerRadius
          } else { // contact on the corner
            val cornerPos = if (playerPos.y > pos.y) { // down corner
              pos + new Vector2f(0, halfLength)
            } else { // up corner
              pos + new Vector2f(0, -halfLength)
            }
            val diff = (playerPos - cornerPos)
            if (diff.length() < playerRadius) {
              diff.normalize()
              diff *= playerRadius
              Vector2f.set(cornerPos + diff, playerPos)
            }
          }
        }
      }

      for (wall <- map.crWalls) {
        val pos = wall.position
        val length = wall.length
        val halfLength = wall.halfLength
        if (Math.abs(pos.x - playerPos.x) < playerRadius && Math.abs(pos.y - playerPos.y) < (playerRadius + halfLength)) { // AABB test
          if (Math.abs(pos.y - playerPos.y) < halfLength) { // front contact
            playerPos.x = pos.x - playerRadius
          } else { // contact on the corner
            val cornerPos = if (playerPos.y > pos.y) { // down corner
              pos + new Vector2f(0, halfLength)
            } else { // up corner
              pos + new Vector2f(0, -halfLength)
            }
            val diff = (playerPos - cornerPos)
            if (diff.length() < playerRadius) {
              diff.normalize()
              diff *= playerRadius
              Vector2f.set(cornerPos + diff, playerPos)
            }
          }
        }
      }
    }
  }
}
