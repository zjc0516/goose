/**
 * Licensed to Gravity.com under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Gravity.com licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gravity.goose

import akka.actor.Actor
import cleaners.{StandardDocumentCleaner, DocumentCleaner}
import extractors.{StandardContentExtractor, ContentExtractor}
import images.{UpgradedImageIExtractor, StandardImageExtractor, ImageExtractor}
import network.HtmlFetcher
import outputformatters.{StandardOutputFormatter, OutputFormatter}
import org.apache.http.client.HttpClient
import org.jsoup.nodes.{Document, Element}
import org.jsoup.Jsoup
import java.io.File
import utils.{ParsingCandidate, URLHelper, Logging}

/**
 * Created by Jim Plush
 * User: jim
 * Date: 8/18/11
 */

case class CrawlCandidate(config: Configuration, url: String, rawHTML: String = null)

class CrawlingActor extends Actor with Logging {

  val logPrefix = "crawler: "

  var config: Configuration = null

  def receive = {
    case cc: CrawlCandidate => {
      config = cc.config
      crawl(cc)
    }
    case _ => throw new Exception("unknown message sent to actor")
  }


  def crawl(crawlCandidate: CrawlCandidate) = {
    val article = new Article()
    for {
      parseCandidate <- URLHelper.getCleanedUrl(crawlCandidate.url)
      rawHtml <- getHTML(crawlCandidate, parseCandidate)
      doc <- getDocument(parseCandidate.url.toString, rawHtml)
    } {

      trace("Crawling url: %s".format(parseCandidate.url))

      val extractor = getExtractor
      val docCleaner = getDocCleaner
      val outputFormatter = getOutputFormatter

      article.finalUrl = parseCandidate.url.toString
      article.linkhash = parseCandidate.linkhash
      article.rawHtml = rawHtml
      article.doc = doc
      article.rawDoc = doc.clone()

      article.title = extractor.getTitle(article)
      article.publishDate = config.publishDateExtractor.extract(doc)
      article.additionalData = config.getAdditionalDataExtractor.extract(doc)
      article.metaDescription = extractor.getMetaDescription(article)
      article.metaKeywords = extractor.getMetaKeywords(article)
      article.canonicalLink = extractor.getCanonicalLink(article)
      article.domain = extractor.getDomain(article.finalUrl)
      article.tags = extractor.extractTags(article)

      // before we do any calcs on the body itself let's clean up the document
      article.doc = docCleaner.clean(article)

      extractor.calculateBestNodeBasedOnClustering(article) match {
        case Some(node: Element) => {
          article.topNode = node
          article.movies = extractor.extractVideos(article.topNode)

          if (config.enableImageFetching) {
            trace(logPrefix + "Image fetching enabled...")
            val imageExtractor = getImageExtractor(article)
            try {
              article.topImage = imageExtractor.getBestImage(article.rawDoc, article.topNode)
            } catch {
              case e: Exception => {
                warn(e, e.toString)
              }
            }
          }
          article.topNode = extractor.postExtractionCleanup(article.topNode)

          article.cleanedArticleText = outputFormatter.getFormattedText(article.topNode)
        }
        case _ => trace("NO ARTICLE FOUND");
      }
      releaseResources(article)
      self.reply(article)
    }
  }

  def getHTML(crawlCandidate: CrawlCandidate, parsingCandidate: ParsingCandidate): Option[String] = {
    if (crawlCandidate.rawHTML != null) {
      Some(crawlCandidate.rawHTML)
    } else {
      HtmlFetcher.getHtml(parsingCandidate.url.toString) match {
        case Some(html) => Some(html)
        case _ => None
      }
    }
  }


  def getImageExtractor(article: Article): ImageExtractor = {
    val httpClient: HttpClient = HtmlFetcher.getHttpClient
    new UpgradedImageIExtractor(httpClient, article, config)
  }

  def getOutputFormatter: OutputFormatter = {
    new StandardOutputFormatter
  }

  def getDocCleaner: DocumentCleaner = {
    new StandardDocumentCleaner
  }

  def getDocument(url: String, rawlHtml: String): Option[Document] = {

    try {
      Some(Jsoup.parse(rawlHtml))
    } catch {
      case e: Exception => {
        trace("Unable to parse %s properly into JSoup Doc".format(url))
        None
      }
    }
  }

  def getExtractor: ContentExtractor = {
    new StandardContentExtractor
  }

  /**
  * cleans up any temp files we have laying around like temp images
  * removes any image in the temp dir that starts with the linkhash of the url we just parsed
  */
  def releaseResources(article: Article) = {
    trace(logPrefix + "STARTING TO RELEASE ALL RESOURCES")

    val dir: File = new File(config.localStoragePath)

    dir.list.foreach(filename => {
      if (filename.startsWith(article.linkhash)) {
        val f: File = new File(dir.getAbsolutePath + "/" + filename)
        if (!f.delete) {
          warn("Unable to remove temp file: " + filename)
        }
      }
    })
  }

}