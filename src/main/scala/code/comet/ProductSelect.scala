package code.comet

import code.model._
import code.snippet.SessionCache.{TheStore, TheCategory}
import net.liftweb.common._
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.Noop
import net.liftweb.http.{CometListener, CometActor, S, SHtml}
import net.liftweb.util.{Props, CssSel}

/**
  * This snippet contains state only via HTTP session (A Liftweb snippet is a web page fragment that has autonomous existence equivalent to a page with framework
  * responsible to render them all and to handle events between snippets and corresponding html div elements) AND displayInstructions representing last data
  * received asynchronously as a CometListener, which updates this with context how it should render itself, namely enabling/disabling button.
  * Much html and Javascript is generated here thanks to the capabilities of liftweb. Similar general comments apply more or less for other comet listeners and to snippets.
  * Created by philippederome on 15-10-26.
  */
class ProductSelect extends CometActor with CometListener with CSSUtils with Loggable {

  private val provider = new ProductProvider() with PersistProductIDAsDB
  // Dependency Injection (another part of the website could use a DB table!)
  private val maxSampleSize = Props.getInt("product.maxSampleSize", 10)
  private var displayInstructions: Either[ClearInstruction, Product] = Left(ClearInstruction()) // private state indicating whether to show product when one is defined (Right of Either) or clear instruction not to show old product
  // and in this context here whether to enable or disable the Recommend button (clear instruction means enable button, product being present means disable).

  def registerWith = ProductExchange // our publisher to whom we register interest

  override def lowPriority = {
    // use partial function for the callback to our publisher ProductExchange, we filter one type of data, cache it so that upon rendering we capture it and act accordingly
    case p: Either[ClearInstruction @unchecked, Product @unchecked] =>
      displayInstructions = p; reRender()
  }

  def render = {
    /**
      * provides a recommendation to a user for a product at LCBO at the selected store if valid.
      * Most of the logic is to distinguish different error handling cases, so that some troubleshooting can take place with some context.
      * @return a JsCmd, i.e. generated JavaScript that the browser invokes in response to an earlier asynchronous form as Liftweb takes that JsCmd for browser to execute.
      *         Prior to that JsCmd, we can execute required code in server side.
      *         We eventually re-render to toggle buttons after action takes place (as a result of our publishing to Actors ProductExchange and ConfirmationExchange).
      *         These actors and their messages effectively coordinate ProductSelect and ProductConsumer as well as ProductDisplay.
      */
    def recommend(): JsCmd = {
      def maySelect(): JsCmd =
        TheStore.is match {
          // validates expected numeric input TheStore (a http session attribute) and when valid, do real handling of accessing LCBO data
          case s if s > 0 =>
            val cat = TheCategory.is.openOr("")
            val prod = provider.recommend(maxSampleSize, s, cat) match {
              // we want to distinguish error messages to user to provide better diagnostics.
              case util.Success(p) =>
                p or {
                  S.notice(s"no product available for category $cat");
                  Empty
                } // returns prod normally but if empty, send a notice of error and return empty.
              case util.Failure(ex) => S.error(s"Unable to choose product of category $cat with error $ex"); Empty
            }
            ConfirmationExchange ! "" // sends a clear string for the confirmation receiver comet actor in all cases since user clicked button.
            prod.dmap {
              Noop
            } { p: Product =>
              ProductExchange ! Right(p) // Sends out to Comet Actors AND SELF asynchronously the event that this product can now be rendered.
              S.clearCurrentNotices // clear error message to make way for normal layout representing normal condition.
            }
          case _ => S.error(s"Enter a number > 0 for Store") // Error goes to site menu, but we could also send it to a DOM element if we were to specify an additional parameter
        }
      displayInstructions match {
        case Left(c) => maySelect() // normal processing  (ProductConsumer does it the other way around as it plays opposite role as to when it should be active)
        case Right(p) => Noop // ignore consecutive clicks for flow control, ensuring we take only the user's first click as actionable for a series of clicks on button before we have time to disable it
      }
    }

    // binding using Liftweb method of CSS selectors, left-hand side for each line match a number of DOM elements, whereas RHS takes an action on selected elements.
    // Note: toggling of buttons is useful to prevent excessive consecutive requests of same that would be too fast and unmanageable (flow control since we SIMULATE consumption)
    // We also do flow control in recommend handler to discard consecutive click events.
    val addImgElem: CssSel = "button *+" #> <img src="/images/recommend.png" alt=" "/>
    displayInstructions match {
      case Left(c) =>
        "button" #> SHtml.ajaxButton("Recommend", () => recommend()) & addImgElem // case is "Clear Product" meaning none applicable and thus we need to recommend, so set up ajax CB recommend that will do server side work and finally JS work, also label button with word Recommend
      case Right(p) =>
        "button" #> disable & addImgElem // toggle to disable button. For Left case, we also toggle but don't need to explicitly enable since first instruction is to rewrite completely the DOM element and that removes the disabled attribute.
    }
  }
}
