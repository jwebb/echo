package model

import scala.concurrent.{ExecutionContext, Future}
import reactivemongo.bson.Macros.Annotations.Key
import reactivemongo.api.DB
import reactivemongo.api.collections.default.BSONCollection
import org.apache.commons.codec.digest.Crypt
import java.security.SecureRandom
import org.apache.commons.codec.binary.Base64
import reactivemongo.bson.{BSONDocument => BD, Macros}

case class User(@Key("_id") name: String, hashedPassword: String, isAdmin: Boolean)

trait UserRepository {
  def addUser(name: String, password: String, isAdmin: Boolean): Future[Unit]
  def authenticate(name: String, password: String): Future[Option[User]]
  def changePassword(user: User, password: String): Future[Unit]
}

class MongoUserRepository(db: DB)(implicit ctx: ExecutionContext) extends UserRepository {
  private implicit val userHandler = Macros.handler[User]

  private val collection: BSONCollection = db("users")
  private val random = new SecureRandom()

  def addUser(name: String, password: String, isAdmin: Boolean): Future[Unit] =
    collection.insert(User(name, hashPassword(password), isAdmin = isAdmin)).map(x => ())

  def authenticate(name: String, password: String): Future[Option[User]] =
    for {
      result <- collection.find(BD("_id" -> name)).one[User]
    } yield {
      result match {
        case Some(user) =>
          if (user.hashedPassword == Crypt.crypt(password, user.hashedPassword)) {
            Some(user)
          } else {
            None
          }
        case None => None
      }
    }


  def changePassword(user: User, password: String): Future[Unit] =
    collection.update(BD("_id" -> user.name), BD("$set" -> BD("hashedPassword" -> hashPassword(password)))).map(x => ())

  private def hashPassword(password: String) = {
    val salt = new Array[Byte](18)
    random.nextBytes(salt)
    Crypt.crypt(password, "$6$rounds=50000$" + Base64.encodeBase64String(salt))
  }
}
