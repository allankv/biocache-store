package au.org.ala.util

import au.org.ala.biocache.AttributionDAO
import au.org.ala.biocache.Attribution
import au.org.ala.biocache.LocationDAO
import java.io.File
import org.wyki.cassandra.pelops.{ Mutator, Pelops, Policy, Selector }
import scala.collection.mutable.{ LinkedList, ListBuffer }
import org.apache.cassandra.thrift.{ Column, ConsistencyLevel, ColumnPath, SlicePredicate, SliceRange }

/**
 * Loads an export from the old portal database of point lookups.
 * 
 * 
select ic.code, cc.code, icm.institution_uid, icm.collection_uid, ic.name from inst_coll_mapping icm
inner join institution_code ic ON ic.id = icm.institution_code_id
inner join collection_code cc ON cc.id = icm.collection_code_id
into outfile '/tmp/coll-mapping.txt';
 * 
 * @author Dave Martin (David.Martin@csiro.au)
 */
object AttributionLoader {

  def main(args: Array[String]): Unit = {
    import FileHelper._
    println("Starting Collection Loader....")
    val file = new File("/data/coll-mapping.txt")
    val locationDAO = new LocationDAO
    val attributionDAO = new AttributionDAO
    var counter = 0
    file.foreachLine { line => {
    		counter+=1
    		//add point with details to 
    		val parts = line.split('\t')
    		var attribution = new Attribution
    		attribution.institutionUid = parts(2)
    		attribution.collectionUid = parts(3)
    		attribution.institutionName = parts(4)
    		attributionDAO.addCollectionMapping(parts(0), parts(1), attribution)
    	}
    }
    println(counter)
    Pelops.shutdown
  }
}