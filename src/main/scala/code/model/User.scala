package code
package model

import code.model.MainSchema._
import net.liftweb.common._
import net.liftweb.db.DB
import net.liftweb.mapper._
import net.liftweb.util.DefaultConnectionIdentifier
import net.liftweb.util.Helpers._

import net.liftweb.squerylrecord.RecordTypeMode._

// This is provided by Liftweb framework as a helper to get started or experiment.
/**
  * An O-R mapped "User" class that includes first name, last name, password and Liftweb demo would add a "Personal Essay" to it
  */
class User extends MegaProtoUser[User] {
  def getSingleton = User

  // what's the "meta" server

  // define an additional field for a personal essay
//  object textArea extends MappedTextarea(this, 2048) {
 //   override def textareaRows = 10
   // override def textareaCols = 50
   // override def displayName = "Personal Essay"
 // }

}

/**
  * The singleton that has methods for accessing the database
  */
object User extends User with MetaMegaProtoUser[User] {
  override def dbTableName = "users"

  // define the DB table name
  override def screenWrap = Full(<lift:surround with="default" at="content">
    <lift:bind/>
  </lift:surround>)


  // define the order fields will appear in forms and output
  override def fieldOrder = List(id, firstName, lastName, email,
    locale, timezone, password)  // deleted parameter textArea from demo

  // comment this line out to require email validations
  override def skipEmailValidation = true
  /**
    * consume (persist) a product to database handling insert or update depending on whether the entry exists already or not.
    * Efficiency consideration: when doing two writes, use DB.use to avoid round-trips.
    * Atomicity provided by liftweb in boot.scala (normally would be S.addAround(DB.buildLoanWrapper)), but done differently for Squeryl specifically.
    *
    * @param p a product representing the Record object that was created after serialization from LCBO.
    * @see Lift in Action, Chapter 10-11 (Mapper and mostly Record), Section 10.3.2 Transactions
    * @return the user who requested the product and the number of times the user has purchased this product as a pair/tuple.
    *         May throw but would be caught as a Failure within Box to be consumed higher up.
    */
  def consume(user: TheUserType, p: Product, quantity: Long): Box[(String, Long)] = {
      // update it with new details; we could verify that there is a difference between LCBO and our version first...
      // assume price and URL for image are fairly volatile and rest is not. In real life, we'd compare them all to check.
      // tryo captures database provider errors (column size too small for example, reporting it as an Empty Box with Failure)
    tryo {
      DB.use(DefaultConnectionIdentifier) { connection =>
        // avoids two/three round-trips to store to DB.
        val prod = Product.getProduct(p.id)
        // Assumes it has been synched up elsewhere if needed, not our business here (or go directly to cache).

        val updatedCount = prod.fold {
          // we never saw that product before and user shows interest (because we hurry to present products we have not had time to store, prior to cache being ready)
          // Store both. It can definitely happen. Our cache should not have it if we don't have it in DB.
          Product.insertProducts(Seq(p)) // updates cache as well
          UserProduct.createRecord.userid(user.id.get).productid(p.id).selectionscount(quantity).save // cascade save dependency.
          quantity.toLong
        } { q =>
            val userProd = userProducts.where(u => u.userid === user.id.get and u.productid === q.id).forUpdate.headOption
            userProd.fold {
              // (Product would be stored in DB with no previous user interest)
              UserProduct.createRecord.userid(user.id.get).productid(q.id).selectionscount(quantity).save // cascade save dependency using Active Record pattern.
              quantity.toLong
            } { u: UserProduct=>
                val newCount = u.selectionscount.get + quantity
                u.selectionscount.set(newCount)
                u.updated.set(u.updated.defaultValue)
                u.update // Active Record pattern, no need to specify table explicitly.
                newCount
            }
          }
        (user.firstName.get, updatedCount)
      }
    }
  }
}


