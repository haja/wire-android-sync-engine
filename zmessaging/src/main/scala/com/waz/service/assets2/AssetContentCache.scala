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

import java.io.File

import com.waz.cache2.{FileCache, LruFileCache, SimpleFileCache}
import com.waz.model.{AssetId, RawAssetId}
import com.waz.utils.events.EventContext

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Purpose of this cache is to store encrypted assets content.
  * Cache should contain some kind of auto cleanup logic.
  */
trait AssetContentCache extends FileCache[AssetId]

class AssetContentCacheImpl(val cacheDirectory: File, val directorySizeThreshold: Long, val sizeCheckingInterval: FiniteDuration)
                           (implicit val ec: ExecutionContext, val ev: EventContext) extends LruFileCache[AssetId] with AssetContentCache {

  override protected def createFileName(key: AssetId): String = key.str
}

/**
  * Propose of this cache is to store unencrypted assets content.
  * Cache by itself should not contain any auto cleanup logic.
  * So remove cache entries as soon as you can.
  */
trait RawAssetContentCache extends FileCache[RawAssetId]

class RawAssetContentCacheImpl(val cacheDirectory: File)
                              (implicit val ec: ExecutionContext) extends SimpleFileCache[RawAssetId] with RawAssetContentCache {

  override protected def createFileName(key: RawAssetId): String = key.str
}