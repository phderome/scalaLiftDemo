package code.model

import cats.implicits._
import code.model.MainSchema._
import net.liftweb.common.Full
import net.liftweb.db.DB
import net.liftweb.mapper._
import net.liftweb.util.DefaultConnectionIdentifier
import net.liftweb.squerylrecord.RecordTypeMode._
import scala.xml.Node
import code.model.GlobalLCBO_IDs._

/**
  * An O-R mapped "User" class that includes first name, last name, password and Liftweb demo would add a "Personal Essay" to it
  * This is provided by Liftweb framework as a helper to get started or experiment. I left screenwrap in Object companion but not using it.
  */
class User extends MegaProtoUser[User] {
  def getSingleton: User.type = User

  /**
    * consume (persist) a product to database handling insert or update depending on whether the entry exists already or not.
    * Efficiency consideration: when doing two writes, use DB.use to avoid round-trips.
    * Atomicity provided by liftweb in boot.scala (normally would be S.addAround(DB.buildLoanWrapper)), but done differently for Squeryl specifically.
    *
    * @param p a product representing the Record object that was created after serialization from LCBO.
    * @param quantity the quantity of the product we are recording for user.
    * @see Lift in Action, Chapter 10-11 (Mapper and mostly Record), Section 10.3.2 Transactions
    * @return the number of times the user has purchased this product as a pair/tuple.
    *         May throw but would be caught as a Throwable within Xor to be consumed higher up.
    */
  def consume(p: IProduct, quantity: Long): Either[Throwable, Long] = Either.catchNonFatal {
    // update it with new details; we could verify that there is a difference between LCBO and our version first...
    // assume price and URL for image are fairly volatile and rest is not. In real life, we'd compare them all to check.
    // Try captures database provider errors (column size too small for example, reporting it as an Throwable in Xor)
    DB.use(DefaultConnectionIdentifier) { connection =>
      // avoids two/three round-trips to store to DB.
      val updatedCount = Product.getProduct(p.pKey).fold {
        throw new RuntimeException(s"User.consume on unsaved product $p missing key ${p.pKey} expected to be found")
      } { pp =>
        val prodId: Long = pp.pKey // coerce type conversion as Squeryl needs a Long to convert to NumericType and P_KEY does not.
        val userProd = userProducts.where(u => u.userid === id.get and u.productid === prodId).forUpdate.headOption
        userProd.fold {
          // (Product would be stored in DB with no previous user interest)
          UserProduct.createRecord.userid(id.get).productid(pp.pKey).selectionscount(quantity).save // cascade save dependency using Active Record pattern.
          quantity.toLong
        } { up =>
            val updatedQuantity = up.selectionscount.get + quantity
            up.selectionscount.set(updatedQuantity)
            up.updated.set(up.updated.defaultValue)
            up.update // Active Record pattern, no need to specify table explicitly.
            updatedQuantity
        }
      }
      updatedCount
    }
  }
}

/**
  * The singleton that has methods for accessing the database
  */
object User extends User with MetaMegaProtoUser[User] {
  override def dbTableName: String = "users" // define the DB table name

  override def screenWrap: Full[Node] = Full(<lift:surround with="default" at="content">
    <lift:bind/>
  </lift:surround>)

  // define the order fields will appear in forms and output
  override def fieldOrder: List[MappedField[_, User]] = List(id, firstName, lastName, email,
    locale, timezone, password) // deleted parameter textArea from demo

  // comment this line out to require email validations
  override def skipEmailValidation: Boolean = true
}
