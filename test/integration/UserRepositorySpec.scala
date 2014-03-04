package integration

import model.MongoUserRepository
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ShouldMatchers, BeforeAndAfter, WordSpec}
import scala.concurrent.ExecutionContext.Implicits.global

class UserRepositorySpec extends WordSpec with BeforeAndAfter with ShouldMatchers with ScalaFutures {
  private val repository = new MongoUserRepository(Help.db)

  before {
    Help.lock()
    Help.clear("users")
    Help.waitFor(repository.addUser("testUser", "SomePassword", isAdmin = true))
  }

  after {
    Help.unlock()
  }

  "the user repository" should {
    "add and authenticate users" in {
      val name = repository.authenticate("testUser", "SomePassword").map(_.get.name)
      name.futureValue should === ("testUser")
    }

    "not authenticate missing users" in {
      val user = repository.authenticate("otherUser", "SomePassword")
      user.futureValue should === (None)
    }

    "not authenticate with incorrect password" in {
      val user = repository.authenticate("testUser", "BadPassword")
      user.futureValue should === (None)
    }

    "support changing passwords" in {
      val user = Help.waitFor(repository.authenticate("testUser", "SomePassword")).get
      repository.changePassword(user, "OtherPassword")
      repository.authenticate("testUser", "OtherPassword").map(_.get.name).futureValue should === ("testUser")
    }
  }
}
