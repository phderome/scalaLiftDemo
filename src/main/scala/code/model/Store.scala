package code.model

import code.Rest.{pagerRestClient,DotDecimalString}
import code.snippet.SessionCache.TheStore
import net.liftweb.common.{Full, Empty, Box, Loggable}
import net.liftweb.json._
import net.liftweb.util.Props
import net.liftweb.json.JsonParser.parse

import scala.util.Try
import scala.xml.Node

/**
  * Created by philippederome on 15-11-01.
  * This is captured from JSON parsing.
  */
case class Store(id: Int = 0,
                 is_dead: Boolean = true,
                 name: String = "",
                 address_line_1: String ="",
                 city: String ="",
                 distance_in_meters: Int = 0) extends Loggable {
  // intentional aliasing allowing more standard naming convention.
  val isDead = is_dead

  // intentional change of scale from metres to kilometres, using String representation instead of integer and keeping 3 decimals (0.335 ml for beer)
  def distanceInKMs: String = {
    val v = distance_in_meters.toInt / 1000.0
    f"$v%1.1f KM(s)"
  }

  override def toString = s"$id, name: $name, Address: $address_line_1, city: $city, distance is:$distanceInKMs"
}

object Store extends pagerRestClient with Loggable {
  private implicit val formats = net.liftweb.json.DefaultFormats
  override def MaxPerPage = Props.getInt("store.lcboMaxPerPage", 0)
  override def MinPerPage = Props.getInt("store.lcboMinPerPage", 0)
  def MaxSampleSize = Props.getInt("store.maxSampleSize", 0)

  /**
    * Convert a store to XML
    */
  implicit def toXml(st: Store): Node =
    <item>{Xml.toXml(st)}</item>


  /**
    * Convert the store to JSON format.  This is
    * implicit and in the companion object, so
    * a Store can be returned easily from a JSON call
    */
  implicit def toJson(st: Store): JValue =
    Extraction.decompose(st)


  /**
    * Find the closest store by coordinates
    */
  def find( lat: String,  lon: String): Box[Store] = synchronized {
    findStore(lat, lon) match {
      case util.Success(Full(x)) =>
        TheStore.set(x)
        Full(x)
      case util.Success(Empty) => logger.error("unable to find closest store info"); Empty
      case util.Failure(x) => logger.error(s"unable to find closest store with error $x"); Empty
      case _ => logger.error("unknown error in finding closest store"); Empty
    }
  }

  private def findStore(lat: String, lon: String): Try[Box[Store]] = {
    val url = s"$LcboDomainURL/stores?where_not=is_dead" +
      additionalParam("lat", lat) +
      additionalParam("lon", lon)
    Try {
      collectStoresOnAPage(List[Store](), url, MaxSampleSize, pageNo = 1).headOption
    }
  }

  @throws(classOf[net.liftweb.json.MappingException])
  @throws(classOf[net.liftweb.json.JsonParser.ParseException])
  @throws(classOf[java.io.IOException])
  @throws(classOf[java.net.SocketTimeoutException])
  @throws(classOf[java.net.UnknownHostException]) // no wifi/LAN connection for instance
  @scala.annotation.tailrec
  private final def collectStoresOnAPage(accumItems: List[Store],
                                 urlRoot: String,
                                 requiredSize: Int,
                                 pageNo: Int): List[Store] = {
    val pageSize = math.max(MinPerPage,
      math.min(MaxPerPage, requiredSize)) // constrained between minPerPage and maxPerPage.
    // specify the URI for the LCBO api url for liquor selection
    val uri = urlRoot + additionalParam("per_page", pageSize) + additionalParam("page", pageNo)
    logger.info(uri)
    val pageContent = get(uri, HttpClientConnTimeOut, HttpClientReadTimeOut) // fyi: throws IOException or SocketTimeoutException
    val jsonRoot = parse(pageContent) // fyi: throws ParseException
    val itemNodes = (jsonRoot \ "result").children // Uses XPath-like querying to extract data from parsed object jsObj.
    val items = for (p <- itemNodes) yield p.extract[Store]
    lazy val outstandingSize = requiredSize - items.size
    lazy val isFinalPage = (jsonRoot \ "pager" \ "is_final_page").extract[Boolean]

    if (items.isEmpty || outstandingSize <= 0 || isFinalPage) return accumItems ++ items

    collectStoresOnAPage(
      accumItems ++ items,
      urlRoot,
      outstandingSize,
      pageNo + 1) // union of this page with next page when we are asked for a full sample
  }

}