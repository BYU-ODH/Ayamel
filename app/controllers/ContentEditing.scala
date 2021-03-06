package controllers

import play.api.mvc._
import controllers.authentication.Authentication
import play.api.libs.json.{JsString, JsArray, Json, JsDefined}
import dataAccess.ResourceController
import models.{User, Collection, Content}
import service._
import java.net.URL
import java.io.IOException
import javax.imageio.ImageIO
import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import play.api.Logger
import scala.Some
import scala.util.matching.Regex

/**
 * Controller that deals with the editing of content
 */
trait ContentEditing {
  this: Controller =>

  /**
   * Sets the metadata for a particular content object
   * @param id The ID of the content
   */
  def setMetadata(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) {  content =>
          // Make sure the user is able to edit
          if (content isEditableBy user) {
            // Get the info from the form
            val title = request.body("title")(0)
            val description = request.body("description")(0)
            val categories = request.body.get("categories").map(_.toList).getOrElse(Nil)
            val keywords = request.body.get("keywords").map(_.toList).getOrElse(Nil).mkString(",")
            val languages = request.body.get("languages").map(_.toList).getOrElse(List("eng"))

            // Update the name of the content
            content.copy(name = title).save

            // Validate description
            val validated = if (description.length > 5000) {
              description.substring(0,5000)
            } else {
              description
            }

            // Create the JSON object
            val obj = Json.obj(
              "title" -> title,
              "description" -> validated,
              "keywords" -> keywords,
              "categories" -> categories,
              "languages" -> Json.obj(
                "iso639_3" -> languages
              )
            )

            val redirect = Redirect(routes.ContentController.view(id))

            // Save the metadata
            ResourceController.updateResource(content.resourceId, obj).map { _ =>
              redirect.flashing("success" -> "Metadata updated.")
            }.recover { case _ =>
              redirect.flashing("error" -> "Oops! Something went wrong.")
            }

          } else
            Future(Errors.forbidden)
        }
  }

  /**
   * Helper function for setSettings
   * @param content The content whose settings are being set
   */
  def recordSettings(content: Content, data: Map[String, Seq[String]]) {
    content.setSetting("captionTrack", data.get("captionTracks").getOrElse(Nil))
    content.setSetting("annotationDocument", data.get("annotationDocs").getOrElse(Nil))
    content.setSetting("targetLanguages", data.get("targetLanguages").getOrElse(Nil))

    content.setSetting("aspectRatio", List(data.get("aspectRatio").map(_(0)).getOrElse("1.7778")))

    content.setSetting("showCaptions", List(data.get("showCaptions").map(_(0)).getOrElse("false")))
    content.setSetting("showAnnotations", List(data.get("showAnnotations").map(_(0)).getOrElse("false")))
    content.setSetting("allowDefinitions", List(data.get("allowDefinitions").map(_(0)).getOrElse("false")))
    content.setSetting("showTranscripts", List(data.get("showTranscripts").map(_(0)).getOrElse("false")))
    content.setSetting("showWordList", List(data.get("showWordList").map(_(0)).getOrElse("false")))
  }

  /**
   * Sets the content's settings
   * @param id The ID of the content
   */
  def setSettings(id: Long) = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          val data = request.body.dataParts
          Future {
            // Make sure the user is able to edit
            if (content isEditableBy user) {
              recordSettings(content, data)
              Ok
            } else
              Errors.forbidden
          }
        }
  }

  /**
   * Image editing view
   * @param id The ID of the content
   */
  def editImage(id: Long) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) {  content =>
          Future {
            if (content.isEditableBy(user) && content.contentType == 'image) {
              val collection = AdditionalDocumentAdder.getCollection()
              Ok(views.html.content.editImage(content, ResourceController.baseUrl, collection))
            } else
              Errors.forbidden
          }
        }
  }

  /**
   * Saves the image edits.
   * @param id The id of the content
   */
  def saveImageEdits(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          if ((content isEditableBy user) && (content.contentType == 'image)) {

            // Get the rotation and crop info
            val rotation = request.body("rotation")(0).toInt
            val cropTop = request.body("cropTop")(0).toDouble
            val cropLeft = request.body("cropLeft")(0).toDouble
            val cropBottom = request.body("cropBottom")(0).toDouble
            val cropRight = request.body("cropRight")(0).toDouble
            val redirect = Redirect(AdditionalDocumentAdder.getCollection() match {
              case Some(collection) => routes.CollectionContent.viewInCollection(content.id.get, collection.id.get)
              case _ => routes.ContentController.view(content.id.get)
            })

            // Load the image
            ImageTools.loadImageFromContent(content).flatMap { image =>
              // Make the changes to the image
              val newImage = ImageTools.crop(
                if (rotation > 0) ImageTools.rotate(image, rotation) else image,
                cropTop, cropLeft, cropBottom, cropRight
              )

              // Save the new image
              FileUploader.uploadImage(newImage, FileUploader.uniqueFilename(content.resourceId + ".jpg")).flatMap { url =>
                // Update the resource
                ResourceHelper.updateFileUri(content.resourceId, url)
                  .map { _ =>
                    redirect.flashing("info" -> "Image updated")
                  }.recover { case _ =>
                    redirect.flashing("error" -> "Failed to update image")
                  }
              }.recover { case _ =>
                redirect.flashing("error" -> "Failed to update image")
              }
            }.recover { case _ =>
              redirect.flashing("error" -> "Couldn't load image")
            }
          } else
            Future(Errors.forbidden)
        }
  }

  /**
   * Sets the thumbnail for content from either a URL or a file
   * @param id The ID of the content that the thumbnail will be for
   */
  def changeThumbnail(id: Long) = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>

          val file = request.body.file("file")
          val url = request.body.dataParts("url")(0)
          val redirect = Redirect(routes.ContentController.view(id))

          try {
            (if (url.isEmpty) {
              file.map { filepart =>
                ImageTools.generateThumbnail(filepart.ref.file)
              }
            } else {
              Some(ImageTools.generateThumbnail(url))
            }) match {
              case Some(fut) =>
                fut.map { url =>
                  content.copy(thumbnail = url).save
                  redirect.flashing("info" -> "Thumbnail changed")
                }.recover { case _ =>
                  redirect.flashing("error" -> "Unknown error while attempting to create thumbnail")
                }
              case None => Future(redirect.flashing("error" -> "No file provided"))
            }
          } catch {
            case _: IOException =>
              Future(redirect.flashing("error" -> "Error reading image file"))
          }
        }
  }

  /**
   * Creates a thumbnail for content from a particular point in time in a video
   * @param id The ID of the content
   * @param time The time in the video which will be used as the thumbnail
   */
  def createThumbnail(id: Long, time: Double) = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          val redirect = Redirect(routes.ContentController.view(id))

          // Get the video resource from the content
          ResourceController.getResource(content.resourceId).flatMap { json =>
            // Get the video file
            (json \ "resource" \ "content" \ "files") match {
              case arr:JsDefined =>
                arr.as[JsArray].value.find { file =>
                  (file \ "mime") match {
                    case str:JsDefined => str.as[JsString].value.startsWith("video")
                    case _ => false
                  }
                }.map[Future[Result]] { videoObject =>
                  //Check to see if it's downloadable
                  (videoObject \ "downloadUri") match {
                    case videoUrl:JsDefined =>
                    /*
                    The "-protocols" command will list your version of ffmpeg
                    List of supported Protocols:
                        applehttp, concat, crypto, file, gopher, http, httpproxy
                        mmsh, mmst, pipe, rtmp, rtp, tcp, udp
                    The default protocol is "file:" and you do not need to specify it in ffmpeg,
                    so we can't check to see if we are using a supported protocol. However,
                    we do know that "https:" is unsupported, so if we get one, try to convert it
                    to "http:". If it doesn't work, we'll just get a message that the thumbnail
                    could not be generated.
                    */
                     // val url = if (videoUrl.as[JsString].value.startsWith("https://"))
                     //        JsString(videoUrl.value.replaceFirst("https://","http://"))
                     //    else
                     //        videoUrl

                      // Generate the thumbnail for that video
                      VideoTools.generateThumbnail(videoUrl.as[JsString].value, time)
                        .map { url =>
                          // Save it and be done
                          content.copy(thumbnail = url).save
                          redirect.flashing("info" -> "Thumbnail updated")
                        }.recover { case e: Exception =>
                          redirect.flashing("error" -> e.getMessage())
                        }
                    case _ => Future {
                      (videoObject \ "mime") match {
                        case videoType:JsDefined =>
                          if (videoType.as[JsString].value == "video/x-youtube") {
                            val youtubeRegex = new Regex("(?<=watch\\?v=|/videos/|embed\\/|youtu.be\\/|\\/v\\/|watch\\?v%3D|%2Fvideos%2F|embed%2F|youtu.be%2F|%2Fv%2F)[^#\\&\\?\n]*")
                            (videoObject \ "streamUri") match {
                              case youtubeUri:JsDefined => {
                                val stringUri = youtubeUri.value.toString
                                val videoId = youtubeRegex findFirstIn stringUri
                                // Sometimes an extra character gets added on to the video ID so slice off everything but the first 11 chars
                                val thumbnailUrl = "https://img.youtube.com/vi/" + videoId.get.toString.slice(0, 11) + "/default.jpg"
                                content.copy(thumbnail = thumbnailUrl.toString).save
                                redirect.flashing("info" -> "Thumbnail updated")
                              }
                              case _ => redirect.flashing("error" -> "Error getting youtube URL")
                            }
                          } else {
                            redirect.flashing("error" -> "Sorry. We can only get youtube thumbnails for now.")
                          }
                        case _ => redirect.flashing("error" -> "Error getting video type")
                      }
                    }
                  }
                }.getOrElse {
                  Future(redirect.flashing("error" -> "No video file found"))
                }
              case _ => Future(redirect.flashing("error" -> "No files found"))
            }
          }.recover { case _ =>
            redirect.flashing("error" -> "Could not access video.")
          }
        }
  }

  /**
   * Sets the downloadUri of the primary resource associated with the content
   * @param id The ID of the content
   */
  def setMediaSource(id: Long) = Authentication.authenticatedAction(parse.urlFormEncoded) {
    implicit request =>
      implicit user =>
        ContentController.getContent(id) { content =>
          if (content isEditableBy user) {
            val url = request.body("url")(0)
            val redirect = Redirect(routes.ContentController.view(id))
            ResourceHelper.updateFileUri(content.resourceId, url).map { _ =>
              redirect.flashing("info" -> "Media source updated")
            }.recover { case _ =>
              redirect.flashing("error" -> "Oops! Something went wrong!")
            }
          } else
            Future(Errors.forbidden)
        }
  }

}

object ContentEditing extends Controller with ContentEditing
