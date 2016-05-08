package code.model

import org.squeryl.{ForeignKeyDeclaration, Schema}
import net.liftweb.squerylrecord.RecordTypeMode._

/**
  * Created by philippederome on 2016-01-01.
  *   // References:
  *
  * @see http://stackoverflow.com/questions/12794427/squeryl-and-postgresqls-autoincrement/12876399#12876399
  * @see http://stackoverflow.com/questions/28859798/in-scala-how-can-i-get-plays-models-and-forms-to-play-nicely-with-squeryl-and?answertab=active#tab-top
  */
object MainSchema extends Schema {
  val stores = table[Store]("store")

  val products = table[Product]("product")
  // String columns are typically nullable.

  val inventories = manyToManyRelation(stores, products).
    via[Inventory]((s,p,inv) => (inv.storeid === s.idField.get, p.idField.get === inv.productid))
  // The table in database needs to be called "Inventory". See Inventory class for all columns.

  val userProducts = table[UserProduct]("userproduct")

  val productToUserProducts = oneToManyRelation(products, userProducts).
    via((p,u) => p.id === u.productid)
  // Foreign-key constraints (see end of file):

  // the default constraint for all foreign keys in this schema :
  override def applyDefaultForeignKeyPolicy(foreignKeyDeclaration: ForeignKeyDeclaration) =
  foreignKeyDeclaration.constrainReference

  on(stores) { s =>
    declare(
      s.lcbo_id defineAs (unique,indexed("store_lcbo_id_idx")))
  }

  on(userProducts) { up =>
    declare(
      up.productid defineAs indexed("userproduct_product"),
      up.userid defineAs indexed("userproduct_user"),
      columns(up.userid, up.productid ) are(unique,indexed("user_prod_idx")))
  }

  on(products) { p =>
    declare(
      p.lcbo_id defineAs (unique,indexed("lcbo_id_idx")))
  }
}
/* also seen from printDdl
CREATE TABLE users (id SERIAL,
firstname VARCHAR(32) ,
lastname VARCHAR(32) ,
email VARCHAR(48) , locale VARCHAR(16) , timezone VARCHAR(32) , password_pw VARCHAR(48) , password_slt VARCHAR(20) ,
textarea VARCHAR(2048) , validated BOOLEAN , uniqueid VARCHAR(32) , superuser BOOLEAN) ;
-- table declarations :
create table "store" (
"city" varchar(30) not null,
"name" varchar(200) not null,
"pkid" bigint primary key not null,
"latitude" double precision not null,
"longitude" double precision not null,
"updated" timestamp not null,
"lcbo_id" bigint not null,
"is_dead" boolean not null,
"address_line_1" varchar(200) not null,
"created" timestamp not null
);
create sequence "s_store_pkid";
alter table store alter column pkid set default nextval('s_store_pkid');
-- indexes on store
create unique index "store_lcbo_id_idx" on "store" ("lcbo_id");
create table "product" (
"price_in_cents" integer not null,
"package" varchar(80) not null,
"name" varchar(120) not null,
"pkid" bigint primary key not null,
"secondary_category" varchar(80) not null,
"description" varchar(2000) not null,
"image_thumb_url" varchar(200) not null,
"varietal" varchar(100) not null,
"updated" timestamp not null,
"alcohol_content" integer not null,
"lcbo_id" bigint not null,
"origin" varchar(200) not null,
"volume_in_milliliters" integer not null,
"primary_category" varchar(40) not null,
"is_discontinued" boolean not null,
"serving_suggestion" varchar(300) not null,
"total_package_units" integer not null,
"created" timestamp not null
);
alter table product alter column description drop not null;
alter table product alter column package drop not null;
alter table product alter column varietal drop not null;
alter table product alter column origin drop not null;
alter table product alter column image_thumb_url drop not null;
alter table product alter column serving_suggestion drop not null;
alter table product alter column secondary_category drop not null;

create sequence "s_product_pkid";
alter table product alter column pkid set default nextval('s_product_pkid');
-- indexes on product
create unique index "lcbo_id_idx" on "product" ("lcbo_id");
create table "Inventory" (
"quantity" bigint not null,
"updated_on" varchar(128) not null,
"store_id" bigint not null,
"storeid" bigint not null,
"productid" bigint not null,
"is_dead" boolean not null,
"product_id" bigint not null
);
create table "userproduct" (
"id" bigint primary key not null,
"productid" bigint not null,
"userid" bigint not null,
"selectionscount" bigint not null,
"updated" timestamp not null,
"created" timestamp not null
);
create sequence "s_userproduct_id";
alter table userproduct alter column id set default nextval('s_userproduct_id');
-- indexes on userproduct
create index "userproduct_product" on "userproduct" ("productid");
create index "userproduct_user" on "userproduct" ("userid");
-- foreign key constraints :
alter table "userproduct" add constraint "userproductFK3" foreign key ("productid") references "product"("pkid");
alter table "Inventory" add constraint "InventoryFK1" foreign key ("storeid") references "store"("pkid");
alter table "Inventory" add constraint "InventoryFK2" foreign key ("productid") references "product"("pkid");
-- composite key indexes :
alter table "Inventory" add primary key ("storeid","productid");
-- column group indexes :
create unique index "user_prod_idx" on "userproduct" ("userid","productid");
*/
