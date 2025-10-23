// utils/RandomUtils.scala
package utils

import java.util.concurrent.ThreadLocalRandom
//import scala.util.Random

object RandomUtils {

  // Production RNG (thread local); tests can pass an explicit scala.util.Random
  private def tlr = ThreadLocalRandom.current()

  def randomIntBetween(min: Int, max: Int): Int = {
    between(min, max)
  }

  def randomBetween(min: Int, max: Int): Long = {
    between(min, max).toLong
  }

  def between(min: Int, max: Int, rnd: ThreadLocalRandom = tlr): Int = {
    require(max >= min, s"max($max) < min($min)")
    // bound is exclusive
    rnd.nextInt((max - min) + 1) + min
  }

  def betweenLong(min: Long, max: Long, rnd: ThreadLocalRandom = tlr): Long = {
    require(max >= min, s"max($max) < min($min)")
    val bound = (max - min) + 1
    // ThreadLocalRandom handles large bounds without bias
    rnd.nextLong(bound) + min
  }

  // Replaces duplicated generateRandomDelay
  def delay(range: (Int, Int)): Long = {
    val (min, max) = range
    between(min, max).toLong
  }

  def chance(p: Double): Boolean = {
    require(p >= 0.0 && p <= 1.0, s"p=$p out of range")
    tlr.nextDouble() < p
  }

  def pickOne[A](coll: Iterable[A]): Option[A] = {
    if (coll.isEmpty) None
    else {
      val idx = between(0, coll.size - 1)
      Some(coll.iterator.drop(idx).next())
    }
  }



//  def pickN[A](coll: IndexedSeq[A], n: Int): IndexedSeq[A] = {
//    if (n <= 0 || coll.isEmpty) IndexedSeq.empty
//    else {
//      // Fisher–Yates partial shuffle
//      val arr = coll.toArray
//      val limit = math.min(n, arr.length)
//      var i = 0
//      while (i < limit) {
//        val j = between(i, arr.length - 1)
//        val tmp = arr(i); arr(i) = arr(j); arr(j) = tmp
//        i += 1
//      }
//      arr.take(limit).toIndexedSeq
//    }
//  }

}