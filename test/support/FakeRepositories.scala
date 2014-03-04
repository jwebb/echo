package support

import model.VersionTag.UpdatableTag
import model._
import org.joda.time.DateTime
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait FakeRepositories {
  object localeRepo extends LocaleRepository {
    private val locales = new mutable.HashMap[String, CopyLocale]
    def defineLocales(newLocales: CopyLocale*): Future[Map[String, CopyLocale]] = {
      locales ++= newLocales.map(x => x.id -> x)
      Future.successful(all())
    }
    def all(): Map[String, CopyLocale] = locales.toMap
    def forId(id: String): CopyLocale = all()(id)
    def primary = all()("en")
    def clear() = locales.clear()
  }

  object versionRepo extends VersionTagRepository {
    val versions = new mutable.HashMap[VersionTag.Tag, Int]()
    clear()

    def update(tag: UpdatableTag, value: Int): Future[Unit] = Future.successful(versions(tag) = value)
    def apply(tag: VersionTag.Tag): Future[Int] = Future.successful(versions(tag))
    def newVersion(): Future[Int] = {
      val last = versions(VersionTag.Latest)
      versions(VersionTag.Latest) = last + 1
      Future.successful(last + 1)
    }
    def clear() {
      versions.clear()
      VersionTag.all.foreach(t => versions(t) = 0)
    }
  }

  object copyRepo extends CopyRepository {
    val copy = new mutable.ArrayBuffer[(String, CopyLocale, Int, BaseCopyRecord)]

    def addApprovalOrRejection(key: String, locale: CopyLocale, version: Int, user: String, approved: Boolean): Future[Unit] = {
      val (i, v, r) = copy.zipWithIndex.collect{ case ((`key`, `locale`, rv, rr), ri) if rv <= version => (ri, rv, rr) }.last
      val action = Action(user, DateTime.now())
      val nr =
        if (approved) r.copy(approvals = action :: r.approvals.toList)
        else r.copy(rejections = action :: r.rejections.toList)
      Future.successful(copy(i) = (key, locale, v, nr))
    }

    def findBundle(locale: CopyLocale, version: Int): Future[Map[String, CopyRecord]] =
      for {
        parent <- locale.parent match {
          case Some(p) => findBundle(localeRepo.forId(p), version).map(_.mapValues(r => new CopyRecord(r, true, r.version)))
          case None => Future.successful(Map())
        }
      } yield {
        parent ++ copy.collect{
          case (key, `locale`, v, r) if v <= version => key -> new CopyRecord(r, false, v)
        }.foldLeft(Map[String, CopyRecord]())(_ + _)
      }

    def versionHistory(key: String, locale: CopyLocale): Future[Seq[CopyRecord]] =
      Future.successful(copy.collect{ case (`key`, `locale`, v, r) => new CopyRecord(r, false, v) })

    def addVersion(key: String, locale: CopyLocale, text: String, user: String, retranslate: Boolean): Future[Int] =
      versionRepo.newVersion().map { v =>
        val r = BaseCopyRecord(text, Action(user, DateTime.now()), wasImport = false, retranslate = retranslate, Nil, Nil, Nil)
        copy.append((key, locale, v, r))
        v
      }

    def clear(): Unit = copy.clear()
  }

  object userRepo extends UserRepository {
    val users = new mutable.HashMap[String, User]
    def addUser(name: String, password: String, isAdmin: Boolean): Future[Unit] =
      Future.successful(users(name) = User(name, password, isAdmin))
    def authenticate(name: String, password: String): Future[Option[User]] =
      Future.successful(users.get(name) match {
        case Some(user) if user.hashedPassword == password => Some(user)
        case _ => None
      })
    def changePassword(user: User, password: String): Future[Unit] =
      Future.successful(users(user.name) = user.copy(hashedPassword = password))
    def clear() = users.clear()
  }

  def clearFakeRepos() {
    localeRepo.clear()
    versionRepo.clear()
    copyRepo.clear()
    userRepo.clear()
  }
}
