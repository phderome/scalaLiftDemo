package code.model

import code.UnitTest
import code.model.GlobalLCBO_IDs.{LCBO_ID, P_KEY}
import code.model.prodAdvisor.ProductAdvisorComponentImpl
import code.model.utils.RNG
import net.liftweb.common.Full

import scala.collection.IndexedSeq

// Highly experimental (scalacheck)
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen


/**
  * Created by philippederome on 2016-05-04.
  */
class ProductAdvisorComponentImplTest extends UnitTest {
  class ProductAdvisorComponentImplTest extends ProductAdvisorComponentImpl

  val instance = new ProductAdvisorComponentImplTest
  val drunkShuffler = instance.agent

  val NullInventoryService = new InventoryService {
    override def pKey: P_KEY = P_KEY(1)
    override def lcboId: LCBO_ID = LCBO_ID(1)
    override val inventoryByProductIdMap: P_KEY => Option[Inventory] = key => None
    override def getProduct(x: LCBO_ID): Option[IProduct] = None
    override def getProductKeysByCategory(lcboCategory: String) = IndexedSeq[KeyKeeperVals]()
    override def asyncLoadCache() = {} // intentional Noop here.
  }


  behavior of "No product available empty list"
  val outOfStockRunner = new ProductRunner {
    override def fetchByStoreCategory(lcboStoreId: Long, category: String, requiredSize: Int): IndexedSeq[IProduct] = IndexedSeq[Product]()
  }
  it should s"advise an empty list of products when using dummy InventoryService and ProductRunner when no products of category can be found" in {
    val rng = RNG.Simple(411)
    drunkShuffler.advise(rng, NullInventoryService, "wine", 5, outOfStockRunner) match {
      case Full(x) => x.toList shouldBe empty
      case _ => 1 should equal(2) // we should never get here
    }
  }

  it should s"advise an empty list of products when no products of category can be found" in {
    val categories = Seq("wine", "spirits", "beer", "ciders", "coolers", "non-Alc")
    val rng = RNG.Simple(411)
    categories.foreach(cat => drunkShuffler.advise(rng, Store, cat, 5, outOfStockRunner) match {
      case Full(x) => x.toList shouldBe empty
      case _ => true should equal(false) // we should never get here
    })
  }


  //val ints = Gen.choose(1, 1000), eventually might use that.
  trait typicalBeerProduct extends IProduct {
    override def primaryCategory: String = "beer"
    override def isDiscontinued: Boolean = false
    override def totalPackageUnits: Int = 1
    override def imageThumbUrl: String = "http://lcboapi.com/someimage.png"
    override def Package: String = "bottle"

    // Change unit of currency from cents to dollars and Int to String
    override def price: String = "$2.00"
    override def streamAttributes: IndexedSeq[Attribute] = Product.streamAttributes
    override def getCachedItem: (IProduct) => Option[IProduct] = s => Some(s) // identity, kind of.
  }

  trait typicalWineProduct extends IProduct {
    override def primaryCategory: String = "wine"
    override def isDiscontinued: Boolean = false
    override def totalPackageUnits: Int = 1
    override def imageThumbUrl: String = "http://lcboapi.com/someimage.png"
    override def Package: String = "bottle"

    // Change unit of currency from cents to dollars and Int to String
    override def price: String = "$15.00"
    override def streamAttributes: IndexedSeq[Attribute] = Product.streamAttributes
    override def getCachedItem: (IProduct) => Option[IProduct] = s => Some(s) // identity, kind of.
  }

  val Heineken = new typicalBeerProduct {
    override def pKey: P_KEY = P_KEY(1)
    override def lcboId: LCBO_ID = LCBO_ID(1)
    override def Name: String = "Heineken"
  }

  val MillStLager = new typicalBeerProduct {
    override def pKey: P_KEY = P_KEY(2)
    override def lcboId: LCBO_ID = LCBO_ID(2)
    override def Name: String = "Mill Street Lager"
  }

  val OysterBay = new typicalWineProduct {
    override def pKey: P_KEY = P_KEY(1000)
    override def lcboId: LCBO_ID = LCBO_ID(1000)
    override def Name: String = "Oyster Bay"
  }
  val ChampagneKrug = new typicalWineProduct {
    override def pKey: P_KEY = P_KEY(1001)
    override def lcboId: LCBO_ID = LCBO_ID(1001)
    override def Name: String = "Krug Champagne"
  }

  val singleBeerRunner = new ProductRunner {
    override def fetchByStoreCategory(lcboStoreId: Long, category: String, requiredSize: Int): IndexedSeq[IProduct] =
      if (category == "beer") Vector(Heineken) else Vector()
  }

  val HeinekensBut63Runner = new ProductRunner {
    // depends precisely on  props store.fixedRNGSeed=411
    val someBeers =  Seq.fill(63)( Heineken) ++ Seq(MillStLager) ++ Seq.fill(37)( Heineken)
    val someWines = Seq(OysterBay, ChampagneKrug)
    // Could create (or better yet generate randomly with ScalaCheck) a handful of concrete Product instances.
    //Need some reasonable simulation for following. With just a bit more work, we could have something really interesting here.
    override def fetchByStoreCategory(lcboStoreId: Long, category: String, requiredSize: Int): IndexedSeq[IProduct] = category match {
      case "beer" => someBeers.toVector
      case "wine" => someWines.toVector
      case _ => Vector()
    }
  }
  val HeinekensBut62Runner = new ProductRunner {
    // depends precisely on  props store.fixedRNGSeed=411. Mill St is at a different spot!
    val someBeers =  Seq.fill(62)( Heineken) ++ Seq(MillStLager) ++ Seq.fill(38)( Heineken)
    val someWines = Seq(OysterBay, ChampagneKrug)
    // Could create (or better yet generate randomly with ScalaCheck) a handful of concrete Product instances.
    //Need some reasonable simulation for following. With just a bit more work, we could have something really interesting here.
    override def fetchByStoreCategory(lcboStoreId: Long, category: String, requiredSize: Int): IndexedSeq[IProduct] = category match {
      case "beer" => someBeers.toVector
      case "wine" => someWines.toVector
      case _ => Vector()
    }
  }

  behavior of "Single product match by category once list of 1 and once empty list"
  it should s"get a Heineken name in first selection for beer when it is the only product" in {
    val rng = RNG.Simple(411)
    drunkShuffler.advise(rng, NullInventoryService, "beer", 1, singleBeerRunner) match {
      case Full(x) => x.headOption.foreach(_._1.Name should equal("Heineken"))
      case _ =>  assert(false) // we don't care about this.
    }
  }
  // following is more to validate previous test. This is not particularly interesting.
  it should s"NOT get a Heineken name in first selection for wine when Heineken is the only (beer) product" in {
    val rng = RNG.Simple(411)
    drunkShuffler.advise(rng, NullInventoryService, "wine", 1, singleBeerRunner) match {
      case Full(Nil) => // success: wine != beer, no match.
      case _ =>  assert(false) // we should not get here.
    }
  }

  // following tests depend on a shuffle of collection indices (post category filter) and normal Scala collection take as selection strategy.
  behavior of "Deterministic random selection of single item among 101 beers: 1 Mill Street among 100 Heinekens fixed seed"
  val rng411 = RNG.Simple(411)
  def validateSelectedName(runner: ProductRunner, rng: RNG, name: String): Unit = {
    drunkShuffler.advise(rng, NullInventoryService, "beer", 1, runner) match {
      case Full(x) => x.headOption.foreach{ _._1.Name should equal(name)}
      case _ =>  assert(false) // we should not get here, so fail it.
    }
  }
  // this more primitive test on shuffling indices motivates the next two material examples that do shuffling on products.
  it should s"predict take 1st element from 0 to 100 randomly permuted exactly on specific seed Simple at 63" in {
    val (shuffled, _) = RNG.shuffle((0 to 100)).run(rng411)
    shuffled.take(1) should equal(Seq(63))
  }

  it should s"get Mill Street Lager or really #63 out of 0 to 100 (64th position in 0-index system, literally 63) if seed is set to 411 with 100 Heinekens out of 101!!!" in
    validateSelectedName(HeinekensBut63Runner, rng411, "Mill Street Lager")

  it should s"get Heineken as Mill Street Lager is not in special spot of index value 63 among 101, 100 of which are Heineken!" in
    validateSelectedName(HeinekensBut62Runner, rng411, "Heineken")

}