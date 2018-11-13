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

import java.io.{ ByteArrayInputStream, File, FileOutputStream }
import java.net.URI

import com.waz.log.ZLog2._
import com.waz.ZLog.ImplicitTag._
import com.waz.service.assets2.Asset.General
import com.waz.model.errors.NotFoundLocal
import com.waz.model.{ AssetId, Mime, Sha256 }
import com.waz.sync.client.AssetClient2
import com.waz.sync.client.AssetClient2.FileWithSha
import com.waz.threading.CancellableFuture
import com.waz.utils.{ returning, IoUtils }
import com.waz.{ FilesystemUtils, ZIntegrationMockSpec }

import scala.concurrent.Future
import scala.util.{ Failure, Random, Success }

class AssetServiceSpec extends ZIntegrationMockSpec {

  private val assetStorage        = mock[AssetStorage]
  private val rawAssetStorage     = mock[RawAssetStorage]
  private val assetDetailsService = mock[AssetDetailsService]
  private val previewService      = mock[AssetPreviewService]
  private val cache               = mock[AssetCache]
  private val client              = mock[AssetClient2]
  private val uriHelper           = mock[UriHelper]

  private val testAssetContent = returning(Array.ofDim[Byte](1024))(Random.nextBytes)

  private val testAsset = Asset(
    id = AssetId(),
    token = None,
    sha = Sha256.calculate(testAssetContent),
    mime = Mime.Default,
    encryption = NoEncryption,
    localSource = None,
    preview = None,
    name = "test_content",
    size = testAssetContent.length,
    details = BlobDetails,
    messageId = None,
    convId = None
  )

  implicit val AssetShow: LogShow[Asset[General]] = LogShow.create(
    hideFields = Set("token"),
    inlineFields = Set("convId", "encryption")
  )

  verbose(l"Test asset: $testAsset")

  private val service: AssetService =
    new AssetServiceImpl(assetStorage, rawAssetStorage, assetDetailsService, previewService, uriHelper, cache, client)

  feature("Assets") {

    scenario("load asset content if it does not exist in cache and asset does not exist in storage") {
      val testDir = FilesystemUtils.createDirectoryForTest()
      val downloadAssetResult = {
        val file = new File(testDir, "asset_content")
        IoUtils.write(new ByteArrayInputStream(testAssetContent), new FileOutputStream(file))
        FileWithSha(file, Sha256.calculate(testAssetContent))
      }

      (assetStorage.find _).expects(*).once().returns(Future.successful(None))
      (assetStorage.save _).expects(testAsset).once().returns(Future.successful(()))
      (client.loadAssetContent _)
        .expects(testAsset, *)
        .once()
        .returns(CancellableFuture.successful(Right(downloadAssetResult)))
      (cache.put _).expects(*, *, *).once().returns(Future.successful(()))
      (cache.getStream _).expects(*).once().returns(Future.successful(new ByteArrayInputStream(testAssetContent)))

      for {
        result <- service.loadContent(testAsset, callback = None)
        bytes = IoUtils.toByteArray(result)
      } yield {
        bytes shouldBe testAssetContent
      }
    }

    scenario("load asset content if it does not exist in cache") {
      val testDir = FilesystemUtils.createDirectoryForTest()
      val downloadAssetResult = {
        val file = new File(testDir, "asset_content")
        IoUtils.write(new ByteArrayInputStream(testAssetContent), new FileOutputStream(file))
        FileWithSha(file, Sha256.calculate(testAssetContent))
      }

      (assetStorage.find _).expects(*).once().returns(Future.successful(Some(testAsset)))
      (cache.getStream _).expects(*).once().returns(Future.failed(NotFoundLocal("not found")))
      (client.loadAssetContent _)
        .expects(testAsset, *)
        .once()
        .returns(CancellableFuture.successful(Right(downloadAssetResult)))
      (cache.put _).expects(*, *, *).once().returns(Future.successful(()))
      (cache.getStream _).expects(*).once().returns(Future.successful(new ByteArrayInputStream(testAssetContent)))

      for {
        result <- service.loadContent(testAsset, callback = None)
        bytes = IoUtils.toByteArray(result)
      } yield {
        bytes shouldBe testAssetContent
      }
    }

    scenario("load asset content if it exists in cache") {
      (assetStorage.find _).expects(*).once().returns(Future.successful(Some(testAsset)))
      (cache.getStream _).expects(*).once().returns(Future.successful(new ByteArrayInputStream(testAssetContent)))

      for {
        result <- service.loadContent(testAsset, callback = None)
        bytes = IoUtils.toByteArray(result)
      } yield {
        bytes shouldBe testAssetContent
      }
    }

    scenario("load asset content if it has not empty local source") {
      val asset =
        testAsset.copy(localSource = Some(LocalSource(new URI("www.test"), Sha256.calculate(testAssetContent))))

      (assetStorage.find _).expects(*).once().returns(Future.successful(Some(asset)))
      (uriHelper.openInputStream _)
        .expects(*)
        .twice()
        .onCall({ _: URI =>
          Success(new ByteArrayInputStream(testAssetContent))
        })

      for {
        result <- service.loadContent(asset, callback = None)
        bytes = IoUtils.toByteArray(result)
      } yield {
        bytes shouldBe testAssetContent
      }
    }

    scenario("load asset content if it has not empty local source and we can not load content") {
      val asset =
        testAsset.copy(localSource = Some(LocalSource(new URI("www.test"), Sha256.calculate(testAssetContent))))
      val testDir = FilesystemUtils.createDirectoryForTest()
      val downloadAssetResult = {
        val file = new File(testDir, "asset_content")
        IoUtils.write(new ByteArrayInputStream(testAssetContent), new FileOutputStream(file))
        FileWithSha(file, Sha256.calculate(testAssetContent))
      }

      (assetStorage.find _).expects(*).once().returns(Future.successful(Some(asset)))
      (uriHelper.openInputStream _).expects(*).once().returns(Failure(new IllegalArgumentException))
      (assetStorage.save _).expects(asset.copy(localSource = None)).once().returns(Future.successful(()))
      (client.loadAssetContent _)
        .expects(asset, *)
        .once()
        .returns(CancellableFuture.successful(Right(downloadAssetResult)))
      (cache.put _).expects(*, *, *).once().returns(Future.successful(()))
      (cache.getStream _).expects(*).once().returns(Future.successful(new ByteArrayInputStream(testAssetContent)))

      for {
        result <- service.loadContent(asset, callback = None)
        bytes = IoUtils.toByteArray(result)
      } yield {
        bytes shouldBe testAssetContent
      }
    }

    scenario("load asset content if it has not empty local source but local source content has changed") {
      val testContentSha = Sha256.calculate(testAssetContent)
      val asset          = testAsset.copy(localSource = Some(LocalSource(new URI("www.test"), testContentSha)))
      val testDir        = FilesystemUtils.createDirectoryForTest()
      val downloadAssetResult = {
        val file = new File(testDir, "asset_content")
        IoUtils.write(new ByteArrayInputStream(testAssetContent), new FileOutputStream(file))
        FileWithSha(file, testContentSha)
      }

      (assetStorage.find _).expects(*).once().returns(Future.successful(Some(asset)))
      //emulating file changing
      (uriHelper.openInputStream _)
        .expects(*)
        .once()
        .returns(Success(new ByteArrayInputStream(testAssetContent :+ 1.toByte)))
      (assetStorage.save _).expects(asset.copy(localSource = None)).once().returns(Future.successful(()))
      (client.loadAssetContent _)
        .expects(asset, *)
        .once()
        .returns(CancellableFuture.successful(Right(downloadAssetResult)))
      (cache.put _).expects(*, *, *).once().returns(Future.successful(()))
      (cache.getStream _).expects(*).once().returns(Future.successful(new ByteArrayInputStream(testAssetContent)))

      for {
        result <- service.loadContent(asset, callback = None)
        bytes = IoUtils.toByteArray(result)
      } yield {
        bytes shouldBe testAssetContent
      }
    }

  }

}
