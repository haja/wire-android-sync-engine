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
package com.waz.service

import com.waz.service.BackendConfig.FirebaseOptions
import com.waz.utils.wrappers.URI

case class BackendConfig(baseUrl: URI, websocketUrl: String, firebaseOptions: FirebaseOptions, environment: String, name: String) {
  val pushSenderId = firebaseOptions.pushSenderId
}

object BackendConfig {

  case class FirebaseOptions(pushSenderId: String, appId: String, apiKey: String)

  //This information can be found in downloadable google-services.json file from the BE console.
  val StagingFirebaseOptions = FirebaseOptions("723990470614", "1:723990470614:android:9a1527f79aa62284", "AIzaSyAGCoJGUtDBLJJiQPLxHQRrdkbyI0wlbo8")
  val ProdFirebaseOptions    = FirebaseOptions("782078216207", "1:782078216207:android:d3db2443512d2055", "AIzaSyBdYVv2f-Y7JJmHVmDNCKgWvX6Isa8rAGA")

  val StagingBackend = BackendConfig(URI.parse("http://10.0.2.2:8080"), "http://10.0.2.2:8080/await", StagingFirebaseOptions, "staging", "staging")
  val StagingBackendLocal = BackendConfig(URI.parse("http://localhost:8080"), "http://localhost:8080/await", StagingFirebaseOptions, "staging", "local")
  val StagingBackendTrigger = BackendConfig(URI.parse("http://trigger.lan:8080"), "http://trigger.lan:8080/await", StagingFirebaseOptions, "staging", "trigger")
  val ProdBackend    = BackendConfig(URI.parse("http://10.0.2.2:8080"), "http://10.0.2.2:8080/await", ProdFirebaseOptions,    "prod", "prod")

  lazy val byName = Seq(StagingBackend, StagingBackendLocal, StagingBackendTrigger, ProdBackend).map(b => b.name -> b).toMap

  def apply(baseUrl: String): BackendConfig = BackendConfig(URI.parse(baseUrl), "", StagingFirebaseOptions, "", "") // XXX only use for testing!

}
