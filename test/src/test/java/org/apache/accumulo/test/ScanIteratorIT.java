/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test;

import static org.junit.Assert.assertEquals;
import java.util.Collections;
import java.util.Map;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.harness.AccumuloClusterIT;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.test.functional.AuthsIterator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ScanIteratorIT extends AccumuloClusterIT {

  private static final String USER = "authsItrUser";

  @Override
  public void configureMiniCluster(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setNumTservers(1);
  }

  @Override
  protected int defaultTimeoutSeconds() {
    return 60;
  }

  private Connector connector;
  private String tableName;

  @Before
  public void setup() throws Exception {
    connector = getConnector();
    tableName = getUniqueNames(1)[0];

    connector.tableOperations().create(tableName);
    connector.securityOperations().createLocalUser(USER, new PasswordToken(""));
    connector.securityOperations().grantTablePermission(USER, tableName, TablePermission.READ);
    connector.securityOperations().grantTablePermission(USER, tableName, TablePermission.WRITE);
    connector.securityOperations().changeUserAuthorizations(USER, AuthsIterator.AUTHS);
  }

  @After
  public void tearDown() throws Exception {
    connector.securityOperations().dropLocalUser(USER);
  }

  @Test
  public void testAuthsPresentInIteratorEnvironment()
      throws TableNotFoundException, AccumuloException, AccumuloSecurityException, TableExistsException, InterruptedException {

    runTest(AuthsIterator.AUTHS, false);
  }

  @Test
  public void testAuthsNotPresentInIteratorEnvironment()
      throws TableNotFoundException, AccumuloException, AccumuloSecurityException, TableExistsException, InterruptedException {

    runTest(new Authorizations("B"), true);
  }

  @Test
  public void testEmptyAuthsInIteratorEnvironment()
      throws TableNotFoundException, AccumuloException, AccumuloSecurityException, TableExistsException, InterruptedException {

    runTest(Authorizations.EMPTY, true);
  }

  private void runTest(ScannerBase scanner, Authorizations auths, boolean shouldFail)
      throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    int count = 0;
    for (Map.Entry<Key,Value> entry : scanner) {
      assertEquals(shouldFail ? AuthsIterator.FAIL : AuthsIterator.SUCCESS, entry.getKey().getRow().toString());
      count++;
    }

    assertEquals(1, count);
  }

  private void runTest(Authorizations auths, boolean shouldFail) throws AccumuloSecurityException, AccumuloException, TableNotFoundException {
    Connector userC = getCluster().getConnector(USER, new PasswordToken(("")));
    writeTestMutation(userC);

    IteratorSetting setting = new IteratorSetting(10, AuthsIterator.class);

    Scanner scanner = userC.createScanner(tableName, auths);
    scanner.addScanIterator(setting);

    BatchScanner batchScanner = userC.createBatchScanner(tableName, auths, 1);
    batchScanner.setRanges(Collections.singleton(new Range("1")));
    batchScanner.addScanIterator(setting);

    runTest(scanner, auths, shouldFail);
    runTest(batchScanner, auths, shouldFail);

    scanner.close();
    batchScanner.close();
  }

  private void writeTestMutation(Connector userC) throws TableNotFoundException, MutationsRejectedException {
    BatchWriter batchWriter = userC.createBatchWriter(tableName, new BatchWriterConfig());
    Mutation m = new Mutation("1");
    m.put(new Text("2"), new Text("3"), new Value("".getBytes()));
    batchWriter.addMutation(m);
    batchWriter.flush();
    batchWriter.close();

  }

}
