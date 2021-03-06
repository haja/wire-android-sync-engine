/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.sync.client

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.IConversation.{Access, AccessRole}
import com.waz.api.impl.ErrorResponse
import com.waz.model.ConversationData.{ConversationType, Link}
import com.waz.model._
import com.waz.sync.client.ConversationsClient.ConversationResponse.ConversationsResult
import com.waz.utils.JsonEncoder.{encodeAccess, encodeAccessRole}
import com.waz.utils.{Json, JsonDecoder, JsonEncoder, returning, _}
import com.waz.znet2.AuthRequestInterceptor
import com.waz.znet2.http.Request.UrlCreator
import com.waz.znet2.http._
import org.json
import org.json.JSONObject

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

trait ConversationsClient {
  import ConversationsClient._
  def loadConversationIds(start: Option[RConvId] = None): ErrorOrResponse[ConversationsResult]
  def loadConversations(start: Option[RConvId] = None, limit: Int = ConversationsPageSize): ErrorOrResponse[ConversationsResult]
  def loadConversations(ids: Seq[RConvId]): ErrorOrResponse[Seq[ConversationResponse]]
  def loadConversation(id: RConvId): ErrorOrResponse[ConversationResponse]
  def postName(convId: RConvId, name: String): ErrorOrResponse[Option[RenameConversationEvent]]
  def postConversationState(convId: RConvId, state: ConversationState): ErrorOrResponse[Unit]
  def postMessageTimer(convId: RConvId, duration: Option[FiniteDuration]): ErrorOrResponse[Unit]
  def postMemberJoin(conv: RConvId, members: Set[UserId]): ErrorOrResponse[Option[MemberJoinEvent]]
  def postMemberLeave(conv: RConvId, user: UserId): ErrorOrResponse[Option[MemberLeaveEvent]]
  def createLink(conv: RConvId): ErrorOrResponse[Link]
  def removeLink(conv: RConvId): ErrorOrResponse[Unit]
  def getLink(conv: RConvId): ErrorOrResponse[Option[Link]]
  def postAccessUpdate(conv: RConvId, access: Set[Access], accessRole: AccessRole): ErrorOrResponse[Unit]
  //TODO Use case class instead of this parameters list
  def postConversation(users: Set[UserId], name: Option[String] = None, team: Option[TeamId], access: Set[Access], accessRole: AccessRole): ErrorOrResponse[ConversationResponse]
}

class ConversationsClientImpl(implicit
                              urlCreator: UrlCreator,
                              httpClient: HttpClient,
                              authRequestInterceptor: AuthRequestInterceptor) extends ConversationsClient {

  import ConversationsClient._
  import HttpClient.AutoDerivation._
  import HttpClient.dsl._
  import com.waz.threading.Threading.Implicits.Background

  private implicit val ConversationIdsResponseDeserializer: RawBodyDeserializer[ConversationsResult] =
    RawBodyDeserializer[JSONObject].map { json =>
      val (ids, hasMore) = ConversationsResult.unapply(JsonObjectResponse(json)).get
      ConversationsResult(ids, hasMore)
    }

  override def loadConversationIds(start: Option[RConvId] = None): ErrorOrResponse[ConversationsResult] = {
    Request
      .Get(
        relativePath = ConversationIdsPath,
        queryParameters = queryParameters("size" -> ConversationIdsPageSize, "start" -> start)
      )
      .withResultType[ConversationsResult]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def loadConversations(start: Option[RConvId] = None, limit: Int = ConversationsPageSize): ErrorOrResponse[ConversationsResult] = {
    Request
      .Get(
        relativePath = ConversationsPath,
        queryParameters = queryParameters("size" -> limit, "start" -> start)
      )
      .withResultType[ConversationsResult]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def loadConversations(ids: Seq[RConvId]): ErrorOrResponse[Seq[ConversationResponse]] = {
    Request
      .Get(relativePath = ConversationsPath, queryParameters = queryParameters("ids" -> ids.mkString(",")))
      .withResultType[ConversationsResult]
      .withErrorType[ErrorResponse]
      .executeSafe
      .map(_.map(_.conversations))
  }

  override def loadConversation(id: RConvId): ErrorOrResponse[ConversationResponse] = {
    Request.Get(relativePath = s"$ConversationsPath/$id")
      .withResultType[ConversationsResult]
      .withErrorType[ErrorResponse]
      .executeSafe(_.conversations.head)
  }

  private implicit val EventsResponseDeserializer: RawBodyDeserializer[List[ConversationEvent]] =
    RawBodyDeserializer[JSONObject].map(json => EventsResponse.unapplySeq(JsonObjectResponse(json)).get)

  override def postName(convId: RConvId, name: String): ErrorOrResponse[Option[RenameConversationEvent]] = {
    Request.Put(relativePath = s"$ConversationsPath/$convId", body = Json("name" -> name))
      .withResultType[List[ConversationEvent]]
      .withErrorType[ErrorResponse]
      .executeSafe {
        case (event: RenameConversationEvent) :: Nil => Some(event)
        case _ => None
      }
  }

  override def postMessageTimer(convId: RConvId, duration: Option[FiniteDuration]): ErrorOrResponse[Unit] = {
    Request
      .Put(
        relativePath = s"$ConversationsPath/$convId/message-timer",
        body = Json("message_timer" -> duration.map(_.toMillis))
      )
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def postConversationState(convId: RConvId, state: ConversationState): ErrorOrResponse[Unit] = {
    Request.Put(relativePath = s"$ConversationsPath/$convId/self", body = state)
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def postMemberJoin(conv: RConvId, members: Set[UserId]): ErrorOrResponse[Option[MemberJoinEvent]] = {
    Request.Post(relativePath = s"$ConversationsPath/$conv/members", body = Json("users" -> Json(members)))
      .withResultType[Option[List[ConversationEvent]]]
      .withErrorType[ErrorResponse]
      .executeSafe(_.collect { case (event: MemberJoinEvent) :: Nil => event })
  }

  override def postMemberLeave(conv: RConvId, user: UserId): ErrorOrResponse[Option[MemberLeaveEvent]] = {
    Request.Delete(relativePath = s"$ConversationsPath/$conv/members/$user")
      .withResultType[Option[List[ConversationEvent]]]
      .withErrorType[ErrorResponse]
      .executeSafe(_.collect { case (event: MemberLeaveEvent) :: Nil => event })
  }

  override def createLink(conv: RConvId): ErrorOrResponse[Link] = {
    Request.Post(relativePath = s"$ConversationsPath/$conv/code", body = "")
      .withResultType[Response[JSONObject]]
      .withErrorType[ErrorResponse]
      .executeSafe { response =>
        val js = response.body
        if (response.code == ResponseCode.Success && js.has("uri"))
          Link(js.getString("uri"))
        else if (response.code == ResponseCode.Created && js.getJSONObject("data").has("uri"))
          Link(js.getJSONObject("data").getString("uri"))
        else
          throw new IllegalArgumentException(s"Can not extract link from json: $js")
      }

  }

  def removeLink(conv: RConvId): ErrorOrResponse[Unit] = {
    Request.Delete(relativePath = s"$ConversationsPath/$conv/code")
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  def getLink(conv: RConvId): ErrorOrResponse[Option[Link]] = {
    Request.Get(relativePath = s"$ConversationsPath/$conv/code")
      .withResultHttpCodes(ResponseCode.SuccessCodes + ResponseCode.NotFound)
      .withResultType[Response[JSONObject]]
      .withErrorType[ErrorResponse]
      .executeSafe { response =>
        val js = response.body
        if (ResponseCode.isSuccessful(response.code) && js.has("uri"))
          Some(Link(js.getString("uri")))
        else if (response.code == ResponseCode.NotFound)
          None
        else
          throw new IllegalArgumentException(s"Can not extract link from json: $js")
      }
  }

  def postAccessUpdate(conv: RConvId, access: Set[Access], accessRole: AccessRole): ErrorOrResponse[Unit] = {
    Request
      .Put(
        relativePath = accessUpdatePath(conv),
        body = Json(
          "access" -> encodeAccess(access),
          "access_role" -> encodeAccessRole(accessRole)
        )
      )
      .withResultType[Unit]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  def postConversation(users: Set[UserId], name: Option[String] = None, team: Option[TeamId], access: Set[Access], accessRole: AccessRole): ErrorOrResponse[ConversationResponse] = {
    debug(s"postConversation($users, $name)")
    val payload = JsonEncoder { o =>
      o.put("users", Json(users))
      name.foreach(o.put("name", _))
      team.foreach(t => o.put("team", returning(new json.JSONObject()) { o =>
        o.put("teamid", t.str)
        o.put("managed", false)
      }))
      o.put("access", encodeAccess(access))
      o.put("access_role", encodeAccessRole(accessRole))
    }
    Request.Post(relativePath = ConversationsPath, body = payload)
      .withResultType[ConversationsResult]
      .withErrorType[ErrorResponse]
      .executeSafe(_.conversations.head)
  }
}

object ConversationsClient {
  val ConversationsPath = "/conversations"
  val ConversationIdsPath = "/conversations/ids"
  val ConversationsPageSize = 100
  val ConversationIdsPageSize = 1000
  val IdsCountThreshold = 32

  def accessUpdatePath(id: RConvId) = s"$ConversationsPath/${id.str}/access"

  case class ConversationResponse(id:           RConvId,
                                  name:         Option[String],
                                  creator:      UserId,
                                  convType:     ConversationType,
                                  team:         Option[TeamId],
                                  muted:        Boolean,
                                  mutedTime:    RemoteInstant,
                                  archived:     Boolean,
                                  archivedTime: RemoteInstant,
                                  access:       Set[Access],
                                  accessRole:   Option[AccessRole],
                                  link:         Option[Link],
                                  messageTimer: Option[FiniteDuration],
                                  members:      Set[UserId])

  object ConversationResponse {
    import com.waz.utils.JsonDecoder._

    implicit lazy val Decoder: JsonDecoder[ConversationResponse] = new JsonDecoder[ConversationResponse] {
      override def apply(implicit js: JSONObject): ConversationResponse = {
        debug(s"decoding response: $js")

        val members = js.getJSONObject("members")
        val state = ConversationState.Decoder(members.getJSONObject("self"))

        ConversationResponse(
          'id,
          'name,
          'creator,
          'type,
          'team,
          state.muted.getOrElse(false),
          state.muteTime.getOrElse(RemoteInstant.Epoch),
          state.archived.getOrElse(false),
          state.archiveTime.getOrElse(RemoteInstant.Epoch),
          'access,
          'access_role,
          'link,
          decodeOptLong('message_timer).map(EphemeralDuration(_)),
          JsonDecoder.arrayColl(members.getJSONArray("others"), { case (arr, i) =>
            UserId(arr.getJSONObject(i).getString("id"))
          }))
      }
    }

    case class ConversationsResult(conversations: Seq[ConversationResponse], hasMore: Boolean)
    
    object ConversationsResult {

      def unapply(response: ResponseContent): Option[(List[ConversationResponse], Boolean)] = try {
        response match {
          case JsonObjectResponse(js) if js.has("conversations") =>
            Some((array[ConversationResponse](js.getJSONArray("conversations")).toList, decodeBool('has_more)(js)))
          case JsonArrayResponse(js) => Some((array[ConversationResponse](js).toList, false))
          case JsonObjectResponse(js) => Some((List(Decoder(js)), false))
          case _ => None
        }
      } catch {
        case NonFatal(e) =>
          warn(s"couldn't parse conversations response: $response", e)
          warn("json decoding failed", e)
          None
      }
    }
  }

  object EventsResponse {
    import com.waz.utils.JsonDecoder._

    def unapplySeq(response: ResponseContent): Option[List[ConversationEvent]] = try {
      response match {
        case JsonObjectResponse(js) if js.has("events") => Some(array[ConversationEvent](js.getJSONArray("events")).toList)
        case JsonArrayResponse(js) => Some(array[ConversationEvent](js).toList)
        case JsonObjectResponse(js) => Some(List(implicitly[JsonDecoder[ConversationEvent]].apply(js)))
        case _ => None
      }
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse events response $response", e)
        None
    }
  }
}
