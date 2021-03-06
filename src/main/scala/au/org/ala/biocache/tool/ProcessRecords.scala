package au.org.ala.biocache.tool

import scala.actors.Actor
import scala.collection.mutable.ArrayBuffer
import au.org.ala.biocache._
import java.io.File
import org.slf4j.LoggerFactory
import au.org.ala.biocache.model.FullRecord
import au.org.ala.biocache.util.{StringConsumer, OptionParser, FileHelper}
import au.org.ala.biocache.cmd.{IncrementalTool, Tool}
import au.org.ala.biocache.index.IndexRecords
import au.org.ala.biocache.processor.RecordProcessor
import java.util.concurrent.ArrayBlockingQueue

object ProcessAll extends Tool {

  def cmd = "process-all"
  def desc = "Process all records"

  def main(args:Array[String]){

    var threads:Int = 4
    val parser = new OptionParser(help) {
      intOpt("t", "thread", "The number of threads to use", {v:Int => threads = v } )
    }
    if(parser.parse(args)){
      ProcessRecords.processRecords(4, None, None)
    }
  }
}

/**
 * A simple threaded implementation of the processing.
 */
object ProcessRecords extends Tool with IncrementalTool {

  import FileHelper._

  def cmd = "process"
  def desc = "Process records (geospatial, taxonomy)"

  val occurrenceDAO = Config.occurrenceDAO
  val persistenceManager = Config.persistenceManager
  val logger = LoggerFactory.getLogger("ProcessRecords")

  def main(args : Array[String]) : Unit = {

    logger.info("Starting processing...")
    var threads:Int = 4
    var startUuid:Option[String] = None
    var endUuid:Option[String] = None
    var checkDeleted = false
    var dataResourceUid:Option[String] = None
    var checkRowKeyFile = false
    var rowKeyFile = ""

    val parser = new OptionParser(help) {
      intOpt("t", "thread", "The number of threads to use", {v:Int => threads = v } )
      opt("s", "start","The record to start with", {v:String => startUuid = Some(v)})
      opt("e", "end","The record to end with", {v:String => endUuid = Some(v)})
      opt("dr", "resource", "The data resource to process", {v:String => dataResourceUid = Some(v)})
      booleanOpt("cd", "checkDeleted", "Check deleted records", {v:Boolean => checkDeleted = v})
      opt("crk", "check for row key file",{ checkRowKeyFile = true })
    }
    
    if(parser.parse(args)){

      if(!dataResourceUid.isEmpty && checkRowKeyFile){
        val (hasRowKey, retrievedRowKeyFile) = ProcessRecords.hasRowKey(dataResourceUid.get)
        rowKeyFile = retrievedRowKeyFile.getOrElse("")
      }

      if(rowKeyFile != ""){
        //process the row key file
        processFileOfRowKeys(new java.io.File(rowKeyFile), threads)
      } else {
        logger.info("Processing " + dataResourceUid.getOrElse("") + " from " + startUuid + "to " +endUuid+ " with " + threads + "actors")
        processRecords(threads, startUuid, dataResourceUid, checkDeleted, lastKey = endUuid)
      }
    }
    //shutdown the persistence
    persistenceManager.shutdown
  }
  
  def getProcessedTotal(pool:Array[Actor]):Int = {
    var size = 0
    for(i<-0 to pool.length-1){
      size += pool(i).asInstanceOf[Consumer].processedRecords
    }
    size
  }

  /**
   * Process a set of records with keys in the supplied file
   * @param file
   * @param threads
   */
  def processFileOfRowKeys(file: java.io.File, threads: Int) {
    val queue = new ArrayBlockingQueue[String](100)
    var ids = 0
    val recordProcessor = new RecordProcessor
    val pool: Array[StringConsumer] = Array.fill(threads) {
      var counter = 0
      var startTime = System.currentTimeMillis
      var finishTime = System.currentTimeMillis

      val p = new StringConsumer(queue, ids, { guid =>
        counter += 1
        val rawProcessed = Config.occurrenceDAO.getRawProcessedByRowKey(guid)
        if (!rawProcessed.isEmpty) {
          val rp = rawProcessed.get
          recordProcessor.processRecord(rp(0), rp(1))

          //debug counter
          if (counter % 1000 == 0) {
            finishTime = System.currentTimeMillis
            logger.info(counter + " >> Last key : " + rp(0).uuid + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
            startTime = System.currentTimeMillis
          }
        }
      })
      ids += 1
      p.start
      p
    }

    file.foreachLine(line => queue.put(line.trim))
    pool.foreach(t => t.shouldStop = true)
    pool.foreach(_.join)
  }

  /**
   * Processes the supplied row keys in a Thread
   */
  def processRecords(file:File, threads: Int, startUuid:Option[String]) : Unit = {
    var ids = 0
    val pool = Array.fill(threads){ val p = new Consumer(Actor.self,ids); ids +=1; p.start }
    logger.info("Starting to process a list of records...")
    val start = System.currentTimeMillis
    val startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var buff = new ArrayBuffer[String]
    //use this variable to evenly distribute the actors work load
    var batches = 0
    var count = 0
    //val processor = new RecordProcessor
    logger.debug("Initialised actors...")
    file.foreachLine(line => {
        count += 1
        if(startUuid.isEmpty || startUuid.get == line) {
          buff += line
        }

        if(buff.size >= 50){
          val actor = pool(batches % threads).asInstanceOf[Consumer]
          batches += 1
          count+=1
          //find a ready actor...
          while(!actor.ready){ Thread.sleep(50) }

          actor ! buff.toArray
          buff.clear
        }

      if (count % 1000 == 0) {
        finishTime = System.currentTimeMillis
        logger.info(count
          + " >> Last key : " + line
          + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f)
          + ", time taken for " + 1000 + " records: " + (finishTime - startTime).toFloat / 1000f
          + ", total time: " + (finishTime - start).toFloat / 60000f + " minutes"
        )
      }
    })

    //add the remaining records from the buff
    if(!buff.isEmpty){
      pool(0).asInstanceOf[Consumer] ! buff.toArray
      batches += 1
    }

    logger.info(count
      + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f)
      + ", time taken for "+ 1000 + " records: " + (finishTime - startTime).toFloat / 1000f
      + ", total time: "+ (finishTime - start).toFloat / 60000f + " minutes"
    )
    logger.info("Finished.")

    //kill the actors
    pool.foreach(actor => actor ! "exit")

    //We can't shutdown the persistence manager until all of the Actors have completed their work
    while(batches > getProcessedTotal(pool)){
      logger.info(batches + " : " + getProcessedTotal(pool))
      Thread.sleep(50)
    }
  }
  
  def performPaging(proc: (Option[(FullRecord, FullRecord)] => Boolean), startKey:String="", endKey:String="", pageSize: Int = 1000){
    occurrenceDAO.pageOverRawProcessed(rawAndProcessed => {
        proc(rawAndProcessed)
    }, startKey, endKey)
  }

  /**
   * Process the records using the supplied number of threads
   */
  def processRecords(threads: Int, firstKey:Option[String], dr: Option[String], checkDeleted:Boolean=false,
                     callback:ObserverCallback = null, lastKey:Option[String]=None): Unit = {

    val endUuid = if (lastKey.isDefined) lastKey.get else if(dr.isEmpty) "" else dr.get +"|~"

    val startUuid = {
	    if(firstKey.isEmpty && !dr.isEmpty) {
	        dr.get +"|"
	    } else {
	       firstKey.getOrElse("")
	    }
    }
    var ids = 0
    val pool = Array.fill(threads){ val p = new Consumer(Actor.self,ids); ids +=1; p.start }
    
    logger.info("Starting with " + startUuid +" ending with " + endUuid)
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis

    logger.debug("Initialised actors...")

    var count = 0
    var guid = "";
    //use this variable to evenly distribute the actors work load
    var batches = 0

    var buff = new ArrayBuffer[(FullRecord,FullRecord)]

    //occurrenceDAO.pageOverAll(Raw, fullRecord => {
    //occurrenceDAO.pageOverRawProcessed(rawAndProcessed => {
    performPaging(rawAndProcessed => {
      if(guid == "") logger.info("First rowKey processed: " + rawAndProcessed.get._1.rowKey)
      guid = rawAndProcessed.get._1.rowKey
      count += 1

      //we want to add the record to the buffer whether or not we send them to the actor
      //add it to the buffer isnt a deleted record
      if (!rawAndProcessed.isEmpty && !rawAndProcessed.get._1.deleted){
        buff += rawAndProcessed.get
      }

      if(buff.size>=50){
        val actor = pool(batches % threads).asInstanceOf[Consumer]
        batches += 1
        //find a ready actor...
        while(!actor.ready){ Thread.sleep(50) }
        actor ! buff.toArray
        buff.clear
      }

      if(callback != null && count % 100 == 0) {
        callback.progressMessage(count)
      }

      //debug counter
      if (count % 1000 == 0) {
        finishTime = System.currentTimeMillis
        logger.info(count
            + " >> Last key : " + rawAndProcessed.get._1.rowKey
            + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f)
            + ", time taken for "+1000+" records: " + (finishTime - startTime).toFloat / 1000f
            + ", total time: "+ (finishTime - start).toFloat / 60000f +" minutes"
        )
        startTime = System.currentTimeMillis
      }
      true //indicate to continue
    }, startUuid, endUuid)
    
    logger.info("Last row key processed: " + guid)
    //add the remaining records from the buff
    if(buff.nonEmpty){
      pool(0).asInstanceOf[Consumer] ! buff.toArray
      batches+=1
    }
    logger.info("Finished.")
    //kill the actors
    pool.foreach(actor => actor ! "exit")

    //We can't shutdown the persistence manager until all of the Actors have completed their work
    while(batches > getProcessedTotal(pool)){
      Thread.sleep(50)
    }
  }
}

/**
 * A consumer actor asks for new work.
 */
class Consumer (master:Actor,val id:Int)  extends Actor  {

  val logger = LoggerFactory.getLogger("Consumer")

  logger.debug("Initialising thread: " + id)
  val processor = new RecordProcessor
  val occurrenceDAO = Config.occurrenceDAO
  var received, processedRecords = 0

  def ready = processedRecords == received

  def act {
    logger.info("In thread: "+id)
    loop{
      react {
        case rawAndProcessed :(FullRecord,FullRecord) => {
          val (raw, processed) = rawAndProcessed
          received += 1
          processor.processRecord(raw, processed)
          processedRecords += 1
        }
        case batch:Array[(FullRecord,FullRecord)] => {
          received += 1
          //for((raw,processed) <- batch) { processor.processRecord(raw, processed) }
          batch.foreach({ case (raw, processed) =>
            var retries = 0
            var processedOK = false
            while(!processedOK && retries<6){
              try {
                processor.processRecord(raw, processed)
                processedOK = true
              } catch {
                case e:Exception => {
                  logger.error("Error processing record: '"+raw.rowKey+"',  sleeping for 20 secs before retries", e)
                  Thread.sleep(20000)
                  retries += 1
                }
              }
            }
          })
          processedRecords += 1
        }
        case keys:Array[String]=>{
          //get the raw and Processed records for the row key
          received +=1
          val start = System.currentTimeMillis
          var counter:Float=0
          for(key <- keys){
            counter +=1
            val records = occurrenceDAO.getRawProcessedByRowKey(key)
            if(!records.isEmpty){
                processor.processRecord(records.get(0), records.get(1))
            }
          }
          val finished = System.currentTimeMillis
          logger.info("Actor "+id +">>> Last Key: "+keys.last+", records per sec: " + counter / (((finished - start).toFloat) / 1000f))
          processedRecords+=1
        }
        case s:String => {
          if(s == "exit"){
            logger.debug("Killing (Actor.act) thread: "+id)
            exit()
          }
        }
      }
    }
  }
}