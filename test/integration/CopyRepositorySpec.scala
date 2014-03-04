package integration

import model._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.ExecutionContext.Implicits.global
import support.FakeRepositories

class CopyRepositorySpec extends CopyRepositoryBase {
  val localeRepo = new MongoLocaleRepository(Help.db)
  val versionRepo = new MongoVersionTagRepository(Help.db)
  val copyRepo = new MongoCopyRepository(Help.db, localeRepo, versionRepo)

  before {
    Help.lock()
    Help.clear("locales", "versionTags", "copy")
  }

  after {
    Help.unlock()
  }
}

class FakeCopyRepositorySpec extends CopyRepositoryBase with FakeRepositories {
  before {
    clearFakeRepos()
  }
}

trait CopyRepositoryBase extends WordSpec with BeforeAndAfter with ShouldMatchers with ScalaFutures {
  val localeRepo: LocaleRepository
  val versionRepo: VersionTagRepository
  val copyRepo: CopyRepository

  "the copy repository" should {
    "add copy and return the latest" in {
      val locale = Help.waitFor(localeRepo.defineLocales(CopyLocale("en", "English", None, isLive = true)))("en")
      copyRepo.addVersion("test.key1", locale, "Example copy", "x", retranslate = false).futureValue should === (1)
      copyRepo.addVersion("test.key2", locale, "More example copy", "x", retranslate = false).futureValue should === (2)
      copyRepo.addVersion("test.key1", locale, "Updated example copy", "x", retranslate = false).futureValue should === (3)

      val bundle = copyRepo.findBundle(locale, 3).map(_.mapValues(_.text))
      bundle.futureValue should === (Map(
        "test.key1" -> "Updated example copy",
        "test.key2" -> "More example copy"
      ))
    }

    "support inheritance" in {
      val locales = Help.waitFor(localeRepo.defineLocales(
        CopyLocale("en_GB", "English (Traditional)", None, isLive = true),
        CopyLocale("en_US", "English (Simplified)", Some("en_GB"), isLive = true)
      ))

      copyRepo.addVersion("shape", locales("en_GB"), "What shape?", "x", retranslate = false).futureValue should === (1)
      copyRepo.addVersion("colour", locales("en_GB"), "What colour?", "x", retranslate = false).futureValue should === (2)
      copyRepo.addVersion("colour", locales("en_US"), "What color?", "x", retranslate = false).futureValue should === (3)

      val en_GB = copyRepo.findBundle(locales("en_GB"), 3).map(_.mapValues(_.text))
      val en_US = copyRepo.findBundle(locales("en_US"), 3).map(_.mapValues(_.text))

      en_GB.futureValue should === (Map("shape" -> "What shape?", "colour" -> "What colour?"))
      en_US.futureValue should === (Map("shape" -> "What shape?", "colour" -> "What color?"))
    }

    "return version history" in {
      val locale = Help.waitFor(localeRepo.defineLocales(CopyLocale("en", "English", None, isLive = true)))("en")
      copyRepo.addVersion("test.key1", locale, "Example copy", "x", retranslate = false).futureValue should === (1)
      copyRepo.addVersion("test.key1", locale, "Updated example copy", "x", retranslate = false).futureValue should === (2)

      copyRepo.versionHistory("test.key1", locale).map(_.map(r => r.version -> r.text)).futureValue should === (Seq(
        1 -> "Example copy",
        2 -> "Updated example copy"
      ))
    }

    "add approvals and rejections" in {
      val locale = Help.waitFor(localeRepo.defineLocales(CopyLocale("en", "English", None, isLive = true)))("en")
      copyRepo.addVersion("test.key1", locale, "Example copy", "x", retranslate = false).futureValue should === (1)
      copyRepo.addApprovalOrRejection("test.key1", locale, 1, "jwebb", approved = true).futureValue should === (())
      copyRepo.addApprovalOrRejection("test.key1", locale, 1, "bletton", approved = false).futureValue should === (())

      val record = Help.waitFor(copyRepo.findBundle(locale, 1))("test.key1")
      record.approvals.map(_.user) should === (Seq("jwebb"))
      record.rejections.map(_.user) should === (Seq("bletton"))
    }

    "add approvals to the latest matching version" in {
      val locale = Help.waitFor(localeRepo.defineLocales(CopyLocale("en", "English", None, isLive = true)))("en")
      copyRepo.addVersion("test.key1", locale, "Old copy", "x", retranslate = false).futureValue should === (1)
      copyRepo.addVersion("test.key1", locale, "New copy", "x", retranslate = false).futureValue should === (2)
      copyRepo.addVersion("test.key2", locale, "Other", "x", retranslate = false).futureValue should === (3)
      copyRepo.addVersion("test.key1", locale, "Next copy", "x", retranslate = false).futureValue should === (4)
      copyRepo.addApprovalOrRejection("test.key1", locale, 3, "approver", approved = true).futureValue should === (())

      val record = Help.waitFor(copyRepo.findBundle(locale, 3))("test.key1")
      record.version should === (2)
      record.approvals.map(_.user) should === (Seq("approver"))
    }
  }
}
