import controllers.CopyController
import model._
import play.api.{GlobalSettings, Application}
import reactivemongo.api.MongoDriver
import scala.Some
import scala.concurrent.ExecutionContext.Implicits.global

object Global extends GlobalSettings {
  private val driver = new MongoDriver()
  private val conn = driver.connection(List("localhost"))
  private val db = conn("copy")

  private val localeRepo = new MongoLocaleRepository(db)
  private val versionRepo = new MongoVersionTagRepository(db)
  private val copyRepo = new MongoCopyRepository(db, localeRepo, versionRepo)
  private val userRepo = new MongoUserRepository(db)

  override def beforeStart(app: Application) {
    localeRepo.defineLocales(
      CopyLocale("en", "English", None, isLive = true),
      CopyLocale("zh_CN", "Chinese (Simplified)", Some("en"), isLive = true)
    )

    userRepo.addUser("jwebb", "password1", isAdmin = true)
  }

  override def getControllerInstance[A](controllerClass: Class[A]): A =
    if (controllerClass == classOf[CopyController]) {
      new CopyController(localeRepo, versionRepo, copyRepo, userRepo).asInstanceOf[A]
    } else {
      throw new IllegalArgumentException("No such controller")
    }
}
