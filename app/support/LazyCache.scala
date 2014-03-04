package support

import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, MILLISECONDS}

object LazyCache {
  /** Builds a LazyCache with the default 5 second time-to-live and global thread pool. */
  def apply[T](loader: => T): LazyCache[T] =
    new LazyCache(5000L, loader)(ExecutionContext.global)

  /** Builds a default LazyCache where the loader returns Futures. */
  def forFuture[T](loader: => Future[T]): LazyCache[T] =
    new LazyCache(5000L, Await.result(loader, Duration(30000L, MILLISECONDS)))(ExecutionContext.global)
}

/** Asynchronously refreshed cache. We only trigger refreshes when the data is requested, so it will
  * generally take two requests with a pause between before new data is seen. */
class LazyCache[T](ttl: Long, loader: => T)(implicit context: ExecutionContext) {
  @volatile private var value: T = null.asInstanceOf[T]
  @volatile private var timestamp = 0L
  @volatile private var currentFuture: Future[T] = null
  private val lock = new ReentrantLock()

  /** Gets the current value, and triggers a background refresh if required. May block while fetching data on the
    * first call. */
  def apply(): T = {
    value match {
      case null =>
        refreshSync()
      case x =>
        if (timestamp + ttl < System.currentTimeMillis()) {
          refreshAsync()
        }
        x
    }
  }

  /** Gets the current value as a Future. This will be already resolved unless there's no value cached yet. */
  def future(): Future[T] = {
    value match {
      case null =>
        currentFuture match {
          case null => refreshAsync()
          case x => x
        }
      case x =>
        if (timestamp + ttl < System.currentTimeMillis()) {
          refreshAsync()
        }
        Future.successful(x)
    }
  }

  /** Triggers a refresh and returns the future result. */
  def refreshAsync(): Future[T] = {
    if (currentFuture == null || currentFuture.isCompleted) {
      // We accept the possibility of multiple concurrent writes here
      currentFuture = Future { refreshSync() }
    }
    currentFuture
  }

  /** Refreshes synchronously and returns the new value (unless some other thread is already doing a refresh,
    * in which case we just wait for the other thread's value to become available). */
  def refreshSync(): T = {
    if (lock.tryLock()) {
      // We are the only thread doing a refresh
      try {
        value = loader
        timestamp = System.currentTimeMillis()
        value
      } finally {
        lock.unlock()
      }
    } else {
      // Some other thread got there first, so wait for it
      lock.lock()
      try {
        if (value == null) {
          // Presumably the other thread failed
          value = loader
          timestamp = System.currentTimeMillis()
        }
        value
      } finally {
        lock.unlock()
      }
    }
  }

  /** If there is a new value currently being loaded, waits for that value to become available. */
  def waitForUnlock() {
    lock.lock()
    lock.unlock()
  }
}
