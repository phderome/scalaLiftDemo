package code.comet

import code.model.{Store, ProductMessage}
import net.liftweb._
import http._
import actor._
import net.liftweb.common.Empty

/**
  * Created by philippederome on 2015-11-29.
 * A singleton that provides Product/Store publication/exchange features to all clients.
 * It's an Actor so it's thread-safe because only one
 * msg item will be processed at once.
 */
object StoreProductExchange extends LiftActor with ListenerManager {
  private var msg = ProductMessage(Empty) // private state
  val ProductMsgID = 0
  val StoreID = 1
  private var lastMsg: Int = 0
  private var store = Store()
 /**
   * When we update the listeners, what do we send?
   * We send a ProductMessage or a Store, which are immutable type,
   * so it can be shared with lots of threads without any
   * danger or locking. One strong argument against mutable types, which I don't use directly in this project.
   */
  def createUpdate = {
    lastMsg match {
      case StoreID => store
      case _ => msg
    }
  }

  /**
   * process msg(s) that are sent to the Actor. We cache it minimally, and then update all the listeners.
    */
  override def lowPriority = {
      // use partial function for the callback to our publisher StoreProductExchange,
      // we filter one type of data, cache it so that upon rendering we capture it and act accordingly
      case p: ProductMessage =>
        msg = p
        lastMsg = ProductMsgID
        updateListeners()
      case s: Store =>
        store = s
        lastMsg = StoreID
        updateListeners()
  }
}