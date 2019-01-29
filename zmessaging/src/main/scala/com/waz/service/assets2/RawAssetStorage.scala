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
package com.waz.service.assets2

import android.content.Context
import com.waz.db.{ColumnBuilders, Dao}
import com.waz.model.RawAssetId
import com.waz.service.assets2.Asset._
import com.waz.service.assets2.RawAssetStorage.RawAssetDao
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.wrappers.{DB, DBCursor}
import com.waz.utils.{CachedStorage2, CirceJSONSupport, DbStorage2, InMemoryStorage2, ReactiveStorage2, ReactiveStorageImpl2, TrimmingLruCache}

import scala.concurrent.ExecutionContext

trait RawAssetStorage extends ReactiveStorage2[RawAssetId, RawAsset[RawGeneral]]

class RawAssetStorageImpl(context: Context, db: DB, ec: ExecutionContext) extends ReactiveStorageImpl2(
  new CachedStorage2(
    new DbStorage2(RawAssetDao)(ec, db),
    new InMemoryStorage2[RawAssetId, RawAsset[RawGeneral]](new TrimmingLruCache(context, Fixed(8)))(ec)
  )(ec)
) with RawAssetStorage

object RawAssetStorage {

  //TODO Actually we do not need DAO classes. We can generate them using 'shapeless'.
  object RawAssetDao extends Dao[RawAsset[RawGeneral], RawAssetId]
    with ColumnBuilders[RawAsset[RawGeneral]]
    with StorageCodecs
    with CirceJSONSupport {

    val Id           = asText(_.id)('_id, "PRIMARY KEY")
    val Source       = asText(_.localSource)('source)
    val Name         = text(_.name)('name)
    val Sha          = asBlob(_.sha)('sha)
    val Mime         = asText(_.mime)('mime)
    val Uploaded     = long(_.uploaded)('uploaded)
    val Size         = long(_.size)('size)
    val Retention    = asInt(_.retention)('retention)
    val Public       = bool(_.public)('public)
    val Encryption   = asText(_.encryption)('encryption)
    val Type         = text(getAssetTypeString)('type)
    val Details      = asText(_.details)('details)
    val UploadStatus = asInt(_.uploadStatus)('upload_status)
    val AssetId      = asTextOpt(_.assetId)('asset_id)
    val MessageId    = asText(_.messageId)('message_id)

    override val idCol = Id
    override val table = Table(
      "RawAssets",
      Id, Source, Sha, Mime, Uploaded, Size, Retention, Public, Encryption, Type, Details, AssetId
    )

    private val Image = "image"
    private val Audio = "audio"
    private val Video = "video"
    private val Blob  = "blob"
    private val NotReady = "not_ready"

    override def apply(implicit cursor: DBCursor): RawAsset[RawGeneral] = {
      RawAsset(Id, Source, Name, Sha, Mime, Uploaded, Size, Retention, Public, Encryption, Details, UploadStatus, AssetId, MessageId)
    }

    private def getAssetTypeString(asset: RawAsset[RawGeneral]): String = asset.details match {
      case _: Image => Image
      case _: Audio => Audio
      case _: Video => Video
      case _: Blob => Blob
      case _: NotReady => NotReady
    }

  }


}
