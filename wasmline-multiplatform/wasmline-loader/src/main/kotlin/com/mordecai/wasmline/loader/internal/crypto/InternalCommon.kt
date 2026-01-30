/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("SpellCheckingInspection")

package com.mordecai.wasmline.loader.internal.crypto

internal const val MANIFEST_FILE_NAME = "manifest.wasmline.json"

internal fun getApplicationManifestFileName(applicationName: String) = "$applicationName.$MANIFEST_FILE_NAME"

/** ECDSA P-256. https://en.wikipedia.org/wiki/Elliptic_Curve_Digital_Signature_Algorithm */
internal expect val ecdsaP256: SignatureAlgorithm

internal fun SignatureAlgorithmId.get(): SignatureAlgorithm {
  return when (this) {
    SignatureAlgorithmId.Ed25519 -> Ed25519
    SignatureAlgorithmId.EcdsaP256 -> ecdsaP256
  }
}

internal expect val systemEpochMsClock: () -> Long

/** Returns the URL of [link] relative to [baseUrl]. */
internal expect fun resolveUrl(baseUrl: String, link: String): String
