package support

import org.scalatest.{Matchers, BeforeAndAfter, WordSpec}
import org.scalatest.concurrent.ScalaFutures
import java.util.concurrent.{TimeUnit, CountDownLatch}
import scala.concurrent.{Future, future}
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.io.IOException

class LazyCacheSpec extends WordSpec with BeforeAndAfter with Matchers with ScalaFutures {
  "the lazy cache" should {
    "block on the first call" in {
      val latch1 = new CountDownLatch(1)
      val latch2 = new CountDownLatch(1)
      val latch3 = new CountDownLatch(1)
      val cache = LazyCache {
        latch1.countDown()
        latch2.await()
        latch3.countDown()
        5
      }
      future {
        latch1.await()
        latch2.countDown()
      }
      latch3.getCount() should === (1)
      cache() should === (5)
      latch3.await()
    }

    "not rerun the loader on the second call" in {
      val i = new AtomicInteger(0)
      val cache = LazyCache {
        i.incrementAndGet()
      }
      cache() should === (1)
      cache() should === (1)
    }

    "rerun the loader after the timeout expires, but return the initial value in the meantime" in {
      val i = new AtomicInteger(0)
      val latch = new CountDownLatch(2)
      val cache = new LazyCache(10L, {
        val r = i.incrementAndGet()
        latch.countDown()
        r
      })
      cache() should === (1)
      Thread.sleep(20L)
      cache() should === (1)
      if (!latch.await(5L, TimeUnit.SECONDS)) fail()
      cache.waitForUnlock()
      cache() should === (2)
    }

    "always rerun for non-concurrent synchronous calls" in {
      val i = new AtomicInteger(0)
      val cache = LazyCache { i.incrementAndGet() }
      cache.refreshSync() should === (1)
      cache.refreshSync() should === (2)
      cache.refreshSync() should === (3)
    }

    "not rerun for concurrent synchronous calls" in {
      val i = new AtomicInteger(0)
      val latch = new CountDownLatch(1)
      val cache = LazyCache {
        latch.await()
        i.incrementAndGet()
      }
      val r1 = future { cache.refreshSync() }
      val r2 = future { cache.refreshSync() }
      Thread.sleep(10L)
      latch.countDown()
      r1.futureValue should === (1)
      r2.futureValue should === (1)
    }

    "return multiple futures for multiple non-overlapping async calls" in {
      val i = new AtomicInteger(0)
      val cache = LazyCache {
        i.incrementAndGet()
      }
      cache.refreshAsync().futureValue should === (1)
      cache.refreshAsync().futureValue should === (2)
    }

    "return a single future for multiple concurrent async calls" in {
      val i = new AtomicInteger(0)
      val latch = new CountDownLatch(1)
      val cache = LazyCache {
        latch.await()
        i.incrementAndGet()
      }
      val r1 = cache.refreshAsync()
      val r2 = cache.refreshAsync()
      val sum = Future.reduce[Int, Int](List(r1, r2))(_ + _)
      latch.countDown()
      sum.futureValue should === (2)
    }

    "behave itself when the loader throws" in {
      val shouldThrow = new AtomicBoolean(true)
      val cache = LazyCache[Int] {
        if (shouldThrow.get())
          throw new IOException("Boo!")
        else
          5
      }

      intercept[IOException] { cache() }
      cache.refreshAsync().fallbackTo(Future(3)).futureValue should === (3)
      intercept[IOException] { cache.refreshSync() }

      shouldThrow.set(false)
      cache.refreshSync() should === (5)
      cache.refreshAsync().futureValue should === (5)
      cache() should === (5)

      shouldThrow.set(true)
      intercept[IOException] { cache.refreshSync() }
      cache.refreshAsync().fallbackTo(Future(7)).futureValue should === (7)
      cache() should === (5)
    }
  }
}
