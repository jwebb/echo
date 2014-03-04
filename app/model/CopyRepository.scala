package model

import org.joda.time.DateTime
import reactivemongo.api.DB
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{BSONDocument => BD, BSON, Macros, BSONDateTime, BSONHandler}
import reactivemongo.core.commands.{Update, FindAndModify}
import scala.concurrent.{ExecutionContext, Future}

case class BaseCopyRecord(
  text: String,
  update: Action,
  wasImport: Boolean,
  retranslate: Boolean,
  approvals: Seq[Action],
  rejections: Seq[Action],
  conflicts: Seq[String]
)

class CopyRecord(rec: BaseCopyRecord, val inherited: Boolean, val version: Int) extends BaseCopyRecord(
  text = rec.text,
  update = rec.update,
  wasImport = rec.wasImport,
  retranslate = rec.retranslate,
  approvals = rec.approvals,
  rejections = rec.rejections,
  conflicts = rec.conflicts
)

case class Action(user: String, time: DateTime) {
  def friendlyTime = time.toString("dd-MMM-yyyy HH:mm")
}

trait CopyRepository {
  def findBundle(locale: CopyLocale, version: Int): Future[Map[String, CopyRecord]]
  def versionHistory(key: String, locale: CopyLocale): Future[Seq[CopyRecord]]
  def addVersion(key: String, locale: CopyLocale, text: String, user: String, retranslate: Boolean): Future[Int]
  def addApprovalOrRejection(key: String, locale: CopyLocale, version: Int, user: String, approved: Boolean): Future[Unit]
}

class MongoCopyRepository(
  db: DB,
  localeRepository: LocaleRepository,
  versionTagRepository: VersionTagRepository
) (
  implicit ctx: ExecutionContext
) extends CopyRepository {
  private implicit object BSONDateTimeHandler extends BSONHandler[BSONDateTime, DateTime] {
    def read(time: BSONDateTime) = new DateTime(time.value)
    def write(jdtime: DateTime) = BSONDateTime(jdtime.getMillis)
  }
  private implicit val actionHandler = Macros.handler[Action]
  private implicit val copyRecordHandler = Macros.handler[BaseCopyRecord]

  private val collection: BSONCollection = db("copy")

  def findBundle(locale: CopyLocale, version: Int) = findBundle(locale, version, inherited = false)
  private def findBundle(locale: CopyLocale, version: Int, inherited: Boolean): Future[Map[String, CopyRecord]] =
    for {
      documents <- collection.find(
          BD(),
          BD(locale.id -> 1)
        ).cursor[BD].collect[Seq]()
      parentBundle <- locale.parent match {
        case Some(parent) => findBundle(localeRepository.forId(parent), version, true)
        case None => Future.successful(Map())
      }
    } yield {
      val seq =
        for {
          doc <- documents
          key <- doc.getAs[String]("_id")
          versions <- doc.getAs[BD](locale.id)
          matching <-
            versions.elements sortBy {
              case (k, v) => -k.toInt
            } find {
              case (k, v) => k.toInt <= version
            }
        } yield {
          key -> new CopyRecord(BSON.read[BD, BaseCopyRecord](matching._2.asInstanceOf[BD]), inherited, matching._1.toInt)
        }
      parentBundle ++ seq.toMap
    }

  def versionHistory(key: String, locale: CopyLocale): Future[Seq[CopyRecord]] =
    for {
      docOpt <- collection.find(
        BD("_id" -> key),
        BD(locale.id -> 1)
      ).one[BD]
    } yield for {
      doc <- docOpt.toSeq
      versions <- doc.getAs[BD](locale.id).toSeq
      version <- versions.elements.toSeq.sortBy(_._1.toInt)
    } yield {
      new CopyRecord(BSON.read[BD, BaseCopyRecord](version._2.asInstanceOf[BD]), false, version._1.toInt)
    }

  def addVersion(key: String, locale: CopyLocale, text: String, user: String, retranslate: Boolean): Future[Int] = {
    if (key.trim.isEmpty)
      throw new IllegalArgumentException("Key is blank")
    val record = BaseCopyRecord(
      text = text,
      update = Action(user, DateTime.now()),
      wasImport = false,
      retranslate = retranslate,
      approvals = Nil,
      rejections = Nil,
      conflicts = Nil
    )
    for {
      version <- versionTagRepository.newVersion()
      result <- db.command(FindAndModify(
          collection.name,
          BD("_id" -> key),
          Update(
            BD("$set" -> BD((locale.id + "." + version) -> record)),
            fetchNewObject = false),
          upsert = true
        ))
    } yield version
  }

  def addApprovalOrRejection(key: String, locale: CopyLocale, version: Int, user: String, approved: Boolean): Future[Unit] = {
    val ar = if (approved) "approvals" else "rejections"
    val action = Action(user, DateTime.now())
    for {
      doc <-
        collection.find(
          BD("_id" -> key),
          BD(locale.id -> 1)
        ).one[BD].map(_.get)
      docVersion = doc.getAs[BD](locale.id).get.elements.toSeq.map(_._1.toInt).filter(_ <= version).max
      result <-
        collection.update(
          BD("_id" -> key, (locale.id + "." + docVersion) -> BD("$exists" -> true)),
          BD("$push" -> BD((locale.id + "." + docVersion + "." + ar) -> action))
        )
    } yield ()
  }
}
