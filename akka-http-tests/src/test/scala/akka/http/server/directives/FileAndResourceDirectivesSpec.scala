/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import java.io.{ File, FileOutputStream }

import akka.http.model.MediaTypes._
import akka.http.model._
import akka.http.model.headers._
import akka.http.util._
import org.scalatest.matchers.Matcher
import org.scalatest.{ Inside, Inspectors }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Properties

class FileAndResourceDirectivesSpec extends RoutingSpec with Inspectors with Inside {

  override def testConfigSource =
    """akka.http.routing {
      |  file-chunking-threshold-size = 16
      |  file-chunking-chunk-size = 8
      |  range-coalescing-threshold = 1
      |}""".stripMargin

  "getFromFile" should {
    "reject non-GET requests" in {
      Put() ~> getFromFile("some") ~> check { handled shouldEqual (false) }
    }
    "reject requests to non-existing files" in {
      Get() ~> getFromFile("nonExistentFile") ~> check { handled shouldEqual (false) }
    }
    "reject requests to directories" in {
      Get() ~> getFromFile(Properties.javaHome) ~> check { handled shouldEqual (false) }
    }
    "return the file content with the MediaType matching the file extension" in {
      val file = File.createTempFile("akkaHttpTest", ".PDF")
      try {
        writeAllText("This is PDF", file)
        Get() ~> getFromFile(file.getPath) ~> check {
          mediaType shouldEqual `application/pdf`
          definedCharset shouldEqual None
          responseAs[String] shouldEqual "This is PDF"
          headers should contain(`Last-Modified`(DateTime(file.lastModified)))
        }
      } finally file.delete
    }
    "return the file content with MediaType 'application/octet-stream' on unknown file extensions" in {
      val file = File.createTempFile("akkaHttpTest", null)
      try {
        writeAllText("Some content", file)
        Get() ~> getFromFile(file) ~> check {
          mediaType shouldEqual `application/octet-stream`
          responseAs[String] shouldEqual "Some content"
        }
      } finally file.delete
    }

    "return a single range from a file" in {
      val file = File.createTempFile("partialTest", null)
      try {
        writeAllText("ABCDEFGHIJKLMNOPQRSTUVWXYZ", file)
        Get() ~> addHeader(Range(ByteRange(0, 10))) ~> getFromFile(file) ~> check {
          status shouldEqual StatusCodes.PartialContent
          headers should contain(`Content-Range`(ContentRange(0, 10, 26)))
          responseAs[String] shouldEqual "ABCDEFGHIJK"
        }
      } finally file.delete
    }

    "return multiple ranges from a file at once" in {
      val file = File.createTempFile("partialTest", null)
      try {
        writeAllText("ABCDEFGHIJKLMNOPQRSTUVWXYZ", file)
        val rangeHeader = Range(ByteRange(1, 10), ByteRange.suffix(10))
        Get() ~> addHeader(rangeHeader) ~> getFromFile(file, ContentTypes.`text/plain`) ~> check {
          status shouldEqual StatusCodes.PartialContent
          header[`Content-Range`] shouldEqual None
          mediaType.withParams(Map.empty) shouldEqual `multipart/byteranges`

          val parts = responseAs[Multipart.ByteRanges].toStrict(1.second).awaitResult(3.seconds).strictParts
          parts.size shouldEqual 2
          parts(0).entity.data.utf8String shouldEqual "BCDEFGHIJK"
          parts(1).entity.data.utf8String shouldEqual "QRSTUVWXYZ"
        }
      } finally file.delete
    }
  }

  "getFromResource" should {
    "reject non-GET requests" in {
      Put() ~> getFromResource("some") ~> check { handled shouldEqual (false) }
    }
    "reject requests to non-existing resources" in {
      Get() ~> getFromResource("nonExistingResource") ~> check { handled shouldEqual (false) }
    }
    "return the resource content with the MediaType matching the file extension" in {
      val route = getFromResource("sample.html")

      def runCheck() =
        Get() ~> route ~> check {
          mediaType shouldEqual `text/html`
          forAtLeast(1, headers) { h ⇒
            inside(h) {
              case `Last-Modified`(dt) ⇒
                DateTime(2011, 7, 1) should be < dt
                dt.clicks should be < System.currentTimeMillis()
            }
          }
          responseAs[String] shouldEqual "<p>Lorem ipsum!</p>"
        }

      runCheck()
      runCheck() // additional test to check that no internal state is kept
    }
    "return the file content with MediaType 'application/octet-stream' on unknown file extensions" in {
      Get() ~> getFromResource("sample.xyz") ~> check {
        mediaType shouldEqual `application/octet-stream`
        responseAs[String] shouldEqual "XyZ"
      }
    }
  }

  "getFromResourceDirectory" should {
    "reject requests to non-existing resources" in {
      Get("not/found") ~> getFromResourceDirectory("subDirectory") ~> check { handled shouldEqual (false) }
    }
    val verify = check {
      mediaType shouldEqual `application/pdf`
      responseAs[String] shouldEqual "123"
    }
    "return the resource content with the MediaType matching the file extension - example 1" in { Get("empty.pdf") ~> getFromResourceDirectory("subDirectory") ~> verify }
    "return the resource content with the MediaType matching the file extension - example 2" in { Get("empty.pdf") ~> getFromResourceDirectory("subDirectory/") ~> verify }
    "return the resource content with the MediaType matching the file extension - example 3" in { Get("subDirectory/empty.pdf") ~> getFromResourceDirectory("") ~> verify }
    "reject requests to directory resources" in {
      Get() ~> getFromResourceDirectory("subDirectory") ~> check { handled shouldEqual (false) }
    }
  }

  "listDirectoryContents" should {
    val base = new File(getClass.getClassLoader.getResource("").toURI).getPath
    new File(base, "subDirectory/emptySub").mkdir()
    def eraseDateTime(s: String) = s.replaceAll("""\d\d\d\d-\d\d-\d\d \d\d:\d\d:\d\d""", "xxxx-xx-xx xx:xx:xx")
    implicit val settings = RoutingSettings.default.copy(renderVanityFooter = false)

    "properly render a simple directory" in {
      Get() ~> listDirectoryContents(base + "/someDir") ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /</title></head>
            |<body>
            |<h1>Index of /</h1>
            |<hr>
            |<pre>
            |<a href="/sub/">sub/</a>             xxxx-xx-xx xx:xx:xx
            |<a href="/fileA.txt">fileA.txt</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/fileB.xml">fileB.xml</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render a sub directory" in {
      Get("/sub/") ~> listDirectoryContents(base + "/someDir") ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /sub/</title></head>
            |<body>
            |<h1>Index of /sub/</h1>
            |<hr>
            |<pre>
            |<a href="/">../</a>
            |<a href="/sub/file.html">file.html</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render the union of several directories" in {
      Get() ~> listDirectoryContents(base + "/someDir", base + "/subDirectory") ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /</title></head>
            |<body>
            |<h1>Index of /</h1>
            |<hr>
            |<pre>
            |<a href="/emptySub/">emptySub/</a>        xxxx-xx-xx xx:xx:xx
            |<a href="/sub/">sub/</a>             xxxx-xx-xx xx:xx:xx
            |<a href="/empty.pdf">empty.pdf</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/fileA.txt">fileA.txt</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/fileB.xml">fileB.xml</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render an empty sub directory with vanity footer" in {
      val settings = 0 // shadow implicit
      Get("/emptySub/") ~> listDirectoryContents(base + "/subDirectory") ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /emptySub/</title></head>
            |<body>
            |<h1>Index of /emptySub/</h1>
            |<hr>
            |<pre>
            |<a href="/">../</a>
            |</pre>
            |<hr>
            |<div style="width:100%;text-align:right;color:gray">
            |<small>rendered by <a href="http://akka.io">Akka Http</a> on xxxx-xx-xx xx:xx:xx</small>
            |</div>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render an empty top-level directory" in {
      Get() ~> listDirectoryContents(base + "/subDirectory/emptySub") ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /</title></head>
            |<body>
            |<h1>Index of /</h1>
            |<hr>
            |<pre>
            |(no files)
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render a simple directory with a path prefix" in {
      Get("/files/") ~> pathPrefix("files")(listDirectoryContents(base + "/someDir")) ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /files/</title></head>
            |<body>
            |<h1>Index of /files/</h1>
            |<hr>
            |<pre>
            |<a href="/files/sub/">sub/</a>             xxxx-xx-xx xx:xx:xx
            |<a href="/files/fileA.txt">fileA.txt</a>        xxxx-xx-xx xx:xx:xx            3  B
            |<a href="/files/fileB.xml">fileB.xml</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render a sub directory with a path prefix" in {
      Get("/files/sub/") ~> pathPrefix("files")(listDirectoryContents(base + "/someDir")) ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /files/sub/</title></head>
            |<body>
            |<h1>Index of /files/sub/</h1>
            |<hr>
            |<pre>
            |<a href="/files/">../</a>
            |<a href="/files/sub/file.html">file.html</a>        xxxx-xx-xx xx:xx:xx            0  B
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "properly render an empty top-level directory with a path prefix" in {
      Get("/files/") ~> pathPrefix("files")(listDirectoryContents(base + "/subDirectory/emptySub")) ~> check {
        eraseDateTime(responseAs[String]) shouldEqual prep {
          """<html>
            |<head><title>Index of /files/</title></head>
            |<body>
            |<h1>Index of /files/</h1>
            |<hr>
            |<pre>
            |(no files)
            |</pre>
            |<hr>
            |</body>
            |</html>
            |"""
        }
      }
    }
    "reject requests to file resources" in {
      Get() ~> listDirectoryContents(base + "subDirectory/empty.pdf") ~> check { handled shouldEqual (false) }
    }
  }

  def prep(s: String) = s.stripMarginWithNewline("\n")

  def writeAllText(text: String, file: File): Unit = {
    val fos = new FileOutputStream(file)
    try {
      fos.write(text.getBytes("UTF-8"))
    } finally fos.close()
  }

  def evaluateTo[T](t: T, atMost: Duration = 100.millis)(implicit ec: ExecutionContext): Matcher[Future[T]] =
    be(t).compose[Future[T]] { fut ⇒
      import scala.concurrent.Await
      fut.awaitResult(atMost)
    }
}
