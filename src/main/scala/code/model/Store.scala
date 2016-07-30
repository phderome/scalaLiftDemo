package code.model

import code.model.GlobalLCBO_IDs.{LCBO_ID, P_KEY}
import code.model.Inventory.fetchInventoriesByStore
import code.model.Product.fetchByStore
import code.model.prodAdvisor.{ProductAdvisorComponentImpl, ProductAdvisorDispatcher}
import code.model.utils.RNG
import code.model.utils.RetainSingles.asMap
import net.liftweb.common.Box
import net.liftweb.json._
import net.liftweb.record.MetaRecord
import net.liftweb.record.field._
import net.liftweb.squerylrecord.RecordTypeMode._
import net.liftweb.util.Props
import org.squeryl.Table
import org.squeryl.annotations._

import scala.collection._
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}
import scala.xml.Node


class Store private() extends LCBOEntity[Store] with IStore with ProductAdvisorDispatcher with ProductAdvisorComponentImpl {

  //products is a StatefulManyToMany[Product,Inventory], it extends Iterable[Product]
  lazy val storeProducts = MainSchema.inventories.leftStateful(this)
  @Column(name="pkid")
  override val idField = new LongField(this, 0)  // our own auto-generated id
  override val inventoryByProductIdMap: P_KEY => Option[Inventory] = key => inventoryByProductId.toMap.get(key)
  val lcbo_id = new LongField(this) // we don't share same PK as LCBO!
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
  private val productsCache = TrieMap[LCBO_ID, IProduct]()
  private val productsCacheByCategory = TrieMap[String, IndexedSeq[KeyKeeperVals]]()  // don't put whole IProduct in here, just useful keys.
  private val inventoryByProductId = TrieMap[P_KEY, Inventory]()
  private val getCachedInventoryItem: Inventory => Option[Inventory] =
    inv => inventoryByProductId.get(P_KEY(inv.productid))

  // for Loader and LCBOEntity
  override def table: Table[Store] = Store.table

  override def cache: concurrent.Map[P_KEY, Store] = Store.cache

  override def lcboIdToPK: concurrent.Map[LCBO_ID, P_KEY]  = Store.lcboIdToPKMap

  override def pKey: P_KEY = P_KEY(idField.get)

  override def meta: MetaRecord[Store] = Store

  override def equals(other: Any): Boolean =
    other match {
      case that: Store =>
        (this eq that) ||
        (that.canEqual(this) &&
          Name == that.Name &&
          isDead == that.isDead &&
          addressLine1 == that.addressLine1)
      case _ => false
    }

  override def Name: String = name.get

  override def isDead: Boolean = is_dead.get

  override def addressLine1: String = address_line_1.get

  override def canEqual(other: Any): Boolean =
    other.isInstanceOf[Store]

  // following three caches leverage ORM's stateful cache of storeProducts and inventories above (which are not presented as map but as slower sequence;
  // we organize as map for faster access).
  // They're recomputed when needed by the three helper functions that follow.
  override def getProduct(x: LCBO_ID): Option[IProduct] = productsCache.get(x)

  override def getProductKeysByCategory(lcboCategory: String): IndexedSeq[KeyKeeperVals] =
    productsCacheByCategory.get(lcboCategory).
      fold(IndexedSeq[KeyKeeperVals]()){ identity }

  def refreshProducts(): Unit =  {
    refreshInventories()
    addToCaches(storeProducts.toIndexedSeq)
  }

  private def addToCaches(items: IndexedSeq[IProduct]): Unit = {
    productsCache ++= asMap(items, {p: IProduct => p.lcboId})
    // project the products to category+key pairs, group by category yielding sequences of category, keys and retain only the key pairs in those sequences.
    // The map construction above technically filters outs from items if there are duplicate keys, so reuse same collection below (productsCache.values)
    productsCacheByCategory ++= productsCache.values.
      map(x => CategoryKeyKeeperVals(x.primaryCategory, x: KeyKeeperVals)).toIndexedSeq.
      groupBy(_.category).
      mapValues(_.map(x => x.keys))
  }

  def refreshInventories(): Unit = inTransaction {
    storeProducts.refresh // key for whole inventory caching to work!
    inventoryByProductId ++= getInventories
  }

  private def getInventories: Map[P_KEY, Inventory] =
    asMap(inventories, { i: Inventory => P_KEY(i.productid)} )

  private def inventories = storeProducts.associations

  def advise(rng: RNG, category: String, requestSize: Int, runner: ProductRunner): Box[Iterable[(IProduct, Long)]] =
    advise(rng, this, category, requestSize, runner)

  override def asyncLoadCache(): Unit =
  // A kind of guard: Two piggy-backed requests to loadCache for same store will thus ignore second one.
    if (Store.storeProductsLoaded.putIfAbsent(idField.get, Unit).isEmpty) loadCache()

  // generally has side effect to update database with more up to date content from LCBO's (if different)
  private def loadCache(): Unit = {
    def fetchProducts() = {
      def context(err: String): String =
        s"Problem loading products into cache with exception error $err"
      lazy val onEmptyError = "Problem loading products into cache, none found"
      val theProducts = fetchByStore(lcboId)
      theProducts.toOption.fold[Unit](
        theProducts.failed.foreach(f => logger.error(context(f.toString()))))(
        items => {
          if (items.isEmpty) logger.error(onEmptyError)
          addToCaches(items)
        })
    }

    def fetchInventories() = {
      def inventoryTableUpdater: (Iterable[Inventory]) => Unit = MainSchema.inventories.update
      def inventoryTableInserter: (Iterable[Inventory]) => Unit = MainSchema.inventories.insert

      val context = (err: String) =>
        s"Problem loading inventories into cache for '$lcboId' with exception error $err"
      // fetch @ LCBO, store to DB and cache into ORM stateful caches, trap/log errors, and if all good, refresh our own store's cache.
      // we chain errors using flatMap (FP fans apparently like this).
      val computation: Try[Unit] =
        fetchInventoriesByStore(
          webApiRoute = "/inventories",
          getCachedInventoryItem,
          inventoryByProductId.toMap,
          Seq("store_id" -> lcboId, "where_not" -> "is_dead")).
      flatMap { inventories =>
        Try(inTransaction {
          execute[Inventory](inventories.updatedInvs, inventoryTableUpdater) // bulk update the ones needing an update, having made the change from LCBO input
          execute[Inventory](inventories.newInvs, inventoryTableInserter) // bulk insert the ones needing an insert having filtered out duped composite keys
        })
      }

      computation.toOption.fold[Unit](
        computation.failed.foreach(f => logger.error(context(f.toString()))))( (Unit) => refreshInventories())

    }

    val fetches =
      for (p <- Future(fetchProducts()); // fetch and then make sure model/Squeryl classes update to DB and their cache synchronously,
           // so we can use their caches.
           i <- Future(fetchInventories())) yield i
    // similarly for inventories and serialize intentionally because of Ref.Integrity  if no exception was thrown

    fetches onComplete {
      case Success(_) => //We've persisted along the way for each LCBO page ( no need to refresh because we do it each time we go to DB)
        logger.debug(s"loadCache async work succeeded for $lcboId")
        if (emptyInventory) logger.warn(s"got no product inventory for storeId $lcboId !") // No provision for retrying.
      case Failure(f) => logger.info(s"loadCache explicitly failed for $lcboId cause ${f.getMessage}")
    }
    logger.info(s"loadCache async launched for $lcboId") // about 15 seconds, likely depends mostly on network/teleco infrastructure
  }

  override def lcboId: LCBO_ID = LCBO_ID(lcbo_id.get)

  private def emptyInventory: Boolean = inventories.toIndexedSeq.forall(_.quantity == 0)

  case class CategoryKeyKeeperVals(category: String, keys: KeyKeeperVals) {}

}

object Store extends Store with MetaRecord[Store] {
  override val cache: concurrent.Map[P_KEY, Store] = TrieMap()  // primary cache
  val lcboIdToPKMap: concurrent.Map[LCBO_ID, P_KEY] = TrieMap() //secondary dependent cache, a.k.a. index
  val queryFilterArgs = getSeq("store.query.Filter")(ConfigPairsRepo.defaultInstance) :+ "per_page" -> Props.getInt("store.lcboMaxPerPage", 0)
  private val storeProductsLoaded: concurrent.Map[Long, Unit] = TrieMap()

  def findAll: Iterable[Store] = cache.values

  def storeIdToLcboId(pKey: P_KEY): Option[LCBO_ID] = getStore(pKey).map(_.lcboId)

  def getStore(pKey: P_KEY): Option[Store] = cache.get(pKey)
  // effectively a thread-safe lock-free set, which helps avoiding making repeated requests for cache warm up for a store.

  /* Convert a store to XML, @see Scala in Depth implicit view */
  implicit def toXml(s: Store): Node =
    <store>{Xml.toXml(s.asJValue)}</store>

  /**
    * synchronous because once the webapp accepts requests, this load must have completed so that the store collection is not empty.
    * Well... Trend is to do everything asynch these days...
    */
  override def load(): Unit = inTransaction {
    def context( ex: String): String =
      s"Problem loading LCBO stores into cache with exception error '$ex'"
    lazy val onEmptyError = "Problem loading LCBO stores into cache, none found"

    logger.info("load start")
    val dbStores = from(table)(item => select(item))
    cacheItems(dbStores)
    // the initial db select is long and synchronous, long because of loading Many-to-Many stateful state, depending on stored data
    val refreshed = getStores  // improves our cache of stores with latest info from LCBO. In real-world,
    // we might have the app run for long and call getStores async once in a while
    refreshed.toOption.fold[Unit](
      refreshed.failed.foreach(f => logger.error(context(f.toString()))))(
      items => {
        if (items.isEmpty) logger.error(onEmptyError)
        synchDirtyAndNewItems(items, getCachedItem)
      })
    logger.info("load end") // trace because it takes a long time.
  }

  override def table: Table[Store] = MainSchema.stores

  override def cacheItems(items: Iterable[Store]): Unit = {
    super.cacheItems(items)
    cache.values.foreach( _.refreshProducts())  // ensure inventories are refreshed INCLUDING on start up.
  }

  def getCachedItem: IStore => Option[IStore] = s =>
    for (pKey <- lcboIdToPKMap.get(s.lcboId);
         ss <- getStore(pKey)) yield ss

  private def getStores = Try {
      collectItemsAsWebClient("/stores", extract, queryFilterArgs)
    // nice to know if it's empty, so we can log an error in that case. That's captured by box and looked at within checkErrors using briefContextErr.
    }
}
