/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.repository.dynamodb

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBClient, AmazonDynamoDBClientBuilder}
import com.typesafe.config.Config
import vinyldns.api.VinylDNSConfig

object DynamoDBClient {

  def apply(config: Config = VinylDNSConfig.dynamoConfig): AmazonDynamoDBClient = {
    val dynamoAKID = config.getString("key")
    val dynamoSecret = config.getString("secret")
    val dynamoEndpoint = config.getString("endpoint")
    val dynamoRegion = config.getString("region")

    // Important!  For some reason the basic credentials get lost in Jenkins.  Set the aws system properties
    // just in case
    System.getProperties.setProperty("aws.accessKeyId", dynamoAKID)
    System.getProperties.setProperty("aws.secretKey", dynamoSecret)
    val credentials = new BasicAWSCredentials(dynamoAKID, dynamoSecret)
    AmazonDynamoDBClientBuilder
      .standard()
      .withCredentials(new AWSStaticCredentialsProvider(credentials))
      .withEndpointConfiguration(new EndpointConfiguration(dynamoEndpoint, dynamoRegion))
      .build()
      .asInstanceOf[AmazonDynamoDBClient]
  }
}
