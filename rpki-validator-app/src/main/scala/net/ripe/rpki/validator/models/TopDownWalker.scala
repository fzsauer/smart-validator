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
package net.ripe.rpki.validator.models

import java.net.URI

import net.ripe.rpki.commons.crypto.crl.{CrlLocator, X509Crl, X509CrlValidator}
import net.ripe.rpki.commons.crypto.util.CertificateRepositoryObjectFactory
import net.ripe.rpki.commons.validation.ValidationString._
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import net.ripe.rpki.commons.validation.{ValidationLocation, ValidationOptions, ValidationResult}
import net.ripe.rpki.validator.models.validation._
import net.ripe.rpki.validator.store.Storage
import org.apache.commons.lang.Validate

import scala.collection.JavaConverters._
import scala.collection.mutable


class TopDownWalker(certificateContext: CertificateRepositoryObjectValidationContext, store: Storage, fetcher: RepoFetcher, validationOptions: ValidationOptions) {

  private object HashUtil extends Hashing

  Validate.isTrue(certificateContext.getCertificate.isObjectIssuer, "certificate must be an object issuer")

  private val validationResult: ValidationResult = ValidationResult.withLocation("")

  private lazy val crlOption: Option[CrlObject] = findCrl

  private def crlLocator = new CrlLocator {
    override def getCrl(uri: URI, context: CertificateRepositoryObjectValidationContext, result: ValidationResult): X509Crl =
      crlOption.get.decoded
  }

  def execute: ValidationResult = {
    Option(certificateContext.getRepositoryURI) match {
      case Some(repositoryUri) =>
        prefetch(repositoryUri)
        validationResult.setLocation(new ValidationLocation(repositoryUri))

      case None =>  //TODO do nothing, suppose this could happen if CA has no children?
    }

    if (crlOption.isDefined) {
      val roas = findRoas
      val childrenCertificates = findChildrenCertificates

      findManifest match {
        case Some(manifest) =>
          crossCheckWithManifest(manifest, crlOption.get, roas, childrenCertificates)
        case None =>
          validationError(VALIDATOR_CA_SHOULD_HAVE_MANIFEST, HashUtil.stringify(certificateContext.getSubjectKeyIdentifier))
      }

      childrenCertificates.foreach( cert => {
        val newValidationContext = new CertificateRepositoryObjectValidationContext(new URI(""), cert.decoded) //TODO get proper URI
        new TopDownWalker(newValidationContext, store, fetcher, validationOptions).execute
      })

    } else {
      validationError(CRL_REQUIRED, "No valid CRL found with SKI=" + certificateContext.getSubjectKeyIdentifier)
    }

    validationResult
  }

  private def prefetch(uri: URI) = fetcher.fetch(uri)

  private def findCrl: Option[CrlObject] = {
    val keyIdentifier = certificateContext.getSubjectKeyIdentifier
    findMostRecentValidCrl(store.getCrls(keyIdentifier))
  }

  private def findMostRecentValidCrl(crlList: Seq[CrlObject]): Option[CrlObject] = {
    crlList.sortBy(_.decoded.getNumber).reverse.find(crl => {
      val crlLocation = crl.decoded.getCrlUri.toString
      val crlValidationResult = ValidationResult.withLocation(crlLocation)
      val validator: X509CrlValidator = new X509CrlValidator(validationOptions, crlValidationResult, certificateContext.getCertificate)
      validator.validate(crlLocation, crl.decoded)
      validationResult.addAll(crlValidationResult)
      ! crlValidationResult.hasFailureForCurrentLocation
    })
  }

  private def findManifest: Option[ManifestObject] = {
    val manifests = store.getManifests(certificateContext.getSubjectKeyIdentifier)
    findMostRecentValidManifest(manifests)
  }

  private def findMostRecentValidManifest(manifests: Seq[ManifestObject]): Option[ManifestObject] = {
    manifests.sortBy(_.decoded.getNumber).reverse.find( manifest => {
      val manifestValidationResult: ValidationResult = ValidationResult.withLocation("")
      manifest.decoded.validate(certificateContext.getLocation.toString, certificateContext, crlLocator, validationOptions, manifestValidationResult)
      validationResult.addAll(manifestValidationResult)
      ! manifestValidationResult.hasFailures
    })
  }

  type FileAndHashEntries = Map[String, Array[Byte]]

  private def processManifestEntries(manifest: ManifestObject, crl: CrlObject, roas: Seq[RepositoryObject[_]], childrenCertificates: Seq[RepositoryObject[_]]) {
    val repositoryUri = certificateContext.getRepositoryURI
    val validationLocation = new ValidationLocation(manifest.url)
    val manifestEntries: FileAndHashEntries = manifest.decoded.getFiles.entrySet().asScala.map { entry =>
      repositoryUri.resolve(entry.getKey).toString -> entry.getValue
    }.toMap
    
    val (crlsOnManifest, entriesExceptCrls) = manifestEntries.partition(_._1.toLowerCase.endsWith(".crl"))
    crossCheckCrls(crl, crlsOnManifest, validationLocation)

    crossCheckRepoObjects(validationLocation, entriesExceptCrls, childrenCertificates ++ roas)
  }

  def crossCheckRepoObjects(validationLocation: ValidationLocation, manifestCertEntries: FileAndHashEntries, foundCertificates: Seq[RepositoryObject[_]]) {
    
    val foundCertificatesEntries = foundCertificates.map(c => c.url -> c.hash).toMap
    
    val notFoundInRepo = manifestCertEntries.keySet -- foundCertificatesEntries.keySet
    val notOnManifest = foundCertificatesEntries.keySet -- manifestCertEntries.keySet
    val objectsWithMatchingUri = manifestCertEntries.keySet intersect foundCertificatesEntries.keySet

    notFoundInRepo.foreach { location =>
      validationResult.warnForLocation(validationLocation, VALIDATOR_REPOSITORY_OBJECT_NOT_FOUND, location)
    }

    notOnManifest.foreach { location =>
      validationResult.warnForLocation(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, location)
    }

    objectsWithMatchingUri.filterNot { location =>
      HashUtil.equals(manifestCertEntries(location), foundCertificatesEntries(location))
    } foreach { location =>
      validationResult.warnForLocation(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE,
        s"Hash code of object at $location (${foundCertificatesEntries(location)}) does not match the one specified in the manifest (${manifestCertEntries(location)})")
    }
  }

  def checkManifestUrlOnCertMatchesLocationInRepo(manifest: ManifestObject) = {
    val manifestLocationInCertificate: String = certificateContext.getManifestURI.toString
    val manifestLocationInRepository: String = manifest.url
    if(! manifestLocationInRepository.equalsIgnoreCase(manifestLocationInCertificate)) {
      validationResult.warnForLocation(new ValidationLocation(manifestLocationInRepository),
        VALIDATOR_MANIFEST_LOCATION_MISMATCH, manifestLocationInCertificate, manifestLocationInRepository)
    }
  }

  def crossCheckCrls(crl: CrlObject, manifestCrlEntries: FileAndHashEntries, validationLocation: ValidationLocation) = {
    if (manifestCrlEntries.size == 0) {
      validationResult.warnForLocation(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, "*.crl")
    } else if (manifestCrlEntries.size > 1) {
      val crlFileNames = manifestCrlEntries.keys.mkString(",")
      validationResult.warnForLocation(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, s"Single CRL expected, found: $crlFileNames")
    } else {
      val locationOnMft = certificateContext.getRepositoryURI.resolve(manifestCrlEntries.keys.head).toString
      val hashOnMft = manifestCrlEntries.values.head
      if (locationOnMft != crl.url) {
        validationResult.warnForLocation(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, s"URI of CRL in manifest ($locationOnMft) doesn't match URL in repo ($crl.url)")
      } else if (!HashUtil.equals(crl.hash, hashOnMft)) {
        validationResult.warnForLocation(validationLocation, VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, s"Hash code of $locationOnMft doesn't match hash code in manifest")
      }
    }
  }

  private def findRoas = {
    val location: String = certificateContext.getLocation.toString
    val roas = store.getRoas(certificateContext.getSubjectKeyIdentifier)
    roas.foreach(_.decoded.validate(location, certificateContext, crlLocator, validationOptions, validationResult))
    roas
  }

  private def findChildrenCertificates = {
    val location: String = certificateContext.getLocation.toString
    val certs = store.getCertificates(certificateContext.getSubjectKeyIdentifier)
    certs.foreach(_.decoded.validate(location, certificateContext, crlLocator, validationOptions, validationResult))
    certs
  }

  private def crossCheckWithManifest(manifest: ManifestObject, crl: CrlObject, roas: Seq[RoaObject], childrenCertificates: Seq[CertificateObject]) {
        checkManifestUrlOnCertMatchesLocationInRepo(manifest)
        processManifestEntries(manifest, crl, roas, childrenCertificates)
  }



  private def validate(repositoryObject: StoredRepositoryObject, uri: URI): ValidationResult = {
    val result = ValidationResult.withLocation(uri)
    val certificateRepositoryObject = CertificateRepositoryObjectFactory.createCertificateRepositoryObject(repositoryObject.binaryObject.toArray[Byte], result)
    certificateRepositoryObject.validate(uri.toString, certificateContext, crlLocator, validationOptions, result)
    result
  }

  private def validationError(uri: URI, key: String, param: String) = {
    validationResult.rejectForLocation(new ValidationLocation(uri), key, param)
  }

  private def validationError(key: String, param: String) = {
    validationResult.error(key, param)
  }

}
