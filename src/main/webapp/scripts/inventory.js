"use strict";

// Global variable, singleton prodSelection
var inventory = (function() {
  var prodIdCheckboxes = [];

  var ajaxFetchInventory = function(storeId, productId) {
      var uri = "http://lcboapi.com/stores/" + storeId + "/products/" + productId + "/inventory"
      $.ajax({
        url: uri,
        dataType: 'jsonp', // for Cross Origin Access
        type: 'GET',
        success: function(data, status){
          var currProdId = data.result.product_id;
          var theCheckBox = prodIdCheckboxes.find(function(elt) {
                                                   return elt.value == currProdId;
                                               });
          if (theCheckBox != undefined) {
            var quantity = data.result.quantity;
            prodIdCBQuantityBuddy($(theCheckBox), currProdId).html(quantity);
          }
        },
        error: function(data, status){
          console.log("Error Data: " + data.responseText + "\nStatus: " + status );
        }
      });
    };

  var prodIdCBQuantityBuddy = function(el, prodId) {
    // server sets name to prodId for easy look up and pairing.
    return el.parents().find("td[name=" + prodId + "]");
  };

  return {
    fetchInventories: function() {
      prodIdCheckboxes = []; // reset it. Too bad, if there was an earlier request. Just take current user input.
      var storeId = storeFinder.getTheSelectedStore();
      $("div.prodContainer").find("input:checkbox").each(function() {
        prodIdCheckboxes.push(this);
        var productId = parseInt(this.value) || 0;
        var quantityEl = prodIdCBQuantityBuddy($(this), productId);
        var quantity = parseInt($(quantityEl).val()) || 0;
        if (storeId > 0 && productId > 0 && quantity <= 0) { // browser gets an update
          ajaxFetchInventory(storeId, productId);
        }
      });
    }
  }
}());


