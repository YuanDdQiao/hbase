/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.replication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.client.replication.TableCFs;
import org.apache.hadoop.hbase.mapreduce.replication.VerifyReplication;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.MultiVersionConcurrencyControl;
import org.apache.hadoop.hbase.regionserver.wal.AbstractFSWAL;
import org.apache.hadoop.hbase.replication.regionserver.Replication;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSource;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSourceInterface;
import org.apache.hadoop.hbase.snapshot.SnapshotTestingUtils;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.testclassification.ReplicationTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.hadoop.hbase.wal.AbstractFSWALProvider;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WALKeyImpl;
import org.apache.hadoop.hbase.wal.WALEdit;
import org.apache.hadoop.mapreduce.Job;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hbase.thirdparty.com.google.common.collect.Lists;
import org.apache.hadoop.hbase.shaded.protobuf.generated.WALProtos;

@Category({ReplicationTests.class, LargeTests.class})
public class TestReplicationSmallTests extends TestReplicationBase {

  private static final Logger LOG = LoggerFactory.getLogger(TestReplicationSmallTests.class);
  private static final String PEER_ID = "2";

  @Rule
  public TestName name = new TestName();

  /**
   * @throws java.lang.Exception
   */
  @Before
  public void setUp() throws Exception {
    // Starting and stopping replication can make us miss new logs,
    // rolling like this makes sure the most recent one gets added to the queue
    for ( JVMClusterUtil.RegionServerThread r :
        utility1.getHBaseCluster().getRegionServerThreads()) {
      utility1.getAdmin().rollWALWriter(r.getRegionServer().getServerName());
    }
    int rowCount = utility1.countRows(tableName);
    utility1.deleteTableData(tableName);
    // truncating the table will send one Delete per row to the slave cluster
    // in an async fashion, which is why we cannot just call deleteTableData on
    // utility2 since late writes could make it to the slave in some way.
    // Instead, we truncate the first table and wait for all the Deletes to
    // make it to the slave.
    Scan scan = new Scan();
    int lastCount = 0;
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for truncate");
      }
      ResultScanner scanner = htable2.getScanner(scan);
      Result[] res = scanner.next(rowCount);
      scanner.close();
      if (res.length != 0) {
        if (res.length < lastCount) {
          i--; // Don't increment timeout if we make progress
        }
        lastCount = res.length;
        LOG.info("Still got " + res.length + " rows");
        Thread.sleep(SLEEP_TIME);
      } else {
        break;
      }
    }
  }

  /**
   * Verify that version and column delete marker types are replicated
   * correctly.
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testDeleteTypes() throws Exception {
    LOG.info("testDeleteTypes");
    final byte[] v1 = Bytes.toBytes("v1");
    final byte[] v2 = Bytes.toBytes("v2");
    final byte[] v3 = Bytes.toBytes("v3");
    htable1 = utility1.getConnection().getTable(tableName);

    long t = EnvironmentEdgeManager.currentTime();
    // create three versions for "row"
    Put put = new Put(row);
    put.addColumn(famName, row, t, v1);
    htable1.put(put);

    put = new Put(row);
    put.addColumn(famName, row, t + 1, v2);
    htable1.put(put);

    put = new Put(row);
    put.addColumn(famName, row, t + 2, v3);
    htable1.put(put);

    Get get = new Get(row);
    get.readAllVersions();
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for put replication");
      }
      Result res = htable2.get(get);
      if (res.size() < 3) {
        LOG.info("Rows not available");
        Thread.sleep(SLEEP_TIME);
      } else {
        assertArrayEquals(CellUtil.cloneValue(res.rawCells()[0]), v3);
        assertArrayEquals(CellUtil.cloneValue(res.rawCells()[1]), v2);
        assertArrayEquals(CellUtil.cloneValue(res.rawCells()[2]), v1);
        break;
      }
    }
    // place a version delete marker (delete last version)
    Delete d = new Delete(row);
    d.addColumn(famName, row, t);
    htable1.delete(d);

    get = new Get(row);
    get.readAllVersions();
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for put replication");
      }
      Result res = htable2.get(get);
      if (res.size() > 2) {
        LOG.info("Version not deleted");
        Thread.sleep(SLEEP_TIME);
      } else {
        assertArrayEquals(CellUtil.cloneValue(res.rawCells()[0]), v3);
        assertArrayEquals(CellUtil.cloneValue(res.rawCells()[1]), v2);
        break;
      }
    }

    // place a column delete marker
    d = new Delete(row);
    d.addColumns(famName, row, t+2);
    htable1.delete(d);

    // now *both* of the remaining version should be deleted
    // at the replica
    get = new Get(row);
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for del replication");
      }
      Result res = htable2.get(get);
      if (res.size() >= 1) {
        LOG.info("Rows not deleted");
        Thread.sleep(SLEEP_TIME);
      } else {
        break;
      }
    }
  }

  /**
   * Add a row, check it's replicated, delete it, check's gone
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testSimplePutDelete() throws Exception {
    LOG.info("testSimplePutDelete");
    Put put = new Put(row);
    put.addColumn(famName, row, row);

    htable1 = utility1.getConnection().getTable(tableName);
    htable1.put(put);

    Get get = new Get(row);
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for put replication");
      }
      Result res = htable2.get(get);
      if (res.isEmpty()) {
        LOG.info("Row not available");
        Thread.sleep(SLEEP_TIME);
      } else {
        assertArrayEquals(res.value(), row);
        break;
      }
    }

    Delete del = new Delete(row);
    htable1.delete(del);

    get = new Get(row);
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for del replication");
      }
      Result res = htable2.get(get);
      if (res.size() >= 1) {
        LOG.info("Row not deleted");
        Thread.sleep(SLEEP_TIME);
      } else {
        break;
      }
    }
  }

  /**
   * Try a small batch upload using the write buffer, check it's replicated
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testSmallBatch() throws Exception {
    LOG.info("testSmallBatch");
    // normal Batch tests
    loadData("", row);

    Scan scan = new Scan();

    ResultScanner scanner1 = htable1.getScanner(scan);
    Result[] res1 = scanner1.next(NB_ROWS_IN_BATCH);
    scanner1.close();
    assertEquals(NB_ROWS_IN_BATCH, res1.length);

    waitForReplication(NB_ROWS_IN_BATCH, NB_RETRIES);
  }

  private void waitForReplication(int expectedRows, int retries) throws IOException, InterruptedException {
    Scan scan;
    for (int i = 0; i < retries; i++) {
      scan = new Scan();
      if (i== retries -1) {
        fail("Waited too much time for normal batch replication");
      }
      ResultScanner scanner = htable2.getScanner(scan);
      Result[] res = scanner.next(expectedRows);
      scanner.close();
      if (res.length != expectedRows) {
        LOG.info("Only got " + res.length + " rows");
        Thread.sleep(SLEEP_TIME);
      } else {
        break;
      }
    }
  }

  private void loadData(String prefix, byte[] row) throws IOException {
    List<Put> puts = new ArrayList<>(NB_ROWS_IN_BATCH);
    for (int i = 0; i < NB_ROWS_IN_BATCH; i++) {
      Put put = new Put(Bytes.toBytes(prefix + Integer.toString(i)));
      put.addColumn(famName, row, row);
      puts.add(put);
    }
    htable1.put(puts);
  }

  /**
   * Test disable/enable replication, trying to insert, make sure nothing's
   * replicated, enable it, the insert should be replicated
   *
   * @throws Exception
   */
  @Test(timeout = 300000)
  public void testDisableEnable() throws Exception {

    // Test disabling replication
    hbaseAdmin.disableReplicationPeer(PEER_ID);

    byte[] rowkey = Bytes.toBytes("disable enable");
    Put put = new Put(rowkey);
    put.addColumn(famName, row, row);
    htable1.put(put);

    Get get = new Get(rowkey);
    for (int i = 0; i < NB_RETRIES; i++) {
      Result res = htable2.get(get);
      if (res.size() >= 1) {
        fail("Replication wasn't disabled");
      } else {
        LOG.info("Row not replicated, let's wait a bit more...");
        Thread.sleep(SLEEP_TIME);
      }
    }

    // Test enable replication
    hbaseAdmin.enableReplicationPeer(PEER_ID);

    for (int i = 0; i < NB_RETRIES; i++) {
      Result res = htable2.get(get);
      if (res.isEmpty()) {
        LOG.info("Row not available");
        Thread.sleep(SLEEP_TIME);
      } else {
        assertArrayEquals(res.value(), row);
        return;
      }
    }
    fail("Waited too much time for put replication");
  }

  /**
   * Integration test for TestReplicationAdmin, removes and re-add a peer
   * cluster
   *
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testAddAndRemoveClusters() throws Exception {
    LOG.info("testAddAndRemoveClusters");
    hbaseAdmin.removeReplicationPeer(PEER_ID);
    Thread.sleep(SLEEP_TIME);
    byte[] rowKey = Bytes.toBytes("Won't be replicated");
    Put put = new Put(rowKey);
    put.addColumn(famName, row, row);
    htable1.put(put);

    Get get = new Get(rowKey);
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i == NB_RETRIES-1) {
        break;
      }
      Result res = htable2.get(get);
      if (res.size() >= 1) {
        fail("Not supposed to be replicated");
      } else {
        LOG.info("Row not replicated, let's wait a bit more...");
        Thread.sleep(SLEEP_TIME);
      }
    }
    ReplicationPeerConfig rpc = new ReplicationPeerConfig();
    rpc.setClusterKey(utility2.getClusterKey());
    hbaseAdmin.addReplicationPeer(PEER_ID, rpc);
    Thread.sleep(SLEEP_TIME);
    rowKey = Bytes.toBytes("do rep");
    put = new Put(rowKey);
    put.addColumn(famName, row, row);
    LOG.info("Adding new row");
    htable1.put(put);

    get = new Get(rowKey);
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i==NB_RETRIES-1) {
        fail("Waited too much time for put replication");
      }
      Result res = htable2.get(get);
      if (res.isEmpty()) {
        LOG.info("Row not available");
        Thread.sleep(SLEEP_TIME*i);
      } else {
        assertArrayEquals(res.value(), row);
        break;
      }
    }
  }


  /**
   * Do a more intense version testSmallBatch, one  that will trigger
   * wal rolling and other non-trivial code paths
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testLoading() throws Exception {
    LOG.info("Writing out rows to table1 in testLoading");
    List<Put> puts = new ArrayList<>(NB_ROWS_IN_BIG_BATCH);
    for (int i = 0; i < NB_ROWS_IN_BIG_BATCH; i++) {
      Put put = new Put(Bytes.toBytes(i));
      put.addColumn(famName, row, row);
      puts.add(put);
    }
    // The puts will be iterated through and flushed only when the buffer
    // size is reached.
    htable1.put(puts);

    Scan scan = new Scan();

    ResultScanner scanner = htable1.getScanner(scan);
    Result[] res = scanner.next(NB_ROWS_IN_BIG_BATCH);
    scanner.close();

    assertEquals(NB_ROWS_IN_BIG_BATCH, res.length);

    LOG.info("Looking in table2 for replicated rows in testLoading");
    long start = System.currentTimeMillis();
    // Retry more than NB_RETRIES.  As it was, retries were done in 5 seconds and we'd fail
    // sometimes.
    final long retries = NB_RETRIES * 10;
    for (int i = 0; i < retries; i++) {
      scan = new Scan();
      scanner = htable2.getScanner(scan);
      res = scanner.next(NB_ROWS_IN_BIG_BATCH);
      scanner.close();
      if (res.length != NB_ROWS_IN_BIG_BATCH) {
        if (i == retries - 1) {
          int lastRow = -1;
          for (Result result : res) {
            int currentRow = Bytes.toInt(result.getRow());
            for (int row = lastRow+1; row < currentRow; row++) {
              LOG.error("Row missing: " + row);
            }
            lastRow = currentRow;
          }
          LOG.error("Last row: " + lastRow);
          fail("Waited too much time for normal batch replication, " +
            res.length + " instead of " + NB_ROWS_IN_BIG_BATCH + "; waited=" +
            (System.currentTimeMillis() - start) + "ms");
        } else {
          LOG.info("Only got " + res.length + " rows... retrying");
          Thread.sleep(SLEEP_TIME);
        }
      } else {
        break;
      }
    }
  }

  /**
   * Do a small loading into a table, make sure the data is really the same,
   * then run the VerifyReplication job to check the results. Do a second
   * comparison where all the cells are different.
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testVerifyRepJob() throws Exception {
    // Populate the tables, at the same time it guarantees that the tables are
    // identical since it does the check
    testSmallBatch();

    String[] args = new String[] {PEER_ID, tableName.getNameAsString()};
    runVerifyReplication(args, NB_ROWS_IN_BATCH, 0);

    Scan scan = new Scan();
    ResultScanner rs = htable2.getScanner(scan);
    Put put = null;
    for (Result result : rs) {
      put = new Put(result.getRow());
      Cell firstVal = result.rawCells()[0];
      put.addColumn(CellUtil.cloneFamily(firstVal), CellUtil.cloneQualifier(firstVal),
          Bytes.toBytes("diff data"));
      htable2.put(put);
    }
    Delete delete = new Delete(put.getRow());
    htable2.delete(delete);
    runVerifyReplication(args, 0, NB_ROWS_IN_BATCH);
  }

  /**
   * Load a row into a table, make sure the data is really the same,
   * delete the row, make sure the delete marker is replicated,
   * run verify replication with and without raw to check the results.
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testVerifyRepJobWithRawOptions() throws Exception {
    LOG.info(name.getMethodName());

    final TableName tableName = TableName.valueOf(name.getMethodName());
    byte[] familyname = Bytes.toBytes("fam_raw");
    byte[] row = Bytes.toBytes("row_raw");

    Table lHtable1 = null;
    Table lHtable2 = null;

    try {
      ColumnFamilyDescriptor fam = ColumnFamilyDescriptorBuilder.newBuilder(familyname)
          .setMaxVersions(100).setScope(HConstants.REPLICATION_SCOPE_GLOBAL).build();
      TableDescriptor table = TableDescriptorBuilder.newBuilder(tableName).addColumnFamily(fam).build();
      scopes = new TreeMap<>(Bytes.BYTES_COMPARATOR);
      for (ColumnFamilyDescriptor f : table.getColumnFamilies()) {
        scopes.put(f.getName(), f.getScope());
      }

      Connection connection1 = ConnectionFactory.createConnection(conf1);
      Connection connection2 = ConnectionFactory.createConnection(conf2);
      try (Admin admin1 = connection1.getAdmin()) {
        admin1.createTable(table, HBaseTestingUtility.KEYS_FOR_HBA_CREATE_TABLE);
      }
      try (Admin admin2 = connection2.getAdmin()) {
        admin2.createTable(table, HBaseTestingUtility.KEYS_FOR_HBA_CREATE_TABLE);
      }
      utility1.waitUntilAllRegionsAssigned(tableName);
      utility2.waitUntilAllRegionsAssigned(tableName);

      lHtable1 = utility1.getConnection().getTable(tableName);
      lHtable2 = utility2.getConnection().getTable(tableName);

      Put put = new Put(row);
      put.addColumn(familyname, row, row);
      lHtable1.put(put);

      Get get = new Get(row);
      for (int i = 0; i < NB_RETRIES; i++) {
        if (i==NB_RETRIES-1) {
          fail("Waited too much time for put replication");
        }
        Result res = lHtable2.get(get);
        if (res.isEmpty()) {
          LOG.info("Row not available");
          Thread.sleep(SLEEP_TIME);
        } else {
          assertArrayEquals(res.value(), row);
          break;
        }
      }

      Delete del = new Delete(row);
      lHtable1.delete(del);

      get = new Get(row);
      for (int i = 0; i < NB_RETRIES; i++) {
        if (i==NB_RETRIES-1) {
          fail("Waited too much time for del replication");
        }
        Result res = lHtable2.get(get);
        if (res.size() >= 1) {
          LOG.info("Row not deleted");
          Thread.sleep(SLEEP_TIME);
        } else {
          break;
        }
      }

      // Checking verifyReplication for the default behavior.
      String[] argsWithoutRaw = new String[] {PEER_ID, tableName.getNameAsString()};
      runVerifyReplication(argsWithoutRaw, 0, 0);

      // Checking verifyReplication with raw
      String[] argsWithRawAsTrue = new String[] {"--raw", PEER_ID, tableName.getNameAsString()};
      runVerifyReplication(argsWithRawAsTrue, 1, 0);
    } finally {
      if (lHtable1 != null) {
        lHtable1.close();
      }
      if (lHtable2 != null) {
        lHtable2.close();
      }
    }
  }

  private void runVerifyReplication(String[] args, int expectedGoodRows, int expectedBadRows)
      throws IOException, InterruptedException, ClassNotFoundException {
    Job job = new VerifyReplication().createSubmittableJob(new Configuration(conf1), args);
    if (job == null) {
      fail("Job wasn't created, see the log");
    }
    if (!job.waitForCompletion(true)) {
      fail("Job failed, see the log");
    }
    assertEquals(expectedGoodRows, job.getCounters().
        findCounter(VerifyReplication.Verifier.Counters.GOODROWS).getValue());
    assertEquals(expectedBadRows, job.getCounters().
        findCounter(VerifyReplication.Verifier.Counters.BADROWS).getValue());
  }

  @Test(timeout=300000)
  // VerifyReplication should honor versions option
  public void testHBase14905() throws Exception {
    // normal Batch tests
    byte[] qualifierName = Bytes.toBytes("f1");
    Put put = new Put(Bytes.toBytes("r1"));
    put.addColumn(famName, qualifierName, Bytes.toBytes("v1002"));
    htable1.put(put);
    put.addColumn(famName, qualifierName, Bytes.toBytes("v1001"));
    htable1.put(put);
    put.addColumn(famName, qualifierName, Bytes.toBytes("v1112"));
    htable1.put(put);

    Scan scan = new Scan();
    scan.readVersions(100);
    ResultScanner scanner1 = htable1.getScanner(scan);
    Result[] res1 = scanner1.next(1);
    scanner1.close();

    assertEquals(1, res1.length);
    assertEquals(3, res1[0].getColumnCells(famName, qualifierName).size());

    for (int i = 0; i < NB_RETRIES; i++) {
      scan = new Scan();
      scan.readVersions(100);
      scanner1 = htable2.getScanner(scan);
      res1 = scanner1.next(1);
      scanner1.close();
      if (res1.length != 1) {
        LOG.info("Only got " + res1.length + " rows");
        Thread.sleep(SLEEP_TIME);
      } else {
        int cellNumber = res1[0].getColumnCells(famName, Bytes.toBytes("f1")).size();
        if (cellNumber != 3) {
          LOG.info("Only got " + cellNumber + " cells");
          Thread.sleep(SLEEP_TIME);
        } else {
          break;
        }
      }
      if (i == NB_RETRIES-1) {
        fail("Waited too much time for normal batch replication");
      }
    }

    put.addColumn(famName, qualifierName, Bytes.toBytes("v1111"));
    htable2.put(put);
    put.addColumn(famName, qualifierName, Bytes.toBytes("v1112"));
    htable2.put(put);

    scan = new Scan();
    scan.readVersions(100);
    scanner1 = htable2.getScanner(scan);
    res1 = scanner1.next(NB_ROWS_IN_BATCH);
    scanner1.close();

    assertEquals(1, res1.length);
    assertEquals(5, res1[0].getColumnCells(famName, qualifierName).size());

    String[] args = new String[] {"--versions=100", PEER_ID, tableName.getNameAsString()};
    runVerifyReplication(args, 0, 1);
  }

  @Test(timeout=300000)
  // VerifyReplication should honor versions option
  public void testVersionMismatchHBase14905() throws Exception {
    // normal Batch tests
    byte[] qualifierName = Bytes.toBytes("f1");
    Put put = new Put(Bytes.toBytes("r1"));
    long ts = System.currentTimeMillis();
    put.addColumn(famName, qualifierName, ts + 1, Bytes.toBytes("v1"));
    htable1.put(put);
    put.addColumn(famName, qualifierName, ts + 2, Bytes.toBytes("v2"));
    htable1.put(put);
    put.addColumn(famName, qualifierName, ts + 3, Bytes.toBytes("v3"));
    htable1.put(put);

    Scan scan = new Scan();
    scan.readVersions(100);
    ResultScanner scanner1 = htable1.getScanner(scan);
    Result[] res1 = scanner1.next(1);
    scanner1.close();

    assertEquals(1, res1.length);
    assertEquals(3, res1[0].getColumnCells(famName, qualifierName).size());

    for (int i = 0; i < NB_RETRIES; i++) {
      scan = new Scan();
      scan.readVersions(100);
      scanner1 = htable2.getScanner(scan);
      res1 = scanner1.next(1);
      scanner1.close();
      if (res1.length != 1) {
        LOG.info("Only got " + res1.length + " rows");
        Thread.sleep(SLEEP_TIME);
      } else {
        int cellNumber = res1[0].getColumnCells(famName, Bytes.toBytes("f1")).size();
        if (cellNumber != 3) {
          LOG.info("Only got " + cellNumber + " cells");
          Thread.sleep(SLEEP_TIME);
        } else {
          break;
        }
      }
      if (i == NB_RETRIES-1) {
        fail("Waited too much time for normal batch replication");
      }
    }

    try {
      // Disabling replication and modifying the particular version of the cell to validate the feature.
      hbaseAdmin.disableReplicationPeer(PEER_ID);
      Put put2 = new Put(Bytes.toBytes("r1"));
      put2.addColumn(famName, qualifierName, ts +2, Bytes.toBytes("v99"));
      htable2.put(put2);

      scan = new Scan();
      scan.readVersions(100);
      scanner1 = htable2.getScanner(scan);
      res1 = scanner1.next(NB_ROWS_IN_BATCH);
      scanner1.close();
      assertEquals(1, res1.length);
      assertEquals(3, res1[0].getColumnCells(famName, qualifierName).size());

      String[] args = new String[] {"--versions=100", PEER_ID, tableName.getNameAsString()};
      runVerifyReplication(args, 0, 1);
      }
    finally {
      hbaseAdmin.enableReplicationPeer(PEER_ID);
    }
  }

  /**
   * Test for HBASE-9038, Replication.scopeWALEdits would NPE if it wasn't filtering out
   * the compaction WALEdit
   * @throws Exception
   */
  @Test(timeout=300000)
  public void testCompactionWALEdits() throws Exception {
    WALProtos.CompactionDescriptor compactionDescriptor =
        WALProtos.CompactionDescriptor.getDefaultInstance();
    RegionInfo hri = RegionInfoBuilder.newBuilder(htable1.getName())
        .setStartKey(HConstants.EMPTY_START_ROW)
        .setEndKey(HConstants.EMPTY_END_ROW)
        .build();
    WALEdit edit = WALEdit.createCompaction(hri, compactionDescriptor);
    Replication.scopeWALEdits(new WALKeyImpl(), edit,
      htable1.getConfiguration(), null);
  }

  /**
   * Test for HBASE-8663
   * Create two new Tables with colfamilies enabled for replication then run
   * ReplicationAdmin.listReplicated(). Finally verify the table:colfamilies. Note:
   * TestReplicationAdmin is a better place for this testing but it would need mocks.
   * @throws Exception
   */
  @Test(timeout = 300000)
  public void testVerifyListReplicatedTable() throws Exception {
    LOG.info("testVerifyListReplicatedTable");

    final String tName = "VerifyListReplicated_";
    final String colFam = "cf1";
    final int numOfTables = 3;

    Admin hadmin = utility1.getAdmin();

    // Create Tables
    for (int i = 0; i < numOfTables; i++) {
      hadmin.createTable(TableDescriptorBuilder.newBuilder(TableName.valueOf(tName + i))
          .addColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(colFam))
              .setScope(HConstants.REPLICATION_SCOPE_GLOBAL).build())
          .build());
    }

    // verify the result
    List<TableCFs> replicationColFams = hbaseAdmin.listReplicatedTableCFs();
    int[] match = new int[numOfTables]; // array of 3 with init value of zero

    for (int i = 0; i < replicationColFams.size(); i++) {
      TableCFs replicationEntry = replicationColFams.get(i);
      String tn = replicationEntry.getTable().getNameAsString();
      if (tn.startsWith(tName) && replicationEntry.getColumnFamilyMap().containsKey(colFam)) {
        int m = Integer.parseInt(tn.substring(tn.length() - 1)); // get the last digit
        match[m]++; // should only increase once
      }
    }

    // check the matching result
    for (int i = 0; i < match.length; i++) {
      assertTrue("listReplicated() does not match table " + i, (match[i] == 1));
    }

    // drop tables
    for (int i = 0; i < numOfTables; i++) {
      TableName tableName = TableName.valueOf(tName + i);
      hadmin.disableTable(tableName);
      hadmin.deleteTable(tableName);
    }

    hadmin.close();
  }

  /**
   *  Test for HBase-15259 WALEdits under replay will also be replicated
   * */
  @Test
  public void testReplicationInReplay() throws Exception {
    final TableName tableName = htable1.getName();

    HRegion region = utility1.getMiniHBaseCluster().getRegions(tableName).get(0);
    RegionInfo hri = region.getRegionInfo();
    NavigableMap<byte[], Integer> scopes = new TreeMap<>(Bytes.BYTES_COMPARATOR);
    for (byte[] fam : htable1.getDescriptor().getColumnFamilyNames()) {
      scopes.put(fam, 1);
    }
    final MultiVersionConcurrencyControl mvcc = new MultiVersionConcurrencyControl();
    int index = utility1.getMiniHBaseCluster().getServerWith(hri.getRegionName());
    WAL wal = utility1.getMiniHBaseCluster().getRegionServer(index).getWAL(region.getRegionInfo());
    final byte[] rowName = Bytes.toBytes("testReplicationInReplay");
    final byte[] qualifier = Bytes.toBytes("q");
    final byte[] value = Bytes.toBytes("v");
    WALEdit edit = new WALEdit(true);
    long now = EnvironmentEdgeManager.currentTime();
    edit.add(new KeyValue(rowName, famName, qualifier,
      now, value));
    WALKeyImpl walKey = new WALKeyImpl(hri.getEncodedNameAsBytes(), tableName, now, mvcc, scopes);
    wal.append(hri, walKey, edit, true);
    wal.sync();

    Get get = new Get(rowName);
    for (int i = 0; i < NB_RETRIES; i++) {
      if (i == NB_RETRIES-1) {
        break;
      }
      Result res = htable2.get(get);
      if (res.size() >= 1) {
        fail("Not supposed to be replicated for " + Bytes.toString(res.getRow()));
      } else {
        LOG.info("Row not replicated, let's wait a bit more...");
        Thread.sleep(SLEEP_TIME);
      }
    }
  }

  @Test(timeout=300000)
  public void testVerifyReplicationPrefixFiltering() throws Exception {
    final byte[] prefixRow = Bytes.toBytes("prefixrow");
    final byte[] prefixRow2 = Bytes.toBytes("secondrow");
    loadData("prefixrow", prefixRow);
    loadData("secondrow", prefixRow2);
    loadData("aaa", row);
    loadData("zzz", row);
    waitForReplication(NB_ROWS_IN_BATCH * 4, NB_RETRIES * 4);
    String[] args = new String[] {"--row-prefixes=prefixrow,secondrow", PEER_ID,
        tableName.getNameAsString()};
    runVerifyReplication(args, NB_ROWS_IN_BATCH *2, 0);
  }

  @Test(timeout = 300000)
  public void testVerifyReplicationSnapshotArguments() {
    String[] args =
        new String[] { "--sourceSnapshotName=snapshot1", "2", tableName.getNameAsString() };
    assertFalse(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));

    args = new String[] { "--sourceSnapshotTmpDir=tmp", "2", tableName.getNameAsString() };
    assertFalse(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));

    args = new String[] { "--sourceSnapshotName=snapshot1", "--sourceSnapshotTmpDir=tmp", "2",
        tableName.getNameAsString() };
    assertTrue(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));

    args = new String[] { "--peerSnapshotName=snapshot1", "2", tableName.getNameAsString() };
    assertFalse(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));

    args = new String[] { "--peerSnapshotTmpDir=/tmp/", "2", tableName.getNameAsString() };
    assertFalse(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));

    args = new String[] { "--peerSnapshotName=snapshot1", "--peerSnapshotTmpDir=/tmp/",
        "--peerFSAddress=tempfs", "--peerHBaseRootAddress=hdfs://tempfs:50070/hbase/", "2",
        tableName.getNameAsString() };
    assertTrue(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));

    args = new String[] { "--sourceSnapshotName=snapshot1", "--sourceSnapshotTmpDir=/tmp/",
        "--peerSnapshotName=snapshot2", "--peerSnapshotTmpDir=/tmp/", "--peerFSAddress=tempfs",
        "--peerHBaseRootAddress=hdfs://tempfs:50070/hbase/", "2", tableName.getNameAsString() };

    assertTrue(Lists.newArrayList(args).toString(), new VerifyReplication().doCommandLine(args));
  }

  private void checkRestoreTmpDir(Configuration conf, String restoreTmpDir, int expectedCount)
      throws IOException {
    FileSystem fs = FileSystem.get(conf);
    FileStatus[] subDirectories = fs.listStatus(new Path(restoreTmpDir));
    assertNotNull(subDirectories);
    assertEquals(subDirectories.length, expectedCount);
    for (int i = 0; i < expectedCount; i++) {
      assertTrue(subDirectories[i].isDirectory());
    }
  }

  @Test(timeout = 300000)
  public void testVerifyReplicationWithSnapshotSupport() throws Exception {
    // Populate the tables, at the same time it guarantees that the tables are
    // identical since it does the check
    testSmallBatch();

    // Take source and target tables snapshot
    Path rootDir = FSUtils.getRootDir(conf1);
    FileSystem fs = rootDir.getFileSystem(conf1);
    String sourceSnapshotName = "sourceSnapshot-" + System.currentTimeMillis();
    SnapshotTestingUtils.createSnapshotAndValidate(utility1.getAdmin(), tableName,
      new String(famName), sourceSnapshotName, rootDir, fs, true);

    // Take target snapshot
    Path peerRootDir = FSUtils.getRootDir(conf2);
    FileSystem peerFs = peerRootDir.getFileSystem(conf2);
    String peerSnapshotName = "peerSnapshot-" + System.currentTimeMillis();
    SnapshotTestingUtils.createSnapshotAndValidate(utility2.getAdmin(), tableName,
      new String(famName), peerSnapshotName, peerRootDir, peerFs, true);

    String peerFSAddress = peerFs.getUri().toString();
    String temPath1 = utility1.getRandomDir().toString();
    String temPath2 = "/tmp2";

    String[] args = new String[] { "--sourceSnapshotName=" + sourceSnapshotName,
        "--sourceSnapshotTmpDir=" + temPath1, "--peerSnapshotName=" + peerSnapshotName,
        "--peerSnapshotTmpDir=" + temPath2, "--peerFSAddress=" + peerFSAddress,
        "--peerHBaseRootAddress=" + FSUtils.getRootDir(conf2), "2", tableName.getNameAsString() };

    Job job = new VerifyReplication().createSubmittableJob(conf1, args);
    if (job == null) {
      fail("Job wasn't created, see the log");
    }
    if (!job.waitForCompletion(true)) {
      fail("Job failed, see the log");
    }
    assertEquals(NB_ROWS_IN_BATCH,
      job.getCounters().findCounter(VerifyReplication.Verifier.Counters.GOODROWS).getValue());
    assertEquals(0,
      job.getCounters().findCounter(VerifyReplication.Verifier.Counters.BADROWS).getValue());

    checkRestoreTmpDir(conf1, temPath1, 1);
    checkRestoreTmpDir(conf2, temPath2, 1);

    Scan scan = new Scan();
    ResultScanner rs = htable2.getScanner(scan);
    Put put = null;
    for (Result result : rs) {
      put = new Put(result.getRow());
      Cell firstVal = result.rawCells()[0];
      put.addColumn(CellUtil.cloneFamily(firstVal), CellUtil.cloneQualifier(firstVal),
        Bytes.toBytes("diff data"));
      htable2.put(put);
    }
    Delete delete = new Delete(put.getRow());
    htable2.delete(delete);

    sourceSnapshotName = "sourceSnapshot-" + System.currentTimeMillis();
    SnapshotTestingUtils.createSnapshotAndValidate(utility1.getAdmin(), tableName,
      new String(famName), sourceSnapshotName, rootDir, fs, true);

    peerSnapshotName = "peerSnapshot-" + System.currentTimeMillis();
    SnapshotTestingUtils.createSnapshotAndValidate(utility2.getAdmin(), tableName,
      new String(famName), peerSnapshotName, peerRootDir, peerFs, true);

    args = new String[] { "--sourceSnapshotName=" + sourceSnapshotName,
        "--sourceSnapshotTmpDir=" + temPath1, "--peerSnapshotName=" + peerSnapshotName,
        "--peerSnapshotTmpDir=" + temPath2, "--peerFSAddress=" + peerFSAddress,
        "--peerHBaseRootAddress=" + FSUtils.getRootDir(conf2), "2", tableName.getNameAsString() };

    job = new VerifyReplication().createSubmittableJob(conf1, args);
    if (job == null) {
      fail("Job wasn't created, see the log");
    }
    if (!job.waitForCompletion(true)) {
      fail("Job failed, see the log");
    }
    assertEquals(0,
      job.getCounters().findCounter(VerifyReplication.Verifier.Counters.GOODROWS).getValue());
    assertEquals(NB_ROWS_IN_BATCH,
      job.getCounters().findCounter(VerifyReplication.Verifier.Counters.BADROWS).getValue());

    checkRestoreTmpDir(conf1, temPath1, 2);
    checkRestoreTmpDir(conf2, temPath2, 2);
  }

  @Test
  public void testEmptyWALRecovery() throws Exception {
    final int numRs = utility1.getHBaseCluster().getRegionServerThreads().size();

    // for each RS, create an empty wal with same walGroupId
    final List<Path> emptyWalPaths = new ArrayList<>();
    long ts = System.currentTimeMillis();
    for (int i = 0; i < numRs; i++) {
      RegionInfo regionInfo =
          utility1.getHBaseCluster().getRegions(htable1.getName()).get(0).getRegionInfo();
      WAL wal = utility1.getHBaseCluster().getRegionServer(i).getWAL(regionInfo);
      Path currentWalPath = AbstractFSWALProvider.getCurrentFileName(wal);
      String walGroupId = AbstractFSWALProvider.getWALPrefixFromWALName(currentWalPath.getName());
      Path emptyWalPath = new Path(utility1.getDataTestDir(), walGroupId + "." + ts);
      utility1.getTestFileSystem().create(emptyWalPath).close();
      emptyWalPaths.add(emptyWalPath);
    }

    // inject our empty wal into the replication queue, and then roll the original wal, which
    // enqueues a new wal behind our empty wal. We must roll the wal here as now we use the WAL to
    // determine if the file being replicated currently is still opened for write, so just inject a
    // new wal to the replication queue does not mean the previous file is closed.
    for (int i = 0; i < numRs; i++) {
      HRegionServer hrs = utility1.getHBaseCluster().getRegionServer(i);
      Replication replicationService = (Replication) hrs.getReplicationSourceService();
      replicationService.preLogRoll(null, emptyWalPaths.get(i));
      replicationService.postLogRoll(null, emptyWalPaths.get(i));
      RegionInfo regionInfo =
          utility1.getHBaseCluster().getRegions(htable1.getName()).get(0).getRegionInfo();
      WAL wal = hrs.getWAL(regionInfo);
      wal.rollWriter(true);
    }

    // ReplicationSource should advance past the empty wal, or else the test will fail
    waitForLogAdvance(numRs);

    // we're now writing to the new wal
    // if everything works, the source should've stopped reading from the empty wal, and start
    // replicating from the new wal
    testSimplePutDelete();
  }

  /**
   * Waits until there is only one log(the current writing one) in the replication queue
   * @param numRs number of regionservers
   */
  private void waitForLogAdvance(int numRs) throws Exception {
    Waiter.waitFor(conf1, 10000, new Waiter.Predicate<Exception>() {
      @Override
      public boolean evaluate() throws Exception {
        for (int i = 0; i < numRs; i++) {
          HRegionServer hrs = utility1.getHBaseCluster().getRegionServer(i);
          RegionInfo regionInfo =
              utility1.getHBaseCluster().getRegions(htable1.getName()).get(0).getRegionInfo();
          WAL wal = hrs.getWAL(regionInfo);
          Path currentFile = ((AbstractFSWAL<?>) wal).getCurrentFileName();
          Replication replicationService = (Replication) utility1.getHBaseCluster()
              .getRegionServer(i).getReplicationSourceService();
          for (ReplicationSourceInterface rsi : replicationService.getReplicationManager()
              .getSources()) {
            ReplicationSource source = (ReplicationSource) rsi;
            if (!currentFile.equals(source.getCurrentPath())) {
              return false;
            }
          }
        }
        return true;
      }
    });
  }
}
