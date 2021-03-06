package au.org.ala.biocache.cmd

import au.org.ala.biocache.Config
import au.org.ala.biocache.index.{IndexMergeTool, BulkProcessor, OptimiseIndex, IndexRecords}
import au.org.ala.biocache.load._
import au.org.ala.biocache.tool._
import au.org.ala.biocache.outliers.ReverseJacknifeProcessor
import au.org.ala.biocache.util.ImageExport
import au.org.ala.biocache.export._
import scala.Some
import au.org.ala.biocache.qa.ValidationRuleRunner
import org.drools.core.factmodel.traits.Trait
import scala.collection.JavaConversions

/**
 * A trait to be added to any runnable application within the biocache-store library.
 * Tools are executable standalone or through CMD2.scala
 */
trait Tool {
  def cmd : String
  def desc : String
  def help  = cmd
  def main(args:Array[String]) : Unit
}

trait NoArgsTool extends Tool {

  /**
   * Checks for arguments. If supplied return help.
   * @param args
   */
  def proceed(args:Array[String], proc:() => Unit){
    if(!args.isEmpty){
      println("Usage: " + help)
      println("\tNo arguments required for tool")
    } else {
      proc()
    }
  }
}

object ShowConfig extends NoArgsTool {

  def cmd = "config"
  def desc = "Output the current config for this instance of biocache CLI"
  def main(args:Array[String]) = {
    proceed(args, () => Config.outputConfig  )
  }
}

object ShowVersion extends NoArgsTool {

  def cmd = "version"
  def desc = "Show build version information"
  def main(args:Array[String]) = {
    import JavaConversions._
    val propertyNames = Config.versionProperties.stringPropertyNames().toList
    propertyNames.foreach(name => {
      println(name + " = " + Config.versionProperties.getProperty(name))
    })
  }
}

object CMD2 {

  def main(args: Array[String]) {

    import JavaConversions._

    if(args.contains("--version") || args.contains("-version")){
      ShowVersion.main(Array[String]())
      return
    }

    if (args.isEmpty) {

      val versionInfo =  if(!Config.versionProperties.isEmpty) {
        "Commit ID: " + Config.versionProperties.getProperty("git.commit.id") +
          "\n Build date: " +  Config.versionProperties.getProperty("git.build.time") +
          "\n Revision date: " +  Config.versionProperties.getProperty("git.commit.time") +
          "\n For more detail run with --version or type 'version' at the prompt"
      } else {
        "Version information not available"
      }

      println("-" * 80)
      println(" Biocache management tool ")
      println(" " + versionInfo + " ")
      println("-" * 80)
      print("\nPlease supply a command or hit ENTER to view command list. \nbiocache> ")

      var input = readLine
      while (input != "exit" && input != "q" && input != "quit") {
        val myArgs = org.apache.tools.ant.types.Commandline.translateCommandline(input)
        if(!myArgs.isEmpty){
          findTool(myArgs.head) match {
            case Some(tool) => try {
              tool.main(myArgs.tail)
            } catch {
              case e:Exception => {
                e.printStackTrace()
                print("\nbiocache> ")
              }
            }
            case None => {
              printTools
              print("\nbiocache> ")
            }
          }
        } else {
          printTools
        }
        print("\nbiocache> ")
        input = readLine
      }
    } else {

      if(args(0).toLowerCase == "--help"){
        printTools
      } else if(args(0).toLowerCase == "--full-help"){
        tools.foreach { tool =>
          println( "-" * 80)
          println("Command: " + tool.cmd)
          println("Description: " + tool.desc)
          tool.main(Array("--help"))
        }
      } else {
        findTool(args.head) match {
          case Some(tool) => tool.main(args.tail)
          case None => printTools
        }
      }
    }
    //close down the data store and index so the program can exit normally
    Config.persistenceManager.shutdown
    IndexRecords.indexer.shutdown
    System.exit(0)
  }

  def printTools = {
    var idx=1
    tools.foreach{ tool =>
      println( "-" * 80)
      padAndPrint("["+idx+"] " + tool.cmd + " - " + tool.desc )
      idx = idx + 1
    }
  }

  def findTool(str:String) : Option[Tool] = {
    tools.foreach { tool =>
      if(tool.cmd == str) return Some(tool)
    }
    None
  }

  def padAndPrint(str: String) = println(padElementTo25(str))

  def padElementTo25(str: String) = padElement(str, 25)

  def padElement(str: String, width: Int) = str.replace(" - ", Array.fill(width - str.indexOf(" -"))(' ').mkString + " - ")

  def tools = Array(
    CalculatedLayerHelper,
    DataResourceDelete,
    DeleteColumn,
    DeleteRecords,
    DescribeResource,
    DownloadMedia,
    DuplicationDetection,
    DwCACreator,
    DwcCSVLoader,
    ExpertDistributionOutlierTool,
    ExportUtil,
    ExportByFacetQuery,
    ExportFacet,
    ExportForOutliers,
    ExportFromIndex,
    ExportFromIndexStream,
    ExportAllSpatialSpecies,
    GBIFOrgCSVCreator,
    Healthcheck,
    ImageExport,
    ImageExport,
    ImportUtil,
    IndexRecords,
    IngestTool,
    IndexMergeTool,
    ListResources,
    Loader,
    OptimiseIndex,
    ProcessAll,
    ProcessSingleRecord,
    ProcessRecords,
    ReprocessIndexSelect,
    ResampleRecordsByQuery,
    ReloadSampling,
    ResourceCleanupTask,
    Sampling,
    ShowConfig,
    ReverseJacknifeProcessor,
    Thumbnailer,
    BulkProcessor,
    ValidationRuleRunner,
    MigrateMedia,
    LoadMediaReferences,
    CopyDataNewColumn,
    BVPLoader,
    ShowVersion
  ).sortBy(_.cmd)
}