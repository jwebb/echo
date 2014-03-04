package model

import reactivemongo.api.DB
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.Macros.Annotations.Key
import reactivemongo.bson.{BSONDocument => BD, Macros}
import reactivemongo.core.commands.{Update, FindAndModify}
import scala.concurrent.{ExecutionContext, Future}

object VersionTag {
  sealed trait Tag extends Product {
    def id = productPrefix.toLowerCase()
    def name = productPrefix
    def allowsEdits = true
  }
  sealed trait UpdatableTag extends Tag {
    override def allowsEdits = false
  }
  case object Live extends UpdatableTag
  case object Proposed extends UpdatableTag
  case object Committed extends UpdatableTag
  case object Latest extends Tag

  val all = Seq(Live, Proposed, Committed, Latest)

  private val map = all.map(x => x.id -> x).toMap
  def apply(id: String):Option[Tag] = map.get(id)
}

trait VersionTagRepository {
  def apply(tag: VersionTag.Tag): Future[Int]
  def update(tag: VersionTag.UpdatableTag, value: Int): Future[Unit]
  def newVersion(): Future[Int]
}

class MongoVersionTagRepository(db: DB)(implicit ctx: ExecutionContext) extends VersionTagRepository {
  case class VersionRecord(@Key("_id") id: String, version: Int)
  private implicit val versionRecordHandler = Macros.handler[VersionRecord]
  private val collection: BSONCollection = db("versionTags")

  def apply(tag: VersionTag.Tag): Future[Int] =
    collection.find(BD("_id" -> tag.productPrefix)).one[VersionRecord].map {
      case Some(x) => x.version
      case None => 0
    }

  def update(tag: VersionTag.UpdatableTag, value: Int): Future[Unit] =
    collection.update(BD("_id" -> tag.productPrefix),
      VersionRecord(tag.productPrefix, value), upsert = true).map(ok => ())

  def newVersion(): Future[Int] =
    db.command(FindAndModify(
      collection.name,
      BD("_id" -> VersionTag.Latest.productPrefix),
      Update(BD("$inc" -> BD("version" -> 1)), fetchNewObject = true),
      upsert = true
    )) map {
      _.get.getAs[Int]("version").get
    }
}
