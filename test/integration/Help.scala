package integration

import java.util.concurrent.locks.ReentrantLock
import reactivemongo.api.MongoDriver
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object Help {
  private val globalLock = new ReentrantLock()
  def lock() = globalLock.lock()
  def unlock() = globalLock.unlock()

  def waitFor[T](f : Future[T]) =
    Await.result(f, Duration(10, SECONDS))

  def clear(collections: String*) {
    for (c <- collections)
      Help.waitFor(db[BSONCollection](c).remove(BSONDocument()))
  }

  val db = new MongoDriver().connection(List("localhost"))("repositorytest")
}
