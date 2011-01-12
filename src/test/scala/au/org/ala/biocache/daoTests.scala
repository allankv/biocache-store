package au.org.ala.biocache
import java.io.File
import java.util.UUID
import org.apache.cassandra.thrift._
import org.apache.thrift.transport._
import org.wyki.cassandra.pelops.{Pelops}
import scala.reflect._

object PointDAOTest {
	def main(args : Array[String]) : Unit = {
		val pointDAO = new LocationDAO
		val point = pointDAO.getLocationByLatLon("-33.25", "135.85")
		if(!point.isEmpty){
			println(point.get.ibra)
			println(point.get.stateProvince)
		} else {
			println("No matching point")
		}
	}
}

object OccurrenceDAOTest {
	def main(args : Array[String]) : Unit = {
		val occurrenceDAO = new OccurrenceDAO
		val ot1 = occurrenceDAO.getByUuid("3480993d-b0b1-4089-9faf-30b4eab050ae", OccurrenceType.Raw)
		if(!ot1.isEmpty){
			val rawOccurrence = ot1.get._1
			val rawClassification = ot1.get._2 
			println(">> The bean set scientific name: " + rawClassification.scientificName) 
			println(">> The bean set class name: " + rawClassification.classs)
		} else {
			println("failed")
		}
		
		val ot2 = occurrenceDAO.getByUuid("3480993d-b0b1-4089-9faf-30b4eab050ae", OccurrenceType.Processed)
		if(!ot2.isEmpty){
			val o = ot1.get._1
			val c = ot1.get._2 
			println(">> (processed) The bean set scientific name: " + c.scientificName) 
			println(">> (processed) The bean set class name: " + c.classs)
		} else {
			println("failed")
		}

		val ot3 = occurrenceDAO.getByUuid("3480993d-b0b1-4089-9faf-30b4eab050ae", OccurrenceType.Consensus)
		if(!ot3.isEmpty){
			val o = ot1.get._1
			val c = ot1.get._2 
			println(">> (consensus) The bean set scientific name: " + c.scientificName) 
			println(">> (consensus) The bean set class name: " + c.classs)
		} else {
			println("failed")
		}
		
		val uuid = UUID.randomUUID.toString
		var qa = new QualityAssertion
		qa.uuid = uuid
		qa.assertionCode  = 123
		qa.positive = true
		qa.comment = "My comment"
		qa.userId = "David.Martin@csiro.au"
		qa.userDisplayName = "Dave Martin"
		
		occurrenceDAO.addQualityAssertion("3480993d-b0b1-4089-9faf-30b4eab050ae",qa)
		occurrenceDAO.addQualityAssertion("3480993d-b0b1-4089-9faf-30b4eab050ae",qa)

		val uuid2 = UUID.randomUUID.toString
		var qa2 = new QualityAssertion
		qa2.uuid = uuid2
		qa2.assertionCode  = 123
		qa2.positive = true
		qa2.comment = "My comment"
		qa2.userId = "David.Martin@csiro.au"
		qa2.userDisplayName = "Dave Martin"
		
		occurrenceDAO.addQualityAssertion("3480993d-b0b1-4089-9faf-30b4eab050ae",qa2)
		
//		val om = new ObjectMapper
//		println(om.writeValueAsString(qa))
		
		Pelops.shutdown
	}
}