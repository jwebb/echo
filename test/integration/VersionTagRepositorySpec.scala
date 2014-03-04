package integration

import model.{MongoVersionTagRepository, VersionTag}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Span, Seconds, Millis}
import org.scalatest.{ShouldMatchers, BeforeAndAfter, WordSpec}
import scala.concurrent.ExecutionContext.Implicits.global

class VersionTagRepositorySpec extends WordSpec with BeforeAndAfter with ShouldMatchers with ScalaFutures {
  override implicit val patienceConfig =
    PatienceConfig(timeout =  Span(10, Seconds), interval = Span(50, Millis))
  val repository = new MongoVersionTagRepository(Help.db)

  before {
    Help.lock()
    Help.clear("versionTags")
  }

  after {
    Help.unlock()
  }

  "the version tag repository" should {
    "default to zero" in {
      repository(VersionTag.Committed).futureValue should === (0)
      repository(VersionTag.Proposed).futureValue should === (0)
      repository(VersionTag.Live).futureValue should === (0)
    }

    "reflect updates" in {
      Help.waitFor(repository.update(VersionTag.Committed, 3))
      Help.waitFor(repository.update(VersionTag.Live, 5))

      repository(VersionTag.Committed).futureValue should === (3)
      repository(VersionTag.Proposed).futureValue should === (0)
      repository(VersionTag.Live).futureValue should === (5)
    }

    "handle many updates" in {
      Help.waitFor(repository.update(VersionTag.Committed, 3))
      Help.waitFor(repository.update(VersionTag.Committed, 4))
      Help.waitFor(repository.update(VersionTag.Committed, 6))

      repository(VersionTag.Committed).futureValue should === (6)
    }

    "return incrementing versions" in {
      repository.newVersion().futureValue should === (1)
      repository.newVersion().futureValue should === (2)
      repository.newVersion().futureValue should === (3)
    }
  }
}
