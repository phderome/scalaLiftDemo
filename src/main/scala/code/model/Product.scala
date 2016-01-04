package code.model

import java.io.IOException
import scala.util.Random

import net.liftweb.db.DB
import net.liftweb.json.{MappingException, DefaultFormats}
import net.liftweb.json.JsonParser._
import net.liftweb.record.field.{LongField,StringField,BooleanField,IntField}
import net.liftweb.record.{Record, MetaRecord}
import net.liftweb.common._
import net.liftweb.common.Failure
import net.liftweb.util.DefaultConnectionIdentifier
import net.liftweb.util.Helpers.tryo
import net.liftweb.squerylrecord.RecordTypeMode._

import MainSchema._
import code.Rest.pagerRestClient

/**
  * Created by philippederome on 15-11-01. Modified 16-01-01 for Record+Squeryl (to replace Mapper), Record being open to NoSQL and Squeryl providing ORM service.
  * Product: The elements of a product from LCBO catalogue that we deem of relevant interest to replicate in DB for this toy demo.
  */
class Product private() extends Record[Product] with CreatedUpdated[Product]  {
  def meta = Product

  val id = new LongField(this) // for now share same PK as LCBO!
  val is_discontinued = new BooleanField(this, false)
  val `package` = new StringField(this, 80)
  val total_package_units = new IntField(this)
  val primary_category = new StringField(this, 40)
  val name = new StringField(this, 80)
  val image_thumb_url = new StringField(this, 200)
  val origin = new StringField(this, 200)
  val price_in_cents = new IntField(this)
  val alcohol_content = new IntField(this)
  val volume_in_milliliters = new IntField(this)
  val secondary_category = new StringField(this, 80)
  val varietal = new StringField(this, 100)
  val description = new StringField(this, 300)
  val serving_suggestion = new StringField(this, 300)

  // intentional aliasing allowing more standard naming convention.
  def primaryCategory = primary_category
  def isDiscontinued = is_discontinued
  def totalPackageUnits = total_package_units
  def imageThumbUrl = image_thumb_url
  def Package = `package` // alias to avoid back ticks

  // Change unit of currency from cents to dollars and Int to String
  def price: String = {
    val p = price_in_cents.get.toInt / 100.0
    f"$p%1.2f"
  } // since we perverted meaning somewhat by changing unit from cents to dollars

  // Change scale by 100 to normal conventions, foregoing LCBO's use of Int (for ex. 1200 becomes "12.0%" including the percent sign)
  def alcoholContent: String = {
    val a = alcohol_content.get.toInt / 100.0
    f"$a%1.1f%%"
  }

  // intentional change of scale from ml to L, using String representation instead of integer and keeping 3 decimals (0.335 ml for beer)
  def volumeInLitre: String = {
    val v = volume_in_milliliters.get.toInt / 1000.0
    f"$v%1.3f L"
  }
  /**
    *
    * @return an ordered list of pairs of values (label and value), representing most of the interesting data of the product
    */
  def createProductElemVals: List[(String, String)] =
  // order is important and would be dependent on web designer input, we could possibly find ordering rule either in database or in web design. This assumes order can be fairly static.
    ( ("Name: ", s"$name") ::
      ("Primary Category: ", s"$primary_category") ::
      ("Secondary Category: ", s"$secondary_category") ::
      ("Varietal: ", s"$varietal") ::
      ("Package: ", s"$Package") ::
      ("Volume: ", s"$volumeInLitre") ::
      ("Price: ",  "$" +s"$price") ::
      ("Description: ", s"$description") ::
      ("Serving Suggestion: ", s"$serving_suggestion") ::
      ("Alcohol content: ", s"$alcoholContent") ::
      ("Origin: ", s"$origin") ::
      Nil ).filter({p: (String, String) => p._2 != "null"})

}

/**
  * object Product: supports persist that does insert or update, depending whether we already have a copy of LCBO product in our database
  * Takes care of a dependency when persisting with assumption that this is always bound to a valid user request, so will attempt to store
  * to UserProducts as well.
  * Errors are possible if data is too large to fit. tryo will catch those and report them.
  */
object Product extends Product with MetaRecord[Product] with pagerRestClient with Loggable {
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
                               volume_in_milliliters: Int) {}

  def create(p: ProductAsLCBOJson): Product = {
    // store in same format as received by provider so that un-serializing if required will be same logic. This boiler-plate code seems crazy (not DRY at all)...
    createRecord.
      id(p.id).
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
      serving_suggestion(p.serving_suggestion)
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
          val prod: Box[Product] = products.where(_.id === p.id).forUpdate.headOption  // Squeryl very friendly DSL syntax!
          val prodId = prod.map { q =>
            update(products)(q =>
              where(q.id === p.id)
                set(q.price_in_cents := p.price_in_cents,
                    q.image_thumb_url := p.image_thumb_url))
            q.id.get
          } openOr {
            p.save
            p.id.get
          }
          UserProduct.consume(user, prodId)   // once the product has been saved, also save the UserProducts relationship for an additional count of the product for the user.
        }
      }
    }
  }

  implicit val formats = DefaultFormats // for JSON extraction

  /**
    * We call up LCBO site each time we get a query with NO caching. This is inefficient but simple and yet reasonably responsive.
    * Select a random product that matches the parameters subject to a max sample size.
    *
    * @param store a String representing a numeric code of a LCBO store
    * @param category a String such as beer, wine, mostly matching primary_category at LCBO, or an asset category.
    * @return
    */
  def recommend(maxSampleSize: Int, store: Int, category: String): Box[Product] =
    tryo {
      val randomIndex = Random.nextInt(math.max(1, maxSampleSize)) // max constraint is defensive for poor client usage (negative numbers).
      val prods = productListByStoreCategory(randomIndex+1, store, category)  // index is 0-based but requiredSize is 1-based so add 1,
      create(prods.take(randomIndex + 1).takeRight(1).head)  // convert JSON case class object to a full-fledged Product with persistence capability.
      // First take will return full collection if index is too large, and prods' size should be > 0 unless there really is nothing.
    }

  /**
    * Purchases a product by increasing user-product count (amount) in database as a way to monitor usage..
    * @param product contains a product
    * @return a Box capturing any exception to be reported further up, capturing how many times user has consumed product.
    */
  def consume(product: Product): Box[(String, Long)] = persist(product) // yeah, could do other things such as real payment transaction and exchange of asset.

  /**
    * LCBO client JSON query handler. So naturally, the code is specifically written with the structure of LCBO documents in mind, with tokens as is.
    * For Liftweb JSON extraction after parse,
    * @see https://github.com/lift/lift/tree/master/framework/lift-base/lift-json/
    *      don't go to more pages than user implicitly requests via requiredSize that should not be exceeded.
    *      Would Streams collection be handy for paging here? Depends on consumption usage perhaps.
    *      Uses tail recursion.
    * @param accumItems accumulator to facilitate tail recursion
    * @param urlRoot a LCBO product query without the details of paging, which we handle here
    * @param requiredSize required size of products that are asked for. May get less if there are fewer matches, but will not go above that size.
    * @param pageNo client calls this with value 1 (initial page), recursion increments it, designates the pageno for LCBO JSON data when data fits on several pages
    * @param myFilter client's filter that can be applied as we process the data before mapping/extracting it out to client data.
    *                 In principle, should be faster when user filters reject many values, but no empirical evidence here.
    * @return a vector of product items matching the query and size constraint, though we may go a bit over the size by multiple of page sizes.
    * @throws java.net.SocketTimeoutException timeout is reached, slow connection
    * @throws java.io.IOException I/O issue
    * @throws net.liftweb.json.JsonParser.ParseException parse problem
    * @throws net.liftweb.json.MappingException our case class does not match JSon object from API
    *
    */
  @throws(classOf[net.liftweb.json.MappingException])
  @throws(classOf[net.liftweb.json.JsonParser.ParseException])
  @throws(classOf[java.io.IOException])
  @throws(classOf[java.net.SocketTimeoutException])
  @throws(classOf[java.net.UnknownHostException]) // no wifi/LAN connection for instance
  @scala.annotation.tailrec
  private final def collectItemsOnAPage(accumItems: List[ProductAsLCBOJson],
                                        urlRoot: String,
                                        requiredSize: Int,
                                        pageNo: Int,
                                        myFilter: ProductAsLCBOJson => Boolean = { p: ProductAsLCBOJson => true }): List[ProductAsLCBOJson] = {

    // specify the URI for the LCBO api url for liquor selection
    val uri = urlRoot + additionalParam("per_page", MaxPerPage) + additionalParam("page", pageNo)  // get as many as possible on a page because we could have few matches.
    logger.info(uri)
    val pageContent = get(uri, HttpClientConnTimeOut, HttpClientReadTimeOut) // fyi: throws IOException or SocketTimeoutException
    val jsonRoot = parse(pageContent) // fyi: throws ParseException
    val itemNodes = (jsonRoot \ "result").children // Uses XPath-like querying to extract data from parsed object jsObj.
    val items = (for (p <- itemNodes) yield p.extract[ProductAsLCBOJson]).filter(myFilter)
    lazy val outstandingSize = requiredSize - items.size

    // Collects into our vector of products products the attributes we care about (extract[Product]). Then filter out unwanted data.
    // fyi: throws Mapping exception.
    //LCBO tells us it's last page (Uses XPath-like querying to extract data from parsed object).
    lazy val isFinalPage = (jsonRoot \ "pager" \ "is_final_page").extract[Boolean]

    if (items.isEmpty || outstandingSize <= 0 || isFinalPage) return accumItems ++ items
    // Deem as last page if there are no products found on current page or LCBO tells us it's final page.
    // Similarly, even if we're not on last page and there are more products, having reached our required size.

    // tail recursion enforced.
    collectItemsOnAPage(
      accumItems ++ items,
      urlRoot,
      outstandingSize,
      pageNo + 1,
      myFilter) // union of this page with next page when we are asked for a full sample
  }

  import java.net.SocketTimeoutException

  // for reflection and generating documentation
  /**
    * Queries LCBO matching category and storeId for a sample size as specified by client, with category considered optional, though not tested when optional.
    * Full URL will be built as follows: http://lcbo.com/products?store_id=<storeId>&q=<category.toLowerCase()>&per_page=<perPage>
    * LCBO allows to specify q as query to specify pattern match on product name (e.g. beer, wine)
    * for pattern match LCBO uses lower case but for actual product category it's upper case, so to make comparisons, we will need to account for that
    * primary_category in catalog or p.primary_category so we need a conversion function to adjust)
    * @param requiredSize upper bound on #items we need. Attempt to match it if enough supply is available.
    * @param store id  of Store at LCBO
    * @param category wine, spirits, and so on
    * @return collection of LCBO products while throwing.
    * @throws SocketTimeoutException timeout reached
    * @throws IOException I/O issue
    * @throws ParseException parse issue
    * @throws MappingException, etc
    */
  @throws(classOf[SocketTimeoutException])
  @throws(classOf[IOException])
  @throws(classOf[ParseException])
  @throws(classOf[MappingException])
  private def productListByStoreCategory(requiredSize: Int, store: Int, category: String = ""): Vector[ProductAsLCBOJson] = {
    val url = s"$LcboDomainURL/products?store_id=$store" + additionalParam("q", category) // does not handle first one such as storeId, which is artificially mandatory
    val filter = { p: ProductAsLCBOJson => p.primary_category == LiquorCategory.toPrimaryCategory(category) &&
      !p.is_discontinued
    }
    // accommodates for the rather unpleasant different ways of seeing product categories (beer and Beer or coolers and Ready-to-Drink/Coolers

    collectItemsOnAPage(
      List[ProductAsLCBOJson](),
      url,
      requiredSize,
      pageNo = 1,
      filter).take(requiredSize).toVector
  }
}
