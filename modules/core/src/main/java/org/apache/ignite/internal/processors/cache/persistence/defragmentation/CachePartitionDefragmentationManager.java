/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.defragmentation;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.configuration.DataPageEvictionMode;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.metric.IoStatisticsHolderNoOp;
import org.apache.ignite.internal.pagemem.PageIdAllocator;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.store.PageStore;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.CacheType;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.IgniteCacheOffheapManager.CacheDataStore;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRow;
import org.apache.ignite.internal.processors.cache.persistence.CheckpointState;
import org.apache.ignite.internal.processors.cache.persistence.DataRegion;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheOffheapManager;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheOffheapManager.GridCacheDataStore;
import org.apache.ignite.internal.processors.cache.persistence.checkpoint.CheckpointManager;
import org.apache.ignite.internal.processors.cache.persistence.checkpoint.CheckpointTimeoutLock;
import org.apache.ignite.internal.processors.cache.persistence.checkpoint.LightweightCheckpointManager;
import org.apache.ignite.internal.processors.cache.persistence.file.FilePageStoreFactory;
import org.apache.ignite.internal.processors.cache.persistence.file.FilePageStoreManager;
import org.apache.ignite.internal.processors.cache.persistence.freelist.AbstractFreeList;
import org.apache.ignite.internal.processors.cache.persistence.freelist.SimpleDataRow;
import org.apache.ignite.internal.processors.cache.persistence.pagemem.PageMemoryEx;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PagePartitionMetaIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PagePartitionMetaIOV3;
import org.apache.ignite.internal.processors.cache.tree.AbstractDataLeafIO;
import org.apache.ignite.internal.processors.cache.tree.CacheDataTree;
import org.apache.ignite.internal.processors.cache.tree.DataRow;
import org.apache.ignite.internal.processors.cache.tree.PendingEntriesTree;
import org.apache.ignite.internal.processors.cache.tree.PendingRow;
import org.apache.ignite.internal.processors.query.GridQueryIndexing;
import org.apache.ignite.internal.processors.query.GridQueryProcessor;
import org.apache.ignite.internal.util.collection.IntHashMap;
import org.apache.ignite.internal.util.collection.IntMap;
import org.apache.ignite.internal.util.future.GridCompoundFuture;
import org.apache.ignite.internal.util.future.GridFinishedFuture;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.lang.IgniteOutClosure;
import org.apache.ignite.maintenance.MaintenanceRegistry;

import static java.util.stream.StreamSupport.stream;
import static org.apache.ignite.internal.pagemem.PageIdAllocator.FLAG_DATA;
import static org.apache.ignite.internal.pagemem.PageIdAllocator.FLAG_IDX;
import static org.apache.ignite.internal.processors.cache.persistence.CheckpointState.FINISHED;
import static org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager.DEFRAGMENTATION_MAPPING_REGION_NAME;
import static org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager.DEFRAGMENTATION_PART_REGION_NAME;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.batchRenameDefragmentedCacheGroupPartitions;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.defragmentedIndexTmpFile;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.defragmentedPartFile;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.defragmentedPartMappingFile;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.defragmentedPartTmpFile;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.renameTempIndexFile;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.renameTempPartitionFile;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.skipAlreadyDefragmentedCacheGroup;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.skipAlreadyDefragmentedPartition;
import static org.apache.ignite.internal.processors.cache.persistence.defragmentation.DefragmentationFileUtils.writeDefragmentationCompletionMarker;

/**
 * Defragmentation manager is the core class that contains main defragmentation procedure.
 */
public class CachePartitionDefragmentationManager {
    /** */
    public static final String DEFRAGMENTATION_MNTC_TASK_NAME = "defragmentationMaintenanceTask";

    /** */
    private final Set<Integer> cacheGroupsForDefragmentation;

    /** Cache shared context. */
    private final GridCacheSharedContext<?, ?> sharedCtx;

    /** Maintenance registry. */
    private final MaintenanceRegistry mntcReg;

    /** Logger. */
    private final IgniteLogger log;

    /** Database shared manager. */
    private final GridCacheDatabaseSharedManager dbMgr;

    /** File page store manager. */
    private final FilePageStoreManager filePageStoreMgr;

    /**
     * Checkpoint for specific defragmentation regions which would store the data to new partitions
     * during the defragmentation.
     */
    private final LightweightCheckpointManager defragmentationCheckpoint;

    /** Default checkpoint for current node. */
    private final CheckpointManager nodeCheckpoint;

    /** Page size. */
    private final int pageSize;

    /** */
    private final DataRegion partDataRegion;

    /** */
    private final DataRegion mappingDataRegion;

    /**
     * @param cacheGrpIds
     * @param sharedCtx Cache shared context.
     * @param dbMgr Database manager.
     * @param filePageStoreMgr File page store manager.
     * @param nodeCheckpoint Default checkpoint for this node.
     * @param defragmentationCheckpoint Specific checkpoint for defragmentation.
     * @param pageSize Page size.
     */
    public CachePartitionDefragmentationManager(
        List<Integer> cacheGrpIds,
        GridCacheSharedContext<?, ?> sharedCtx,
        GridCacheDatabaseSharedManager dbMgr,
        FilePageStoreManager filePageStoreMgr,
        CheckpointManager nodeCheckpoint,
        LightweightCheckpointManager defragmentationCheckpoint,
        int pageSize
    ) throws IgniteCheckedException {
        cacheGroupsForDefragmentation = new HashSet<>(cacheGrpIds);

        this.dbMgr = dbMgr;
        this.filePageStoreMgr = filePageStoreMgr;
        this.pageSize = pageSize;
        this.sharedCtx = sharedCtx;

        this.mntcReg = sharedCtx.kernalContext().maintenanceRegistry();
        this.log = sharedCtx.logger(getClass());
        this.defragmentationCheckpoint = defragmentationCheckpoint;
        this.nodeCheckpoint = nodeCheckpoint;

        partDataRegion = dbMgr.dataRegion(DEFRAGMENTATION_PART_REGION_NAME);
        mappingDataRegion = dbMgr.dataRegion(DEFRAGMENTATION_MAPPING_REGION_NAME);
    }

    /** */
    public void executeDefragmentation() throws IgniteCheckedException {
        log.info("Defragmentation started.");

        try {
            // Checkpointer must be enabled so all pages on disk are in their latest valid state.
            dbMgr.resumeWalLogging();

            dbMgr.onStateRestored(null);

            nodeCheckpoint.forceCheckpoint("beforeDefragmentation", null).futureFor(FINISHED).get();

            sharedCtx.wal().onDeActivate(sharedCtx.kernalContext());

            // Now the actual process starts.
            TreeIterator treeIter = new TreeIterator(pageSize);

            IgniteInternalFuture<?> idxDfrgFut = null;
            DataPageEvictionMode prevPageEvictionMode = null;

            for (CacheGroupContext oldGrpCtx : sharedCtx.cache().cacheGroups()) {
                if (!oldGrpCtx.userCache())
                    continue;

                int grpId = oldGrpCtx.groupId();

                if (!cacheGroupsForDefragmentation.isEmpty() && !cacheGroupsForDefragmentation.contains(grpId))
                    continue;

                File workDir = filePageStoreMgr.cacheWorkDir(oldGrpCtx.sharedGroup(), oldGrpCtx.cacheOrGroupName());

                if (skipAlreadyDefragmentedCacheGroup(workDir, grpId, log))
                    continue;

                GridCacheOffheapManager offheap = (GridCacheOffheapManager)oldGrpCtx.offheap();

                List<CacheDataStore> oldCacheDataStores = stream(offheap.cacheDataStores().spliterator(), false)
                    .filter(store -> {
                        try {
                            return filePageStoreMgr.exists(grpId, store.partId());
                        }
                        catch (IgniteCheckedException e) {
                            throw new IgniteException(e);
                        }
                    })
                    .collect(Collectors.toList());

                if (workDir != null && !oldCacheDataStores.isEmpty()) {
                    // We can't start defragmentation of new group on the region that has wrong eviction mode.
                    // So waiting of the previous cache group defragmentation is inevitable.
                    DataPageEvictionMode curPageEvictionMode = oldGrpCtx.dataRegion().config().getPageEvictionMode();

                    if (prevPageEvictionMode == null || prevPageEvictionMode != curPageEvictionMode) {
                        prevPageEvictionMode = curPageEvictionMode;

                        partDataRegion.config().setPageEvictionMode(curPageEvictionMode);

                        if (idxDfrgFut != null)
                            idxDfrgFut.get();
                    }

                    IntMap<CacheDataStore> cacheDataStores = new IntHashMap<>();

                    for (CacheDataStore store : offheap.cacheDataStores()) {
                        // Tree can be null for not yet initialized partitions.
                        // This would mean that these partitions are empty.
                        assert store.tree() == null || store.tree().groupId() == grpId;

                        if (store.tree() != null)
                            cacheDataStores.put(store.partId(), store);
                    }

                    dbMgr.checkpointedDataRegions().remove(oldGrpCtx.dataRegion());

                    // Another cheat. Ttl cleanup manager knows too much shit.
                    oldGrpCtx.caches().stream()
                        .filter(cacheCtx -> cacheCtx.groupId() == grpId)
                        .forEach(cacheCtx -> cacheCtx.ttl().unregister());

                    // Technically wal is already disabled, but "PageHandler.isWalDeltaRecordNeeded" doesn't care and
                    // WAL records will be allocated anyway just to be ignored later if we don't disable WAL for
                    // cache group explicitly.
                    oldGrpCtx.localWalEnabled(false, false);

                    boolean encrypted = oldGrpCtx.config().isEncryptionEnabled();

                    FilePageStoreFactory pageStoreFactory = filePageStoreMgr.getPageStoreFactory(grpId, encrypted);

                    createIndexPageStore(grpId, workDir, pageStoreFactory, partDataRegion, val -> {
                    }); //TODO Allocated tracker.

                    GridCompoundFuture<Object, Object> cmpFut = new GridCompoundFuture<>();

                    PageMemoryEx oldPageMem = (PageMemoryEx)oldGrpCtx.dataRegion().pageMemory();

                    CacheGroupContext newGrpCtx = new CacheGroupContext(
                        sharedCtx,
                        grpId,
                        oldGrpCtx.receivedFrom(),
                        CacheType.USER,
                        oldGrpCtx.config(),
                        oldGrpCtx.affinityNode(),
                        partDataRegion,
                        oldGrpCtx.cacheObjectContext(),
                        null,
                        null,
                        oldGrpCtx.localStartVersion(),
                        true,
                        false,
                        true
                    );

                    defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();

                    try {
                        // This will initialize partition meta in index partition - meta tree and reuse list.
                        newGrpCtx.start();
                    }
                    finally {
                        defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();
                    }

                    IntMap<LinkMap> linkMapByPart = new IntHashMap<>();

                    for (CacheDataStore oldCacheDataStore : oldCacheDataStores) {
                        int partId = oldCacheDataStore.partId();

                        PartitionContext partCtx = new PartitionContext(
                            workDir,
                            grpId,
                            partId,
                            partDataRegion,
                            mappingDataRegion,
                            oldGrpCtx,
                            newGrpCtx,
                            cacheDataStores.get(partId),
                            pageStoreFactory
                        );

                        if (skipAlreadyDefragmentedPartition(workDir, grpId, partId, log)) {
                            partCtx.createPageStore(
                                () -> defragmentedPartMappingFile(workDir, partId).toPath(),
                                partCtx.mappingPagesAllocated,
                                partCtx.mappingPageMemory
                            );

                            linkMapByPart.put(partId, partCtx.createLinkMapTree(false));

                            continue;
                        }

                        partCtx.createPageStore(
                            () -> defragmentedPartMappingFile(workDir, partId).toPath(),
                            partCtx.mappingPagesAllocated,
                            partCtx.mappingPageMemory
                        );

                        linkMapByPart.put(partId, partCtx.createLinkMapTree(true));

                        partCtx.createPageStore(
                            () -> defragmentedPartTmpFile(workDir, partId).toPath(),
                            partCtx.partPagesAllocated,
                            partCtx.partPageMemory
                        );

                        partCtx.createNewCacheDataStore(offheap);

                        copyPartitionData(partCtx, treeIter);

                        IgniteInClosure<IgniteInternalFuture<?>> cpLsnr = fut -> {
                            if (fut.error() != null)
                                return;

                            PageStore oldPageStore = null;

                            try {
                                oldPageStore = filePageStoreMgr.getStore(grpId, partId);
                            }
                            catch (IgniteCheckedException ignore) {
                            }

                            if (log.isDebugEnabled()) {
                                log.debug(S.toString(
                                    "Partition defragmented",
                                    "grpId", grpId, false,
                                    "partId", partId, false,
                                    "oldPages", oldPageStore.pages(), false,
                                    "newPages", partCtx.partPagesAllocated.get() + 1, false,
                                    "mappingPages", partCtx.mappingPagesAllocated.get() + 1, false,
                                    "pageSize", pageSize, false,
                                    "partFile", defragmentedPartFile(workDir, partId).getName(), false,
                                    "workDir", workDir, false
                                ));
                            }

                            oldPageMem.invalidate(grpId, partId);

                            partCtx.partPageMemory.invalidate(grpId, partId);

                            DefragmentationPageReadWriteManager pageMgr = (DefragmentationPageReadWriteManager)partCtx.partPageMemory.pageManager();

                            pageMgr.pageStoreMap().removePageStore(grpId, partId); // Yes, it'll be invalid in a second.

                            renameTempPartitionFile(workDir, partId);
                        };

                        GridFutureAdapter<?> cpFut = defragmentationCheckpoint
                            .forceCheckpoint("partition defragmented", null)
                            .futureFor(CheckpointState.FINISHED);

                        cpFut.listen(cpLsnr);

                        cmpFut.add((IgniteInternalFuture<Object>)cpFut);
                    }

                    // A bit too general for now, but I like it more then saving only the last checkpoint future.
                    cmpFut.markInitialized().get();

                    idxDfrgFut = new GridFinishedFuture<>();

                    if (filePageStoreMgr.hasIndexStore(grpId)) {
                        defragmentIndexPartition(oldGrpCtx, newGrpCtx, linkMapByPart);

                        idxDfrgFut = defragmentationCheckpoint
                            .forceCheckpoint("index defragmented", null)
                            .futureFor(CheckpointState.FINISHED);
                    }

                    idxDfrgFut.listen(fut -> {
                        oldPageMem.invalidate(grpId, PageIdAllocator.INDEX_PARTITION);

                        PageMemoryEx partPageMem = (PageMemoryEx)partDataRegion.pageMemory();

                        partPageMem.invalidate(grpId, PageIdAllocator.INDEX_PARTITION);

                        DefragmentationPageReadWriteManager pageMgr = (DefragmentationPageReadWriteManager)partPageMem.pageManager();

                        pageMgr.pageStoreMap().removePageStore(grpId, PageIdAllocator.INDEX_PARTITION);

                        PageMemoryEx mappingPageMem = (PageMemoryEx)mappingDataRegion.pageMemory();

                        pageMgr = (DefragmentationPageReadWriteManager)mappingPageMem.pageManager();

                        pageMgr.pageStoreMap().clear(grpId);

                        renameTempIndexFile(workDir);

                        writeDefragmentationCompletionMarker(filePageStoreMgr.getPageStoreFileIoFactory(), workDir, log);

                        batchRenameDefragmentedCacheGroupPartitions(workDir, log);
                    });
                }

                // I guess we should wait for it?
                if (idxDfrgFut != null)
                    idxDfrgFut.get();
            }

            mntcReg.unregisterMaintenanceTask(DEFRAGMENTATION_MNTC_TASK_NAME);

            log.info("Defragmentation completed. All partitions are defragmented.");
        }
        finally {
            defragmentationCheckpoint.stop(true);
        }
    }

    /** */
    public void createIndexPageStore(
        int grpId,
        File workDir,
        FilePageStoreFactory pageStoreFactory,
        DataRegion partRegion,
        LongConsumer allocatedTracker
    ) throws IgniteCheckedException {
        // Index partition file has to be deleted before we begin, otherwise there's a chance of reading corrupted file.
        // There is a time period when index is already defragmented but marker file is not created yet. If node is
        // failed in that time window then index will be deframented once again. That's fine, situation is rare but code
        // to fix that would add unnecessary complications.
        U.delete(defragmentedIndexTmpFile(workDir));

        PageStore idxPageStore;

        defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();
        try {
            idxPageStore = pageStoreFactory.createPageStore(
                FLAG_IDX,
                () -> defragmentedIndexTmpFile(workDir).toPath(),
                allocatedTracker
            );
        }
        finally {
            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();
        }

        idxPageStore.sync();

        PageMemoryEx partPageMem = (PageMemoryEx)partRegion.pageMemory();

        DefragmentationPageReadWriteManager partMgr = (DefragmentationPageReadWriteManager)partPageMem.pageManager();

        partMgr.pageStoreMap().addPageStore(grpId, PageIdAllocator.INDEX_PARTITION, idxPageStore);
    }

    /**
     * Defragmentate partition.
     *
     * @param partCtx
     * @param treeIter
     * @throws IgniteCheckedException If failed.
     */
    private void copyPartitionData(
        PartitionContext partCtx,
        TreeIterator treeIter
    ) throws IgniteCheckedException {
        CacheDataTree tree = partCtx.oldCacheDataStore.tree();

        CacheDataTree newTree = partCtx.newCacheDataStore.tree();
        PendingEntriesTree newPendingTree = partCtx.newCacheDataStore.pendingTree();
        AbstractFreeList<CacheDataRow> freeList = partCtx.newCacheDataStore.getCacheStoreFreeList();

        long cpLockThreshold = 150L;

        defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();

        try {
            AtomicLong lastCpLockTs = new AtomicLong(System.currentTimeMillis());
            AtomicInteger entriesProcessed = new AtomicInteger();

            treeIter.iterate(tree, partCtx.cachePageMemory, (tree0, io, pageAddr, idx) -> {
                if (System.currentTimeMillis() - lastCpLockTs.get() >= cpLockThreshold) {
                    defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();

                    defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();

                    lastCpLockTs.set(System.currentTimeMillis());
                }

                AbstractDataLeafIO leafIo = (AbstractDataLeafIO)io;
                CacheDataRow row = tree.getRow(io, pageAddr, idx);

                int cacheId = row.cacheId();

                // Reuse row that we just read.
                row.link(0);

                // "insertDataRow" will corrupt page memory if we don't do this.
                if (row instanceof DataRow && !partCtx.oldGrpCtx.storeCacheIdInDataPage())
                    ((DataRow)row).cacheId(CU.UNDEFINED_CACHE_ID);

                freeList.insertDataRow(row, IoStatisticsHolderNoOp.INSTANCE);

                // Put it back.
                if (row instanceof DataRow)
                    ((DataRow)row).cacheId(cacheId);

                newTree.putx(row);

                long newLink = row.link();

                partCtx.linkMap.put(leafIo.getLink(pageAddr, idx), newLink);

                if (row.expireTime() != 0)
                    newPendingTree.putx(new PendingRow(cacheId, row.expireTime(), newLink));

                entriesProcessed.incrementAndGet();

                return true;
            });

            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();

            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();

            freeList.saveMetadata(IoStatisticsHolderNoOp.INSTANCE);

            copyCacheMetadata(partCtx);
        }
        finally {
            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();
        }
    }

    /** */
    private void copyCacheMetadata(
        PartitionContext partCtx
    ) throws IgniteCheckedException {
        // Same for all page memories. Why does it need to be in PageMemory?
        long partMetaPageId = partCtx.cachePageMemory.partitionMetaPageId(partCtx.grpId, partCtx.partId);

        long oldPartMetaPage = partCtx.cachePageMemory.acquirePage(partCtx.grpId, partMetaPageId);

        try {
            long oldPartMetaPageAddr = partCtx.cachePageMemory.readLock(partCtx.grpId, partMetaPageId, oldPartMetaPage);

            try {
                PagePartitionMetaIO oldPartMetaIo = PageIO.getPageIO(oldPartMetaPageAddr);

                // Newer meta versions may contain new data that we don't copy during defragmentation.
                assert Arrays.asList(1, 2, 3).contains(oldPartMetaIo.getVersion())
                    : "IO version " + oldPartMetaIo.getVersion() + " is not supported by current defragmentation algorithm." +
                    " Please implement copying of all data added in new version.";

                long newPartMetaPage = partCtx.partPageMemory.acquirePage(partCtx.grpId, partMetaPageId);

                try {
                    long newPartMetaPageAddr = partCtx.partPageMemory.writeLock(partCtx.grpId, partMetaPageId, newPartMetaPage);

                    try {
                        PagePartitionMetaIOV3 newPartMetaIo = PageIO.getPageIO(newPartMetaPageAddr);

                        // Copy partition state.
                        byte partState = oldPartMetaIo.getPartitionState(oldPartMetaPageAddr);
                        newPartMetaIo.setPartitionState(newPartMetaPageAddr, partState);

                        // Copy cache size for single cache group.
                        long size = oldPartMetaIo.getSize(oldPartMetaPageAddr);
                        newPartMetaIo.setSize(newPartMetaPageAddr, size);

                        // Copy update counter value.
                        long updateCntr = oldPartMetaIo.getUpdateCounter(oldPartMetaPageAddr);
                        newPartMetaIo.setUpdateCounter(newPartMetaPageAddr, updateCntr);

                        // Copy global remove Id.
                        long rmvId = oldPartMetaIo.getGlobalRemoveId(oldPartMetaPageAddr);
                        newPartMetaIo.setGlobalRemoveId(newPartMetaPageAddr, rmvId);

                        // Copy cache sizes for shared cache group.
                        long oldCountersPageId = oldPartMetaIo.getCountersPageId(oldPartMetaPageAddr);
                        if (oldCountersPageId != 0L) {
                            Map<Integer, Long> sizes = GridCacheOffheapManager.readSharedGroupCacheSizes(
                                partCtx.cachePageMemory,
                                partCtx.grpId,
                                oldCountersPageId
                            );

                            long newCountersPageId = GridCacheOffheapManager.writeSharedGroupCacheSizes(
                                partCtx.partPageMemory,
                                partCtx.grpId,
                                0L,
                                partCtx.partId,
                                sizes
                            );

                            newPartMetaIo.setCountersPageId(newPartMetaPageAddr, newCountersPageId);
                        }

                        // Copy counter gaps.
                        long oldGapsLink = oldPartMetaIo.getGapsLink(oldPartMetaPageAddr);
                        if (oldGapsLink != 0L) {
                            byte[] gapsBytes = partCtx.oldCacheDataStore.partStorage().readRow(oldGapsLink);

                            SimpleDataRow gapsDataRow = new SimpleDataRow(partCtx.partId, gapsBytes);

                            partCtx.newCacheDataStore.partStorage().insertDataRow(gapsDataRow, IoStatisticsHolderNoOp.INSTANCE);

                            newPartMetaIo.setGapsLink(newPartMetaPageAddr, gapsDataRow.link());
                        }

                        // Encryption stuff.
                        newPartMetaIo.setEncryptedPageCount(newPartMetaPageAddr, 0);
                        newPartMetaIo.setEncryptedPageIndex(newPartMetaPageAddr, 0);
                    }
                    finally {
                        partCtx.partPageMemory.writeUnlock(partCtx.grpId, partMetaPageId, newPartMetaPage, null, true);
                    }
                }
                finally {
                    partCtx.partPageMemory.releasePage(partCtx.grpId, partMetaPageId, newPartMetaPage);
                }
            }
            finally {
                partCtx.cachePageMemory.readUnlock(partCtx.grpId, partMetaPageId, oldPartMetaPage);
            }
        }
        finally {
            partCtx.cachePageMemory.releasePage(partCtx.grpId, partMetaPageId, oldPartMetaPage);
        }
    }

    /**
     * Defragmentate indexing partition.
     *
     * @param grpCtx
     * @param mappingByPartition
     *
     * @throws IgniteCheckedException If failed.
     */
    private void defragmentIndexPartition(
        CacheGroupContext grpCtx,
        CacheGroupContext newCtx,
        IntMap<LinkMap> mappingByPartition
    ) throws IgniteCheckedException {
        GridQueryProcessor query = grpCtx.caches().get(0).kernalContext().query();

        if (!query.moduleEnabled())
            return;

        final GridQueryIndexing idx = query.getIndexing();

        CheckpointTimeoutLock cpLock = defragmentationCheckpoint.checkpointTimeoutLock();

        idx.defragment(
            grpCtx,
            newCtx,
            (PageMemoryEx)partDataRegion.pageMemory(),
            mappingByPartition,
            cpLock
        );
    }

    /** */
    @SuppressWarnings("PublicField")
    private class PartitionContext {
        /** */
        public final File workDir;

        /** */
        public final int grpId;

        /** */
        public final int partId;

        /** */
        public final DataRegion cacheDataRegion;

        /** */
        public final PageMemoryEx cachePageMemory;

        /** */
        public final PageMemoryEx partPageMemory;

        /** */
        public final PageMemoryEx mappingPageMemory;

        /** */
        public final CacheGroupContext oldGrpCtx;

        /** */
        public final CacheGroupContext newGrpCtx;

        /** */
        public final CacheDataStore oldCacheDataStore;

        /** */
        private GridCacheDataStore newCacheDataStore;

        /** */
        public final FilePageStoreFactory pageStoreFactory;

        /** */
        public final AtomicLong partPagesAllocated = new AtomicLong();

        /** */
        public final AtomicLong mappingPagesAllocated = new AtomicLong();

        /** */
        private LinkMap linkMap;

        /** */
        public PartitionContext(
            File workDir,
            int grpId,
            int partId,
            DataRegion partDataRegion,
            DataRegion mappingDataRegion,
            CacheGroupContext oldGrpCtx,
            CacheGroupContext newGrpCtx,
            CacheDataStore oldCacheDataStore,
            FilePageStoreFactory pageStoreFactory
        ) {
            this.workDir = workDir;
            this.grpId = grpId;
            this.partId = partId;
            cacheDataRegion = oldGrpCtx.dataRegion();

            cachePageMemory = (PageMemoryEx)cacheDataRegion.pageMemory();
            partPageMemory = (PageMemoryEx)partDataRegion.pageMemory();
            mappingPageMemory = (PageMemoryEx)mappingDataRegion.pageMemory();

            this.oldGrpCtx = oldGrpCtx;
            this.newGrpCtx = newGrpCtx;
            this.oldCacheDataStore = oldCacheDataStore;
            this.pageStoreFactory = pageStoreFactory;
        }

        /** */
        public PageStore createPageStore(IgniteOutClosure<Path> pathProvider, AtomicLong pagesAllocated, PageMemoryEx pageMemory) throws IgniteCheckedException {
            PageStore partPageStore;

            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();
            try {
                partPageStore = pageStoreFactory.createPageStore(
                    FLAG_DATA,
                    pathProvider,
                    pagesAllocated::addAndGet
                );
            }
            finally {
                defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();
            }

            partPageStore.sync();

            DefragmentationPageReadWriteManager pageMgr = (DefragmentationPageReadWriteManager)pageMemory.pageManager();

            pageMgr.pageStoreMap().addPageStore(grpId, partId, partPageStore);

            return partPageStore;
        }

        /** */
        public LinkMap createLinkMapTree(boolean initNew) throws IgniteCheckedException {
            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();

            try {
                long mappingMetaPageId = initNew
                    ? mappingPageMemory.allocatePage(grpId, partId, FLAG_DATA)
                    : PageIdUtils.pageId(partId, FLAG_DATA, LinkMap.META_PAGE_IDX);

                assert PageIdUtils.pageIndex(mappingMetaPageId) == LinkMap.META_PAGE_IDX
                    : PageIdUtils.toDetailString(mappingMetaPageId);

                linkMap = new LinkMap(newGrpCtx, mappingPageMemory, mappingMetaPageId, initNew);
            }
            finally {
                defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();
            }

            return linkMap;
        }

        /** */
        public void createNewCacheDataStore(GridCacheOffheapManager offheap) {
            GridCacheDataStore newCacheDataStore = offheap.createGridCacheDataStore(
                newGrpCtx,
                partId,
                true,
                log
            );

            defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadLock();

            try {
                newCacheDataStore.init();
            }
            finally {
                defragmentationCheckpoint.checkpointTimeoutLock().checkpointReadUnlock();
            }

            this.newCacheDataStore = newCacheDataStore;
        }
    }
}
