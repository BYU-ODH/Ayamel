package controllers

import authentication.Authentication
import authentication.Authentication.result2futureresult
import play.api.mvc._
import models._
import service.{FileUploader, ImageTools, HashTools}
import scala.util.{Try, Success, Failure}
import javax.imageio.ImageIO
import scala.concurrent._
import play.api.libs.json._
import ExecutionContext.Implicits.global

/**
 * Controller dealing with users
 */
trait Users { this: Controller =>

  /**
   * Get the collections that a user belongs to
   * @return Result[json array] of collections
   */
  def getEnrollment = Authentication.secureAPIAction() {
    implicit request =>
      implicit user =>
        Ok(Json.toJson(user.getEnrollment.map(Collection.coll2json)))
  }

  /**
   * Get the collections in which the user is a teacher or a ta
   * Calling this function with an admin does not return all collections
   * @return Result[json array] of collections
   */
  def getCollectionsForTeacherOrTA = Authentication.secureAPIAction() {
    implicit request =>
      implicit user =>
        Ok(Json.toJson(user.getEnrollment.filter(_.userIsAdmin(user)).map(coll =>
          Json.obj(
            "name" -> coll.name,
            "thumbnail" -> Json.toJson(coll.getContent.map(_.thumbnail).find(_.nonEmpty).getOrElse("")),
            "id" -> coll.id.get,
            "published" -> coll.published,
            "archived" -> coll.archived,
            "owner" -> coll.owner,
            "role" -> (if (coll.userIsTA(user)) "ta" else "teacher"),
            "content" -> coll.getContent.map(cont =>
                Json.obj(
                  "id" -> cont.id,
                  "name" -> cont.name,
                  "contentType" -> cont.contentType.toString,
                  "thumbnail" -> cont.thumbnail,
                  "views" -> cont.views))))))
  }

  def getAsJson = Authentication.secureAPIAction() {
    implicit request =>
      implicit user =>
        Ok(user.toJson)
  }

  /**
   * Get the user's most recently viewed contents
   * @return Result[json array] of content with id, name, thumbnail, and collection name
   */
  def recentContent = Authentication.secureAPIAction() {
    implicit request =>
      implicit user =>
        Ok(Json.toJson(ViewingHistory.getUserViews(user.id.get).foldLeft(List[ViewingHistory]()){
          // remove duplicates
          (a, b) => if (a.exists(_.contentId == b.contentId)) a else b :: a
        }.map { recentContent =>
          Content.findById(recentContent.contentId).map { content =>
            Json.obj(
              "id" -> recentContent.contentId,
              "name" -> content.name,
              "thumbnail" -> content.thumbnail,
              "collection" -> Collection.findById(content.collectionId).get.name)
          }.getOrElse(JsNull)
        }.filter(_ match {
          case JsNull => false
          case _ => true
        })))
  }

  /**
   * The account settings view
   */
  def accountSettings = Authentication.authenticatedAction() {
    implicit request =>
      implicit user =>
        Future(Ok(views.html.users.accountSettings()))
  }

  /**
   * Saves the user account information
   */
  def saveSettings = Authentication.authenticatedAction(parse.json) {
    implicit request =>
      implicit user =>

        // Change the user information
        val name = (request.body \ "name").as[String]
        val email = (request.body \ "email").as[String]
        user.copy(name = Some(name), email = Some(email)).save

        Future {
          Redirect(routes.Users.accountSettings())
            .flashing("info" -> "Personal information updated.")
        }
  }

  /**
   * Updates the user's profile picture
   */
  def uploadProfilePicture = Authentication.authenticatedAction(parse.multipartFormData) {
    implicit request =>
      implicit user =>
        val redirect = Redirect(routes.Users.accountSettings())

        // Load the image from the file and make it into a thumbnail
        request.body.file("file") match {
        case None =>
          Future(redirect.flashing("error" -> "Missing File"))
        case Some(picture) =>
          Try(Option(ImageIO.read(picture.ref.file))) match {
          case Failure(_) =>
            Future(redirect.flashing("error" -> "Could not read image"))
          case Success(imgOpt) =>
            imgOpt match {
            case None =>
              Future(redirect.flashing("error" -> "Error reading image."))
            case Some(image) =>
              ImageTools.makeThumbnail(image) match {
              case None =>
                Future(redirect.flashing("error" -> "Error processing image."))
              case Some(thmb) =>
                // Upload the file
                FileUploader.uploadImage(thmb, picture.filename).map { url =>
                  // Save the user info about the profile picture
                  user.copy(picture = Some(url)).save
                  redirect.flashing("info" -> "Profile picture updated")
                }.recover { case _ =>
                  redirect.flashing("error" -> "Failed to upload image")
                }
              }
            }
          }
        }
  }

}

object Users extends Controller with Users
