package controllers.authentication

import scala.concurrent._
import ExecutionContext.Implicits.global
import play.api.mvc._
import play.api.Play.current
import play.api.libs.json._
import models.{User, SitePermissions, Collection}
import controllers.Errors
import service.TimeTools

/**
 * This controller does logging out and has a bunch of helpers for dealing with authentication and permissions.
 */
object Authentication extends Controller {

  val isHTTPS = current.configuration.getBoolean("HTTPS").getOrElse(false)

  /**
   * Given a user, logs the user in and sets up the session
   * @param user The user to log
   * @param path A path where the user will be redirected
   * @return The result. To be called from within an action
   */
  def login(user: User, path: String)(implicit request: RequestHeader): Result = {

    // Check if the user's account is merged. If it is, then login with the primary account, if this one isn't it
    val accountLink = user.getAccountLink
    val loginUser =
      if (accountLink.isDefined && user.id.get != accountLink.get.primaryAccount)
        User.findById(accountLink.get.primaryAccount).getOrElse(user)
      else user

    // Log the user in
    loginUser.copy(lastLogin = TimeTools.now()).save

    // Redirect
    {
      if (path.isEmpty)
        Redirect(controllers.routes.Application.home())
      else
        Redirect(path)
    }.withSession("userId" -> loginUser.id.get.toString)
      .flashing("success" -> ("Welcome " + loginUser.displayName + "!"))
  }

  /**
   * Merges a user with the active user
   * @param user The user account to merge with the active one
   */
  def merge(user: User)(implicit request: RequestHeader): Result = {
    getUserFromRequest().map { activeUser =>
      if (activeUser != user) {
        activeUser.merge(user)
        Redirect(controllers.routes.Users.accountSettings()).flashing("success" -> "Account merged.")
      } else
        Redirect(controllers.routes.Users.accountSettings()).flashing("alert" -> "You cannot merge an account with itself")
    }.getOrElse {
      Redirect(controllers.routes.Application.index()).flashing("alert" -> "You are not logged in")
    }
  }

  /**
   * Logs out
   */
  def logout = Action {
    implicit request =>
      val service = controllers.routes.Application.index().absoluteURL(isHTTPS)
      getUserFromRequest()(request).map { user =>
        val accountLink = user.getAccountLink
        val casLogoutUrl  = "https://cas.byu.edu/cas/logout?service="

        // If the account is not linked, there exists only one authentication scheme
        val redir:String = if (accountLink == None) {
          if (user.authScheme == 'cas) { casLogoutUrl + service } else { service }
        } else {
          val users = accountLink.get.getUsers
          val authSchemes = for (u <- accountLink.get.getUsers) yield { u.authScheme }
          if (authSchemes.contains('cas)) { casLogoutUrl + service } else { service }
        }
        Redirect(redir)
      }.getOrElse(Redirect(service))
        .withNewSession
  }

  /**
   * Once the user is authenticated with some scheme, call this to get the actual user object. If it doesn't exist then
   * it will be created.
   * @param username The username of the user
   * @param authScheme The auth scheme used to authenticate
   * @param name The name of the user. Used only if creating the user.
   * @param email The email of the user. Used only if creating the user.
   * @return The user
   */
  def getAuthenticatedUser(username: String, authScheme: Symbol, name: Option[String] = None, email: Option[String] = None): User = {
    // Check if the user is already created
    val user = User.findByAuthInfo(username, authScheme)

    // Add the email and username if they are empty
    val updatedUser = user.map { user =>
      if ((user.email.filterNot(_.length!=0).isEmpty && !email.isEmpty) ||
       (user.name.filterNot(_.length!=0).isEmpty && !name.isEmpty)) {
        user.copy(email = email, name = name).save
      } else user
    }

    updatedUser.getOrElse {
      val user = User(None, username, authScheme, username, name, email).save
      SitePermissions.assignRole(user, 'student)
      user
    }
  }


  // ==========================
  //   Authentication Helpers
  // ==========================
  // These are to help with creating authenticated
  // action or ensuring a certain access level.
  // ==========================

  def enforceCollectionAdmin(collection: Option[Collection])(result: Future[Result])(implicit request: Request[_], user: User): Future[Result] = {
    if (collection.map(_.userIsAdmin(user)).getOrElse(false) || user.hasSitePermission("admin"))
      result
    else {
      Future { Errors.forbidden }
    }
  }

  def enforcePermission(permission: String)(result: Future[Result])(implicit request: Request[_], user: User): Future[Result] = {
    if (user.hasSitePermission(permission))
      result
    else
      Future { Errors.forbidden }
  }

  def getUserFromRequest()(implicit request: RequestHeader): Option[User] = {
    request.session.get("userId").flatMap( userId => User.findById(userId.toLong) )
  }

  /**
   * A generic action to be used for secured API endpoints
   * @param f The action logic. A curried function which, given a request and the authenticated user, returns a result.
   * @return The result. A BadRequest with a json object with a message field or the Result returned from the given action
   */
  def secureAPIRequest[A](parser: BodyParser[A] = BodyParsers.parse.anyContent)(f: Request[A] => User => Future[Result]) = Action.async(parser) {
    implicit request =>
      getUserFromRequest().map( user => f(request)(user) ).getOrElse {
        Future {
          Forbidden(JsObject(Seq("message" -> JsString("You must be logged in to request this resource."))))
        }
      }
  }

  /**
   * A generic action to be used on authenticated pages.
   * @param f The action logic. A curried function which, given a request and the authenticated user, returns a result.
   * @return The result. Either a redirect due to not being logged in, or the result returned by f.
   */
  def authenticatedAction[A](parser: BodyParser[A] = BodyParsers.parse.anyContent)(f: Request[A] => User => Future[Result]) = Action.async(parser) {
    implicit request =>
      getUserFromRequest().map( user => f(request)(user) ).getOrElse {
        Future {
          Redirect(controllers.routes.Application.index().toString(), Map("path" -> List(request.path)))
            .flashing("alert" -> "You are not logged in")
        }
      }
  }
}

