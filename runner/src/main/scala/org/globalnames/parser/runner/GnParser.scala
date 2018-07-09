package org.globalnames
package parser
package runner

import java.io.{BufferedWriter, FileWriter}
import java.util.concurrent.atomic.AtomicInteger

import ScientificNameParser.{instance => scientificNameParser}
import tcp.TcpServer
import web.controllers.WebServer
import resource._
import parser.BuildInfo

import scala.collection.parallel.ForkJoinTaskSupport
import scala.collection.parallel.immutable.ParVector
import scala.concurrent.forkjoin.ForkJoinPool
import scala.io.{Source, StdIn}
import scalaz._
import Scalaz._

import org.json4s.JsonAST.JArray
import org.json4s.jackson.JsonMethods

object GnParser {
  sealed trait Mode
  case object InputFileParsing extends Mode
  case object TcpServerMode extends Mode
  case object WebServerMode extends Mode
  case object NameParsing extends Mode

  sealed trait Format
  case object Format {
    case object Simple extends Format
    case object JsonPretty extends Format
    case object JsonCompact extends Format

    def parse(v: String): Format = v match {
      case "simple" => Format.Simple
      case "json-pretty" => Format.JsonPretty
      case "json-compact" => Format.JsonCompact
      case x => throw new IllegalArgumentException(s"Unexpected value of `format` flag: $x")
    }
  }

  case class Config(mode: Option[Mode] = Some(InputFileParsing),
                    inputFile: Option[String] = None,
                    outputFile: Option[String] = None,
                    host: String = "0.0.0.0",
                    port: Int = 4334,
                    name: String = "",
                    private val format: Format = Format.JsonPretty,
                    private val threadsNumber: Option[Int] = None) {

    val parallelism: Int = threadsNumber.getOrElse(ForkJoinPool.getCommonPoolParallelism)

    def renderResult(result: ResultRendered): String = format match {
      case Format.Simple => result.delimitedStringRenderer.delimitedString()
      case Format.JsonCompact => result.jsonRenderer.renderCompactJson
      case Format.JsonPretty => result.jsonRenderer.render(compact = false)
    }

    def resultsToJson(results: Vector[ResultRendered]): String = format match {
      case Format.Simple =>
        val resultsStrings = for (r <- results) yield r.delimitedString()
        resultsStrings.mkString("\n")
      case f =>
        val resultsJsonArr = for (r <- results.toList) yield r.jsonRenderer.json()
        val resultsJson = JArray(resultsJsonArr)
        val resultString = f match {
          case Format.JsonCompact => JsonMethods.compact(resultsJson)
          case _ => JsonMethods.pretty(resultsJson)
        }
        resultString
    }

  }

  private[runner] val gnParserVersion = BuildInfo.version
  private[runner] val welcomeMessage = "Enter scientific names line by line"

  private val parser = new scopt.OptionParser[Config]("gnparser") {
    head("gnparser", gnParserVersion)
    head("NOTE: if no command is provided then `file` is executed by default")
    help("help").text("prints this usage text")
    opt[String]('f', "format").text("format result: simple CSV, JSON compact or pretty")
               .optional.action { (x, c) => c.copy(format = Format.parse(x)) }
    cmd("name").action { (_, c) => c.copy(mode = NameParsing.some) }
               .text("parse single scientific name").children(
      arg[String]("<scientific_name>").required.action { (x, c) => c.copy(name = x) }
    )
    cmd("file").action { (_, c) => c.copy(mode = InputFileParsing.some) }
               .text("parse scientific names from input file").children(
      opt[String]('i', "input").text("if not present then input from <stdin>")
                               .optional.valueName("<path_to_input_file>")
                               .action { (x, c) => c.copy(inputFile = x.some) },
      opt[String]('o', "output").optional.text("if not present then output to <stdout>")
                                .valueName("<path_to_output_file>")
                                .action { (x, c) => c.copy(outputFile = x.some) },
      opt[Int]('t', "threads").valueName("<threads_number>")
                              .action { (x, c) => c.copy(threadsNumber = x.some)}
    )
    cmd("socket").action { (_, c) => c.copy(mode = TcpServerMode.some) }
                 .text("run socket server for parsing").children(
      opt[Int]('p', "port").valueName("<port>").action { (x, c) => c.copy(port = x)},
      opt[String]('h', "host").valueName("<host>").action { (x, c) => c.copy(host = x) }
    )
    cmd("web").action { (_, c) => c.copy(mode = WebServerMode.some) }
              .text("run web server for parsing").children(
      opt[Int]('p', "port").valueName("<port>").action { (x, c) => c.copy(port = x) },
      opt[String]('h', "host").valueName("<host>").action { (x, c) => c.copy(host = x) }
    )
  }

  protected[runner] def parse(args: Array[String]): Option[Config] =
    parser.parse(args, Config())

  def main(args: Array[String]): Unit = parse(args) match {
    case Some(cfg) => cfg.mode.get match {
      case InputFileParsing => startFileParse(cfg)
      case TcpServerMode => TcpServer.run(cfg)
      case WebServerMode => WebServer.run(cfg)
      case NameParsing =>
        val result = scientificNameParser.fromString(cfg.name)
        println(cfg.renderResult(result))
    }
    case None =>
      Console.err.println("Invalid configuration of parameters. Check --help")
  }

  def startFileParse(config: Config): Unit = {
    val inputIteratorEither = config.inputFile match {
      case None =>
        Console.err.println(welcomeMessage)
        val iterator = Iterator.continually(StdIn.readLine())
        iterator.takeWhile { str => str != null && str.trim.nonEmpty }.right
      case Some(fp) =>
        val sourceFile = Source.fromFile(fp)
        val inputLinesManaged = managed(sourceFile).map { _.getLines.toVector }
        \/.fromEither(inputLinesManaged.either.either)
    }

    val namesParsed = inputIteratorEither match {
      case -\/(errors) =>
        Console.err.println(errors.map { _.getMessage }.mkString("\n"))
        ParVector.empty
      case \/-(res) =>
        val namesInputPar = res.toVector.par
        namesInputPar.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(config.parallelism))
        val parsedNamesCount = new AtomicInteger()

        Console.err.println(s"Running with parallelism: ${config.parallelism}")
        for (name <- namesInputPar) yield {
          val currentParsedCount = parsedNamesCount.incrementAndGet()
          if (currentParsedCount % 10000 == 0) {
            Console.err.println(s"Parsed $currentParsedCount of ${namesInputPar.size} lines")
          }
          val result = scientificNameParser.fromString(name.trim)
          result
        }
    }

    val resultsJsonStr = config.resultsToJson(namesParsed.seq)

    config.outputFile match {
      case Some(fp) =>
        for { writer <- managed(new BufferedWriter(new FileWriter(fp))) } {
          writer.write(resultsJsonStr)
        }
      case None => println(resultsJsonStr)
    }
  }
}
