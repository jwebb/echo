package integration

import model.{MongoLocaleRepository, CopyLocale}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ShouldMatchers, BeforeAndAfter, WordSpec}
import scala.concurrent.ExecutionContext.Implicits.global

class LocaleRepositorySpec extends WordSpec with BeforeAndAfter with ShouldMatchers with ScalaFutures {
  private val repository = new MongoLocaleRepository(Help.db)

  before {
    Help.lock()
    Help.clear("locales")
  }

  after {
    Help.unlock()
  }

  "the locale repository" should {
    "start empty" in {
      repository.all() should === (Map.empty)
    }

    "return all defined locales" in {
      val f = repository.defineLocales(
        CopyLocale("en_US", "US English", Some("en"), isLive = true),
        CopyLocale("en", "English", None, isLive = true)
      )
      f.map(_.keySet).futureValue should === (Set("en", "en_US"))
      repository.all().values.map(_.name).toSet should === (Set("English", "US English"))
    }

    "edit locales" in {
      repository.defineLocales(
        CopyLocale("en", "American", None, isLive = true),
        CopyLocale("fr", "French", None, isLive = true)
      ).map(_.mapValues(_.name)).futureValue should === (Map(
        "en" -> "American",
        "fr" -> "French"
      ))

      repository.defineLocales(
        CopyLocale("en", "English", None, isLive = true)
      ).map(_.mapValues(_.name)).futureValue should === (Map(
        "en" -> "English",
        "fr" -> "French"
      ))
    }
  }
}
