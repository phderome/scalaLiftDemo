package code.model

import java.io.IOException
import java.net.SocketTimeoutException
import java.sql.SQLException

import scala.annotation.tailrec
import scala.collection._
import scala.language.implicitConversions
import scala.util.Random
import scala.xml.Node
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import net.liftweb.common.{Full, Empty, Box, Loggable}
import net.liftweb.json._
import net.liftweb.json.JsonParser.{ParseException, parse}
import net.liftweb.util.Helpers.tryo
import net.liftweb.record.MetaRecord
import net.liftweb.record.field._
import net.liftweb.squerylrecord.RecordTypeMode._
import net.liftweb.util.Props

import org.squeryl.annotations._

import code.model.Product._
import code.Rest.pagerRestClient

class Store  private() extends Persistable[Store] with CreatedUpdated[Store] with Loggable  {
  private implicit val formats = net.liftweb.json.DefaultFormats

  @Column(name="pkid")
  override val idField = new LongField(this, 0)  // our own auto-generated id
  val lcbo_id = new LongField(this) // we don't share same PK as LCBO!

  // for Persistable
  override def table(): org.squeryl.Table[Store] = Store.table()
  override def cache() = Store.storesCache
  override def LcboIdsToDBIds() = Store.LcboIdsToDBIds
  override def pKey: Long = idField.get
  override def lcboId: Long = lcbo_id.get
  override def setLcboId(id: Long): Unit = lcbo_id.set(id)
  override def meta = Store

  val is_dead = new BooleanField(this, false)
  val latitude = new DoubleField(this)
  val longitude = new DoubleField(this)
  val name = new StringField(this, 200) {
    override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val address_line_1 = new StringField(this, 200) {
    override def setFilter = notNull _ :: crop _ :: super.setFilter
  }
  val city = new StringField(this, 30) {
    override def setFilter = notNull _ :: crop _ :: super.setFilter
  }

  //products is a StatefulManyToMany[Product,Inventory], it extends Iterable[Product]
  lazy val storeProducts = MainSchema.inventories.leftStateful(this)
  private def inventories = storeProducts.associations

  // following three caches leverage ORM's stateful cache of storeProducts and inventories above (which are not presented as map but as slower sequence;
  // we organize as map for faster access).
  // They're recomputed when needed by the three helper functions that follow.
  private val productsCache = TrieMap[Long, Product]()  // keyed by lcboId
  private val productsCacheByCategory = TrieMap[String, IndexedSeq[Product]]()
  private val inventoryByProductId = TrieMap[Long, Inventory]()

  private def productsByLcboId: Map[Long, Product] = storeProducts.toIndexedSeq.groupBy(_.lcboId).mapValues(_.head)
  private def productsByCategory: Map[String, IndexedSeq[Product]] = storeProducts.toIndexedSeq.groupBy(_.primaryCategory)
  private def getInventories: Map[Long, Inventory] =
    inventories.toIndexedSeq.map { inv => inv.productid -> inv } (breakOut)  // moderately slow because of iteration

  private def emptyInventory: Boolean =
    inventories.toIndexedSeq.forall(_.quantity == 0)

  def isDirty(s: Store): Boolean = {
    is_dead.get != s.is_dead.get ||
      address_line_1.get != s.address_line_1.get
  }

  /**
    * We call up LCBO site each time we get a query with NO caching. This is inefficient but simple and yet reasonably responsive.
    * Select a random product that matches the parameters subject to a max sample size.
    *
    * @param category a String such as beer, wine, mostly matching primary_category at LCBO, or an asset category (for query only not to compare results and filter!).
    * @param requestSize a number representing how many items we need to sample
    * @return quantity found in inventory for product and the product
    */
  def recommend(category: String, requestSize: Int): Box[Iterable[(Long, Product)]] = {
    /**
      * Queries LCBO matching category
      * Full URL will be built as follows: http://lcbo.com/products?store_id=<storeId>&q=<category.toLowerCase()>&per_page=<perPage>
      * LCBO allows to specify q as query to specify pattern match on product name (e.g. beer, wine)
      * for pattern match LCBO uses lower case but for actual product category it's upper case, so to make comparisons, we will need to account for that
      * primary_category in catalog or p.primary_category so we need a conversion function to adjust)
      *
      * @param category wine, spirits, and so on
      * @return collection of LCBO products while throwing.
      */
    def productsByStoreCategory(category: String): IndexedSeq[Product] = {
      val url = s"$LcboDomainURL/products?store_id=${lcboId}" + additionalParam("q", category) // does not handle first one such as storeId, which is artificially mandatory
      collectItemsOnAPage(
        IndexedSeq[Product](),
        url,
        Store.MaxSampleSize,
        cacheOnly = false,
        pageNo = 1,
        Store.notDiscontinued)
    }

    def getRequestFromCache(matchingKeys: IndexedSeq[Long], lcboProdCategory: String): IndexedSeq[(Long, Product)] = {
      // get some random sampling.
      val permutedKeys = Random.shuffle(matchingKeys)
      productsCacheByCategory.getOrElse(lcboProdCategory, IndexedSeq[Product]()).map(_.lcboId)
      val seq = for (id <- permutedKeys;
                     p <- productsCache.get(id)) yield p
      // generate double the keys and hope it's enough to find enough products with positive inventory as a result
      // checking quantity in for comprehension above is cost prohibitive.
      seq.take(2 * requestSize).map { p: Product =>
        (inventoryByProductId.get(p.id).
          map(_.quantity).
          fold(0.toLong)
          {identity}, p)}.filter { _._1 > 0 }.take(requestSize)
    }

    def getSerialResult: IndexedSeq[(Long, Product)] = {
      val prods = productsByStoreCategory(category) // take a hit of one go to LCBO, no more.
      val permutedIndices = Random.shuffle[Int, IndexedSeq](prods.indices)
      val seq = for (id <- permutedIndices;
                     lcbo_id = prods(id).lcboId.toInt;
                     p <- productsCache.get(lcbo_id)) yield (0.toLong, p)
      // we may have 0 inventory, browser should try to finish off that work not web server.
      seq.take(requestSize)
      }

    // we could get errors going to LCBO, this tryo captures those.
    tryo {
      val lcboProdCategory = LiquorCategory.toPrimaryCategory(category) // transform to the category LCBO uses on product names in results
      val matchingKeys = productsCacheByCategory.getOrElse(lcboProdCategory, IndexedSeq[Product]()).map(_.lcboId)
      // products are loaded before inventories and we might have none
      asyncLoadCache() // if we never loaded the cache, do it (fast lock free test). Note: useful even if we have product of matching inventory
      val cachedRes = getRequestFromCache(matchingKeys, lcboProdCategory)
      if (cachedRes.nonEmpty) cachedRes
      else getSerialResult
    }
  }

  /**
    * LCBO client JSON query handler.
    *
    * @see https://github.com/lift/lift/tree/master/framework/lift-base/lift-json/
    *      don't go to more pages than user implicitly requests via requiredSize that should not be exceeded.
    *      Uses tail recursion.
    * @param accumItems accumulator to facilitate tail recursion
    * @param urlRoot a LCBO product query without the details of paging, which we handle here
    * @param requiredSize required size of products that are asked for. May get less if there are fewer matches, but will not go above that size.
    *                      if cacheOnly is true, this value can be arbitrary and ignored.
    * @param cacheOnly true if we don't need a full return result set, false if we need data we can consume (side effect is to cache all the time)
    * @param pageNo client calls this with value 1 (initial page), recursion increments it, designates the pageno for LCBO JSON data when data fits on several pages
    * @param filter client's filter that can be applied as we process the data before mapping/extracting it out to client data.
    *                 In principle, should be faster when user filters reject many values, but no empirical evidence here.
    * @return a vector of product items matching the query and size constraint (or none if cacheOnly is true). Always side effect to cache.
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
  @tailrec
  private final def collectItemsOnAPage(accumItems: IndexedSeq[Product],
                                        urlRoot: String,
                                        requiredSize: Int,
                                        cacheOnly: Boolean,
                                        pageNo: Int,
                                        filter: Product => Boolean): IndexedSeq[Product] = {

    // specify the URI for the LCBO api url for liquor selection
    val uri = urlRoot + additionalParam("per_page", MaxPerPage) + additionalParam("page", pageNo) // get as many as possible on a page because we could have few matches.
    val pageContent = get(uri, HttpClientConnTimeOut, HttpClientReadTimeOut) // fyi: throws IOException or SocketTimeoutException
    val jsonRoot = parse(pageContent) // fyi: throws ParseException
    val itemNodes = (jsonRoot \ "result").children.toVector // Uses XPath-like querying to extract data from parsed object jsObj.
    // collect our list of products in items and filter out unwanted products
    val items = Product.extractLcbo(itemNodes).filter(filter) // hard code filter for now.
    val outstandingSize = requiredSize - items.size

    //LCBO tells us it's last page (Uses XPath-like querying to extract data from parsed object).
    val isFinalPage = (jsonRoot \ "pager" \ "is_final_page").extractOrElse[Boolean](false)
    val totalPages = (jsonRoot \ "pager" \ "total_pages").extractOrElse[Int](0)
    val revisedAccumItems =  accumItems ++ Product.reconcile(cacheOnly, items)  // global db+cache update.
    productsCache ++= revisedAccumItems.groupBy(_.lcboId).mapValues(_.head) // update local store specific caches after having updated global cache for all products
    productsCacheByCategory ++= revisedAccumItems.groupBy(_.primaryCategory)

    if ( (outstandingSize <= 0  && !cacheOnly )|| isFinalPage || totalPages < pageNo + 1) {
      logger.info(uri) // log only the last one, less chatty
      return revisedAccumItems
    }
    // Deem as last page only if  LCBO tells us it's final page or we evaluate next page won't have any (totalPages).
    // Similarly having reached our required size,we can stop.

    collectItemsOnAPage(
      revisedAccumItems, // union of this page with next page when we are asked for a full sample
      urlRoot,
      outstandingSize,
      cacheOnly,
      pageNo + 1,
      filter)
  }

  @throws(classOf[SocketTimeoutException])
  @throws(classOf[IOException])
  @throws(classOf[ParseException])
  @throws(classOf[MappingException])
  private def fetchInventoriesByStore(): Unit = {
    @throws(classOf[net.liftweb.json.MappingException])
    @throws(classOf[net.liftweb.json.JsonParser.ParseException])
    @throws(classOf[java.io.IOException])
    @throws(classOf[java.net.SocketTimeoutException])
    @throws(classOf[java.net.UnknownHostException]) // no wifi/LAN connection for instance
    @tailrec
    def collectInventoriesOnAPage(  urlRoot: String,
                                    pageNo: Int): Unit = {

      def stateOfInventory(item: InventoryAsLCBOJson): EntityRecordState = {
        val prodId = Product.lcboidToDBId(item.product_id)
        val invOption = prodId.flatMap(inventoryByProductId.get(_))
        (prodId, invOption)  match {
          case (Some(id), None)  => New
          case (Some(id), Some(inv)) if inv.dirty_?(item) => Dirty
          case (Some(id), _) => Clean
          case _ => Undefined  // on a product we don't yet know, so consider it as undefined so we don't violate FK on products (LCBO makes no guaranty to be consistent here)
        }
      }

      // specify the URI for the LCBO api url for liquor selection
      val uri = urlRoot + additionalParam("per_page", MaxPerPage) + additionalParam("page", pageNo) // get as many as possible on a page because we could have few matches.
      val pageContent = get(uri, HttpClientConnTimeOut, HttpClientReadTimeOut) // fyi: throws IOException or SocketTimeoutException
      val jsonRoot = parse(pageContent) // fyi: throws ParseException
      val itemNodes = (jsonRoot \ "result").children.toVector // Uses XPath-like querying to extract data from parsed object jsObj.
      val filter = { p: InventoryAsLCBOJson => !p.is_dead } // filter accommodates for the rather unpleasant different ways of seeing product categories (beer and Beer or coolers and Ready-to-Drink/Coolers
      val items = (for (p <- itemNodes) yield p.extract[InventoryAsLCBOJson]).filter(filter)  // LCBO sends us poisoned useless nulls that we need to filter for DB (filter them right away).
      // Collects into our list of inventories the attributes we care about (extract[InventoryAsLCBOJson]). Then filter out unwanted data.

      // partition items into 3 lists, clean (no change), new (to insert) and dirty (to update), using neat groupBy, after doing a quick db refresh and cache refresh.
      val storeProductsByState: Map[EntityRecordState, IndexedSeq[InventoryAsLCBOJson]] = items.toIndexedSeq.groupBy( stateOfInventory )

      val dirtyInventories =
        storeProductsByState.getOrElse(Dirty, Nil).
          flatMap { inv => Product.lcboidToDBId(inv.product_id).
            flatMap { dbProductId =>
                  inventoryByProductId.get(dbProductId).map  { _.copyAttributes(inv) } }
          }.toIndexedSeq

      // get the New partition, returning Nil if we don't have any, and open up option for a match on the productID in database from the LCBO ID.
      // finally fetch a Inventory that is created with specified productId, quantity, and storeId.
      val newInventories =
        storeProductsByState.getOrElse(New, Nil).
        flatMap { inv => Product.lcboidToDBId(inv.product_id).
          map { dbProductId =>
                new Inventory(idField.get, dbProductId, inv.quantity, inv.updated_on, inv.is_dead ) } } (collection.breakOut)

      // God forbid, we might supply ourselves data that violates composite key. Weed it out!
      def removeCompositeKeyDupes(invs: IndexedSeq[Inventory]): Iterable[Inventory] = {
        invs.groupBy(x => (x.productid, x.storeid)).map{case (k,v) => v.last}
      }
      val CompKeyFilterNewInventories = removeCompositeKeyDupes(newInventories)
      val CompKeyFilterDirtyInventories = removeCompositeKeyDupes(dirtyInventories)

      try {  // getNextException in catch is what is useful to log (along with the data that led to the exception)
        inTransaction {
          // we refresh just before splitting the inventories into clean, dirty, new classes.
          MainSchema.inventories.update(CompKeyFilterDirtyInventories)
          MainSchema.inventories.insert(CompKeyFilterNewInventories)
          refreshInventories()
        }
      } catch {
          case se: SQLException =>  // the bad
            logger.error(s"SQLException New Invs $CompKeyFilterNewInventories Dirty Invs $CompKeyFilterDirtyInventories")
            logger.error("Code: " + se.getErrorCode)
            logger.error("SqlState: " + se.getSQLState)
            logger.error("Error Message: " + se.getMessage)
            logger.error("NextException:" + se.getNextException)  // the "good".
          case e: Exception =>  // the UGLY!
            logger.error("General exception caught: " + e)
        }

      //LCBO tells us it's last page (Uses XPath-like querying to extract data from parsed object).
      val isFinalPage = (jsonRoot \ "pager" \ "is_final_page").extractOrElse[Boolean](false)
      val totalPages = (jsonRoot \ "pager" \ "total_pages").extractOrElse[Int](0)
      if (isFinalPage || totalPages < pageNo +1) {
        logger.info(uri) // log only last one to be less verbose
        return
      }
      collectInventoriesOnAPage( urlRoot, pageNo + 1) // recurse to cache all we can
    }
    collectInventoriesOnAPage(s"$LcboDomainURL/inventories?store_id=${lcboId}", 1)
  }

  // generally has side effect to update database with more up to date content from LCBO's (if different)
  private def loadCache(): Unit = {
    def fetchProducts(): Unit = {
      def fetchProductsByStore(): Unit = {
        collectItemsOnAPage(
          accumItems=IndexedSeq[Product](),
          s"$LcboDomainURL/products?store_id=$lcboId",
          requiredSize=0,  // ignored if cacheOnly is true, meaning get them all to cache them all
          cacheOnly = true,
          pageNo = 1,
          filter=Store.notDiscontinued) // We make a somewhat arbitrary assumption that discontinued products are of zero interest.
      }
      inTransaction {  // needed
        tryo { fetchProductsByStore() } match {
          case net.liftweb.common.Failure(m, ex, _) =>
            logger.error(s"Problem loading products into cache for '${lcboId}' with message $m and exception error $ex")
          case Empty =>
            logger.error(s"Problem loading products into cache for '${lcboId}'")
          case _ => ;
        }
      }
    }

    def fetchInventories(): Unit = {
      inTransaction { // needed
        tryo { fetchInventoriesByStore() } match {
          case net.liftweb.common.Failure(m, ex, _) =>
            logger.error(s"Problem loading inventories into cache for '${lcboId}' with message $m and exception error $ex")
          case Empty =>
            logger.error(s"Problem loading inventories into cache for '${lcboId}'")
          case _ => ;
        }
      }
    }

    logger.info(s"loadCache start ${lcboId}")
    // fetch and then make sure model/Squeryl classes update to DB and their cache synchronously, so we can use their caches.
    fetchProducts() // updates products on each query if something new comes up.
    fetchInventories() // and similarly for inventories
    logger.info(s"loadCache ended ${lcboId}") // 30 seconds from last LCBO query to completing the cache update (Jan 23). Needs to be better.
  }

  def asyncLoadCache(): Unit = {
    // A kind of guard: Two piggy-backed requests to loadCache for same store will thus ignore second one.
    // Slightly unwanted consequence is that clients need to test for empty set and not assume it's non empty.
    // we impose referential integrity so we MUST get products and build on that to get inventories that refer to products
    // Note: we may execute this function, get nothing back from LCBO (e.g. website down) and still provide user data because of our db store.
    if (Store.storeProductsLoaded.putIfAbsent(idField.get, Unit).isEmpty) {
      val fut = Future { loadCache() }
      fut foreach {
        case m =>
          //We've persisted along the way for each LCBO page ( no need to refresh because we do it each time we go to DB)
          logger.debug(s"loadCache succeeded for ${lcboId}")
          if (emptyInventory) {
            logger.warn(s"got NO product inventory for storeId ${lcboId} !") // No provision for retrying.
          }

      }
      fut.failed foreach {
        case f =>
          logger.info(s"loadCache explicitly failed for ${lcboId} cause $f")
      }
    }
  }

  def refreshInventories(): Unit = {
    inTransaction {
      storeProducts.refresh // key for whole inventory caching to work!
      inventoryByProductId ++= getInventories
    }
  }

  def refreshProducts(): Unit = {
    inTransaction {
      storeProducts.refresh // key for whole inventory caching to work!
      productsCache ++= productsByLcboId
      productsCacheByCategory ++= productsByCategory
      inventoryByProductId ++= getInventories
    }
  }
}

object Store extends Store with MetaRecord[Store] with pagerRestClient with Loggable {
  private implicit val formats = net.liftweb.json.DefaultFormats
  private val MaxSampleSize = Props.getInt("store.maxSampleSize", 0)

  private val storesCache: concurrent.Map[Long, Store] = TrieMap[Long, Store]()  // primary cache
  override val LcboIdsToDBIds: concurrent.Map[Long, Long] = TrieMap[Long, Long]() //secondary dependent cache
  override def table(): org.squeryl.Table[Store] = MainSchema.stores

  override def addNewItemsToCaches(items: Iterable[Store]): Unit = {
    super.addNewItemsToCaches(items)
    // note to C++ developer: doing the refreshProducts using items instead of storesCache would accomplish nothing since those objects are equal but DIFFERENT.
    // they were copied (the way to do it in immutable functional world, even though in C++ we try our best not to copy such objects)
    inTransaction { storesCache.foreach { case (id, s)  => s.refreshProducts() } } // ensure inventories are refreshed INCLUDING on start up.
  }

  private val storeProductsLoaded: concurrent.Map[Long, Unit] = TrieMap() // auxilliary independent cache
  // effectively a thread-safe lock-free set, which helps avoiding making repeated requests for cache warm up for a store.

  def availableStores: Set[Long] = storesCache.toMap.keySet
  def lcboIdToDBId(l: Int): Option[Long] = LcboIdsToDBIds.get(l)
  def storeIdToLcboId(s: Long): Option[Long] = storesCache.get(s).map(_.lcboId)
  def getStore(s: Long): Option[Store] = storesCache.get(s)
  def getStoreByLcboId(id: Long): Option[Store] = lcboidToDBId(id).flatMap( storesCache.get )

  override def MaxPerPage = Props.getInt("store.lcboMaxPerPage", 0)
  override def MinPerPage = Props.getInt("store.lcboMinPerPage", 0)

    /* Convert a store to XML */
  implicit def toXml(st: Store): Node =
    <store>{Xml.toXml(st.asJValue)}</store>

  @volatile
  var dummy: Any = _
  def timed[T](body: =>T): Double = {
    val start = System.nanoTime
    dummy = body
    val end = System.nanoTime
    ((end - start) / 1000) / 1000.0
  }

  private def notDiscontinued(p: Product): Boolean = !p.isDiscontinued

  private def getStores(dbStores: Map[Long, Store]): Unit = {
    def collectAllStoresIntoCache(): Box[Unit] = {
      // collects stores individually from LCBO REST as Store on all pages starting from a pageNo.
      @tailrec
      def collectStoresOnAPage(urlRoot: String,
                               pageNo: Int): Unit = {
        val uri = urlRoot + additionalParam("per_page", MaxPerPage) + additionalParam("page", pageNo)
        val pageContent = get(uri, HttpClientConnTimeOut, HttpClientReadTimeOut)
        val jsonRoot = parse(pageContent)
        val itemNodes = (jsonRoot \ "result").children.toVector // Uses XPath-like querying to extract data from parsed object jsObj.

        val items = extractLcbo(itemNodes)
        // partition pageStoreSeq into 3 lists, clean (no change), new (to insert) and dirty (to update), using neat groupBy.
        val storesByState: Map[EntityRecordState, IndexedSeq[Store]] = items.groupBy {
          s =>  ( getStoreByLcboId(s.lcboId), s) match {
            case (None, _) => New
            case (Some(store), item) if store.isDirty(item) => Dirty
            case (_ , _) => Clean  // or decided not to handle such as stores "out of bound" that we won't cache.
          }
        }

        // identify the dirty and new stores for batch update and cache them as side effect (automatically)
        val dirtyStores =  storesByState.getOrElse(Dirty, IndexedSeq[Store]())
        val newStores = storesByState.getOrElse(New, IndexedSeq[Store]()).toIndexedSeq
        updateAndInsert(dirtyStores, newStores)

        val isFinalPage = (jsonRoot \ "pager" \ "is_final_page").extractOrElse[Boolean](false)
        if (items.isEmpty || isFinalPage) {
          logger.info(uri) // log only last one to be less verbose
          return // no need to look at more pages
        }
        collectStoresOnAPage(urlRoot, pageNo + 1) // fetch on next page
      }

      // we'd like the is_dead ones as well to update state (but apparently you have to query for it explicitly!?!?)
      val url = s"$LcboDomainURL/stores?"
      tryo {
        // gather stores on this page (url) and recursively to following pages
        inTransaction { collectStoresOnAPage(url, 1) }
        logger.debug(s"done loading stores from LCBO")
      }
    }

    collectAllStoresIntoCache() match {
      case Full(m) => ;
      case net.liftweb.common.Failure(m, ex, _) =>
        logger.error(s"Problem loading LCBO stores into cache with message '$m' and exception error '$ex'")
      case Empty =>
        logger.error(s"Problem loading LCBO stores into cache, none found")
    }
  }

  def init(): Unit = {
    logger.info("Store.init start")
    def asyncLoad: (Iterable[Store]) => Unit = {items: Iterable[Store] => asyncGetStores(items.map { s => s.idField.get -> s }(breakOut))}
    init(asyncLoad)  // the initial db init is long and synchronous, long because of loading Many-to-Many stateful state, depending on storage data
    logger.info("Store.init end")
  }

  def asyncGetStores(x: Map[Long, Store]): Unit = {
    val fut = Future { getStores(x) }
    fut foreach {
      case m =>
        logger.info(s"asyncGetStores (asynch for Store.init) completed")
    }
    fut.failed foreach {
      case f =>
        logger.error(s"asyncGetStores explicitly failed with cause $f") // pretty fatal at this point.
    }
  }

 def findAll(): Iterable[Store] =
    storesCache.values

}