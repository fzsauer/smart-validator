/**
 * The BSD License
 *
 * Copyright (c) 2010-2012 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator.fetchers

import java.net.URI

import grizzled.slf4j.Logging
import net.ripe.rpki.validator.config.{ApplicationOptions, Http}
import net.ripe.rpki.validator.store.HttpFetcherStore
import org.apache.commons.io.IOUtils
import org.apache.http.HttpStatus
import org.apache.http.client.methods.HttpGet

class SingleObjectHttpFetcher(store: HttpFetcherStore) extends Fetcher with Http with Logging {
  override def trustedCertsLocation = ApplicationOptions.trustedSslCertsLocation

  def fetch(uri: URI, process: FetcherListener): Seq[Fetcher.Error] = {
    tryTo(uri)(connectionE) {
      val response = httpGet(uri.toString)
      response.getStatusLine.getStatusCode match {
        case HttpStatus.SC_OK =>
          IOUtils.toByteArray(response.getEntity.getContent)
        case _ =>
          throw new RuntimeException(response.getStatusLine.getStatusCode + " " + response.getStatusLine.getReasonPhrase)
      }
    }.right.map { bytes =>
      processObject(uri, bytes, process)
    }.left.toSeq
  }
}
