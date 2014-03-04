package controllers

import model._
import play.api.mvc._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.mvc.Action
import scala.Some
import play.api.libs.json.{JsString, JsObject, Json}
import org.joda.time.DateTime
import play.api.data.{Form, Forms}

case class LoginData(user: String, password: String)
case class PasswordData(old: String, new1: String, new2: String)

class CopyController(
  localeRepo: LocaleRepository,
  versionRepo: VersionTagRepository,
  copyRepo: CopyRepository,
  userRepo: UserRepository
) extends Controller {
  private def withVersion(versionTag: String)(fn: Int => Future[SimpleResult]): Future[SimpleResult] = {
    VersionTag(versionTag) match {
      case Some(tag) =>
        versionRepo(tag).flatMap(version => fn(version))
      case None =>
        Future.successful(NotFound("No such version") )
    }
  }

  private def withLocale(localeId: String)(fn: CopyLocale => Future[SimpleResult]): Future[SimpleResult] = {
    localeRepo.all().get(localeId) match {
      case Some(locale) =>
        fn(locale)
      case None =>
        Future.successful(NotFound("No such locale"))
    }
  }

  def withAuth(f: String => Request[AnyContent] => Future[SimpleResult]): EssentialAction = {
    def username(request: RequestHeader) = request.session.get("user")
    def onUnauthorized(request: RequestHeader) = Results.Redirect(routes.CopyController.login)
    Security.Authenticated(username, onUnauthorized) { user =>
      Action.async(request => f(user)(request))
    }
  }

  def index = Action { request =>
    Redirect(routes.CopyController.workflow(VersionTag.Latest.id, localeRepo.primary.id))
  }

  def workflow(versionTag: String, localeId: String) =
    withAuth { user => request =>
      withVersion(versionTag) { version =>
        withLocale(localeId) { locale =>
          for {
            bundle <- copyRepo.findBundle(locale, version)
            primaryBundle <- copyRepo.findBundle(localeRepo.primary, version)
          } yield {
            Ok(views.html.workflow(
              versionTags = VersionTag.all,
              locales = localeRepo.all().values.toSeq,
              currentTag = VersionTag(versionTag).get,
              currentLocale = locale,
              primaryBundle = primaryBundle,
              bundle = bundle,
              user = user
            ))
          }
        }
      }
    }

  def action(versionTag: String, localeId: String, key: String) =
    withAuth { user => request =>
      withVersion(versionTag) { version =>
        withLocale(localeId) { locale =>
          val body = request.body.asJson.get
          val approved = (body \ "action").as[String] match {
            case "approve" => true
            case "reject" => false
            case _ => throw new IllegalArgumentException("Uknown action")
          }
          copyRepo.addApprovalOrRejection(key, locale, version, "jwebb", approved = approved).map(x =>
            Ok(Json.obj())
          )
        }
      }
    }

  def put(versionTag: String, localeId: String, key: String) =
    withAuth { user => request =>
      withVersion(versionTag) { version =>
        withLocale(localeId) { locale =>
          val body = request.body.asJson.get
          val text = (body \ "text").as[String]
          val retranslate = (body \ "retranslate").as[Boolean]
          for {
            version <- copyRepo.addVersion(key, locale, text, "jwebb", retranslate)
          } yield {
            Ok(Json.obj("version" -> version))
          }
        }
      }
    }

  def download(versionTag: String) = Action.async { request =>
    withVersion(versionTag) { version =>
      if (request.headers.get(ETAG).map(_.toInt) == Some(version)) {
        Future.successful(NotModified)
      } else {
        val bundleFutures =
          for {
            locale <- localeRepo.all().values if locale.isLive
          } yield for {
            bundle <- copyRepo.findBundle(locale, version)
          } yield {
            val content = for ((k, v) <- bundle.toSeq) yield k -> JsString(v.text)
            locale.id -> JsObject(content)
          }

        for {
          bundles <- Future.sequence(bundleFutures)
        } yield {
          Ok(Json.obj(
            "version" -> version,
            "date" -> DateTime.now().toString(),
            "messages" -> JsObject(bundles.toSeq)
          )).withHeaders(
            ETAG -> version.toString
          )
        }
      }
    }
  }

  val loginForm =
    Form(Forms.mapping(
      "user" -> Forms.nonEmptyText,
      "password" -> Forms.nonEmptyText
    )(LoginData.apply)(LoginData.unapply))

  def login() = Action {
    Ok(views.html.login(loginForm)).withNewSession
  }

  def loginPost() = Action.async { implicit request =>
    val completedForm = loginForm.bindFromRequest()
    completedForm.fold(
      hasErrors = { form =>
        Future.successful(BadRequest(views.html.login(form)))
      },
      success = { data =>
        for {
          result <- userRepo.authenticate(data.user, data.password)
        } yield {
          result match {
            case Some(user) =>
              Redirect(routes.CopyController.index()).withNewSession.withSession("user" -> user.name)
            case None =>
              BadRequest(views.html.login(completedForm.withGlobalError("Login failed")))
          }
        }
      }
    )
  }

  val passwordForm =
    Form(Forms.mapping(
      "old" -> Forms.nonEmptyText,
      "new1" -> Forms.nonEmptyText,
      "new2" -> Forms.nonEmptyText
    )(PasswordData.apply)(PasswordData.unapply))

  def password() = withAuth { user => request =>
    Future.successful(Ok(views.html.password(passwordForm)))
  }

  def passwordPost() = withAuth { userName => implicit request =>
    val completedForm = passwordForm.bindFromRequest()
    completedForm.fold(
      hasErrors = { form =>
        Future.successful(BadRequest(views.html.password(form)))
      },
      success = { data =>
        for {
          result <- userRepo.authenticate(userName, data.old)
          done <- result match {
            case Some(user) =>
              if (data.new1 == data.new2) {
                userRepo.changePassword(user, data.new1).map { x =>
                  Redirect(routes.CopyController.index())
                }
              } else {
                Future.successful(BadRequest(views.html.password(completedForm.withError("new2", "Passwords don't match"))))
              }
            case None =>
              Future.successful(BadRequest(views.html.password(completedForm.withError("old", "Incorrect password"))))
          }
        } yield done
      }
    )
  }
}
