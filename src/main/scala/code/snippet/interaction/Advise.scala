package code.snippet.interaction

import code.model.{IProduct, Product, Store, User}
import code.snippet.SessionCache.{theAdviseCount, theCategory}
import code.model.AttributeHtmlData
import net.liftweb.http.S
import net.liftweb.http.js.{JE, JsCmd}
import net.liftweb.http.js.JsCmds.{SetHtml, _}
import net.liftweb.json.JsonAST.JValue

import scala.xml.NodeSeq
import code.model.GlobalLCBO_IDs.TaggedLong
/**
  * Created by philippederome on 2016-07-31.
  *
  * Rendering handler for the Advise button (question mark icon gif).
  */
trait Advise extends UtilCommands {
  /**
    * A call to browser (invoking Javascript) when we are done to ask it to deal with incomplete inventories and get them himself
    * if we cannot send them all in a timely fashion
    */
  val fetchInventoriesJS = JE.Call("inventory.fetchInventories")

  /**
    * A call to browser (invoking Javascript) when we are done to show the products to display and back patch
    * inventory calculation initiated by browser (it will go to LCBO API if needed)
    */
  val showProdDisplayJS =  JsShowId("prodDisplay") & fetchInventoriesJS

  /**
    * The prodDisplayJS and getDiv function below can be thought as an Action callback that mixes up markup and Scala.
    * The structure deliberately follows the markup of index.html.
    *
    * @param jsStore input parameter from browser that specifies the store chosen by user (sent as plain number by browser and given as JInt by Lift)
    * @return JSCmd a JavaScript command that Lift Framework will get browser to execute once we are done handling this method
    *         In particular on error nothing gets done (other than earlier instructions to send notices via S state handler)
    *         But in normal case of successful selection, build html elements to display product selections with tables, images, and checkboxes.
    */
  def advise(jsStore: JValue): JsCmd =
    User.currentUser.dmap { S.error("advise", "advise feature unavailable, Login first!"); Noop } { user =>
      /**
        * to make JSON parsing work
        */
      implicit val formatsAdvise = net.liftweb.json.DefaultFormats
      val cmd =
        for {storeId <- jsStore.extractOpt[Long]
             s <- Store.getStore(storeId.PKeyID)  // no result here or earlier on will lead to error below as cmd will be None
             advice = maySelect(s)
        } yield advice

      cmd.fold {
        S.error("We need to establish your local store first, please wait for local geo to become available"); Noop }
      { identity } // normal case
    }

  private def maySelect(store: Store): JsCmd = {
    // Preliminaries
    type InventoryResponse = Store.Selection
    type InventoryResponseValidation = Either[String, InventoryResponse]
    lazy val successToProducts: InventoryResponse => InventoryResponseValidation = inventories =>
      if (inventories.isEmpty) Left(s"Unable to find a product of category ${theCategory.is}")
      // we're reloading into cache to make up for that issue!
      else Right(inventories)

    lazy val errorToProducts: Throwable => InventoryResponseValidation = t =>
      Left(s"Unable to choose product of category ${theCategory.is} with exception error ${t.toString()}")

    val inventoryResponse = store.advise(theCategory.is, theAdviseCount.is, Product)

    inventoryResponse.fold(errorToProducts, successToProducts) match {
      case Left(msg) => S.error(msg); Noop
      case Right(inventories) =>
        S.error("") // work around clearCurrentNotices clear error message to make way for normal layout representing normal condition.
        prodDisplayJS(inventories.map { case (p, q) => QuantityOfProduct(q, p) })
    }
  }

  // for each element of qOfProds, build a Div as NodeSeq and concatenate them as a NodeSeq for the several divs
  def prodDisplayJS(qOfProds: Iterable[QuantityOfProduct]): JsCmd =
    SetHtml("prodContainer", <div>{qOfProds.map(getDiv)}</div>) & hideConfirmationJS & showProdDisplayJS
  // JsCmd (JavaScript  (n JsCmd) can be chained as bigger JavaScript command)

  def getDiv(qOfProd: QuantityOfProduct): NodeSeq = {
    val prod = qOfProd.product
    val prodKeyName = prod.lcboKey.toString
    val inventory = qOfProd.quantity.toString
    val invAttribute = AttributeHtmlData(key="Quantity:", value=inventory, css="prodAttrContentInventory", name=prodKeyName)
    // label it as prodAttrContentInventory for client to interpret and identify easily
    // similarly, assigning the prodId to name helps identify it in browser JS.
    val allAttributes = prod.streamAttributes :+ invAttribute

    // tag the div with misc attributes to facilitate reconciling related data within browser JS
    <div class="prodIdRoot" name={prodKeyName}>{attributesMarkup(allAttributes)}{selectionMarkup(prod)}</div><hr/>
  }

  /**
    *
    * @param attrs the attributes that we mark up as HTML
    * @return layout description:
    * attributes in center column (Blueprint class span-8, width 8 not to side) as attributesMarkup
    * and to its right (Blueprint classes span-8 last) other data containing img and selection details as selectionMarkup.
    * selectionMarkup is top to bottom: image, then checkbox, quantity and cost input text fields right justified (CSS class prodSelectInput).
    * We also add a hiddenCost, so that the cost per item is always available for our calculation
    * (visible to user in attributes in any event, but harder for us to get at for computation)
    */
  def attributesMarkup(attrs: IndexedSeq[AttributeHtmlData]): NodeSeq = {
    def rowMarkup(a: AttributeHtmlData): NodeSeq = {
      <tr>
        <td class="prodAttrHead">{a.key}</td>
        <td class={a.css} name={a.name}>{a.value}</td>
      </tr>
    }
    <div class="span-8">
      <table>
        <thead>
          <tr>
            <th>Attribute</th>
            <th>Value</th>
          </tr>
        </thead>
        <tbody>{attrs.map(rowMarkup)}</tbody>
      </table>
    </div>
  }

  /**
    * Create a selection markup consisting of a check box to confirm multi-selection, a quantity input field,
    * and a cost read-only field.
    * Create a checkBox with value being product id (key for lookups) and label's html representing name.
    * The checkbox state is picked up when we call JS in the container snippet
    * @param prod
    * @return NodeSeq consisting of the markup for check box, quantity of product, and cost of product*quantity
    */
  def selectionMarkup(prod: IProduct): NodeSeq = {
    val checkBoxNS =
      <label>
        <input type="checkbox" class="prodSelectInput" value={prod.lcboKey.toString}/>
        {prod.Name}
      </label><br/>

    val quantityNS =
      <label>Item Quantity:
        <input type="text" class="prodQty prodSelectInput" onchange="prodSelection.updateQtyItem(this);" value="1"/>
      </label><br/>

    // read-only costNS, so user can see it clearly but cannot update it.
    val costNS =
      <label>Cost:
        <input type="text" class="prodCost prodSelectInput" value={prod.price} readonly="readonly"/>
      </label>

    // this info is redundant in DOM to some extent but makes it more convenient to fetch and we're not using JSON here.
    val hiddenCostNS: NodeSeq = <input type="text" class="hiddenProdCost" value={prod.price} hidden="hidden"/>
    val imgNS = <img src={prod.imageThumbUrl}/>
    (<div class="span-8 last">{imgNS}<br/>{checkBoxNS}{quantityNS}{costNS}{hiddenCostNS}</div>): NodeSeq
  }

  /**
    * a quantity indicating how many items of given product is understood to be currently available at the store of user's interest.
    * @param quantity the quantity of product
    * @param product a product we track of for user
    */
  case class QuantityOfProduct(quantity: Long, product: IProduct)
}
