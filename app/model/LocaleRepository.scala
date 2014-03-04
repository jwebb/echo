package model

import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.DB
import reactivemongo.bson.{BSONDocument => BD, Macros}
import reactivemongo.bson.Macros.Annotations.Key
import scala.concurrent.{Future, ExecutionContext}
import support.LazyCache

case class CopyLocale(@Key("_id") id: String, name: String, parent: Option[String], isLive: Boolean) {
  def isPrimary = (id == "en")
}

trait LocaleRepository {
  def primary: CopyLocale
  def all(): Map[String, CopyLocale]
  def forId(id: String): CopyLocale
  def defineLocales(locales: CopyLocale*): Future[Map[String, CopyLocale]]
}

class MongoLocaleRepository(db: DB)(implicit ctx: ExecutionContext) extends LocaleRepository {
  private implicit val copyLocaleHandler = Macros.handler[CopyLocale]
  private val collection: BSONCollection = db("locales")

  private val cache = LazyCache.forFuture {
    collection.find(BD.empty).cursor[CopyLocale].collect[Seq]().map(xs => xs.map(x => (x.id, x)).toMap)
  }

  def primary: CopyLocale = all().values.find(_.isPrimary).get

  def all(): Map[String, CopyLocale] = cache()

  def forId(id: String): CopyLocale = cache()(id)

  def defineLocales(locales: CopyLocale*): Future[Map[String, CopyLocale]] = {
    val futures =
      for (locale <- locales)
      yield collection.update(BD("_id" -> locale.id), locale, upsert = true)
    Future.sequence(futures).flatMap(rs => cache.refreshAsync())
  }
}
