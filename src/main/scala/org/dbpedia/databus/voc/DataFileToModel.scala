/*-
 * #%L
 * databus-maven-plugin
 * %%
 * Copyright (C) 2018 Sebastian Hellmann (on behalf of the DBpedia Association)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

package org.dbpedia.databus.voc

import org.dbpedia.databus.{Parameters, Properties}
import org.dbpedia.databus.lib.Datafile
import org.dbpedia.databus.shared.rdf.conversions._
import org.dbpedia.databus.shared.rdf.vocab._

import better.files._
import org.apache.jena.datatypes.xsd.XSDDatatype._
import org.apache.jena.rdf.model.{Model, ModelFactory, Resource}
import org.apache.jena.vocabulary.{OWL, RDF, RDFS, XSD}
import org.apache.maven.plugin.AbstractMojo

import scala.collection.JavaConverters._
import scala.language.reflectiveCalls

import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.{LocalDateTime, ZoneId}

object DataFileToModel {

  val prefixes = Map(
    "databus" -> "https://databus.dbpedia.org/",
    "dataid" -> global.dataid.namespace,
    "dataid-ld" -> "http://dataid.dbpedia.org/ns/ld#",
    "dataid-mt" -> "http://dataid.dbpedia.org/ns/mt#",
    "dataid-pl" -> "http://dataid.dbpedia.org/ns/pl#",
    "dmp" -> "http://dataid.dbpedia.org/ns/dmp#",
    "dct" -> global.dcterms.namespace,
    "dcat" -> global.dcat.namespace,
    "void" -> "http://rdfs.org/ns/void#",
    "prov" -> global.prov.namespace,
    "xsd" -> XSD.NS,
    "owl" -> OWL.NS,
    "foaf" -> global.foaf.namespace,
    "rdf" -> RDF.uri,
    "rdfs" -> RDFS.uri,
    "datacite" -> "http://purl.org/spar/datacite/",
    "spdx" -> "http://spdx.org/rdf/terms#",
    "sd" -> "http://www.w3.org/ns/sparql-service-description#"
  )
}

trait DataFileToModel extends Properties with Parameters {

  this: AbstractMojo =>

  import DataFileToModel._

  def modelForDatafile(datafile: Datafile): Model = {

    implicit val model: Model = ModelFactory.createDefaultModel

    for((key, value) <- prefixes) {
      model.setNsPrefix(key, value)
    }

    // main uri of dataid for SingleFile
    val singleFileResource = ("#" + datafile.finalBasename(params.versionToInsert)).asIRI

    /**
      * linking to dataset
      */
    val datasetResource = s"#${finalName}".asIRI
    singleFileResource.addProperty(dataid.isDistributionOf, datasetResource)
    datasetResource.addProperty(dcat.distribution, singleFileResource)

    /**
      *  type properties
      */
    singleFileResource.addProperty(RDF.`type`, dataid.SingleFile)

    /**
      * basic properties
      */
    addBasicPropertiesToResource(model, singleFileResource)

    /**
      * specific info about the file
       */

    def modificationTime = params.modifiedDate.getOrElse(
      LocalDateTime.ofInstant(datafile.file.toScala.lastModifiedTime, ZoneId.systemDefault()))
    singleFileResource.addProperty(dcterms.modified, ISO_LOCAL_DATE.format(modificationTime).asTypedLiteral(XSDdate))

    singleFileResource.addProperty(dataid.sha256sum, datafile.sha256sum.asPlainLiteral)
    singleFileResource.addProperty(dataid.signature, datafile.signatureBase64.asPlainLiteral)
    singleFileResource.addProperty(dataid.preview, datafile.preview)
    singleFileResource.addProperty(dataid.uncompressedByteSize, datafile.uncompressedByteSize.toString.asTypedLiteral(XSDdecimal))
    singleFileResource.addProperty(dcat.byteSize, datafile.bytes.toString.asTypedLiteral(XSDdecimal))
    singleFileResource.addProperty(dcat.downloadURL, datafile.finalBasename(params.versionToInsert).asIRI)

    singleFileResource.addProperty(dataid.duplicates, datafile.duplicates.toString.asTypedLiteral(XSDdecimal))
    singleFileResource.addProperty(dataid.sorted, datafile.sorted.toString.asTypedLiteral(XSDboolean))
    singleFileResource.addProperty(dataid.nonEmptyLines, datafile.nonEmptyLines.toString.asTypedLiteral(XSDdecimal))

    /**
      * mediatype
      * sh: it is a dataid property. However, dataid modeled it as a property of the mimetype, which is the wrong place
      * files have one format extension and maybe one compressionextension and mimetypes have a list of likely extensions
      */
    def mediaTypeName = datafile.format.getClass.getSimpleName.stripSuffix("$")
    val mediaTypeRes = (model.getNsPrefixURI("dataid-mt") + mediaTypeName).asIRI
    mediaTypeRes.addProperty(RDF.`type`, s"${model.getNsPrefixURI("dataid-mt")}MediaType".asIRI)
    singleFileResource.addProperty(dcat.mediaType, mediaTypeRes)
    mediaTypeRes.addProperty(dataid.mimetype, datafile.format.mimeType)
    singleFileResource.addProperty(dataid.prop.formatExtension, datafile.formatExtension.asPlainLiteral)
    singleFileResource.addProperty(dataid.compression, datafile.compressionOrArchiveDesc)

    /**
      * content variant
      */
    datafile.contentVariantExtensions.foreach { contentVariant =>
      singleFileResource.addProperty(dataid.prop.contentVariant, contentVariant)
    }

    model
  }

  def addBasicPropertiesToResource(model: Model, thisResource: Resource) = {

    implicit def vocabModel = model

    // label
    for {
      label: String <- labels.asScala
      labelText :: langTag :: Nil = label.split("@").toList
    } {
      thisResource.addProperty(RDFS.label, labelText, langTag)
      thisResource.addProperty(dcterms.title, labelText, langTag)
    }

    //basic properties
    thisResource.addProperty(dcterms.description, datasetDescription.asPlainLiteral)
    // todo add version number, but this is a dataid issue
    thisResource.addProperty(dcterms.conformsTo, global.dataid.namespace)
    thisResource.addProperty(dcterms.hasVersion, version.asPlainLiteral)

    def issuedTime = params.issuedDate.getOrElse(invocationTime)

    thisResource.addProperty(dcterms.issued, ISO_LOCAL_DATE.format(issuedTime).asTypedLiteral(XSDdate))
    thisResource.addProperty(dcterms.license, license.asIRI)
    thisResource.addProperty(dataid.associatedAgent, publisher.toString.asIRI)
    thisResource.addProperty(dcterms.publisher, publisher.toString.asIRI)

    if (maintainer !=null){
      thisResource.addProperty(dataid.maintainer, maintainer.toString.asIRI)
    }
  }
}
