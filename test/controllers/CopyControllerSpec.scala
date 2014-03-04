package controllers

import akka.util.Timeout
import integration.Help
import model.CopyLocale
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import play.api.GlobalSettings
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.test._
import scala.Some
import scala.concurrent.ExecutionContext.Implicits.global
import support.FakeRepositories

class CopyControllerSpec
  extends WordSpec with BeforeAndAfter with ShouldMatchers with ScalaFutures
  with ResultExtractors with HeaderNames with Status with Results with PlayRunners with Writeables
  with FakeRepositories
{
  val controller = new CopyController(localeRepo, versionRepo, copyRepo, userRepo)
  implicit val timeout = Timeout(10000L)

  object TestGlobal extends GlobalSettings {
    override def getControllerInstance[A](controllerClass: Class[A]): A =
      controller.asInstanceOf[A]
  }

  before {
    clearFakeRepos()

    Help.waitFor(localeRepo.defineLocales(CopyLocale("en", "English", None, isLive = true)))
    val locale = localeRepo.forId("en")
    Help.waitFor(userRepo.addUser("jwebb", "password", isAdmin = true))
    Help.waitFor(copyRepo.addVersion("test.key", locale, "Some text", "jwebb", retranslate = true))
  }

  def doLogin(password: String = "password"): String = {
    val result = Help.waitFor(WS.url("http://localhost:18888/login").withHeaders(
      CONTENT_TYPE -> "application/x-www-form-urlencoded"
    ).withFollowRedirects(follow = false).post(
        s"user=jwebb&password=$password"
      ))
    result.status should === (303)
    result.header(SET_COOKIE).get
  }

  "the controller" should {
    "return a version as JSON" in {
      val result = controller.download("latest")(FakeRequest())
      contentType(result) should === (Some("application/json"))
      val json = contentAsJson(result)
      (json \ "version").as[Int] should === (1)
      (json \ "messages" \ "en" \ "test.key").as[String] should === ("Some text")
    }

    "support adding new keys" in {
      running(TestServer(18888, FakeApplication(withGlobal = Some(TestGlobal)))) {
        val cookie = doLogin()
        val result = Help.waitFor(WS.url("http://localhost:18888/latest/en/new.key").withHeaders(
          COOKIE -> cookie
        ).withFollowRedirects(follow = false).put(Json.obj(
          "text" -> "New text",
          "retranslate" -> true
        )))
        result.status should === (200)

        copyRepo.findBundle(localeRepo.forId("en"), 2).map(_("new.key").text).futureValue should === ("New text")
      }
    }

    "support approving keys" in {
      running(TestServer(18888, FakeApplication(withGlobal = Some(TestGlobal)))) {
        val cookie = doLogin()
        val result = Help.waitFor(WS.url("http://localhost:18888/latest/en/test.key").withHeaders(
          COOKIE -> cookie
        ).withFollowRedirects(follow = false).post(Json.obj(
          "action" -> "approve"
        )))
        result.status should === (200)

        copyRepo.findBundle(localeRepo.forId("en"), 1).map(_("test.key").approvals(0).user).futureValue should === ("jwebb")
      }
    }
  }
}
