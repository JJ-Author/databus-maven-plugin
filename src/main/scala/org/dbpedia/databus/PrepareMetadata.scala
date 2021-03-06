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

package org.dbpedia.databus

import org.dbpedia.databus.lib.Datafile
import org.dbpedia.databus.params.{BaseEntity => ScalaBaseEntity}
import org.dbpedia.databus.shared.helpers.conversions._
import org.dbpedia.databus.shared.rdf.conversions._
import org.dbpedia.databus.shared.rdf.vocab._
import org.dbpedia.databus.voc.DataFileToModel
import better.files.{File => _, _}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFLanguages.TURTLE
import org.apache.maven.plugin.{AbstractMojo, MojoExecutionException}
import org.apache.maven.plugins.annotations.{LifecyclePhase, Mojo}

import scala.language.reflectiveCalls
import java.io._
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

import org.apache.jena.datatypes.xsd.XSDDatatype.XSDdate
import org.apache.jena.vocabulary.{RDF, RDFS}

/**
  * Analyse release data files
  *
  * Generates statistics from the release data files such as:
  * * md5sum
  * * file size in bytes
  * * compression algo used
  * * internal mimetype
  * Also creates a signature with the private key
  *
  * Later more can be added like
  * * links
  * * triple size
  *
  */
@Mojo(name = "metadata", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresOnline = true)
class PrepareMetadata extends AbstractMojo with Properties with SigningHelpers with DataFileToModel {

  @throws[MojoExecutionException]
  override def execute(): Unit = {
    //skip the parent module
    if(isParent()) {
      getLog.info("skipping parent module")
      return
    }

    val dataIdCollect: Model = ModelFactory.createDefaultModel


    getLog.info(s"looking for data files in: ${dataInputDirectory.getCanonicalPath}")
    getLog.info(s"Found ${getListOfInputFiles().size} files:\n${getListOfInputFiles().mkString(", ")}")
    //collecting metadata for each file
    getListOfInputFiles().foreach(datafile => {
      processFile(datafile, dataIdCollect)
    })

    //retrieving all User Accounts
    var accountOption = {
      implicit val userAccounts: Model = PrepareMetadata.registeredAccounts
      Option(publisher.toString.asIRI.getProperty(foaf.account)).map(_.getObject.asResource)
    }

    // write the model to /target/
    if(!dataIdCollect.isEmpty) {
      {
        implicit val editContext = dataIdCollect

        // add DataId
        /**
          * <http://downloads.dbpedia.org/2016-10/core-i18n/en/2016-10_dataid_en.ttl>
        ? dataid:underAuthorization  <http://downloads.dbpedia.org/2016-10/core-i18n/en/2016-10_dataid_en.ttl?auth=maintainerAuthorization> , <http://downloads.dbpedia.org/2016-10/core-i18n/en/2016-10_dataid_en.ttl?auth=creatorAuthorization> , <http://downloads.dbpedia.org/2016-10/core-i18n/en/2016-10_dataid_en.ttl?auth=contactAuthorization> ;
        dc:modified                "2017-07-06"^^xsd:date ;
        foaf:primaryTopic          <http://dbpedia.org/dataset/main_dataset?lang=en&dbpv=2016-10> .
          */

        val dataIdResource = dataIdCollect.createResource("")
        dataIdResource.addProperty(dcterms.title, s"DataID metadata for ${groupId}/${artifactId}", "en")
        dataIdResource.addProperty(RDFS.`label`, s"DataID metadata for ${groupId}/${artifactId}", "en")
        dataIdResource.addProperty(dcterms.hasVersion, "1.3.0")
        dataIdResource.addProperty(RDF.`type`, dataid.DataId)
        dataIdResource.addProperty(RDFS.`comment`,
          s"""Metadata created by the DBpedia Databus Maven Plugin: https://github.com/dbpedia/databus-maven-plugin
            |Version 1.3.0 (the first stable release, containing all essential properties)
            |The DataID ontology is a metadata omnibus, which can be extended to be interoperable with all metadata formats
            |Note that the metadata (the dataid.ttl file) is always CC-0, the files are licensed individually
            |Created by ${publisher}
          """.stripMargin)
        def issuedTime = params.issuedDate.getOrElse(invocationTime)
        dataIdResource.addProperty(dcterms.issued, ISO_LOCAL_DATE.format(issuedTime).asTypedLiteral(XSDdate))
        dataIdResource.addProperty(dcterms.license, "http://purl.oclc.org/NET/rdflicense/cc-zero1.0".asIRI)
        dataIdResource.addProperty(dcterms.conformsTo, global.dataid.namespace)
        dataIdResource.addProperty(dataid.associatedAgent, publisher.toString.asIRI)
        dataIdResource.addProperty(dcterms.publisher, publisher.toString.asIRI)

        // add dataset metadata
        val datasetResource = dataIdCollect.createResource(s"#${finalName}")
        datasetResource.addProperty(RDF.`type`, dataid.Dataset)
        addBasicPropertiesToResource(dataIdCollect, datasetResource)


        /**
          * match WebId to Account Name
          */
        accountOption match {

          case Some(account) => {

            datasetResource.addProperty(dataid.groupId, s"${account.getURI}/${groupId}".asIRI)
            datasetResource.addProperty(dataid.artifact, s"${account.getURI}/${groupId}/${artifactId}".asIRI)
          }

          case None => {

            datasetResource.addProperty(dataid.groupId, "https://github.com/dbpedia/accounts/blob/master/README.md#ACCOUNTNEEDED".asIRI)
            datasetResource.addProperty(dataid.artifact, "https://github.com/dbpedia/accounts/blob/master/README.md#ACCOUNTNEEDED".asIRI)
            getLog.warn("Not registered, Dataset URIs will not work, please register at https://github.com/dbpedia/accounts/blob/master/README.md#ACCOUNTNEEDED")
          }
        }

        /**
          * adding wasDerivedFrom other datasets
          */
        params.wasDerivedFrom.foreach { case ScalaBaseEntity(artifact, version) =>

          val baseEntityBlankNode = editContext.createResource().tap { baseEntityRes =>

            baseEntityRes.addProperty(dataid.artifact, artifact.toString.asIRI)
            baseEntityRes.addProperty(dcterms.hasVersion, version)
          }

          datasetResource.addProperty(prov.wasDerivedFrom, baseEntityBlankNode)
        }
      }

      //writing the metadatafile
      getDataIdFile().toScala.outputStream.foreach { os =>

        dataIdCollect.write(os, "turtle")
      }
    }
  }

  def processFile(datafile: File, dataIdCollect: Model): Unit = {

    getLog.debug(s"found file ${datafile.getCanonicalPath}")
    val df: Datafile = Datafile(datafile)(getLog).ensureExists()
    df.updateSignature(singleKeyPairFromPKCS12)
    df.updateFileMetrics()

    val model = modelForDatafile(df)
    getLog.debug(df.toString)
    dataIdCollect.add(model)
  }
}

object PrepareMetadata {

  lazy val registeredAccounts = ModelFactory.createDefaultModel.tap { accountsModel =>

    accountsModel.read("https://raw.githubusercontent.com/dbpedia/accounts/master/accounts.ttl", TURTLE.getName)
  }
}
