package code.model

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection._

import net.liftweb.db.DB
import net.liftweb.record.field.{LongField,StringField,BooleanField,IntField}
import net.liftweb.record.{Record, MetaRecord}
import net.liftweb.common._
import net.liftweb.common.Failure
import net.liftweb.util.{Props, DefaultConnectionIdentifier}
import net.liftweb.util.Helpers.tryo
import net.liftweb.squerylrecord.RecordTypeMode._
import net.liftweb.squerylrecord.KeyedRecord

import org.squeryl.annotations._

import MainSchema._
import code.Rest.pagerRestClient

// helper case class to extract from JSON as REST client to LCBO.
case class ProductAsLCBOJson(id: Int,
                             is_discontinued: Boolean,
                             `package`: String,
                             total_package_units: Int,
                             primary_category: String,
                             name: String,
                             image_thumb_url: String,
                             origin: String,
                             description: String,
                             secondary_category: String,
                             serving_suggestion: String,
                             varietal: String,
                             price_in_cents: Int,
                             alcohol_content: Int,
                             volume_in_milliliters: Int,
                             inventory_count: Int) {
  def removeNulls: ProductAsLCBOJson = { // remove LCBO's poisoned null strings
    def notNull(s: String) = if (s eq null) "" else s

    ProductAsLCBOJson(id,
      is_discontinued,
      notNull(`package`),
      total_package_units,
      notNull(primary_category),
      name: String,
      notNull(image_thumb_url),
      notNull(origin),
      notNull(description),
      notNull(secondary_category),
      notNull(serving_suggestion),
      notNull(varietal),
      price_in_cents,
      alcohol_content,
      volume_in_milliliters,
      inventory_count)
  }

  def getProduct(dbProducts: Map[Int, Product]) = dbProducts.get(id)

}


/**
  * Created by philippederome on 15-11-01. Modified 16-01-01 for Record+Squeryl (to replace Mapper), Record being open to NoSQL and Squeryl providing ORM service.
  * Product: The elements of a product from LCBO catalogue that we deem of relevant interest to replicate in DB for this toy demo.
  */
class Product private() extends Record[Product] with KeyedRecord[Long] with CreatedUpdated[Product]  {
  def meta = Product

  @Column(name="id")
  override val idField = new LongField(this, 1)  // our own auto-generated id
  val lcbo_id = new IntField(this) // we don't share same PK as LCBO!
  val is_discontinued = new BooleanField(this, false)
  val `package` = new StringField(this, 80) { // allow dropping some data in order to store/copy without SQL error (120 empirically good)
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val total_package_units = new IntField(this)
  val primary_category = new StringField(this, 40) { // allow dropping some data in order to store/copy without SQL error (120 empirically good)
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val name = new StringField(this, 120) { // allow dropping some data in order to store/copy without SQL error (120 empirically good)
  override def setFilter = notNull _ :: crop _  :: super.setFilter
  }
  val image_thumb_url = new StringField(this, 200) { // allow dropping some data in order to store/copy without SQL error (120 empirically good)
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val origin = new StringField(this, 200) { // allow dropping some data in order to store/copy without SQL error (120 empirically good)
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val price_in_cents = new IntField(this)
  val alcohol_content = new IntField(this)
  val volume_in_milliliters = new IntField(this)
  val secondary_category = new StringField(this, 80)
  val varietal = new StringField(this, 100) { // allow dropping some data in order to store/copy without SQL error (120 empirically good)
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val description = new StringField(this, 2000) {// allow dropping some data in order to store/copy without SQL error
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val serving_suggestion = new StringField(this, 300) {// allow dropping some data in order to store/copy without SQL error
  override def setFilter = notNull _ :: crop _ :: super.setFilter
  }

  val inventory = new IntField(this)

  // intentional aliasing allowing more standard naming convention.
  def primaryCategory = primary_category
  def isDiscontinued = is_discontinued
  def totalPackageUnits = total_package_units
  def imageThumbUrl = image_thumb_url
  def Package = `package`.get // alias to avoid back ticks

  // Change unit of currency from cents to dollars and Int to String
  def price: String = {
    val p = price_in_cents.get / 100.0
    f"$p%1.2f"
  } // since we perverted meaning somewhat by changing unit from cents to dollars

  // Change scale by 100 to normal conventions, foregoing LCBO's use of Int (for ex. 1200 becomes "12.0%" including the percent sign)
  def alcoholContent: String = {
    val a = alcohol_content.get / 100.0
    f"$a%1.1f%%"
  }

  // intentional change of scale from ml to L, using String representation instead of integer and keeping 3 decimals (0.335 ml for beer)
  def volumeInLitre: String = {
    val v = volume_in_milliliters.get / 1000.0
    f"$v%1.3f L"
  }
  /**
    *
    * @return an ordered list of pairs of values (label and value), representing most of the interesting data of the product
    */
  def createProductElemVals: List[(String, String)] =
  // order is important and would be dependent on web designer input, we could possibly find ordering rule either in database or in web design. This assumes order can be fairly static.
    (("Name: ", name.get) ::
      ("Inventory: ", inventory.toString ) ::
      ("Primary Category: ", primary_category.get) ::
      ("Secondary Category: ", secondary_category.get) ::
      ("Varietal: ", varietal.get) ::
      ("Package: ", Package) ::
      ("Volume: ", volumeInLitre) ::
      ("Price: ", "$" + price) ::
      ("Description: ", description.get) ::
      ("Serving Suggestion: ", serving_suggestion.get) ::
      ("Alcohol content: ", alcoholContent) ::
      ("Origin: ", origin.get) ::
      Nil).filter({ p: (String, String) => p._2 != "null" && !p._2.isEmpty })

  def isDirty(p: ProductAsLCBOJson): Boolean = {
    price_in_cents.get != p.price_in_cents ||
      image_thumb_url.get != p.image_thumb_url
  }
  def copyAttributes(p: ProductAsLCBOJson): Product = {
    price_in_cents.set(p.price_in_cents)
    image_thumb_url.set(p.image_thumb_url)
    this
  }

  def synchUp(p: ProductAsLCBOJson): Unit = {
    def copyAttributes(p: ProductAsLCBOJson): Unit = {
      price_in_cents.set(p.price_in_cents)
      image_thumb_url.set(p.image_thumb_url)
    }
    if (isDirty(p)) {
      copyAttributes( p)
      this.update  // Active Record pattern
    }
  }
}

/**
  * object Product: supports persist that does insert or update, depending whether we already have a copy of LCBO product in our database
  * Takes care of a dependency when persisting with assumption that this is always bound to a valid user request, so will attempt to store
  * to UserProducts as well.
  * Errors are possible if data is too large to fit. tryo will catch those and report them.
  */
object Product extends Product with MetaRecord[Product] with pagerRestClient with Loggable {
  val synchLcbo = Props.getBool("product.synchLcbo", true)
  val productCacheSize = Props.getInt("product.cacheSize", 0)
  val prodLoadWorkers = Props.getInt("product.load.workers", 1)
  private val DBBatchSize = Props.getInt("product.DBBatchSize", 1)


  // thread-safe lock free objects
  private val productsCache: concurrent.Map[Int, Product] = TrieMap()

  def update(storeMap: Map[Int, Product]) = {
    productsCache ++= storeMap
    logger.trace(s"product size ${productsCache.size}")
  }

  // thread unsafe (race condition)
  def getProduct(prodId: Int): Option[Product] = if (productsCache.contains(prodId)) Some(productsCache(prodId)) else None

  def fetchSynched(p: ProductAsLCBOJson) = {
    DB.use(DefaultConnectionIdentifier) { connection =>
      val o = products.where(_.lcbo_id === p.id).forUpdate.headOption // Load from DB if available, else create it Squeryl very friendly DSL syntax!
      o.map { q => q.synchUp(p); q } getOrElse
      { val q = create(p); q.save; q }
    }
  }

  @tailrec
  def updateProducts(myProducts: Iterable[Product]): Unit = {
    val slice = myProducts.take(DBBatchSize)
    products.update(slice)
    val rest = myProducts.takeRight(myProducts.size - slice.size)
    if (!rest.isEmpty) updateProducts( rest)
  }

  @tailrec
  def insertNewProducts( myProducts: Iterable[Product]): Unit = {
    val slice = myProducts.take(DBBatchSize)
    products.insert(slice)
    val rest = myProducts.takeRight(myProducts.size - slice.size)
    if (!rest.isEmpty) insertNewProducts(rest)
  }

  def create(p: ProductAsLCBOJson): Product = {
    // store in same format as received by provider so that un-serializing if required will be same logic. This boiler-plate code seems crazy (not DRY at all)...
    createRecord.
      lcbo_id(p.id).
      name(p.name).
      primary_category(p.primary_category).
      is_discontinued(p.is_discontinued).
      `package`(p.`package`).
      origin(p.origin).
      image_thumb_url(p.image_thumb_url).
      price_in_cents(p.price_in_cents).
      total_package_units(p.total_package_units).
      volume_in_milliliters(p.volume_in_milliliters).
      alcohol_content(p.alcohol_content).
      secondary_category(p.secondary_category).
      varietal(p.varietal).
      description(p.description).
      serving_suggestion(p.serving_suggestion).
      inventory(p.inventory_count)
  }
  /**
    * persist a product to database handling insert or update depending on whether the entry exists already or not.
    * Efficiency consideration: when doing two writes, use DB.use to avoid round-trips.
    * Atomicity provided by liftweb in boot.scala (normally would be S.addAround(DB.buildLoanWrapper)), but done differently for Squeryl specifically.
    * @param p a product representing the Record object that was created after serialization from LCBO.
    * @see Lift in Action, Chapter 10-11 (Mapper and mostly Record), Section 10.3.2 Transactions
    * @return the user who requested the product and the number of times the user has purchased this product as a pair/tuple.
    *         May throw but would be caught as a Failure within Box to be consumed higher up.
    */
  def persist(p: Product) = {
    User.currentUser.dmap { Failure("unable to store transaction, Login first!").asA[(String, Long)] }
    { user => // normal case
      // update it with new details; we could verify that there is a difference between LCBO and our version first...
      // assume price and URL for image are fairly volatile and rest is not. In real life, we'd compare them all to check.
      // Use openOr on Box prod so that if non-empty, we update it, otherwise we create and save the product.
      // tryo captures database provider errors (column size too small for example, reporting it as an Empty Box with Failure)
      tryo {
        DB.use(DefaultConnectionIdentifier) { connection =>
          // avoids two/three round-trips to store to DB. Tested this with some long sleep before UserProduct.consume and saw old timestamp for Product compared with UserProduct
          // and it got stored at same time as UserProduct (monitoring Postgres).
          // We do this in transaction so we have local consistency (i.e. the product will not be deleted by some other transaction while we're here)
          val prod: Box[Product] = products.where(_.lcbo_id === p.lcbo_id).forUpdate.headOption
          // Assumes it has been synched up elsewhere if needed, not our business here (or go directly to cache). Squeryl very friendly DSL syntax!
          var count = 0.toLong
          prod.map { q =>
            val userProd: Box[UserProduct] = userProducts.where(u => u.user_c === user.id.get and u.productid === q.id).forUpdate.headOption
            if (userProd.isEmpty) {
              // (Product would be stored in DB with no user interest)
              count = 1
              UserProduct.createRecord.user_c(user.id.get).productid(q.id).selectionscount(count).save // cascade save dependency.
            } else {
              // cascade save dependency (there should only be one entry to update).
              userProd.map { u =>
                count = u.selectionscount.get + 1
                u.selectionscount.set(count)
                u.updated.set(u.updated.defaultValue)
                u.update // Active Record pattern )
              } // from compiler perspective the map could have been a no-op, but that's not really possible in practice.
            }
          } openOr {
            count = 1 // we never saw that product before and user shows interest, store both.
            p.save
            UserProduct.createRecord.user_c(user.id.get).productid(p.id).selectionscount(count).save // cascade save dependency.
          }
          (user.firstName.get, count)
        }
      }
    }
  }

  /**
    * Purchases a product by increasing user-product count (amount) in database as a way to monitor usage..
    * @param product contains a product
    * @return a Box capturing any exception to be reported further up, capturing how many times user has consumed product.
    */
  def consume(product: Product): Box[(String, Long)] = persist(product) // yeah, could do other things such as real payment transaction and exchange of asset.


}
