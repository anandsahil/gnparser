package org.globalnames.parser
package formatters

import spray.json._

import formatters.{Summarizer => s}

trait SummarizerProtocol extends DefaultJsonProtocol {

  import DetailsGeneratorProtocol.nameFormat

  implicit val canonicalNameFormat = jsonFormat3(s.CanonicalName)

  implicit val summaryFormat = jsonFormat15(s.Summary)

}

object SummarizerProtocol extends SummarizerProtocol
