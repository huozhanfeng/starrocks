// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.planner;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.Analyzer;
import com.starrocks.analysis.BrokerDesc;
import com.starrocks.analysis.DataDescription;
import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.common.UserException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.load.BrokerFileGroup;
import com.starrocks.qe.ConnectContext;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TBrokerFileStatus;
import com.starrocks.thrift.TBrokerRangeDesc;
import com.starrocks.thrift.TScanRangeLocations;
import com.starrocks.thrift.TUniqueId;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class FileScanNodeTest {
    private long jobId;
    private long txnId;
    private TUniqueId loadId;
    private BrokerDesc brokerDesc;

    // config
    private int loadParallelInstanceNum;

    // backends
    private ImmutableMap<Long, Backend> idToBackend;

    @Mocked
    Partition partition;

    @Before
    public void setUp() {
        jobId = 1L;
        txnId = 2L;
        loadId = new TUniqueId(3, 4);
        brokerDesc = new BrokerDesc("broker0", null);

        loadParallelInstanceNum = Config.load_parallel_instance_num;

        // backends
        Map<Long, Backend> idToBackendTmp = Maps.newHashMap();
        Backend b1 = new Backend(0L, "host0", 9050);
        b1.setAlive(true);
        idToBackendTmp.put(0L, b1);
        Backend b2 = new Backend(1L, "host1", 9050);
        b2.setAlive(true);
        idToBackendTmp.put(1L, b2);
        Backend b3 = new Backend(2L, "host2", 9050);
        b3.setAlive(true);
        idToBackendTmp.put(2L, b3);
        idToBackend = ImmutableMap.copyOf(idToBackendTmp);
    }

    @Test
    public void testCreateScanRangeLocations(@Mocked Catalog catalog, @Mocked SystemInfoService systemInfoService,
                                             @Injectable Database db, @Injectable OlapTable table)
            throws UserException {
        // table schema
        List<Column> columns = Lists.newArrayList();
        Column c1 = new Column("c1", Type.BIGINT, true);
        columns.add(c1);
        Column c2 = new Column("c2", Type.BIGINT, true);
        columns.add(c2);
        List<String> columnNames = Lists.newArrayList("c1", "c2");

        new Expectations() {
            {
                Catalog.getCurrentSystemInfo();
                result = systemInfoService;
                systemInfoService.getIdToBackend();
                result = idToBackend;
                table.getBaseSchema();
                result = columns;
                table.getFullSchema();
                result = columns;
                table.getPartitions();
                minTimes = 0;
                result = Arrays.asList(partition);
                partition.getId();
                minTimes = 0;
                result = 0;
                table.getColumn("c1");
                result = columns.get(0);
                table.getColumn("c2");
                result = columns.get(1);
            }
        };

        // case 0
        // 2 csv files: file1 512M, file2 256M
        // result: 3 ranges. file1 2 ranges, file2 1 range

        // file groups
        List<BrokerFileGroup> fileGroups = Lists.newArrayList();
        List<String> files = Lists.newArrayList("hdfs://127.0.0.1:9001/file1", "hdfs://127.0.0.1:9001/file2");
        DataDescription desc =
                new DataDescription("testTable", null, files, columnNames, null, null, null, false, null);
        BrokerFileGroup brokerFileGroup = new BrokerFileGroup(desc);
        Deencapsulation.setField(brokerFileGroup, "columnSeparator", "\t");
        Deencapsulation.setField(brokerFileGroup, "rowDelimiter", "\n");
        fileGroups.add(brokerFileGroup);

        // file status
        List<List<TBrokerFileStatus>> fileStatusesList = Lists.newArrayList();
        List<TBrokerFileStatus> fileStatusList = Lists.newArrayList();
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file1", false, 536870912, true));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file2", false, 268435456, true));
        fileStatusesList.add(fileStatusList);

        Analyzer analyzer = new Analyzer(Catalog.getCurrentCatalog(), new ConnectContext());
        DescriptorTable descTable = analyzer.getDescTbl();
        TupleDescriptor tupleDesc = descTable.createTupleDescriptor("DestTableTuple");
        FileScanNode scanNode = new FileScanNode(new PlanNodeId(0), tupleDesc, "FileScanNode", fileStatusesList, 2);
        scanNode.setLoadInfo(jobId, txnId, table, brokerDesc, fileGroups, true, loadParallelInstanceNum);
        scanNode.setUseVectorized(true);
        scanNode.init(analyzer);
        scanNode.finalize(analyzer);

        // check
        List<TScanRangeLocations> locationsList = scanNode.getScanRangeLocations(0);
        Assert.assertEquals(3, locationsList.size());
        int file1RangesNum = 0;
        int file2RangesNum = 0;
        for (TScanRangeLocations locations : locationsList) {
            TBrokerRangeDesc rangeDesc = locations.scan_range.broker_scan_range.ranges.get(0);
            Assert.assertEquals(268435456, rangeDesc.size);
            if (rangeDesc.path.endsWith("file1")) {
                ++file1RangesNum;
            } else if (rangeDesc.path.endsWith("file2")) {
                ++file2RangesNum;
            }
        }
        Assert.assertEquals(2, file1RangesNum);
        Assert.assertEquals(1, file2RangesNum);

        // case 1
        // 4 parquet files
        // result: 3 ranges. 2 files in one range and 1 file in every other range

        // file groups
        fileGroups = Lists.newArrayList();
        files = Lists.newArrayList("hdfs://127.0.0.1:9001/file1", "hdfs://127.0.0.1:9001/file2",
                "hdfs://127.0.0.1:9001/file3", "hdfs://127.0.0.1:9001/file4");
        desc = new DataDescription("testTable", null, files, columnNames, null, null, "parquet", false, null);
        brokerFileGroup = new BrokerFileGroup(desc);
        Deencapsulation.setField(brokerFileGroup, "columnSeparator", "\t");
        Deencapsulation.setField(brokerFileGroup, "rowDelimiter", "\n");
        Deencapsulation.setField(brokerFileGroup, "fileFormat", "parquet");
        fileGroups.add(brokerFileGroup);

        // file status
        fileStatusesList = Lists.newArrayList();
        fileStatusList = Lists.newArrayList();
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file1", false, 268435454, false));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file2", false, 268435453, false));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file3", false, 268435452, false));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file4", false, 268435451, false));
        fileStatusesList.add(fileStatusList);

        analyzer = new Analyzer(Catalog.getCurrentCatalog(), new ConnectContext());
        descTable = analyzer.getDescTbl();
        tupleDesc = descTable.createTupleDescriptor("DestTableTuple");
        scanNode = new FileScanNode(new PlanNodeId(0), tupleDesc, "FileScanNode", fileStatusesList, 4);
        scanNode.setLoadInfo(jobId, txnId, table, brokerDesc, fileGroups, true, loadParallelInstanceNum);
        scanNode.setUseVectorized(true);
        scanNode.init(analyzer);
        scanNode.finalize(analyzer);

        // check
        locationsList = scanNode.getScanRangeLocations(0);
        Assert.assertEquals(3, locationsList.size());
        for (TScanRangeLocations locations : locationsList) {
            List<TBrokerRangeDesc> rangeDescs = locations.scan_range.broker_scan_range.ranges;
            Assert.assertTrue(rangeDescs.size() == 1 || rangeDescs.size() == 2);
        }

        // case 2
        // 2 file groups
        // result: 4 ranges. group1 3 ranges, group2 1 range

        // file groups
        fileGroups = Lists.newArrayList();
        files = Lists.newArrayList("hdfs://127.0.0.1:9001/file1", "hdfs://127.0.0.1:9001/file2",
                "hdfs://127.0.0.1:9001/file3");
        desc = new DataDescription("testTable", null, files, columnNames, null, null, "parquet", false, null);
        brokerFileGroup = new BrokerFileGroup(desc);
        Deencapsulation.setField(brokerFileGroup, "columnSeparator", "\t");
        Deencapsulation.setField(brokerFileGroup, "rowDelimiter", "\n");
        Deencapsulation.setField(brokerFileGroup, "fileFormat", "parquet");
        fileGroups.add(brokerFileGroup);

        List<String> files2 = Lists.newArrayList("hdfs://127.0.0.1:9001/file4", "hdfs://127.0.0.1:9001/file5");
        DataDescription desc2 =
                new DataDescription("testTable", null, files2, columnNames, null, null, null, false, null);
        BrokerFileGroup brokerFileGroup2 = new BrokerFileGroup(desc2);
        Deencapsulation.setField(brokerFileGroup2, "columnSeparator", "\t");
        Deencapsulation.setField(brokerFileGroup2, "rowDelimiter", "\n");
        fileGroups.add(brokerFileGroup2);

        // file status
        fileStatusesList = Lists.newArrayList();
        fileStatusList = Lists.newArrayList();
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file1", false, 268435456, false));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file2", false, 10, false));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file3", false, 10, false));
        fileStatusesList.add(fileStatusList);

        List<TBrokerFileStatus> fileStatusList2 = Lists.newArrayList();
        fileStatusList2.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file4", false, 10, true));
        fileStatusList2.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file5", false, 10, true));
        fileStatusesList.add(fileStatusList2);

        analyzer = new Analyzer(Catalog.getCurrentCatalog(), new ConnectContext());
        descTable = analyzer.getDescTbl();
        tupleDesc = descTable.createTupleDescriptor("DestTableTuple");
        scanNode = new FileScanNode(new PlanNodeId(0), tupleDesc, "FileScanNode", fileStatusesList, 5);
        scanNode.setLoadInfo(jobId, txnId, table, brokerDesc, fileGroups, true, loadParallelInstanceNum);
        scanNode.setUseVectorized(true);
        scanNode.init(analyzer);
        scanNode.finalize(analyzer);

        // check
        locationsList = scanNode.getScanRangeLocations(0);
        Assert.assertEquals(4, locationsList.size());
        int group1RangesNum = 0;
        int group2RangesNum = 0;
        for (TScanRangeLocations locations : locationsList) {
            List<TBrokerRangeDesc> rangeDescs = locations.scan_range.broker_scan_range.ranges;
            String path = rangeDescs.get(0).path;
            if (path.endsWith("file1") || path.endsWith("file2") || path.endsWith("file3")) {
                Assert.assertEquals(1, rangeDescs.size());
                ++group1RangesNum;
            } else if (path.endsWith("file4") || path.endsWith("file5")) {
                Assert.assertEquals(2, rangeDescs.size());
                ++group2RangesNum;
            }
        }
        Assert.assertEquals(3, group1RangesNum);
        Assert.assertEquals(1, group2RangesNum);

        // case 4
        // 2 parquet file and one is very large
        // result: 2 ranges

        // file groups
        fileGroups = Lists.newArrayList();
        files = Lists.newArrayList("hdfs://127.0.0.1:9001/file1", "hdfs://127.0.0.1:9001/file2");
        desc = new DataDescription("testTable", null, files, columnNames, null, null, "parquet", false, null);
        brokerFileGroup = new BrokerFileGroup(desc);
        Deencapsulation.setField(brokerFileGroup, "columnSeparator", "\t");
        Deencapsulation.setField(brokerFileGroup, "rowDelimiter", "\n");
        Deencapsulation.setField(brokerFileGroup, "fileFormat", "parquet");
        fileGroups.add(brokerFileGroup);

        // file status
        fileStatusesList = Lists.newArrayList();
        fileStatusList = Lists.newArrayList();
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file1", false, 268435456000L, false));
        fileStatusList.add(new TBrokerFileStatus("hdfs://127.0.0.1:9001/file2", false, 10, false));
        fileStatusesList.add(fileStatusList);

        analyzer = new Analyzer(Catalog.getCurrentCatalog(), new ConnectContext());
        descTable = analyzer.getDescTbl();
        tupleDesc = descTable.createTupleDescriptor("DestTableTuple");
        scanNode = new FileScanNode(new PlanNodeId(0), tupleDesc, "FileScanNode", fileStatusesList, 4);
        scanNode.setLoadInfo(jobId, txnId, table, brokerDesc, fileGroups, true, loadParallelInstanceNum);
        scanNode.setUseVectorized(true);
        scanNode.init(analyzer);
        scanNode.finalize(analyzer);

        // check
        locationsList = scanNode.getScanRangeLocations(0);
        System.out.println(locationsList);
        Assert.assertEquals(2, locationsList.size());
    }
}